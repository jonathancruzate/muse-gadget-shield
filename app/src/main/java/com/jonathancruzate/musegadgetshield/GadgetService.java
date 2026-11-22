package com.jonathancruzate.musegadgetshield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Muse Gadget host for the NVIDIA Shield, v0.4.1: the full gadget stack.
 *
 * BLE setup (pairing v5, SDK ble_setup.py port in SetupController +
 * Pairing) runs over the SDK setup GATT service while the pair window is
 * open; once provisioned, LinkService holds the steady-state Noise link
 * session. The pair window mirrors the SDK: advertising runs for at most
 * 600 s, then stops and the adapter name is restored.
 *
 * Advertising layout (proven in v0.3 — do not change): legacy 31-byte
 * packets cannot carry name + 128-bit UUID + manufacturer data together
 * (DATA_TOO_LARGE), so the advertising packet carries the service UUID
 * and the paired flag, and the device name rides the scan response.
 */
public class GadgetService extends Service {
    public static final String TAG = "MuseGadgetShield";
    // Friendly name the link registers under (link.register display_name),
    // kept separate from the BLE pairing name — mirrors the Windows port's
    // DISPLAY_NAME = "PC".
    public static final String DISPLAY_NAME = "Shield TV";
    public static final String ACTION_STOP = "com.jonathancruzate.musegadgetshield.STOP";
    public static final String ACTION_FORCE_PAIR = "com.jonathancruzate.musegadgetshield.FORCE_PAIR";
    public static final String ACTION_SELFTEST = "com.jonathancruzate.musegadgetshield.SELFTEST";
    // On-demand audio capture probe (v0.4.6, see AudioProbe): measures real
    // recordings per input device on a worker thread; touches no BLE/link
    // or pairing state.
    public static final String ACTION_PROBE_AUDIO =
            "com.jonathancruzate.musegadgetshield.action.PROBE_AUDIO";
    // Pre-opened-recorder hold probe (v0.4.7, see AudioProbe.runHold):
    // opens + starts the BUS and builtin recorders while idle and logs
    // per-second levels for 15 s, catching what a remote-mic button
    // press that begins mid-window does to already-running recorders.
    // Touches no BLE/link or pairing state.
    public static final String ACTION_PROBE_HOLD =
            "com.jonathancruzate.musegadgetshield.action.PROBE_HOLD";
    // System speech-recognition probe (v0.5.1, see SpeechProbe):
    // AudioRecord can never hear the remote from this app, but in-app
    // voice search (YouTube etc.) rides the SYSTEM SpeechRecognizer
    // service, whose capture happens in Google's privileged stack.
    // This action runs one 25 s listening session and logs every
    // callback, deciding whether that path is open to this app.
    // Touches no BLE/link or pairing state.
    public static final String ACTION_PROBE_SPEECH =
            "com.jonathancruzate.musegadgetshield.action.PROBE_SPEECH";
    // Voice-relay activation (v0.5.0, see VoiceRelay): fired by
    // MainActivity when the "Voice relay to phone" toggle flips OFF→ON
    // (there is no Talk button — the flip itself is the signal). The
    // service sends the relay text message over the live link session's
    // /chat/stream — the same path LinkSession.sendVoice posts to, with
    // a text payload (LinkSession.sendChat) — so voice mode activates
    // on Jonathan's phone with the Shield TV context. Sent exactly once
    // per enable; a restart with the toggle already ON does not resend.
    public static final String ACTION_RELAY_ON =
            "com.jonathancruzate.musegadgetshield.action.RELAY_ON";
    // Voice mode (v0.6.0, see VoiceActivity): launch the full-screen
    // avatar voice UI. The in-app triggers are the KeyTriggerService
    // keycode consumption and MainActivity's Voice mode entry; this
    // action is the remote/test lane (adb startservice).
    public static final String ACTION_VOICE_MODE =
            "com.jonathancruzate.musegadgetshield.action.VOICE_MODE";
    /**
     * The live link supervisor, for same-process UI (VoiceActivity's
     * chat turns). Set in ensureStack(); null until the stack exists.
     */
    public static volatile LinkService activeLinkService;

