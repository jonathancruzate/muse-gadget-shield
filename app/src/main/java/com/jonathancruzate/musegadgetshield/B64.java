package com.jonathancruzate.musegadgetshield;

import java.util.Base64;

/** Base64 (standard and URL-safe, unpadded on encode) and hex helpers. */
public final class B64 {
    private B64() {}

    public static String urlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] urlDecode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }

    public static String stdEncode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] stdDecode(String text) {
        return Base64.getDecoder().decode(text);
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static byte[] unhex(String text) {
        if ((text.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
