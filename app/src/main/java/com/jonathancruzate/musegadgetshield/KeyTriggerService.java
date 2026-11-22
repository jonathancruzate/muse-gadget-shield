package com.jonathancruzate.musegadgetshield;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The remote-voice-key trigger (v0.6.0, Jonathan's directive
 * 2026-11-08 23:16): "built in search triggers by keycode that
 * activates muse gadget sdk voice mode".
 *
 * The Shield remote's microphone button is, by default, Google
 * Assistant's: the system routes the press to the
 * VoiceInteractionService before most apps ever see it. This
 * accessibility service asks Android for key-event filtering
 * (res/xml/key_trigger_service.xml: flagRequestFilterKeyEvents +
 * canRequestFilterKeyEvents), which places {@link #onKeyEvent} in
 * the input path ahead of that routing. On ACTION_DOWN of the voice
 * keys — KEYCODE_ASSIST (219), KEYCODE_VOICE_ASSIST (231), and
 * KEYCODE_SEARCH (84), the keycode the community remappers retarget
 * this button to — the press is CONSUMED (returning true drops it,
 * so Assistant's UI never appears) and VoiceActivity launches.
 * Key-ups of the same keys are consumed too, so a stray release
 * can't fall through to the system either. Every key the service
 * sees is logged under the KEYPROBE prefix: the first live check of
 * this trigger is Jonathan pressing the mic button once while we
 * watch whether ASSIST actually reaches this service on this build
 * (the make-or-break fact the forum remappers kept failing on).
 *
 * The service retrieves no window content and ignores every
 * accessibility event; key filtering is its only job.
 */
public class KeyTriggerService extends AccessibilityService {
    private static final String TAG = GadgetService.TAG;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "KEYPROBE trigger service connected (key filtering requested)");
    }

    @Override public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        Log.i(TAG, "KEYPROBE code=" + code + " action=" + (down ? "down" : "up")
                + " repeat=" + event.getRepeatCount());
        if (isVoiceKey(code)) {
            if (down && event.getRepeatCount() == 0) {
                Log.i(TAG, "KEYPROBE voice key consumed (code=" + code
                        + "); launching voice mode, Assistant suppressed");
                Intent intent = new Intent(this, VoiceActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "KEYPROBE voice activity launch failed: "
                            + e.getClass().getSimpleName());
                }
            }
            // Consume downs AND ups of the voice keys: nothing about
            // this press may reach the VoiceInteractionService.
            return true;
        }
        return super.onKeyEvent(event);
    }

    static boolean isVoiceKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ASSIST
                || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_SEARCH;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Ignored by design: this service exists for key filtering only.
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        Log.i(TAG, "KEYPROBE trigger service destroyed");
        super.onDestroy();
    }
}
