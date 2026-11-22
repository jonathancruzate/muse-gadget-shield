package com.jonathancruzate.musegadgetshield;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * X25519 (RFC 7748) in pure Java, so the Noise handshake behaves
 * identically on every provider. Includes the SDK's peer checks: the
 * known low-order public keys are rejected and an all-zero DH output is
 * an error (see noise/noise_xx.py in the Linux SDK).
 */
public final class X25519 {
    private X25519() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] BASE_POINT = new byte[32];
    static { BASE_POINT[0] = 9; }

    private static final byte[][] LOW_ORDER_POINTS = {
        hex("0000000000000000000000000000000000000000000000000000000000000000"),
        hex("0100000000000000000000000000000000000000000000000000000000000000"),
        hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
        hex("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
        hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
        hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
        hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
    };

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static final class KeyPair {
        public final byte[] privateKey; // 32 bytes, unclamped (clamped in scalarMult)
        public final byte[] publicKey;  // 32 bytes
        KeyPair(byte[] priv, byte[] pub) { this.privateKey = priv; this.publicKey = pub; }
    }

    public static KeyPair generateKeyPair(SecureRandom random) {
        byte[] priv = new byte[32];
        random.nextBytes(priv);
        return new KeyPair(priv, scalarMult(priv, BASE_POINT));
    }

    /** Raw DH with the SDK's low-order / all-zero peer checks. */
    public static byte[] dh(byte[] privateKey, byte[] peerPublicKey) {
        if (peerPublicKey == null || peerPublicKey.length != 32) {
            throw new IllegalArgumentException("x25519: invalid public key length");
        }
        for (byte[] low : LOW_ORDER_POINTS) {
            if (Arrays.equals(peerPublicKey, low)) {
                throw new IllegalArgumentException("x25519: rejected low-order public key");
            }
        }
        byte[] shared = scalarMult(privateKey, peerPublicKey);
        boolean zero = true;
        for (byte b : shared) zero &= (b == 0);
        if (zero) throw new IllegalArgumentException("x25519: DH produced all-zeros output");
        return shared;
    }

    /** RFC 7748 X25519 function. */
    public static byte[] scalarMult(byte[] scalar, byte[] uBytes) {
        byte[] k = scalar.clone();
        k[0] &= 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger x1 = decodeU(uBytes);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = (k[t >> 3] >> (t & 7)) & 1;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp;
                tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger plus = da.add(cb).mod(P);
            BigInteger minus = da.subtract(cb).mod(P);
            x3 = plus.multiply(plus).mod(P);
            z3 = x1.multiply(minus.multiply(minus).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P);
        }
        if (swap == 1) {
            BigInteger tmp;
            tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        BigInteger result = x2.multiply(z2.modInverse(P)).mod(P);
        return encodeU(result);
    }

    private static BigInteger decodeU(byte[] bytes) {
        byte[] copy = bytes.clone();
        copy[31] &= 127; // X25519 ignores the top bit of the received u-coordinate
        BigInteger value = BigInteger.ZERO;
        for (int i = 31; i >= 0; i--) {
            value = value.shiftLeft(8).or(BigInteger.valueOf(copy[i] & 0xFF));
        }
        return value;
    }

    private static byte[] encodeU(BigInteger value) {
        byte[] out = new byte[32];
        BigInteger v = value.mod(P);
        for (int i = 0; i < 32; i++) {
            out[i] = v.byteValue();
            v = v.shiftRight(8);
        }
        return out;
    }
}
