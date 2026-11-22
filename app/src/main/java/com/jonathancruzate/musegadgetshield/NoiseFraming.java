package com.jonathancruzate.musegadgetshield;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Noise transport framing (protobuf chunk envelopes); a port of the SDK's
 * noise/framing.py. A transport payload is split into chunks of at most
 * {@link #MAX_CHUNK_PAYLOAD} bytes sharing a random chunk id; the decoder
 * reassembles by id with the same bounds and poisoning behavior.
 */
public final class NoiseFraming {
    private NoiseFraming() {}

    public static final int MAX_CHUNK_PAYLOAD = 65489;
    public static final int MAX_PENDING_ASSEMBLIES = 16;
    public static final int MAX_TOTAL_CHUNKS = 256;
    public static final int MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024;
    public static final double ASSEMBLY_TTL_SECONDS = 60;

    public static final class NoiseTransportFrame {
        public final long chunkId;
        public final int chunkIndex;
        public final int totalChunks;
        public final byte[] payload;

        public NoiseTransportFrame(long chunkId, int chunkIndex, int totalChunks, byte[] payload) {
            this.chunkId = chunkId;
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.payload = payload != null ? payload : new byte[0];
        }
    }

    public static byte[] encodeNoiseFrame(NoiseTransportFrame frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (frame.chunkId != 0) write(out, Proto.int64Field(1, frame.chunkId));
        if (frame.chunkIndex != 0) write(out, Proto.uint32Field(2, frame.chunkIndex));
        if (frame.totalChunks != 0) write(out, Proto.uint32Field(3, frame.totalChunks));
        if (frame.payload.length > 0) write(out, Proto.bytesField(4, frame.payload));
        return out.toByteArray();
    }

    public static NoiseTransportFrame decodeNoiseFrame(byte[] data) {
        long chunkId = 0;
        int chunkIndex = 0;
        int totalChunks = 1;
        byte[] payload = new byte[0];
        Proto.Reader reader = new Proto.Reader(data);
        while (!reader.atEnd()) {
            int[] key = reader.readKey();
            int field = key[0], wire = key[1];
            if (field == 1) {
                if (wire != Proto.WIRE_VARINT) throw new Proto.ProtoError("chunk_id wrong wire type");
                chunkId = reader.readInt64();
            } else if (field == 2) {
                if (wire != Proto.WIRE_VARINT) throw new Proto.ProtoError("chunk_index wrong wire type");
                chunkIndex = (int) reader.readUint32();
            } else if (field == 3) {
                if (wire != Proto.WIRE_VARINT) throw new Proto.ProtoError("total_chunks wrong wire type");
                totalChunks = (int) reader.readUint32();
            } else if (field == 4) {
                if (wire != Proto.WIRE_DELIMITED) throw new Proto.ProtoError("payload wrong wire type");
                payload = reader.readDelimited();
            } else {
                reader.skipField(wire);
            }
        }
        return new NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public static List<byte[]> encodeNoiseFrames(byte[] data) {
        return encodeNoiseFrames(data, RANDOM.nextLong());
    }

    public static List<byte[]> encodeNoiseFrames(byte[] data, long chunkId) {
        int totalChunks = Math.max(1, (data.length + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD);
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException("payload too large for noise framing ("
                    + data.length + " bytes, " + totalChunks + " chunks > " + MAX_TOTAL_CHUNKS + ")");
        }
        List<byte[]> frames = new ArrayList<>();
        if (data.length == 0) {
            frames.add(encodeNoiseFrame(new NoiseTransportFrame(chunkId, 0, 1, new byte[0])));
            return frames;
        }
        for (int index = 0; index < totalChunks; index++) {
            int start = index * MAX_CHUNK_PAYLOAD;
            int len = Math.min(MAX_CHUNK_PAYLOAD, data.length - start);
            byte[] chunk = new byte[len];
            System.arraycopy(data, start, chunk, 0, len);
            frames.add(encodeNoiseFrame(new NoiseTransportFrame(chunkId, index, totalChunks, chunk)));
        }
        return frames;
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    /** Reassembles chunked transport payloads; poisons itself after any failure. */
    public static final class NoiseFrameDecoder {
        private static final class Assembly {
            final Map<Integer, byte[]> chunks = new HashMap<>();
            final int total;
            int totalBytes = 0;
            final double createdAt;
            double lastUpdated;

            Assembly(int total, double now) {
                this.total = total;
                this.createdAt = now;
                this.lastUpdated = now;
            }
        }

        private final Map<Long, Assembly> pending = new HashMap<>();
        private boolean poisoned = false;

        private static double nowSeconds() { return System.nanoTime() / 1e9; }

        /** Returns the reassembled payload, or null while chunks are missing. */
        public byte[] decode(byte[] frameBytes) {
            if (poisoned) throw new IllegalStateException("NoiseFrameDecoder: poisoned after prior failure");
            try {
                NoiseTransportFrame frame = decodeNoiseFrame(frameBytes);
                if (frame.totalChunks < 1 || frame.totalChunks > MAX_TOTAL_CHUNKS) {
                    throw new IllegalArgumentException("invalid totalChunks: " + frame.totalChunks);
                }
                if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
                    throw new IllegalArgumentException(
                            "chunkIndex " + frame.chunkIndex + " out of range [0, " + frame.totalChunks + ")");
                }
                if (frame.payload.length > MAX_CHUNK_PAYLOAD) {
                    throw new IllegalArgumentException(
                            "payload too large for noise frame (" + frame.payload.length + " bytes)");
                }
                evictExpired();
                Assembly assembly = pending.get(frame.chunkId);
                if (assembly == null) {
                    if (pending.size() >= MAX_PENDING_ASSEMBLIES) {
                        throw new IllegalArgumentException("too many pending noise frame assemblies");
                    }
                    double now = nowSeconds();
                    assembly = new Assembly(frame.totalChunks, now);
                    pending.put(frame.chunkId, assembly);
                }
                if (assembly.total != frame.totalChunks) {
                    pending.remove(frame.chunkId);
                    throw new IllegalArgumentException("inconsistent totalChunks for chunkId: expected "
                            + assembly.total + ", got " + frame.totalChunks);
                }
                if (assembly.chunks.containsKey(frame.chunkIndex)) {
                    pending.remove(frame.chunkId);
                    throw new IllegalArgumentException("duplicate chunkIndex " + frame.chunkIndex);
                }
                assembly.lastUpdated = nowSeconds();
                assembly.totalBytes += frame.payload.length;
                if (assembly.totalBytes > MAX_ASSEMBLY_BYTES) {
                    pending.remove(frame.chunkId);
                    throw new IllegalArgumentException("assembly exceeded byte budget");
                }
                assembly.chunks.put(frame.chunkIndex, frame.payload);
                if (assembly.chunks.size() < assembly.total) return null;
                pending.remove(frame.chunkId);
                if (assembly.total == 1) return assembly.chunks.get(0);
                ByteArrayOutputStream out = new ByteArrayOutputStream(assembly.totalBytes);
                for (int i = 0; i < assembly.total; i++) {
                    byte[] chunk = assembly.chunks.get(i);
                    out.write(chunk, 0, chunk.length);
                }
                return out.toByteArray();
            } catch (RuntimeException e) {
                poisoned = true;
                throw e;
            }
        }

        private void evictExpired() {
            double now = nowSeconds();
            java.util.Iterator<Assembly> it = pending.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().createdAt > ASSEMBLY_TTL_SECONDS) it.remove();
            }
        }
    }
}
