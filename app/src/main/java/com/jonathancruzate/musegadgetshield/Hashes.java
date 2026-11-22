package com.jonathancruzate.musegadgetshield;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hash/HMAC/HKDF/AES-GCM primitives matching the Python SDK's use of
 * ``hashlib``/``hmac``/``cryptography`` (HKDF per RFC 5869, AES-256-GCM
 * with the tag appended to the ciphertext, as Python's AESGCM returns).
 */
public final class Hashes {
    private Hashes() {}

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) md.update(part);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF-Expand only (RFC 5869 section 2.2), as pairing.py's HKDFExpand. */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        int counter = 1;
        while (done < length) {
            byte[] input = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, input, 0, t.length);
            System.arraycopy(info, 0, input, t.length, info.length);
            input[input.length - 1] = (byte) counter++;
            t = hmacSha256(prk, input);
            int take = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, take);
            done += take;
        }
        return out;
    }

    /** Full HKDF (extract with {@code salt}, then expand with {@code info}). */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] realSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        byte[] prk = hmacSha256(realSalt, ikm);
        return hkdfExpand(prk, info, length);
    }

    /** AES-GCM encrypt; returns ciphertext with the 16-byte tag appended. */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /** AES-GCM decrypt of ciphertext-with-appended-tag. Throws on a bad tag. */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertextAndTag, byte[] aad)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertextAndTag);
    }

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
