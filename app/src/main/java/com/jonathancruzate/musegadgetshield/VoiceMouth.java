package com.jonathancruzate.musegadgetshield;

import android.content.Context;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The TV's mouth: speak a reply ON THIS DEVICE. Two mouths, in
 * order of preference —
 *
 *   1. the SDK's TTS stream for the reply's message id
 *      (LinkService.streamTts, MP3 via MediaPlayer) — Muse's
 *      real voice out of the TV speakers, whole-message only;
 *   2. Android TextToSpeech — sentence-streamed: each complete
 *      sentence is queued as its own utterance the moment the
 *      ReplyTracker finalizes it (v0.8.1), so a long reply
 *      starts speaking seconds into the turn instead of after
 *      the full text lands;
 *   3. nothing (captions only) — a FAILED turn for a TV-started
 *      conversation, logged as such by the caller.
 *
 * v0.8.1 shape: a per-turn {@link SpeechSession}. Sentences are
 * offered as they finalize; the MP3 fetch starts speculatively
 * the moment a reply's message completes (the earliest the TTS
 * endpoint can serve it, with short 404 retries for asset lag).
 * The MP3 contains the WHOLE message, so it can only claim a
 * turn in which no sentence has started speaking yet — the
 * moment Android TTS starts a sentence, the turn belongs to the
 * streamed mouth and the prefetch is discarded. Failures are
 * logged with exception class AND message (never payloads), so
 * a streamTts refusal is diagnosable from logcat.
 *
 * A session counts speech only when audio actually completes
 * (utterance onDone / MediaPlayer OnCompletion) — an initialized
 * engine that never speaks does not count (Jonathan's acceptance
 * criterion). Blocking methods: worker threads only.
 */
final class VoiceMouth {
    private static final String TAG = GadgetService.TAG;

    enum Result { STREAM_TTS, ANDROID_TTS, NONE }

    private VoiceMouth() { }

    /** One voice turn's speech: sentence queue + MP3 race. */
    static final class SpeechSession {
        interface Listener {
            /** An utterance started: caption scroll sync point. */
            void onSentenceStart(int globalOffset);
        }

        /** How long finish() waits for a pending MP3 before the
         *  streamed mouth takes an unstarted turn. */
        private static final long MP3_HOLD_MS = 3000;
        /** streamTts not-ready retries (404 = asset not generated yet). */
        private static final int[] PREFETCH_RETRY_SLEEPS_MS = {2000, 4000, 6000};

        private final Context appContext;
        private final TextToSpeech tts;
        private final boolean ttsUsable;
        private final Listener listener;

        private final Object lock = new Object();
        private final List<String> allSentences = new ArrayList<>();
        private final Map<String, Integer> utteranceOffsets = new HashMap<>();
        private int sentenceSeq;
        private int enqueued;
        private int completedCount;
        private boolean anyStarted;
        private boolean queueStarted;
        private boolean ttsAbandoned;
        private boolean finished;
        private Result result = Result.NONE;

        private volatile byte[] prefetchMp3;
        private volatile String prefetchError;
        private final CountDownLatch prefetchDone = new CountDownLatch(1);
        private volatile boolean prefetchStarted;

