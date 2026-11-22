package com.jonathancruzate.musegadgetshield;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The steady-state supervisor: load the pairing, keep the device token
 * fresh, fetch the account's VM, hold a {@link LinkSession}, and
 * reconnect with backoff; a port of the SDK's service.py run loop.
 *
 * The command surface is deliberately minimal on a TV: one read-only
 * {@code device.info} command. There is no shell access here.
 */
public final class LinkService {
    public interface Logger {
        void log(String message);
    }

    private static final double BACKOFF_INITIAL_SECONDS = 2;
    private static final double BACKOFF_MAX_SECONDS = 60;
    private static final double AUTH_RETRY_FLOOR_SECONDS = 15;
    private static final double SESSION_RESET_SECONDS = 30;
    private static final double UNPAIRED_POLL_SECONDS = 30;
    private static final long TOKEN_REFRESH_AGE_SECONDS = 3 * 60 * 60;

    private final File stateDir;
    private final Identity identity;
    private final String displayName;
    private final Logger logger;

    private volatile boolean running = false;
    private Thread thread;
    private volatile LinkSession session;
    private final Object pairingLock = new Object();

    public LinkService(File stateDir, Identity identity, String displayName, Logger logger) {
        this.stateDir = stateDir;
        this.identity = identity;
        this.displayName = displayName;
        this.logger = logger;
    }

