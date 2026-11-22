package com.jonathancruzate.musegadgetshield;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.KeyAgreement;

/**
 * Device side of Muse Gadget BLE pairing, protocol version 5; a direct
 * port of the SDK's pairing.py.
 *
 * Community mode only: {@code pairing_auth} is {@code "none"} (no
 * manufacturer proof) and the policy is {@code confirm_app}, so a valid
 * client-finished record confirms the session without a physical button.
 * The wire format, transcript, key schedule and record encryption match
 * the ESP32 reference firmware and the SDK's published test vectors.
 */
public final class Pairing {
    private Pairing() {}

    public static final int PAIRING_VERSION = 5;
    public static final String PAIRING_MODEL = "hatch_link";
    public static final String PAIRING_SUITE = "p256-hkdf-sha256-aes-gcm-v1";
    public static final String POLICY_BUTTON = "confirm_press";
    public static final String POLICY_APP = "confirm_app";
    public static final String AUTH_OFFICIAL = "fleet_ecdsa_p256_v1";
    public static final String AUTH_COMMUNITY = "none";
    public static final int BUTTON_CONFIRM_TIMEOUT_S = 60;

    public static final String RECORD_LABEL = "hatch-link ble setup v1";
    private static final byte[] SESSION_ID_LABEL = "hatch-link session id v1".getBytes(StandardCharsets.UTF_8);

    public static final int CLIENT_FINISHED_TIMEOUT_S = 60;
    public static final int CONFIRMED_TIMEOUT_S = 120;
    public static final int PROVISIONING_TIMEOUT_S = 120;

    public static final String ERROR_INVALID_HELLO = "error_pairing_invalid_hello";
    public static final String ERROR_DECRYPT = "error_pairing_decrypt";

    private static final int P256_POINT_BYTES = 65;
    private static final int NONCE_BYTES = 16;
    private static final int SESSION_ID_BYTES = 16;
    private static final int TAG_BYTES = 16;
    private static final int MAX_B64_CHARS = 4096;
    private static final int MAX_CIPHERTEXT_B64_CHARS = 16384;

    private static final int TO_DEVICE = 0;
    private static final int FROM_DEVICE = 1;

    /** A handshake step failed; {@code status} is the wire error to report. */
    public static final class PairingError extends Exception {
        public final String status;
        PairingError(String status) { super(status); this.status = status; }
    }

    public enum State { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

    /** Seconds source, injectable for tests (Python's {@code clock}). */
    public interface Clock { double nowSeconds(); }

    /** Device key source, injectable for tests (Python's {@code generate_key}). */
    public interface KeyGen { PrivateKey generate() throws Exception; }

    /** Random byte source, injectable for tests (Python's {@code random_bytes}). */
    public interface RandomSource { byte[] bytes(int n); }

    public static Clock systemClock() {
        return new Clock() {
            @Override public double nowSeconds() { return System.nanoTime() / 1e9; }
        };
    }

    public static KeyGen platformKeyGen() {
        return new KeyGen() {
            @Override public PrivateKey generate() throws Exception {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair pair = gen.generateKeyPair();
                return pair.getPrivate();
            }
        };
    }

    public static RandomSource secureRandomSource(final SecureRandom random) {
        return new RandomSource() {
            @Override public byte[] bytes(int n) {
                byte[] b = new byte[n];
                random.nextBytes(b);
                return b;
            }
        };
    }

    // -- Encoding helpers (Python: b64url_encode / b64url_decode / parse_counter)

    static String b64urlEncode(byte[] data) {
        return B64.urlEncode(data);
    }

    static byte[] b64urlDecode(Object text) { return b64urlDecode(text, MAX_B64_CHARS); }

