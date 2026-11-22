package com.jonathancruzate.musegadgetshield;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent device state in the app's private files directory; a port of
 * the SDK's config.py file handling (atomic JSON writes) plus the SDK
 * token rules.
 */
public final class DeviceStore {
    private DeviceStore() {}

    public static final String PAIRING_FILE = "pairing.json";
    public static final String SDK_TOKEN_FILE = "sdk_token";

    // mgst_ plus 43 canonical base64url characters, as issued by gadgets.muse.ai.
    private static final Pattern SDK_TOKEN_RE =
            Pattern.compile("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");

    public static Map<String, Object> loadJson(File file) {
        try {
            String text = readText(file);
            if (text == null) return null;
            Object data = Json.parse(text);
            if (data instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) data;
                return map;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Atomically write {@code data} as JSON: a crash leaves the old file or the new one. */
    public static void saveJson(File file, Map<String, Object> data) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(Json.stringify(data));
                w.flush();
            }
            if (!tmp.renameTo(file)) {
                // Fall back to a direct write if the filesystem refuses the rename.
                try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                    w.write(Json.stringify(data));
                    w.flush();
                }
                tmp.delete();
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not save " + file.getName(), e);
        }
    }

    public static void deleteJson(File file) {
        if (file.exists()) file.delete();
    }

    private static String readText(File file) throws IOException {
        if (!file.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /**
     * The SDK token from the state directory, or null. Throws
     * {@link IllegalArgumentException} for a token that gadgets.muse.ai
     * could not have issued (the SDK raises ValueError the same way).
     */
    public static String sdkToken(File stateDir) {
        String token;
        try {
            token = readText(new File(stateDir, SDK_TOKEN_FILE));
        } catch (IOException e) {
            return null;
        }
        if (token == null) return null;
        token = token.trim();
        if (token.isEmpty()) return null;
        if (!SDK_TOKEN_RE.matcher(token).matches()) {
            throw new IllegalArgumentException(
                    "the SDK token is not valid; copy it again from gadgets.muse.ai");
        }
        return token;
    }

    /** The part of an SDK token gadgets.muse.ai shows, or "none" without a token. */
    public static String sdkTokenHint(String token) {
        return token != null ? token.substring(0, Math.min(12, token.length())) : "none";
    }
}
