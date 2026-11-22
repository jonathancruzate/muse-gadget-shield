package com.jonathancruzate.musegadgetshield;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

/**
 * Manifest satisfier ONLY (v0.7.0). The voice-interaction
 * meta-data (res/xml/voice_interaction.xml) must name a
 * recognition service inside this package for Muse Gadget to be
 * a valid default-assistant candidate; this stub is that name.
 *
 * It is NOT the app's ears, and it must never become them: the
 * voice loop listens through SpeechRecognizer, which binds
 * Settings.Secure's voice_recognition_service — on this Shield
 * that is Katniss's recognition service
 * (com.google.android.katniss/com.google.android.apps.tvsearch
 * .voice.recognition.KatnissRecognitionService), the lane proven
 * to hear the remote (2026-11-08 23:43, 0.97 confidence). If this
 * stub were ever selected as the recognition service, sessions
 * would fail loudly here instead of listening: onStartListening
 * answers ERROR_CLIENT immediately (a no-op service that never
 * calls back would hang callers until their watchdogs fire).
 */
public class StubRecognitionService extends RecognitionService {
    private static final String TAG = GadgetService.TAG;

    @Override protected void onStartListening(Intent recognizerIntent, Callback listener) {
        Log.w(TAG, "VOICE stub recognition service invoked — it is a manifest "
                + "satisfier only; the recognition service should be Katniss's");
        if (listener != null) {
            try {
                listener.error(SpeechRecognizer.ERROR_CLIENT);
            } catch (RemoteException ignored) { }
        }
    }

    @Override protected void onCancel(Callback listener) {
        // No-op by design (see class javadoc).
    }

    @Override protected void onStopListening(Callback listener) {
        // No-op by design (see class javadoc).
    }
}
