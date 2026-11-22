package com.jonathancruzate.musegadgetshield;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two plain-HTTPS device API calls; a port of the SDK's muse_api.py.
 */
public final class MuseApi {
    private MuseApi() {}

    private static final String TAG = "MuseGadgetShield";

    public static final String DEFAULT_API_URL = "https://api.muse.ai";
    public static final String DEFAULT_NOISE_HOST = "hatch.metaaivm.com";
    private static final String API_VERSION = "1.0.0";
    private static final int TIMEOUT_MS = 30000;

    public static final class HttpStatusException extends IOException {
        public final int status;
        HttpStatusException(int status, String body) {
            super("HTTP " + status + (body != null && !body.isEmpty() ? ": " + body : ""));
            this.status = status;
        }
    }

    public static final class Vm {
        public final String vmId;
        public final String authToken;
        public final boolean isDefault;
        Vm(String vmId, String authToken, boolean isDefault) {
            this.vmId = vmId;
            this.authToken = authToken;
            this.isDefault = isDefault;
        }
    }

    /**
     * Host portion of a URL, for diagnostics only. Never returns paths,
     * queries or credentials; "empty" for a missing/blank URL.
     */
    static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "empty";
        try {
            String host = new URL(url).getHost();
            return host != null && !host.isEmpty() ? host : "unparseable";
        } catch (Exception e) {
            return "unparseable";
        }
    }

    public static String apiRoot(Map<String, Object> pairing) {
        Object v2 = pairing.get("api_url_v2");
        if (v2 instanceof String && !((String) v2).isEmpty()) {
            return ((String) v2).replaceAll("/+$", "");
        }
        return DEFAULT_API_URL;
    }

    public static String noiseHost(Map<String, Object> pairing) {
        Object host = pairing.get("noise_host");
        if (host instanceof String && !((String) host).isEmpty()) return (String) host;
        return DEFAULT_NOISE_HOST;
    }

    /** GET /fetch_vms with the device access token; parses the VM list. */
    @SuppressWarnings("unchecked")
    public static List<Vm> fetchVms(Map<String, Object> pairing) throws IOException {
        Object accessToken = pairing.get("access_token");
        if (!(accessToken instanceof String) || ((String) accessToken).isEmpty()) {
            throw new IOException("pairing record has no access token");
        }
        String root = apiRoot(pairing);
        String url = root + "/fetch_vms";
        // Diagnostics (v0.4.4): host only — never the full URL, the token,
        // or the response body (a fetch_vms body carries VM auth tokens).
        Log.i(TAG, "fetch_vms: api root host=" + hostOf(root));
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("X-API-Version", API_VERSION);
        int status;
        String body;
        try {
            status = conn.getResponseCode();
            body = readBody(conn, status);
        } catch (IOException e) {
            Log.w(TAG, "fetch_vms: request failed, no HTTP status ("
                    + e.getClass().getSimpleName() + ")");
            throw e;
        }
        if (status < 200 || status >= 300) {
            Log.w(TAG, "fetch_vms: HTTP " + status + " from host=" + hostOf(root));
            throw new HttpStatusException(status, body);
        }
        Object data = Json.parse(body);
        List<Vm> vms;
        try {
            vms = parseFetchVms(data, status);
        } catch (IOException e) {
            // The parse error message carries only the backend error CODE
            // value, never the response body.
            Log.w(TAG, "fetch_vms: " + e.getMessage());
            throw e;
        }
        Log.i(TAG, "fetch_vms: HTTP " + status + ", vms=" + vms.size());
        return vms;
    }

    /**
     * Parse a fetch_vms response body (already decoded from JSON) in the
     * REAL API schema: the VM list lives under "vm_list" — NOT "vms"
     * (that drift is what broke pairing in v0.4.4 and earlier: every
     * healthy response parsed as 0 VMs). Per entry: "vm_id",
     * "vm_auth_token", "default". Matches the SDK's muse_api.py and the
     * ESP32 vm_api.c. A top-level "error_title" / "backend_error_code"
     * is an API failure even on HTTP 200 and throws; the exception
     * message carries only the error CODE value, never the body.
     * Kept free of android.util.Log so desktop checks can run it.
     */
    @SuppressWarnings("unchecked")
    static List<Vm> parseFetchVms(Object data, int status) throws IOException {
        if (!(data instanceof Map)) {
            throw new IOException("fetch_vms returned unexpected JSON");
        }
        Map<String, Object> map = (Map<String, Object>) data;
        Object errorTitle = map.get("error_title");
        Object errorCode = map.get("backend_error_code");
        boolean hasError = (errorTitle != null && !(errorTitle instanceof String
                        && ((String) errorTitle).isEmpty()))
                || (errorCode != null && !(errorCode instanceof String
                        && ((String) errorCode).isEmpty()));
        if (hasError) {
            String code = errorCode instanceof String ? (String) errorCode : "none";
            throw new HttpStatusException(status,
                    "fetch_vms error response, backend_error_code=" + code);
        }
        Object vmListRaw = map.get("vm_list");
        java.util.List<Vm> vms = new java.util.ArrayList<>();
        if (vmListRaw instanceof List) {
            for (Object item : (List<Object>) vmListRaw) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> vm = (Map<String, Object>) item;
                Object vmId = vm.get("vm_id");
                Object authToken = vm.get("vm_auth_token");
                if (!(vmId instanceof String) || !(authToken instanceof String)) continue;
                boolean isDefault = Boolean.TRUE.equals(vm.get("default"));
                vms.add(new Vm((String) vmId, (String) authToken, isDefault));
            }
        }
        return vms;
    }

    /** Pick the account's default VM, falling back to the first. */
    public static Vm pickVm(List<Vm> vms) {
        for (Vm vm : vms) if (vm.isDefault) return vm;
        return vms.isEmpty() ? null : vms.get(0);
    }

    /**
     * Rotate the device token pair via POST /device_token/refresh; a port
     * of refresh_device_token. Throws {@link HttpStatusException} with the
     * HTTP status; a 401 means the pairing is revoked.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> refreshDeviceToken(
            Map<String, Object> pairing, String deviceId, String sdkToken) throws IOException {
        Object refreshTokenRaw = pairing.get("refresh_token");
        if (!(refreshTokenRaw instanceof String) || ((String) refreshTokenRaw).isEmpty()) {
            throw new IOException("pairing record has no refresh token");
        }
        String refreshToken = (String) refreshTokenRaw;
        if (refreshToken.startsWith("hatch_refresh:")) {
            refreshToken = refreshToken.substring("hatch_refresh:".length());
        }
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("device_id", deviceId);
        if (sdkToken != null) bodyMap.put("sdk_token", sdkToken);
        byte[] body = Json.stringify(bodyMap).getBytes(StandardCharsets.UTF_8);
        String url = apiRoot(pairing) + "/device_token/refresh";
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer hatch_refresh:" + refreshToken);
        conn.setRequestProperty("X-API-Version", API_VERSION);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int status = conn.getResponseCode();
        String responseBody = readBody(conn, status);
        if (status < 200 || status >= 300) throw new HttpStatusException(status, responseBody);
        Object data = Json.parse(responseBody);
        Map<String, String> result = new LinkedHashMap<>();
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            // SDK muse_api._post_refresh: the token pair may be wrapped
            // in a "payload" object; read from inside it when present.
            Object payload = map.get("payload");
            if (payload instanceof Map) map = (Map<String, Object>) payload;
            Object access = map.get("access_token");
            Object refresh = map.get("refresh_token");
            if (access instanceof String) result.put("access_token", (String) access);
            if (refresh instanceof String) result.put("refresh_token", (String) refresh);
        }
        return result;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "musegadget-shield");
        return conn;
    }

    private static String readBody(HttpURLConnection conn, int status) throws IOException {
        InputStream stream = status >= 200 && status < 300
                ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            try { stream.close(); } catch (IOException ignored) { }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
