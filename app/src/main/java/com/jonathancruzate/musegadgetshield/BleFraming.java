package com.jonathancruzate.musegadgetshield;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunked framing for messages larger than one BLE packet; a direct port
 * of the SDK's ble_framing.py. Both directions use the same frame:
 * {@code 0xFE}, chunk index, total chunks, then a payload fragment. A
 * write or notification that does not start with {@code 0xFE} is a
 * complete, unchunked message.
 */
public final class BleFraming {
    private BleFraming() {}

    public static final int CHUNK_MAGIC = 0xFE;
    public static final int HEADER_BYTES = 3;
    public static final int MAX_PACKET_BYTES = 160;
    public static final int MAX_CHUNKS = 255;
    public static final int MAX_MESSAGE_BYTES = 8192;
    public static final int DEFAULT_ATT_MTU = 23;

    /** Split {@code data} into framed packets that fit one notification at {@code mtu}. */
    public static List<byte[]> encodeChunks(byte[] data, int mtu) {
        int notifyMax = Math.min(mtu > 3 ? mtu - 3 : 20, MAX_PACKET_BYTES);
        int usable = notifyMax - HEADER_BYTES;
        List<byte[]> fragments = new ArrayList<>();
        if (data.length == 0) {
            fragments.add(new byte[0]);
        } else {
            for (int i = 0; i < data.length; i += usable) {
                int len = Math.min(usable, data.length - i);
                byte[] frag = new byte[len];
                System.arraycopy(data, i, frag, 0, len);
                fragments.add(frag);
            }
        }
        if (fragments.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException(
                    "message needs " + fragments.size() + " chunks (max " + MAX_CHUNKS + ")");
        }
        int total = fragments.size();
        List<byte[]> packets = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            byte[] frag = fragments.get(i);
            byte[] packet = new byte[HEADER_BYTES + frag.length];
            packet[0] = (byte) CHUNK_MAGIC;
            packet[1] = (byte) i;
            packet[2] = (byte) total;
            System.arraycopy(frag, 0, packet, HEADER_BYTES, frag.length);
            packets.add(packet);
        }
        return packets;
    }

    /**
     * Reassembles chunked writes, strictly in order. Index 0, or a change
     * in the total, starts a new message; an out-of-order chunk or an
     * oversize message discards what has been collected.
     */
    public static final class ChunkAssembler {
        private final int maxBytes;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private int total = 0;
        private int next = 0;

        public ChunkAssembler() { this(MAX_MESSAGE_BYTES); }

        public ChunkAssembler(int maxBytes) { this.maxBytes = maxBytes; }

        public void reset() {
            buf.reset();
            total = 0;
            next = 0;
        }

        /** Add one write; returns a complete message once one is available, else null. */
        public byte[] feed(byte[] packet) {
            if (packet.length < HEADER_BYTES || (packet[0] & 0xFF) != CHUNK_MAGIC) {
                return packet;
            }
            int index = packet[1] & 0xFF;
            int newTotal = packet[2] & 0xFF;
            if (newTotal == 0) {
                reset();
                return null;
            }
            if (index == 0 || newTotal != total) {
                reset();
                total = newTotal;
            }
            if (index != next || index >= total) {
                reset();
                return null;
            }
            int fragmentLength = packet.length - HEADER_BYTES;
            if (buf.size() + fragmentLength > maxBytes) {
                reset();
                return null;
            }
            buf.write(packet, HEADER_BYTES, fragmentLength);
            next = index + 1;
            if (next < total) return null;
            byte[] message = buf.toByteArray();
            reset();
            return message;
        }
    }
}
