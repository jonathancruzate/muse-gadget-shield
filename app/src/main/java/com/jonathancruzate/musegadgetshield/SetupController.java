package com.jonathancruzate.musegadgetshield;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BLE setup commands: the protocol logic behind the GATT characteristics.
 * A direct port of the SDK's ble_setup.py.
 *
 * Independent of the Android BLE stack: a transport delivers reassembled
 * writes and sends framed notifications. Messages are handled one at a
 * time on a worker thread, in arrival order, and every send happens under
 * one lock so encrypted record counters reach the phone in order.
 */
public final class SetupController {
    private static final Logger log = Logger.getLogger("musegadget.setup");

    public interface Transport {
        /** Notify each packet in order; blocks until all are queued. */
        void sendPackets(java.util.List<byte[]> packets);
        int mtu();
        void disconnect(double delaySeconds);
    }

    public interface Network {
        boolean isOnline();
        Map<String, Object> currentConnectionEntry();
    }

    /** Credentials handed over by a {@code provision_v2} command. */
    public static final class Credentials {
        public final String accessToken;
        public final String refreshToken;
        public final String username;
        public final String apiUrl;
        public final String apiUrlV2;
        public final String noiseHost;

        Credentials(String accessToken, String refreshToken, String username,
                String apiUrl, String apiUrlV2, String noiseHost) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.username = username;
            this.apiUrl = apiUrl;
            this.apiUrlV2 = apiUrlV2;
            this.noiseHost = noiseHost;
        }
    }

    /** Setup could not finish; {@code status} is what the app is told. */
    public static final class ProvisionFailed extends Exception {
        public final String status;
        public ProvisionFailed(String status) { super(status); this.status = status; }
    }

    /** Runs provisioning: verify credentials, then persist them through the committer. */
    public interface Provision {
        void provision(Credentials credentials, Committer commit) throws ProvisionFailed;
    }

    /** Mirrors pairing.commit_provisioning: runs {@code save} under the pairing lock. */
    public interface Committer {
        boolean commit(BooleanSupplier save);
    }

    private static final Set<String> SENSITIVE_ACTIONS = new HashSet<>(Arrays.asList(
            "provision", "provision_v2", "wifi_scan", "ota", "device.ota",
            "unpair", "set_wifi", "set_auth"));
    private static final Set<String> PLAINTEXT_STATUSES = new HashSet<>(Arrays.asList(
            "error_encryption_required",
            "error_pairing_invalid_hello",
            "error_pairing_unavailable",
            "error_pairing_decrypt"));
    private static final double DISCONNECT_AFTER_ERROR_S = 0.3;
    private static final byte[] STOP = new byte[0];

    private final Pairing.PairingSession pairing;
    private final Identity identity;
    private final String version;
    private final Transport transport;
    private final Network network;
    private final Provision provision;
    private final Runnable onComplete;

    private final BleFraming.ChunkAssembler assembler = new BleFraming.ChunkAssembler();
    private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final Object txLock = new Object();
    private final Object stateLock = new Object();
    private boolean plaintextBlocked = false;
    private boolean provisioning = false;
    private Thread worker;

    public SetupController(Pairing.PairingSession pairing, Identity identity, String version,
            Transport transport, Network network, Provision provision, Runnable onComplete) {
        this.pairing = pairing;
        this.identity = identity;
        this.version = version;
        this.transport = transport;
        this.network = network;
        this.provision = provision;
        this.onComplete = onComplete != null ? onComplete : new Runnable() {
            @Override public void run() {}
        };
    }

    // -- Transport callbacks (any thread) ------------------------------------

    public void onWrite(byte[] packet) {
        byte[] message = assembler.feed(packet);
        if (message != null) inbox.offer(message);
    }

    public void onDisconnect() {
        log.info("BLE client disconnected; clearing pairing session");
        assembler.reset();
        synchronized (stateLock) { plaintextBlocked = false; }
        pairing.reset();
    }

    // -- Lifecycle -------------------------------------------------------------

    public void start() {
        worker = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "ble-setup");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        inbox.offer(STOP);
    }

    private void runLoop() {
        while (true) {
            byte[] message;
            try {
                message = inbox.take();
            } catch (InterruptedException e) {
                return;
            }
            if (message == STOP) return;
            try {
                handleMessage(message, false);
            } catch (Exception e) {
                log.log(Level.WARNING, "setup command failed", e);
            }
        }
    }

    // -- Dispatch ----------------------------------------------------------------

    public void handleMessage(byte[] raw, boolean decrypted) {
        Map<String, Object> command;
        try {
            Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("not an object");
            @SuppressWarnings("unchecked")
            Map<String, Object> asMap = (Map<String, Object>) parsed;
            command = asMap;
        } catch (Exception e) {
            log.warning("invalid command JSON (" + raw.length + " bytes)");
            sendStatus("error_invalid_command");
            return;
        }
        Object actionObj = command.get("action");
        String action = actionObj instanceof String ? (String) actionObj : "";
        log.info("RX action: " + (action.isEmpty() ? "?" : action) + (decrypted ? " (encrypted)" : ""));

        boolean blocked;
        synchronized (stateLock) { blocked = plaintextBlocked; }

        if (!decrypted && "pairing_client_hello".equals(action)) {
            handleHello(command);
        } else if (!decrypted && "pairing_encrypted".equals(action)) {
            handleRecord(command);
        } else if ("get_device_info".equals(action)) {
            // Public metadata, safe in plaintext at any point. Apps re-read
            // it when they restart a handshake on the same connection.
            sendJson(deviceInfo());
        } else if (!decrypted && blocked) {
            log.warning("plaintext command ignored after pairing started: " + action);
        } else if (!decrypted && SENSITIVE_ACTIONS.contains(action)) {
            sendStatus("error_encryption_required");
        } else if (decrypted && "pairing_client_finished".equals(action)) {
            handleClientFinished(command);
        } else if (decrypted && SENSITIVE_ACTIONS.contains(action) && !pairing.confirmed()) {
            sendStatus("error_pairing_confirm_required");
        } else if (decrypted && "wifi_scan".equals(action)) {
            handleWifiScan();
        } else if (decrypted && "provision_v2".equals(action)) {
            handleProvision(command);
        } else {
            sendStatus("error_unknown_action");
        }
    }

    public Map<String, Object> deviceInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", "device_info");
        info.put("node_id", identity.nodeId());
        info.put("version", version);
        info.putAll(pairing.deviceInfo());
        info.put("build_sha", "");
        info.put("network_ready", network.isOnline());
        return info;
    }

    private void handleHello(Map<String, Object> command) {
        Map<String, Object> ready;
        try {
            ready = pairing.handleHello(command);
        } catch (Pairing.PairingError err) {
            sendStatus(err.status);
            return;
        }
        synchronized (stateLock) { plaintextBlocked = true; }
        sendJson(ready);
    }

    private void handleRecord(Map<String, Object> envelope) {
        String plaintext;
        try {
            plaintext = pairing.decrypt(envelope);
        } catch (Pairing.PairingError err) {
            sendStatus(err.status);
            transport.disconnect(DISCONNECT_AFTER_ERROR_S);
            return;
        }
        handleMessage(plaintext.getBytes(StandardCharsets.UTF_8), true);
    }

    private void handleClientFinished(Map<String, Object> command) {
        long generation = pairing.handleClientFinished(command);
        if (generation == 0) {
            sendStatus("error_pairing_decrypt");
            transport.disconnect(DISCONNECT_AFTER_ERROR_S);
            return;
        }
        log.info("pairing confirmed (app consent)");
        sendStatus("pairing_confirmed", generation);
    }

    private void handleWifiScan() {
        java.util.List<Object> networks = new java.util.ArrayList<>();
        if (network.isOnline()) networks.add(network.currentConnectionEntry());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "wifi_scan_result");
        result.put("networks", networks);
        sendEncryptedJson(result, 0);
    }

    private static String text(Map<String, Object> command, String key) {
        Object value = command.get(key);
        return value instanceof String ? (String) value : "";
    }

    private void handleProvision(Map<String, Object> command) {
        if (!(command.get("ssid") instanceof String)
                || !(command.get("password") instanceof String)
                || text(command, "access_token").isEmpty()
                || text(command, "refresh_token").isEmpty()
                || !"device".equals(text(command, "token_type"))) {
            sendStatus("error_missing_credentials");
            return;
        }
        long generation = 0;
        String refusal = null;
        synchronized (stateLock) {
            if (provisioning) {
                refusal = "error_operation_in_progress";
            } else {
                generation = pairing.markProvisioning();
                refusal = generation != 0 ? null : "error_pairing_confirm_required";
                provisioning = generation != 0;
            }
        }
        // Sent after the lock is released: sendStatus() takes stateLock
        // itself when the session has no keys, and the lock is not reentrant.
        if (refusal != null) {
            sendStatus(refusal);
            return;
        }
        // The Wi-Fi fields are deliberately dropped: this device only sets
        // up when it is already online.
        final Credentials credentials = new Credentials(
                text(command, "access_token"),
                text(command, "refresh_token"),
                text(command, "username"),
                text(command, "api_url"),
                text(command, "api_url_v2"),
                text(command, "noise_host"));
        final long gen = generation;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { runProvision(credentials, gen); }
        }, "provision");
        thread.setDaemon(true);
        thread.start();
    }

    private void runProvision(Credentials credentials, final long generation) {
        try {
            sendStatus("wifi_connecting", generation);
            if (!network.isOnline()) {
                // Stay in provisioning so the app can retry, as the firmware does.
                pairing.extendProvisioning(generation);
                sendStatus("wifi_failed", generation);
                return;
            }
            sendStatus("wifi_connected", generation);
            Committer commit = new Committer() {
                @Override public boolean commit(BooleanSupplier save) {
                    return pairing.commitProvisioning(generation, save);
                }
            };
            try {
                provision.provision(credentials, commit);
            } catch (ProvisionFailed err) {
                log.warning("provisioning failed: " + err.status);
                sendStatus(err.status, generation);
                transport.disconnect(0.5);
                return;
            }
            sendStatus("auth_ok", generation);
            log.info("setup complete");
            onComplete.run();
        } finally {
            synchronized (stateLock) { provisioning = false; }
        }
    }

    // -- Sending -------------------------------------------------------------------

    public void sendStatus(String status) { sendStatus(status, 0); }

    public void sendStatus(String status, long generation) {
        synchronized (txLock) {
            Map<String, Object> envelope = pairing.encryptStatus(status, generation);
            if (envelope != null) {
                sendLocked(Json.stringify(envelope));
                log.info("TX status (encrypted #" + envelope.get("counter") + "): " + status);
                return;
            }
            boolean blocked;
            synchronized (stateLock) { blocked = plaintextBlocked; }
            if (generation != 0 || blocked || !PLAINTEXT_STATUSES.contains(status)) {
                log.info("TX status suppressed: " + status);
                return;
            }
            java.util.List<byte[]> packets = new java.util.ArrayList<>();
            packets.add(status.getBytes(StandardCharsets.UTF_8));
            transport.sendPackets(packets);
            log.info("TX status: " + status);
        }
    }

    public void sendJson(Map<String, Object> obj) {
        synchronized (txLock) {
            sendLocked(Json.stringify(obj));
            log.info("TX " + obj.get("type"));
        }
    }

    public void sendEncryptedJson(Map<String, Object> obj, long generation) {
        synchronized (txLock) {
            Map<String, Object> envelope = pairing.encryptJson(Json.stringify(obj), generation);
            if (envelope == null) {
                log.info("TX " + obj.get("type") + " suppressed: no session");
                return;
            }
            sendLocked(Json.stringify(envelope));
            log.info("TX " + obj.get("type") + " (encrypted #" + envelope.get("counter") + ")");
        }
    }

    private void sendLocked(String text) {
        java.util.List<byte[]> packets = BleFraming.encodeChunks(
                text.getBytes(StandardCharsets.UTF_8), transport.mtu());
        transport.sendPackets(packets);
    }
}
