package com.jonathancruzate.musegadgetshield;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The default-assistant voice plate (v0.7.0): what appears when
 * the remote's mic button invokes Muse Gadget in its
 * default-assist role. It inflates the SAME avatar voice UI as
 * VoiceActivity (shared {@link VoiceUi}) and runs the SAME loop
 * (shared {@link VoiceController}): onShow starts the loop
 * straight into its LISTENING state; onHide stops it; Back
 * finishes the session.
 *
 * Because the invocation arrives through the voice-interaction
 * role itself, Google Assistant is never in this path at all —
 * there is nothing to suppress.
 *
 * Re-press (v0.9.2): pressing the mic button again while the
 * plate is up makes the system hide and immediately re-show
 * this session. Pausing on that hide tore down the running
 * recognizer and the re-show opened a new phase with a new
 * beep (the 2026-11-09 22:30 bug) — so onHide now DEFERS the
 * pause briefly; a re-show inside the grace cancels it and the
 * invocation reaches the existing controller as a mic-press:
 * the mic toggle of the persistent conversation (mute while
 * listening, unmute while muted, no-op during a turn) — never
 * a new conversation, never a teardown-and-restart. A genuine
 * hide still pauses, just a beat later.
 */
public class MuseVoiceSession extends VoiceInteractionSession {
    private static final String TAG = GadgetService.TAG;
    private static final long HIDE_PAUSE_GRACE_MS = 750;

    private VoiceController controller;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Runnable deferredPause = new Runnable() {
        @Override public void run() {
            if (controller != null) controller.pause();
        }
    };

    public MuseVoiceSession(Context context) {
        super(context);
    }

    @Override public View onCreateContentView() {
        controller = new VoiceController(getContext());
        return controller.getView();
    }

    @Override public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        // A re-show inside the hide grace is a re-press: cancel
        // the pending pause so start() sees the live phase and
        // handles it as one (VoiceController.handleRepress).
        hideHandler.removeCallbacks(deferredPause);
        // Full-screen plate: the avatar UI is designed for the
        // whole TV, not a bottom strip.
        try {
            if (getWindow() != null && getWindow().getWindow() != null) {
                getWindow().getWindow().setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT);
            }
        } catch (Exception e) {
            Log.i(TAG, "VOICE session window sizing failed: "
                    + e.getClass().getSimpleName());
        }
        Log.i(TAG, "VOICE session shown (flags=" + showFlags + ")");
        if (controller != null) controller.start();
    }

    @Override public void onHide() {
        // Deferred (see the class doc): the re-press hide→show
        // pair must not pause the phase. A genuine hide pauses
        // HIDE_PAUSE_GRACE_MS later; onDestroy/onBackPressed
        // cancel the pending pause and tear down directly.
        hideHandler.removeCallbacks(deferredPause);
        hideHandler.postDelayed(deferredPause, HIDE_PAUSE_GRACE_MS);
        super.onHide();
    }

    @Override public void onDestroy() {
        hideHandler.removeCallbacks(deferredPause);
        if (controller != null) controller.shutdown();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyTriggerService.isVoiceKey(keyCode)) {
            if (controller != null && event.getRepeatCount() == 0) {
                controller.handleVoiceKey();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        hideHandler.removeCallbacks(deferredPause);
        if (controller != null) controller.pause();
        finish();
    }
}