    public void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "LinkService");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        LinkSession s = session;
        if (s != null) s.close();
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }

    public boolean isRunning() { return running; }

    public LinkSession currentSession() {
        LinkSession s = session;
        return s != null && s.isRegistered() && !s.isClosed() ? s : null;
    }

    /** Send one chat message on the live session, if any. */
    public List<Map<String, Object>> sendChat(String message, String sessionId) {
        return sendChat(message, sessionId, null);
    }

    /** Send one chat message with an output modality ("voice" for TTS-able replies). */
    public List<Map<String, Object>> sendChat(String message, String sessionId,
            String outputModality) {
        LinkSession s = currentSession();
        if (s == null) throw new LinkSession.LinkSessionException("no live link session");
        return s.sendChat(message, sessionId, outputModality);
    }

    /**
     * Follow the chat subscription on the live session, events
     * delivered as they arrive (see LinkSession.subscribeChatStreaming).
     * Blocks the calling thread; throws when there is no live session.
     */
    public void subscribeChatStreaming(Consumer<Map<String, Object>> onEvent,
            String sessionId, BooleanSupplier cancelled) {
        LinkSession s = currentSession();
        if (s == null) throw new LinkSession.LinkSessionException("no live link session");
        s.subscribeChatStreaming(onEvent, sessionId, cancelled);
    }

    /** Send one voice note (WAV bytes) on the live session, if any. */
    public List<Map<String, Object>> sendVoice(byte[] wavBytes, String sessionId,
            String message, String outputModality) {
        LinkSession s = currentSession();
        if (s == null) throw new LinkSession.LinkSessionException("no live link session");
        return s.sendVoice(wavBytes, sessionId, message, outputModality, 60);
    }

    public byte[] streamTts(String messageId) throws Exception {
        LinkSession s = currentSession();
        if (s == null) throw new LinkSession.LinkSessionException("no live link session");
        return s.streamTts(messageId);
    }

    /** The lock provisioning commits under (pairing.py's pairing_lock). */
    public Object pairingLock() { return pairingLock; }

    public boolean isPaired() {
        return DeviceStore.loadJson(pairingFile()) != null;
    }

    public File pairingFile() { return new File(stateDir, DeviceStore.PAIRING_FILE); }

    public void deletePairing() {
        DeviceStore.deleteJson(pairingFile());
    }

    // -- the loop ----------------------------------------------------------------

    private void runLoop() {
        double backoff = BACKOFF_INITIAL_SECONDS;
        while (running) {
            Map<String, Object> pairing;
            synchronized (pairingLock) {
                pairing = DeviceStore.loadJson(pairingFile());
            }
            if (pairing == null) {
                // Unpaired: the BLE setup window owns this state. Poll for a
                // pairing record appearing.
                if (!sleepSeconds(UNPAIRED_POLL_SECONDS)) return;
                continue;
            }
            try {
                pairing = refreshTokenIfStale(pairing);
                List<MuseApi.Vm> vms = MuseApi.fetchVms(pairing);
                MuseApi.Vm vm = MuseApi.pickVm(vms);
                if (vm == null) {
                    log("The account has no VM to link to yet; retrying shortly.");
                    if (!sleepSeconds(backoff)) return;
                    backoff = Math.min(backoff * 2, BACKOFF_MAX_SECONDS);
                    continue;
                }
                LinkSession linkSession = new LinkSession(
                        vm.vmId, vm.authToken, MuseApi.noiseHost(pairing),
                        identity.nodeId(), displayName, buildCommands(),
                        new LinkSession.Callbacks() {
                            @Override public void log(String message) {
                                LinkService.this.log(message);
                            }
                            @Override public void onUnpaired() {
                                log("The pairing was removed from the app; forgetting it.");
                                synchronized (pairingLock) {
                                    deletePairing();
                                }
                            }
                        });
                session = linkSession;
                try {
                    linkSession.open();
                    LinkSession.Outcome outcome = linkSession.run();
                    long livedMs = System.currentTimeMillis() - linkSession.registeredAtMs();
                    if (livedMs >= SESSION_RESET_SECONDS * 1000) backoff = BACKOFF_INITIAL_SECONDS;
                    if (outcome == LinkSession.Outcome.UNPAIRED) {
                        synchronized (pairingLock) {
                            deletePairing();
                        }
                        backoff = BACKOFF_INITIAL_SECONDS;
                        continue;
                    }
                    log("Link session ended; reconnecting.");
                } finally {
                    linkSession.close();
                    if (session == linkSession) session = null;
                }
            } catch (LinkSession.LinkError e) {
                log("Link session failed (" + e.kind + "): " + e.getMessage());
                if (e.kind == LinkSession.ErrorKind.AUTHORIZATION
                        || e.kind == LinkSession.ErrorKind.FORBIDDEN) {
                    backoff = Math.max(backoff, AUTH_RETRY_FLOOR_SECONDS);
                }
            } catch (Exception e) {
                log("Link attempt failed: " + e);
            }
            if (!running) return;
            if (!sleepSeconds(backoff)) return;
            backoff = Math.min(backoff * 2, BACKOFF_MAX_SECONDS);
        }
    }

    /** Rotate the device token pair once the access token is ~3 hours old. */
    private Map<String, Object> refreshTokenIfStale(Map<String, Object> pairing) {
        Object savedAtRaw = pairing.get("access_token_saved_at");
        long savedAt = savedAtRaw instanceof Number ? ((Number) savedAtRaw).longValue() : 0;
        long now = System.currentTimeMillis() / 1000;
        Object refreshToken = pairing.get("refresh_token");
        if (savedAt == 0 || !(refreshToken instanceof String)
                || now - savedAt < TOKEN_REFRESH_AGE_SECONDS) {
            return pairing;
        }
        try {
            String sdkToken = null;
            try {
                sdkToken = DeviceStore.sdkToken(stateDir);
            } catch (IllegalArgumentException ignored) { }
            Map<String, String> rotated = MuseApi.refreshDeviceToken(pairing, identity.nodeId(), sdkToken);
            String access = rotated.get("access_token");
            if (access == null || access.isEmpty()) return pairing;
            Map<String, Object> updated = new LinkedHashMap<>(pairing);
            updated.put("access_token", access);
            String newRefresh = rotated.get("refresh_token");
            if (newRefresh != null && !newRefresh.isEmpty()) {
                updated.put("refresh_token", newRefresh);
            }
            updated.put("access_token_saved_at", now);
            synchronized (pairingLock) {
                DeviceStore.saveJson(pairingFile(), updated);
            }
            log("Device token rotated.");
            return updated;
        } catch (MuseApi.HttpStatusException e) {
            if (e.status == 401) {
                log("The device token refresh was refused (401); the pairing is revoked.");
                synchronized (pairingLock) {
                    deletePairing();
                }
                throw new LinkSession.LinkSessionException("pairing revoked");
            }
            log("Device token refresh failed (HTTP " + e.status + "); using the current token.");
            return pairing;
        } catch (Exception e) {
            log("Device token refresh failed (" + e + "); using the current token.");
            return pairing;
        }
    }

    private List<LinkSession.Command> buildCommands() {
        List<LinkSession.Command> commands = new ArrayList<>();
        commands.add(new LinkSession.Command(
                "device.info",
                "Report this device's identity and link state. Takes no arguments.",
                "device.info",
                new LinkSession.CommandHandler() {
                    @Override public Map<String, Object> handle(Map<String, Object> params) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("node_id", identity.nodeId());
                        info.put("device_id", identity.deviceId());
                        info.put("model", "SHIELD Android TV (Muse Gadget Shield)");
                        info.put("platform", "android-tv");
                        info.put("version", LinkSession.CLIENT_VERSION);
                        info.put("paired", isPaired());
                        LinkSession s = currentSession();
                        info.put("link_registered", s != null);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("ok", Boolean.TRUE);
                        result.put("payload", info);
                        return result;
                    }
                }));
        return commands;
    }

    /** Interrupt-driven wake: returns false only once stop() has run. */
    private boolean sleepSeconds(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            // stop() interrupts after clearing running; a wake() interrupt
            // just ends the sleep early so the loop re-checks state.
        }
        return running;
    }

    /** Cut the current sleep short so the loop re-reads state now. */
    public void wake() {
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** Forget the pairing and drop the live session (server-driven unpair). */
    public void forgetPairingAndDropSession() {
        synchronized (pairingLock) {
            deletePairing();
        }
        LinkSession s = session;
        if (s != null) s.close();
    }

    private void log(String message) {
        try {
            logger.log(message);
        } catch (Exception ignored) { }
    }
}
