package com.jonathancruzate.musegadgetshield;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One Noise link session: WebSocket, Noise XX handshake, the control
 * stream, and the request/response machinery for chat and voice; a port
 * of the SDK's link_client.py (LinkSession, minus the realtime voice
 * call and the generic streaming API, which stay out of scope here).
 *
 * A session is single-use: once {@link #run()} returns or throws, build a
 * new one (the service loop does).
 */
public final class LinkSession {
    public static final String CLIENT_VERSION = "0.11.2";
    // Registration shape (checked 2026-11-09 against the SDK): the
    // SDK's register_params() hardcodes platform "linux", family
    // "homehub", model "linux" for every non-firmware device — the
    // Windows PC gadget registers identically — and linux/AGENTS.md
    // mandates exactly these values (family "link" is the ESP32
    // firmware family; the server pushes firmware OTAs to it, so it
    // must never be used here). There is no android/tv platform or
    // family the backend documents or the SDK supports, so this APK
    // — the gadget itself, holding identity, pairing and link on the
    // TV — registers with the homehub shape; what presents it to the
    // fleet as the Shield TV is display_name "Shield TV", node
    // homelink-fa76f8, and BLE name MuseGadget-FA76F8.
    public static final String LINK_PLATFORM = "linux";
    public static final String LINK_DEVICE_FAMILY = "homehub";
    public static final String LINK_MODEL_ID = "linux";
    private static final String APP_ID = "musegadget";
    private static final String CONTROL_PATH = "/link-control";

    private static final double REQUEST_TIMEOUT_SECONDS = 60;
    private static final int BUFFERED_RESPONSE_LIMIT = 1024 * 1024;
    private static final int REQUEST_SINGLE_SHOT_LIMIT = 48 * 1024;
    private static final int RESPONSE_SINGLE_SHOT_LIMIT = 64 * 1024;
    private static final int STREAM_CHUNK_BYTES = 16 * 1024;
    private static final int TTS_BUFFER_LIMIT = 8 * 1024 * 1024;
    private static final int MAX_VOICE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;
    private static final int HANDSHAKE_TIMEOUT_SECONDS = 20;
    private static final double REGISTER_TIMEOUT_SECONDS = 30;
    private static final int INVOKE_CONCURRENCY = 4;

    public enum Outcome { COMPLETED, UNPAIRED }

    public enum ErrorKind {
        CONNECT, AUTHORIZATION, FORBIDDEN, TRANSPORT, NOISE_HANDSHAKE,
        REGISTER, REGISTER_TIMEOUT, TOO_LARGE, PROTOCOL, INTERNAL
    }

    public static final class LinkError extends Exception {
        public final ErrorKind kind;
        public LinkError(ErrorKind kind, String message) { super(message); this.kind = kind; }
        public LinkError(ErrorKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    public static final class LinkSessionException extends RuntimeException {
        public LinkSessionException(String message) { super(message); }
        public LinkSessionException(String message, Throwable cause) { super(message, cause); }
    }

    /** One command the device advertises; handlers run off the reader thread. */
    public interface CommandHandler {
        /**
         * Run the command and return its result dict, SDK executor style:
         * {@code {"ok": true, "payload": {...}}} or
         * {@code {"ok": false, "error": "..."}}.
         */
        Map<String, Object> handle(Map<String, Object> params) throws Exception;
    }

    public static final class Command {
        public final String name;
        public final String description;
        public final String usage;
        public final CommandHandler handler;

        public Command(String name, String description, String usage, CommandHandler handler) {
            this.name = name;
            this.description = description;
            this.usage = usage;
            this.handler = handler;
        }
    }

    public interface Callbacks {
        void log(String message);
        void onUnpaired();
    }

    /** Opens the WebSocket; the SDK's LinkSession takes the same seam for tests. */
    public interface Connector {
        WsConnection connect(URI url, Map<String, String> headers,
                WsClient.Listener listener) throws IOException;
    }

    public static Connector defaultConnector() {
        return new Connector() {
            @Override public WsConnection connect(URI url, Map<String, String> headers,
                    WsClient.Listener listener) throws IOException {
                return WsClient.connect(url, headers, 20000, 20000, 20, listener);
            }
        };
    }

    // -- configuration/state ---------------------------------------------------

    private final String vmId;
    private final String vmAuthToken;
    private final String noiseHost;
    private final String nodeId;
    private final String displayName;
    private final List<Command> commands;
    private final Callbacks callbacks;
    private final Connector connector;

    private final Object sendLock = new Object();
    private volatile WsConnection ws;
    private volatile NoiseTransport transport;
    private volatile boolean closed = false;
    private final AtomicBoolean closeCalled = new AtomicBoolean(false);

    private final CompletableFuture<byte[]> msg1Future = new CompletableFuture<>();
    private volatile boolean noiseReady = false;

    private final Map<Long, HttpStream> streams = new ConcurrentHashMap<>();
    private final Map<Long, PendingRequest> pending = new ConcurrentHashMap<>();

    private volatile long controlStreamId = -1;
    private volatile String registerId;
    private final CompletableFuture<Boolean> registrationFuture = new CompletableFuture<>();
    private final ControlDecoder controlDecoder = new ControlDecoder();
    private volatile Outcome outcome;
    private volatile LinkError failure;
    private volatile long registeredAtMs;
    private volatile boolean registered = false;

    private static java.util.concurrent.ThreadFactory daemonFactory(final String name) {
        return new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    private final ExecutorService invokeExecutor =
            Executors.newCachedThreadPool(daemonFactory("LinkSession-invoke"));
    private final java.util.concurrent.Semaphore invokeSlots =
            new java.util.concurrent.Semaphore(INVOKE_CONCURRENCY);

    public LinkSession(String vmId, String vmAuthToken, String noiseHost, String nodeId,
            String displayName, List<Command> commands, Callbacks callbacks) {
        this(vmId, vmAuthToken, noiseHost, nodeId, displayName, commands, callbacks,
                defaultConnector());
    }

    public LinkSession(String vmId, String vmAuthToken, String noiseHost, String nodeId,
            String displayName, List<Command> commands, Callbacks callbacks,
            Connector connector) {
        this.vmId = vmId;
        this.vmAuthToken = vmAuthToken;
        this.noiseHost = noiseHost;
        this.nodeId = nodeId;
        this.displayName = displayName;
        this.commands = commands != null ? commands : new ArrayList<Command>();
        this.callbacks = callbacks;
        this.connector = connector;
    }

    public boolean isRegistered() { return registered; }
    public long registeredAtMs() { return registeredAtMs; }
    public boolean isClosed() { return closed; }

    // -- session lifecycle --------------------------------------------------------

    /** Open the socket, handshake, and register. Throws {@link LinkError}. */
    public void open() throws LinkError {
        // noiseHost is a bare host in production; an explicit ws:// or
        // wss:// prefix is honored for local test rigs.
        String base = noiseHost.contains("://") ? noiseHost : "wss://" + noiseHost;
        String url = base + "/v1/noise?vm_id=" + uriComponent(vmId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + vmAuthToken);
        final WsConnection client;
        try {
            ExecutorService connectExecutor =
                    Executors.newSingleThreadExecutor(daemonFactory("LinkSession-connect"));
            try {
                final String connectUrl = url;
                final Map<String, String> connectHeaders = headers;
                Future<WsConnection> future = connectExecutor.submit(
                        new java.util.concurrent.Callable<WsConnection>() {
                            @Override public WsConnection call() throws Exception {
                                return connector.connect(
                                        new URI(connectUrl), connectHeaders,
                                        new WsClient.Listener() {
                                            @Override public void onMessage(byte[] message) {
                                                onWsMessage(message);
                                            }
                                            @Override public void onClosed(String reason) {
                                                onWsClosed(reason);
                                            }
                                        });
                            }
                        });
                client = future.get(HANDSHAKE_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
            } finally {
                connectExecutor.shutdownNow();
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WsClient.UpgradeRejectedException) {
                int status = ((WsClient.UpgradeRejectedException) cause).status;
                if (status == 401 || status == 403) {
                    throw new LinkError(ErrorKind.AUTHORIZATION,
                            "the VM rejected the link credentials (HTTP " + status + ")", cause);
                }
            }
            throw new LinkError(ErrorKind.CONNECT, "could not open the link socket: " + cause, cause);
        } catch (TimeoutException e) {
            throw new LinkError(ErrorKind.CONNECT, "link socket connect timed out", e);
        } catch (Exception e) {
            throw new LinkError(ErrorKind.CONNECT, "could not open the link socket: " + e, e);
        }
        this.ws = client;

        // Noise XX handshake: -> e | <- e, ee, s, es | -> s, se
        NoiseXx.NoiseXXInitiator handshake = new NoiseXx.NoiseXXInitiator();
        try {
            handshake.initialize();
            byte[] msg1 = handshake.writeMessage1();
            client.send(msg1);
            byte[] msg2 = msg1Future.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (msg2 == null) throw new LinkError(ErrorKind.NOISE_HANDSHAKE, "socket closed during handshake");
            handshake.readMessage2(msg2);
            client.send(handshake.writeMessage3());
        } catch (LinkError e) {
            closeTransport();
            throw e;
        } catch (TimeoutException e) {
            closeTransport();
            throw new LinkError(ErrorKind.NOISE_HANDSHAKE, "Noise handshake timed out", e);
        } catch (Exception e) {
            closeTransport();
            throw new LinkError(ErrorKind.NOISE_HANDSHAKE, "Noise handshake failed: " + e, e);
        }
        NoiseXx.CipherState[] ciphers = handshake.split();
        transport = new NoiseTransport(ciphers[0], ciphers[1],
                new NoiseTransport.Sender() {
                    @Override public void sendMessage(byte[] message) throws Exception {
                        WsConnection c = ws;
                        if (c == null) throw new IOException("WebSocket is closed");
                        c.send(message);
                    }
                },
                new NoiseTransport.Handler() {
                    @Override public void onRequest(long streamId, NoiseEnvelope.ApplicationRequest request) {
                        onTransportRequest(streamId, request);
                    }
                    @Override public void onResponse(long streamId, NoiseEnvelope.ApplicationResponse response) {
                        onTransportResponse(streamId, response);
                    }
                    @Override public void onBodyChunk(long streamId, NoiseEnvelope.BodyChunk chunk) {
                        onTransportBodyChunk(streamId, chunk);
                    }
                    @Override public void onReset(long streamId, NoiseEnvelope.Reset reset) {
                        onTransportReset(streamId, reset);
                    }
                });
        noiseReady = true;

        // Open the control stream (a never-finished POST /link-control
        // stream) and register on it, as the SDK does.
        try {
            long streamId = transport.allocateStreamId();
            controlStreamId = streamId;
            registerId = UUID.randomUUID().toString();
            synchronized (sendLock) {
                transport.sendRequest(streamId, new NoiseEnvelope.ApplicationRequest(
                        "POST", CONTROL_PATH, new ArrayList<NoiseEnvelope.Header>(),
                        new byte[0], false));
            }
            sendControl(controlRegisterMessage());
            try {
                registrationFuture.get(
                        (long) (REGISTER_TIMEOUT_SECONDS * 1000) + 5000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new LinkError(ErrorKind.REGISTER_TIMEOUT,
                        "the control stream did not answer the register request", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof LinkError) throw (LinkError) cause;
                throw new LinkError(ErrorKind.REGISTER, "link.register failed: " + cause, cause);
            }
            if (failure != null) throw failure;
            if (outcome == Outcome.UNPAIRED) {
                throw new LinkError(ErrorKind.REGISTER, "the gadget was unpaired during registration");
            }
        } catch (LinkError e) {
            closeTransport();
            throw e;
        } catch (LinkSessionException e) {
            closeTransport();
            throw new LinkError(ErrorKind.REGISTER, "link.register failed: " + e.getMessage(), e);
        } catch (Exception e) {
            closeTransport();
            throw new LinkError(ErrorKind.REGISTER, "link.register failed: " + e, e);
        }
        registered = true;
        registeredAtMs = System.currentTimeMillis();
        log("Link session registered as " + nodeId + ".");
    }

    /**
     * Block until the session ends. Returns the outcome; throws {@link
     * LinkError} for failures the service loop should classify.
     */
    public Outcome run() throws LinkError {
        if (!registered) throw new LinkError(ErrorKind.INTERNAL, "run() before open()");
        // The reader thread drives everything; wait for an end condition.
        while (!closed) {
            if (failure != null) throw failure;
            if (outcome != null) return outcome;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (failure != null) throw failure;
        if (outcome != null) return outcome;
        return Outcome.COMPLETED;
    }

    public void close() {
        if (!closeCalled.compareAndSet(false, true)) return;
        closed = true;
        closeTransport();
        invokeExecutor.shutdownNow();
    }

    private void closeTransport() {
        closed = true;
        NoiseTransport t = transport;
        if (t != null) t.close();
        WsConnection c = ws;
        if (c != null) c.close();
        // Wake any waiters so blocked sends fail instead of hanging.
        if (!registrationFuture.isDone()) {
            registrationFuture.completeExceptionally(
                    new LinkSessionException("session closed"));
        }
        for (PendingRequest p : pending.values()) {
            if (!p.future.isDone()) {
                p.future.completeExceptionally(new LinkSessionException("session closed"));
            }
        }
        for (HttpStream s : streams.values()) {
            if (!s.future.isDone()) {
                s.future.completeExceptionally(new LinkSessionException("session closed"));
            }
        }
        if (!msg1Future.isDone()) msg1Future.complete(null);
    }

    // -- WebSocket / transport plumbing --------------------------------------------

    private void onWsMessage(byte[] message) {
        if (!noiseReady) {
            if (!msg1Future.isDone()) msg1Future.complete(message);
            return;
        }
        NoiseTransport t = transport;
        if (t == null) return;
        try {
            synchronized (sendLock) {
                // The reader is the only caller, but keep decrypt order with
                // the send lock so cipher use stays serialized.
                t.handleMessage(message);
            }
        } catch (Exception e) {
            failSession(new LinkError(ErrorKind.TRANSPORT, "Noise transport failed: " + e, e));
        }
    }

    private void onWsClosed(String reason) {
        if (!noiseReady && !msg1Future.isDone()) {
            msg1Future.complete(null);
            return;
        }
        if (outcome == null && failure == null) {
            outcome = Outcome.COMPLETED;
        }
        closed = true;
    }

    private void failSession(LinkError error) {
        if (failure == null && outcome == null) failure = error;
        closeTransport();
    }

    private boolean isControl(long streamId) {
        return controlStreamId > 0 && streamId == controlStreamId;
    }

    private void onTransportRequest(long streamId, NoiseEnvelope.ApplicationRequest request) {
        if (isControl(streamId)) {
            feedControl(request.body, request.endBody);
        }
        // The VM never opens request streams to the device in this protocol;
        // invokes arrive as control-stream messages.
    }

    private void onTransportResponse(long streamId, NoiseEnvelope.ApplicationResponse response) {
        if (isControl(streamId)) {
            handleControlResponse(response);
            return;
        }
        HttpStream stream = streams.get(streamId);
        if (stream != null) {
            try {
                stream.noteResponse(response.status, response.headers);
                stream.append(response.body, response.endBody);
            } catch (LinkError e) {
                failSession(e);
            }
            return;
        }
        PendingRequest p = pending.get(streamId);
        if (p != null) {
            p.status = response.status;
            p.headers = response.headers;
            p.chunks.write(response.body, 0, response.body.length);
            if (response.endBody) completePending(streamId);
        }
    }

    private void onTransportBodyChunk(long streamId, NoiseEnvelope.BodyChunk chunk) {
        if (isControl(streamId)) {
            feedControl(chunk.data, chunk.endBody);
            return;
        }
        HttpStream stream = streams.get(streamId);
        if (stream != null) {
            try {
                stream.append(chunk.data, chunk.endBody);
            } catch (LinkError e) {
                failSession(e);
            }
            return;
        }
        PendingRequest p = pending.get(streamId);
        if (p != null) {
            p.chunks.write(chunk.data, 0, chunk.data.length);
            if (chunk.endBody) completePending(streamId);
        }
    }

    private void onTransportReset(long streamId, NoiseEnvelope.Reset reset) {
        if (isControl(streamId)) {
            log("control stream reset by the VM (" + reset.code + "): " + reset.reason);
            if (outcome == null && failure == null) outcome = Outcome.COMPLETED;
            closeTransport();
            return;
        }
        HttpStream stream = streams.remove(streamId);
        if (stream != null) {
            if (!stream.future.isDone()) {
                stream.future.completeExceptionally(new LinkSessionException(
                        "stream reset (" + reset.code + "): " + reset.reason));
            }
            return;
        }
        PendingRequest p = pending.remove(streamId);
        if (p != null && !p.future.isDone()) {
            p.future.completeExceptionally(new LinkSessionException(
                    "stream reset (" + reset.code + "): " + reset.reason));
        }
    }

    // -- control stream plumbing ---------------------------------------------------

    private void handleControlResponse(NoiseEnvelope.ApplicationResponse response) {
        if (response.status == 403) {
            failSession(new LinkError(ErrorKind.FORBIDDEN,
                    "the control stream refused the session (HTTP 403)"));
            return;
        }
        if (response.status == 401) {
            failSession(new LinkError(ErrorKind.AUTHORIZATION,
                    "the control stream refused the session (HTTP 401)"));
            return;
        }
        if (response.status < 200 || response.status >= 300) {
            log("control stream refused: HTTP " + response.status);
            if (outcome == null && failure == null) outcome = Outcome.COMPLETED;
            closeTransport();
            return;
        }
        feedControl(response.body, response.endBody);
    }

    private void feedControl(byte[] data, boolean ended) {
        List<Map<String, Object>> messages;
        try {
            messages = controlDecoder.feed(data);
        } catch (LinkError e) {
            failSession(e);
            return;
        }
        for (Map<String, Object> message : messages) {
            handleControlMessage(message);
            if (closed) return;
        }
        if (ended && outcome == null && failure == null) {
            log("control stream ended by the VM");
            outcome = Outcome.COMPLETED;
            closeTransport();
        }
    }

    /** Length-prefixed JSON message splitter for the control stream. */
    static final class ControlDecoder {
        private byte[] buffer = new byte[0];

        synchronized List<Map<String, Object>> feed(byte[] data) throws LinkError {
            byte[] next = new byte[buffer.length + data.length];
            System.arraycopy(buffer, 0, next, 0, buffer.length);
            System.arraycopy(data, 0, next, buffer.length, data.length);
            buffer = next;
            List<Map<String, Object>> messages = new ArrayList<>();
            while (buffer.length >= 4) {
                long length = (buffer[0] & 0xFFL) | ((buffer[1] & 0xFFL) << 8)
                        | ((buffer[2] & 0xFFL) << 16) | ((buffer[3] & 0xFFL) << 24);
                if (length > MAX_MESSAGE_BYTES) {
                    throw new LinkError(ErrorKind.TOO_LARGE,
                            "control message length " + length + " exceeds the VM limit");
                }
                if (buffer.length < 4 + length) break;
                byte[] raw = new byte[(int) length];
                System.arraycopy(buffer, 4, raw, 0, (int) length);
                byte[] rest = new byte[buffer.length - 4 - (int) length];
                System.arraycopy(buffer, 4 + (int) length, rest, 0, rest.length);
                buffer = rest;
                Map<String, Object> message = parseJsonObjectOrNull(
                        new String(raw, StandardCharsets.UTF_8));
                if (message != null) messages.add(message);
            }
            return messages;
        }
    }

    private void completePending(long streamId) {
        PendingRequest p = pending.remove(streamId);
        if (p != null && !p.future.isDone()) {
            p.future.complete(new HttpMessage(p.status, p.headers, p.chunks.toByteArray()));
        }
    }

    // -- control stream --------------------------------------------------------------

    private Map<String, Object> controlRegisterMessage() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node_id", nodeId);
        params.put("display_name", displayName);
        params.put("platform", LINK_PLATFORM);
        params.put("version", CLIENT_VERSION);
        params.put("device_family", LINK_DEVICE_FAMILY);
        params.put("model_id", LINK_MODEL_ID);
        params.put("is_wakeup_supported", Boolean.FALSE);
        params.put("commands_v2", commandsV2());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "req");
        message.put("id", registerId);
        message.put("method", "link.register");
        message.put("params", params);
        return message;
    }

    private List<Object> commandsV2() {
        List<Object> out = new ArrayList<>();
        for (Command command : commands) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", command.name);
            entry.put("description", command.description);
            entry.put("usage", command.usage);
            out.add(entry);
        }
        return out;
    }

    /** One decoded control message; a port of the SDK's _handle. */
    private void handleControlMessage(Map<String, Object> message) {
        Object id = message.get("id");
        if (registerId != null && id instanceof String && registerId.equals(id)
                && message.get("method") == null) {
            if (message.get("error") != null || Boolean.FALSE.equals(message.get("ok"))) {
                LinkError error = new LinkError(
                        ErrorKind.FORBIDDEN, "link registration rejected by the VM");
                if (!registrationFuture.isDone()) {
                    registrationFuture.completeExceptionally(error);
                }
                failSession(error);
            } else {
                registrationFuture.complete(Boolean.TRUE);
            }
            return;
        }
        Object event = message.get("event");
        if ("link.unpaired".equals(event) || "node.unpaired".equals(event)
                || "link.unpaired".equals(message.get("method"))
                || "node.unpaired".equals(message.get("method"))) {
            handleUnpaired();
            return;
        }
        if ("link.invoke".equals(message.get("method"))) {
            handleInvoke(message);
        }
    }

    private void handleUnpaired() {
        outcome = Outcome.UNPAIRED;
        try {
            callbacks.onUnpaired();
        } catch (Exception ignored) { }
        closeTransport();
    }

    // -- invokes -------------------------------------------------------------------------

    private void handleInvoke(final Map<String, Object> message) {
        final Object invokeId = message.get("id");
        if (invokeId == null) return;
        Object commandRaw = message.get("command");
        if (!(commandRaw instanceof String)) {
            respondInvoke(invokeId, errorResult("command must be a string"));
            return;
        }
        final String name = (String) commandRaw;
        Object paramsRaw = message.get("params");
        @SuppressWarnings("unchecked")
        final Map<String, Object> params = paramsRaw instanceof Map
                ? (Map<String, Object>) paramsRaw : new LinkedHashMap<String, Object>();
        invokeExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    invokeSlots.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Command command = null;
                    for (Command c : commands) {
                        if (c.name.equals(name)) { command = c; break; }
                    }
                    Map<String, Object> result;
                    if (command == null) {
                        result = errorResult("unknown command: " + name);
                    } else {
                        try {
                            result = command.handler.handle(params);
                            if (result == null) {
                                result = okResult(new LinkedHashMap<String, Object>());
                            }
                        } catch (Exception e) {
                            // The SDK reports a generic failure: exception text
                            // can echo parameters or credentials.
                            result = errorResult("command failed");
                        }
                    }
                    respondInvoke(invokeId, result);
                } finally {
                    invokeSlots.release();
                }
            }
        });
    }

    private static Map<String, Object> okResult(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", Boolean.TRUE);
        result.put("payload", payload);
        return result;
    }

    private static Map<String, Object> errorResult(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", Boolean.FALSE);
        result.put("error", error);
        return result;
    }

    private void respondInvoke(Object invokeId, Map<String, Object> result) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("method", "link.result");
        reply.put("id", invokeId);
        reply.putAll(result);
        try {
            sendControl(reply);
        } catch (Exception e) {
            log("could not send the link.result reply: " + e.getMessage());
        }
    }

    // -- requests ---------------------------------------------------------------------------

    private static final class PendingRequest {
        final CompletableFuture<HttpMessage> future = new CompletableFuture<>();
        final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        volatile int status = 200;
        volatile List<NoiseEnvelope.Header> headers = new ArrayList<>();
    }

    private static final class HttpMessage {
        final int status;
        final List<NoiseEnvelope.Header> headers;
        final byte[] body;
        HttpMessage(int status, List<NoiseEnvelope.Header> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    /** Send one framed request and collect its response (any size, streamed). */
    private HttpMessage sendRequestBytes(String verb, String path, byte[] body,
            double timeoutSeconds, int maxResponseBytes) {
        return sendRequestBytes(verb, path, body, timeoutSeconds, maxResponseBytes, null);
    }

    /** As above, with extra request headers (e.g. an Accept for TTS). */
    private HttpMessage sendRequestBytes(String verb, String path, byte[] body,
            double timeoutSeconds, int maxResponseBytes,
            List<NoiseEnvelope.Header> extraHeaders) {
        NoiseTransport t = transport;
        if (t == null) throw new LinkSessionException("session is not open");
        long streamId = t.allocateStreamId();
        List<NoiseEnvelope.Header> headers = new ArrayList<>();
        if (body.length > 0) {
            headers.add(new NoiseEnvelope.Header("Content-Type", "application/json"));
        }
        headers.add(new NoiseEnvelope.Header("x-request-id", UUID.randomUUID().toString()));
        headers.add(new NoiseEnvelope.Header("x-app-id", APP_ID));
        if (extraHeaders != null) headers.addAll(extraHeaders);
        boolean buffered = maxResponseBytes > RESPONSE_SINGLE_SHOT_LIMIT
                || body.length > REQUEST_SINGLE_SHOT_LIMIT;
        if (!buffered) {
            PendingRequest p = new PendingRequest();
            pending.put(streamId, p);
            try {
                synchronized (sendLock) {
                    t.sendRequest(streamId, new NoiseEnvelope.ApplicationRequest(
                            verb, path, headers, body, true));
                }
                if (timeoutSeconds <= 0) return p.future.get();
                return p.future.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.remove(streamId);
                sendResetQuietly(streamId);
                throw new LinkSessionException("request timed out: " + verb + " " + path, e);
            } catch (ExecutionException e) {
                throw new LinkSessionException("request failed: " + e.getCause(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LinkSessionException("request interrupted", e);
            } catch (LinkSessionException e) {
                pending.remove(streamId);
                throw e;
            } catch (Exception e) {
                pending.remove(streamId);
                throw new LinkSessionException("request send failed: " + e, e);
            }
        }
        HttpStream stream = new HttpStream(maxResponseBytes);
        streams.put(streamId, stream);
        try {
            synchronized (sendLock) {
                if (body.length == 0) {
                    t.sendRequest(streamId, new NoiseEnvelope.ApplicationRequest(
                            verb, path, headers, new byte[0], true));
                } else if (body.length <= STREAM_CHUNK_BYTES) {
                    t.sendRequest(streamId, new NoiseEnvelope.ApplicationRequest(
                            verb, path, headers, body, true));
                } else {
                    int firstEnd = STREAM_CHUNK_BYTES - (STREAM_CHUNK_BYTES % 4);
                    byte[] first = new byte[firstEnd];
                    System.arraycopy(body, 0, first, 0, firstEnd);
                    t.sendRequest(streamId, new NoiseEnvelope.ApplicationRequest(
                            verb, path, headers, first, false));
                    int offset = firstEnd;
                    while (offset < body.length) {
                        int end = Math.min(offset + STREAM_CHUNK_BYTES, body.length);
                        byte[] chunk = new byte[end - offset];
                        System.arraycopy(body, offset, chunk, 0, chunk.length);
                        offset = end;
                        t.sendBodyChunk(streamId, new NoiseEnvelope.BodyChunk(
                                chunk, offset >= body.length));
                    }
                }
            }
            HttpStream.Completed completed = timeoutSeconds <= 0
                    ? stream.future.get()
                    : stream.future.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
            return new HttpMessage(completed.status, completed.headers, completed.body);
        } catch (TimeoutException e) {
            streams.remove(streamId);
            sendResetQuietly(streamId);
            throw new LinkSessionException("request timed out: " + verb + " " + path, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LinkError) failSession((LinkError) cause);
            throw new LinkSessionException("request failed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LinkSessionException("request interrupted", e);
        } catch (LinkSessionException e) {
            streams.remove(streamId);
            throw e;
        } catch (Exception e) {
            streams.remove(streamId);
            throw new LinkSessionException("request send failed: " + e, e);
        }
    }

    private void sendResetQuietly(long streamId) {
        try {
            NoiseTransport t = transport;
            if (t != null) {
                synchronized (sendLock) {
                    t.sendReset(streamId, new NoiseEnvelope.Reset(
                            NoiseEnvelope.RESET_CANCELLED, "request timed out"));
                }
            }
        } catch (Exception ignored) { }
    }

    private static final class HttpStream {
        static final class Completed {
            final int status;
            final List<NoiseEnvelope.Header> headers;
            final byte[] body;
            Completed(int status, List<NoiseEnvelope.Header> headers, byte[] body) {
                this.status = status;
                this.headers = headers;
                this.body = body;
            }
        }

        final CompletableFuture<Completed> future = new CompletableFuture<>();
        private final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        private final int maxBytes;
        private final Consumer<byte[]> chunkSink;
        private final boolean retain;
        private int status = 200;
        private List<NoiseEnvelope.Header> headers = new ArrayList<>();

        HttpStream(int maxBytes) { this(maxBytes, null, true); }

        /**
         * @param chunkSink receives a copy of every body chunk as it
         *     arrives (on the reader thread), for streaming consumers
         *     like the chat subscription; null buffers as before.
         * @param retain false skips buffering the body (streaming
         *     consumers that never read {@link Completed#body}).
         */
        HttpStream(int maxBytes, Consumer<byte[]> chunkSink, boolean retain) {
            this.maxBytes = maxBytes;
            this.chunkSink = chunkSink;
            this.retain = retain;
        }

        synchronized void append(byte[] chunk, boolean endBody) throws LinkError {
            if (retain) {
                chunks.write(chunk, 0, chunk.length);
                if (chunks.size() > maxBytes) {
                    throw new LinkError(ErrorKind.TOO_LARGE,
                            "response exceeded " + maxBytes + " bytes");
                }
            }
            if (chunkSink != null && chunk.length > 0) {
                chunkSink.accept(Arrays.copyOf(chunk, chunk.length));
            }
            if (endBody && !future.isDone()) {
                future.complete(new Completed(status, headers, chunks.toByteArray()));
            }
        }

        private boolean noted = false;

        void noteResponse(int status, List<NoiseEnvelope.Header> headers) {
            if (noted) return;
            noted = true;
            this.status = status;
            this.headers = headers;
        }
    }

    // -- JSON over the control stream ------------------------------------------------------

    /** Encode and send one control-stream message as a body chunk (SDK send()). */
    public void sendControl(Map<String, Object> message) {
        NoiseTransport t = transport;
        if (t == null || controlStreamId <= 0) {
            throw new LinkSessionException("session is not open");
        }
        byte[] payload = Json.stringify(message).getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[4 + payload.length];
        framed[0] = (byte) (payload.length & 0xFF);
        framed[1] = (byte) ((payload.length >>> 8) & 0xFF);
        framed[2] = (byte) ((payload.length >>> 16) & 0xFF);
        framed[3] = (byte) ((payload.length >>> 24) & 0xFF);
        System.arraycopy(payload, 0, framed, 4, payload.length);
        if (framed.length > MAX_MESSAGE_BYTES) {
            throw new LinkSessionException("control message exceeds the 256 KiB VM limit");
        }
        try {
            synchronized (sendLock) {
                t.sendBodyChunk(controlStreamId,
                        new NoiseEnvelope.BodyChunk(framed, false));
            }
        } catch (LinkSessionException e) {
            throw e;
        } catch (Exception e) {
            throw new LinkSessionException("control send failed: " + e, e);
        }
    }

    /**
     * Send one JSON payload to a session path and return the decoded stream
     * of JSON messages (SSE-style lines / concatenated objects).
     */
    public List<Map<String, Object>> sendChatStreamRequest(
            String path, Map<String, Object> payload, double timeoutSeconds) {
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        HttpMessage response = sendRequestBytes(
                "POST", path, body, timeoutSeconds, BUFFERED_RESPONSE_LIMIT);
        if (response.status == 404) {
            throw new LinkSessionException("the VM has no chat session handler at " + path
                    + "; it may not support chat sessions");
        }
        if (response.status < 200 || response.status >= 300) {
            String preview = new String(response.body, StandardCharsets.UTF_8);
            if (preview.length() > 200) preview = preview.substring(0, 200);
            throw new LinkSessionException(
                    "chat request failed with HTTP " + response.status + ": " + preview);
        }
        return new MessageDecoder().feed(new String(response.body, StandardCharsets.UTF_8));
    }

    /** Fire-and-collect one chat message; returns the decoded reply messages. */
    public List<Map<String, Object>> sendChat(String message, String sessionId) {
        return sendChat(message, sessionId, null);
    }

    /**
     * As {@link #sendChat(String, String)}, with an output modality:
     * "voice" asks for a reply {@link #streamTts} can speak (the reply
     * text still arrives on the chat subscription as usual). The
     * decoded response is the acknowledgement only — the reply itself
     * is followed through the subscription (see ReplyTracker).
     */
    public List<Map<String, Object>> sendChat(String message, String sessionId,
            String outputModality) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        payload.put("device_id", nodeId);
        if (sessionId != null) payload.put("session_id", sessionId);
        if (outputModality != null) payload.put("output_modality", outputModality);
        return sendChatStreamRequest("/chat/stream", payload, REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * Send one voice note (a complete WAV file, at most 2 MiB) as a chat
     * turn and return the decoded reply messages. With {@code
     * outputModality} "voice", the reply's final message id can be passed
     * to {@link #streamTts} for spoken audio.
     */
    public List<Map<String, Object>> sendVoice(byte[] wavBytes, String sessionId,
            String message, String outputModality, double timeoutSeconds) {
        if (wavBytes.length > MAX_VOICE_BYTES) {
            throw new IllegalArgumentException(
                    "voice notes are capped at 2 MiB, got " + wavBytes.length + " bytes");
        }
        List<Object> items = new ArrayList<>();
        if (message != null && !message.isEmpty()) {
            Map<String, Object> textItem = new LinkedHashMap<>();
            textItem.put("type", "text");
            textItem.put("text", message);
            items.add(textItem);
        }
        Map<String, Object> fileItem = new LinkedHashMap<>();
        fileItem.put("type", "file");
        fileItem.put("mime_type", "audio/wav");
        fileItem.put("filename", "voice_note.wav");
        fileItem.put("data_base64", B64.stdEncode(wavBytes));
        items.add(fileItem);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message != null ? message : "");
        payload.put("device_id", nodeId);
        payload.put("items", items);
        if (sessionId != null) payload.put("session_id", sessionId);
        if (outputModality != null) payload.put("output_modality", outputModality);
        return sendChatStreamRequest("/chat/stream", payload, timeoutSeconds);
    }

    /**
     * Fetch the spoken reply for a chat message id, as MP3 bytes
     * (v0.8.1: semantics aligned with the SDK's stream_tts — the
     * {@code Accept: audio/mpeg} header is sent, the legacy path is
     * tried ONLY when the primary answers 404, and any other
     * refusal raises immediately with its HTTP status in the
     * exception message; the v0.7.0 loop overwrote the first
     * path's outcome with the second's and the caller logged only
     * the exception class, so a refusal was undiagnosable).
     * Transport failures propagate as LinkSessionException with
     * their own messages. Response bodies are never included in
     * error text (the SDK's rule).
     */
    public byte[] streamTts(String messageId) throws Exception {
        String encoded = uriComponent(messageId);
        String[] paths = {
            "/api/voice/tts-stream?message_id=" + encoded,
            "/api/voice/tts-stream?message_id=" + encoded,
        };
        List<NoiseEnvelope.Header> accept = new ArrayList<>();
        accept.add(new NoiseEnvelope.Header("Accept", "audio/mpeg"));
        for (int i = 0; i < paths.length; i++) {
            HttpMessage response = sendRequestBytes(
                    "GET", paths[i], new byte[0], REQUEST_TIMEOUT_SECONDS,
                    TTS_BUFFER_LIMIT, accept);
            if (response.status >= 200 && response.status < 300) {
                if (response.body.length > 0) return response.body;
                throw new LinkSessionException(
                        "TTS stream returned an empty body for " + paths[i]);
            }
            if (response.status == 404 && i == 0) {
                continue; // the SDK's legacy fallback: only a 404 falls through
            }
            throw new LinkSessionException(
                    "TTS stream returned HTTP " + response.status + " for " + paths[i]);
        }
        throw new LinkSessionException("TTS stream failed");
    }

    /**
     * Open a chat subscription and deliver each event to {@code
     * onEvent} AS IT ARRIVES (the SDK's subscribe_chat semantics),
     * blocking the calling thread until the stream ends, the session
     * fails, or {@code cancelled} turns true (checked twice a
     * second; cancelling resets the stream). The buffered
     * {@link #subscribeChat} above only delivers when the VM closes
     * the stream, which is too late for a voice turn. Events arrive
     * on this (the caller's) thread via the transport reader; the
     * payload mirrors the SDK's: the session id when one is given,
     * an empty object for the main chat. Throws {@link
     * LinkSessionException} on a refused or failed subscription.
     */
    public void subscribeChatStreaming(final Consumer<Map<String, Object>> onEvent,
            String sessionId, java.util.function.BooleanSupplier cancelled) {
        NoiseTransport t = transport;
        if (t == null) throw new LinkSessionException("session is not open");
        Map<String, Object> payload = new LinkedHashMap<>();
        if (sessionId != null) payload.put("session_id", sessionId);
        byte[] body = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        long streamId = t.allocateStreamId();
        List<NoiseEnvelope.Header> headers = new ArrayList<>();
        headers.add(new NoiseEnvelope.Header("Content-Type", "application/json"));
        headers.add(new NoiseEnvelope.Header("Accept", "application/x-ndjson"));
        headers.add(new NoiseEnvelope.Header("x-request-id", UUID.randomUUID().toString()));
        headers.add(new NoiseEnvelope.Header("x-app-id", APP_ID));
        final MessageDecoder decoder = new MessageDecoder();
        HttpStream stream = new HttpStream(BUFFERED_RESPONSE_LIMIT,
                new Consumer<byte[]>() {
                    @Override public void accept(byte[] chunk) {
                        List<Map<String, Object>> events = decoder.feed(
                                new String(chunk, StandardCharsets.UTF_8));
                        for (Map<String, Object> event : events) {
                            try {
                                onEvent.accept(event);
                            } catch (Exception ignored) { }
                        }
                    }
                }, false);
        streams.put(streamId, stream);
        try {
            synchronized (sendLock) {
                t.sendRequest(streamId, new NoiseEnvelope.ApplicationRequest(
                        "POST", "/chat/subscribe", headers, body, true));
            }
            HttpStream.Completed completed = null;
            while (completed == null) {
                if (cancelled.getAsBoolean()) {
                    streams.remove(streamId);
                    sendResetQuietly(streamId);
                    return;
                }
                try {
                    completed = stream.future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (closed) {
                        throw new LinkSessionException("session closed during subscribe");
                    }
                }
            }
            if (completed.status < 200 || completed.status >= 300) {
                throw new LinkSessionException(
                        "chat subscription refused: HTTP " + completed.status);
            }
        } catch (ExecutionException e) {
            throw new LinkSessionException("chat subscription failed: " + e.getCause(),
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            streams.remove(streamId);
            sendResetQuietly(streamId);
            throw new LinkSessionException("chat subscription interrupted", e);
        } catch (LinkSessionException e) {
            streams.remove(streamId);
            throw e;
        } catch (Exception e) {
            streams.remove(streamId);
            throw new LinkSessionException("chat subscription failed: " + e, e);
        }
    }

    /** Open a streaming chat subscription; events go to {@code onEvent}. */
    public void subscribeChat(final Consumer<Map<String, Object>> onEvent) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("device_id", nodeId);
                try {
                    List<Map<String, Object>> events =
                            sendChatStreamRequest("/chat/subscribe", payload, 0);
                    for (Map<String, Object> event : events) onEvent.accept(event);
                } catch (Exception e) {
                    log("chat subscription ended: " + e.getMessage());
                }
            }
        }, "LinkSession-subscribe");
        thread.setDaemon(true);
        thread.start();
    }

    // -- helpers ---------------------------------------------------------------------------------

    /** Decode the JSON messages in one chat-stream body (lines or concatenated objects). */
    public static final class MessageDecoder {
        private String buffer = "";

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> feed(String text) {
            buffer += text;
            List<Map<String, Object>> messages = new ArrayList<>();
            while (true) {
                int newline = buffer.indexOf('\n');
                if (newline == -1) break;
                String line = buffer.substring(0, newline).trim();
                buffer = buffer.substring(newline + 1);
                if (line.isEmpty()) continue;
                if (line.startsWith("data:")) line = line.substring("data:".length()).trim();
                if (line.isEmpty() || "[DONE]".equals(line)) continue;
                Object data = tryParse(line);
                if (data instanceof Map) messages.add((Map<String, Object>) data);
            }
            String rest = buffer.trim();
            if (!rest.isEmpty()) {
                int end = firstObjectEnd(rest);
                if (end >= rest.length()) {
                    Object data = tryParse(rest);
                    if (data instanceof Map) {
                        messages.add((Map<String, Object>) data);
                        buffer = "";
                    }
                }
            }
            return messages;
        }

        /** End index (exclusive) of the first balanced JSON object, or -1. */
        private static int firstObjectEnd(String text) {
            int start = text.indexOf('{');
            if (start == -1) return -1;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            return -1;
        }

        private Object tryParse(String text) {
            try {
                return Json.parse(text);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Percent-encoding identical to the SDK's quote(value, safe="-_.!~*'()"). */
    public static String uriComponent(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.'
                    || c == '!' || c == '~' || c == '*' || c == '\'' || c == '(' || c == ')';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObjectOrNull(String text) {
        try {
            Object data = Json.parse(text);
            return data instanceof Map ? (Map<String, Object>) data : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void log(String message) {
        try {
            callbacks.log(message);
        } catch (Exception ignored) { }
    }
}
