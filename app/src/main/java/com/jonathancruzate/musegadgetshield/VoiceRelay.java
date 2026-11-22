package com.jonathancruzate.musegadgetshield;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Voice relay to phone" (v0.5.0, Jonathan's directive 2026-11-08):
 * an optional mode for the Shield. The TV's microphone paths are
 * verified unusable by apps (see the voice probe verdict in the
 * README), so when this toggle is ON the Shield gives up being a
 * microphone endpoint entirely — its own RECORD_AUDIO permission is
 * revoked (best effort, see {@link #stripMicPermission}) and every
 * capture path in the app is gated off — and voice mode instead
 * activates on Jonathan's phone, via the relay chat message built by
 * {@link #buildMessage} (sent by GadgetService over the link
 * session's /chat/stream, the same path LinkSession.sendVoice posts
 * to, with a text payload instead of a WAV item). There is no Talk
 * button: the OFF→ON toggle flip itself is the signal, and the
 * message is sent exactly once per enable — switching OFF sends
 * nothing, and an app restart with the toggle already ON does not
 * resend.
 *
 * The preference lives in its own SharedPreferences file so both
 * MainActivity (the couch UI) and GadgetService (the sender + probe
 * gate) read the same state. Default OFF.
 */
public final class VoiceRelay {
    private VoiceRelay() {}

    private static final String TAG = GadgetService.TAG;
    static final String PREFS_NAME = "voice_relay";
    static final String KEY_RELAY_ON = "relay_to_phone";

    public static boolean isOn(Context context) {
        return prefs(context).getBoolean(KEY_RELAY_ON, false);
    }

    public static void setOn(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_RELAY_ON, on).apply();
        Log.i(TAG, "RELAY voice relay " + (on ? "ON" : "OFF"));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The relay message, verbatim (Jonathan's wording), with a real
     * activation timestamp appended: the text ends with
     * "(Activated yyyy-MM-dd HH:mm:ss zzz.)" in the device's local
     * timezone at send time.
     */
    public static String buildMessage(Date when) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US);
        return "[Voice relay — Shield TV] Jonathan activated voice from the Shield TV "
                + "in the living room. The TV has no usable microphone (verified), so "
                + "voice mode should run on his phone. Context: he is at the TV and "
                + "wants to talk to Muse and control the Shield TV. Please bring up "
                + "voice on the phone for this conversation. "
                + "(Activated " + fmt.format(when) + ".)";
    }

    /**
     * Give up this app's microphone permission. The runtime self-revoke
     * API ({@code Context.revokeSelfPermissionOnKill}) is API 31+; this
     * app runs on the Shield's API 30, so it is attempted reflectively
     * (and will take effect on any newer device) while on API 30 the
     * enforcement is the code gate: while the relay is ON, GadgetService
     * refuses the probe actions and no capture path runs. Turning the
     * toggle OFF does NOT re-grant the permission — if the mic is ever
     * wanted again on a device where the revoke landed, re-grant it via
     * Settings > Apps > Muse Gadget Shield > Permissions.
     */
    public static void stripMicPermission(Context context) {
        try {
            Method m = Context.class.getMethod(
                    "revokeSelfPermissionOnKill", String.class);
            m.invoke(context, Manifest.permission.RECORD_AUDIO);
            Log.i(TAG, "RELAY mic strip: revokeSelfPermissionOnKill(RECORD_AUDIO) invoked");
        } catch (NoSuchMethodException e) {
            Log.i(TAG, "RELAY mic strip: revokeSelfPermissionOnKill unavailable on API "
                    + Build.VERSION.SDK_INT
                    + "; capture paths are gated by the relay flag instead");
        } catch (Exception e) {
            Log.w(TAG, "RELAY mic strip: revoke attempt failed: "
                    + e.getClass().getSimpleName());
        }
    }
}
