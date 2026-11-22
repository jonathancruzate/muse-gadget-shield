package com.jonathancruzate.musegadgetshield;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Arrays;
import java.util.Locale;

/**
 * On-demand audio capture probe (v0.4.6).
 *
 * The v0.3 probe only checked AudioRecord.getState() at construction,
 * with no explicit input-device routing and no actual recording, and
 * never while the Shield remote's mic button was held (the remote
 * streams only while held — see the mic verdict in the README). This
 * probe closes those gaps: for every enumerated input device it routes
 * capture to that specific device (AudioRecord.setPreferredDevice —
 * the API 30 AudioRecord.Builder has no routing method, so routing is
 * set on the instance before startRecording), starts recording for
 * real, discards ~700 ms, then measures peak / RMS amplitude over
 * ~700 ms of 16-bit samples.
 *
 * Pure measurement: it creates and releases its own AudioRecord
 * instances and touches nothing else — no BLE, link, or pairing state.
 * Every line is logged through the Logger with the "AUDPROBE" prefix.
 *
 * v0.4.7 adds {@link #runHold}: the one configuration the sweep cannot
 * test. Live runs showed that while the remote's mic button is held
 * (remote streaming to Assistant) every NEW AudioRecord construction
 * throws UnsupportedOperationException — the input is exclusively
 * claimed during streaming. runHold instead creates and STARTS two
 * recorders while idle (BUS + VOICE_COMMUNICATION, builtin + MIC),
 * keeps them open, and logs peak / RMS per 1-second window for 15 s,
 * so a button press that begins mid-window shows whether a pre-opened
 * recorder gets fed, gets silenced, or gets killed. Lines carry the
 * "AUDHOLD" prefix.
 */
final class AudioProbe {
    private AudioProbe() {}

