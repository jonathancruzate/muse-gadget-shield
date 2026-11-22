package com.jonathancruzate.musegadgetshield;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * System speech-recognition probe (v0.5.1).
 *
 * Every AudioRecord path from this app is proven deaf to the Shield
 * remote's microphone (see the probe matrix in the README: idle pipes
 * carry silence, and while the remote is streaming the input is
 * exclusively claimed / policy-muted for direct capture). But apps
 * like YouTube get remote-mic voice search through a completely
 * different capture path: the app calls
 * {@link android.speech.SpeechRecognizer} and the SYSTEM recognition
 * service — part of Google's privileged stack — does the listening
 * and hands back text. No audio ever enters the calling app's
 * process, so the AudioRecord lockout does not apply. Whether that
 * service path is open to a normal third-party app on this Shield is
 * exactly what this probe decides.
 *
 * One listening session per action: availability check, start
 * listening with partial results, log every listener callback under
 * the "SPEECH" prefix (levels via the max RMS, transcripts as they
 * arrive), and a hard stop + destroy after 25 s if no terminal
 * callback (results / error) lands first. Like the audio probes,
 * this touches no BLE/link or pairing state — it creates and
 * destroys its own SpeechRecognizer only.
 *
 * All SpeechRecognizer calls happen on the main Looper (the API
 * requires the main thread): {@link #run} posts the session start to
 * a main-Looper Handler, and listener callbacks arrive there too.
 * The live session is held in a static field so the recognizer and
 * its listener cannot be GC'd out from under a pending session.
 *
 * Gated like every capture path (v0.5.0): while voice relay is ON,
 * the probe logs "SPEECH skipped: voice relay on" and does nothing —
 * enforced here and at GadgetService dispatch.
 */
final class SpeechProbe {
    private SpeechProbe() {}

    interface Logger {
        void log(String message);
    }

    private static final long TIMEOUT_MS = 25_000;

    /** The live session, if any (see class javadoc). */
    private static Session active;

    static void run(Context context, Logger logger) {
        if (VoiceRelay.isOn(context)) {
            // Voice relay ON (v0.5.0): this device is not a mic endpoint.
            logger.log("SPEECH skipped: voice relay on");
            return;
        }
        logger.log("SPEECH available=" + SpeechRecognizer.isRecognitionAvailable(context));
        final Session session;
        synchronized (SpeechProbe.class) {
            if (active != null) {
                logger.log("SPEECH busy: a probe session is already running");
                return;
            }
            session = new Session(context.getApplicationContext(), logger);
            active = session;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { session.start(); }
        });
    }

    private static void clearActive(Session session) {
        synchronized (SpeechProbe.class) {
            if (active == session) active = null;
        }
    }

    private static String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "ERROR_INSUFFICIENT_PERMISSIONS";
            default: return "ERROR_UNKNOWN";
        }
    }

    private static String fmtRms(float value) {
        return value == -Float.MAX_VALUE
                ? "none" : String.format(Locale.US, "%.1f", value);
    }

    /**
     * One listening session. Created, started, called back, and
     * destroyed entirely on the main Looper.
     */
    private static final class Session implements RecognitionListener {
        private final Context context;
        private final Logger logger;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private SpeechRecognizer recognizer;
        private boolean finished;
        private float maxRms = -Float.MAX_VALUE;
        private float loggedMax = -Float.MAX_VALUE;
        private int buffers;

        private final Runnable timeout = new Runnable() {
            @Override public void run() { finish("timeout", true); }
        };

        Session(Context context, Logger logger) {
            this.context = context;
            this.logger = logger;
        }

        void start() {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        context.getPackageName());
                // Same endpointing trim as the voice loop (v0.8.1).
                intent.putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1200);
                intent.putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        800);
                recognizer.startListening(intent);
                logger.log("SPEECH listening (hard stop in 25 s)");
            } catch (Exception e) {
                logger.log("SPEECH start failed: " + e.getClass().getSimpleName());
                finish("error", false);
                return;
            }
            handler.postDelayed(timeout, TIMEOUT_MS);
        }

        /**
         * Terminal path, exactly once: stop the clock, tear the
         * recognizer down, log the DONE line, release the session.
         */
        private void finish(String outcome, boolean cancelFirst) {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(timeout);
            if (recognizer != null) {
                try {
                    if (cancelFirst) recognizer.cancel();
                    else recognizer.stopListening();
                } catch (Exception ignored) { }
                try { recognizer.destroy(); } catch (Exception ignored) { }
                recognizer = null;
            }
            logger.log("SPEECH DONE outcome=" + outcome
                    + " maxRms=" + fmtRms(maxRms) + " buffers=" + buffers);
            clearActive(this);
        }

        // ---- RecognitionListener (all callbacks on the main thread) ----

        @Override public void onReadyForSpeech(Bundle params) {
            logger.log("SPEECH readyForSpeech");
        }

        @Override public void onBeginningOfSpeech() {
            logger.log("SPEECH beginningOfSpeech");
        }

        @Override public void onRmsChanged(float rmsdB) {
            if (rmsdB > maxRms) maxRms = rmsdB;
            if (rmsdB >= loggedMax + 3.0f) {
                loggedMax = rmsdB;
                logger.log("SPEECH rms max=" + fmtRms(rmsdB));
            }
        }

        @Override public void onBufferReceived(byte[] buffer) {
            buffers++;
        }

        @Override public void onEndOfSpeech() {
            logger.log("SPEECH endOfSpeech");
        }

        @Override public void onError(int error) {
            logger.log("SPEECH error code=" + error + " name=" + errorName(error));
            finish("error", false);
        }

        @Override public void onResults(Bundle results) {
            ArrayList<String> texts =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String top = texts != null && !texts.isEmpty() ? texts.get(0) : "";
            float[] confidence =
                    results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
            String line = "SPEECH results top=\"" + top + "\"";
            if (confidence != null && confidence.length > 0) {
                line += String.format(Locale.US, " confidence=%.2f", confidence[0]);
            }
            line += " alternates=" + (texts != null ? texts.size() : 0);
            logger.log(line);
            finish("results", false);
        }

        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> texts =
                    partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (texts != null && !texts.isEmpty()) {
                logger.log("SPEECH partial: " + texts.get(0));
            }
        }

        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
