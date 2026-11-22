package com.jonathancruzate.musegadgetshield;

import android.util.Log;

/**
 * The SDK ReplyGate's discipline, ported to the chat transport
 * (v0.9.0 SDK adoption; canonical: muse-gadget-sdk's
 * linux/src/musegadget/voice_gate.py).
 *
 * The SDK gate fences PROVIDER AUDIO by response lineage: audio
 * may be spoken only when its response descends from the current
 * caller's confirmed turn; unconfirmed audio is held, stale
 * audio (from a fenced turn) is dropped, and a new turn fences
 * everything before it. On this device there is no provider
 * audio — the realtime lane needs a PCM mic uplink the Shield
 * cannot feed — so the same state machine fences reply TEXT on
 * the chat transport:
 *
 *   STALE   — no live turn (initial state, or a retired turn).
 *             Everything is dropped.
 *   WAITING — a turn has begun (our sendChat ack bound it) but
 *             no reply lineage is confirmed yet.
 *   PLAYING — a reply's lineage is confirmed for this turn: the
 *             ReplyTracker admitted it, which on this transport
 *             IS the lineage proof (strict ack parentage — the
 *             tracker tracks no other text). Its sentences may
 *             flow to the mouth.
 *
 * On top of flow admission, the gate holds the turn's
 * AUTHORIZED SPEAK ID: the completed reply whose whole-message
 * TTS asset the mouth may fetch — the chat-transport analog of
 * the SDK's authorized speak_text id. A completion for a reply
 * that never flowed through the gate does not authorize.
 *
 * The gate is deliberately a second, independent fence in front
 * of the SentencePump: the tracker binds, the gate admits, the
 * pump splits, the mouth speaks. Any layer can be reasoned about
 * (and logged) on its own.
 */
final class ReplyGate {
    private static final String TAG = GadgetService.TAG;

    enum State { STALE, WAITING, PLAYING }

    private State state = State.STALE;
    private String confirmedReplyId;
    private String authorizedSpeakId;
    private int dropped;

    /** A new caller turn begins; everything before it is fenced. */
    synchronized void beginTurn() {
        state = State.WAITING;
        confirmedReplyId = null;
        authorizedSpeakId = null;
        Log.i(TAG, "VOICE gate: turn begun (WAITING)");
    }

    /**
     * May this reply's text flow to the mouth? The first reply
     * seen in a turn confirms its lineage and opens the gate;
     * later replies of the same turn are continuations of the
     * same submission lineage (the tracker's binding) and flow
     * too. In STALE, everything drops.
     */
    synchronized boolean admitText(String replyId) {
        if (state == State.STALE || replyId == null) {
            dropped++;
            return false;
        }
        if (confirmedReplyId == null) {
            confirmedReplyId = replyId;
            state = State.PLAYING;
            Log.i(TAG, "VOICE gate: reply lineage confirmed (PLAYING)");
        }
        return true;
    }

    /**
     * A reply completed: authorize its id as the turn's speak id
     * (the only id the mouth may fetch TTS for). Refused when the
     * reply never flowed through this gate.
     */
    synchronized boolean authorizeSpeak(String replyId) {
        if (state == State.STALE || replyId == null
                || confirmedReplyId == null) {
            dropped++;
            Log.i(TAG, "VOICE gate: speak authorization refused "
                    + "(unconfirmed reply)");
            return false;
        }
        authorizedSpeakId = replyId;
        Log.i(TAG, "VOICE gate: speak authorized for completed reply");
        return true;
    }

    synchronized String authorizedSpeakId() {
        return authorizedSpeakId;
    }

    synchronized State state() {
        return state;
    }

    synchronized int droppedCount() {
        return dropped;
    }

    /** The turn is over; fence it (late text drops as STALE). */
    synchronized void retire() {
        if (state != State.STALE) {
            state = State.STALE;
            Log.i(TAG, "VOICE gate: turn retired (STALE) dropped=" + dropped);
        }
        confirmedReplyId = null;
        authorizedSpeakId = null;
    }
}
