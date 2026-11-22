---
name: local-muse-gadget-shield
description: Muse Gadget Shield — the Muse Gadget SDK as an Android app on the NVIDIA Shield TV (package com.jonathancruzate.musegadgetshield); the APK itself is the gadget. BLE pairing v5, Noise/WSS link as node homelink-fa76f8 ("Shield TV"), voice relay to phone, default-assist voice mode (the app holds the voice-interaction role) with the paired Muse's own avatar (Stef) drawn in the SDK's procedural pixel avatar system, and the audio/speech diagnostic probes. Use for anything about the Shield gadget app: installing/updating it, pairing, the assist role, voice mode, the relay toggle, or the probes.
---

# Muse Gadget Shield

The Muse Gadget SDK (the Linux port's design) running as an Android app on
Jonathan's NVIDIA Shield TV (mdarcy, Android 11 / SDK 30). It gives Muse a
paired, linked presence on the TV: BLE pairing with the Muse app, a live
Noise-encrypted link, a full-screen voice mode, and a voice relay to his
phone. Project: `~/workspace/muse-gadget-shield/` — its
`README.md` is the canonical engineering record (every version's changes
and evidence); this skill is the operating summary.

Current installed: **v0.11.2 (versionCode 29)** — the burst-span STREAM_ACCESSIBILITY earcon mute (v0.11.1's MUTE_AUDIO_BEEPS-on-all-sessions proved inert at runtime; see the beep-mechanism paragraph) on top of v0.11.0; INSTALLED 2026-11-17 via `install -r`, link re-registered as homelink-fa76f8, read-back verified. The build it replaced, v0.11.0 (versionCode 27), surfaced the board area's LED + mode controls in MainActivity (see the BoardLink section); built 2026-11-16, installed 2026-11-16 ~23:47 EST, BoardLink gate 5 completed the same night. Before that, v0.10.0 (versionCode 26) added the living-room board USB serial lane (built 2026-11-13, installed 2026-11-14), and v0.9.6/vc25 was the original beep-fix build (installed 2026-11-13, correcting the earlier note that v0.9.5/vc24 was the last installed release). BLE name `MuseGadget-FA76F8`
(hyphenated — the final fleet ruling, 2026-11-08), link display name
"Shield TV", node id `homelink-fa76f8`.

**The APK is the gadget** (Jonathan's ruling, 2026-11-09): the app on
the Shield TV holds the identity, the pairing, and the link itself —
there is no other gadget component for the Shield anywhere else.

## How it works

- **Pairing (BLE, protocol v5).** The app advertises as a BLE peripheral
  under `MuseGadget-FA76F8`; the Muse app (phone) pairs it with the v5
  setup protocol (Noise-based, community_app_v5 self-test vectors pass in
  every build). Pairing state, the device identity, and the SDK token live
  in app-private storage (`identity.json`, `pairing.json`, `sdk_token`).
  Paired 2026-11-08; the link registers as `homelink-fa76f8` on every boot
  of the foreground `GadgetService`.
- **Link.** Noise XX handshake over WSS to the Muse backend (platform
  `linux`, family `homehub`, model `linux`). Those values are NOT a
  porting shortcut: the SDK's `register_params()` hardcodes exactly
  them for every non-firmware device (the Windows PC gadget registers
  identically), `linux/AGENTS.md` mandates them, and the only other
  family (`link`) is the ESP32 firmware family the server pushes OTAs
  to — never use it. What names this device to the fleet is the display
  name "Shield TV" + node + BLE name. The TV executor is deliberately
  read-only: it answers `device.info` and nothing else — no actuation
  surface ships enabled on the TV.
- **Default assist (v0.7.0).** The app holds the system's
  voice-interaction role: `MuseVoiceInteractionService` +
  `MuseVoiceSessionService`, so the remote's mic button opens OUR
  voice session and Google Assistant is never invoked. This is the
  only mechanism that owns that button on this build — the press
  produces NO accessibility key event (proven by isolated-press test,
  2026-11-09). A `StubRecognitionService` is declared only to satisfy
  the manifest; the system's recognition service stays Katniss's
  (verify: `settings get secure voice_recognition_service` →
  `com.google.android.katniss/…KatnissRecognitionService`).
  Role read-backs: `settings get secure voice_interaction_service`
  and `assistant` → our component; `dumpsys role` → ASSISTANT
  holders=our package. Prior holder was Katniss; restore via
  Settings → Apps → Default apps → Assist app (or reset those two
  secure settings to the Katniss component).
- **Voice relay to phone (v0.5.0).** A persisted toggle in the couch UI
  ("Voice relay to phone"). Flipping it OFF→ON sends exactly one chat
  message over the link announcing the activation came from the Shield TV
  in the living room, so Muse can open voice mode on the phone (the
  phone's Device Assistance app owns phone-side activation). While the
  toggle is ON, **no capture path in this app runs** — the probes log
  "skipped: voice relay on" and VoiceActivity shows the relay notice.
  Honest caveat: on API 30 an app cannot revoke its own mic permission,
  so the strip is a **code gate**, not an OS flag — `RECORD_AUDIO` still
  reads granted in Settings while the gate is on.
- **Voice mode (v0.6.0; v0.7.0 avatar + trigger; v0.8.0 the
  paired Muse's own avatar; v0.8.1 latency/patience/UI; v0.9.0
  SDK adoption).** Full-screen
  `VoiceActivity` and the VIS session share one `VoiceUi` +
  `VoiceController`: **no brand header on the voice screen since
  v0.8.1** (Jonathan's order — avatar, badge, state, captions
  only; the Muse logo + wordmark header survives only on
  MainActivity), the **SDK's avatar system** as the
  voice face (`SdkAvatarView` — a faithful port of the ESP32 SDK's
  procedural pixel renderer `muse_pixel.c`: state-driven modes
  BOOT/IDLE/LISTENING/THINKING/SPEAKING/ERROR/OFF, rendered per-frame,
  no image assets; `SpeakingChoreo` drives speaking), the mic-state glyphs in the avatar
  view (the "● LISTENING" badge retired in v0.9.4) + state line,
  and captions for the last transcript and reply.
  The reply caption is a **bounded auto-scrolling region** (v0.8.1)
  that follows the sentence being spoken — long replies can no
  longer run off the bottom of the screen. The UI **opens straight
  into LISTENING** (v0.7.0 fix — v0.6.0's
  passive idle read as dead in Jonathan's live test). Stef's static
  picture is retired as the primary face.
  - **The character is Stef, never the SDK default (v0.8.0,
    Jonathan's order 2026-11-09 21:20 EST — "avatar has to use
    the current users who's paired avatar not the default let
    the gadget sdk").** Following the SDK's own
    `esp32/tools/muse/AVATAR_RECIPE.md` flow (a custom renderer
    replaces the default in place, keeping the animation
    system), `SdkAvatarView`'s geometry + FIXED palette were
    redrawn as the paired Muse's avatar: long dark hair framing
    the face (the superellipse mass, strand-streak shading)
    instead of the fur hood, a taller chin-tapered warm-skin
    face with an arched hairline and side locks, brown almond
    eyes with lashes, thick dark brows, red lips that articulate
    (teeth row in the grin / open speaking mouth), black-top
    sleeves and shoulders with the skin cutout, and the gold
    "Stephany" nameplate (chain + plate + pink heart) at her
    neck. Modes, per-mode schemes/accents, dressing, speaking
    choreography and the view's public API are unchanged from
    v0.7.0. **The SDK's default furry creature (Meta's
    character) must never come back without Jonathan's
    say-so.**
  - **Ears:** the system `SpeechRecognizer` (the public
    KatnissRecognitionService). Direct AudioRecord capture from the
    remote is closed to apps on this Shield (proven three ways in
    v0.4.6/v0.4.7 — silence or policy-mute in every posture), but the
    recognition service works: a live session returned a final
    transcript at 0.97 confidence on 2026-11-08 23:43 EST. Sessions
    reuse the proven probe parameters (FREE_FORM, partial results,
    maxResults 3, calling package set; one session per utterance,
    destroyed after use, 25 s watchdog). **One press starts
    listening — nothing is held** (Jonathan's correction,
    2026-11-09; the on-screen hint reads simply "Listening"
    since v0.9.5 — "— just speak" removed at Jonathan's
    order, 2026-11-10).
    **Endpointing trimmed (v0.8.1):** complete-silence 1200 ms /
    possibly-complete 800 ms — post-speech endpointing only;
    measured on this build they do NOT govern the no-speech
    span (a silent session ran ~5.1 s with COMPLETE_SILENCE=1200
    set; the no-speech give-up is Katniss-internal).
    **Listen window + one beep (v0.9.1, Jonathan's spec
    2026-11-09 22:24):** a listen phase gets **10 s** without a
    recognized result, enforced by a phase clock in the
    controller, then idles (`VOICE listen window expired after
    10 s without speech — idle`). Katniss still ends its own
    sessions inside the window (ERROR_NO_MATCH / empty results,
    ~1.3–5.1 s) and they chain invisibly. **Exactly one audible
    beep per phase** — the first session's; chained restarts
    hold the beep-stream mute (music/notification/assistance)
    for the restart session's ENTIRE span, restored only when
    that session ends. (v0.8.1's 45 s window restored the mute
    at ready-for-speech and every restart beeped anyway —
    Katniss plays its ready beep after ready — and its sticky
    per-phase speech flag let error 7 burn the failure budget
    and idle phases mid-window, observed 22:20:27; both fixed:
    no-match routing is now unconditional and listener
    callbacks are epoch-guarded per session.) Genuine errors
    keep the 2-consecutive-failure budget. (v0.9.6 correction,
    2026-11-13: the beep-stream mute in this paragraph never
    actually silenced the restarts and was removed entirely —
    see the v0.9.6 beep-mechanism paragraph below.)
    **Persistent conversation + mic toggle (v0.9.2,
    Jonathan's "mimic voice mode" spec 2026-11-09 22:37):**
    the voice conversation stays open until Back; the mic
    button toggles the mic inside it. Press while listening =
    **capture mute** (recognizer cancelled at once, `VOICE mic
    muted (conversation continues)`, UI shows MUTED; any
    in-flight turn/reply continues). **MUTED presentation
    (v0.9.3, Jonathan's "instead of the rectangle and text"):**
    no badge and no state text in MUTED — a mic-off glyph
    drawn in the avatar view itself (13×14 pixel stamp beside
    the face: bright capsule, grey cradle/stem/base, red slash;
    `SdkAvatarView.drawMic` with `off=true`) carries the
    state, and the avatar's colour scheme desaturates to grey
    (a dedicated `Mode.MUTED`). **LISTENING presentation
    (v0.9.4, Jonathan's "the microphone without the red
    line"):** the badge rectangle is retired in LISTENING
    too — the same stamp at the same anchor WITHOUT the
    slash (`drawMic` with `off=false`), in the listening
    scheme's normal colours, so mute/unmute reads as the
    slash appearing/disappearing in place; the badge view
    stays in the layout INVISIBLE in every state (nothing
    below it moves) and the "Listening" hint
    line stays (shortened in v0.9.5). The SDK ships no mic-mute asset (its only
    mute is the StopWatch speaker toggle); the glyph is ours,
    in the SDK's pixel language. Press while muted =
    unmute (`VOICE mic unmuted`, fresh SILENT burst). Press
    from conversation-idle = a new burst with the single start
    beep. Window expiry returns to conversation-idle, never
    closes the conversation. Press during THINKING/SPEAKING is
    a no-op. Capture mute (the mic) ≠ beep mute: at v0.9.2 the
    beep mute spanned each recognizer run continuously (speaker
    streams 3/5/11, no restore between chained sessions,
    force-restored at SPEAKING entry) — that machinery is
    retired history since v0.9.6 (see below); capture mute is
    unchanged. A VIS re-press arrives
    as hide→show: onHide defers pause 750 ms so the re-show
    routes to the SAME controller's toggle, never a teardown.
    **Beep mechanism (v0.9.6, 2026-11-13 — the restart-beep
    root cause, established from evidence):** the session
    beeps are **Katniss earcons**, not sounds this app plays
    on any audio stream — the recognition service emits
    SPEAK_NOW_BEEP events and plays the beep itself. That is
    why three generations of AudioManager stream mutes
    (streams 3/5/11, in every span/restore arrangement) never
    silenced a restart, and why every restart's "beep muted"
    log line could read back identical full pre-mute volumes
    (15/7/15): the mute was real, it just governed streams the
    beep never travels on. Found by REA of the Katniss APK:
    its start-listening params carry a `muteAudioBeeps` field,
    settable via the recognizer extra
    `com.google.recognition.extra.MUTE_AUDIO_BEEPS`. The fix:
    every session EXCEPT a burst's first carries
    `MUTE_AUDIO_BEEPS=true` (silent restarts, silent unmute
    re-listens, silent post-turn re-listens); the first
    session keeps the single intended beep; all AudioManager
    beep-mute machinery was deleted from `VoiceController`.
    **(v0.11.1, 2026-11-17: the first-session exemption is
    repealed — EVERY session now carries the extra.
    Jonathan ordered "silence everything"; his 2026-11-09
    one-beep cue spec is retired.)**
    **(v0.11.2 CORRECTION, 2026-11-17, runtime evidence: the
    extra is NOT honored on this path. Jonathan's recording
    of the 00:51 burst on v0.11.1 carries the failure beeps,
    and a live logcat capture of the 00:58 burst shows
    Katniss's AudioPlayer playing `raw/open` (KI.wav) and
    `raw/failure` (HS.wav) for EVERY session, extra set or
    not — the dex gate exists but the running
    RecognitionClient's params evidently never receive the
    flag. The working mute in v0.11.2 is a burst-span
    STREAM_ACCESSIBILITY mute in `VoiceController` (engage
    before the burst's first session, hold across chained
    restarts, restore ~700 ms after burst end): the earcon
    AudioTrack uses usage=USAGE_ASSISTANCE_ACCESSIBILITY,
    the one stream the v0.8.1–0.9.2 masking ({3,5,11}) never
    touched. The extra stays set, harmless.)**
    The same handling found and fixed a second defect:
    `voice_recognition_service` had been flipped to this app's
    StubRecognitionService — restored to Katniss's
    KatnissRecognitionService and read back (re-verified
    2026-11-16). Shipped as v0.9.6/vc25 (installed
    2026-11-13), folded into v0.10.0/vc26 (installed
    2026-11-14; read-back verified 2026-11-16: versionName
    0.10.0/vc26, interaction service ours, link registered as
    homelink-fa76f8). Audibility is certified only by
    Jonathan's ears — his next real press is the confirming
    test; logs cannot prove silence.
    **Voice screen background is pure black #000000**
    (v0.9.2; VoiceUi root + avatar background cells;
    MainActivity unchanged).
  - **Turn:** the transcript goes over the link via
    `sendChat(text, null, "voice")`. The `/chat/stream` response is
    only an acknowledgement (the user message id) — the reply TEXT
    arrives as `/chat/subscribe` stream events, followed by
    `ReplyTracker` (an SDK-faithful port of `send_and_wait`): it binds
    events to the turn via the ack's message id, accumulates delta
    events into the reply text, and decides completion from
    agent/task status + quiet time. **Strict binding (v0.7.0):** the
    tracker arms ONLY after our own send's ack and drops every stream
    event while no gadget turn is outstanding — before this fix, the
    open TV screen completed a phantom "turn" off the phone main-chat
    session's stream events (2026-11-09 00:16). A turn started at the
    TV is answered ON the TV (spoken from the TV speakers) — Jonathan's
    rule; the phone plays no part unless the relay toggle is ON.
  - **Mouth (v0.8.1 sentence-streamed; v0.9.0 gated):** a per-turn
    `VoiceMouth.SpeechSession`. The `ReplyTracker`'s bound reply
    text streams through the **`ReplyGate`** (v0.9.0 — the SDK
    `voice_gate.py` state machine ported to this transport: text
    flows only inside a begun turn, the first bound reply confirms
    the turn's lineage, and only the gate-authorized completed
    reply may be TTS-fetched) into a `SentencePump`, which releases
    each COMPLETE sentence to Android `TextToSpeech` (Locale.US) as
    it finalizes — a long reply starts speaking ~1–2 s after its
    first sentence lands instead of after the whole turn. In
    parallel, the whole-message SDK TTS MP3 (`streamTts`, the real
    Muse voice) is fetched speculatively from the moment the
    reply completes (404-retried for asset lag); it claims only a
    turn where no sentence has started yet, since it contains the
    whole message. `streamTts` follows the SDK's semantics exactly
    (Accept: audio/mpeg; the legacy path only on a 404) and
    failures log exception class + message. If no mouth produces
    completed audio the turn FAILS (captions-only is a failed
    turn). The loop re-listens only after the turn is answered AND
    the speech queue drains. Every turn logs `VOICE lane=chat` —
    the SDK's realtime voice lane is NOT adoptable here (it admits
    only PCM mic uplink; no text-turn input exists in the protocol
    — evidence in the README's v0.9.0 section). Back exits.
  - **Trigger:** primary is the **voice-interaction role** (above):
    the mic press is delivered to `MuseVoiceSession` by the system.
    Secondary/diagnostic layer: `KeyTriggerService` (an
    AccessibilityService with `canRequestFilterKeyEvents`) logs EVERY
    key it sees as `KEYPROBE code=<n> action=<down|up> repeat=<r>` and
    consumes `KEYCODE_ASSIST` (219), `KEYCODE_VOICE_ASSIST` (231), and
    `KEYCODE_SEARCH` (84) — but the isolated-press test proved the mic
    key never reaches it on this build, so it is a D-pad/other-key
    lane and a standing diagnostic, NOT the mic path. VoiceActivity's
    own `onKeyDown` consumes the same keys while foreground.
    MainActivity's "Voice mode" button is the first, largest,
    auto-focused element, and GadgetService accepts the
    `…action.VOICE_MODE` startservice action (remote/test lane).
    **Verification status:** role + settings + service binding all
    verified by read-back (2026-11-09), and Jonathan's live
    mic-press test PASSED 2026-11-09 21:17–21:19 EST: the plate
    opened straight into LISTENING, his spoken turn transcribed
    in ~6 s, and the reply was spoken on the TV. Measured from
    logcat, THINKING lasted ~91 s that turn — backend reply
    turnaround, not the mic, recognizer or TTS (playback started
    ~2.4 s after the reply completed). v0.8.0 (Stef avatar)
    verified the same night: build checks PASS, `install -r`,
    link re-registered as homelink-fa76f8, and the voice plate
    screencapped in LISTENING showing the new Stef character
    (`evidence/v0.8.0-voiceactivity.png`). **v0.8.1 (vc18)**
    verified 2026-11-09: build checks PASS, `install -r`, link
    re-registered as homelink-fa76f8; silent-room logcat showed the
    patient window end-to-end — silent no-match restarts with beep
    mute/restore cycles (streams 3/5/11) and the distinct 45 s
    expiry line, with NO failure-budget idle; the header-less
    voice screen with "Listening — just speak" was screencapped
    (`evidence/v0.8.1-voice-listening.png`,
    `v0.8.1-voice-idle-after-patient-window.png`). **v0.9.0
    (vc19)** verified the same night: build checks PASS,
    `install -r`, link re-registered, listen behavior unchanged;
    the gate/turn path awaits Jonathan's next real press (no
    synthetic turns, per the standing ban). **v0.9.1 (vc20)**
    verified 2026-11-09: build checks PASS, `install -r`, link
    re-registered as homelink-fa76f8; silent-room logcat showed
    one full 10 s phase — six chained sessions, every error 7
    routed to a silent restart, mute/restore bracketing each
    restart's whole span, NO failure-budget idle, ending on the
    10 s expiry line (`evidence/v0.9.1-silent-room-logcat.txt`);
    the audible single-beep is Jonathan's next press to confirm.
    **v0.9.2 (vc21)** verified the same night: build checks
    PASS, `install -r`, link re-registered as homelink-fa76f8;
    silent-room toggle runs showed capture mute ×4 / unmute
    ×3, one beep-mute engage + one restore per recognizer span
    (no restore between chained sessions), expiry back to
    conversation-idle, beep bursts from idle, and ZERO
    conversation teardowns (`VOICE paused`/`VOICE closed`
    absent) — `evidence/v0.9.2-silent-room-logcat.txt`; the
    pure-black voice background was pixel-verified
    (`evidence/v0.9.2-voice-black.png`, samples RGB 0,0,0).
    Note: a verification run attempted while the Shield's
    display had fallen asleep was invalidated by the
    screensaver swallowing an activity intent — wake the
    display (keyevent 224) before simulated-press runs.
    **v0.9.3 (vc22)** verified 2026-11-09: build checks PASS,
    `install -r`, link re-registered as homelink-fa76f8; a
    scripted two-invocation run (both presses inside one
    burst — they MUST land inside the 10 s window; a pair
    straddling expiry correctly starts a fresh burst instead)
    produced `VOICE state=MUTED` + `VOICE mic muted
    (conversation continues)` (`evidence/v0.9.3-verification-
    logcat.txt`), and the screencaps show the MUTED state
    carried by the mic-off glyph alone — no badge, no text,
    grey scheme, pure black (`evidence/v0.9.3-voice-muted.png`,
    contrast `evidence/v0.9.3-voice-listening.png`).
    **v0.9.4 (vc23)** verified 2026-11-09: build checks
    PASS, `install -r`, link re-registered as
    homelink-fa76f8; a scripted two-invocation run FROM A
    CLOSED conversation (a still-warm one interleaves VIS
    session-show events and races the captures — back out
    of the voice screen before a verification run)
    produced LISTENING carrying the plain mic glyph and
    then `VOICE state=MUTED` + `VOICE mic muted
    (conversation continues)`
    (`evidence/v0.9.4-verification-logcat.txt`); the
    screencaps show the glyph pair at the same anchor —
    no slash in LISTENING, red slash in MUTED — with no
    badge in either state
    (`evidence/v0.9.4-voice-listening.png`,
    `evidence/v0.9.4-voice-muted.png`).
    **v0.9.5 (vc24)** shipped 2026-11-10: one-string
    change — the listening line reads "Listening"
    ("— just speak" removed, Jonathan's order); build
    checks PASS, `install -r`, link re-registered as
    homelink-fa76f8. Its on-device visual check was cut
    short the same night (a verification VOICE_MODE fire
    opened a phase while Jonathan was at the TV; he
    reported beeping and all on-device voice activity
    was stopped on his order), and the screencap taken
    for it captured the launcher, not the voice screen —
    the shortened line is verified in source + build
    only; do NOT claim an on-screen confirmation for
    v0.9.5.
    **v0.9.6 (vc25)** shipped 2026-11-13: the restart-beep
    fix (see the beep-mechanism paragraph above) — root cause
    from Katniss APK REA (earcons + the MUTE_AUDIO_BEEPS
    recognizer extra), stream-mute machinery deleted,
    `voice_recognition_service` restored to Katniss. It was
    installed 2026-11-13 and replaced by v0.10.0/vc26 the
    next day, which carries the same fix; the installed
    build was read-back verified 2026-11-16. No live
    verification burst was run for the fix; audibility rests
    on Jonathan's next real press.
    **v0.11.1 (vc28)** shipped 2026-11-17: silence
    everything — `MUTE_AUDIO_BEEPS` on ALL sessions, first
    included (see the beep-mechanism paragraph). Built on the
    v0.11.0 tree (board controls ride along). Build checks
    PASS, `install -r`, link re-registered as
    homelink-fa76f8; APK SHA-256 `0e940860…e44`. One
    verification burst ran after install: every session
    start, the first included, logged "beeps muted at
    source". **This build did NOT silence anything at
    runtime (see the v0.11.2 correction in the
    beep-mechanism paragraph).**
    **v0.11.2 (vc29)** shipped 2026-11-17: the burst-span
    STREAM_ACCESSIBILITY mute (engage/restore logged as
    "VOICE beep stream muted/restored"). Build checks PASS,
    `install -r`, onCreate (v0.11.2) 01:06:46 EST, link
    re-registered; APK SHA-256 `3462a9fa…4ca`. No
    verification burst was fired after install; audibility
    rests on Jonathan's next real press, with the
    engage/restore log lines as the mechanism check.
    **Versioning note:**
    the brief's "v0.7.1/vc17" and "v0.8.0/vc18" numbers collided
    with the already-shipped avatar build (0.8.0/vc17), so the
    latency build shipped as **0.8.1/vc18** and the SDK-adoption
    build as **0.9.0/vc19**.
- **Living-room board USB lane (v0.10.0, installed 2026-11-14).**
  A USB host lane to the living-room board (ESP32-C5, native USB):
  `BoardLink` (owned by GadgetService, published as
  `GadgetService.activeBoardLink`) detects a board by VID 0x303A
  (ESP32 TinyUSB default) or CDC (class 2) / CDC-data (class 10)
  interfaces, takes the standard UsbManager permission flow, does
  CDC-ACM setup by hand (controlTransfer SET_LINE_CODING 115200 8N1
  + SET_CONTROL_LINE_STATE — framework only, no usb-serial library),
  and speaks the `BoardProtocol` framed protocol (magic AA 55,
  payload_len LE16, type, payload, CRC16-CCITT over len+type+payload;
  STATUS_REQ/RESP, BUTTON_EVENT, LED_SET, MODE_SET/MODE_EVENT,
  PING/PONG; reader thread resyncs on magic, drops + counts bad-CRC
  frames) over the bulk endpoints. MainActivity shows a compact
  board area (state, last STATUS_RESP fields, last gesture, last
  mode) with Ping/Status buttons plus LED and mode control rows
  (v0.11.0, installed 2026-11-16)
  (`requestStatus()`, `setLed()`,
  `setMode()`, `ping()` are the BoardLink surface). Attach/detach via
  BoardLink's receiver + the manifest `device_filter` (res/xml).
  Logs: `BOARD` prefix, lengths/states only. Fully inert with no
  board attached; NO chat-sending path added; codec unit-proven
  offline (staging under muse-home-link/livingroom-board).
  **Hardware verification (2026-11-14; the board has been plugged
  into the Shield's USB since 03:26 EST):** enumeration confirmed
  on the real board (VID 0x303A, PID 0x1001, CDC control class 2 +
  CDC data class 10); USB permission granted at the TV at
  22:52:34 EST and persisted (a GadgetService restart reconnects
  with no new dialog); hand-rolled CDC-ACM setup succeeds against
  the C5's serial/JTAG endpoint (`BOARD cdc setup lineCoding=7
  controlLineState=0`, `BOARD connected vid=0x303a pid=0x1001`);
  MainActivity's board area reads "connected". **STATUS round
  trip: board side FIXED in prov-v3.5 (flashed 2026-11-15);
  Shield round trip VERIFIED 2026-11-16** (Phase 3 re-test,
  board back on the Shield: BoardLink connected with no new
  permission dialog — one GadgetService restart was needed
  first, as the already-running service had not picked up the
  re-plugged board — and the app decoded STATUS_RESP on the
  connect poll and every later poll; the TV panel populates
  with uptime/mode/heap and the live counters,
  usb_rx_frames advancing 1 -> 8 across the session's polls
  with usb_crc_errors and usb_rx_dropped at 0 throughout;
  the app's Ping got an instant PONG, no timeout. Board
  uptime advanced monotonically all session on Shield power,
  so the PC-era COM3 reset-cycling is gone. **LED_SET /
  MODE_SET from the app (Phase 3 gate 5) VERIFIED
  2026-11-16** after the v0.11.0 install (on v0.10.0,
  MainActivity exposed only BOARD PING and BOARD STATUS
  buttons, so there was no app control to drive them
  with; v0.11.0 adds an LED row (down / idle / pilot /
  night / ack, calling `setLed(0..4)`) and a mode row
  (idle / pilot / night, calling `setMode(MODE_*)`)
  under the existing board buttons). Mode half: MODE
  NIGHT logged `BOARD setMode mode=2 (night) sent=true`
  + a decoded `BOARD mode event mode=2 (night)`, the
  panel Mode field read night, a fresh STATUS_RESP read
  mode=night / csi_rate_hz=5, and the tunnel-side
  `lr.csi_status` agreed (target_rate_hz 5, unpaused);
  MODE PILOT the same shape (panel csi_rate_hz=0,
  tunnel paused=true / target 0); MODE IDLE restored
  (csi_rate_hz=20). LED half: LED ACK (pattern 4) and
  LED NIGHT (pattern 3) each logged `BOARD setLed
  pattern=<n> sent=true` with no BOARD errors, and
  receipt is proven by the board's own counter:
  usb_rx_frames advanced +2 across each LED tap + poll
  pair (15 -> 17 ack, 18 -> 20 night) against +1 for a
  control poll alone, with usb_crc_errors and
  usb_rx_dropped at 0 all session; LED proof is
  counter-based, NOT visual (the protocol has no LED
  ack frame and no camera watches the board's LED).
  LED IDLE (pattern 1) restored at the end; final panel
  mode=idle / csi_rate_hz=20 / usb_rx_frames=24,
  errors 0/0, board uptime monotonic all session.
  Evidence: `evidence/boardlink-phase3-2026-11-16-*`
  and `evidence/boardlink-gate5-2026-11-16-*`.) The 2026-11-14 defect
  (across two GadgetService sessions the app sent 3 STATUS_REQ
  and 1 PING and zero frames were ever decoded, reader stop
  line `badCrc=0 decoded=0`, and the MainActivity Status field
  stayed empty, while the board itself was healthy on its
  Wi-Fi tunnel) was diagnosed on the PC against prov-v3.3-csi:
  two tasks raced 1-byte blocking reads on the board's single
  USB Serial/JTAG RX queue (the stock token console's usj_task
  and lr_usb's usb_task), splitting every frame so none ever
  parsed. Prov-v3.5 makes the console task the queue's only
  reader and demuxes whole AA55 frames to lr_usb. PC-side
  proof over COM3 (2026-11-15): 10/10 PING -> PONG and 10/10
  STATUS_REQ -> STATUS_RESP with sane JSON and exact counters
  (usb_rx_frames advancing per frame, usb_crc_errors and
  usb_rx_dropped at 0), a bad-CRC frame counted and ignored,
  MODE_SET answered with a MODE_EVENT push, and the stock
  token line path intact (`>sdk_token=` answered by the
  console, framed traffic flowing right after). The Shield
  round trip itself was observed in the app on 2026-11-16
  (see above; Phase 3 of the prov-v3.5 bundle runbook,
  ~/workspace/goals/manage-my-smart-home/hidden_files/roborock-senses/firmware-provisioned-v3.5/README.md).
- **Probes (diagnostics, all gated by the relay toggle).** Service
  actions on GadgetService; logs under tag `MuseGadgetShield`:
  - `…action.PROBE_AUDIO` — AudioRecord source/rate matrix (v0.4.6;
    result: silence/policy-mute — the remote mic is closed to apps).
  - `…action.PROBE_HOLD` — pre-opened recorders across a held press
    (v0.4.7; result: peak 0 in all windows).
  - `…action.PROBE_SPEECH` — one SpeechRecognizer session, `SPEECH`
    log prefix (v0.5.1; the probe that proved the ears).
  - `…action.PROBE` legacy actions also exist: `STOP`, `FORCE_PAIR`,
    `SELFTEST` (no `.action.` infix — see GadgetService).

## Setup

- **Build:** `./build.sh` in the project dir (framework-only, no
  Gradle). It must print `SELFTEST PASS (all)`, `FETCHVMS PARSE PASS
  (all)`, and `DESKTOP CHECKS PASS`. Output APK:
  `muse-gadget-shield-prototype.apk` in the project dir.
- **Install: `install -r` ONLY, never clear app data.** The SDK token,
  identity, pairing, and the relay toggle persistence all live in app
  data; clearing it unpairs the gadget. ADB lane to the Shield:
  forwarder `~/workspace/bin/tailnet-forward.py 5556 100.82.130.50:5555`,
  then `adb -s 127.0.0.1:5556 install -r <apk>` (adb binary:
  `~/.local/android-sdk/platform-tools/adb`). After install, relaunch
  MainActivity and confirm logcat shows `Link session registered as
  homelink-fa76f8` and the GadgetService is foreground.
- **Default assist (voice role):** the app must hold the role for the
  mic button to reach it. Verify (all read-only, verified 2026-11-09):
  `settings get secure voice_interaction_service` →
  `com.jonathancruzate.musegadgetshield/.MuseVoiceInteractionService`;
  `settings get secure assistant` → same component;
  `dumpsys role` → `android.app.role.ASSISTANT holders=com.jonathancruzate.musegadgetshield`;
  `settings get secure voice_recognition_service` → Katniss's service
  (must stay Katniss's — our StubRecognitionService is a manifest
  satisfier only). Manual path: Settings → Apps → Default apps →
  Assist app → Muse. `cmd role get-role-holders` is NOT supported on
  this build — use `dumpsys role`.
- **Accessibility (secondary trigger/diagnostics):** enable `KeyTriggerService`.
  Via adb (verified working 2026-11-08):
  1. `adb shell settings get secure enabled_accessibility_services` —
     read the current list first (Projectivy Launcher's service is
     enabled on this Shield; never clobber it).
  2. Append
     `com.jonathancruzate.musegadgetshield/com.jonathancruzate.musegadgetshield.KeyTriggerService`
     (colon-separated) and write the combined value back with
     `settings put secure enabled_accessibility_services <value>`.
  3. `adb shell settings put secure accessibility_enabled 1`.
  4. Verify: logcat shows `KEYPROBE trigger service connected`, and
     `dumpsys accessibility` lists the service under Enabled services.
  Manual fallback: Settings → Device Preferences → Accessibility →
  "Muse Gadget voice trigger" → On.
- **Pairing:** already done (2026-11-08). If it is ever lost (app data
  cleared), re-pair from the Muse app: Settings → Devices → add the
  gadget while the app is in its pair window (the app re-opens the
  window when unpaired). The realistic `fetch_vms` schema reads the VM
  list under the `vm_list` key — the v0.4.5 fix; do not regress it
  (build.sh's FETCHVMS PARSE check guards it).

## Use

- **Voice mode:** press the remote's mic button (the app holds the
  assist role, so our voice plate opens — no Google Assistant) or open
  the app and tap "Voice mode". The screen opens on the SDK avatar
  already LISTENING; press the mic button once and just talk (NO
  hold — listening starts automatically on one press); the
  transcript appears as a caption, the reply is captioned and spoken
  from the TV, and it listens again. One beep per listen phase;
  silence gets 10 s (sessions re-arm silently underneath) before
  the phase idles; two genuine failures in a row drop it to the
  idle prompt — press the mic button to start again.
  Back exits to the previous screen.
  Remote fire:
  `adb shell am startservice -n com.jonathancruzate.musegadgetshield/.GadgetService -a com.jonathancruzate.musegadgetshield.action.VOICE_MODE`
- **Relay:** flip "Voice relay to phone" ON in the app's couch UI when
  the phone should be the microphone (one activation message is sent
  per OFF→ON flip; flipping it back OFF restores this app's capture
  paths). Remote fire of the same send:
  `adb shell am startservice -n com.jonathancruzate.musegadgetshield/.GadgetService -a com.jonathancruzate.musegadgetshield.action.RELAY_ON`
  — but prefer the in-app toggle: it also flips the persisted gate.
- **Probes (adb):**
  `adb shell am startservice -n com.jonathancruzate.musegadgetshield/.GadgetService -a com.jonathancruzate.musegadgetshield.action.PROBE_SPEECH`
  (same shape for `PROBE_AUDIO`, `PROBE_HOLD`). Watch with
  `adb shell logcat -s MuseGadgetShield`. Probes no-op with a skip
  line while the relay toggle is ON.
- **Logs:** everything is tag `MuseGadgetShield`; prefixes: `KEYPROBE`
  (every key + trigger), `VOICE` (voice-loop state changes and
  transcript/reply lengths — never contents), `SPEECH` (recognition
  probe detail incl. transcripts), `RELAY` (activation send,
  host/status only — never message bodies or secrets).

## Standing constraints

- **NO self-test sends, ever (Jonathan's standing ban, 2026-11-09).**
  No synthetic chat messages, no self-test loops, no test turns over
  the live link — in any build, for any reason. The v0.7.0 vc15
  build's `VoiceSelfTest` spammed his main chat and was force-stopped;
  vc16 removed it and both wiring sites entirely (zero references in
  source). Never re-add a message-sending test facility. (The
  `SelfTest.java` crypto test-vector check is unrelated and fine: no
  network, no chat.)
- Fleet naming is hyphenated everywhere (`MuseGadget-FA76F8`); do not
  re-litigate. The link display name stays "Shield TV".
- Never clear app data; `install -r` only. Never print or log the SDK
  token or any secret — diagnostics are host + HTTP status + counts.
- The TV executor stays read-only (`device.info` only); no actuation
  surface on the TV without a new explicit directive.
- The Shield is the tailnet exit node — never stop its Tailscale.
