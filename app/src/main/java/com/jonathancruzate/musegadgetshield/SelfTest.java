package com.jonathancruzate.musegadgetshield;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-device self-test: replays the SDK's community_app_v5 pairing test
 * vector (synthetic data published with the SDK) through the production
 * PairingSession, plus the RFC 7748 section 6.1 X25519 vector. Run via
 * the SELFTEST intent action; results land in logcat. No network, no BLE.
 */
public final class SelfTest {
    public interface Logger { void log(String message); }

    private SelfTest() {}

    // community_app_v5 vector constants (synthetic; from the SDK test suite).
    private static final String MOBILE_PUB_B64 = "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
    private static final String MOBILE_NONCE_B64 = "AAECAwQFBgcICQoLDA0ODw";
    private static final String DEVICE_NONCE_B64 = "EBESExQVFhcYGRobHB0eHw";
    private static final String EXPECTED_DEVICE_PUB_B64 = "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E";
    private static final String EXPECTED_TRANSCRIPT_HASH_B64 = "18Rs196tzddPGuj18pZ8rWObWYpwoDUIwocjnUASj_0";
    private static final String EXPECTED_SESSION_ID_B64 = "z0eNLGw5mczvD4a1F2ubSQ";
    private static final String FINISHED_CIPHERTEXT_B64 = "uwNX3KEkix5T6nWce3s2SKPPh-5E31x-UIHFSK6MpMUg562B";
    private static final String FINISHED_TAG_B64 = "zT4kU6XK-rM7rvt3VEITLg";
    private static final String MOBILE_RX_KEY_HEX = "9abb2954b05e2f5f7112e9bfd41fd970602e27ed07453c1cff51f575de5d0b4e";

    public static void run(Logger log) {
        int failures = 0;
        failures += checkX25519(log) ? 0 : 1;
        failures += checkPairingVector(log) ? 0 : 1;
        log.log("SELFTEST " + (failures == 0 ? "PASS (all)" : "FAIL (" + failures + " groups)"));
    }

    private static boolean checkX25519(Logger log) {
        try {
            byte[] alicePriv = B64.unhex(
                    "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
            byte[] alicePubExpected = B64.unhex(
                    "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
            byte[] bobPub = B64.unhex(
                    "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
            byte[] sharedExpected = B64.unhex(
                    "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
            byte[] base = new byte[32];
            base[0] = 9;
            byte[] alicePub = X25519.scalarMult(alicePriv, base);
            if (!Arrays.equals(alicePub, alicePubExpected)) {
                log.log("SELFTEST x25519 FAIL: public key mismatch");
                return false;
            }
            byte[] shared = X25519.dh(alicePriv, bobPub);
            if (!Arrays.equals(shared, sharedExpected)) {
                log.log("SELFTEST x25519 FAIL: shared secret mismatch");
                return false;
            }
            log.log("SELFTEST x25519 PASS (RFC 7748 6.1)");
            return true;
        } catch (Exception e) {
            log.log("SELFTEST x25519 FAIL: " + e);
            return false;
        }
    }

    private static boolean checkPairingVector(Logger log) {
        try {
            final byte[] deviceNonce = Pairing.b64urlDecode(DEVICE_NONCE_B64);
            Pairing.PairingSession session = new Pairing.PairingSession(
                    "homelink-000001", "hatch-link:02:00:00:00:00:01", "02:00:00:00:00:01",
                    "1.0.0", null,
                    Pairing.systemClock(),
                    new Pairing.KeyGen() {
                        @Override public PrivateKey generate() throws Exception {
                            return Pairing.privateKeyFromScalar(BigInteger.valueOf(2));
                        }
                    },
                    new Pairing.RandomSource() {
                        @Override public byte[] bytes(int n) {
                            if (n != deviceNonce.length) {
                                throw new IllegalStateException("unexpected nonce size " + n);
                            }
                            return deviceNonce.clone();
                        }
                    });
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("action", "pairing_client_hello");
            hello.put("version", 5L);
            hello.put("pairing_auth", "none");
            hello.put("pairing_policy", "confirm_app");
            hello.put("mobile_pub", MOBILE_PUB_B64);
            hello.put("mobile_nonce", MOBILE_NONCE_B64);
            Map<String, Object> ready = session.handleHello(hello);
            if (!EXPECTED_DEVICE_PUB_B64.equals(ready.get("device_pub"))) {
                log.log("SELFTEST pairing FAIL: device_pub mismatch: " + ready.get("device_pub"));
                return false;
            }
            if (!EXPECTED_TRANSCRIPT_HASH_B64.equals(ready.get("transcript_hash"))) {
                log.log("SELFTEST pairing FAIL: transcript_hash mismatch: "
                        + ready.get("transcript_hash"));
                return false;
            }
            if (!EXPECTED_SESSION_ID_B64.equals(ready.get("session_id"))) {
                log.log("SELFTEST pairing FAIL: session_id mismatch: " + ready.get("session_id"));
                return false;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "pairing_encrypted");
            record.put("session_id", EXPECTED_SESSION_ID_B64);
            record.put("counter", "0");
            record.put("ciphertext", FINISHED_CIPHERTEXT_B64);
            record.put("tag", FINISHED_TAG_B64);
            String plaintext = session.decrypt(record);
            if (!"{\"action\":\"pairing_client_finished\"}".equals(plaintext)) {
                log.log("SELFTEST pairing FAIL: client_finished plaintext: " + plaintext);
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> finished = (Map<String, Object>) Json.parse(plaintext);
            long generation = session.handleClientFinished(finished);
            if (generation == 0) {
                log.log("SELFTEST pairing FAIL: client_finished rejected");
                return false;
            }
            Map<String, Object> status = session.encryptStatus("pairing_confirmed", generation);
            if (status == null) {
                log.log("SELFTEST pairing FAIL: pairing_confirmed not sealed");
                return false;
            }
            byte[] ct = Pairing.b64urlDecode(status.get("ciphertext"));
            byte[] tag = Pairing.b64urlDecode(status.get("tag"));
            byte[] opened = Hashes.aesGcmDecrypt(
                    B64.unhex(MOBILE_RX_KEY_HEX),
                    Pairing.recordNonce(1, 0),
                    Hashes.concat(ct, tag),
                    Pairing.recordAad(EXPECTED_SESSION_ID_B64, 1, 0));
            String openedText = new String(opened, java.nio.charset.StandardCharsets.UTF_8);
            if (!openedText.contains("\"status\":\"pairing_confirmed\"")) {
                log.log("SELFTEST pairing FAIL: confirmed payload: " + openedText);
                return false;
            }
            log.log("SELFTEST pairing PASS (community_app_v5 vector, both directions)");
            return true;
        } catch (Exception e) {
            log.log("SELFTEST pairing FAIL: " + e);
            return false;
        }
    }
}
