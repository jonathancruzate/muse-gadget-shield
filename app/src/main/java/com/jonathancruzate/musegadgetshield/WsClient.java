package com.jonathancruzate.musegadgetshield;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal RFC 6455 WebSocket client (wss) with the pieces the Noise
 * link needs: an upgrade that surfaces the HTTP status on rejection,
 * masked sends, ping/pong, fragmentation reassembly, and a keepalive
 * ping while open. Android has no java.net.http WebSocket, and pulling in
 * a library for one socket is not worth it.
 */
public final class WsClient implements WsConnection {
    public static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    public interface Listener {
        void onMessage(byte[] message);
        void onClosed(String reason);
    }

    public static final class UpgradeRejectedException extends IOException {
        public final int status;
        UpgradeRejectedException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object sendLock = new Object();
    private volatile boolean closed = false;
    private final long keepaliveSeconds;
    private ScheduledExecutorService keepalive;

    private WsClient(Socket socket, Listener listener, long keepaliveSeconds) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.keepaliveSeconds = keepaliveSeconds;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /**
     * Connect and perform the upgrade. {@code headers} carries extra
     * request headers (Authorization). Raises {@link
     * UpgradeRejectedException} with the HTTP status when the server
     * refuses the upgrade.
     */
    public static WsClient connect(URI uri, Map<String, String> headers,
            int connectTimeoutMs, int readTimeoutMs, long keepaliveSeconds,
            Listener listener) throws IOException {
        String scheme = uri.getScheme();
        boolean secure = "wss".equals(scheme);
        if (!secure && !"ws".equals(scheme)) {
            throw new IOException("unsupported WebSocket scheme: " + scheme);
        }
        String host = uri.getHost();
        int port = uri.getPort() >= 0 ? uri.getPort() : (secure ? 443 : 80);
        Socket socket = secure
                ? SSLSocketFactory.getDefault().createSocket()
                : new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
        WsClient client = new WsClient(socket, listener, keepaliveSeconds);
        try {
            client.upgrade(uri, host, port, headers);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) { }
            throw e;
        }
        client.startReader();
        return client;
    }

    private void upgrade(URI uri, String host, int port, Map<String, String> headers) throws IOException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = java.util.Base64.getEncoder().encodeToString(keyBytes);
        StringBuilder request = new StringBuilder();
        request.append("GET ").append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host);
        if (port != 443 && port != 80) request.append(':').append(port);
        request.append("\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        Map<String, String> all = new LinkedHashMap<>();
        if (headers != null) all.putAll(headers);
        for (Map.Entry<String, String> e : all.entrySet()) {
            request.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null) throw new EOFException("WebSocket upgrade: no response");
        String[] parts = statusLine.split(" ", 3);
        int status = -1;
        if (parts.length >= 2) {
            try { status = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) { }
        }
        // Drain response headers.
        String line;
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        while ((line = readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                responseHeaders.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        if (status != 101) {
            throw new UpgradeRejectedException(status,
                    "WebSocket upgrade rejected with HTTP " + status);
        }
        String accept = responseHeaders.get("sec-websocket-accept");
        String expected = computeAccept(key);
        if (accept == null || !accept.equals(expected)) {
            throw new IOException("WebSocket upgrade: bad Sec-WebSocket-Accept");
        }
        socket.setSoTimeout(0);
    }

    private String computeAccept(String key) throws IOException {
        try {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .getBytes(StandardCharsets.ISO_8859_1));
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 16384) throw new IOException("WebSocket header line too long");
        }
        if (!any && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void startReader() {
        if (keepaliveSeconds > 0) {
            keepalive = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "WsClient-keepalive");
                    t.setDaemon(true);
                    return t;
                }
            });
            keepalive.scheduleAtFixedRate(new Runnable() {
                @Override public void run() {
                    try { sendControl(0x9, new byte[0]); } catch (IOException ignored) { }
                }
            }, keepaliveSeconds, keepaliveSeconds, TimeUnit.SECONDS);
        }
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "WsClient-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private void readLoop() {
        ByteArrayOutputStream fragment = null;
        int fragmentOpcode = -1;
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long length = b1 & 0x7F;
                if (length == 126) {
                    length = ((long) readByte() << 8) | readByte();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) length = (length << 8) | readByte();
                }
                if (length < 0 || length > MAX_MESSAGE_BYTES) {
                    throw new IOException("WebSocket frame too large: " + length);
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) length];
                readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }
                switch (opcode) {
                    case 0x9: // ping
                        sendControl(0xA, payload);
                        break;
                    case 0xA: // pong
                        break;
                    case 0x8: // close
                        closeQuietly();
                        listener.onClosed("server closed the connection");
                        return;
                    case 0x0: // continuation
                        if (fragment == null) throw new IOException("unexpected continuation frame");
                        fragment.write(payload, 0, payload.length);
                        if (fragment.size() > MAX_MESSAGE_BYTES) {
                            throw new IOException("WebSocket message too large");
                        }
                        if (fin) {
                            byte[] message = fragment.toByteArray();
                            fragment = null;
                            listener.onMessage(message);
                        }
                        break;
                    case 0x1:
                    case 0x2:
                        if (fin) {
                            listener.onMessage(payload);
                        } else {
                            fragment = new ByteArrayOutputStream();
                            fragment.write(payload, 0, payload.length);
                            fragmentOpcode = opcode;
                        }
                        break;
                    default:
                        throw new IOException("unknown WebSocket opcode " + opcode);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                listener.onClosed("read failed: " + e.getMessage());
                return;
            }
        }
        if (!closed) listener.onClosed("connection closed");
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) throw new EOFException("WebSocket stream ended");
        return b;
    }

    private void readFully(byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n == -1) throw new EOFException("WebSocket stream ended");
            offset += n;
        }
    }

    public void send(byte[] payload) throws IOException {
        synchronized (sendLock) {
            if (closed) throw new IOException("WebSocket is closed");
            writeFrame(0x2, payload);
        }
    }

    private void sendControl(int opcode, byte[] payload) throws IOException {
        synchronized (sendLock) {
            if (closed) return;
            writeFrame(opcode, payload);
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        int length = payload.length;
        if (length < 126) {
            frame.write(0x80 | length);
        } else if (length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((length >>> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) ((length >>> (i * 8)) & 0xFF));
        }
        frame.write(mask, 0, 4);
        for (int i = 0; i < length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    public void close() {
        synchronized (sendLock) {
            if (closed) return;
            closed = true;
            try { writeFrame(0x8, new byte[0]); } catch (IOException ignored) { }
        }
        closeQuietly();
    }

    private void closeQuietly() {
        closed = true;
        if (keepalive != null) keepalive.shutdownNow();
        try { socket.close(); } catch (IOException ignored) { }
    }

    public boolean isClosed() { return closed; }
}
