package com.jonathancruzate.musegadgetshield;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * USB serial host lane to the living-room board (v0.10.0, Wave 3).
 *
 * Framework-only: this project builds without Gradle/AndroidX, so
 * instead of a third-party usb-serial library this lane speaks to the
 * board — an ESP32-C5 on native USB (TinyUSB CDC) — directly through
 * UsbManager host APIs, doing the CDC-ACM control-line setup by hand
 * with UsbDeviceConnection.controlTransfer (SET_LINE_CODING 115200
 * 8N1 + SET_CONTROL_LINE_STATE DTR/RTS) and moving the framed
 * protocol ({@link BoardProtocol}) over the bulk IN/OUT endpoints.
 *
 * Detection: a device qualifies when its VID matches
 * {@link #BOARD_VENDOR_ID} (ESP32 native-USB TinyUSB default 0x303A),
 * or when any interface is CDC communications (class 2) or CDC data
 * (class 10). Permission uses the standard UsbManager permission
 * intent flow; attach/detach arrive via a dynamically registered
 * BroadcastReceiver (the manifest device_filter covers the
 * plug-in-while-app-closed launch path).
 *
 * The lane is fully inert when no board is attached: no threads, no
 * transfers, and every send method is a logged no-op while
 * disconnected. It sends NO chat messages and touches no other
 * subsystem — state is exposed to GadgetService (the owner) and to
 * MainActivity through getters plus a {@link Listener} callback.
 *
 * Logs: tag MuseGadgetShield, prefix BOARD, lengths/states/defined
 * fields only — never payload dumps.
 */
public final class BoardLink {
    /** ESP32 native-USB TinyUSB default vendor id (Espressif). */
    public static final int BOARD_VENDOR_ID = 0x303A;
    /** Optional product-id pin; -1 accepts any PID for the VID. */
    public static final int BOARD_PRODUCT_ID = -1;

    private static final String ACTION_USB_PERMISSION =
            "com.jonathancruzate.musegadgetshield.action.USB_PERMISSION";

    private static final int USB_CLASS_CDC = 2;
    private static final int USB_CLASS_CDC_DATA = 10;

    // CDC-ACM class requests (USB CDC spec, PSTN subclass).
    private static final int CDC_SET_LINE_CODING = 0x20;
    private static final int CDC_SET_CONTROL_LINE_STATE = 0x22;
    private static final int CDC_LINE_CODING_BAUD = 115200;

    private static final int READ_TIMEOUT_MS = 500;
    private static final int WRITE_TIMEOUT_MS = 1000;
    private static final int STATUS_SUMMARY_MAX = 180;

    /** Notified (on an arbitrary thread) whenever board state changes. */
    public interface Listener {
        void onBoardStateChanged();
    }

    private final Context context;
    private final UsbManager usbManager;
    private volatile Listener listener;

    private boolean receiverRegistered = false;
    private boolean started = false;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface commInterface;
    private UsbInterface dataInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private Thread readerThread;
    private volatile boolean readerRunning = false;

    private final BoardProtocol.Decoder decoder = new BoardProtocol.Decoder();
    private final Object writeLock = new Object();

    // UI-visible state.
    private volatile String connectionState = "no board";
    private volatile String lastStatusJson = null;
    private volatile String lastStatusSummary = null;
    private volatile int lastGesture = -1;
    private volatile int lastMode = -1;
    private volatile long lastPongAtMs = 0;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.i(GadgetService.TAG, "BOARD usb permission granted=" + granted
                        + " device=" + (dev != null));
                if (granted && dev != null) {
                    openDevice(dev);
                } else {
                    setState("permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && isBoardDevice(dev)) {
                    Log.i(GadgetService.TAG, "BOARD attached vid=0x"
                            + Integer.toHexString(dev.getVendorId())
                            + " pid=0x" + Integer.toHexString(dev.getProductId()));
                    onBoardDetected(dev);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && device != null
                        && dev.getDeviceName().equals(device.getDeviceName())) {
                    Log.i(GadgetService.TAG, "BOARD detached");
                    closeConnection();
                    setState("no board");
                }
            }
        }
    };

    public BoardLink(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /** Register the USB receiver and scan once for an attached board. */
    public synchronized void start() {
        if (started) return;
        started = true;
        if (usbManager == null) {
            Log.w(GadgetService.TAG, "BOARD usb host unavailable (no UsbManager)");
            setState("usb unavailable");
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(usbReceiver, filter);
        receiverRegistered = true;
        scanForBoard();
    }

    /** Idempotent teardown: close the connection and drop the receiver. */
    public synchronized void stop() {
        closeConnection();
        if (receiverRegistered) {
            try { context.unregisterReceiver(usbReceiver); }
            catch (Exception ignored) { }
            receiverRegistered = false;
        }
        started = false;
        setState("no board");
    }

    /** Scan currently attached USB devices for the living-room board. */
    public synchronized void scanForBoard() {
        if (usbManager == null) return;
        if (connection != null) return; // already connected
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (isBoardDevice(dev)) {
                onBoardDetected(dev);
                return;
            }
        }
        if (device == null) setState("no board");
    }

    /**
     * Board acceptance: the configured VID/PID (ESP32 native USB), or
     * any CDC (class 2) / CDC-data (class 10) interface.
     */
    public static boolean isBoardDevice(UsbDevice dev) {
        if (dev.getVendorId() == BOARD_VENDOR_ID
                && (BOARD_PRODUCT_ID < 0 || dev.getProductId() == BOARD_PRODUCT_ID)) {
            return true;
        }
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            int cls = dev.getInterface(i).getInterfaceClass();
            if (cls == USB_CLASS_CDC || cls == USB_CLASS_CDC_DATA) return true;
        }
        return false;
    }

    private void onBoardDetected(UsbDevice dev) {
        device = dev;
        if (usbManager.hasPermission(dev)) {
            openDevice(dev);
        } else {
            setState("permission pending");
            requestPermission(dev);
        }
    }

    private void requestPermission(UsbDevice dev) {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, 0);
        Log.i(GadgetService.TAG, "BOARD requesting usb permission");
        usbManager.requestPermission(dev, pi);
    }

    private synchronized void openDevice(UsbDevice dev) {
        closeConnection();
        device = dev;
        UsbDeviceConnection conn = usbManager.openDevice(dev);
        if (conn == null) {
            Log.w(GadgetService.TAG, "BOARD openDevice returned null");
            setState("open failed");
            return;
        }
        // Find the CDC comm + data interfaces and the bulk endpoints.
        UsbInterface comm = null;
        UsbInterface data = null;
        UsbInterface fallback = null;
        UsbEndpoint bulkIn = null;
        UsbEndpoint bulkOut = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            int cls = iface.getInterfaceClass();
            if (cls == USB_CLASS_CDC && comm == null) comm = iface;
            if (cls == USB_CLASS_CDC_DATA && data == null) data = iface;
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
            }
            if (in != null && out != null && fallback == null) {
                fallback = iface;
                if (data == null || bulkIn == null) { bulkIn = in; bulkOut = out; }
            }
        }
        UsbInterface bulkIface = data != null ? data : fallback;
        if (bulkIface != null && (bulkIn == null || bulkOut == null)) {
            for (int e = 0; e < bulkIface.getEndpointCount(); e++) {
                UsbEndpoint ep = bulkIface.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) bulkOut = ep;
            }
        }
        if (bulkIface == null || bulkIn == null || bulkOut == null) {
            Log.w(GadgetService.TAG, "BOARD no bulk IN/OUT endpoints found");
            conn.close();
            setState("no bulk endpoints");
            return;
        }
        if (comm != null && !conn.claimInterface(comm, true)) {
            Log.w(GadgetService.TAG, "BOARD claim comm interface failed");
        }
        if (!conn.claimInterface(bulkIface, true)) {
            Log.w(GadgetService.TAG, "BOARD claim data interface failed");
            conn.close();
            setState("claim failed");
            return;
        }
        connection = conn;
        commInterface = comm;
        dataInterface = bulkIface;
        endpointIn = bulkIn;
        endpointOut = bulkOut;
        decoder.reset();

        setupCdcAcm(conn, comm != null ? comm : bulkIface);
        setState("connected");
        Log.i(GadgetService.TAG, "BOARD connected vid=0x"
                + Integer.toHexString(dev.getVendorId())
                + " pid=0x" + Integer.toHexString(dev.getProductId()));
        startReader();
        // Ask for a first status snapshot right away.
        requestStatus();
    }

    /**
     * CDC-ACM setup by hand: SET_LINE_CODING (115200 8N1) and
     * SET_CONTROL_LINE_STATE (DTR + RTS asserted), class requests on
     * the communications interface. Results are logged as states only;
     * a board that ignores line coding still gets its bulk lane.
     */
    private void setupCdcAcm(UsbDeviceConnection conn, UsbInterface controlIface) {
        int index = controlIface.getId();
        byte[] lineCoding = new byte[7];
        lineCoding[0] = (byte) (CDC_LINE_CODING_BAUD & 0xFF);
        lineCoding[1] = (byte) ((CDC_LINE_CODING_BAUD >> 8) & 0xFF);
        lineCoding[2] = (byte) ((CDC_LINE_CODING_BAUD >> 16) & 0xFF);
        lineCoding[3] = (byte) ((CDC_LINE_CODING_BAUD >> 24) & 0xFF);
        lineCoding[4] = 0; // 1 stop bit
        lineCoding[5] = 0; // no parity
        lineCoding[6] = 8; // 8 data bits
        int lc = conn.controlTransfer(0x21, CDC_SET_LINE_CODING, 0, index,
                lineCoding, lineCoding.length, WRITE_TIMEOUT_MS);
        int cls = conn.controlTransfer(0x21, CDC_SET_CONTROL_LINE_STATE, 3, index,
                null, 0, WRITE_TIMEOUT_MS);
        Log.i(GadgetService.TAG, "BOARD cdc setup lineCoding=" + lc
                + " controlLineState=" + cls);
    }

    private void startReader() {
        readerRunning = true;
        readerThread = new Thread(new Runnable() {
            @Override public void run() { readerLoop(); }
        }, "board-usb-reader");
        readerThread.start();
    }

    private void readerLoop() {
        byte[] buffer = new byte[512];
        while (readerRunning) {
            UsbDeviceConnection conn = connection;
            UsbEndpoint in = endpointIn;
            if (conn == null || in == null) break;
            int n = conn.bulkTransfer(in, buffer, buffer.length, READ_TIMEOUT_MS);
            if (n > 0) {
                List<BoardProtocol.Frame> frames = decoder.feed(buffer, 0, n);
                for (BoardProtocol.Frame frame : frames) handleFrame(frame);
            } else if (n < 0 && connection == null) {
                break;
            }
            // n < 0 with the connection alive is a read timeout: loop.
        }
        Log.i(GadgetService.TAG, "BOARD reader stopped badCrc="
                + decoder.badCrcCount() + " decoded=" + decoder.decodedCount());
    }

    private void handleFrame(BoardProtocol.Frame frame) {
        switch (frame.type) {
            case BoardProtocol.TYPE_STATUS_RESP: {
                String json = new String(frame.payload, StandardCharsets.UTF_8);
                lastStatusJson = json;
                lastStatusSummary = summarizeStatus(json);
                Log.i(GadgetService.TAG, "BOARD status_resp len=" + frame.payload.length
                        + " summaryLen=" + (lastStatusSummary != null
                                ? lastStatusSummary.length() : 0));
                notifyChanged();
                break;
            }
            case BoardProtocol.TYPE_BUTTON_EVENT: {
                if (frame.payload.length >= 1) {
                    lastGesture = frame.payload[0] & 0xFF;
                    Log.i(GadgetService.TAG, "BOARD button gesture=" + lastGesture
                            + " (" + BoardProtocol.gestureName(lastGesture) + ")");
                    notifyChanged();
                }
                break;
            }
            case BoardProtocol.TYPE_MODE_EVENT: {
                if (frame.payload.length >= 1) {
                    lastMode = frame.payload[0] & 0xFF;
                    Log.i(GadgetService.TAG, "BOARD mode event mode=" + lastMode
                            + " (" + BoardProtocol.modeName(lastMode) + ")");
                    notifyChanged();
                }
                break;
            }
            case BoardProtocol.TYPE_PONG: {
                lastPongAtMs = System.currentTimeMillis();
                Log.i(GadgetService.TAG, "BOARD pong");
                notifyChanged();
                break;
            }
            case BoardProtocol.TYPE_PING: {
                // Board-initiated ping: answer with a PONG.
                sendFrame(BoardProtocol.TYPE_PONG, null);
                break;
            }
            default:
                Log.i(GadgetService.TAG, "BOARD frame type=0x"
                        + Integer.toHexString(frame.type)
                        + " len=" + frame.payload.length);
                break;
        }
    }

    /**
     * Flatten a STATUS_RESP JSON object into "key=value" pairs for the
     * couch UI. Scalars only, capped in length; a non-JSON payload is
     * shown length-only in logs and summarised as unparsed here.
     */
    private static String summarizeStatus(String json) {
        try {
            Map<String, Object> obj = Json.parseObject(json);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map || v instanceof List) continue;
                if (sb.length() > 0) sb.append("  ");
                sb.append(e.getKey()).append('=').append(String.valueOf(v));
                if (sb.length() >= STATUS_SUMMARY_MAX) break;
            }
            String out = sb.toString();
            return out.length() > STATUS_SUMMARY_MAX
                    ? out.substring(0, STATUS_SUMMARY_MAX) : out;
        } catch (Exception e) {
            return "(unparsed status, " + json.length() + " chars)";
        }
    }

    private boolean sendFrame(int type, byte[] payload) {
        UsbDeviceConnection conn = connection;
        UsbEndpoint out = endpointOut;
        if (conn == null || out == null) {
            Log.i(GadgetService.TAG, "BOARD send skipped (not connected) type=0x"
                    + Integer.toHexString(type));
            return false;
        }
        byte[] frame = BoardProtocol.encode(type, payload);
        synchronized (writeLock) {
            // Frames are small (bounded by MAX_PAYLOAD + 7); one bulk
            // transfer carries a whole frame in practice. A short
            // positive return is logged, not silently retried (a retry
            // from offset 0 would duplicate the frame head).
            int n = conn.bulkTransfer(out, frame, frame.length, WRITE_TIMEOUT_MS);
            if (n < 0) {
                Log.w(GadgetService.TAG, "BOARD write failed type=0x"
                        + Integer.toHexString(type));
                return false;
            }
            if (n != frame.length) {
                Log.w(GadgetService.TAG, "BOARD write short type=0x"
                        + Integer.toHexString(type) + " wrote=" + n
                        + " of=" + frame.length);
                return false;
            }
        }
        return true;
    }

    // ---- Public control surface (used by GadgetService / MainActivity) ----

    public boolean isConnected() { return connection != null; }

    public boolean requestStatus() {
        boolean ok = sendFrame(BoardProtocol.TYPE_STATUS_REQ, null);
        Log.i(GadgetService.TAG, "BOARD requestStatus sent=" + ok);
        return ok;
    }

    public boolean setLed(int pattern) {
        boolean ok = sendFrame(BoardProtocol.TYPE_LED_SET,
                new byte[]{(byte) pattern});
        Log.i(GadgetService.TAG, "BOARD setLed pattern=" + pattern + " sent=" + ok);
        return ok;
    }

    public boolean setMode(int mode) {
        boolean ok = sendFrame(BoardProtocol.TYPE_MODE_SET,
                new byte[]{(byte) mode});
        Log.i(GadgetService.TAG, "BOARD setMode mode=" + mode
                + " (" + BoardProtocol.modeName(mode) + ") sent=" + ok);
        return ok;
    }

    public boolean ping() {
        boolean ok = sendFrame(BoardProtocol.TYPE_PING, null);
        Log.i(GadgetService.TAG, "BOARD ping sent=" + ok);
        return ok;
    }

    // ---- State getters for the UI ----

    public String connectionState() { return connectionState; }
    public String lastStatusJson() { return lastStatusJson; }
    public String lastStatusSummary() { return lastStatusSummary; }
    public int lastGesture() { return lastGesture; }
    public int lastMode() { return lastMode; }
    public long lastPongAtMs() { return lastPongAtMs; }
    public int badFrameCount() { return decoder.badCrcCount(); }

    /** Compact multi-line state for MainActivity's board area. */
    public String boardSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Living-room board: ").append(connectionState);
        sb.append("\nStatus: ")
                .append(lastStatusSummary != null ? lastStatusSummary : "—");
        sb.append("\nLast button: ")
                .append(lastGesture >= 0
                        ? BoardProtocol.gestureName(lastGesture) : "—")
                .append("    Mode: ")
                .append(lastMode >= 0 ? BoardProtocol.modeName(lastMode) : "—");
        if (badFrameCount() > 0) {
            sb.append("    Bad frames: ").append(badFrameCount());
        }
        return sb.toString();
    }

    private synchronized void closeConnection() {
        readerRunning = false;
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (connection != null) {
            try {
                if (dataInterface != null) connection.releaseInterface(dataInterface);
                if (commInterface != null && commInterface != dataInterface) {
                    connection.releaseInterface(commInterface);
                }
            } catch (Exception ignored) { }
            connection.close();
            connection = null;
        }
        commInterface = null;
        dataInterface = null;
        endpointIn = null;
        endpointOut = null;
    }

    private void setState(String state) {
        connectionState = state;
        notifyChanged();
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) l.onBoardStateChanged();
    }
}