        SpeechSession(Context appContext, TextToSpeech tts, boolean ttsReady,
                Listener listener) {
            this.appContext = appContext;
            this.tts = tts;
            this.ttsUsable = tts != null && ttsReady;
            this.listener = listener;
            if (this.ttsUsable) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        Integer offset;
                        synchronized (lock) {
                            anyStarted = true;
                            offset = utteranceOffsets.get(utteranceId);
                        }
                        if (listener != null && offset != null) {
                            listener.onSentenceStart(offset);
                        }
                    }
                    @Override public void onDone(String utteranceId) {
                        utteranceCompleted();
                    }
                    @Override public void onError(String utteranceId) {
                        utteranceCompleted();
                    }
                    @Override public void onStop(String utteranceId, boolean interrupted) {
                        utteranceCompleted();
                    }
                });
            }
        }

        private void utteranceCompleted() {
            synchronized (lock) {
                completedCount++;
                lock.notifyAll();
            }
        }

        /**
         * Offer one complete sentence (from the SentencePump,
         * subscription thread; quick and non-blocking). Queued
         * for Android TTS immediately unless the MP3 has already
         * claimed the turn or TTS is unusable (then it is held
         * for the finish() decision).
         */
        void offerSentence(String sentence, int globalOffset) {
            synchronized (lock) {
                if (finished) return;
                allSentences.add(sentence);
                if (!ttsUsable) return;
                enqueueLocked(sentence, globalOffset);
            }
        }

        private void enqueueLocked(String sentence, int globalOffset) {
            String id = "vs-" + (sentenceSeq++);
            utteranceOffsets.put(id, globalOffset);
            android.os.Bundle params = new android.os.Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            int mode = queueStarted ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
            int rc = tts.speak(sentence, mode, params, id);
            if (rc == TextToSpeech.SUCCESS) {
                queueStarted = true;
                enqueued++;
            } else {
                Log.i(TAG, "VOICE mouth=androidTts speak() rejected sentenceChars="
                        + sentence.length());
            }
        }

        /**
         * Start the speculative whole-message MP3 fetch for a
         * completed reply (earliest servable moment). Retries
         * only 404s — the not-generated-yet signature — with
         * backoff; any other refusal is final and logged with
         * its status.
         */
        void startPrefetch(final LinkService link, final String messageId) {
            if (link == null || messageId == null || prefetchStarted) return;
            prefetchStarted = true;
            Thread thread = new Thread(new Runnable() {
                @Override public void run() {
                    for (int attempt = 1; attempt <= 4; attempt++) {
                        if (attempt > 1) {
                            try {
                                Thread.sleep(PREFETCH_RETRY_SLEEPS_MS[attempt - 2]);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        try {
                            byte[] mp3 = link.streamTts(messageId);
                            if (mp3 != null && mp3.length > 0) {
                                prefetchMp3 = mp3;
                                Log.i(TAG, "VOICE tts prefetch ok attempt=" + attempt
                                        + " bytes=" + mp3.length);
                                signalPrefetch();
                                return;
                            }
                            prefetchError = "empty body";
                        } catch (Exception e) {
                            prefetchError = e.getClass().getSimpleName() + ": "
                                    + e.getMessage();
                            boolean notReady = e.getMessage() != null
                                    && e.getMessage().contains("HTTP 404");
                            Log.i(TAG, "VOICE tts prefetch attempt=" + attempt
                                    + " failed: " + prefetchError);
                            if (!notReady) {
                                signalPrefetch();
                                return;
                            }
                        }
                    }
                    signalPrefetch();
                }
            }, "voice-tts-prefetch");
            thread.setDaemon(true);
            thread.start();
        }

        /** Has any sentence actually started speaking on TTS? */
        boolean anySpeechStarted() {
            synchronized (lock) {
                return anyStarted;
            }
        }

        /**
         * Decide the mouth and wait for all speech to complete.
         * Call once, on the turn thread, after the tracker has
         * answered and every sentence has been offered.
         */
        Result finishAndAwait(long drainTimeoutMs) {
            boolean mp3Claims = false;
            synchronized (lock) {
                finished = true;
                if (anyStarted) {
                    // The streamed mouth owns the turn; an MP3
                    // arriving now would double-speak.
                    if (prefetchMp3 != null) {
                        Log.i(TAG, "VOICE tts prefetch discarded: sentence "
                                + "streaming already underway");
                    }
                } else if (prefetchStarted) {
                    mp3Claims = awaitPrefetchLocked();
                }
                if (!anyStarted && !mp3Claims && enqueued == 0 && ttsUsable
                        && !allSentences.isEmpty()) {
                    // Nothing was ever queued (e.g. TTS was marked
                    // unusable at offer time is false here — this is
                    // the defensive path): queue everything now.
                    for (int i = 0; i < allSentences.size(); i++) {
                        enqueueLocked(allSentences.get(i), 0);
                    }
                }
            }
            if (mp3Claims) {
                synchronized (lock) {
                    ttsAbandoned = true;
                    if (ttsUsable) {
                        try { tts.stop(); } catch (Exception ignored) { }
                    }
                }
                if (playMp3(appContext, prefetchMp3)) {
                    result = Result.STREAM_TTS;
                    return result;
                }
                Log.i(TAG, "VOICE mouth=streamTts playback failed; re-queueing "
                        + "sentences on androidTts");
                synchronized (lock) {
                    ttsAbandoned = false;
                    enqueued = 0;
                    completedCount = 0;
                    queueStarted = false;
                    if (ttsUsable) {
                        for (String s : allSentences) enqueueLocked(s, 0);
                    }
                }
            }
            if (ttsUsable && enqueued > 0) {
                awaitDrain(drainTimeoutMs);
                synchronized (lock) {
                    result = anyStarted ? Result.ANDROID_TTS : Result.NONE;
                }
                if (result == Result.ANDROID_TTS) {
                    Log.i(TAG, "VOICE mouth=androidTts speech queue drained sentences="
                            + allSentences.size());
                }
            }
            if (result == Result.NONE) {
                if (prefetchError != null) {
                    Log.i(TAG, "VOICE mouth=streamTts failed: " + prefetchError);
                }
                Log.w(TAG, "VOICE mouth=none (captions only) — no audible reply "
                        + "on this device");
            }
            return result;
        }

        private void signalPrefetch() {
            prefetchDone.countDown();
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        /** Caller holds lock. Waits briefly for an in-flight prefetch. */
        private boolean awaitPrefetchLocked() {
            long deadline = System.currentTimeMillis() + MP3_HOLD_MS;
            while (prefetchMp3 == null && prefetchDone.getCount() > 0
                    && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return prefetchMp3 != null;
        }

        private void awaitDrain(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (lock) {
                while (!ttsAbandoned && completedCount < enqueued
                        && System.currentTimeMillis() < deadline) {
                    try {
                        lock.wait(250);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (completedCount < enqueued) {
                    Log.i(TAG, "VOICE mouth=androidTts drain timed out completed="
                            + completedCount + " enqueued=" + enqueued);
                }
            }
        }
    }

    /**
     * Play one MP3 through MediaPlayer. Returns true only when
     * playback actually ran to completion (OnCompletion), and
     * logs that completion explicitly.
     */
    private static boolean playMp3(Context appContext, byte[] mp3) {
        File file = new File(appContext.getCacheDir(), "voice_reply.mp3");
        MediaPlayer player = null;
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(mp3);
            } finally {
                out.close();
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean completed = new AtomicBoolean(false);
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    completed.set(true);
                    latch.countDown();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.i(TAG, "VOICE mouth=streamTts playback error what=" + what
                            + " extra=" + extra);
                    latch.countDown();
                    return true;
                }
            });
            player.prepare();
            player.start();
            Log.i(TAG, "VOICE mouth=streamTts playback started bytes=" + mp3.length);
            latch.await(120, TimeUnit.SECONDS);
            if (completed.get()) {
                Log.i(TAG, "VOICE mouth=streamTts playback completed bytes=" + mp3.length);
                return true;
            }
            Log.i(TAG, "VOICE mouth=streamTts playback did not complete");
            return false;
        } catch (Exception e) {
            Log.i(TAG, "VOICE mp3 playback failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        } finally {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) { }
            }
        }
    }
}