    /**
     * The living-room board USB lane (v0.10.0), for same-process UI
     * (MainActivity's board-status area). Owned by this service;
     * created in onCreate, stopped in shutdown(). Fully inert when
     * no board is attached.
     */
    public static volatile BoardLink activeBoardLink;
    private BoardLink boardLink;

    // Setup GATT layout, from the upstream SDK.
    public static final UUID SERVICE_UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c");
    public static final UUID RX_UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01");
    public static final UUID TX_UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c");
    public static final UUID CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int PAIR_WINDOW_MS = 600_000;
    private static final int MANUFACTURER_ID = 0xFFFF;
    private static final String ORIGINAL_NAME_FALLBACK = "SHIELD";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom secureRandom = new SecureRandom();

    private BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic txChar;
    private BluetoothDevice connectedDevice;
    private volatile int currentMtu = BleFraming.DEFAULT_ATT_MTU;
    private boolean notificationsEnabled = false;

    private String originalName;
    private boolean nameChanged = false;
    private boolean advertising = false;
    private boolean pairWindowOpen = false;
    private boolean started = false;

    private Identity identity;
    private Pairing.PairingSession pairingSession;
    private SetupController setupController;
    private LinkService linkService;

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            Log.i(TAG, "advertise onStartSuccess name=" + identity.bleName()
                    + " paired=" + isPaired());
        }
        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            // 1=DATA_TOO_LARGE 2=TOO_MANY_ADVERTISERS 3=ALREADY_STARTED
            // 4=INTERNAL_ERROR 5=FEATURE_UNSUPPORTED
            Log.e(TAG, "advertise onStartFailure errorCode=" + errorCode);
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            Log.i(TAG, "gatt onServiceAdded status=" + status + " uuid=" + service.getUuid());
        }
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "gatt connection device=" + device.getAddress()
                    + " status=" + status + " newState=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevice = device;
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (device.equals(connectedDevice)) connectedDevice = null;
                notificationsEnabled = false;
                currentMtu = BleFraming.DEFAULT_ATT_MTU;
                if (setupController != null) setupController.onDisconnect();
            }
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
            if (RX_UUID.equals(characteristic.getUuid()) && setupController != null) {
                if (preparedWrite) {
                    // Flushed by onExecuteWrite.
                    appendPrepared(offset, value);
                } else {
                    setupController.onWrite(value);
                }
            }
        }
        @Override public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            if (gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            if (execute) {
                byte[] assembled = takePrepared();
                if (assembled.length > 0 && setupController != null) {
                    setupController.onWrite(assembled);
                }
            } else {
                takePrepared();
            }
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            if (gattServer == null) return;
            byte[] payload = Json.stringify(setupController.deviceInfo())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] slice = offset < payload.length
                    ? Arrays.copyOfRange(payload, offset, payload.length) : new byte[0];
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            }
            if (CCC_UUID.equals(descriptor.getUuid())) {
                notificationsEnabled = Arrays.equals(
                        value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                Log.i(TAG, "TX notifications " + (notificationsEnabled ? "enabled" : "disabled"));
            }
        }
        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            currentMtu = mtu;
            Log.i(TAG, "gatt MTU=" + mtu);
        }
    };

    // Prepared-write assembly (the Muse app's long writes may use it).
    private byte[] preparedBuffer = new byte[0];
    private synchronized void appendPrepared(int offset, byte[] value) {
        if (offset == 0) {
            preparedBuffer = value.clone();
        } else if (offset == preparedBuffer.length) {
            byte[] next = Arrays.copyOf(preparedBuffer, preparedBuffer.length + value.length);
            System.arraycopy(value, 0, next, offset, value.length);
            preparedBuffer = next;
        } else if (offset < preparedBuffer.length) {
            byte[] next = Arrays.copyOf(preparedBuffer,
                    Math.max(preparedBuffer.length, offset + value.length));
            System.arraycopy(value, 0, next, offset, value.length);
            preparedBuffer = next;
        }
    }
    private synchronized byte[] takePrepared() {
        byte[] out = preparedBuffer;
        preparedBuffer = new byte[0];
        return out;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "gadget", "Muse Gadget", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(1, buildNotification("Starting…"));
        Log.i(TAG, "service onCreate (v" + LinkSession.CLIENT_VERSION + ")");
        // Living-room board USB lane (v0.10.0): inert without a board.
        boardLink = new BoardLink(this);
        activeBoardLink = boardLink;
        boardLink.start();
        if (VoiceRelay.isOn(this)) {
            // Relay mode persisted across a restart: this device stays a
            // non-microphone endpoint (idempotent strip attempt; capture
            // paths gate on the flag in onStartCommand/AudioProbe).
            Log.i(TAG, "RELAY voice relay is ON; this device will not capture audio");
            VoiceRelay.stripMicPermission(this);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "STOP action received");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SELFTEST.equals(action)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    SelfTest.run(new SelfTest.Logger() {
                        @Override public void log(String message) { Log.i(TAG, message); }
                    });
                }
            }, "selftest").start();
            return START_STICKY;
        }
        if (ACTION_RELAY_ON.equals(action)) {
            Log.i(TAG, "RELAY activation action received");
            if (!VoiceRelay.isOn(this)) {
                Log.i(TAG, "RELAY activation ignored: voice relay is OFF");
                return START_STICKY;
            }
            ensureStack();
            new Thread(new Runnable() {
                @Override public void run() { sendRelayActivation(); }
            }, "relay-activation").start();
            return START_STICKY;
        }
        if (ACTION_PROBE_AUDIO.equals(action)) {
            if (VoiceRelay.isOn(this)) {
                // Voice relay ON: this device is not a microphone endpoint.
                Log.i(TAG, "AUDPROBE skipped: voice relay on");
                return START_STICKY;
            }
            Log.i(TAG, "AUDPROBE action received");
            new Thread(new Runnable() {
                @Override public void run() {
                    AudioProbe.run(GadgetService.this, new AudioProbe.Logger() {
                        @Override public void log(String message) { Log.i(TAG, message); }
                    });
                }
            }, "audprobe").start();
            return START_STICKY;
        }
        if (ACTION_PROBE_HOLD.equals(action)) {
            if (VoiceRelay.isOn(this)) {
                // Voice relay ON: this device is not a microphone endpoint.
                Log.i(TAG, "AUDHOLD skipped: voice relay on");
                return START_STICKY;
            }
            Log.i(TAG, "AUDHOLD action received");
            new Thread(new Runnable() {
                @Override public void run() {
                    AudioProbe.runHold(GadgetService.this, new AudioProbe.Logger() {
                        @Override public void log(String message) { Log.i(TAG, message); }
                    });
                }
            }, "audhold").start();
            return START_STICKY;
        }
        if (ACTION_VOICE_MODE.equals(action)) {
            Log.i(TAG, "VOICE action received; launching voice mode");
            ensureStack();
            startGadget();
            Intent voice = new Intent(this, VoiceActivity.class);
            voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(voice);
            } catch (Exception e) {
                Log.w(TAG, "VOICE launch failed: " + e.getClass().getSimpleName());
            }
            return START_STICKY;
        }
        if (ACTION_PROBE_SPEECH.equals(action)) {
            if (VoiceRelay.isOn(this)) {
                // Voice relay ON: this device is not a mic endpoint.
                Log.i(TAG, "SPEECH skipped: voice relay on");
                return START_STICKY;
            }
            Log.i(TAG, "SPEECH action received");
            new Thread(new Runnable() {
                @Override public void run() {
                    SpeechProbe.run(GadgetService.this, new SpeechProbe.Logger() {
                        @Override public void log(String message) { Log.i(TAG, message); }
                    });
                }
            }, "speechprobe").start();
            return START_STICKY;
        }
        ensureStack();
        if (ACTION_FORCE_PAIR.equals(action)) {
            Log.i(TAG, "FORCE_PAIR: reopening the pair window");
            openPairWindow();
            return START_STICKY;
        }
        startGadget();
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, "gadget")
                .setContentTitle("Muse Gadget for Shield TV")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, buildNotification(text));
    }

    private File stateDir() { return getFilesDir(); }

    private boolean isPaired() {
        return DeviceStore.loadJson(new File(stateDir(), DeviceStore.PAIRING_FILE)) != null;
    }

    private String sdkTokenOrNull() {
        try {
            return DeviceStore.sdkToken(stateDir());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "stored SDK token is invalid; pairing will proceed without it");
            return null;
        }
    }

    /**
     * Send the voice-relay activation message (VoiceRelay.buildMessage)
     * as a chat turn on the live link session: LinkService.sendChat →
     * LinkSession.sendChat posts a text payload to /chat/stream, the
     * same session path sendVoice uses for WAV turns. Runs once per
     * OFF→ON toggle flip (the only caller is the ACTION_RELAY_ON
     * handler). RELAY log lines carry host + outcome only — never
     * tokens or response bodies.
     */
    private void sendRelayActivation() {
        Map<String, Object> pairing =
                DeviceStore.loadJson(new File(stateDir(), DeviceStore.PAIRING_FILE));
        String host = pairing != null ? MuseApi.noiseHost(pairing) : "unknown";
        if (linkService == null) {
            Log.w(TAG, "RELAY activation failed host=" + host + " error=no link service");
            return;
        }
        String message = VoiceRelay.buildMessage(new java.util.Date());
        try {
            java.util.List<Map<String, Object>> replies = linkService.sendChat(message, null);
            Log.i(TAG, "RELAY activation sent host=" + host
                    + " path=/chat/stream replies=" + replies.size());
        } catch (Exception e) {
            Log.w(TAG, "RELAY activation failed host=" + host
                    + " error=" + relayErrorLabel(e));
        }
    }

    /** Host/status-only failure label: an HTTP status if one is named, else the exception class. */
    private static String relayErrorLabel(Exception e) {
        String msg = e.getMessage();
        if (msg != null) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(msg);
            if (m.find()) return "HTTP " + m.group(1);
        }
        return e.getClass().getSimpleName();
    }

    private synchronized void ensureStack() {
        if (setupController != null) return;
        // Identity: identity.json wins; the v0.3 prototype's bare suffix is
        // migrated so the advertised node id / BLE name (…FA76F8) survive.
        SharedPreferences prefs = getSharedPreferences("identity", Context.MODE_PRIVATE);
        String legacySuffix = prefs.getString("suffix", null);
        identity = Identity.loadOrCreate(stateDir(), legacySuffix, secureRandom);
        Log.i(TAG, "identity node=" + identity.nodeId() + " bleName=" + identity.bleName()
                + " deviceId=" + identity.deviceId());

        pairingSession = new Pairing.PairingSession(
                identity.nodeId(), identity.deviceId(), identity.mac,
                "shield-" + LinkSession.CLIENT_VERSION, sdkTokenOrNull());

        linkService = new LinkService(stateDir(), identity, DISPLAY_NAME,
                new LinkService.Logger() {
                    @Override public void log(String message) { Log.i(TAG, message); }
                });
        activeLinkService = linkService;

        setupController = new SetupController(
                pairingSession, identity, LinkSession.CLIENT_VERSION,
                new SetupController.Transport() {
                    @Override public void sendPackets(java.util.List<byte[]> packets) {
                        notifyPackets(packets);
                    }
                    @Override public int mtu() { return currentMtu; }
                    @Override public void disconnect(double delaySeconds) {
                        final BluetoothDevice device = connectedDevice;
                        if (device == null || gattServer == null) return;
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (gattServer != null) {
                                    try { gattServer.cancelConnection(device); }
                                    catch (Exception ignored) { }
                                }
                            }
                        }, (long) (delaySeconds * 1000));
                    }
                },
                new SetupController.Network() {
                    @Override public boolean isOnline() { return checkOnline(); }
                    @Override public Map<String, Object> currentConnectionEntry() {
                        return connectionEntry();
                    }
                },
                new SetupController.Provision() {
                    @Override public void provision(SetupController.Credentials credentials,
                            SetupController.Committer commit) throws SetupController.ProvisionFailed {
                        doProvision(credentials, commit);
                    }
                },
                new Runnable() {
                    @Override public void run() { onProvisioned(); }
                });
        setupController.start();
    }

    /** Verify the account credentials against the API, then persist them. */
    private void doProvision(SetupController.Credentials credentials,
            final SetupController.Committer commit) throws SetupController.ProvisionFailed {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("access_token", credentials.accessToken);
        record.put("refresh_token", credentials.refreshToken);
        record.put("token_type", "device");
        record.put("username", credentials.username != null ? credentials.username : "");
        record.put("api_url", httpsOrEmpty(credentials.apiUrl));
        record.put("api_url_v2", httpsOrEmpty(credentials.apiUrlV2));
        record.put("noise_host", credentials.noiseHost != null ? credentials.noiseHost : "");
        // Diagnostics (v0.4.4): which API roots the app handed us (hosts
        // only — never full URLs or tokens) and which one fetchVms uses.
        boolean useV2 = !((String) record.get("api_url_v2")).isEmpty();
        Log.i(TAG, "provision verify: api_url host=" + MuseApi.hostOf((String) record.get("api_url"))
                + ", api_url_v2 host=" + MuseApi.hostOf((String) record.get("api_url_v2"))
                + ", fetch_vms root=" + (useV2 ? "api_url_v2" : "default")
                + " host=" + MuseApi.hostOf(MuseApi.apiRoot(record)));
        // Verify before persisting (pairing.py): the account must have a VM.
        try {
            java.util.List<MuseApi.Vm> vms = MuseApi.fetchVms(record);
            if (vms.isEmpty()) {
                Log.w(TAG, "provisioning refused: the account has no VM yet");
                throw new SetupController.ProvisionFailed("auth_failed");
            }
        } catch (SetupController.ProvisionFailed e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "provisioning credential check failed: " + e);
            throw new SetupController.ProvisionFailed("auth_failed");
        }
        record.put("access_token_saved_at", System.currentTimeMillis() / 1000);
        record.put("sdk_token_hint", DeviceStore.sdkTokenHint(sdkTokenOrNull()));
        final Map<String, Object> toSave = record;
        boolean committed = commit.commit(new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                synchronized (linkService.pairingLock()) {
                    DeviceStore.saveJson(
                            new File(stateDir(), DeviceStore.PAIRING_FILE), toSave);
                }
                return true;
            }
        });
        if (!committed) {
            throw new SetupController.ProvisionFailed("auth_failed");
        }
    }

    private static String httpsOrEmpty(String url) {
        return url != null && url.startsWith("https://") ? url : "";
    }

    private void onProvisioned() {
        Log.i(TAG, "provisioned: closing the pair window and starting the link");
        updateNotification("Paired — link connecting");
        // Give the auth_ok status a moment to reach the phone first.
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                closePairWindow();
                if (linkService != null) linkService.wake();
            }
        }, 1500);
    }

    private void notifyPackets(java.util.List<byte[]> packets) {
        BluetoothDevice device = connectedDevice;
        if (gattServer == null || txChar == null || device == null) {
            Log.w(TAG, "notify dropped: no connected device");
            return;
        }
        for (int i = 0; i < packets.size(); i++) {
            if (i > 0) {
                // Stagger chunks: phones drop back-to-back notifications.
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            txChar.setValue(packets.get(i));
            boolean ok = gattServer.notifyCharacteristicChanged(device, txChar, false);
            if (!ok) Log.w(TAG, "notifyCharacteristicChanged returned false");
        }
    }

    private boolean checkOnline() {
        // The SDK probes TCP to the API host; the Shield is ethernet-first.
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("api.muse.ai", 443), 5000);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private Map<String, Object> connectionEntry() {
        String label = currentSsid();
        if (label == null || label.isEmpty()) label = "Use current connection";
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ssid", label);
        entry.put("rssi", -40L);
        entry.put("secure", Boolean.FALSE);
        return entry;
    }

    private String currentSsid() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network network = cm.getActiveNetwork();
            if (network == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            ssid = ssid.replaceAll("^\"|\"$", "");
            return "<unknown ssid>".equals(ssid) ? null : ssid;
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void startGadget() {
        if (started) { Log.i(TAG, "already running"); return; }
        ensureStack();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "bluetooth adapter missing/disabled");
            stopSelf();
            return;
        }
        originalName = adapter.getName();
        advertiser = adapter.getBluetoothLeAdvertiser();
        Log.i(TAG, "adapterName=" + originalName + " gadgetName=" + identity.bleName()
                + " advertiserNull=" + (advertiser == null)
                + " multipleAdvertisement=" + adapter.isMultipleAdvertisementSupported());
        if (advertiser == null) {
            Log.e(TAG, "PERIPHERAL UNSUPPORTED: getBluetoothLeAdvertiser() == null");
            stopSelf();
            return;
        }
        gattServer = bm.openGattServer(this, gattCallback);
        if (gattServer == null) {
            Log.e(TAG, "GATT SERVER FAILED: openGattServer() == null");
            stopSelf();
            return;
        }
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        txChar = new BluetoothGattCharacteristic(TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        txChar.addDescriptor(new BluetoothGattDescriptor(CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(rx);
        service.addCharacteristic(txChar);
        boolean added = gattServer.addService(service);
        Log.i(TAG, "gatt addService returned=" + added);
        started = true;

        linkService.start();
        if (isPaired()) {
            updateNotification("Paired — link connecting");
            Log.i(TAG, "paired already; link loop running, pair window stays closed");
        } else {
            openPairWindow();
        }
    }

    /** Open (or reopen) the 600 s advertising window under the gadget name. */
    private synchronized void openPairWindow() {
        if (pairWindowOpen) return;
        if (adapter == null || advertiser == null) {
            Log.w(TAG, "openPairWindow before startGadget; starting first");
            startGadget();
            if (pairWindowOpen) return; // startGadget opened it (unpaired path)
        }
        pairWindowOpen = true;
        updateNotification("Pair window open — " + identity.bleName());
        // Advertise immediately without the name, then rename + re-advertise
        // with the name in the scan response (v0.3-proven two-phase start).
        startAdvertising();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pairWindowOpen) return;
                stopAdvertising();
                boolean renamed = adapter.setName(identity.bleName());
                nameChanged = renamed;
                for (int i = 0; i < 15 && !identity.bleName().equals(adapter.getName()); i++) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
                Log.i(TAG, "adapter setName(" + identity.bleName() + ")=" + renamed
                        + " nameNow=" + adapter.getName());
                if (pairWindowOpen) startAdvertising();
            }
        }, 1500);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!pairWindowOpen) return;
                Log.i(TAG, "pair window expired");
                closePairWindow();
                if (!isPaired()) {
                    Log.i(TAG, "still unpaired at window end; stopping service");
                    shutdown();
                    stopSelf();
                }
            }
        }, PAIR_WINDOW_MS);
    }

    private synchronized void closePairWindow() {
        pairWindowOpen = false;
        stopAdvertising();
        restoreAdapterName();
    }

    private void startAdvertising() {
        if (advertiser == null || advertising) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        // Name + UUID + manufacturer data exceeds the legacy packet, so the
        // name goes in the scan response (v0.3-proven layout).
        boolean renamed = identity.bleName().equals(adapter.getName());
        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .addManufacturerData(MANUFACTURER_ID, new byte[]{isPaired() ? (byte) 1 : (byte) 0});
        if (renamed) {
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            advertiser.startAdvertising(settings, dataBuilder.build(), scanResponse, advCallback);
        } else {
            advertiser.startAdvertising(settings, dataBuilder.build(), advCallback);
        }
        Log.i(TAG, "startAdvertising called (scanResponseName=" + renamed + ")");
    }

    private void stopAdvertising() {
        if (advertiser != null && advertising) {
            try { advertiser.stopAdvertising(advCallback); } catch (Exception e) {
                Log.w(TAG, "stopAdvertising: " + e);
            }
            advertising = false;
        }
    }

    private void restoreAdapterName() {
        if (nameChanged && adapter != null) {
            String target = originalName != null ? originalName : ORIGINAL_NAME_FALLBACK;
            boolean restored = adapter.setName(target);
            Log.i(TAG, "adapter name restored to " + target + "=" + restored
                    + " nameNow=" + adapter.getName());
            nameChanged = false;
        }
    }

    private synchronized void shutdown() {
        handler.removeCallbacksAndMessages(null);
        if (boardLink != null) { boardLink.stop(); boardLink = null; activeBoardLink = null; }
        if (setupController != null) setupController.stop();
        if (linkService != null) linkService.stop();
        closePairWindow();
        if (gattServer != null) { gattServer.close(); gattServer = null; }
        started = false;
        Log.i(TAG, "shutdown complete");
    }

    @Override public void onDestroy() {
        shutdown();
        Log.i(TAG, "service onDestroy");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
