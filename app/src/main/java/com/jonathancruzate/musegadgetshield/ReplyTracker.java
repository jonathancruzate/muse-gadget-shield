package com.jonathancruzate.musegadgetshield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows one chat request to its reply text (v0.6.0) — a compact
 * port of the SDK's reply machinery (linux service.py's
 * send_and_wait + _ReplyReader, and muse_turn.py's MuseTurn) to
 * this app's map-based JSON.
 *
 * Why this exists: POST /chat/stream's response is only an
 * acknowledgement (the user message's id); the assistant's reply
 * TEXT arrives asynchronously as events on POST /chat/subscribe.
 * The voice loop therefore: (1) opens the subscription, (2) sends
 * the transcript with LinkService.sendChat, (3) hands the returned
 * acknowledgement to {@link #acknowledge}, (4) feeds every
 * subscription event to {@link #feed}, and (5) waits for
 * {@link #isAnswered}. Events arriving before the acknowledgement
 * lands are held briefly and replayed through the same binding
 * check once it does.
 *
 * Thread safety: {@link #feed} runs on the link reader thread via
 * the subscription; the voice loop polls from its worker thread.
 * All state is guarded by this instance's monitor.
 *
 * BINDING (v0.7.0, after the phantom turn of 2026-11-09 00:16;
 * rules cross-checked against the SDK's real MuseTurn in
 * linux/src/musegadget/muse_turn.py): the tracker is armed only
 * after the gadget's own sendChat ack yields its message id (the
 * controller makes it the subscription target only then, and
 * drops every stream event while no gadget turn is outstanding).
 * On this wire, Muse's reply messages are typically PARENTLESS,
 * so — exactly as in MuseTurn — a message event is rejected
 * only when it carries an explicit FOREIGN parent; a parentless
 * message event binds when it arrives inside the answer window
 * (after our own message's message.user event, before any other
 * user message, which closes the window). What the phantom turn
 * proved unsafe, and what changes vs both v0.6.0 and the SDK's
 * shared-chat compatibility behaviour: parentless STATUS events
 * no longer complete anything by themselves — they count only
 * inside the window, and {@link #isAnswered} additionally
 * requires at least one bound reply, so a turn can never
 * complete on an empty or unbound event set (v0.6.0 completed
 * with replyChars=0 off a simultaneous phone session's terminal
 * task status). Dropped unbound events are counted
 * ({@link #ignoredUnboundCount}).
 */
final class ReplyTracker {
    /** Silence after which a turn with text counts as answered (SDK: 3 s). */
    private static final long REPLY_QUIET_MS = 3_000;
    /** Grace after a terminal task with still-blank replies (SDK: 1 s). */
    private static final long EMPTY_REPLY_GRACE_MS = 1_000;
    private static final int MAX_HELD_EVENTS = 64;
    private static final int MAX_REPLIES = 32;

    private static final Set<String> TERMINAL_TASK_STATUSES = new HashSet<>(
            Arrays.asList("completed", "failed", "errored", "cancelled", "canceled"));
    private static final Set<String> MESSAGE_EVENTS = new HashSet<>(
            Arrays.asList("delta.message_start", "delta.text_append",
                    "delta.message_done", "message.assistant"));
    private static final Set<String> STATUS_EVENTS = new HashSet<>(
            Arrays.asList("agent.status", "task.status"));

    private static final class ReplyState {
        final String messageId;
        String text = "";
        boolean done;
        boolean bound;
        ReplyState(String messageId) { this.messageId = messageId; }
    }

    /**
     * Streaming progress (v0.8.1): fired as bound reply text grows
     * and when a bound reply completes, so the voice loop can
     * release complete sentences to the mouth DURING the turn
     * instead of waiting for the whole reply, and can start the
     * streamTts fetch the moment a reply's message is done (the
     * earliest the TTS endpoint can serve it). Callbacks run on
     * the subscription thread while this tracker's monitor is
     * held: implementations must be quick and non-blocking, and
     * must not call back into the tracker.
     */
    interface ProgressListener {
        void onReplyText(String messageId, String text, boolean done);
        void onReplyDone(String messageId);
    }

    private volatile ProgressListener progressListener;

    void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    private final List<Map<String, Object>> held = new ArrayList<>();
    private final Set<String> userIds = new HashSet<>();
    private final Map<String, ReplyState> replies = new LinkedHashMap<>();
    private String sessionId;
    private boolean acknowledged;
    private int ignoredUnbound;
    private boolean windowOpen;
    private boolean busy;
    private boolean taskFinished;
    private long lastActivityMs = System.currentTimeMillis();

    /**
     * Bind the tracker to the user message in sendChat's
     * acknowledgement (the decoded /chat/stream response messages:
     * the ack object itself, or its "result"/"response" member,
     * carries message_id / reply_to_message_id / session_id).
     * Returns false when no user message id was found — the caller
     * should treat the turn as unanswerable rather than guess.
     */
    synchronized boolean acknowledge(List<Map<String, Object>> ackMessages) {
        if (ackMessages != null) {
            for (Map<String, Object> message : ackMessages) {
                collectAck(message);
                Object result = message.get("result");
                if (result instanceof Map) collectAck(castMap(result));
                Object response = message.get("response");
                if (response instanceof Map) collectAck(castMap(response));
            }
        }
        acknowledged = !userIds.isEmpty();
        List<Map<String, Object>> replay = new ArrayList<>(held);
        held.clear();
        for (Map<String, Object> event : replay) route(event);
        return acknowledged;
    }

    private void collectAck(Map<String, Object> map) {
        for (String key : new String[]{"message_id", "reply_to_message_id"}) {
            Object value = map.get(key);
            if (value instanceof String && !((String) value).isEmpty()) {
                userIds.add((String) value);
            }
        }
        Object session = map.get("session_id");
        if (sessionId == null && session instanceof String
                && !((String) session).isEmpty()) {
            sessionId = (String) session;
        }
    }

    /** Feed one decoded subscription event (any map off /chat/subscribe). */
    synchronized void feed(Map<String, Object> event) {
        if (event == null) return;
        // NOTE: no seq guard. The subscribe stream multiplexes
        // several seq spaces (chat/agent events run ~5k ahead of
        // message.user events on this wire — measured live
        // 2026-11-09), so a monotone-seq dedupe eats our own
        // message.user anchor and the window never opens.
        // MuseTurn has no seq guard either; arrival order on
        // the one stream is the order.
        if (!acknowledged) {
            if (held.size() < MAX_HELD_EVENTS) held.add(event);
            return;
        }
        route(event);
    }

    private void route(Map<String, Object> event) {
        if (!"event".equals(event.get("type"))) return;
        Object payloadRaw = event.get("payload");
        if (!(payloadRaw instanceof Map)) return;
        Map<String, Object> payload = castMap(payloadRaw);
        String eventSession = stringOrNull(payload.get("session_id"));
        if (eventSession == null) eventSession = stringOrNull(event.get("session_id"));
        if (eventSession != null && sessionId != null && !eventSession.equals(sessionId)) {
            return; // another chat's events neither open nor close this answer
        }
        String name = stringOrNull(event.get("event"));
        if (name == null) return;
        String messageId = stringOrNull(payload.get("message_id"));
        if (messageId == null) messageId = stringOrNull(event.get("message_id"));
        if (messageId == null) messageId = stringOrNull(payload.get("id"));
        if ("message.user".equals(name)) {
            // The answer window (the SDK's rule: the answer is
            // what follows our own message's message.user event).
            // Another user message closes it — a simultaneous
            // phone turn's replies must never bind to this turn.
            windowOpen = messageId != null && userIds.contains(messageId);
            return;
        }
        if (!MESSAGE_EVENTS.contains(name) && !STATUS_EVENTS.contains(name)) {
            return;
        }
        String parent = stringOrNull(payload.get("reply_to_message_id"));
        if (parent == null) parent = stringOrNull(payload.get("parent_message_id"));
        boolean parentBound = parent != null
                && (userIds.contains(parent) || replies.containsKey(parent));
        if (MESSAGE_EVENTS.contains(name)) {
            if (parent != null && !parentBound
                    && !(messageId != null && replies.containsKey(messageId))) {
                ignoredUnbound++; // explicit foreign parent (MuseTurn's rule)
                return;
            }
            if (parent == null && !windowOpen
                    && !(messageId != null && replies.containsKey(messageId))) {
                ignoredUnbound++; // parentless, outside our window
                return;
            }
            onMessage(name, payload, messageId, parent);
        } else { // STATUS_EVENTS
            if (parent != null && !parentBound) {
                ignoredUnbound++;
                return;
            }
            if (parent == null && !windowOpen) {
                // The phantom-turn vector: another flow's
                // parentless statuses never touch this turn.
                ignoredUnbound++;
                return;
            }
            onStatus(name, payload, parent);
        }
    }

    /** Events dropped since arming because they weren't bound to our message. */
    synchronized int ignoredUnboundCount() { return ignoredUnbound; }

    /** Outcome-side diagnostic: gate states + per-reply shape (lengths only). */
    synchronized String debugState() {
        StringBuilder out = new StringBuilder();
        out.append("ack=").append(acknowledged)
           .append(" busy=").append(busy)
           .append(" taskFinished=").append(taskFinished)
           .append(" window=").append(windowOpen ? "open" : "closed")
           .append(" replies=").append(replies.size());
        for (ReplyState reply : replies.values()) {
            out.append(" [done=").append(reply.done)
               .append(" textLen=").append(reply.text.length()).append("]");
        }
        return out.toString();
    }

    /**
     * Diagnostic for the self-test lane: describe how route()
     * would treat one event right now, as id RELATIONSHIPS only
     * (ours/known/foreign/none) — never id values or content.
     */
    private String firstReplyId;

    synchronized String debugDecision(Map<String, Object> event) {
        if (event == null) return "null-event";
        if (!acknowledged) return "held(pre-ack)";
        Object payloadRaw = event.get("payload");
        if (!(payloadRaw instanceof Map)) return "no-payload";
        Map<String, Object> payload = castMap(payloadRaw);
        String eventSession = stringOrNull(payload.get("session_id"));
        if (eventSession == null) eventSession = stringOrNull(event.get("session_id"));
        if (eventSession != null && sessionId != null && !eventSession.equals(sessionId)) {
            return "drop:session-mismatch";
        }
        String name = stringOrNull(event.get("event"));
        String messageId = stringOrNull(payload.get("message_id"));
        if (messageId == null) messageId = stringOrNull(event.get("message_id"));
        if (messageId == null) messageId = stringOrNull(payload.get("id"));
        if ("message.user".equals(name)) {
            return "user-event id=" + (messageId != null && userIds.contains(messageId)
                    ? "ours" : "foreign");
        }
        String parent = stringOrNull(payload.get("reply_to_message_id"));
        if (parent == null) parent = stringOrNull(payload.get("parent_message_id"));
        String parentKind = parent == null ? "none"
                : userIds.contains(parent) ? "ours"
                : replies.containsKey(parent) ? "known-reply" : "foreign";
        String midKind = messageId == null ? "none"
                : replies.containsKey(messageId) ? "known-reply" : "new";
        if ("delta.message_start".equals(name) && messageId != null
                && firstReplyId == null) {
            firstReplyId = messageId;
        }
        String rel = "";
        if (parent != null && parent.equals(messageId)) rel += " parent==mid";
        if (parent != null && parent.equals(firstReplyId)) rel += " parent==firstReply";
        if (messageId != null && messageId.equals(firstReplyId)) rel += " mid==firstReply";
        Object replyTo = payload.get("reply_to_message_id");
        if (replyTo == null) rel += " replyTo=null";
        return "name=" + name + " parent=" + parentKind + " mid=" + midKind
                + " window=" + (windowOpen ? "open" : "closed") + rel;
    }

    private void onMessage(String name, Map<String, Object> payload,
            String messageId, String parent) {
        if (messageId == null || messageId.isEmpty()) return;
        if ("user".equals(payload.get("role"))) return;
        if (parent != null && !userIds.contains(parent) && !replies.containsKey(parent)) {
            return; // names a parent that is not ours: not our reply
        }
        ReplyState reply = replies.get(messageId);
        if (reply == null) {
            if (replies.size() >= MAX_REPLIES) return;
            reply = new ReplyState(messageId);
            replies.put(messageId, reply);
        }
        // route() only lets bound events through (explicit bound
        // parent, or parentless inside our answer window), so any
        // reply tracked here is bound by construction.
        reply.bound = true;
        lastActivityMs = System.currentTimeMillis();
        if ("delta.text_append".equals(name) && !reply.done) {
            Object text = payload.get("text");
            if (text instanceof String) {
                reply.text += (String) text;
                fireText(reply);
            }
            return;
        }
        if ("delta.message_done".equals(name) || "message.assistant".equals(name)) {
            String completed = stringOrNull(payload.get("display_text"));
            if (completed == null) {
                Object transcript = payload.get("transcript");
                if (transcript instanceof String) {
                    completed = (String) transcript;
                } else if (transcript instanceof Map) {
                    completed = transcriptText(castMap(transcript), messageId);
                }
            }
            if (completed == null && !(payload.get("transcript") instanceof String)) {
                completed = stringOrNull(payload.get("content"));
            }
            if (completed != null) reply.text = completed;
            boolean wasDone = reply.done;
            if ("delta.message_done".equals(name)
                    || !Boolean.FALSE.equals(payload.get("display_text_ready"))) {
                reply.done = true;
            }
            fireText(reply);
            if (reply.done && !wasDone) fireDone(reply);
        }
    }

    private void fireText(ReplyState reply) {
        ProgressListener listener = progressListener;
        if (listener != null) {
            listener.onReplyText(reply.messageId, reply.text, reply.done);
        }
    }

    private void fireDone(ReplyState reply) {
        ProgressListener listener = progressListener;
        if (listener != null) {
            listener.onReplyDone(reply.messageId);
        }
    }

    private void onStatus(String name, Map<String, Object> payload, String parent) {
        ReplyState parentReply = parent != null ? replies.get(parent) : null;
        boolean parentLinked = parent != null
                && (userIds.contains(parent) || (parentReply != null && parentReply.bound));
        if (parent != null && !parentLinked) return;
        lastActivityMs = System.currentTimeMillis();
        if ("agent.status".equals(name)) {
            Object activity = payload.get("activity_code");
            if (activity instanceof String) {
                String code = (String) activity;
                busy = !code.isEmpty() && !"online".equals(code) && !"idle".equals(code)
                        && !TERMINAL_TASK_STATUSES.contains(code);
            }
        } else { // task.status
            Object status = payload.get("status");
            if (status instanceof String) {
                String value = (String) status;
                if (TERMINAL_TASK_STATUSES.contains(value)) {
                    taskFinished = true;
                    busy = false;
                } else if ("running".equals(value)) {
                    taskFinished = false;
                    busy = true;
                }
            }
        }
    }

    /**
     * Completion rule (v0.7.0, strict): acknowledged, Muse idle,
     * AT LEAST ONE bound reply exists and every bound reply is
     * done, and either our task finished (with the short
     * EMPTY_REPLY_GRACE_MS so reordered text can still land — the
     * only path that may complete with empty text, e.g. a bound
     * reply that genuinely carried none) or the reply stream has
     * been quiet for REPLY_QUIET_MS with non-empty text. The
     * v0.6.0 rule completed with zero replies once any routed
     * task.status turned terminal — the phantom-turn bug; an
     * empty reply set can never complete a turn now.
     */
    synchronized boolean isAnswered() {
        if (!acknowledged || busy) return false;
        if (replies.isEmpty()) return false;
        for (ReplyState reply : replies.values()) {
            if (!reply.done) return false;
        }
        long quietMs = System.currentTimeMillis() - lastActivityMs;
        if (taskFinished) {
            return quietMs >= EMPTY_REPLY_GRACE_MS;
        }
        return quietMs >= REPLY_QUIET_MS && !replyTextLocked().isEmpty();
    }

    synchronized boolean isAcknowledged() { return acknowledged; }

    /** The reply text so far: non-blank replies joined blank-line separated. */
    synchronized String replyText() { return replyTextLocked(); }

    private String replyTextLocked() {
        StringBuilder out = new StringBuilder();
        for (ReplyState reply : replies.values()) {
            String text = reply.text.trim();
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(text);
        }
        return out.toString();
    }

    /** Message id of the last reply holding text — the TTS handle. */
    synchronized String lastReplyMessageId() {
        String found = null;
        for (ReplyState reply : replies.values()) {
            if (!reply.text.trim().isEmpty()) found = reply.messageId;
        }
        return found;
    }

    /**
     * Message id of the last DONE reply, text or not. Measured
     * on the live wire 2026-11-09: a background-mediated answer
     * can complete as a bare delta.message_done whose payload
     * carries no text at all — the text exists server-side (the
     * app renders it), and streamTts synthesizes from the
     * message id, so the mouth can still speak that reply.
     */
    synchronized String lastDoneReplyMessageId() {
        String found = null;
        for (ReplyState reply : replies.values()) {
            if (reply.done) found = reply.messageId;
        }
        return found;
    }

    /**
     * The SDK's transcript_text(): read assistant text out of a
     * structured transcript ({messages: [{role, id, content:
     * [{type: "text", text}]}]}), last matching assistant message.
     */
    private static String transcriptText(Map<String, Object> transcript, String messageId) {
        Object messages = transcript.get("messages");
        if (!(messages instanceof List)) return "";
        Map<String, Object> chosen = null;
        for (Object raw : (List<?>) messages) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> message = castMap(raw);
            if (!"assistant".equals(message.get("role"))) continue;
            if (messageId != null && !messageId.equals(message.get("id"))) continue;
            chosen = message;
        }
        if (chosen == null) return "";
        Object content = chosen.get("content");
        if (!(content instanceof List)) return "";
        StringBuilder out = new StringBuilder();
        for (Object raw : (List<?>) content) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> item = castMap(raw);
            Object text = item.get("text");
            if ("text".equals(item.get("type")) && text instanceof String) {
                if (out.length() > 0) out.append("\n");
                out.append((String) text);
            }
        }
        return out.toString();
    }

    private static String stringOrNull(Object value) {
        return value instanceof String && !((String) value).isEmpty()
                ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
