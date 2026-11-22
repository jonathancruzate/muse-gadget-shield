package com.jonathancruzate.musegadgetshield;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The shared Muse voice loop (v0.7.0, extracted from the v0.6.0
 * VoiceActivity so both hosts drive the identical behavior):
 * VoiceActivity (the explicit full-screen voice mode) and
 * MuseVoiceSession (the system voice-interaction session shown
 * when the remote's mic button invokes this app in its
 * default-assistant role).
 *
 * Ears: the system SpeechRecognizer service — Settings.Secure's
 * voice_recognition_service, which on this Shield is Katniss's
 * recognition service and must stay that way (proven live
 * 2026-11-08 23:43: a final transcript at 0.97 confidence from
 * this app). Sessions reuse SpeechProbe's exact parameters and
 * one-shot lifecycle: create, listen once, destroy. Listening
 * starts automatically on ONE mic-button press — nothing is
 * held (Jonathan's correction, 2026-11-09). Since v0.8.1 the
 * recognizer's endpointing is trimmed (1.2 s / 0.8 s silence
 * params) and a listen phase runs on an explicit phase clock:
 * Katniss ends its own session after ~5 s of silence
 * (ERROR_NO_MATCH / empty results), so sessions chain invisibly
 * inside the phase. v0.9.1 (Jonathan's spec, 2026-11-09 22:24):
 * the phase budget is 10 s without a recognized result — then
 * the phase idles — and exactly ONE beep is audible per phase
 * (the first session's start beep). (How that is enforced
 * changed in v0.9.6 — see the v0.9.6 paragraph below; the
 * stream-mute mechanisms described in this paragraph and the
 * v0.9.2 one are superseded history.) The
 * v0.8.1 design (45 s window, mute restored at ready-for-speech)
 * failed in the living room: Katniss plays its ready beep AFTER
 * the restore, so every restart beeped anyway, and a sticky
 * per-phase "heard speech" flag misclassified later no-matches
 * as failures and idled phases mid-window (22:20:27). Both are
 * fixed below; see the README's v0.9.1 section.
 *
 * v0.9.2 (Jonathan's bug report, 2026-11-09 22:31; model
 * corrected twice the same evening, final spec 22:37): a mic
 * re-press mid-phase beeped repeatedly and looked stuck. Two
 * causes in the 22:30:21–22:30:41 logcat: (1) the VIS re-press
 * arrived as onHide→onShow, so pause() tore down the running
 * recognizer and start() opened a NEW phase with a NEW audible
 * beep; (2) the beep mute cycled per restart session, so any
 * beep Katniss emitted at a session edge escaped, several
 * times per phase. The fix models Muse voice mode: the voice
 * CONVERSATION is persistent — it stays open until the user
 * exits (Back) — and the mic button is a mic TOGGLE inside it.
 * A press while listening mutes the mic (CAPTURE mute: the
 * recognizer stops at once; the conversation, any in-flight
 * turn, and any playing reply continue; the UI shows MUTED).
 * A press while muted unmutes (listening resumes, silently).
 * A press from conversation-idle starts a new listen BURST
 * (silently since v0.11.1 — no session ever beeps). The 10 s no-speech window still
 * ends a burst, but expiry returns to conversation-idle — it
 * never closes the conversation. During THINKING/SPEAKING the
 * mic is already off, so a press is a no-op. Capture mute
 * (the mic) is not a speaker/beep mechanism at all — the two
 * were named distinctly throughout the stream-mute era, and
 * v0.9.6 retired the speaker-side half entirely (below).
 * MuseVoiceSession defers its onHide pause briefly so the
 * re-press hide→show pair reaches the existing controller as
 * a toggle instead of a teardown-and-restart.
 *
 * v0.9.6 (2026-11-13 — the restart-beep root cause, found in
 * the recognition service's own code): the beeps are
 * Katniss's per-session "speak now" earcons — its APK logs
 * APP_EVENT_SPEAK_NOW_BEEP_START/END around every session
 * start — and Katniss exposes a caller control for exactly
 * them: the recognizer-intent extra
 * com.google.recognition.extra.MUTE_AUDIO_BEEPS (its
 * start-listening params carry a muteAudioBeeps field, in the
 * same com.google.recognition.extra.* family as its other
 * caller extras). This app never set it. Instead, three
 * builds (0.8.1/0.9.1/0.9.2) tried to MASK the beeps from
 * outside by zeroing speaker streams {MUSIC, NOTIFICATION,
 * ASSISTANCE} — and the beeps were heard anyway, every
 * restart, while those streams sat at zero (Jonathan's
 * count, 2026-11-10 00:06: five session starts, five beeps).
 * The earcon does not ride a path that external stream
 * zeroing covers, and each build was "verified" from log
 * lines that only proved our own AudioManager calls — log
 * lines cannot certify silence; only a listener can. The
 * fix is at the source: every SILENT session start (chained
 * restarts, unmute bursts, post-turn re-listens, failure
 * retries) now carries MUTE_AUDIO_BEEPS=true, so Katniss
 * never plays the earcon; a burst's opening session does
 * not, so its single start beep still sounds. The entire
 * stream-mute machinery is deleted — no guessed streams, no
 * saved volumes, no restores, no watchdog.
 *
 * v0.11.1 (2026-11-17): the opening session's exemption is
 * repealed at Jonathan's order ("Remove the first beep too,
 * silence everything"). The REA2 dex trace
 * (hidden_files/beep-rea2-findings.md) proved the exemption
 * cost TWO earcons per burst — the start beep plus the
 * error earcon when Katniss kills a no-speech session — so
 * now EVERY session carries the extra and no earcon ever
 * plays. The one-beep cue spec (2026-11-09) is retired.
 *
 * v0.11.2 (2026-11-17, same night): the extra alone did NOT
 * silence the running build (runtime proof at the
 * EXTRA_MUTE_AUDIO_BEEPS comment below); v0.11.2 adds the
 * burst-span STREAM_ACCESSIBILITY mute, the earcon's actual
 * stream.
 *
 * The transcript goes over the link as a chat turn
 * (LinkService.sendChat with output_modality "voice"); the reply
 * TEXT is followed through the chat subscription by ReplyTracker
 * (the /chat/stream response itself is only an acknowledgement).
 * Mouth (VoiceMouth.SpeechSession): the reply streams — each
 * complete sentence is released to Android TTS as it finalizes
 * (SentencePump) while the whole-message SDK TTS MP3 is fetched
 * speculatively from the moment the reply completes; the MP3
 * (Muse's real voice) claims only a turn where no sentence has
 * started yet. Captions-only is a failed turn. Every turn logs
 * its lane (VOICE lane=chat — the SDK's realtime voice lane
 * requires a PCM microphone uplink this device cannot feed; see
 * the README's SDK-adoption section).
 *
 * Lifecycle contract for hosts:
 *   start()    — the UI is shown: subscribe, init TTS, and (relay
 *                OFF) go straight into a LISTENING session. There
 *                is deliberately no passive idle opening state:
 *                v0.6.0 sat at an idle prompt and read as dead.
 *   pause()    — the UI is hidden: stop listening and pulsing;
 *                subscription and TTS stay warm so a re-show can
 *                start again.
 *   shutdown() — the host is gone: full teardown, terminal.
 *
 * Failure budget: two consecutive genuine failures
 * (recognition errors other than no-match / speech-timeout, or
 * failed chat turns) drop the loop to the idle prompt; a voice
 * key (or a re-show) re-arms it. No-match, speech-timeout, and
 * empty results NEVER touch the budget, whatever any earlier
 * session in the phase did — the 10 s phase clock owns them.
 * While the voice-relay
 * toggle is ON the controller shows the relay state and never
 * listens — the phone is the mic then.
 *
 * VOICE log lines carry state changes and transcript/reply
 * LENGTHS only — never contents (SpeechProbe's SPEECH logs remain
 * the place transcripts themselves are visible during diagnosis).
 */
final class VoiceController {
    private static final String TAG = GadgetService.TAG;
    private static final long SESSION_TIMEOUT_MS = 25_000;
    // Agent turns are VM-paced: the 2026-11-09 live test's turn
    // took 90.4 s against the old 90 s budget and survived by a
    // sub-second margin. 150 s of patience; the failure mode of a
    // too-long budget (a hung turn) is bounded by the user simply
    // pressing the mic button again.
    private static final long REPLY_WAIT_MS = 150_000;
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    // Listen phase budget (v0.9.1, Jonathan's spec): one phase
    // gets 10 s without a recognized result before it idles.
    // Katniss ends its own sessions at ~5 s of silence, so a
    // phase is typically two chained sessions; the clock — not
    // the recognizer — enforces the budget.
    private static final long LISTEN_WINDOW_MS = 10_000;
    // Recognizer endpointing (v0.8.1): Katniss's defaults leave a
    // long post-speech tail; these trim it without cutting off
    // mid-sentence pauses. Measured on this build (2026-11-09
    // 21:30 logs): these extras govern POST-speech endpointing
    // only — a silent session still ran ~5.1 s before
    // ERROR_NO_MATCH despite COMPLETE_SILENCE=1200, so the
    // no-speech give-up is Katniss-internal and not settable via
    // the public extras; the phase clock above owns that span.
    private static final int SILENCE_COMPLETE_MS = 1200;
    private static final int SILENCE_POSSIBLY_COMPLETE_MS = 800;
    // Hang guard for the speech queue drain at turn end.
    private static final long SPEECH_DRAIN_MS = 120_000;

    private enum State { IDLE, LISTENING, MUTED, THINKING, SPEAKING, RELAY }

    private final Context appContext;
    private final VoiceUi ui;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService turnExecutor =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "voice-turn");
                    t.setDaemon(true);
                    return t;
                }
            });

    private ObjectAnimator pulseBadge;

    /** The live controller, if any (hosts check it). */
    static volatile VoiceController liveInstance;

    private volatile boolean closed;
    private volatile boolean active;
    private boolean relayOn;
    private State state = State.IDLE;
    private int consecutiveFailures;
    // Capture mute (v0.9.2): the mic toggle inside the
    // persistent conversation. True only in State.MUTED — the
    // conversation is open, no recognizer is running, and the
    // next press unmutes.
    private boolean micMuted;

    // Recognition session (main thread only). Each session gets
    // a fresh listener stamped with a session epoch; endSession
    // bumps the epoch, so a late callback from a destroyed
    // recognizer (cancel/destroy can still deliver one) can
    // never be misattributed to the NEXT session — the stale-
    // callback half of the interleaving defects.
    private SpeechRecognizer recognizer;
    private boolean sessionLive;
    private boolean sessionFinished;
    private int sessionEpoch;
    private final Runnable sessionWatchdog = new Runnable() {
        @Override public void run() {
            if (sessionLive && !sessionFinished) {
                Log.i(TAG, "VOICE session watchdog: no terminal callback in 25 s");
                sessionFinished = true;
                endSession();
                // A hung session produced no result: the phase
                // clock owns it, exactly like a no-match.
                handleNoMatch();
            }
        }
    };

    // Listen bursts + beep discipline (main thread only). A
    // burst runs from an activation (conversation open, a press
    // from conversation-idle, an unmute, a post-turn re-listen)
    // until a result, a turn, the mic being muted, or the 10 s
    // clock expiring back to conversation-idle. No session
    // beeps, ever (v0.11.1): every session start carries the
    // MUTE_AUDIO_BEEPS recognizer extra, so the recognition
    // service never plays its earcon for it (v0.9.6 —
    // suppression at the source; the v0.8.1–0.9.2
    // speaker-stream masking that never worked is deleted;
    // v0.9.6–v0.11.0 exempted a burst's opening session for a
    // single start beep — repealed 2026-11-17 at Jonathan's
    // order, silence everything).
    private long listenPhaseStartMs;

    // Beep stream (v0.11.2). The recognition service's earcons
    // play on an AudioTrack with usage
    // USAGE_ASSISTANCE_ACCESSIBILITY (REA, beep-rea2), which
    // the audio policy serves from STREAM_ACCESSIBILITY — the
    // one stream the v0.8.1–0.9.2 masking ({MUSIC,
    // NOTIFICATION, ASSISTANT}) never muted, which is why
    // those builds changed nothing in the living room. The
    // MUTE_AUDIO_BEEPS intent extra (v0.9.6/v0.11.1) is set on
    // every session but is empirically NOT honored on this
    // path: Katniss's own AudioPlayer logs show the open and
    // failure earcons playing for every session of a burst
    // (live capture, 2026-11-17 00:58) and Jonathan's
    // recording of the 00:51 burst carries the failure beeps.
    // So the burst span carries the real mute: engaged before
    // a burst's first session starts, held across chained
    // restarts (the failure beep plays in the gap between
    // sessions), restored ~700 ms after the burst ends so the
    // final session's end earcon is covered too.
    private AudioManager audioManager;
    private int savedBeepVolume = -1;
    private final Runnable beepRestoreRunnable = new Runnable() {
        @Override public void run() { restoreBeepStream(); }
    };
    private final Runnable beepWatchdogRunnable = new Runnable() {
        @Override public void run() { restoreBeepStream(); }
    };

    // Chat turn plumbing.
    private volatile ReplyTracker currentTracker;
    private final java.util.concurrent.atomic.AtomicInteger unboundDrops =
            new java.util.concurrent.atomic.AtomicInteger();
    private Thread subscribeThread;

    // Fallback mouth (the primary mouth is VoiceMouth/streamTts).
    private TextToSpeech tts;
    private volatile boolean ttsReady;

    VoiceController(Context hostContext) {
        this.appContext = hostContext.getApplicationContext() != null
                ? hostContext.getApplicationContext() : hostContext;
        this.ui = VoiceUi.build(hostContext);
        liveInstance = this;
    }

    View getView() {
        return ui.root;
    }

    // ------------------------------------------------------------ lifecycle

    /** Whether the UI is shown and the loop is live. */
    boolean isActive() {
        return active && !closed;
    }

    /** The host UI is shown: begin (or resume) the loop. */
    synchronized void start() {
        if (closed) return;
        if (active) {
            // A re-show of the open conversation is a mic press
            // (v0.9.2): route it to the mic toggle — mute while
            // listening, unmute while muted, no-op during a
            // turn. The conversation itself is never restarted.
            if (handleRepress()) return;
            if (state == State.RELAY) return;
            // IDLE with the plate up: fall through and re-arm.
        }
        active = true;
        micMuted = false;
        ui.avatarView.setRunning(true);
        relayOn = VoiceRelay.isOn(appContext);
        if (tts == null) initTts();
        if (relayOn) {
            setState(State.RELAY,
                    "Voice is relayed to your phone — this TV is not a microphone.");
            Log.i(TAG, "VOICE open: relay on, voice loop disabled");
            return;
        }
        startSubscription();
        consecutiveFailures = 0;
        // Unmissable LISTENING state from the first frame: the
        // mic glyph is up and a session opens immediately — no
        // idle prompt first (Jonathan's v0.6.0 live-test
        // complaint; the glyph replaced the badge in v0.9.4).
        startListeningPhase();
    }

    /** The host UI is hidden: stop listening; stay warm. */
    synchronized void pause() {
        active = false;
        micMuted = false;
        endSession();
        restoreBeepStream();
        stopPulse();
        ui.avatarView.setRunning(false);
        Log.i(TAG, "VOICE paused");
    }

    /** Terminal teardown (host destroyed). Safe to call twice. */
    synchronized void shutdown() {
        if (closed) return;
        closed = true;
        active = false;
        endSession();
        restoreBeepStream();
        stopPulse();
        ui.avatarView.setRunning(false);
        if (liveInstance == this) liveInstance = null;
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
            try { tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        turnExecutor.shutdownNow();
        Log.i(TAG, "VOICE closed");
    }

    /** A voice key (or re-summon) reached the host. */
    void handleVoiceKey() {
        if (closed || relayOn) return;
        if (!active) {
            start();
            return;
        }
        if (state == State.IDLE) {
            // Press from conversation-idle: a new listen burst
            // (silent since v0.11.1).
            consecutiveFailures = 0;
            startListeningPhase();
            return;
        }
        // LISTENING / MUTED / THINKING / SPEAKING: the toggle.
        handleRepress();
    }

    /**
     * A mic press inside the open conversation (v0.9.2 —
     * Jonathan's re-press bug; final model 2026-11-09 22:37,
     * mirroring Muse voice mode): the press is the mic TOGGLE.
     * While LISTENING it capture-mutes; while MUTED it unmutes;
     * while THINKING or SPEAKING the mic is already off, so it
     * is absorbed as a no-op and any in-flight turn or playing
     * reply continues untouched. Nothing here ever ends, idles
     * out, or tears down the conversation. Returns true when
     * the press was handled as a toggle (a press from
     * conversation-idle is not: it starts a beep burst — see
     * handleVoiceKey / start).
     */
    private boolean handleRepress() {
        if (state == State.LISTENING) {
            muteMic();
            return true;
        }
        if (state == State.MUTED) {
            unmuteMic();
            return true;
        }
        if (state == State.THINKING || state == State.SPEAKING) {
            Log.i(TAG, "VOICE re-press absorbed (phase active)");
            return true;
        }
        return false;
    }

    /**
     * Capture mute: the recognizer session is cancelled on the
     * spot — mic off, no more capture — and the CONVERSATION
     * CONTINUES: the UI settles to a clear MUTED state, and
     * nothing about the conversation ends.
     */
    private void muteMic() {
        endSession();
        scheduleBeepStreamRestore();
        micMuted = true;
        // v0.9.3 (Jonathan's order): NO "Muted" text and no
        // badge — the mic-off glyph in the avatar view carries
        // the state ("instead of the rectangle and text"). The
        // state line is set empty on purpose; its layout slot
        // stays, so nothing below it moves.
        setState(State.MUTED, "");
        Log.i(TAG, "VOICE mic muted (conversation continues)");
    }

    /**
     * Unmute: listening resumes inside the same conversation
     * with a fresh recognizer session — SILENTLY (no beep: the
     * session carries the MUTE_AUDIO_BEEPS extra, like every
     * recognizer start since v0.11.1).
     * The resumed listening is a fresh burst clock.
     */
    private void unmuteMic() {
        micMuted = false;
        Log.i(TAG, "VOICE mic unmuted");
        startListeningPhaseSilent();
    }

    // ------------------------------------------------------------------ UI

    private void setState(State next, String text) {
        state = next;
        ui.stateView.setText(text);
        Log.i(TAG, "VOICE state=" + next);
        // The SDK avatar mirrors the loop state (its own modes:
        // listening/thinking/speaking/idle), and its accent tints
        // the badge, the way the SDK's UI tints around the avatar.
        ui.avatarView.setMode(avatarModeFor(next));
        ui.listeningBadge.setBackgroundColor(ui.avatarView.accentColor());
        // v0.9.4 (Jonathan's order): the badge rectangle is
        // retired in LISTENING too — the plain mic glyph in
        // the avatar view (same stamp as MUTED's, without the
        // slash) carries the state, mirroring v0.9.3's MUTED
        // treatment. The badge view stays in the layout
        // (INVISIBLE in every state now) so nothing below it
        // moves, and with no visible badge there is no pulse.
        ui.listeningBadge.setVisibility(View.INVISIBLE);
        stopPulse();
    }

    private static SdkAvatarView.Mode avatarModeFor(State state) {
        switch (state) {
            case LISTENING: return SdkAvatarView.Mode.LISTENING;
            case THINKING: return SdkAvatarView.Mode.THINKING;
            case SPEAKING: return SdkAvatarView.Mode.SPEAKING;
            case MUTED: return SdkAvatarView.Mode.MUTED;
            default: return SdkAvatarView.Mode.IDLE;
        }
    }

    private void startPulse() {
        if (pulseBadge != null) return;
        // The badge pulses; the avatar has its own motion now
        // (breathing/bobbing in the SDK renderer) and is no longer
        // scale-pulsed as a whole.
        pulseBadge = ObjectAnimator.ofFloat(ui.listeningBadge, View.ALPHA, 1.0f, 0.55f);
        pulseBadge.setDuration(850);
        pulseBadge.setRepeatCount(ValueAnimator.INFINITE);
        pulseBadge.setRepeatMode(ValueAnimator.REVERSE);
        pulseBadge.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseBadge.start();
    }

    private void stopPulse() {
        if (pulseBadge != null) { pulseBadge.cancel(); pulseBadge = null; }
        if (ui.listeningBadge != null) ui.listeningBadge.setAlpha(1.0f);
    }

    // ------------------------------------------------------- subscription

    /**
     * One subscription for the controller's lifetime, re-opened on
     * the current link session whenever the stream ends (the
     * supervisor reconnects underneath us). Events feed the armed
     * turn tracker only — a tracker becomes the target strictly
     * AFTER its own sendChat ack has bound it (see runChatTurn);
     * with none armed, events are dropped and counted, never
     * tracked (the phantom-turn fix).
     */
    private void startSubscription() {
        if (subscribeThread != null && subscribeThread.isAlive()) return;
        subscribeThread = new Thread(new Runnable() {
            @Override public void run() {
                while (!closed) {
                    LinkService link = GadgetService.activeLinkService;
                    if (link == null) {
                        sleepQuietly(1000);
                        continue;
                    }
                    try {
                        link.subscribeChatStreaming(
                                new java.util.function.Consumer<Map<String, Object>>() {
                                    @Override public void accept(Map<String, Object> event) {
                                        ReplyTracker tracker = currentTracker;
                                        if (tracker != null) {
                                            tracker.feed(event);
                                        } else {
                                            // No gadget turn is
                                            // outstanding: the event
                                            // belongs to some other
                                            // conversation flow (e.g.
                                            // a phone voice session in
                                            // the same main chat) and
                                            // is dropped, never
                                            // tracked. Counted, with a
                                            // throttled log line.
                                            int n = unboundDrops.incrementAndGet();
                                            if (n == 1 || n % 50 == 0) {
                                                Log.i(TAG, "VOICE subscribe: ignoring "
                                                        + "unbound stream events (no gadget "
                                                        + "turn outstanding) count=" + n);
                                            }
                                        }
                                    }
                                },
                                null,
                                new java.util.function.BooleanSupplier() {
                                    @Override public boolean getAsBoolean() {
                                        return closed;
                                    }
                                });
                    } catch (Exception e) {
                        Log.i(TAG, "VOICE subscription ended: "
                                + e.getClass().getSimpleName() + "; reopening");
                    }
                    sleepQuietly(2000);
                }
            }
        }, "voice-subscribe");
        subscribeThread.setDaemon(true);
        subscribeThread.start();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------- recognition

    /**
     * Begin a listen burst from conversation-open (start) or
     * a press from conversation-idle, and start the 10 s burst
     * clock. Since v0.11.1 this start is silent like every
     * other (MUTE_AUDIO_BEEPS extra set): the opening-beep
     * exemption was repealed at Jonathan's order (silence
     * everything), so no session ever beeps.
     */
    private void startListeningPhase() {
        listenPhaseStartMs = System.currentTimeMillis();
        // false = not a restart (log wording only); the
        // MUTE_AUDIO_BEEPS extra is set unconditionally inside.
        startListeningInternal(false);
    }

    /**
     * Begin a listen burst SILENTLY: an unmute, a post-turn
     * re-listen, a failure retry. Its first session is a
     * silent start (MUTE_AUDIO_BEEPS extra set), exactly like
     * a chained restart — as is every burst start since
     * v0.11.1.
     */
    private void startListeningPhaseSilent() {
        listenPhaseStartMs = System.currentTimeMillis();
        startListeningInternal(true);
    }

    /**
     * Chain another session INSIDE the current phase after a
     * no-match: silent — the session carries the
     * MUTE_AUDIO_BEEPS extra, so the recognition service plays
     * no earcon for it and the couch hears nothing.
     */
    private void restartListeningSilent() {
        startListeningInternal(true);
    }

    private void startListeningInternal(boolean silentRestart) {
        if (closed || !active || relayOn || sessionLive) return;
        setState(State.LISTENING, "Listening");
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "VOICE recognition unavailable on this device");
            onListenFailure();
            return;
        }
        sessionFinished = false;
        engageBeepStream();
        try {
            final int epoch = ++sessionEpoch;
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
            recognizer.setRecognitionListener(listenerFor(epoch));
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    appContext.getPackageName());
            // v0.9.6: the recognizer extra meant to suppress
            // the recognition service's earcons at the source
            // (see EXTRA_MUTE_AUDIO_BEEPS below). v0.11.1: set
            // on EVERY session (Jonathan's order 2026-11-17,
            // silence everything). v0.11.2: runtime proof says
            // the running service ignores it on this path — it
            // stays set, but the burst-span stream mute above
            // is what actually silences the earcons.
            intent.putExtra(EXTRA_MUTE_AUDIO_BEEPS, true);
            // Endpointing trim (v0.8.1): shorter post-speech
            // silence tails than Katniss's defaults.
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SILENCE_COMPLETE_MS);
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SILENCE_POSSIBLY_COMPLETE_MS);
            recognizer.startListening(intent);
            sessionLive = true;
            handler.removeCallbacks(sessionWatchdog);
            handler.postDelayed(sessionWatchdog, SESSION_TIMEOUT_MS);
            Log.i(TAG, "VOICE listening (session started"
                    + (silentRestart ? ", silent restart" : "") + ")");
        } catch (Exception e) {
            Log.w(TAG, "VOICE startListening failed: " + e.getClass().getSimpleName());
            endSession();
            onListenFailure();
        }
    }

    /** Engage the burst-span earcon mute (see the field note
     * at savedBeepVolume). No-op while already engaged. */
    private void engageBeepStream() {
        handler.removeCallbacks(beepRestoreRunnable);
        if (savedBeepVolume >= 0) return;
        try {
            if (audioManager == null) {
                audioManager = (AudioManager) appContext
                        .getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return;
            savedBeepVolume = audioManager.getStreamVolume(
                    AudioManager.STREAM_ACCESSIBILITY);
            audioManager.setStreamVolume(
                    AudioManager.STREAM_ACCESSIBILITY, 0, 0);
            Log.i(TAG, "VOICE beep stream muted (accessibility, prev="
                    + savedBeepVolume + ")");
            handler.removeCallbacks(beepWatchdogRunnable);
            handler.postDelayed(beepWatchdogRunnable, 25000);
        } catch (Exception e) {
            savedBeepVolume = -1;
            Log.w(TAG, "VOICE beep stream mute failed: "
                    + e.getClass().getSimpleName());
        }
    }

    /** Restore the earcon stream volume, if muted. */
    private void restoreBeepStream() {
        handler.removeCallbacks(beepWatchdogRunnable);
        if (savedBeepVolume < 0) return;
        int prev = savedBeepVolume;
        savedBeepVolume = -1;
        try {
            if (audioManager != null) {
                audioManager.setStreamVolume(
                        AudioManager.STREAM_ACCESSIBILITY, prev, 0);
            }
            Log.i(TAG, "VOICE beep stream restored (" + prev + ")");
        } catch (Exception e) {
            Log.w(TAG, "VOICE beep stream restore failed: "
                    + e.getClass().getSimpleName());
        }
    }

    /** Restore shortly after a burst ends: the final session's
     * end earcon plays just after its error callback, so the
     * restore waits ~700 ms to cover it. A new burst engaging
     * in the meantime cancels the pending restore. */
    private void scheduleBeepStreamRestore() {
        if (savedBeepVolume < 0) return;
        handler.removeCallbacks(beepRestoreRunnable);
        handler.postDelayed(beepRestoreRunnable, 700);
    }

    /** Terminal session teardown; safe to call more than once. */
    private void endSession() {
        handler.removeCallbacks(sessionWatchdog);
        sessionLive = false;
        // Invalidate the dying session's listener: any callback
        // it still delivers lands on a stale epoch and is dropped.
        sessionEpoch++;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    // The recognizer's start beep, controlled at the source
    // (v0.9.6). The beep is the recognition service's own
    // per-session "speak now" earcon (Katniss logs
    // APP_EVENT_SPEAK_NOW_BEEP_START/END for it), and its
    // start-listening parameters include a caller-controlled
    // muteAudioBeeps flag, set through this intent extra —
    // one of the com.google.recognition.extra.* family the
    // service parses (verified in the pulled Katniss APK's
    // dex string table, 2026-11-13). Setting it on a session
    // start means the service never plays the earcon for that
    // session. This replaces the v0.8.1–0.9.2 approach —
    // zeroing speaker streams {MUSIC, NOTIFICATION,
    // ASSISTANCE} from outside — which never silenced a
    // single restart beep in the living room: the earcon is
    // not covered by that external masking, whatever the
    // volume logs claimed. All stream-muting machinery
    // (muteBeepStreams / restoreBeepVolumes / saved volumes /
    // the restore watchdog) is deleted.
    //
    // v0.11.2 CORRECTION (2026-11-17, runtime evidence): the
    // extra is NOT honored on this path — Katniss's own
    // AudioPlayer logs (live capture 2026-11-17 00:58) show
    // the open and failure earcons playing for EVERY session
    // with the extra set, and Jonathan's recording of the
    // 00:51 burst carries the failure beeps. The dex gate
    // exists (hidden_files/beep-rea2-findings.md) but the
    // running RecognitionClient's params evidently never
    // receive the flag. The extra stays set (harmless; a
    // future Katniss may honor it), but the working mute is
    // the burst-span STREAM_ACCESSIBILITY mute (see the field
    // note at savedBeepVolume): the earcon AudioTrack uses
    // usage=USAGE_ASSISTANCE_ACCESSIBILITY, the one stream
    // the deleted masking never touched.
    private static final String EXTRA_MUTE_AUDIO_BEEPS =
            "com.google.recognition.extra.MUTE_AUDIO_BEEPS";

    /**
     * A RecognitionListener stamped with one session's epoch.
     * Every callback first checks the epoch: once the session
     * ends (endSession bumps the epoch), any late callback the
     * destroyed recognizer still delivers is dropped instead of
     * being mistaken for the next session's event. Before this
     * guard, session state (a shared listener + a shared
     * "finished" flag the next session reset) was the only
     * attribution — the interleaving half of the defects.
     *
     * Routing (v0.9.1): ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT and
     * EMPTY results are how a listen session reports "nothing
     * recognized" — they ALWAYS go to the phase clock and NEVER
     * touch the failure budget. v0.8.1 gated that routing on a
     * sticky per-phase flag set by onBeginningOfSpeech: one
     * session's onset (which Katniss fires on any audio energy,
     * including a mid-stream join) poisoned every later no-match
     * in the phase into a "post-speech failure", and two of
     * those idled the loop mid-window (observed 2026-11-09
     * 22:20:27 — a restart session's error 7, 1.3 s in, counted
     * as the second consecutive failure). There is deliberately
     * no per-phase speech flag anymore.
     */
    private RecognitionListener listenerFor(final int epoch) {
        return new RecognitionListener() {
            private boolean stale() {
                return epoch != sessionEpoch;
            }

            @Override public void onReadyForSpeech(Bundle params) {
                // Nothing to do: for silent sessions the
                // earcon was suppressed at the source by the
                // MUTE_AUDIO_BEEPS extra (v0.9.6); there is no
                // external mute left to time around this
                // callback (the v0.8.1 restore-at-ready defect
                // class is gone with the machinery).
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) {
                if (stale()) return;
                // Feed the SDK avatar's live level (mic while
                // listening): SpeechProbe's floor is about -2 dB
                // and speech peaked at 10 dB on this device.
                ui.avatarView.setLevel((rmsdB + 2.0f) / 12.0f);
            }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }

            @Override public void onPartialResults(Bundle partialResults) {
                if (stale()) return;
                ArrayList<String> texts =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts != null && !texts.isEmpty() && !closed) {
                    ui.heardView.setText("You: " + texts.get(0));
                }
            }

            @Override public void onResults(Bundle results) {
                if (stale() || sessionFinished) return;
                sessionFinished = true;
                endSession();
                ArrayList<String> texts =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String transcript = texts != null && !texts.isEmpty() ? texts.get(0) : "";
                if (transcript == null || transcript.trim().isEmpty()) {
                    // Silence reaches this callback too: Katniss
                    // sometimes ends a silent session with EMPTY
                    // results instead of ERROR_NO_MATCH (observed
                    // live 2026-11-09 21:46). "Nothing recognized"
                    // is the phase clock's case, unconditionally —
                    // never a failure.
                    handleNoMatch();
                    return;
                }
                consecutiveFailures = 0;
                ui.heardView.setText("You: " + transcript);
                Log.i(TAG, "VOICE heard transcriptChars=" + transcript.length());
                runChatTurn(transcript);
            }

            @Override public void onError(int error) {
                if (stale() || sessionFinished) return;
                sessionFinished = true;
                endSession();
                Log.i(TAG, "VOICE session error code=" + error);
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // "Nothing recognized" (no match, or no
                    // speech input at all): the phase clock owns
                    // it, unconditionally — never the budget.
                    handleNoMatch();
                } else {
                    onListenFailure();
                }
            }

            @Override public void onEvent(int eventType, Bundle params) { }
        };
    }

    /**
     * Nothing recognized yet in this phase (a no-match, a speech
     * timeout, an empty result, or a hung session): chain a
     * SILENT restart until the phase's 10 s clock runs out, then
     * idle — logged as the listen-window expiry, never as a
     * failure, and the failure budget is untouched.
     */
    private void handleNoMatch() {
        if (closed || !active || relayOn) return;
        long elapsed = System.currentTimeMillis() - listenPhaseStartMs;
        if (elapsed < LISTEN_WINDOW_MS) {
            Log.i(TAG, "VOICE no-match restart (silent) phaseElapsedMs=" + elapsed);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!closed && active && state == State.LISTENING) {
                        restartListeningSilent();
                    }
                }
            }, 150);
        } else {
            setState(State.IDLE, "Press the mic button and talk to me");
            Log.i(TAG, "VOICE listen window expired after "
                    + (LISTEN_WINDOW_MS / 1000)
                    + " s without speech — idle");
            scheduleBeepStreamRestore();
        }
    }

    /**
     * A genuine listening failure (any recognition error other
     * than no-match / speech-timeout; empty results and hung
     * sessions are no-matches, above): burns the failure budget;
     * the in-phase retry is silent like every chained restart.
     */
    private void onListenFailure() {
        if (closed || !active || relayOn) return;
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            setState(State.IDLE, "Press the mic button and talk to me");
            Log.i(TAG, "VOICE loop idle after " + consecutiveFailures
                    + " consecutive failures");
            scheduleBeepStreamRestore();
        } else {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!closed && active && state == State.LISTENING) {
                        restartListeningSilent();
                    }
                }
            }, 500);
        }
    }

    /**
     * A failed chat turn: burns the same budget; its re-listen
     * is a SILENT burst (v0.9.2 beep rule: beeps belong only to
     * bursts from conversation-idle / -open — an internal retry
     * is neither).
     */
    private void onTurnFailure() {
        if (closed || !active || relayOn) return;
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            setState(State.IDLE, "Press the mic button and talk to me");
            Log.i(TAG, "VOICE loop idle after " + consecutiveFailures
                    + " consecutive failures");
            scheduleBeepStreamRestore();
        } else {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!closed && active && state != State.THINKING
                            && state != State.SPEAKING) {
                        startListeningPhaseSilent();
                    }
                }
            }, 500);
        }
    }

    // ------------------------------------------------------------ chat turn

    private void runChatTurn(final String transcript) {
        scheduleBeepStreamRestore();
        setState(State.THINKING, "Thinking…");
        turnExecutor.execute(new Runnable() {
            @Override public void run() {
                final LinkService link = GadgetService.activeLinkService;
                if (link == null) {
                    postTurnFailure("no link service");
                    return;
                }
                // Per-turn lane log (v0.9.0 SDK adoption): the
                // realtime voice lane needs a PCM mic uplink this
                // device cannot feed (README evidence), so every
                // turn runs the chat lane; the line keeps the lane
                // explicit in the log either way.
                Log.i(TAG, "VOICE lane=chat (realtime lane not feedable "
                        + "on this device — PCM uplink unavailable)");
                final long turnStartMs = System.currentTimeMillis();
                // STRICT turn binding (v0.7.0 fix for the phantom
                // turn of 2026-11-09 00:16): the tracker is armed
                // ONLY after our own sendChat returns and its ack
                // yields our message id. Until then the
                // subscription drops every event (see
                // startSubscription) — chat-stream events from any
                // other flow (e.g. a simultaneous phone voice
                // session in the same main chat) can never complete
                // or contaminate a gadget turn.
                final ReplyTracker tracker = new ReplyTracker();
                // Sentence streaming (v0.8.1): the pump releases
                // each complete sentence of the BOUND reply to the
                // speech session as it finalizes, and starts the
                // speculative whole-message MP3 fetch the moment a
                // reply completes. The tracker admits only bound
                // text, so nothing unbound can ever be spoken —
                // the ReplyGate discipline on this transport.
                final VoiceMouth.SpeechSession speech =
                        new VoiceMouth.SpeechSession(appContext, tts, ttsReady,
                                new VoiceMouth.SpeechSession.Listener() {
                                    @Override public void onSentenceStart(
                                            final int globalOffset) {
                                        handler.post(new Runnable() {
                                            @Override public void run() {
                                                if (!closed) {
                                                    ui.scrollReplyTo(globalOffset);
                                                }
                                            }
                                        });
                                    }
                                });
                final boolean[] spoke = {false};
                SentencePump pump = new SentencePump(new SentencePump.Sink() {
                    @Override public void onSentence(String sentence,
                            int globalOffset) {
                        if (!spoke[0]) {
                            spoke[0] = true;
                            Log.i(TAG, "VOICE first sentence ready after "
                                    + (System.currentTimeMillis() - turnStartMs)
                                    + " ms chars=" + sentence.length());
                            handler.post(new Runnable() {
                                @Override public void run() {
                                    if (!closed && active) {
                                        setState(State.SPEAKING, "Speaking…");
                                    }
                                }
                            });
                        }
                        speech.offerSentence(sentence, globalOffset);
                    }
                    @Override public void onTextChanged() {
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (!closed) {
                                    ui.replyView.setText(tracker.replyText());
                                }
                            }
                        });
                    }
                    @Override public void onReplyDone(String messageId) {
                        // Earliest servable moment for the TTS
                        // asset of this reply.
                        speech.startPrefetch(link, messageId);
                    }
                });
                // The ReplyGate fences the pump (v0.9.0): text
                // flows only inside a begun turn, and only the
                // gate-authorized completed reply may start the
                // TTS prefetch. The tracker binds; the gate
                // admits; the pump splits.
                final ReplyGate gate = new ReplyGate();
                tracker.setProgressListener(new ReplyTracker.ProgressListener() {
                    @Override public void onReplyText(String messageId,
                            String text, boolean done) {
                        if (gate.admitText(messageId)) {
                            pump.onReplyText(messageId, text, done);
                        }
                    }
                    @Override public void onReplyDone(String messageId) {
                        if (gate.authorizeSpeak(messageId)) {
                            pump.onReplyDone(messageId);
                        }
                    }
                });
                final String replyText;
                final String replyMessageId;
                final int ignoredUnbound;
                try {
                    gate.beginTurn();
                    List<Map<String, Object>> ack = link.sendChat(transcript, null, "voice");
                    if (!tracker.acknowledge(ack)) {
                        postTurnFailure("chat acknowledgement carried no message id");
                        return;
                    }
                    currentTracker = tracker;
                    long deadline = System.currentTimeMillis() + REPLY_WAIT_MS;
                    while (!closed && System.currentTimeMillis() < deadline) {
                        if (tracker.isAnswered()) break;
                        Thread.sleep(200);
                    }
                    // Flush trailing fragments before the mouth
                    // decision so a one-sentence reply is complete.
                    pump.finishAll();
                    replyText = tracker.replyText();
                    replyMessageId = tracker.lastReplyMessageId();
                    ignoredUnbound = tracker.ignoredUnboundCount();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    postTurnFailure("chat turn failed: " + e.getClass().getSimpleName());
                    return;
                } finally {
                    currentTracker = null;
                }
                Log.i(TAG, "VOICE reply replyChars=" + replyText.length()
                        + " messageId=" + (replyMessageId != null)
                        + " ignoredUnbound=" + ignoredUnbound);
                if (replyText.isEmpty()) {
                    // No bound reply text within the wait: a failed
                    // turn, never a phantom SPEAKING cycle.
                    postTurnFailure("no bound reply within "
                            + (REPLY_WAIT_MS / 1000) + " s");
                    return;
                }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (closed || !active) return;
                        ui.replyView.setText(replyText);
                        if (state == State.THINKING) {
                            setState(State.SPEAKING, "Speaking…");
                        }
                    }
                });
                // Re-listen happens only after BOTH the turn is
                // answered (above) AND the speech queue has
                // drained (here) — the loop's standing semantics.
                // The turn still counts only when the reply is
                // AUDIBLY played on this TV (Jonathan's acceptance
                // criterion): captions alone are a failed turn.
                VoiceMouth.Result mouth = VoiceMouth.Result.NONE;
                if (active) {
                    mouth = speech.finishAndAwait(SPEECH_DRAIN_MS);
                }
                Log.i(TAG, "VOICE mouth=" + mouth + " turnMs="
                        + (System.currentTimeMillis() - turnStartMs));
                gate.retire();
                if (mouth == VoiceMouth.Result.NONE) {
                    postTurnFailure("reply was not audibly played on the TV");
                    return;
                }
                handler.post(new Runnable() {
                    @Override public void run() {
                        // Post-turn re-listen: a SILENT burst —
                        // the conversation simply continues
                        // (v0.9.2 beep rule), and a capture-muted
                        // conversation never auto-listens.
                        if (!closed && active && !micMuted) {
                            startListeningPhaseSilent();
                        }
                    }
                });
            }
        });
    }

    private void postTurnFailure(String why) {
        Log.w(TAG, "VOICE turn failure: " + why);
        handler.post(new Runnable() {
            @Override public void run() {
                if (!closed && active) onTurnFailure();
            }
        });
    }

    // ---------------------------------------------------------------- mouth

    private void initTts() {
        try {
            tts = new TextToSpeech(appContext, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = tts.setLanguage(Locale.US);
                        ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                                && result != TextToSpeech.LANG_NOT_SUPPORTED;
                        Log.i(TAG, "VOICE androidTts ready=" + ttsReady);
                    } else {
                        Log.i(TAG, "VOICE androidTts init failed status=" + status);
                    }
                }
            });
            // No utterance listener here: each turn's
            // VoiceMouth.SpeechSession installs its own so
            // completions are attributed to that turn's queue.
        } catch (Exception e) {
            Log.i(TAG, "VOICE androidTts unavailable: " + e.getClass().getSimpleName());
            tts = null;
        }
    }

    // The mouth itself lives in VoiceMouth: a per-turn
    // SpeechSession — sentence-streamed Android TTS racing the
    // whole-message streamTts MP3 — and a reply that never
    // audibly plays on this TV is a failed turn.
}
