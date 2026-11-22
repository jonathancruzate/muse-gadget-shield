package com.jonathancruzate.musegadgetshield;

import java.io.File;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stable device identity; a port of the SDK's identity.py.
 *
 * The identity is a random, locally administered MAC-shaped value
 * generated once and kept across upgrades and unpairing. The derived
 * names follow the Muse Gadget conventions the apps expect: the node id
 * and the BLE name end in the same six hex digits.
 */
public final class Identity {
    public static final String NODE_ID_PREFIX = "homelink-";
    public static final String BLE_NAME_PREFIX = "MuseGadget-";
    private static final Pattern MAC_RE =
            Pattern.compile("[0-9a-f]{2}(:[0-9a-f]{2}){5}");

    public final String mac;

    private Identity(String mac) { this.mac = mac; }

    public String suffix() {
        String flat = mac.replace(":", "");
        return flat.substring(flat.length() - 6);
    }

    public String nodeId() { return NODE_ID_PREFIX + suffix(); }

    public String deviceId() { return "hatch-link:" + mac; }

    public String bleName() {
        // Hyphenated form ("MuseGadget-XXXXXX") — FINAL fleet standard,
        // owner's ruling 2026-11-08: the ESP32 firmware ships hyphenated
        // (esp32 identity.c / muse_video_wifi.c construct the name that
        // way), so the ESP32 form as shipped is the reference and every
        // port converges on it. This supersedes the Linux client's
        // no-hyphen convention (linux identity.py / AGENTS.md).
        return BLE_NAME_PREFIX + suffix().toUpperCase(java.util.Locale.US);
    }

    public static String generateMac(SecureRandom random) {
        byte[] octets = new byte[6];
        random.nextBytes(octets);
        octets[0] = (byte) ((octets[0] & 0xFC) | 0x02); // unicast, locally administered
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < octets.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", octets[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Load the persisted identity, or create and persist one. When the
     * v0.3 prototype's bare suffix is supplied as {@code legacySuffix}, the
     * MAC is built around it so the already-advertised node id and BLE
     * name (and their FA76F8 suffix) survive the upgrade.
     */
    public static Identity loadOrCreate(File stateDir, String legacySuffix, SecureRandom random) {
        Map<String, Object> stored = DeviceStore.loadJson(new File(stateDir, "identity.json"));
        if (stored != null) {
            Object mac = stored.get("mac");
            if (mac instanceof String && MAC_RE.matcher((String) mac).matches()) {
                return new Identity((String) mac);
            }
        }
        Identity identity;
        if (legacySuffix != null && legacySuffix.length() == 6) {
            String s = legacySuffix.toLowerCase(java.util.Locale.US);
            identity = new Identity("02:00:00:"
                    + s.substring(0, 2) + ":" + s.substring(2, 4) + ":" + s.substring(4, 6));
        } else {
            identity = new Identity(generateMac(random));
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("mac", identity.mac);
        DeviceStore.saveJson(new File(stateDir, "identity.json"), record);
        return identity;
    }
}
