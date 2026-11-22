package com.jonathancruzate.musegadgetshield;

import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sentence splitter for streamed replies (v0.8.1): consumes the
 * ReplyTracker's per-reply text snapshots and releases each
 * COMPLETE sentence to the mouth as it finalizes — terminal
 * punctuation (. ! ? …) followed by whitespace, or a newline
 * break — so the first sentence of a long reply is spoken while
 * the agent is still writing the rest. The trailing fragment is
 * released when its reply completes ({@link #finishReply}) or at
 * {@link #finishAll}.
 *
 * Lineage discipline (the SDK ReplyGate's rule, ported to this
 * transport in v0.9.0): the pump only ever sees BOUND replies —
 * the tracker admits no other text — and a sentence is released
 * only from a reply the gate has confirmed for this turn (see
 * VoiceController's wiring: confirmation is the tracker's bound
 * state itself, established by the strict ack binding).
 *
 * Offsets are character positions in the joined caption text
 * (the tracker's replyText(): non-blank replies joined with a
 * blank line), so the UI can scroll the caption region to the
 * sentence currently being spoken.
 *
 * Threading: callbacks arrive on the subscription thread;
 * emissions are quick and non-blocking (a TTS queue add).
 */
final class SentencePump implements ReplyTracker.ProgressListener {
    private static final String TAG = GadgetService.TAG;

    interface Sink {
        /** One complete sentence, in order, with its caption offset. */
        void onSentence(String sentence, int globalOffset);
        /** The joined caption text changed; refresh the caption view. */
        void onTextChanged();
        /** A bound reply completed; its message id is TTS-fetchable now. */
        void onReplyDone(String messageId);
    }

    private static final class ReplyProgress {
        String emitted = "";
        String lastText = "";
        int nextBase;       // global offset where this reply's text starts
        boolean finished;
    }

    private final Sink sink;
    private final Map<String, ReplyProgress> byReply = new LinkedHashMap<>();
    private int joinedLength;   // length of the joined caption text so far

    SentencePump(Sink sink) {
        this.sink = sink;
    }

    @Override public synchronized void onReplyText(String messageId, String text,
            boolean done) {
        ReplyProgress progress = byReply.get(messageId);
        if (progress == null) {
            progress = new ReplyProgress();
            progress.nextBase = byReply.isEmpty() ? 0 : joinedLength + 2;
            byReply.put(messageId, progress);
        }
        if (text == null) text = "";
        progress.lastText = text;
        // Keep the joined-length bookkeeping aligned with the
        // tracker's replyText(): only non-blank replies occupy
        // space, joined by a blank line.
        joinedLength = progress.nextBase + text.trim().length()
                + (text.trim().isEmpty() ? -2 : 0);
        if (progress.finished) return;
        if (!text.startsWith(progress.emitted)) {
            // The completed text replaced the streamed deltas
            // (delta.message_done carries the final text): resync
            // to the longest common prefix; what was already
            // spoken cannot be unspoken, only the tail adjusts.
            int common = 0;
            int max = Math.min(text.length(), progress.emitted.length());
            while (common < max
                    && text.charAt(common) == progress.emitted.charAt(common)) {
                common++;
            }
            Log.i(TAG, "VOICE pump resync replyChars=" + text.length()
                    + " commonPrefix=" + common);
            progress.emitted = text.substring(0, common);
        }
        emitComplete(text, progress, done);
        sink.onTextChanged();
        if (done) finishReply(messageId);
    }

    @Override public synchronized void onReplyDone(String messageId) {
        sink.onReplyDone(messageId);
    }

    /** Flush every reply's trailing fragment (turn complete). */
    synchronized void finishAll() {
        for (String id : byReply.keySet().toArray(new String[0])) {
            finishReply(id);
        }
    }

    private void finishReply(String messageId) {
        ReplyProgress progress = byReply.get(messageId);
        if (progress == null || progress.finished) return;
        progress.finished = true;
        // Flush the trailing fragment even if this reply's done
        // state never flowed through onReplyText.
        emitComplete(progress.lastText, progress, true);
    }

    private void emitComplete(String text, ReplyProgress progress, boolean done) {
        int from = progress.emitted.length();
        int i = from;
        while (i < text.length()) {
            int boundary = sentenceEnd(text, i, done);
            if (boundary < 0) break;
            String sentence = text.substring(i, boundary).trim();
            if (!sentence.isEmpty()) {
                sink.onSentence(sentence,
                        progress.nextBase + i + leadingTrim(text, i, boundary));
            }
            i = boundary;
            progress.emitted = text.substring(0, i);
        }
        if (done) {
            String tail = text.substring(i).trim();
            if (!tail.isEmpty()) {
                sink.onSentence(tail, progress.nextBase + i
                        + leadingTrim(text, i, text.length()));
            }
            progress.emitted = text;
        }
    }

    private static int leadingTrim(String text, int from, int to) {
        int n = 0;
        while (from + n < to && Character.isWhitespace(text.charAt(from + n))) n++;
        return n;
    }

    /**
     * End index (exclusive) of the sentence starting at {@code from},
     * or -1 when no complete sentence ends within the text yet.
     * A sentence completes at terminal punctuation followed by
     * whitespace (or, when the reply is done, by the end of text),
     * or at a newline.
     */
    private static int sentenceEnd(String text, int from, boolean done) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                int j = i + 1;
                // Absorb closing quotes/brackets into the sentence.
                while (j < text.length() && "\"')]}”’".indexOf(text.charAt(j)) >= 0) j++;
                if (j >= text.length()) {
                    return done ? j : -1;
                }
                if (Character.isWhitespace(text.charAt(j))) {
                    return j + 1;
                }
            }
        }
        return -1;
    }
}