    interface Logger {
        void log(String message);
    }

    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 6
            MediaRecorder.AudioSource.MIC,                 // 1
    };
    private static final int RATE_PREFERRED = 48000;
    private static final int RATE_FALLBACK = 16000;
    private static final long DISCARD_MS = 700;
    private static final long MEASURE_MS = 700;
    private static final long TIME_CAP_MS = 60_000;

    static void run(Context context, Logger logger) {
        if (VoiceRelay.isOn(context)) {
            // Voice relay ON (v0.5.0): this device is not a mic endpoint.
            logger.log("AUDPROBE skipped: voice relay on");
            return;
        }
        long startMs = System.currentTimeMillis();
        long deadlineMs = startMs + TIME_CAP_MS;
        try {
            boolean granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                logger.log("AUDPROBE start failed: no AudioManager");
                logger.log("AUDPROBE DONE");
                return;
            }
            AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            logger.log("AUDPROBE start inputs=" + devices.length
                    + " recordAudioGranted=" + granted);
            for (AudioDeviceInfo device : devices) {
                if (System.currentTimeMillis() > deadlineMs) {
                    logger.log("AUDPROBE abort: time cap reached");
                    break;
                }
                int[] rates = device.getSampleRates();
                logger.log("AUDPROBE device id=" + device.getId()
                        + " type=" + device.getType()
                        + " product=" + device.getProductName()
                        + " isSource=" + device.isSource()
                        + " rates=" + (rates == null ? "null" : Arrays.toString(rates)));
                int rate = pickRate(rates);
                for (int source : SOURCES) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        logger.log("AUDPROBE abort: time cap reached");
                        return;
                    }
                    probeCombo(device, source, rate, logger);
                }
            }
        } catch (Throwable t) {
            logger.log("AUDPROBE sweep exception: " + describe(t));
        }
        logger.log("AUDPROBE DONE elapsedMs=" + (System.currentTimeMillis() - startMs));
    }

    /** 48000 if the device lists it, else 16000, else the highest listed rate. */
    private static int pickRate(int[] rates) {
        if (rates == null || rates.length == 0) return RATE_PREFERRED;
        int max = 0;
        boolean hasFallback = false;
        for (int r : rates) {
            if (r == RATE_PREFERRED) return RATE_PREFERRED;
            if (r == RATE_FALLBACK) hasFallback = true;
            if (r > max) max = r;
        }
        return hasFallback ? RATE_FALLBACK : max;
    }

    private static void probeCombo(AudioDeviceInfo device, int source, int rate,
                                   Logger logger) {
        String combo = "device=" + device.getId() + " type=" + device.getType()
                + " source=" + source + " rate=" + rate;
        int minBuf = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            logger.log("AUDPROBE " + combo + " outcome=minBufferError code=" + minBuf);
            return;
        }
        int bufferBytes = Math.max(minBuf * 4, 8192);
        AudioRecord rec = null;
        boolean recording = false;
        try {
            rec = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            boolean routed = rec.setPreferredDevice(device);
            int state = rec.getState();
            logger.log("AUDPROBE " + combo + " minBuf=" + minBuf
                    + " buffer=" + bufferBytes + " routed=" + routed
                    + " state=" + state);
            if (state != AudioRecord.STATE_INITIALIZED) {
                logger.log("AUDPROBE " + combo + " outcome=uninitialized state=" + state);
                return;
            }
            rec.startRecording();
            recording = true;
            int recState = rec.getRecordingState();
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                logger.log("AUDPROBE " + combo
                        + " outcome=startFailed recordingState=" + recState);
                return;
            }
            short[] buf = new short[2048];
            readFor(rec, buf, DISCARD_MS, null);
            Stats stats = new Stats();
            readFor(rec, buf, MEASURE_MS, stats);
            logger.log("AUDPROBE " + combo
                    + " peak=" + stats.peak
                    + " rms=" + String.format(Locale.US, "%.1f", stats.rms())
                    + " samples=" + stats.count);
        } catch (Throwable t) {
            logger.log("AUDPROBE " + combo + " outcome=exception " + describe(t));
        } finally {
            if (rec != null) {
                if (recording) {
                    try { rec.stop(); } catch (Throwable ignored) { }
                }
                try { rec.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private static final class Stats {
        int peak = 0;
        long count = 0;
        double sumSq = 0;
        double rms() { return count == 0 ? 0.0 : Math.sqrt(sumSq / count); }
    }

    /** Read (blocking) until the duration elapses; measure into stats if given. */
    private static void readFor(AudioRecord rec, short[] buf, long durationMs,
                                Stats stats) {
        long endMs = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < endMs) {
            int n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
            if (n <= 0) return; // error code (negative) or no data: stop this phase
            if (stats != null) {
                for (int i = 0; i < n; i++) {
                    int v = buf[i];
                    int a = v < 0 ? -v : v;
                    if (a > stats.peak) stats.peak = a;
                    stats.sumSq += (double) v * (double) v;
                    stats.count++;
                }
            }
        }
    }

    // ---- PROBE_HOLD (v0.4.7): pre-opened recorders, windowed levels ----

    private static final int HOLD_SECONDS = 15;
    private static final int TYPE_BUS = 21;         // AudioDeviceInfo.TYPE_BUS
    private static final int TYPE_BUILTIN_MIC = 15; // AudioDeviceInfo.TYPE_BUILTIN_MIC
    private static boolean holdRunning = false;

    static void runHold(Context context, Logger logger) {
        if (VoiceRelay.isOn(context)) {
            // Voice relay ON (v0.5.0): this device is not a mic endpoint.
            logger.log("AUDHOLD skipped: voice relay on");
            return;
        }
        synchronized (AudioProbe.class) {
            if (holdRunning) {
                logger.log("AUDHOLD already running; ignoring");
                return;
            }
            holdRunning = true;
        }
        try {
            runHoldInner(context, logger);
        } catch (Throwable t) {
            logger.log("AUDHOLD exception: " + describe(t));
            logger.log("AUDHOLD DONE");
        } finally {
            synchronized (AudioProbe.class) { holdRunning = false; }
        }
    }

    private static void runHoldInner(Context context, Logger logger) {
        boolean granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            logger.log("AUDHOLD start failed: no AudioManager");
            logger.log("AUDHOLD DONE");
            return;
        }
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        logger.log("AUDHOLD start inputs=" + devices.length
                + " recordAudioGranted=" + granted);
        AudioDeviceInfo bus = firstOfType(devices, TYPE_BUS);
        AudioDeviceInfo builtin = firstOfType(devices, TYPE_BUILTIN_MIC);

        java.util.List<HoldReader> readers = new java.util.ArrayList<>();
        java.util.List<AudioRecord> records = new java.util.ArrayList<>();
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        openHold(bus, TYPE_BUS, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                logger, readers, records);
        openHold(builtin, TYPE_BUILTIN_MIC, MediaRecorder.AudioSource.MIC,
                logger, readers, records);
        if (readers.isEmpty()) {
            logger.log("AUDHOLD DONE");
            return;
        }
        for (HoldReader reader : readers) {
            Thread t = new Thread(reader, "audhold-" + reader.deviceId);
            threads.add(t);
            t.start();
        }
        try {
            Thread.sleep(HOLD_SECONDS * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (HoldReader reader : readers) reader.stop = true;
        for (AudioRecord rec : records) {
            try { rec.stop(); } catch (Throwable ignored) { }
            try { rec.release(); } catch (Throwable ignored) { }
        }
        for (Thread t : threads) {
            try { t.join(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        logger.log("AUDHOLD DONE");
    }

    private static AudioDeviceInfo firstOfType(AudioDeviceInfo[] devices, int type) {
        for (AudioDeviceInfo d : devices) {
            if (d.isSource() && d.getType() == type) return d;
        }
        return null;
    }

    /** Build + route + start one hold recorder; on success register its reader. */
    private static void openHold(AudioDeviceInfo device, int wantType, int source,
                                 Logger logger,
                                 java.util.List<HoldReader> readers,
                                 java.util.List<AudioRecord> records) {
        if (device == null) {
            logger.log("AUDHOLD open failed: no input device of type=" + wantType);
            return;
        }
        int deviceId = device.getId();
        int rate = RATE_PREFERRED;
        int minBuf = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            logger.log("AUDHOLD open device=" + deviceId + " source=" + source
                    + " outcome=minBufferError code=" + minBuf);
            return;
        }
        int bufferBytes = Math.max(minBuf * 4, 8192);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            boolean routed = rec.setPreferredDevice(device);
            int state = rec.getState();
            logger.log("AUDHOLD open device=" + deviceId + " source=" + source
                    + " state=" + state + " routed=" + routed);
            if (state != AudioRecord.STATE_INITIALIZED) {
                try { rec.release(); } catch (Throwable ignored) { }
                return;
            }
            rec.startRecording();
            int recState = rec.getRecordingState();
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                logger.log("AUDHOLD device=" + deviceId
                        + " recordingState=" + recState);
                try { rec.release(); } catch (Throwable ignored) { }
                return;
            }
            records.add(rec);
            readers.add(new HoldReader(rec, deviceId, logger));
        } catch (Throwable t) {
            logger.log("AUDHOLD open device=" + deviceId + " source=" + source
                    + " outcome=exception " + describe(t));
            if (rec != null) {
                try { rec.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Reads one pre-opened recorder continuously, logging peak / RMS per
     * 1-second wall-clock window ("AUDHOLD t=..."), plus immediate lines
     * for new read-error codes and recording-state changes.
     */
    private static final class HoldReader implements Runnable {
        final AudioRecord rec;
        final int deviceId;
        final Logger logger;
        volatile boolean stop = false;

        HoldReader(AudioRecord rec, int deviceId, Logger logger) {
            this.rec = rec;
            this.deviceId = deviceId;
            this.logger = logger;
        }

        @Override public void run() {
            short[] buf = new short[1024];
            long startMs = System.currentTimeMillis();
            long windowStartMs = startMs;
            int window = 1;
            Stats stats = new Stats();
            int windowErr = 0; // last read error code this window (0 = none)
            java.util.Set<Integer> loggedErrs = new java.util.HashSet<>();
            int lastRecState = rec.getRecordingState();
            while (!stop) {
                int n;
                try {
                    n = rec.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                } catch (Throwable t) {
                    if (!stop) {
                        logger.log("AUDHOLD device=" + deviceId
                                + " readException " + describe(t));
                    }
                    break;
                }
                long now = System.currentTimeMillis();
                if (n <= 0) {
                    if (n < 0) {
                        windowErr = n;
                        if (!stop && loggedErrs.add(n)) {
                            logger.log("AUDHOLD device=" + deviceId
                                    + " readError code=" + n);
                        }
                    }
                    // Avoid a hot loop if the HAL errors persistently.
                    try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                } else {
                    for (int i = 0; i < n; i++) {
                        int v = buf[i];
                        int a = v < 0 ? -v : v;
                        if (a > stats.peak) stats.peak = a;
                        stats.sumSq += (double) v * (double) v;
                        stats.count++;
                    }
                }
                int recState = rec.getRecordingState();
                if (recState != lastRecState) {
                    lastRecState = recState;
                    if (!stop) {
                        logger.log("AUDHOLD device=" + deviceId
                                + " recordingState=" + recState);
                    }
                }
                if (now - windowStartMs >= 1000) {
                    logger.log("AUDHOLD t=" + window + " device=" + deviceId
                            + " peak=" + stats.peak
                            + " rms=" + String.format(Locale.US, "%.1f", stats.rms())
                            + " readErr=" + windowErr);
                    window++;
                    stats = new Stats();
                    windowErr = 0;
                    windowStartMs = now;
                    if (window > HOLD_SECONDS) break;
                }
                if (now - startMs > (HOLD_SECONDS + 2) * 1000L) break; // safety
            }
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
