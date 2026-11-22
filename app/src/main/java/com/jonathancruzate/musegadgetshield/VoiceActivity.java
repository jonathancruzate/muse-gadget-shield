package com.jonathancruzate.musegadgetshield;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Voice mode screen (v0.6.0; refactored v0.7.0): the explicit
 * full-screen Muse voice UI on the TV — Muse brand header,
 * Stef's avatar as the voice face, captions — now a thin host over
 * the shared {@link VoiceController} + {@link VoiceUi}, which
 * MuseVoiceSession (the default-assistant voice plate) drives
 * identically.
 *
 * Assistant suppression while this screen is foreground: the
 * voice keys are consumed in onKeyDown (and globally by
 * KeyTriggerService), never passed on toward the system's
 * VoiceInteractionService. A press while the loop is idle re-arms
 * listening. Back exits.
 */
public class VoiceActivity extends Activity {
    private VoiceController controller;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make sure the gadget stack (and therefore the link) is up.
        Intent svc = new Intent(this, GadgetService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        controller = new VoiceController(this);
        setContentView(controller.getView());
        controller.start();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyTriggerService.isVoiceKey(keyCode)) {
            if (event.getRepeatCount() == 0) controller.handleVoiceKey();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Re-summoned (e.g. by the key trigger) while already open.
        controller.handleVoiceKey();
    }

    @Override public void onBackPressed() {
        if (controller != null) controller.shutdown();
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (controller != null) controller.shutdown();
        super.onDestroy();
    }
}
