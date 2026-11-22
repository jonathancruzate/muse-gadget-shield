package com.jonathancruzate.musegadgetshield;

import android.content.Intent;
import android.os.Build;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

/**
 * The app's VoiceInteractionService (v0.7.0) — the component that
 * makes Muse Gadget eligible for the default-assistant role.
 *
 * Why the role is the mechanism (proven 2026-11-09 00:05 EST): on
 * this Shield build the remote's mic key produces NO accessibility
 * key event at all — the isolated-press capture was empty while
 * Katniss ran a full Assistant session. The press is routed by the
 * system to whichever app holds the voice-interaction role, full
 * stop. Jonathan's decision (00:06 EST): Muse Gadget takes the
 * role, so the button invokes MuseVoiceSession (our avatar voice
 * plate) natively and Google Assistant is never invoked — the
 * "no Assistant popup" requirement holds by construction.
 *
 * The meta-data (res/xml/voice_interaction.xml) names
 * MuseVoiceSessionService as the session service and
 * StubRecognitionService as the recognition-service satisfier.
 * The app itself keeps listening through SpeechRecognizer, which
 * binds Settings.Secure's voice_recognition_service — Katniss's
 * recognition service, deliberately untouched by the role change.
 */
public class MuseVoiceInteractionService extends VoiceInteractionService {
    private static final String TAG = GadgetService.TAG;

    @Override public void onReady() {
        super.onReady();
        Log.i(TAG, "VOICE interaction service ready (default-assist role held by Muse Gadget)");
        // Keep the gadget stack (and therefore the link) up.
        Intent svc = new Intent(this, GadgetService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception e) {
            Log.w(TAG, "VOICE interaction onReady: gadget start failed: "
                    + e.getClass().getSimpleName());
        }
    }

    @Override public void onShutdown() {
        Log.i(TAG, "VOICE interaction service shutdown (role released)");
        super.onShutdown();
    }
}
