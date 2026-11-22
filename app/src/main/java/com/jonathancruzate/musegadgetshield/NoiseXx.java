package com.jonathancruzate.musegadgetshield;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Noise XX handshake (Noise_XX_25519_AESGCM_SHA256), initiator side; a
 * port of the SDK's noise/noise_xx.py (CipherState, SymmetricState and
 * NoiseXXInitiator — the responder exists only in the SDK's tests, so it
 * is not ported).
 */
public final class NoiseXx {
    private NoiseXx() {}

    public static final class NoiseProtocolError extends RuntimeException {
        NoiseProtocolError(String message) { super(message); }
        NoiseProtocolError(String message, Throwable cause) { super(message, cause); }
    }

    private static final byte[] PROTOCOL_NAME =
            "Noise_XX_25519_AESGCM_SHA256".getBytes(StandardCharsets.UTF_8);
    private static final int DH_KEY_LEN = 32;
    private static final int AEAD_TAG_LEN = 16;
    private static final int MIN_MSG2_LEN = DH_KEY_LEN + (DH_KEY_LEN + AEAD_TAG_LEN) + AEAD_TAG_LEN;
    private static final long MAX_SAFE_NONCE = (1L << 53) - 1;

    static byte[] buildNonceIv(long nonce) {
        if (nonce < 0) throw new NoiseProtocolError("nonce outside uint64 range");
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++) {
            iv[4 + i] = (byte) (nonce >>> (56 - i * 8));
        }
        return iv;
    }

    /** One Noise cipher state: AES-256-GCM with a nonce counter, poisoned by any failure. */
    public static final class CipherState {
        private byte[] key;
        private boolean keyed = false;
        private long nonce = 0;
        private boolean poisoned = false;

        public synchronized void initializeKey(byte[] newKey) {
            assertAlive();
            if (newKey.length != 32) {
                throw new NoiseProtocolError("CipherState: AES-GCM key must be 32 bytes");
            }
            this.key = newKey.clone();
            this.keyed = true;
            this.nonce = 0;
        }

        public boolean hasKey() { return keyed; }

        public synchronized byte[] encryptWithAd(byte[] ad, byte[] plaintext) {
            assertAlive();
            if (!keyed) return plaintext;
            long n = nextNonce();
            try {
                return Hashes.aesGcmEncrypt(key, buildNonceIv(n), plaintext, ad);
            } catch (RuntimeException e) {
                poisoned = true;
                throw e;
            }
        }

        public synchronized byte[] decryptWithAd(byte[] ad, byte[] ciphertext) {
            assertAlive();
            if (!keyed) return ciphertext;
            long n = nextNonce();
            try {
                return Hashes.aesGcmDecrypt(key, buildNonceIv(n), ciphertext, ad);
            } catch (Exception e) {
                poisoned = true;
                throw new NoiseProtocolError("CipherState: decrypt failed", e);
            }
        }

        private long nextNonce() {
            if (nonce >= MAX_SAFE_NONCE) {
                poisoned = true;
                throw new NoiseProtocolError("CipherState: nonce exhausted");
            }
            return nonce++;
        }

        private void assertAlive() {
            if (poisoned) throw new NoiseProtocolError("CipherState: poisoned after prior failure");
        }
    }

    private static byte[][] hkdf(byte[] chainingKey, byte[] inputKeyMaterial, int numOutputs) {
        byte[] tempKey = Hashes.hmacSha256(chainingKey, inputKeyMaterial);
        byte[] output1 = Hashes.hmacSha256(tempKey, new byte[]{1});
        byte[] output2 = Hashes.hmacSha256(tempKey, Hashes.concat(output1, new byte[]{2}));
        if (numOutputs == 2) return new byte[][]{output1, output2};
        byte[] output3 = Hashes.hmacSha256(tempKey, Hashes.concat(output2, new byte[]{3}));
        return new byte[][]{output1, output2, output3};
    }

    private static final class SymmetricState {
        private byte[] ck = new byte[32];
        private byte[] h = new byte[32];
        private CipherState cipher = new CipherState();

        void initialize() {
            byte[] padded = new byte[32];
            System.arraycopy(PROTOCOL_NAME, 0, padded, 0, PROTOCOL_NAME.length);
            h = padded;
            ck = h.clone();
            mixHash(new byte[0]);
        }

        void mixHash(byte[] data) {
            h = Hashes.sha256(h, data);
        }

        void mixKey(byte[] inputKeyMaterial) {
            byte[][] out = hkdf(ck, inputKeyMaterial, 2);
            ck = out[0];
            cipher = new CipherState();
            cipher.initializeKey(out[1]);
        }

        byte[] encryptAndHash(byte[] plaintext) {
            byte[] ciphertext = cipher.encryptWithAd(h, plaintext);
            mixHash(ciphertext);
            return ciphertext;
        }

        byte[] decryptAndHash(byte[] ciphertext) {
            byte[] plaintext = cipher.decryptWithAd(h, ciphertext);
            mixHash(ciphertext);
            return plaintext;
        }

        CipherState[] split() {
            byte[][] out = hkdf(ck, new byte[0], 2);
            ck = new byte[32];
            h = new byte[32];
            CipherState c1 = new CipherState();
            c1.initializeKey(out[0]);
            CipherState c2 = new CipherState();
            c2.initializeKey(out[1]);
            return new CipherState[]{c1, c2};
        }

        byte[] handshakeHash() { return h.clone(); }
    }

    private enum Phase { CREATED, INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

    /** The device side of the handshake: the client connecting to the VM. */
    public static final class NoiseXXInitiator {
        private final SymmetricState ss = new SymmetricState();
        private final SecureRandom random;
        private X25519.KeyPair e;
        private X25519.KeyPair s;
        private byte[] re;
        private byte[] rs;
        private Phase phase = Phase.CREATED;

        public NoiseXXInitiator() { this(new SecureRandom()); }

        public NoiseXXInitiator(SecureRandom random) { this.random = random; }

        public void initialize() {
            requirePhase(Phase.CREATED, "initialize");
            ss.initialize();
            phase = Phase.INITIALIZED;
        }

        public byte[] writeMessage1() {
            requirePhase(Phase.INITIALIZED, "write_message1");
            try {
                e = X25519.generateKeyPair(random);
                ss.mixHash(e.publicKey);
                ss.encryptAndHash(new byte[0]);
                phase = Phase.MSG1_SENT;
                return e.publicKey.clone();
            } catch (RuntimeException ex) {
                phase = Phase.DEAD;
                throw ex;
            }
        }

        public byte[] readMessage2(byte[] msg) {
            requirePhase(Phase.MSG1_SENT, "read_message2");
            if (msg.length < MIN_MSG2_LEN) {
                phase = Phase.DEAD;
                throw new NoiseProtocolError(
                        "NoiseXX: message 2 too short (" + msg.length + " < " + MIN_MSG2_LEN + ")");
            }
            try {
                int offset = 0;
                re = Arrays.copyOfRange(msg, offset, offset + DH_KEY_LEN);
                ss.mixHash(re);
                offset += DH_KEY_LEN;

                ss.mixKey(X25519.dh(e.privateKey, re));

                rs = ss.decryptAndHash(Arrays.copyOfRange(msg, offset, offset + DH_KEY_LEN + AEAD_TAG_LEN));
                offset += DH_KEY_LEN + AEAD_TAG_LEN;

                ss.mixKey(X25519.dh(e.privateKey, rs));

                byte[] payload = ss.decryptAndHash(Arrays.copyOfRange(msg, offset, msg.length));
                phase = Phase.MSG2_READ;
                return payload;
            } catch (RuntimeException ex) {
                phase = Phase.DEAD;
                throw ex;
            }
        }

        public byte[] writeMessage3() {
            requirePhase(Phase.MSG2_READ, "write_message3");
            try {
                s = X25519.generateKeyPair(random);
                byte[] encS = ss.encryptAndHash(s.publicKey);
                ss.mixKey(X25519.dh(s.privateKey, re));
                byte[] encPayload = ss.encryptAndHash(new byte[0]);
                phase = Phase.MSG3_SENT;
                return Hashes.concat(encS, encPayload);
            } catch (RuntimeException ex) {
                phase = Phase.DEAD;
                throw ex;
            }
        }

        /** Returns {sendCipher, recvCipher}. */
        public CipherState[] split() {
            requirePhase(Phase.MSG3_SENT, "split");
            phase = Phase.SPLIT;
            CipherState[] result = ss.split();
            e = null;
            s = null;
            re = null;
            rs = null;
            return result;
        }

        public void destroy() {
            e = null;
            s = null;
            re = null;
            rs = null;
            phase = Phase.DEAD;
        }

        public byte[] remoteStaticPublicKey() {
            return rs == null ? null : rs.clone();
        }

        public byte[] handshakeHash() { return ss.handshakeHash(); }

        private void requirePhase(Phase expected, String method) {
            if (phase == Phase.DEAD) {
                throw new NoiseProtocolError("NoiseXX: " + method + " called on dead handshake");
            }
            if (phase != expected) {
                throw new NoiseProtocolError("NoiseXX: " + method + " called in wrong phase (expected "
                        + expected + ", got " + phase + ")");
            }
        }
    }
}