    static byte[] b64urlDecode(Object text, int maxChars) {
        if (!(text instanceof String)) throw new IllegalArgumentException("invalid base64url");
        String s = (String) text;
        if (s.length() == 0 || s.length() > maxChars) {
            throw new IllegalArgumentException("invalid base64url length");
        }
        if (s.length() % 4 == 1) throw new IllegalArgumentException("invalid base64url");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) throw new IllegalArgumentException("invalid base64url");
        }
        return B64.urlDecode(s);
    }

    static long parseCounter(Object text) {
        if (!(text instanceof String)) throw new IllegalArgumentException("invalid counter");
        String s = (String) text;
        if (s.isEmpty()) throw new IllegalArgumentException("invalid counter");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("invalid counter");
        }
        try {
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("counter overflow");
        }
    }

    /** Canonical v5 transcript; SHA-256 of it is the {@code transcript_hash}. */
    public static String buildTranscript(
            boolean community, long authEpoch, String policy, String deviceId, String nodeId,
            String mac, String firmwareVersion, String mobilePub, String devicePub,
            String mobileNonce, String deviceNonce) {
        boolean button = POLICY_BUTTON.equals(policy);
        if (!(button || (community && POLICY_APP.equals(policy)))) {
            throw new IllegalArgumentException("unsupported pairing policy: " + policy);
        }
        if (community ? (authEpoch != 0) : (authEpoch <= 0)) {
            throw new IllegalArgumentException("invalid auth epoch: " + authEpoch);
        }
        String[] fields = {deviceId, nodeId, mac, firmwareVersion,
                mobilePub, devicePub, mobileNonce, deviceNonce};
        for (String f : fields) {
            if (f == null || f.isEmpty()) {
                throw new IllegalArgumentException("transcript fields must be non-empty");
            }
        }
        String[] lines = {
            "hatch-link-pairing-v" + PAIRING_VERSION,
            "version=" + PAIRING_VERSION,
            "initiator_role=mobile",
            "responder_role=link",
            "device_id=" + deviceId,
            "node_id=" + nodeId,
            "mac=" + mac,
            "model=" + PAIRING_MODEL,
            "firmware_version=" + firmwareVersion,
            "selected_cipher_suite=" + PAIRING_SUITE,
            "pairing_auth=" + (community ? AUTH_COMMUNITY : AUTH_OFFICIAL),
            "pairing_auth_epoch=" + authEpoch,
            "pairing_policy=" + policy,
            "confirm_timeout_seconds=" + (button ? BUTTON_CONFIRM_TIMEOUT_S : 0),
            "mobile_pub=" + mobilePub,
            "device_pub=" + devicePub,
            "mobile_nonce=" + mobileNonce,
            "device_nonce=" + deviceNonce,
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Returns {@code {mobileTxKey, mobileRxKey, sessionId}}.
     * {@code mobileTxKey} decrypts records the device receives and
     * {@code mobileRxKey} encrypts records it sends.
     */
    public static byte[][] deriveSessionKeys(
            byte[] ecdhSecret, byte[] mobileNonce, byte[] deviceNonce, byte[] transcriptHash) {
        byte[] salt = Hashes.sha256(mobileNonce, deviceNonce, transcriptHash);
        byte[] sessionSecret = Hashes.hkdf(
                ecdhSecret, salt, RECORD_LABEL.getBytes(StandardCharsets.UTF_8), 32);
        byte[] mobileTx = Hashes.hkdfExpand(
                sessionSecret, "mobile->device".getBytes(StandardCharsets.UTF_8), 32);
        byte[] mobileRx = Hashes.hkdfExpand(
                sessionSecret, "device->mobile".getBytes(StandardCharsets.UTF_8), 32);
        byte[] sessionIdFull = Hashes.sha256(SESSION_ID_LABEL, transcriptHash, ecdhSecret);
        byte[] sessionId = Arrays.copyOf(sessionIdFull, SESSION_ID_BYTES);
        return new byte[][]{mobileTx, mobileRx, sessionId};
    }

    static byte[] recordNonce(int direction, long counter) {
        byte[] nonce = new byte[12];
        nonce[0] = (byte) direction;
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (counter >>> (56 - i * 8));
        }
        return nonce;
    }

    static byte[] recordAad(String sessionIdB64, int direction, long counter) {
        String arrow = direction == TO_DEVICE ? "m2d" : "d2m";
        return (RECORD_LABEL + "|" + sessionIdB64 + "|" + arrow + "|" + counter)
                .getBytes(StandardCharsets.UTF_8);
    }

    // -- P-256 helpers -------------------------------------------------------

    private static ECParameterSpec p256Params() {
        try {
            java.security.AlgorithmParameters params =
                    java.security.AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uncompressed X9.62 point encoding (0x04 || X || Y), as the wire carries. */
    static byte[] encodePoint(ECPublicKey key) {
        byte[] out = new byte[P256_POINT_BYTES];
        out[0] = 0x04;
        byte[] x = toFixed(key.getW().getAffineX(), 32);
        byte[] y = toFixed(key.getW().getAffineY(), 32);
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    static ECPublicKey decodePoint(byte[] encoded) throws Exception {
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
    }

    /** Build the private key for a raw scalar (test-vector hook). */
    public static PrivateKey privateKeyFromScalar(BigInteger scalar) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePrivate(new ECPrivateKeySpec(scalar, p256Params()));
    }

    /** The public key belonging to a JCA EC private key. */
    static ECPublicKey publicFromPrivate(PrivateKey priv) throws Exception {
        // There is no JCA call for this; derive it by scalar multiplication
        // of the generator, as the curve parameters describe.
        ECPrivateKey ecPriv = (ECPrivateKey) priv;
        ECParameterSpec params = ecPriv.getParams();
        ECPoint g = params.getGenerator();
        ECPoint pub = scalarMultiply(g, ecPriv.getS(), params);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(pub, params));
    }

    private static byte[] toFixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        int srcPos = Math.max(0, raw.length - length);
        int count = Math.min(raw.length, length);
        System.arraycopy(raw, srcPos, out, length - count, count);
        return out;
    }

    // Affine EC math for the public-from-private derivation only (key setup,
    // never per-record), on the P-256 curve y^2 = x^3 - 3x + b (mod p).
    private static ECPoint scalarMultiply(ECPoint point, BigInteger scalar, ECParameterSpec params) {
        java.security.spec.ECField field = params.getCurve().getField();
        if (!(field instanceof java.security.spec.ECFieldFp)) {
            throw new IllegalStateException("not an Fp curve");
        }
        BigInteger p = ((java.security.spec.ECFieldFp) field).getP();
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = point;
        BigInteger k = scalar;
        while (k.signum() > 0) {
            if (k.testBit(0)) result = pointAdd(result, addend, params, p);
            addend = pointAdd(addend, addend, params, p);
            k = k.shiftRight(1);
        }
        return result;
    }

    private static ECPoint pointAdd(ECPoint a, ECPoint b, ECParameterSpec params, BigInteger p) {
        if (ECPoint.POINT_INFINITY.equals(a)) return b;
        if (ECPoint.POINT_INFINITY.equals(b)) return a;
        BigInteger x1 = a.getAffineX(), y1 = a.getAffineY();
        BigInteger x2 = b.getAffineX(), y2 = b.getAffineY();
        BigInteger lambda;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) return ECPoint.POINT_INFINITY;
            BigInteger aCoef = params.getCurve().getA();
            lambda = x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(aCoef)
                    .multiply(y1.add(y1).modInverse(p)).mod(p);
        } else {
            lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p);
        }
        BigInteger x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(p);
        BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new ECPoint(x3, y3);
    }

    private static byte[] ecdh(PrivateKey priv, PublicKey peer) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(priv);
        agreement.doPhase(peer, true);
        byte[] secret = agreement.generateSecret();
        // The shared secret is the X coordinate; JCA providers return it
        // field-sized, but normalize leading-zero truncation defensively.
        if (secret.length == 32) return secret;
        if (secret.length < 32) {
            byte[] out = new byte[32];
            System.arraycopy(secret, 0, out, 32 - secret.length, secret.length);
            return out;
        }
        return Arrays.copyOfRange(secret, secret.length - 32, secret.length);
    }

    /**
     * One device's pairing state; method calls are serialized like the
     * Python session's lock. Methods that advance the handshake return a
     * nonzero <em>generation</em> token; deferred work holds on to it and
     * checks {@link #isCurrent} before acting.
     */
    public static final class PairingSession {
        private final String nodeId;
        private final String sdkToken;
        private final String deviceId;
        private final String mac;
        private final String firmwareVersion;
        private final Clock clock;
        private final KeyGen generateKey;
        private final RandomSource randomBytes;

        private long generation = 0;
        private State state;
        private double deadline;
        private byte[] rxKey;
        private byte[] txKey;
        private String sessionIdB64 = "";
        private long rxCounter;
        private long txCounter;

        public PairingSession(String nodeId, String deviceId, String mac,
                String firmwareVersion, String sdkToken) {
            this(nodeId, deviceId, mac, firmwareVersion, sdkToken,
                    systemClock(), platformKeyGen(),
                    secureRandomSource(new SecureRandom()));
        }

        public PairingSession(String nodeId, String deviceId, String mac,
                String firmwareVersion, String sdkToken,
                Clock clock, KeyGen generateKey, RandomSource randomBytes) {
            this.nodeId = nodeId;
            this.sdkToken = sdkToken;
            this.deviceId = deviceId;
            this.mac = mac;
            this.firmwareVersion = (firmwareVersion == null || firmwareVersion.isEmpty())
                    ? "unknown" : firmwareVersion;
            this.clock = clock;
            this.generateKey = generateKey;
            this.randomBytes = randomBytes;
            resetLocked();
        }

        /** Pairing fields for the {@code get_device_info} response. */
        public Map<String, Object> deviceInfo() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("device_id", deviceId);
            info.put("mac", mac);
            info.put("model", PAIRING_MODEL);
            info.put("pairing_protocol", (long) PAIRING_VERSION);
            info.put("pairing_auth", AUTH_COMMUNITY);
            info.put("pairing_auth_epoch", 0L);
            info.put("pairing_policy", POLICY_APP);
            return info;
        }

        public synchronized State state() {
            expireLocked();
            return state;
        }

        public synchronized boolean confirmed() {
            State s = state();
            return s == State.READY || s == State.PROVISIONING;
        }

        public synchronized boolean isCurrent(long gen) {
            return gen != 0 && gen == generation;
        }

        public synchronized void reset() {
            resetLocked();
        }

        /** Start a session from {@code pairing_client_hello}; returns {@code pairing_ready}. */
        public synchronized Map<String, Object> handleHello(Map<String, Object> message)
                throws PairingError {
            Object version = message.get("version");
            boolean versionOk = false;
            if (version instanceof Number && !(version instanceof Boolean)) {
                versionOk = ((Number) version).doubleValue() == PAIRING_VERSION;
            }
            if (!versionOk
                    || !AUTH_COMMUNITY.equals(message.get("pairing_auth"))
                    || !POLICY_APP.equals(message.get("pairing_policy"))) {
                throw new PairingError(ERROR_INVALID_HELLO);
            }
            resetLocked();
            byte[] mobilePub;
            byte[] mobileNonce;
            ECPublicKey peer;
            try {
                mobilePub = b64urlDecode(message.get("mobile_pub"));
                mobileNonce = b64urlDecode(message.get("mobile_nonce"));
                if (mobilePub.length != P256_POINT_BYTES || mobilePub[0] != 0x04
                        || mobileNonce.length != NONCE_BYTES) {
                    throw new IllegalArgumentException("invalid hello key material");
                }
                peer = decodePoint(mobilePub);
            } catch (Exception e) {
                resetLocked();
                throw new PairingError(ERROR_INVALID_HELLO);
            }
            try {
                PrivateKey deviceKey = generateKey.generate();
                ECPublicKey devicePubKey = (deviceKey instanceof ECPublicKey)
                        ? (ECPublicKey) deviceKey : publicFromPrivate(deviceKey);
                byte[] devicePub = encodePoint(devicePubKey);
                byte[] deviceNonce = randomBytes.bytes(NONCE_BYTES);
                String transcript = buildTranscript(
                        true, 0, POLICY_APP, deviceId, nodeId, mac, firmwareVersion,
                        b64urlEncode(mobilePub), b64urlEncode(devicePub),
                        b64urlEncode(mobileNonce), b64urlEncode(deviceNonce));
                byte[] transcriptHash = Hashes.sha256(transcript.getBytes(StandardCharsets.UTF_8));
                byte[] ecdhSecret = ecdh(deviceKey, peer);
                byte[][] keys = deriveSessionKeys(ecdhSecret, mobileNonce, deviceNonce, transcriptHash);
                this.rxKey = keys[0];
                this.txKey = keys[1];
                this.sessionIdB64 = b64urlEncode(keys[2]);
                this.rxCounter = 0;
                this.txCounter = 0;
                this.state = State.WAIT_CLIENT_FINISHED;
                this.deadline = clock.nowSeconds() + CLIENT_FINISHED_TIMEOUT_S;
                Map<String, Object> ready = new LinkedHashMap<>();
                ready.put("type", "pairing_ready");
                ready.put("version", (long) PAIRING_VERSION);
                ready.put("device_id", deviceId);
                ready.put("node_id", nodeId);
                ready.put("mac", mac);
                ready.put("model", PAIRING_MODEL);
                ready.put("firmware_version", firmwareVersion);
                ready.put("pairing_auth", AUTH_COMMUNITY);
                ready.put("pairing_auth_epoch", 0L);
                ready.put("pairing_policy", POLICY_APP);
                ready.put("device_pub", b64urlEncode(devicePub));
                ready.put("device_nonce", b64urlEncode(deviceNonce));
                ready.put("transcript_hash", b64urlEncode(transcriptHash));
                ready.put("session_id", sessionIdB64);
                return ready;
            } catch (PairingError e) {
                throw e;
            } catch (Exception e) {
                resetLocked();
                throw new PairingError(ERROR_INVALID_HELLO);
            }
        }

        /**
         * Open one mobile-to-device {@code pairing_encrypted} record. Any
         * failure (wrong session, skipped or replayed counter, bad tag,
         * expiry) clears the session, as the firmware does.
         */
        public synchronized String decrypt(Map<String, Object> envelope) throws PairingError {
            if (expireLocked() || state == State.IDLE) {
                resetLocked();
                throw new PairingError(ERROR_DECRYPT);
            }
            String plaintext;
            long counter;
            try {
                if (!sessionIdB64.equals(envelope.get("session_id"))) {
                    throw new IllegalArgumentException("wrong session");
                }
                counter = parseCounter(envelope.get("counter"));
                if (counter != rxCounter) throw new IllegalArgumentException("unexpected counter");
                byte[] ciphertext = b64urlDecode(
                        envelope.get("ciphertext"), MAX_CIPHERTEXT_B64_CHARS);
                byte[] tag = b64urlDecode(envelope.get("tag"));
                if (tag.length != TAG_BYTES) throw new IllegalArgumentException("invalid tag length");
                byte[] opened = Hashes.aesGcmDecrypt(
                        rxKey, recordNonce(TO_DEVICE, counter),
                        Hashes.concat(ciphertext, tag),
                        recordAad(sessionIdB64, TO_DEVICE, counter));
                plaintext = new String(opened, StandardCharsets.UTF_8);
            } catch (Exception e) {
                resetLocked();
                throw new PairingError(ERROR_DECRYPT);
            }
            rxCounter += 1;
            return plaintext;
        }

        /**
         * Confirm the session after the first decrypted record, which must
         * be exactly {@code {"action": "pairing_client_finished"}}. Returns
         * the new generation, or 0 (after clearing the session) if invalid.
         */
        public synchronized long handleClientFinished(Map<String, Object> command) {
            boolean ok = command.size() == 1
                    && "pairing_client_finished".equals(command.get("action"))
                    && !expireLocked()
                    && state == State.WAIT_CLIENT_FINISHED
                    && rxCounter == 1;
            if (!ok) {
                resetLocked();
                return 0;
            }
            advanceGenerationLocked();
            state = State.READY;
            deadline = clock.nowSeconds() + CONFIRMED_TIMEOUT_S;
            return generation;
        }

        /** Enter provisioning from a confirmed session; returns its generation. */
        public synchronized long markProvisioning() {
            if (!expireLocked() && state == State.READY) {
                advanceGenerationLocked();
                state = State.PROVISIONING;
                deadline = clock.nowSeconds() + PROVISIONING_TIMEOUT_S;
            }
            return state == State.PROVISIONING ? generation : 0;
        }

        public synchronized boolean extendProvisioning(long gen) {
            boolean valid = provisioningLocked(gen);
            if (valid) deadline = clock.nowSeconds() + PROVISIONING_TIMEOUT_S;
            return valid;
        }

        /**
         * Run {@code commit} under the pairing lock if the session is still
         * valid. {@code commit} must only persist local state; no network or
         * BLE calls.
         */
        public synchronized boolean commitProvisioning(long gen,
                java.util.function.BooleanSupplier commit) {
            return provisioningLocked(gen) && commit.getAsBoolean();
        }

        /**
         * Seal a device-to-mobile record. Returns null when there is no
         * active session, or when a nonzero {@code generation} no longer
         * matches (the work it belongs to is stale).
         */
        public synchronized Map<String, Object> encryptJson(String plaintext, long gen) {
            if ((gen != 0 && gen != generation) || expireLocked() || state == State.IDLE) {
                return null;
            }
            long counter = txCounter;
            byte[] sealed = Hashes.aesGcmEncrypt(
                    txKey, recordNonce(FROM_DEVICE, counter),
                    plaintext.getBytes(StandardCharsets.UTF_8),
                    recordAad(sessionIdB64, FROM_DEVICE, counter));
            txCounter += 1;
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "pairing_encrypted");
            envelope.put("session_id", sessionIdB64);
            envelope.put("counter", Long.toString(counter));
            envelope.put("ciphertext",
                    b64urlEncode(Arrays.copyOf(sealed, sealed.length - TAG_BYTES)));
            envelope.put("tag",
                    b64urlEncode(Arrays.copyOfRange(sealed, sealed.length - TAG_BYTES, sealed.length)));
            return envelope;
        }

        public synchronized Map<String, Object> encryptStatus(String status, long gen) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "status");
            message.put("status", status);
            // Apps read only type and status, so older ones ignore the token.
            if (sdkToken != null && "pairing_confirmed".equals(status)) {
                message.put("sdk_token", sdkToken);
            }
            return encryptJson(Json.stringify(message), gen);
        }

        private boolean provisioningLocked(long gen) {
            return gen != 0 && gen == generation && !expireLocked()
                    && state == State.PROVISIONING;
        }

        private void advanceGenerationLocked() { generation += 1; }

        private void resetLocked() {
            advanceGenerationLocked();
            clearLocked();
        }

        private void clearLocked() {
            state = State.IDLE;
            deadline = 0;
            rxKey = null;
            txKey = null;
            sessionIdB64 = "";
            rxCounter = 0;
            txCounter = 0;
        }

        private boolean expireLocked() {
            if (state == State.IDLE || clock.nowSeconds() <= deadline) return false;
            // Drop the keys but keep the generation, so the owner of the
            // expired session can still recognise it.
            clearLocked();
            return true;
        }
    }
}
