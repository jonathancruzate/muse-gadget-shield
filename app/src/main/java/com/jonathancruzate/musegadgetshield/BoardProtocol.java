package com.jonathancruzate.musegadgetshield;

import java.util.ArrayList;
import java.util.List;

/**
 * Living-room board framed protocol (v0.10.0, Wave 3) — pure Java, no
 * Android dependencies, so the exact shipping codec is unit-testable on
 * a desktop JVM (see the host-side codec test under
 * muse-home-link/livingroom-board/staging/usb-codec-test/).
 *
 * Wire format (all multi-byte integers little-endian):
 *
 *   +--------+--------+-----------+-------+-----------+--------+
 *   | 0xAA   | 0x55   | len LE16  | type  | payload   | CRC16  |
 *   +--------+--------+-----------+-------+-----------+--------+
 *
 * - len is the PAYLOAD length in bytes (the type byte is not counted).
 * - CRC is CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection, no
 *   final XOR — the "CCITT-FALSE" variant), computed over the byte
 *   sequence len(2, LE as transmitted) + type(1) + payload(len), and
 *   transmitted little-endian after the payload.
 * - Total frame size = 2 + 2 + 1 + len + 2.
 *
 * Message types:
 *   0x01 STATUS_REQ   empty payload, host -> board
 *   0x02 STATUS_RESP  UTF-8 JSON payload, board -> host
 *   0x10 BUTTON_EVENT payload[0] = gesture (1 single, 2 double, 3 long)
 *   0x20 LED_SET      payload[0] = LED pattern id, host -> board
 *   0x21 MODE_SET     payload[0] = mode (0 idle, 1 pilot, 2 night)
 *   0x22 MODE_EVENT   payload[0] = mode, board -> host
 *   0x7F PING         empty payload
 *   0x7E PONG         empty payload
 */
public final class BoardProtocol {
    private BoardProtocol() {}

    public static final int MAGIC_0 = 0xAA;
    public static final int MAGIC_1 = 0x55;

    public static final int TYPE_STATUS_REQ = 0x01;
    public static final int TYPE_STATUS_RESP = 0x02;
    public static final int TYPE_BUTTON_EVENT = 0x10;
    public static final int TYPE_LED_SET = 0x20;
    public static final int TYPE_MODE_SET = 0x21;
    public static final int TYPE_MODE_EVENT = 0x22;
    public static final int TYPE_PING = 0x7F;
    public static final int TYPE_PONG = 0x7E;

    public static final int GESTURE_SINGLE = 1;
    public static final int GESTURE_DOUBLE = 2;
    public static final int GESTURE_LONG = 3;

    public static final int MODE_IDLE = 0;
    public static final int MODE_PILOT = 1;
    public static final int MODE_NIGHT = 2;

    /** Sanity bound: a larger claimed payload length is treated as garbage. */
    public static final int MAX_PAYLOAD = 4096;

    private static final int HEADER_LEN = 5; // magic(2) + len(2) + type(1)
    private static final int CRC_LEN = 2;

    /** One decoded frame: a type byte and an owned copy of the payload. */
    public static final class Frame {
        public final int type;
        public final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    /** CRC16-CCITT (poly 0x1021, init 0xFFFF) over data[off, off+len). */
    public static int crc16Ccitt(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    /** Encode one frame. Payload may be null (treated as empty). */
    public static byte[] encode(int type, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        if (len > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + len);
        }
        byte[] frame = new byte[HEADER_LEN + len + CRC_LEN];
        frame[0] = (byte) MAGIC_0;
        frame[1] = (byte) MAGIC_1;
        frame[2] = (byte) (len & 0xFF);
        frame[3] = (byte) ((len >> 8) & 0xFF);
        frame[4] = (byte) type;
        if (len > 0) System.arraycopy(payload, 0, frame, HEADER_LEN, len);
        int crc = crc16Ccitt(frame, 2, 2 + 1 + len); // len + type + payload
        frame[HEADER_LEN + len] = (byte) (crc & 0xFF);
        frame[HEADER_LEN + len + 1] = (byte) ((crc >> 8) & 0xFF);
        return frame;
    }

