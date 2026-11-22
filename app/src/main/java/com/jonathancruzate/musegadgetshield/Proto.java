package com.jonathancruzate.musegadgetshield;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal protobuf wire codec; a port of the SDK's noise/_proto.py,
 * covering the field kinds the Noise service envelopes use.
 */
public final class Proto {
    private Proto() {}

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_FIXED64 = 1;
    public static final int WIRE_DELIMITED = 2;
    public static final int WIRE_FIXED32 = 5;

    private static final int MAX_FIELD_NUMBER = (1 << 29) - 1;
    private static final int FIRST_RESERVED_FIELD_NUMBER = 19000;
    private static final int LAST_RESERVED_FIELD_NUMBER = 19999;

    public static final class ProtoError extends RuntimeException {
        ProtoError(String message) { super(message); }
    }

    private static boolean validFieldNumber(int fieldNumber) {
        return fieldNumber != 0
                && fieldNumber <= MAX_FIELD_NUMBER
                && !(FIRST_RESERVED_FIELD_NUMBER <= fieldNumber
                        && fieldNumber <= LAST_RESERVED_FIELD_NUMBER);
    }

    // -- Encoding ------------------------------------------------------------

    public static byte[] encodeVarint(long value) {
        if (value < 0) {
            // uint64 varints of negative int64s are the 10-byte form; callers
            // pass already-mapped unsigned values (see signedInt64Value).
            throw new ProtoError("varint value must be non-negative");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    /** Encode a uint64 given as an unsigned long (bit pattern preserved). */
    public static byte[] encodeVarintUnsigned(long bits) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = bits;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    private static byte[] encodeKey(int fieldNumber, int wireType) {
        if (!validFieldNumber(fieldNumber)) throw new ProtoError("invalid field number");
        if (wireType != WIRE_VARINT && wireType != WIRE_FIXED64
                && wireType != WIRE_DELIMITED && wireType != WIRE_FIXED32) {
            throw new ProtoError("invalid wire type");
        }
        return encodeVarint((fieldNumber << 3) | wireType);
    }

    public static byte[] varintField(int fieldNumber, long unsignedBits) {
        return Hashes.concat(encodeKey(fieldNumber, WIRE_VARINT), encodeVarintUnsigned(unsignedBits));
    }

    public static byte[] int64Field(int fieldNumber, long value) {
        return varintField(fieldNumber, value);
    }

    public static byte[] int32Field(int fieldNumber, int value) {
        return varintField(fieldNumber, value);
    }

    public static byte[] uint32Field(int fieldNumber, long value) {
        if (value < 0 || value > 0xFFFFFFFFL) throw new ProtoError("uint32 value out of range");
        return varintField(fieldNumber, value);
    }

    public static byte[] boolField(int fieldNumber, boolean value) {
        return varintField(fieldNumber, value ? 1 : 0);
    }

    public static byte[] delimitedField(int fieldNumber, byte[] payload) {
        return Hashes.concat(encodeKey(fieldNumber, WIRE_DELIMITED),
                encodeVarint(payload.length), payload);
    }

    public static byte[] stringField(int fieldNumber, String value) {
        return delimitedField(fieldNumber, value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] bytesField(int fieldNumber, byte[] value) {
        return delimitedField(fieldNumber, value);
    }

    // -- Decoding --------------------------------------------------------------

    public static final class Reader {
        private final byte[] data;
        private int offset;

        public Reader(byte[] data) { this.data = data; this.offset = 0; }

        public boolean atEnd() { return offset >= data.length; }

        public long readVarint() {
            long value = 0;
            int shift = 0;
            for (int i = 0; i < 10; i++) {
                if (offset >= data.length) throw new ProtoError("truncated varint");
                int b = data[offset++] & 0xFF;
                if (i == 9 && (b & 0xFE) != 0) throw new ProtoError("malformed varint");
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new ProtoError("malformed varint");
        }

        /** Reads a field key; returns {fieldNumber, wireType}. */
        public int[] readKey() {
            long key = readVarint();
            int fieldNumber = (int) (key >>> 3);
            int wireType = (int) (key & 0x07);
            if (!validFieldNumber(fieldNumber)) throw new ProtoError("invalid field number");
            if (wireType != WIRE_VARINT && wireType != WIRE_FIXED64
                    && wireType != WIRE_DELIMITED && wireType != WIRE_FIXED32) {
                throw new ProtoError("invalid wire type");
            }
            return new int[]{fieldNumber, wireType};
        }

        public byte[] readDelimited() {
            long length = readVarint();
            if (length < 0 || offset + length > data.length) {
                throw new ProtoError("truncated delimited field");
            }
            byte[] out = new byte[(int) length];
            System.arraycopy(data, offset, out, 0, (int) length);
            offset += (int) length;
            return out;
        }

        public int readInt32() {
            long raw = readVarint();
            // Raw is the uint64 bit pattern of the int32; narrow it.
            int signed = (int) raw;
            return signed;
        }

        public long readInt64() {
            return readVarint();
        }

        public long readUint32() {
            long raw = readVarint();
            if (Long.compareUnsigned(raw, 0xFFFFFFFFL) > 0) {
                throw new ProtoError("uint32 value out of range");
            }
            return raw;
        }

        public void skipField(int wireType) {
            switch (wireType) {
                case WIRE_VARINT:
                    readVarint();
                    return;
                case WIRE_FIXED64:
                    if (offset + 8 > data.length) throw new ProtoError("truncated fixed64 field");
                    offset += 8;
                    return;
                case WIRE_DELIMITED:
                    readDelimited();
                    return;
                case WIRE_FIXED32:
                    if (offset + 4 > data.length) throw new ProtoError("truncated fixed32 field");
                    offset += 4;
                    return;
                default:
                    throw new ProtoError("invalid wire type");
            }
        }
    }
}