    public static String gestureName(int gesture) {
        switch (gesture) {
            case GESTURE_SINGLE: return "single";
            case GESTURE_DOUBLE: return "double";
            case GESTURE_LONG: return "long";
            default: return "unknown(" + gesture + ")";
        }
    }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_IDLE: return "idle";
            case MODE_PILOT: return "pilot";
            case MODE_NIGHT: return "night";
            default: return "unknown(" + mode + ")";
        }
    }

    /**
     * Streaming decoder with resync-on-magic and CRC validation.
     *
     * Bytes are accumulated across feed() calls, so frames may arrive
     * split at any boundary (including inside the magic). Leading
     * garbage is skipped until the 0xAA 0x55 magic is found (a lone
     * trailing 0xAA is held — it may be half of a split magic). A
     * frame whose CRC does not match is dropped whole and counted in
     * {@link #badCrcCount()}; decoding resumes after it. A claimed
     * payload length over {@link #MAX_PAYLOAD} is treated as a false
     * magic: one byte is dropped and the scan resyncs.
     */
    public static final class Decoder {
        private byte[] buf = new byte[1024];
        private int size = 0;
        private int badCrc = 0;
        private int garbage = 0;
        private int decoded = 0;

        public synchronized List<Frame> feed(byte[] data, int off, int len) {
            if (len > 0) {
                ensureCapacity(size + len);
                System.arraycopy(data, off, buf, size, len);
                size += len;
            }
            List<Frame> out = new ArrayList<>();
            while (true) {
                int magicAt = indexOfMagic();
                if (magicAt < 0) {
                    // No complete magic in the buffer. Keep a trailing
                    // 0xAA (possible split magic); drop the rest.
                    int keep = (size > 0 && buf[size - 1] == (byte) MAGIC_0) ? 1 : 0;
                    garbage += size - keep;
                    if (keep == 1) buf[0] = buf[size - 1];
                    size = keep;
                    break;
                }
                if (magicAt > 0) {
                    garbage += magicAt;
                    drop(magicAt);
                }
                if (size < HEADER_LEN) break; // wait for len + type
                int payloadLen = (buf[2] & 0xFF) | ((buf[3] & 0xFF) << 8);
                if (payloadLen > MAX_PAYLOAD) {
                    // False magic: drop one byte and resync.
                    garbage += 1;
                    drop(1);
                    continue;
                }
                int frameLen = HEADER_LEN + payloadLen + CRC_LEN;
                if (size < frameLen) break; // wait for the rest
                int expected = crc16Ccitt(buf, 2, 2 + 1 + payloadLen);
                int actual = (buf[HEADER_LEN + payloadLen] & 0xFF)
                        | ((buf[HEADER_LEN + payloadLen + 1] & 0xFF) << 8);
                if (expected != actual) {
                    badCrc++;
                    drop(frameLen);
                    continue;
                }
                int type = buf[4] & 0xFF;
                byte[] payload = new byte[payloadLen];
                System.arraycopy(buf, HEADER_LEN, payload, 0, payloadLen);
                out.add(new Frame(type, payload));
                decoded++;
                drop(frameLen);
            }
            return out;
        }

        public synchronized int badCrcCount() { return badCrc; }
        public synchronized int garbageBytes() { return garbage; }
        public synchronized int decodedCount() { return decoded; }

        public synchronized void reset() {
            size = 0;
            badCrc = 0;
            garbage = 0;
            decoded = 0;
        }

        private int indexOfMagic() {
            for (int i = 0; i + 1 < size; i++) {
                if (buf[i] == (byte) MAGIC_0 && buf[i + 1] == (byte) MAGIC_1) return i;
            }
            return -1;
        }

        private void drop(int n) {
            System.arraycopy(buf, n, buf, 0, size - n);
            size -= n;
        }

        private void ensureCapacity(int needed) {
            if (needed <= buf.length) return;
            int cap = buf.length;
            while (cap < needed) cap *= 2;
            byte[] next = new byte[cap];
            System.arraycopy(buf, 0, next, 0, size);
            buf = next;
        }
    }
}
