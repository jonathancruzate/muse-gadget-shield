# Muse Gadget — Shield port

**Renamed 2026-11-21 (Jonathan's order):** the app is now **Muse Gadget for Shield TV** (was Muse Gadget Shield; user-facing/launcher name only; package `com.jonathancruzate.musegadgetshield`, node `homelink-fa76f8`, BLE name `MuseGadget-FA76F8`, and link display name "Shield TV" unchanged).

> **Project split (2026-11-09, Jonathan's order):** the Shield gadget
> and the PC gadget are separate projects. This repo is the Shield
> side; the Windows PC port lives in `~/workspace/muse-gadget-pc`.
> Both were split out of the former combined
> `muse-gadget-windows-shield` project, which is archived.

Status 2026-11-08 (Leg B2): **pairing v5 setup protocol + steady-state
link session PORTED (v0.4.0) and verified; app installed on the Shield,
SDK token provisioned into app-private storage, service stopped,
ready to pair.** Pairing itself is Jonathan's step (see "Ready-to-pair"
below) — it has NOT been run yet. Mic capture remains VERIFIED
UNAVAILABLE to apps on this Shield as equipped (see the voice probe
verdict below); voice notes are wired in code, awaiting an audio input.

**v0.4.1 rename (2026-11-08, Jonathan's order — "fix the hyphen and
name everywhere"):** the BLE pairing name now follows the ESP32 /
Home Link convention **with hyphen — `MuseGadget-FA76F8`** (was
`MuseGadgetFA76F8`, the Linux-port style; `Identity.bleName()` is the
single source, so the adapter name, scan-response name, and logs all
carry it), and the link registers under the friendly display name
**"Shield TV"** (`GadgetService.DISPLAY_NAME`, sent as
`link.register`'s `display_name` — previously the BLE name; mirrors
the Windows port's separate `DISPLAY_NAME = "PC"`). Node id unchanged
(`homelink-fa76f8`). versionCode 5 / versionName 0.4.1, APK 78,251 B,
SHA-256 `c2c20ecb9c02bfb7ef5570ddc9ce8455d493f06b18e6492ae12d00358836804c`.
Verified: desktop SELFTEST vectors PASS, identity check prints
`MuseGadget-FA76F8`, on-device logcat shows `advertise
onStartSuccess name=MuseGadget-FA76F8 paired=false`
(`evidence/v0.4.1-ondevice-logcat.txt`).

**v0.4.2 correction (2026-11-08, Jonathan's ruling — the SDK-canonical
no-hyphen form is the fleet standard):** this **reverts the v0.4.1
BLE-name change above**. `Identity.BLE_NAME_PREFIX` is back to
`"MuseGadget"`, so `bleName()` returns **`MuseGadgetFA76F8`** — the
canonical form the SDK specifies (linux `identity.py` / `AGENTS.md`:
the apps compare the text after the prefix with the suffix after
`homelink-` in the node id). The ESP32 Home Link — the fleet's only
hyphenated name (`MuseGadget-DDA658`) — will be renamed to the
no-hyphen form to match, instead of the ports converging on the
hyphen. The v0.4.1 **display name "Shield TV" is retained**
(`GadgetService.DISPLAY_NAME`, unchanged). Node id unchanged
(`homelink-fa76f8`). versionCode 6 / versionName 0.4.2, APK 78,251 B,
SHA-256 `e13982c5e0a13b1b3b6b3c7e624a89a0b2a09991e278d0fcc3521fb6c6e4a355`.
Verified: desktop SELFTEST vectors PASS and identity check prints
`MuseGadgetFA76F8`; installed over v0.4.1 (`install -r`, app data
preserved); on-device SELFTEST PASS (all); logcat shows `advertise
onStartSuccess name=MuseGadgetFA76F8 paired=false` with the adapter
renamed to `MuseGadgetFA76F8`
(`evidence/v0.4.2-ondevice-logcat.txt`). Pair window reopened on
install; pairing itself is still Jonathan's step (below).

**v0.4.3 final naming (2026-11-08, Jonathan's FINAL ruling —
hyphenated, matching the ESP32 firmware as shipped):** this
**supersedes the v0.4.2 correction above**. The deciding fact:
Meta's ESP32 firmware constructs its BLE name **with** a hyphen in
its own identity code (`esp32/main/identity.c`,
`muse_video_wifi.c` — `MuseGadget-%02X%02X%02X` from the MAC), so
the hyphenated form is the reference as shipped and the Linux
client's no-hyphen form is the outlier. Fleet standard, final:
**hyphenated everywhere.** `Identity.BLE_NAME_PREFIX` is
`"MuseGadget-"` again, so `bleName()` returns
**`MuseGadget-FA76F8`** (the v0.4.1 name, now on final footing);
the no-hyphen v0.4.2 window was stopped before install. The
**display name "Shield TV" is retained**
(`GadgetService.DISPLAY_NAME`, unchanged). Node id unchanged
(`homelink-fa76f8`). versionCode 7 / versionName 0.4.3
(`LinkSession.CLIENT_VERSION` also corrected to 0.4.3 — it had
lagged at 0.4.1), APK 78,251 B, SHA-256
`f758d0a609aaeff4c767fb19dc1e0506064a7da71076c68779da0cbda0671530`.
Verified: desktop SELFTEST vectors PASS and identity check prints
`nodeId=homelink-fa76f8 bleName=MuseGadget-FA76F8`; installed over
v0.4.2 (`install -r`, app data preserved, device still unpaired);
logcat shows `advertise onStartSuccess name=MuseGadget-FA76F8
paired=false` with the adapter renamed to `MuseGadget-FA76F8`
(`evidence/v0.4.3-ondevice-logcat.txt`). Pair window reopened on
install; pairing itself is still Jonathan's step (below).

**v0.4.4 diagnostic build (2026-11-08, after the first real-app
pairing attempt):** the 22:34 EST pairing ran the full BLE v5 flow
(encrypted session, `wifi_scan` answered, `provision_v2` received,
`wifi_connecting`/`wifi_connected` sent) and then failed at the
provision verify step — `GadgetService.doProvision` →
`MuseApi.fetchVms` returned an **empty VM list** (HTTP 200 path),
so provisioning was refused with "the account has no VM yet" and
the app showed "Couldn't connect". The Java verify mirrors the
SDK's `pair.py` `_verify_and_save`, so the empty list is real API
data for the app-issued credentials; what was missing is *which*
API root answered. v0.4.4 changes **no pairing behavior** — it only
adds host-only diagnostic logging at the verify step:
`GadgetService` logs the hosts of the app-supplied `api_url` /
`api_url_v2` and which root `fetchVms` will use; `MuseApi.fetchVms`
logs the root host, the HTTP status (and the exception path when no
status was read), and the parsed VM count. Tokens, full URLs and
response bodies are never logged (a `fetch_vms` body carries VM
auth tokens). Data first — no fix attempted in this build.
versionCode 8 / versionName 0.4.4, APK 78,251 B, SHA-256
`d5d7c4306dfb0260b00859d9487bf0274862047e809c19e63cade1fd26fcc464`.
Verified: desktop SELFTEST vectors PASS and identity check prints
`nodeId=homelink-fa76f8 bleName=MuseGadget-FA76F8`; installed over
v0.4.3 (`install -r`, app data preserved, device still unpaired);
logcat shows `service onCreate (v0.4.4)` and `advertise
onStartSuccess name=MuseGadget-FA76F8 paired=false`
(`evidence/v0.4.4-ondevice-logcat.txt`). Pair window reopened on
install; the re-pairing attempt is Jonathan's step (below), and the
new log lines land under the same `MuseGadgetShield` tag.

**v0.4.5 fix (2026-11-08, root cause of the pairing refusal —
`fetch_vms` response-schema drift):** the v0.4.4 diagnostic retry
(22:41 EST) logged it plainly: `api_url`/`api_url_v2` hosts both
`api.muse.ai`, `fetch_vms: HTTP 200, vms=0` — a healthy response
parsing as zero VMs. The bug was ours: `MuseApi.fetchVms` read the
VM list from the key **`"vms"`** with entry fields
`"auth_token"` / `"is_default"`, but the real Muse API returns
**`"vm_list"`** with entries keyed **`"vm_id"` /
`"vm_auth_token"` / `"default"`** (confirmed against both
references: linux SDK `muse_api.py` `data.get("vm_list")` and the
ESP32 `vm_api.c`). The desktop fake VM had been built to our own
wrong schema, which is why every loopback test passed while the
real app could never pair. The fix: parsing factored into
`MuseApi.parseFetchVms` reading `vm_list` (entries missing
`vm_id`/`vm_auth_token` skipped); a top-level `"error_title"` /
`"backend_error_code"` on an HTTP 200 is now treated as failure
(throws, logging only the error CODE value, never the body). The
v0.4.4 diagnostic logging (host, HTTP status, VM count) is kept.
Also aligned: `refreshDeviceToken` now unwraps a top-level
`"payload"` object before reading the token pair, matching SDK
`muse_api._post_refresh`. **Regression fixture:**
`tests/FetchVmsParseMain.java` parses a synthetic response in the
REAL schema (asserts 1 VM with the right id/token/default, plus
incomplete-entry skipping and the error-payload throw) and is
wired into `build.sh` as step [6/6] alongside the SELFTEST
vectors, so every build runs it. BLE name and display name
unchanged (`MuseGadget-FA76F8` / "Shield TV"). versionCode 9 /
versionName 0.4.5, APK 78,251 B, SHA-256
`e694165e6d0032cd0502a0ce842037e00d4ad7e519faec8e7b617f14057fa35f`.
Verified: build.sh desktop checks PASS (SELFTEST vectors +
`FETCHVMS PARSE PASS (all)`); installed over v0.4.4 (`install -r`,
app data preserved, device still unpaired); on-device logcat shows
`service onCreate (v0.4.5)`, identity
`node=homelink-fa76f8 bleName=MuseGadget-FA76F8`, and `advertise
onStartSuccess name=MuseGadget-FA76F8 paired=false`
(`evidence/v0.4.5-ondevice-logcat.txt`). Pair window reopened on
install; the re-pairing attempt is Jonathan's step (below).

**v0.4.6 audio capture probe (2026-11-08, on-demand — no pairing
behavior changes):** pairing completed live after the v0.4.5 fix
(`pairing.json` persisted, link registered as `homelink-fa76f8`), so
this build adds the measurement tool the voice question needs next.
New service action
`com.jonathancruzate.musegadgetshield.action.PROBE_AUDIO` runs
`AudioProbe` on a worker thread inside the running service (the
BLE/link session is untouched). It closes the three gaps of the
v0.3 probe — that one only checked `getState()` at construction,
unrouted, never recording, and never while the Shield remote's mic
button was held (the remote streams only while held): for every
enumerated input device it builds an `AudioRecord` (Builder,
PCM16 mono, buffer = max(minBuf×4, 8192)), routes it to that exact
device (`setPreferredDevice` on the instance — the API 30 Builder
has no routing method), starts recording for real, discards ~700 ms,
then measures peak / RMS over ~700 ms, for sources
VOICE_COMMUNICATION and MIC at 48 kHz. All lines log under
`MuseGadgetShield` with the `AUDPROBE` prefix; sweep capped at
~60 s (the idle run took ~10 s). **Idle baseline**
(`evidence/v0.4.6-audprobe-idle.txt`): with per-device routing, the
type 21 (BUS, id 15) and type 15 (BUILTIN_MIC, id 5) inputs now
**initialize and record** — unlike v0.3's all-STATE_UNINITIALIZED —
but deliver pure digital silence at idle (peak=0, rms=0.0 over
~45k / ~35k samples per combo). Type 25 (REMOTE_SUBMIX, id 7)
initializes but `startRecording` never reaches RECORDING
(`outcome=startFailed`) for both sources. So the input stubs open
when routed explicitly yet carry no signal while nothing streams —
the decisive run is the same probe fired while the remote's mic
button is held. versionCode 10 / versionName 0.4.6, APK 82,347 B,
SHA-256 `840fb86fefcb59c3b15376941804271a26df25fe1bb1fa82775f0bfd6ee01d9c`.
Installed over v0.4.5 (`install -r`, data preserved —
`pairing.json` intact); on relaunch the log shows `paired already;
link loop running` and `Link session registered as
homelink-fa76f8` (22:51 EST), then the idle probe above.

**v0.4.7 hold probe (2026-11-08, on-demand — no pairing behavior
changes):** tonight's PROBE_AUDIO live runs produced three results.
(1) **Idle:** the routed BUS (type 21) and builtin (type 15)
recorders initialize and record pure digital silence (peak=0).
(2) **Wrong button held:** the same sweep fired while a non-mic
remote button was held — silence everywhere, as expected.
(3) **Correct mic button held** (remote streaming to Assistant):
every `AudioRecord` construction in the sweep threw
`UnsupportedOperationException` ("Cannot create AudioRecord") —
during streaming the input is **exclusively claimed**, so no new
recorder can even be built. One configuration therefore remained
untested: recorders created *and started* while idle, kept open,
and measured *while the button press begins* — does a pre-opened
recorder get fed, get silenced, or get killed when streaming
starts? v0.4.7 adds exactly that test as a new service action,
`com.jonathancruzate.musegadgetshield.action.PROBE_HOLD`
(`AudioProbe.runHold`): it opens two recorders up front — (a) BUS
device (type 21) + VOICE_COMMUNICATION, (b) builtin device
(type 15) + MIC, both 48 kHz routed via `setPreferredDevice`
(same construction as v0.4.6; open outcomes logged as `AUDHOLD
open device=<id> source=<s> state=<n>`) — starts both, then for
15 s reads each continuously on its own thread, logging peak /
RMS per 1-second window (`AUDHOLD t=<sec> device=<id> peak=<p>
rms=<r> readErr=<e>`); new read-error codes and recording-state
changes are logged immediately with their code; either recorder
failing to open is logged and the other continues. After 15 s
both are stopped + released (`AUDHOLD DONE`). Like PROBE_AUDIO
(kept intact), it creates/releases only its own AudioRecords —
no BLE/link/pairing state is touched. **The hold probe was NOT
fired at install time**; the main agent triggers it live with
Jonathan holding the button. versionCode 11 / versionName
0.4.7, APK 86,443 B, SHA-256
`c5e3279af8df599ffed83cb0ea4df1cdb7fb1af0957bf852e403a26372cca649`.
Verified: build.sh desktop checks PASS (SELFTEST vectors +
`FETCHVMS PARSE PASS (all)`); installed over v0.4.6 (`install
-r`, data preserved — `pairing.json`, `identity.json`,
`sdk_token` all intact); on relaunch the log shows `service
onCreate (v0.4.7)`, `paired already; link loop running, pair
window stays closed`, and `Link session registered as
homelink-fa76f8` (23:03 EST), service foreground
(`evidence/v0.4.7-ondevice-logcat.txt`). **PROBE_HOLD is armed
and ready for the live run.**

**v0.5.0 voice relay to phone (2026-11-08, Jonathan's directive
23:08 + design change 23:10):** the Shield's mic paths are verified
unusable by apps (probe matrix complete: idle = silence, streaming =
exclusively claimed / policy-muted), so this build adds an optional
mode in which the Shield stops being a microphone endpoint and voice
mode activates on Jonathan's phone instead. MainActivity is now a
couch UI (large, D-pad-focusable controls) with a persisted
**"Voice relay to phone"** toggle (`SharedPreferences` file
`voice_relay`, key `relay_to_phone`, default OFF) and a status line.
**There is no Talk button** (design change): the toggle flip itself
is the relay signal.

Behavior matrix:

| Toggle | Behavior |
|---|---|
| OFF (default) | Normal gadget. RECORD_AUDIO permission intact; probe actions run as before. Switching OFF sends nothing. |
| OFF→ON flip in the UI | Persists ON; strips the app's own RECORD_AUDIO access (see strip semantics); gates off every capture path (both probe actions log `AUDPROBE skipped: voice relay on` / `AUDHOLD skipped: voice relay on` and do nothing — enforced both at GadgetService dispatch and inside AudioProbe); sends the relay activation message **exactly once for that enable** via GadgetService action `…action.RELAY_ON` → `LinkService.sendChat` → `LinkSession.sendChat` (text payload to `/chat/stream`, the same session path `sendVoice` posts WAV turns to). Outcome logged as `RELAY` lines, host + status only (`RELAY activation sent host=hatch.metaaivm.com path=/chat/stream replies=N` / `RELAY activation failed host=… error=HTTP nnn`). |
| Restart with toggle already ON | Restores ON, re-attempts the strip (idempotent), capture stays gated — and does **not** resend the message. Sends happen only on a UI OFF→ON transition. |

**Strip semantics:** the runtime self-revoke
(`Context.revokeSelfPermissionOnKill`) is API 31+; the Shield is
API 30, so `VoiceRelay.stripMicPermission` attempts it reflectively
(it takes effect on newer devices) and on API 30 the enforcement is
the code gate above — the log states this plainly
(`RELAY mic strip: revokeSelfPermissionOnKill unavailable on API 30;
capture paths are gated by the relay flag instead`), and
`RECORD_AUDIO` remains `granted=true` at OS level here. Turning the
toggle OFF never auto-re-grants the permission; on a device where
the revoke landed, re-grant via Settings > Apps > Muse Gadget
Shield > Permissions if the mic is ever wanted again.

**The relay message** (verbatim, with the send-time timestamp
appended by `VoiceRelay.buildMessage`):

> [Voice relay — Shield TV] Jonathan activated voice from the Shield TV in the living room. The TV has no usable microphone (verified), so voice mode should run on his phone. Context: he is at the TV and wants to talk to Muse and control the Shield TV. Please bring up voice on the phone for this conversation. (Activated `<yyyy-MM-dd HH:mm:ss zzz, device-local>`.)

**TV home-row presence:** `MainActivity`'s launcher filter carries
`LEANBACK_LAUNCHER` (present since the prototype) and the manifest
now adds a TV banner (`android:banner="@drawable/tv_banner"` on the
application and the activity; `res/drawable/tv_banner.png`, 320×180,
dark background, "Muse Gadget / SHIELD TV"); `android.software.leanback`
stays `required=false`, so the app shows in the Shield's apps row
for couch access.

versionCode 12 / versionName 0.5.0, APK 94,707 B, SHA-256
`9548184d7159f4f4cda17047cb6db351770d8271c98852fe0105aa22dae70394`.
Verified: build.sh desktop checks PASS (SELFTEST vectors +
`FETCHVMS PARSE PASS (all)`); installed over v0.4.7 (`install -r`,
data preserved — `identity.json`, `pairing.json`, `sdk_token`
intact); `dumpsys package` shows versionName 0.5.0 / versionCode 12;
on relaunch `Link session registered as homelink-fa76f8`
(23:12 EST), GadgetService foreground. **Live observation during
verification:** ~9 s after MainActivity appeared on the TV, the
toggle was flipped ON through the UI (the builder sent no input
events beyond a wake keyevent; the flip arrived via the real
listener path — consistent with a couch test by Jonathan, who had
just ordered the design): the log shows `RELAY voice relay ON`, the
strip attempt, `RELAY activation action received`, and
`RELAY activation sent host=hatch.metaaivm.com path=/chat/stream
replies=1` — i.e. one relay message was delivered to the chat
stream, and the toggle remains ON (persisted `relay_to_phone=true`).
UI screenshot: `evidence/v0.5.0-mainactivity.png` (toggle ON,
status line reading "Voice relay ON — microphone stripped on this
TV; voice activation sent to your phone."). A fresh install renders
the toggle OFF by default.

**v0.5.1 speech-recognition probe (2026-11-08, on-demand — no other
behavior change):** the AudioRecord matrix is closed — direct
capture from this app can never hear the remote (idle pipes carry
silence; while the remote streams, the input is exclusively claimed
or policy-muted for direct capture). But that verdict covers only
one capture path. The voice search inside apps like YouTube never
opens an AudioRecord: the app calls the Android
**`SpeechRecognizer`** API and the *system* recognition service —
part of Google's privileged stack — does the listening and returns
text. No audio ever enters the calling app's process, so the
AudioRecord lockout does not apply to it. Tonight's context for
testing exactly that: the r/ShieldAndroidTV workaround for the
remote's mic button (Button Manager remaps it to **keycode 84,
`KEYCODE_SEARCH`**) works by making the button trigger apps'
*native* voice search instead of Google Assistant — and that
in-app voice search rides this same system recognition service.
If the service path is open to this app, the remote button can be
wired straight to it; if it is closed to third-party apps, the
phone relay stays the only voice route.

v0.5.1 therefore adds exactly one thing: service action
`…action.PROBE_SPEECH` → `SpeechProbe`, one 25 s listening session
per firing. It logs `SPEECH available=<isRecognitionAvailable>`
first, creates the `SpeechRecognizer` on the main Looper (the API
requires the main thread; the session is held statically so it
cannot be GC'd mid-flight), and a `RecognitionListener` logs every
callback under the `SPEECH` prefix: `readyForSpeech`,
`beginningOfSpeech`, `endOfSpeech`, the running max RMS (logged
whenever the max climbs by ≥3 dB and again in the DONE line),
buffer-received counts, partial transcripts as they arrive, and on
results the top transcript + confidence. `onError` logs the numeric
code AND its symbolic name (e.g. `ERROR_NO_MATCH`). The intent is
`ACTION_RECOGNIZE_SPEECH` with `LANGUAGE_MODEL_FREE_FORM`,
`EXTRA_PARTIAL_RESULTS`, `EXTRA_MAX_RESULTS=3`, and
`EXTRA_CALLING_PACKAGE`. A hard stop destroys the recognizer after
25 s if no terminal callback lands; exactly one
`SPEECH DONE outcome=<results|error|timeout> maxRms=<v>` line ends
every run. The relay gate applies like every capture path: while
voice relay is ON, the probe logs `SPEECH skipped: voice relay on`
and does nothing (enforced at GadgetService dispatch and inside
`SpeechProbe`). No pairing, BLE, link, or relay behavior changed.

versionCode 13 / versionName 0.5.1, APK 98,803 B, SHA-256
`50072ed7b9282a3001656dd369d60a1e0ade0752ffd98a271d792e3d691af267`.
Verified: build.sh desktop checks PASS (SELFTEST vectors +
`FETCHVMS PARSE PASS (all)`); installed over v0.5.0 (`install -r`,
data preserved — `identity.json`, `pairing.json`, `sdk_token`
intact, `relay_to_phone=true` still persisted); `dumpsys package`
shows versionName 0.5.1 / versionCode 13; on relaunch `Link session
registered as homelink-fa76f8` (23:18 EST), GadgetService foreground
(`evidence/v0.5.1-ondevice-logcat.txt`). **PROBE_SPEECH is armed
and NOT fired** — at verification time the relay toggle is ON, so
firing it now would (correctly) only log the skip line; the live
test runs with the main agent and Jonathan with relay OFF.

**v0.6.0 voice mode (2026-11-08, Jonathan's directive 23:16 —
"built in search triggers by keycode that activates muse gadget
sdk voice mode with full avatar", plus his two 23:45 refinements:
Google Assistant MUST NOT pop up, and the voice UI carries
official Muse branding):** the probe's proof became the product.
Round 3 of the speech probe (23:43 EST) returned a final
transcript at 0.97 confidence — the public Katniss recognition
service is open to this app even though direct AudioRecord
capture is closed. v0.6.0 builds voice mode on exactly that lane.

*Voice UI (`VoiceActivity`, full-screen, TV-first).* Brand header:
the **authentic Muse logo** — extracted from the installed Muse
app itself (com.facebook.aura 15.0.0.3.156: its adaptive launcher
icon's foreground vector, gradient stops and path data
machine-extracted via aapt2, never hand-drawn; see
`res/drawable/muse_logo.xml`) on a white rounded chip next to the
"Muse" wordmark. Below it, **Stef's avatar large and centered**
(the active avatar, `avatar-1792645695164555342-10.webp`,
downscaled to a 512×512 PNG at
`res/drawable-nodpi/stef_avatar.png`), a state line
(Listening… / Thinking… / Speaking… / idle "Hold the remote mic
button and talk to me" — the hold framing in that string was
wrong: listening starts automatically on ONE press and nothing
is held; the string and the framing were corrected in v0.8.1,
below), captions for the last heard transcript
and the last reply, and a subtle scale-pulse on the avatar while
listening. Back exits; DPAD-friendly throughout. MainActivity
gained the same brand header and a prominent focusable "Voice
mode" button.

*Trigger + Assistant suppression (`KeyTriggerService`).* An
AccessibilityService declared with `canRequestFilterKeyEvents`
(`res/xml/key_trigger_service.xml`; it retrieves no window
content). `onKeyEvent` logs **every** key as
`KEYPROBE code=<n> action=<down|up> repeat=<r>` and
**consumes** `KEYCODE_ASSIST` (219), `KEYCODE_VOICE_ASSIST`
(231), and `KEYCODE_SEARCH` (84) on down AND up (returns true),
so the press never routes to the system's
VoiceInteractionService — Google Assistant does not pop up. The
first (repeat-0) down launches VoiceActivity. `VoiceActivity`
consumes the same keys in its own `onKeyDown` while foreground,
so repeat presses inside voice mode can't summon Assistant
either. GadgetService also accepts
`…action.VOICE_MODE` (startservice) as the remote/test launch
lane.

**KEYPROBE verification plan (the honest split).** Verified at
build time: the settings route enables the service and it
**binds** — after appending our component to
`enabled_accessibility_services` (Projectivy Launcher's existing
service preserved) and setting `accessibility_enabled=1`,
logcat shows `KEYPROBE trigger service connected (key filtering
requested)` and `dumpsys accessibility` lists it under Enabled
services. That is partial evidence only. Whether this Shield
build actually delivers the remote's mic key to `onKeyEvent` is
proven by the **live press test**, still owed: Jonathan presses
the remote mic button once while watching logcat — success is
`KEYPROBE code=219` (or 231/84) logged, VoiceActivity opening,
and no Assistant UI. Until that runs, Assistant suppression is
designed-and-bound, not live-proven. KEYPROBE stays in the build
as the standing verification aid.

*Voice loop.* On open (relay OFF) the activity starts one
activity-lifetime `/chat/subscribe` stream (re-opened on the
current link session whenever it ends) and auto-starts listening
after ~1.2 s so the subscription is live before the first reply
can arrive. Recognition sessions reuse SpeechProbe's exact
parameters and one-shot lifecycle (FREE_FORM, partials,
maxResults 3, calling package; created per session, destroyed at
the terminal callback; 25 s watchdog). On a final transcript:
caption it, then a worker runs the chat turn — `sendChat(text,
null, "voice")` (new `output_modality` parameter on the send)
whose response is only an acknowledgement, and the new
**`ReplyTracker`** (a faithful port of the SDK's
`muse_turn.py`/`service.py send_and_wait`) follows the reply
TEXT through the subscription: pre-ack events are held, events
bind to the turn by ack message id / parent chain, delta events
accumulate into text, and completion is decided by
agent/task status plus the SDK's quiet windows (1 s after
message_done with replies, 3 s otherwise). **Reply-text capture
therefore ships working** — the v0.5.x gap (stream handling that
counted replies but discarded their text) is closed. The reply
is captioned and spoken, then the loop re-listens. Two
consecutive failed/empty sessions (or a failed turn) drop the
loop to the idle prompt; a mic-button press re-arms it. The
silent-room behavior is already verified (below): two
`ERROR_NO_MATCH` sessions, one retry, then idle — exactly as
designed.

*Mouth — which TTS shipped.* Primary: the SDK's own TTS path —
`streamTts(replyMessageId)` returns the reply's synthesized MP3,
played through MediaPlayer (the real Muse voice). Fallback:
Android `TextToSpeech` (Locale.US) when the stream path fails or
no message id is available. Captions-only if both fail. The
`VOICE mouth=<streamTts|androidTts|none>` log line records which
mouth each reply used. Neither mouth has spoken a reply yet —
the first real voice turn is the live test — so the mouth order
is shipped-and-logged, pending that proof. (On the verification
open the Android fallback did initialize:
`VOICE androidTts ready=true`.)

*Relay precedence.* While the voice-relay toggle is ON,
VoiceActivity shows "Voice is relayed to your phone" and never
listens, and the trigger still opens the activity (which then
just shows the relay state). All v0.4.5 fixes, the pairing/link,
the relay toggle + gate, and every probe action are unchanged.

versionCode 14 / versionName 0.6.0, APK SHA-256
`9ac21df6866825dda69045a57b91d2a8b1d5eb40c57b16da9bca68280d4a5666`.
Verified: build.sh desktop checks PASS (SELFTEST vectors +
`FETCHVMS PARSE PASS (all)` + full desktop compile — which now
includes all v0.6.0 sources); installed over v0.5.1
(`install -r`, data preserved — `identity.json`, `pairing.json`,
`sdk_token` intact); `dumpsys package` shows versionName 0.6.0 /
versionCode 14; on relaunch `Link session registered as
homelink-fa76f8`, GadgetService foreground; accessibility
service enabled and bound via the settings lane (above);
VoiceActivity launched via the `…action.VOICE_MODE` service
action and screencapped (`evidence/v0.6.0-voiceactivity.png`).
Skill: `docs/SKILL.md` in this project and the installed copy at
`~/workspace/custom-skills/local-muse-gadget-shield/SKILL.md`.

**v0.7.0 default-assist voice + SDK avatar (built 2026-11-08
night, finished and verified 2026-11-09 — Jonathan's decisions:
"Muse Gadget the default assist app" (00:06), "use the avatar
system from the sdk not just the picture" (00:17), "a turn that
starts at the TV ends at the TV" (00:19)):** the accessibility
lane provably cannot own the remote's mic button on this build —
the isolated-press test captured zero key events while Katniss
ran a full Assistant session — so v0.7.0 takes the one mechanism
that does own it: the **voice-interaction role**.

*The assist stack.* `MuseVoiceInteractionService` +
`MuseVoiceSessionService`/`MuseVoiceSession` (same shared
`VoiceUi` + `VoiceController` as `VoiceActivity`), plus a
manifest-satisfier `StubRecognitionService` that is never the
system's recognition service. Verified on device 2026-11-09:
`settings get secure voice_interaction_service` and `assistant`
both = `com.jonathancruzate.musegadgetshield/.MuseVoiceInteractionService`;
`dumpsys role` shows `android.app.role.ASSISTANT
holders=com.jonathancruzate.musegadgetshield`. **Before-values**
(prior holder): Katniss, `com.google.android.katniss` (the stock
TV search/assistant stack). **Restore path:** Settings → Apps →
Default apps → Assist app → Google Assistant, or via adb set
`voice_interaction_service`/`assistant` back to the Katniss
component and remove our ASSISTANT role holder.
**Recognition stays Katniss's:** `settings get secure
voice_recognition_service` =
`com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService`
— verified before and after the vc16 install. The
`KeyTriggerService` accessibility layer from v0.6.0 still ships
(KEYPROBE diagnostics + in-app key consumption) and was
re-enabled in settings on 2026-11-09 (alongside Projectivy,
which was preserved).

*The APK is the gadget (Jonathan's headline ruling, 2026-11-09
— "our muse gadget device for shield should be the APK, an app
that is the gadget on the shield tv").* The app on the TV holds
identity, pairing and the link itself; nothing gadget-side lives
anywhere else. Registration shape, checked against the SDK the
same day: `link_client.py`'s `register_params()` **hardcodes**
`platform: "linux"`, `device_family: "homehub"`,
`model_id: "linux"` for every non-firmware device — the Windows
PC gadget registers identically — and `linux/AGENTS.md` mandates
exactly these values; the only other family, `link`, is the
ESP32 firmware family and the server pushes firmware OTAs to it,
so it must never be used. There is no android/tv platform or
family the SDK supports or the backend documents, so the APK
registers with the homehub shape (the constraint is documented
in `LinkSession.java` at the constants); what presents it to the
fleet as the Shield TV is `display_name` "Shield TV", node
`homelink-fa76f8`, and BLE name `MuseGadget-FA76F8`.

*SDK avatar system.* `SdkAvatarView` (835 lines) is a faithful
Canvas port of the ESP32 SDK's procedural pixel avatar
(`esp32/avatar/muse_pixel.c`): no image assets, nothing
server-driven — each frame renders a pose (mode, time-in-mode,
live audio level) on a 64×64 grid with the SDK's palette,
ordered dithering and geometry, in the SDK's modes
(BOOT/IDLE/LISTENING/THINKING/SPEAKING/ERROR/OFF) with
`SpeakingChoreo` driving the speaking animation. It is the face
of BOTH voice hosts (VoiceActivity and the VIS session);
Stef's static picture is retired as the primary presentation.
Live evidence: `evidence/v0.7.0-voiceactivity.png` — the avatar
in LISTENING mode under the "● LISTENING" badge.

*Voice UI fixes from Jonathan's live test of v0.6.0.* The voice
UI now opens straight into an unmissable LISTENING state (badge
+ state line + avatar mode from the first frame; `start()` calls
`startListening()` immediately) instead of the passive idle that
read as dead. MainActivity's Voice mode entry is the first,
largest (34sp), auto-focused element
(`evidence/v0.7.0-mainactivity.png`). New `tv_banner.png`
(320×180, extracted Muse logo + "Muse" wordmark + "Shield TV"
on the dark brand background) replaces the placeholder banner;
verified in the shipped APK's `res/drawable/`
(`evidence/v0.7.0-launcher-home.png` shows the apps row).

*ReplyTracker strict binding.* The tracker arms only after the
gadget's own `sendChat` ack yields its message id and drops
every stream event while no gadget turn is outstanding — the
fix for the 00:16 phantom cycle (the open TV screen had
completed a "turn" off the phone main-chat session's stream
events). Verified in source (`ReplyTracker` javadoc + arming in
`VoiceController`) and by the tracker's own on-device decision
logs during the incident.

*Self-test REMOVED (Jonathan's standing ban — "no self-test
sends, ever").* The first v0.7.0 build (versionCode 15) carried
a synthetic-turn facility, `VoiceSelfTest` (service action
`…action.VOICE_SELFTEST`), built to prove the on-TV-answer
acceptance criterion; fired repeatedly from the supervisory
loop it spammed Jonathan's main chat (~00:26–01:05 EST,
2026-11-09) until he ordered it stopped and the app was
force-stopped. versionCode 16 deletes `VoiceSelfTest.java` and
both GadgetService wiring sites (constant + dispatch) — zero
references remain in source — and no synthetic-message facility
of any kind ships. (The unrelated `SelfTest.java` crypto
test-vector check stays: no network, no chat.) A TV-started
turn being answered audibly on the TV is now proven only the
real way: Jonathan's live mic-press test (below).

versionCode 16 / versionName 0.7.0, APK SHA-256
`c546f933bc5fb36d93605b7b9712ea9efb30ca78b5cc1ad92d9b73705bbe03be`.
Verified 2026-11-09 (fresh-context pickup): build.sh desktop
checks PASS (SELFTEST vectors + `FETCHVMS PARSE PASS (all)` +
full compile); installed over vc15 (`install -r`, data
preserved — `identity.json`, `pairing.json`, `sdk_token`
intact); `dumpsys package` shows versionName 0.7.0 /
versionCode 16; on relaunch `Link session registered as
homelink-fa76f8` (10:08 EST), GadgetService foreground, VIS
service bound by the system; role + recognition read-backs
above re-verified after install; voice UI launched via the
`…action.VOICE_MODE` service action and screencapped in
LISTENING; silent-room sessions ended ERROR_NO_MATCH with zero
sends, exactly as designed. **Still owed: Jonathan's live
mic-press test** — one press should open OUR voice plate (no
Assistant), a one-press spoken turn should transcribe, answer
through the tracker, and speak the reply from the TV speakers
(the `VOICE mouth=` log line records which mouth). Skill:
`docs/SKILL.md` in this project and the installed copy at
`~/workspace/custom-skills/local-muse-gadget-shield/SKILL.md`.

**v0.8.0 the paired Muse's own avatar (2026-11-09 —
Jonathan's order, 21:20 EST: "avatar has to use the current
users who's paired avatar not the default let the gadget
sdk"):** the voice mode's face is no longer the Gadget SDK's
DEFAULT character. The SDK's own recipe for this replacement
(`esp32/tools/muse/AVATAR_RECIPE.md` + `avatar_prompt.md` —
"Your Muse has its own avatar, and it can redraw that avatar
as a new muse_pixel.c... The default avatar is Meta's
character") is ported to the Android renderer: `SdkAvatarView`
keeps the whole SDK avatar system — same modes
(BOOT/IDLE/LISTENING/THINKING/SPEAKING/ERROR/OFF), same
per-mode colour schemes and accents, same state dressing
(aura, rings, waves, thought dots, sparkles, alert), same
`SpeakingChoreo` gestures and mouth articulation, same public
API (`setMode`/`setLevel`/`setRunning`/`accentColor`) — with
the character's geometry and FIXED palette redrawn as **Stef,
the paired Muse's avatar** (designed from her active Muse
avatar: big smile, red lipstick, long dark wavy hair, black
cutout top, gold "Stephany" nameplate necklace).

*The redraw (in `SdkAvatarView.java`, class docs carry the
full rationale).* The superellipse mass is now her long dark
hair (the SDK's fur shading restyled: vertical strand streaks,
dark hair tones BD→BH, mode-tinted rim kept); the face panel
is taller and chin-tapered (fa 0.62·ja, fb 10.6) with an arched
hairline (higher at the centre part) and side locks over the
cheeks; the eyes are human almonds — white sclera, brown iris,
dark pupil, shine, thick lash line with an outer flick (the
bead eyes are gone); thick dark brow arcs carry the per-mode
raises; a two-pixel nose shadow; the six SDK mouth states are
drawn as her red lips (C_LIP), with the white teeth row in the
grin and the open speaking mouth — her signature smile; the
limbs are sleeves of her black top; below the chin a short
neck, then the black top with its skin cutout wedge, and the
gold nameplate at her neck (chain arc + gold plate + one pink
heart pixel — the script itself is sub-pixel at 64 px). Four
palette roles were added past the SDK's 29 — 33 slots total
including the background, one over the C recipe's 32-entry
guideline, which is an ESP32 table-size constraint the Android
port does not share: C_LIP, C_GOLD, C_TOP, C_TOPL.
**The SDK default creature must not come back without
Jonathan's say-so.**

versionCode 17 / versionName 0.8.0, APK 463,883 B, SHA-256
`caaf547e942b76eda6b4b7737ff9a7a4d7770d4cd3c5e28a84738f747ce1bf19`
(`LinkSession.CLIENT_VERSION` bumped to 0.8.0 alongside the
manifest, as before). Verified 2026-11-09: build.sh PASS
(`SELFTEST PASS (all)` + `FETCHVMS PARSE PASS (all)` +
`DESKTOP CHECKS PASS`); installed `install -r` (data
preserved); `dumpsys package` shows versionName 0.8.0 /
versionCode 17; service onCreate logs `v0.8.0` and on relaunch
`Link session registered as homelink-fa76f8` (21:27:48 EST).
Visual verification (`evidence/v0.8.0-voiceactivity.png`,
read back by the builder): the voice plate in LISTENING shows
Stef — dark hair framing a warm-skin face, wide brown eyes,
small red "o" lips, sleeve-hands raised beside her face, gold
nameplate with its pink heart, blue listening dressing; no
trace of the furry default.
**Concurrency note (honest record):** Jonathan was live-using
the Shield during the install window; seconds after the
relaunch his own mic press opened a session and his spoken
question ("you can control the Nvidia Shield right", 39
chars, 21:27:54) ran a full turn on the new build — THINKING
and SPEAKING states with the new avatar were captured from
that real turn (`evidence/v0.8.0-speaking.png`), and the reply
played out on the TV. No synthetic or test turn was sent by
the builder at any point: the builder's own verification opens
produced only silent-room ERROR_NO_MATCH sessions and zero
sends, and the UI was left closed via Back (`VOICE closed`).
Skill: `docs/SKILL.md` in this project and the installed copy
at `~/workspace/custom-skills/local-muse-gadget-shield/SKILL.md`.

**v0.8.1 voice latency, patience, and the clean voice screen
(2026-11-09 — from Jonathan's live test that evening,
21:17–21:31 EST).** *Versioning note: this work was briefed as
"v0.7.1 (versionCode 17)", but the device and tree were already
at 0.8.0/vc17 (the avatar build above) and Android versionCodes
must increase — so the latency build ships as **0.8.1 /
versionCode 18** and the SDK-adoption build below as **0.9.0 /
versionCode 19**.*

*The measured problem.* Press → LISTENING instantly (one press;
listening starts automatically — **no hold**, Jonathan's
correction, twice that night), transcript heard at +5.3 s,
THINKING 90.4 s (the agent turn itself, VM-side), reply 974
chars — and `streamTts` FAILED with a LinkSessionException in
~64 ms, so Android TTS spoke the whole reply as one utterance
(21:29:24–21:30:19). Separately, the silent-room failure:
session → ~5.1 s silence → `VOICE session error code=7`
(ERROR_NO_MATCH) → one automatic re-listen → 5.1 s → error 7 →
"loop idle after 2 consecutive failures" — beep… beep… dead in
~11 s, twice (21:30:19–21:30:30, 21:31:28–21:31:39).

*streamTts: diagnosis and fix.* Code-level facts: the same path
had WORKED at 21:19 for a 23-char reply; the failure returned
in ~64 ms, far too fast for the 60 s timeout or for synthesis
time. Three client defects were masking whatever the server
did: (1) no `Accept: audio/mpeg` header (the SDK's
`link_client.stream_tts` sends one); (2) on ANY failure of the
primary path the old loop tried the legacy path and overwrote
the first error with the second's; (3) the caller logged only
the exception CLASS, so the HTTP status was lost. Ranked root
causes for the refusal itself: a length-correlated server-side
refusal (a TTS synthesis cap, or the per-message TTS asset not
yet generated when asked ~50 ms after the reply completed —
asset generation scales with length; the short reply's asset
was ready inside the tracker's quiet window, the long one's
was not). The fix, all in this build: SDK-faithful semantics
(legacy path ONLY on a 404 from the primary, exactly like
`stream_tts`), the `Accept` header, HTTP status carried in the
exception, failures logged with class + message (never
payloads), 404-retries with backoff (2 s/4 s/6 s — the
not-ready signature), and the fetch moved EARLIER (next item).
Honest limit: if the backend refuses long-message TTS
outright, no client change can force it — but the refusal is
now named exactly in logcat, and the reply no longer depends
on it (next item).

*Sentence-streamed speaking.* The mouth no longer waits for
the full reply. `ReplyTracker` now fires progress callbacks as
bound reply text grows; `SentencePump` releases each COMPLETE
sentence (terminal punctuation + boundary; trailing fragment
at completion) in order; `VoiceMouth.SpeechSession` queues one
Android-TTS utterance per sentence and starts the speculative
whole-message MP3 fetch the moment a reply completes (the
earliest the TTS endpoint can serve it). The MP3 contains the
WHOLE message, so it claims only a turn where no sentence has
started speaking yet — otherwise it would double-speak; a
discarded prefetch is logged. Re-listen semantics unchanged:
only after the turn is answered AND the speech queue drains.
Expected effect on a tonight-shaped turn: first speech ~1–2 s
after the first sentence finalizes — during the agent's turn,
not ~90 s later. The per-turn log line `VOICE first sentence
ready after <ms>` measures it live. Also: `REPLY_WAIT_MS`
90 s → 150 s (tonight's 90.4 s turn survived the old budget by
a sub-second margin; agent turns are VM-paced).

*Endpointing trim.* Recognizer silence params set in both the
voice loop and SpeechProbe (parity): COMPLETE_SILENCE 1200 ms,
POSSIBLY_COMPLETE 800 ms — a shorter post-speech tail without
cutting mid-sentence pauses.

*Patient listen window + one beep.* ERROR_NO_MATCH (and its
twin ERROR_SPEECH_TIMEOUT) with no speech heard in the phase
no longer touches the failure budget: the phase chains SILENT
restarts until ~45 s have elapsed without `beginningOfSpeech`,
then idles with the distinct line `VOICE listen window expired
after 45 s without speech (patient window)`. Silence also
arrives as EMPTY RESULTS on this recognizer (discovered in
this build's first verification run, 21:46 — two empty-result
sessions burned the budget and idled the loop in ~11 s); empty
results with no speech heard route to the same patient path.
One audible beep per phase (the first session; each post-reply
re-listen may beep once as its cue): chained restarts mute the
beep streams device-wide via AudioManager — streams 3 (music),
5 (notification), 11 (assistance); observed volumes 15/7/15 —
for the ~1 s around the restart's start, restoring the exact
prior volumes at ready-for-speech (+700 ms), with a 1.5 s
watchdog and an unconditional restore at session end, so a
mute can never reach the mouth. Post-speech and genuine errors
keep the 2-consecutive-failure budget.

*Voice screen.* The Muse logo + "Muse" wordmark header is
REMOVED from the voice screen (both hosts share `VoiceUi`;
MainActivity's branding is untouched): avatar, badge, state,
captions only. The reply caption is now a bounded 98 dp
ScrollView region (the 960×540 dp budget again sums exactly)
that AUTO-SCROLLS to the sentence currently being spoken
(utterance-onStart is the sync point); the heard line is
capped at 2 lines; nothing renders off-screen. Hints corrected
everywhere: listening reads **"Listening — just speak"**, idle
reads **"Press the mic button and talk to me"**.

*Verification (no synthetic sends, as always).* Build checks
PASS (SELFTEST + FETCHVMS PARSE + DESKTOP CHECKS); `install -r`
only; service onCreate logs `v0.8.1`; link re-registered as
homelink-fa76f8 (21:45:22 EST); silent-room logcat: the phase
chained silent restarts (mute/restore logged every cycle) and
expired at exactly 45 s (`21:48:43`, distinct expiry line, NO
failure-budget idle). Screenshots:
`evidence/v0.8.1-voice-listening.png` (Stef listening, badge
up, "Listening — just speak", no header),
`evidence/v0.8.1-voice-idle-after-patient-window.png`,
`evidence/v0.8.1-main.png` (MainActivity branding intact).
Final 0.8.1 APK: versionCode 18, SHA-256
`3e1c96ce6345c201466e635b40523b871cadb564ba25a39cce0b60afbae1346e`.

**v0.9.0 SDK adoption (2026-11-09 — Jonathan's order: "adopt
as much of the SDK as you can").** Canonical SDK:
`~/workspace/muse-gadget-sdk` (linux client + esp32). Full
inventory against this app:

| SDK subsystem | Verdict | Why / where |
|---|---|---|
| Pairing v5 crypto (Noise, community_app_v5) | Already faithful | Ported; RFC/self-test vectors pass every build |
| Noise XX / WSS link transport | Already faithful | Ported (`NoiseTransport`/`LinkSession`) |
| `muse_api.fetch_vms` + token refresh | Already faithful | Ported, incl. the `vm_list` schema fix |
| `link_client` register / invoke | Already faithful | Ported; the SDK hardcodes `platform:"linux"`, `device_family:"homehub"`, `model_id:"linux"` for every non-firmware device — this app registers identically (family `link` is ESP32 firmware w/ OTA; never use) |
| `link_client.stream_tts` semantics | **Adopted (v0.8.1)** | Accept header, 404-only legacy fallback, no-body-in-errors |
| `muse_turn` (send_and_wait discipline) | Already faithful | Ported as `ReplyTracker` + strict ack binding |
| `muse_pixel` avatar | Already faithful | Ported as `SdkAvatarView`; character redrawn as Stef in v0.8.0 per AVATAR_RECIPE |
| `voice_gate.ReplyGate` | **Adopted (v0.9.0)** | Ported as `ReplyGate.java` on the chat transport (below) |
| `voice_call` realtime voice lane | **NOT adoptable** | PCM-uplink boundary — evidence below |
| `voice_audio` (PCM capture/convert) | Not applicable | No app-accessible mic input exists on this Shield (proven v0.4.6/v0.4.7) |
| `executor` / `commands` (shell + fileops, drop-ins) | Deliberately NOT adopted | Security posture: the TV executor stays read-only (`device.info` only); Phase 2 actuation stays disarmed |
| `ble_setup` (pairing flow) | Already faithful | Ported as `SetupController` |
| `identity` (node/BLE naming) | Already faithful | Ported (`Identity`) |
| `network` (nmcli/wpa Wi-Fi provisioning) | Not applicable | Linux-host tooling; the TV enters the current connection only |
| `doctor` / `config` / local socket service | Not applicable | Linux-host diagnostics/layout; Android equivalents are logcat + app-private storage |
| Streamed/chunked TTS playback | Partially adopted | The SDK client itself buffers the whole MP3 body before playback; what this app adopts is the streaming that matters at the couch — sentence-streamed speech (v0.8.1) + the earliest-moment MP3 fetch. Byte-streamed MediaPlayer playback was judged unsafe (no progressive source for a Noise-framed asset on this stack) |

*Realtime verdict: the lane does NOT accept text turns —
PCM uplink is required.* Evidence from the canonical client
(`linux/src/musegadget/voice_call.py`, read in full): admission
is `POST /voice/realtime?call_id=…` streaming NDJSON; the only
client→server events are `hatch.voice.call.start`
(`device:"mcu-wearable"`, `call_media_type:"audio"`,
`proactive_greeting_enabled:false`), the
`hatch.voice.capability.offer` (`end_call_stop`), 
`input_audio_buffer.append` carrying **base64 PCM16LE mono
24 kHz** audio (frames validated at 1–960 samples; end-of-input
generates silence frames for endpointing), and
`response.cancel` (barge-in); cleanup is
`POST /voice/end-call`. **No text-input event exists anywhere
in the client's send surface**, and the SDK's own AGENTS.md
forbids inventing server events. Our ears are the Katniss
SpeechRecognizer — text, not PCM — and the remote's audio
never reaches app AudioRecord on this Shield (proven), so the
lane cannot be fed. (The brief's "admission ping" /
`/voice/ws-hint` do not exist in the canonical client source.)
Chat lane stays the operative lane; every turn logs
`VOICE lane=chat (realtime lane not feedable on this device —
PCM uplink unavailable)`. The voice-relay-to-phone toggle and
its gate are untouched; Phase 2 actuation untouched (disarmed).

*ReplyGate port.* `ReplyGate.java` mirrors `voice_gate.py`'s
state machine (STALE / WAITING / PLAYING): a turn begins at our
send; the first bound reply confirms the turn's lineage and
opens the gate; only a gate-admitted reply's text reaches the
SentencePump; and the gate holds the turn's AUTHORIZED SPEAK
ID — the completed reply whose TTS asset the mouth may fetch —
the chat-transport analog of the SDK's authorized speak_text
id. A new turn fences the old (retire → STALE drops late text).
The tracker binds; the gate admits; the pump splits; the mouth
speaks — each layer independently logged (`VOICE gate: …`).

*Also in v0.9.0:* MainActivity's info line now reads the
manifest versionName dynamically (it sat hardcoded at "v0.7.0"
through two releases).

*Verification.* Build checks PASS; `install -r` only; service
onCreate logs `v0.9.0`; link re-registered as homelink-fa76f8
(21:51:00 EST); silent-room listen behavior re-spot-checked
(silent chaining unchanged); main screen shows the dynamic
"v0.9.0" line (`evidence/v0.9.0-main.png`). The gate/turn path
itself awaits Jonathan's next real press — no synthetic turns
were sent (standing ban). APK: versionCode 19, SHA-256
`424adab26dc40b864d416cd0b7b1593ce368ec20b803a2b42e1d90002f0b56c0`.

*What changes at the couch:* one press (no hold) → one beep →
she keeps listening for ~45 s instead of dying at ~11 s; when
she answers a long reply she starts speaking as the first
sentences arrive instead of after the whole reply; captions
stay on screen and follow what's being said; the voice screen
is just Stef, her state, and the words.

**v0.9.1 listen window + single beep (2026-11-09 — Jonathan's
exact spec, 22:24 EST: "The amount of time you gave it before —
5 seconds — just change it to 10 and don't let it beep
twice.")** The v0.8.1 patience design failed in the living
room on both of its promises, so it is replaced, not tuned:

*Why v0.8.1 beeped anyway (restore-at-ready root cause).* The
beep mute restored the stream volumes at ready-for-speech
(+700 ms, 1.5 s watchdog backstop). Katniss plays its ready
beep AFTER the ready callback — so every chained restart
beeped, audibly, verified by Jonathan's ears at 22:22 EST
("It won't stop beeping") while the logs claimed each restart
was muted. Log-verified muting is not verified silence.

*Why error 7 still idled phases (the failure-budget bug,
observed 22:20:27 EST).* No-match routing was gated on
`heardSpeechInPhase`, a sticky per-phase flag set by
`onBeginningOfSpeech` and cleared only at phase start.
Katniss fires speech onset on any audio energy (a mid-stream
join, the user starting to talk at a session boundary), so
one onset poisoned every later no-match in that phase into a
"post-speech failure"; two of those hit the 2-failure budget
and idled the loop mid-window — at 22:20:27 a restart
session's error 7, 1.3 s in and having heard nothing, counted
as the second consecutive failure. Contributing interleaving:
one shared RecognitionListener plus a shared `sessionFinished`
flag the next session reset, so a destroyed recognizer's late
callback could be attributed to its successor, and the
session watchdog never marked the session finished.

*The v0.9.1 design (VoiceController).*
- **Phase clock = 10 s.** `LISTEN_WINDOW_MS` replaces the 45 s
  patient window AND the old 5 s×2-failures behavior. A phase
  starts on activation; if no recognized result arrives within
  10 s, the phase idles with the single line `VOICE listen
  window expired after 10 s without speech — idle`. The clock
  is checked when a session ends, so a phase can overrun by
  one session tail (sessions run ~1.3–2.9 s with no audio
  stream, ~5.1 s with the remote streaming). Katniss sessions
  still end on their own inside the window and chain invisibly
  via `handleNoMatch`.
- **Exactly one audible beep per phase** — the first session's
  start beep. Chained restarts hold the AudioManager mute
  (streams 3/5/11) for the restart session's ENTIRE span: the
  mute is applied before `startListening` and restored only in
  `endSession()` — the funnel every session exit passes
  through (results, error, pause, shutdown, watchdog) — never
  at ready-for-speech. A 25 s watchdog and the phase-end path
  remain as backstops so a mute can never leak into playback.
  Honest limit: mute correctness is log-verifiable (mute and
  restore bracket each restart session below); the audible
  single-beep is confirmed by Jonathan's next press.
- **No-match never touches the budget, unconditionally.**
  ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT, empty results, and
  hung sessions route to the phase clock with no speech-flag
  gate; the flag itself is deleted. Genuine errors and failed
  turns keep the 2-consecutive-failure budget.
- **Per-session listener epochs.** Each session's
  RecognitionListener is stamped with an epoch; `endSession`
  bumps it, so late callbacks from a destroyed recognizer are
  dropped as stale instead of landing on the next session. The
  session watchdog now marks the session finished before
  ending it.
- **Silence extras — what they actually control here.**
  COMPLETE_SILENCE 1200 ms / POSSIBLY_COMPLETE 800 ms are kept
  for POST-speech endpointing. They do NOT govern the
  no-speech span on this build: measured at 21:30 EST a silent
  session ran ~5.1 s before ERROR_NO_MATCH with
  COMPLETE_SILENCE=1200 set, and in the 0.9.1 silent-room run
  silent sessions ran ~1.3–2.9 s — the no-speech give-up is
  Katniss-internal and takes no public extra. The 10 s budget
  therefore lives in the phase clock, not the recognizer.

*Verification.* Build checks PASS (SELFTEST vectors, FETCHVMS
parse); `install -r` only, data preserved; `dumpsys package`
shows versionName 0.9.1 / versionCode 20; link re-registered
as homelink-fa76f8 (22:28:44 EST). Silent-room phase,
22:28:55–22:29:07 EST (`evidence/v0.9.1-silent-room-logcat.txt`):
one phase, six chained sessions, every error code 7 routed to
a silent no-match restart (phaseElapsedMs 2396 → 8804), ZERO
failure-budget idles, each restart's mute/restore bracketing
its full session span, and the phase ending on the 10 s
expiry line alone. No synthetic turns were sent (standing
ban). APK: versionCode 20, SHA-256
`116a3b63af033c6ebbb60e91cbe94ed850c5cdf338bd873649c7f0c3fbce350e`.

*What changes at the couch:* one press → ONE beep → she
listens for 10 s, not ~5, and the restarts underneath are
silent — then she's quiet until pressed again.

**v0.9.2 persistent conversation — mic toggle, conversation-
scoped beep mute, black screen (2026-11-09 — Jonathan's bug
report 22:31 EST: "if I press the mic button again it beeps
multiple times and appears to get stuck"; model set by his
corrections at 22:36–22:37 EST: "mimic voice mode".)**

*Root cause (logcat, phase 22:30:21–22:30:41 EST).* His first
press ran a listen phase (a chained session was mid-stream at
phaseElapsed 8412). The re-press at 22:30:30 arrived as a VIS
hide→show pair: `VOICE paused` (the hide called pause(),
tearing down the recognizer that may have been hearing him)
→ `VOICE session shown (flags=7)` → a NEW phase (phaseElapsed
reset to 1415) with a NEW audible phase-start beep. Around
that, v0.9.1's beep mute still cycled per restart session —
any beep Katniss emitted at a session edge (before the mute
landed, after the restore) escaped, so one phase could chirp
several times — and the UI sat at LISTENING for the full
window, which reads as stuck.

*The model (mirrors Muse voice mode).* The voice
CONVERSATION is persistent: VoiceActivity / the VIS plate
stays open until the user exits (Back). Inside it, the mic
button is a mic TOGGLE, and listening runs in BURSTS:

- **Press while listening → capture mute.** The recognizer
  session is cancelled immediately — mic off, no more
  capture — and the conversation CONTINUES: an in-flight turn
  or a playing reply is unaffected, the UI settles to a clear
  MUTED state ("Muted"). Log: `VOICE mic muted (conversation
  continues)`. No beep, no burst end beyond the capture stop,
  no idle-close.
- **Press while muted → unmute.** Listening resumes in the
  same conversation with a fresh recognizer session and a
  fresh burst clock — silently, no beep. Log: `VOICE mic
  unmuted`.
- **Press from conversation-idle** (conversation open, not
  listening, not muted — e.g. after a window expired) → a new
  listen burst with the single start beep.
- **Press during THINKING/SPEAKING** → no-op (the mic is
  already off); a turn in flight plays out untouched.
- The 10 s no-speech window still ends a burst, but expiry
  returns to CONVERSATION-idle (screen stays up) — it never
  closes the conversation.

*Terminology (kept distinct in code and docs).* **Capture
mute** = the mic (this toggle). **Beep mute** = the speaker
streams (3/5/11) that carry the recognizer's beeps. They are
different switches; the re-press bug conflated their handling.

*Beep rules.* Exactly ONE beep when a burst starts from
conversation-open or conversation-idle (its first session
runs unmuted). ZERO beeps for internal chained restarts,
capture mute, unmute, post-turn re-listen, and failure
retries (all silent bursts now). The beep mute is scoped to
the conversation: it engages before every non-beep recognizer
start and holds CONTINUOUSLY across chained restarts —
`endSession()` no longer restores (that per-session restore
was the chirp) — and is restored when no recognizer is
running and no speech is playing: at window expiry, at
capture mute, at turn start (`runChatTurn`), at a failure
idle, at pause/shutdown. SPEAKING entry force-restores, so
playback always takes precedence over a recognizer mute; a
watchdog (window + 25 s) posted once per engage is the leak
backstop, removed by every restore so it can never fire into
a later span.

*VIS wiring.* A re-press reaches the session as hide→show.
`MuseVoiceSession.onHide` now DEFERS `pause()` by 750 ms;
`onShow` cancels the deferral and calls the controller's
`start()`, which routes an already-active conversation to the
toggle (`handleRepress`) instead of a fresh phase. Back and
destroy cancel the deferral and tear down directly. Presses
that reach the foreground activity instead (voice key,
GadgetService `VOICE_MODE` re-invocation via singleTop
`onNewIntent`) hit the same toggle through `handleVoiceKey`.

*Black screen.* The shared voice UI's background is now
ACTUAL black — pure #000000, no gradient, no blue: the
`VoiceUi` root and `SdkAvatarView`'s background cells (both
were rgb(16,18,24)). Avatar, badge, and captions render on
the black. MainActivity is unchanged.

*Verification.* Build checks PASS (SELFTEST vectors, FETCHVMS
parse); `install -r` only, data preserved; `dumpsys package`
shows versionName 0.9.2 / versionCode 21; link re-registered
as homelink-fa76f8 (final build 22:45:04 EST). Silent-room
toggle runs (`evidence/v0.9.2-silent-room-logcat.txt`,
presses simulated as VOICE_MODE invocations against the open
conversation): across the runs — capture mute ×4 (`VOICE mic
muted (conversation continues)` + `state=MUTED`, recognizer
chain stopped dead each time), unmute ×3 (beep-mute engage
line BEFORE the session-start line — the resumed session is
silent), window expiry ×2 back to conversation-idle with a
single restore, press-from-idle beep bursts ×2 (plain
`session started`), exactly one beep-mute engage and one
restore per recognizer span with NO restore between chained
sessions, and ZERO `VOICE paused` / `VOICE closed` lines —
the conversation was never torn down. One confound worth
recording: the first choreography attempt was invalidated by
the Shield's dream/screensaver — the display had fallen
asleep during the build and a startActivity intent was
swallowed under DreamActivity; waking the display (keyevent
224) restored normal delivery. Environmental, not an app
defect. Black background pixel-verified on
`evidence/v0.9.2-voice-black.png` (3840×2160 screencap; all
sampled corners/edges read RGB 0,0,0). No synthetic turns
were sent (standing ban). Honest limit, as before: log lines
verify the mechanism; the audible single-beep / silence is
confirmed by Jonathan's next real press. APK: versionCode
21, SHA-256
`148c502a2e5730bcc2eac449a6451603462dd3ace2c4db38449aec2b76b568bf`.

*What changes at the couch:* the voice screen is a
conversation that stays up, on a pure black screen. Press to
talk; press again and her mic mutes — she stays right there;
press again and she's listening again, silently. One beep
when a burst opens from idle, none for anything else.

**v0.9.3 muted-state glyph (2026-11-09 — Jonathan's order
23:35 EST: the MUTED state shown with a drawn glyph "instead
of the rectangle and text").**

In v0.9.2 the MUTED state read as the state line's "Muted"
text under the (invisible-badge) slot. v0.9.3 removes the
word entirely: in MUTED there is NO badge rectangle and NO
state text (the state line is set empty; its layout slot
stays, so nothing below it moves) — a drawn **mic-off glyph
in the avatar view itself** carries the state.

*The glyph (`SdkAvatarView.drawMicOff`).* A 13×14 stamp in
the renderer's own pixel language — the same stamp mechanism
as `drawAlert`'s "!" and placed where the foreground dressing
lives, centred beside the face at cheek height, where the
sound waves sit for SPEAKING. The mic capsule is stamped in
the scheme's bright tone, its cradle/stem/base in the mid
tone, and the slash is drawn over it cell-by-cell, 2 px thick,
top-left to bottom-right, in her lipstick red (the palette's
red, and the universal mute colour). It renders in the MUTED
state only. The SDK ships no mic-mute asset to copy (checked
2026-11-09: its only mute is the StopWatch face's speaker
toggle, drawn in firmware) — the StopWatch muted icon was the
reading reference: a plain silhouette with a slash, legible
at a glance.

*New avatar mode.* `SdkAvatarView.Mode.MUTED`, appended after
OFF so the SCHEMES ordinals don't shift, with its own
desaturated grey scheme row: the character renders calm like
IDLE (breathing, blinking, her smile) while the aura/accent
go grey — the desaturation itself is part of the state read.
`VoiceController.avatarModeFor` maps the controller's MUTED
to it, and `muteMic()` now passes an empty state text. All
other states are byte-identical to v0.9.2 (LISTENING keeps
its badge + "Listening — just speak").

*Verification.* Build checks PASS (SELFTEST vectors,
FETCHVMS parse, DESKTOP CHECKS); `install -r` only, data
preserved; `dumpsys package` shows versionName 0.9.3 /
versionCode 22; service onCreate (v0.9.3) and the link
re-registered as homelink-fa76f8 (23:39:34 EST). Muted
screenshot taken with the v0.9.2 choreography — two
VOICE_MODE invocations inside one burst against the open
conversation (the second must land inside the 10 s window:
an earlier attempt pair straddled the window expiry and the
second press correctly started a fresh burst instead —
choreography note for future runs: fire both invocations in
a single scripted sequence, not across chat round-trips).
Log (`evidence/v0.9.3-verification-logcat.txt`): burst opens
23:42:02, second invocation 23:42:06 → `VOICE state=MUTED` +
`VOICE mic muted (conversation continues)`. Screenshots:
`evidence/v0.9.3-voice-muted.png` (Stef calm under the grey
scheme, mic-off glyph at her right — bright capsule, red
slash — no badge, no text, pure black) and
`evidence/v0.9.3-voice-listening.png` (the same conversation
seconds earlier: LISTENING badge + hint, unchanged). The
voice screen was backed out of afterwards, leaving YouTube
TV resumed as found. No synthetic turns were sent. APK:
versionCode 22, SHA-256
`28732c0d8a55441d837b659eb15ce7b0ba3b4a78d2e20bbaed714581d8daa5d0`.

*What changes at the couch:* press again while she's
listening and the word "Muted" is gone — a little red-slashed
mic appears beside her face and everything else goes quiet
grey. That's the whole signal.

**v0.9.4 listening-state glyph (2026-11-09 — Jonathan's
order 23:45 EST: the rectangle is still there with text
when listening, when it should be "the microphone without
the red line").**

v0.9.3 glyph-carried MUTED but left LISTENING on the old
presentation: the filled "● LISTENING" badge rectangle,
pulsing, under the avatar. v0.9.4 retires the badge in
LISTENING too: the same mic stamp is drawn **without the
slash**, at the same anchor beside the face, so the pair
carries both states and mute/unmute reads as the slash
appearing/disappearing in place.

*Implementation (glyph reuse).* `SdkAvatarView.drawMicOff`
became `drawMic(pal, cx, cy, boolean off)`: the 13×14 stamp
(capsule in the scheme's bright tone C_G0, cradle/stem/base
in the mid tone C_G2) is drawn either way; the lipstick-red
slash is overlaid only when `off`. The foreground extras now
call it from both modes — MUTED with `off=true`, LISTENING
with `off=false` — at the identical anchor
(`jcx + 20, jfy + 1`). The colours resolve through the
active mode's scheme, so LISTENING shows the glyph in its
normal colours (bright capsule, blue cradle on this scheme)
and MUTED in the grey scheme, exactly as in v0.9.3. In
LISTENING the stamp is drawn after the sound waves, so the
solid silhouette stays legible over them. On the controller
side, `VoiceController.setState` no longer shows the badge
in any state (and no longer pulses it): the badge view stays
in the layout INVISIBLE purely so nothing below it moves,
the same slot-preservation trick as MUTED's empty state text
in v0.9.3. The "Listening — just speak" hint line is
unchanged; THINKING / SPEAKING / IDLE presentations are
untouched.

*Verification.* Build checks PASS (SELFTEST vectors,
FETCHVMS parse, DESKTOP CHECKS); `install -r` only, data
preserved; `dumpsys package` shows versionName 0.9.4 /
versionCode 23; the link re-registered as homelink-fa76f8
(23:48:45 EST). Screenshots with the v0.9.2/0.9.3
choreography — two VOICE_MODE invocations in one scripted
sequence, the second inside the same 10 s burst — taken from
a CLOSED conversation (a first attempt against a still-warm
conversation interleaved VIS session-show events and raced;
choreography note, extending v0.9.3's: back out of the voice
screen before a verification run). Log
(`evidence/v0.9.4-verification-logcat.txt`): burst opens
23:51:00 (state=LISTENING through chained silent restarts),
second invocation 23:51:04 → `VOICE state=MUTED` +
`VOICE mic muted (conversation continues)`.
`evidence/v0.9.4-voice-listening.png`: the plain mic glyph
at her right cheek — bright capsule, blue cradle, NO slash,
NO badge, hint below, full-colour scheme, pure black.
`evidence/v0.9.4-voice-muted.png`: the same glyph at the
same anchor with the red slash under the grey scheme, no
badge, no text. The voice screen was backed out of
afterwards, leaving YouTube TV resumed as found. No
synthetic turns were sent. APK: versionCode 23, SHA-256
`740d29835a92297c4f3825ebec2a661e767ac349fff5ae8732950862a85ead35`.

*What changes at the couch:* listening is a little mic
beside her face now, not a billboard — and muting just
strikes it through, in place.

**v0.9.5 listening line shortened (2026-11-10 — Jonathan's
order 00:01 EST: "Just remove - just speak and just keep
Listening").** One-string change: the listening state's
line under the avatar (`VoiceController.startListeningInternal`)
reads simply **"Listening"** — "— just speak" removed. No
other string, state, or layout changed. Build checks PASS
(SELFTEST vectors, FETCHVMS parse, DESKTOP CHECKS);
`install -r` only, data preserved; `dumpsys package` shows
versionName 0.9.5 / versionCode 24; service onCreate
(v0.9.5) 00:04:53 EST and the link re-registered as
homelink-fa76f8 (00:04:54 EST). APK SHA-256
`1e1f45299a3407157022d71484fe3839c0583ab8a47d74e4060a39f42b8ddd19`.
Verification caveat, recorded plainly: the on-device visual
check was cut short the same night — a VOICE_MODE fire at
00:05:13 EST opened a listen phase while Jonathan was at
the TV (he reported beeping; all further on-device voice
activity was stopped on his order), and the screencap
taken for this version (`evidence/v0.9.5-voice-listening.png`)
captured the launcher, NOT the voice screen. The shortened
line is therefore verified in source + build only; its
on-screen confirmation is still owed and must not be
claimed from this build's evidence. No synthetic turns
were sent.

**v0.9.6 restart-beep fix (2026-11-13 — Jonathan's order
2026-11-10, "Use agy to diagnose and fix"; executed by a
builder under the same evidence brief after two agy routes
failed — a model-less launch died on a 429 quota, then the
frontier ladder exhausted all five models with exit-3).**

*Root cause, from evidence (REA of the Katniss APK).* The
session beeps are **Katniss earcons**: the recognition
service emits SPEAK_NOW_BEEP events and plays the beep
itself. They are not sounds this app plays on any audio
stream — which is why three generations of AudioManager
stream mutes (streams 3/5/11, in every span/restore
arrangement tried across v0.8.1/v0.9.1/v0.9.2) never
silenced a restart, and why every restart's "beep muted"
log line read back identical full pre-mute volumes
(3=15, 5=7, 11=15): the mute was real, it just governed
streams the beep never travels on. The Katniss dex carries
the actual control: its start-listening params have a
`muteAudioBeeps` field, settable through the recognizer
extra `com.google.recognition.extra.MUTE_AUDIO_BEEPS`.

*Fix.* Every recognizer session EXCEPT a burst's first now
carries `MUTE_AUDIO_BEEPS=true` — chained restarts, unmute
re-listens, and post-turn re-listens are silent; the first
session keeps the single intended beep. All AudioManager
beep-mute machinery was deleted from `VoiceController`
(zero stream-mute call sites remain). The same handling
found and fixed a second defect: `voice_recognition_service`
had been flipped to this app's StubRecognitionService —
restored to Katniss's KatnissRecognitionService and read
back.

*Ship state.* v0.9.6 / versionCode 25, `install -r`,
installed 2026-11-13; folded into v0.10.0/vc26, installed
2026-11-14. Read-back verified 2026-11-16: installed
versionName 0.10.0 / versionCode 26, `voice_recognition_service`
= Katniss, `voice_interaction_service` = ours, link
registered as homelink-fa76f8.

*Verification honesty.* No live verification burst was run
for this fix, and none is claimed: log-based beep
verification is invalid for audibility claims (the standing
lesson of 2026-11-10 — logs prove our calls, not silence).
The mechanism is proven at the layer that produces the
beep; audibility is certified only by Jonathan's next real
press. The brief's written report was not produced by the
original run (the builder died after the code and device
work landed); it was written retroactively on 2026-11-16
from the run's progress log, the source, and device
read-backs: `hidden_files/v096-beep-fix-report.md`.

**v0.11.1 silence everything (2026-11-17 — Jonathan's
order: "Remove the first beep too, silence everything").**
Built on the v0.11.0 tree (board-area LED + mode controls,
installed 2026-11-16 ~23:47 EST with BoardLink gate 5
completed that night). The REA2 dex trace
(`hidden_files/beep-rea2-findings.md`) had proven the
v0.9.6 mechanism sound and isolated the residue: a burst's
opening session, exempted from `MUTE_AUDIO_BEEPS` under the
2026-11-09 one-beep spec, played TWO earcons per burst
(start beep + the error earcon when Katniss killed the
no-speech session) — the beeps Jonathan reported at
2026-11-16 23:44, with restarts verifiably silent. The
exemption is now repealed: `startListeningInternal` sets
`MUTE_AUDIO_BEEPS=true` on EVERY session, first included,
so no earcon can ever play; the one-beep cue spec is
retired. Build checks PASS (SELFTEST, FETCHVMS PARSE,
DESKTOP CHECKS); `install -r`, data preserved; dumpsys
shows versionName 0.11.1 / versionCode 28; service
onCreate (v0.11.1) 00:44:28 EST, link re-registered as
homelink-fa76f8. APK SHA-256
`0e94086082b4919c6c744e498c734fb554c32de8858d75286f5d66319b7a0e44`.
One verification burst ran after install: every session
start, the first included, logged "beeps muted at source".
Audibility certification remains Jonathan's ears.

**v0.11.2 burst-span stream mute (2026-11-17 — the
v0.11.1 correction).** v0.11.1 did NOT silence anything at
runtime. Proof: Jonathan recorded the 00:51 EST burst on
v0.11.1 (video analyzed in `~/workspace/shield/
beep-test-20261117/`): five loud ~1178 Hz beeps, one per
no-speech session death; and a live logcat capture of a
00:58 EST burst shows Katniss's `katniss_search_AudioPlayer`
playing `raw/open` (KI.wav, 8626 B) at every session start
and `raw/failure` (HS.wav, 14000 B) at every no-speech death
— every session, with the MUTE_AUDIO_BEEPS extra set. The
REA2 dex gate exists, but the running RecognitionClient's
params evidently never receive the flag on this path; the
extra stays set (harmless) but is not the mechanism. The
earcon AudioTrack's attributes (REA2: usage=10,
USAGE_ASSISTANCE_ACCESSIBILITY) point at
STREAM_ACCESSIBILITY — the one stream the v0.8.1–0.9.2
masking ({MUSIC, NOTIFICATION, ASSISTANT}) never muted.
v0.11.2 therefore mutes STREAM_ACCESSIBILITY for the span
of each listen burst: engaged in `startListeningInternal`
before the session starts, held across chained restarts
(the failure beep plays in the inter-session gap), restored
~700 ms after the burst ends (window expiry, failure idle,
capture mute, turn start) so the last session's end earcon
is covered, immediate restore on pause/shutdown, 25 s
watchdog backstop. Engage/restore log as "VOICE beep stream
muted (accessibility, prev=N)" / "VOICE beep stream
restored (N)". Decisions API weighed the fix the same night
(sanitized noul): 83.5% for the stream mute over
single-session bursts. Build checks PASS; `install -r`,
data preserved; onCreate (v0.11.2) 01:06:46 EST, link
re-registered as homelink-fa76f8; APK SHA-256
`3462a9fa67a7750e8a0ebe5e117f08e5cdae82f6245532dd96e7c07c440054ca`.
No verification burst was fired after install; audibility
certification is Jonathan's next real press, with the
engage/restore lines as the mechanism check.

**v0.10.0 living-room board USB serial lane (2026-11-13 —
Wave 3 of the living-room board build; Android side only,
NOT installed).** A USB host lane to the living-room board
(the ESP32-C5 board on native USB). *Versioning note:* the
tree already carried an unreleased v0.9.6 / versionCode 25
(the restart-beep fix in `VoiceController`, dated 2026-11-13)
past the last documented release (v0.9.5 / vc24), so this
build ships as **versionName 0.10.0 / versionCode 26** —
versionCodes only ever increase (`LinkSession.CLIENT_VERSION`
bumped to 0.10.0 alongside the manifest, as before).

*Framework only, no library.* This project builds with
`./build.sh` (no Gradle/AndroidX), and no third-party
usb-serial library is vendored: `BoardLink` speaks to the
board directly through `UsbManager` host APIs and does the
CDC-ACM setup by hand — `controlTransfer` SET_LINE_CODING
(115200 8N1) + SET_CONTROL_LINE_STATE (DTR/RTS) on the
communications interface, framed traffic over the bulk
IN/OUT endpoints of the CDC-data interface.

*Detection + permission.* A device qualifies when its VID
is `BoardLink.BOARD_VENDOR_ID` (**0x303A**, the ESP32
native-USB TinyUSB default; `BOARD_PRODUCT_ID = -1` accepts
any PID) OR any interface is CDC (class 2) / CDC-data
(class 10). Permission follows the standard UsbManager
permission-intent flow. Attach/detach: a dynamically
registered `BroadcastReceiver` in `BoardLink` (permission
result, ATTACHED, DETACHED) plus the manifest path — a
`USB_DEVICE_ATTACHED` intent-filter + `@xml/device_filter`
(vendor-id 12346, CDC class 2) on MainActivity and a
not-required `android.hardware.usb.host` feature — so
plugging the board in can surface the app.

*Protocol (`BoardProtocol`, pure Java — no Android deps).*
Frame = magic `AA 55`, payload_len LE16 (payload bytes
only), type u8, payload, CRC16-CCITT (poly 0x1021, init
0xFFFF) over len+type+payload, CRC transmitted LE. Types:
0x01 STATUS_REQ (empty), 0x02 STATUS_RESP (UTF-8 JSON),
0x10 BUTTON_EVENT (gesture 1 single / 2 double / 3 long),
0x20 LED_SET (pattern u8), 0x21 MODE_SET (0 idle / 1 pilot
/ 2 night), 0x22 MODE_EVENT (mode u8), 0x7F PING, 0x7E PONG
(a board-initiated PING is answered with a PONG). The reader
thread's streaming decoder resyncs on the magic across
arbitrary chunk splits, validates the CRC, and drops +
counts bad frames (`badCrcCount`, surfaced in the UI when
nonzero). Payloads are capped at 4096 bytes; a larger claim
is treated as a false magic and resynced past.

*Surfacing — no existing behavior changes.* `GadgetService`
owns one `BoardLink` (created in `onCreate`, stopped in
`shutdown()`, published as `GadgetService.activeBoardLink`
for same-process UI). MainActivity gains a compact board
area under the relay status (the column now scrolls instead
of clipping): connection state, the last STATUS_RESP
flattened to scalar key=value fields, the last button
gesture, the last mode, plus "Board ping" / "Board status"
buttons calling `BoardLink.ping()` / `requestStatus()`
(refreshed every 2 s while the activity is resumed;
`setLed(pattern)` / `setMode(mode)` are on the same public
surface for service-side callers). Logs stay under tag
`MuseGadgetShield` with the `BOARD` prefix —
lengths/states/defined fields only, never payload dumps.
The lane is fully inert with no board attached, and it adds
**no chat-sending code path of any kind** (the standing
no-self-test-sends ban is untouched; voice mode, pairing,
the assist role, and the relay are all unchanged).

*Verification.* Build checks PASS (`SELFTEST PASS (all)`,
`FETCHVMS PARSE PASS (all)`, `DESKTOP CHECKS PASS`; log at
`~/workspace/muse-home-link/livingroom-board/reports/wave3-build.log`).
The frame codec is unit-proven offline against the actual
shipping class: 18 Java checks (STATUS_REQ encode, STATUS_RESP
decode round-trip, CRC-failure drop + count, resync after
garbage with a split magic) plus an independent Python
re-implementation reproducing every Java vector byte-for-byte
(`~/workspace/muse-home-link/livingroom-board/staging/usb-codec-test/`,
`USB CODEC TEST PASS (all)`). **Not installed anywhere and
unverified against real hardware:** USB enumeration /
permission UX on the Shield, the CDC control transfers
against the actual board firmware, endpoint layout, and any
live frame exchange all wait for the board to be physically
attached. APK: versionCode 26, 480,338 B, SHA-256
`e33b2469b61eb2aba6c70a3add0cadf7e08e07a1beea42aba49c4e1333919a28`.

**v0.11.0 board LED + mode controls in the couch UI
(2026-11-16 — built, NOT installed).** Closes the one gap
left by the 2026-11-16 BoardLink Phase 3 re-test: gates
1-4 and 6 passed on the installed v0.10.0 (BoardLink
connected on the persisted permission, STATUS_RESP
populates the TV panel, counters sane, PING answered with
PONG), but gate 5 (LED_SET / MODE_SET effective FROM THE
APP) could not run — v0.10.0's MainActivity exposed
exactly two board controls ("Board ping" / "Board
status") and `BoardLink.setLed()` / `setMode()` were
code-only. v0.11.0 changes **MainActivity only** (plus the
version bumps): two compact rows in the existing board
area, same style as the Ping/Status row — an LED row of
five small buttons ("LED down / idle / pilot / night /
ack") calling `setLed(pattern)` with the firmware's
`lr.led_set` pattern ids 0-4 (0 link-down, 1 idle, 2
pilot, 3 night, 4 ack), and a mode row of three buttons
("Mode idle / pilot / night") calling `setMode()` with
`BoardProtocol.MODE_IDLE` / `MODE_PILOT` / `MODE_NIGHT`.
A mode tap proves its round trip on screen when the
board's MODE_EVENT push updates the "Mode:" field in the
board status text (refreshed on the existing 2 s timer).
**No protocol, codec, or link change:** `BoardProtocol`
and `BoardLink` are untouched, no chat-sending path was
added (the standing no-self-test-sends ban stands), and
voice mode, pairing, the assist role, and the relay are
all unchanged. versionName 0.11.0 / versionCode 27
(`LinkSession.CLIENT_VERSION` bumped to 0.11.0 alongside
the manifest, as before). Build checks PASS
(`SELFTEST PASS (all)`, `FETCHVMS PARSE PASS (all)`,
`DESKTOP CHECKS PASS`); the built APK's badging reads
versionCode 27 / versionName 0.11.0 and all eight new
button labels are present in its dex. APK: 480,338 B,
SHA-256
`998f4286ed62b16aa4754f5067f50d82902edbb7eb1b9c4b48069f18f06d2d12`.
**NOT installed:** the Shield stays on v0.10.0 / vc26;
install (`install -r` only, never clear app data) and
the on-TV gate 5 run happen later, when the TV is free.
Gate 5 has NOT passed — it has not run yet.

**Addendum (2026-11-16 ~23:47-23:54 EST): INSTALLED and
gate 5 PASSED.** `install -r` succeeded; read-back
versionName 0.11.0 / versionCode 27. BoardLink
reconnected on its own after the install (panel
"connected", STATUS_RESP uptime_s=53427 mode=idle
csi_rate_hz=20 usb_rx_frames=9, errors 0/0); a closing
GadgetService restart re-captured the full sequence in
logcat (`BOARD cdc setup lineCoding=7
controlLineState=0`, `BOARD connected vid=0x303a
pid=0x1001`, connect-poll `status_resp` decoded,
`Link session registered as homelink-fa76f8`), no
permission dialog anywhere. Gate 5, mode half: "Mode
night" → `BOARD setMode mode=2 (night) sent=true` +
`BOARD mode event mode=2 (night)` decoded, panel Mode:
night, STATUS mode=night / csi_rate_hz=5, tunnel
`lr.csi_status` target_rate_hz=5 unpaused; "Mode pilot"
→ same shape, panel csi_rate_hz=0, tunnel paused=true /
target 0; "Mode idle" → restored, csi_rate_hz=20. Gate
5, LED half: "LED ack" (pattern 4) and "LED night"
(pattern 3) logged clean `BOARD setLed pattern=<n>
sent=true` lines; receipt proven by the board's own
usb_rx_frames (+2 across each LED tap + poll pair,
15 → 17 and 18 → 20, against +1 for a control poll),
usb_crc_errors / usb_rx_dropped 0 all session; "LED
idle" (pattern 1) restored, final panel
usb_rx_frames=24 after the restart reconnect poll.
LED proof is counter-based, not visual (no LED ack
frame in the protocol, no camera on the board). End
state: board mode idle, LED pattern 1, BoardLink
connected, device.health ok (heap_free_internal
37,339), CSI armed at 20 Hz with frames advancing,
uptime monotonic 53,427 → 53,773 across the session.
Evidence: `evidence/boardlink-gate5-2026-11-16-*`.

## Hardware gate verdict (the question this leg had to resolve)

This Shield (SHIELD Android TV, `mdarcy`, NVIDIA, Android 11 / SDK 30)
**supports BLE peripheral mode**. Real evidence, not inference — prototype
logcat (`MuseGadgetShield` tag, files in `evidence/`):

- `pm has-feature android.hardware.bluetooth_le` → `true`;
  `pm list features` also shows bluetooth, leanback, television.
- `BluetoothAdapter.getBluetoothLeAdvertiser()` → non-null;
  `isMultipleAdvertisementSupported=true`.
  `isLeExtendedAdvertisingSupported=false` (also coded-PHY / 2M /
  periodic all false) → **legacy 31-byte advertising only**.
- Advertiser `onStartSuccess` twice per run: Phase A (service UUID +
  manufacturer data, no name) and Phase B (name `MuseGadget-FA76F8` in
  the **scan response**, adapter renamed) — `mode=2 connectable=true`.
- GATT server: `addService returned=true`,
  `onServiceAdded status=0 uuid=7fdd3d1c-38ea-46cf-8b46-314ecf5f240c`,
  and connection-state callbacks fired. `dumpsys bluetooth_manager`
  during a run shows this app registered as a GATT server
  (`app_if: 5, com.jonathancruzate.musegadgetshield`).
- Corroboration: the Shield already runs a system GATT server for its
  remote (`com.nvidia.shield.remote.server`).

### Findings that shape the real port

1. **31-byte legacy limit is the binding constraint.** v0.1 put the name
   in the advertising packet with the UUID + manufacturer data →
   `onStartFailure errorCode=1` (DATA_TOO_LARGE). v0.2+ splits exactly
   like the payload budget requires: advertising packet = setup service
   UUID + manufacturer paired-flag (`0xFFFF` → `[0]`); scan response =
   device name. That succeeds.
2. **`BluetoothAdapter.setName()` works but is asynchronous** — v0.1
   read the old name back immediately after a `true` return; dumpsys
   showed the new name moments later. The service now polls for the
   rename, and restores `SHIELD` on stop (verified by dumpsys after
   every test run: name back to `SHIELD`, service gone).
3. **Self-scan does not see the Shield's own advertisement** on this
   stack (12 s service-UUID-filtered scan, 0 results while advertising).
   External visibility rests on the advertiser success callbacks + GATT
   registration until the phone-side scan at pairing time.
4. Identity: node id `homelink-fa76f8`, BLE name
   `MuseGadget-FA76F8` (hyphenated — the ESP32 firmware form as
   shipped; the fleet's final standard per the owner's ruling,
   after the v0.4.1 → v0.4.2 → v0.4.3 sequence at the top) —
   suffixes match, persisted in the app's private prefs across
   reinstalls.

## Voice probe verdict (scope update, 2026-11-08 20:44)

**An Android TV app cannot record voice on this Shield today.**

- `AudioManager.getDevices(GET_DEVICES_INPUTS)` → 3 inputs: type 21
  (BUS, 48 kHz), type 25 (REMOTE_SUBMIX, 16/48 kHz), type 15
  (BUILTIN_MIC, 8–48 kHz incl. 24 kHz). No Bluetooth-SCO/remote-mic
  input, no USB input.
- With `RECORD_AUDIO` verified granted in-app, **all 12 AudioRecord
  combinations failed**: sources MIC / DEFAULT / VOICE_RECOGNITION /
  CAMCORDER × 48 / 16 / 24 kHz → every one `state=0`
  (STATE_UNINITIALIZED), `PROBE mic RESULT captured=false`.
  The BUILTIN_MIC policy entry is a stub; there is no capture hardware
  behind it.
- **The Shield remote's microphone is not an app audio source.** It
  feeds Google Assistant through NVIDIA's private remote service; it
  never appears as an input device. Remote-mic capture is not a path.
- Working mic options, in order: **(1) a USB microphone / USB headset**
  plugged into the Shield — it would appear as a USB input device and
  AudioRecord should initialize (re-run this app's probe to confirm in
  one shot); **(2) phone-mic relay** — the phone records and forwards
  WAV to this app / the VM session (extra moving parts, attribution is
  by session so it can work, but it's a relay architecture, not a
  Shield mic); (3) remote mic — ruled out above.

## What the app does (v0.4.0, installed on the Shield, service stopped)

Package `com.jonathancruzate.musegadgetshield`, framework-only Java
(no AndroidX/Gradle; `build.sh` reuses the shield-wifi-sense toolchain).
APK 78,251 B, SHA-256 (v0.4.0)
`1105c21c9fe0d4994b734a3fd6b880c49da0c041d8a1a285369f687e0bf85231`.

Ported from the Linux reference (`~/workspace/muse-gadget-sdk/linux`),
class by class (`app/src/main/java/com/jonathancruzate/musegadgetshield/`):

- **Pairing v5** — `Pairing.java` (transcript, P-256 ECDH via JCA,
  HKDF-SHA256, AES-256-GCM records, generation/deadline rules),
  `SetupController.java` (`ble_setup.py`: RX chunk reassembly via
  `BleFraming.java`, command dispatch, encryption gating,
  `wifi_scan` → single open "current connection" entry,
  `provision_v2` → verify by fetching account VMs → persist
  `pairing.json` → `auth_ok`), `Hashes.java`, `Json.java`, `B64.java`,
  `X25519.java` (pure-Java RFC 7748 for Noise), `Identity.java`,
  `DeviceStore.java`.
- **Steady state** — `NoiseXx.java` (Noise XX initiator), `Proto.java`
  + `NoiseEnvelope.java` + `NoiseFraming.java` (protobuf service
  envelopes, 65,489 B transport chunking), `NoiseTransport.java`
  (stream-multiplexed service requests), `WsClient.java` (RFC 6455
  client), `MuseApi.java` (fetch_vms, device-token refresh),
  `LinkSession.java` (holds `POST /link-control`, `link.register` as
  `platform: "linux"` / `device_family: "homehub"`, `link.invoke` →
  `link.result`, unpair events, chat turns), `LinkService.java`
  (reconnect/backoff loop, 3 h token rotation, unpaired poll).
- **Executor** — deliberately minimal: one harmless read-only command,
  `device.info`; everything else returns an "unknown command"
  `link.result`. No shell on the TV.
- **Voice readiness (code only)** — `LinkSession.sendVoice` posts a
  WAV file item (≤ 2 MiB) to `/chat/stream` with
  `output_modality: "voice"`; `streamTts` fetches the spoken reply.
  No capture path exists yet (see the verdict above) — nothing records.
- `GadgetService` keeps the v0.3-proven BLE shape (Phase A/B
  advertising, adapter rename + restore) and hosts the setup
  controller; unpaired → 600 s pair window; paired → link loop only.
  Actions: default START, `.STOP`, `.FORCE_PAIR`, `.SELFTEST`,
  `.action.PROBE_AUDIO` (v0.4.6 on-demand audio capture probe),
  `.action.PROBE_HOLD` (v0.4.7 pre-opened-recorder hold probe),
  `.action.RELAY_ON` (v0.5.0 voice-relay activation send, fired by
  the MainActivity toggle's OFF→ON flip; both audio probe actions
  are gated off while the relay is ON), `.action.PROBE_SPEECH`
  (v0.5.1 system SpeechRecognizer probe — one 25 s listening
  session via the system recognition service; also relay-gated).

### Verification (2026-11-08, Leg B2)

- **SDK test vectors, production classes**: X25519 RFC 7748 §6.1 +
  the `community_app_v5` pairing vector — transcript hash, session id,
  client-finished decrypt, `pairing_confirmed` seal — PASS, on the
  desktop JVM AND on the Shield itself (`SELFTEST` action, logcat in
  `evidence/v0.4-ondevice-logcat.txt`: `SELFTEST PASS (all)`).
- **Cross-implementation pairing loopback**: the Java device
  (`SetupController` + `Pairing`) driven over stdio by a phone built
  from the SDK's own `build_transcript`/`derive_session_keys` and
  framing — full flow PASS: plaintext `wifi_scan` refused
  (`error_encryption_required`), `pairing_confirmed` opened with the
  SDK-derived key, `wifi_scan` offered the current-connection entry,
  `provision_v2` → `wifi_connecting → wifi_connected → auth_ok`,
  `pairing.json` persisted (`evidence/v0.4-desktop-tests.txt`).
- **Noise/link interop**: Java `LinkSession` against a fake VM built
  on the SDK's responder/envelopes — WS upgrade, Noise handshake,
  register (params asserted), invoke `device.info` + unknown-command
  error, chat text turn, voice turn (WAV item), TTS stream reassembled
  byte-exact, `link.unpaired` handled — PASS
  (`evidence/v0.4-link-interop.txt`). Desktop harness note: this
  sandbox blocks raw JVM sockets, so the test ran the WS leg through a
  Python pipe bridge (`tests/ws_bridge.py`); on the Shield the client
  connects directly.
- **On-device regression**: install over v0.3 (same signer),
  versionCode 4 / 0.4.0; identity migrated to FA76F8; GATT service
  registered; `advertise onStartSuccess` (both phases), adapter
  renamed; STOP restored adapter name to `SHIELD`, service gone,
  media session untouched (paused, same position).
- **Not exercised**: a real phone-driven BLE pairing (needs Jonathan's
  Muse app — his step below), a live session against the production
  VM (needs pairing first), and any microphone capture (impossible as
  equipped).

## Ready-to-pair (Jonathan's step, ~1 min)

The window must be open while pairing: ask Muse to start the gadget
service on the Shield first (it advertises for 600 s), then:

1. Muse app → **Settings > Devices > Developer mode → Add Device**.
2. Pick **`MuseGadget-FA76F8`** and accept the community warning.
3. Pick the offered network ("Use current connection").

On success the app sends `provision_v2`; the Shield verifies the
credentials against the account's VMs, persists `pairing.json`, the
`pairing_confirmed` record carries the SDK token (already provisioned
in app-private storage by the Leg B2 setup step — value never printed),
BLE tears down, and the link loop registers `homelink-fa76f8` with the
VM automatically.

## Remaining path

1. **Voice capture**: plug in a USB microphone / headset — expect a
   USB input device; re-run the v0.3 probe (`AudioRecord` init check),
   then record PCM, downsample to 24 kHz mono like
   `voice_audio.Pcm48To24`, wrap as WAV, and feed
   `LinkSession.sendVoice` (already wired). Spoken replies play via
   `streamTts` MP3 → MediaPlayer on the TV speakers.
2. **Realtime voice**: port `voice_call.py` (583 lines) + `voice_gate.py`
   ReplyGate. Requirements from the Linux reference + linux/AGENTS.md:
   paired store + exact `--vm-id`; a voice-only `LinkSession`
   (`register_device=False`); **app_id** (default `hatch-web`) set on
   the Noise upgrade query; admission `POST /voice/realtime` (end:
   `/voice/end-call`); admit only `mcu-wearable` calls; wait for call/
   session readiness before uploading; PCM16LE mono 24 kHz in
   960-sample (40 ms) frames; half-duplex by default (TV speakers +
   room mic make full duplex an echo trap — the CLI itself recommends
   headphones for full duplex); send the `hatch.voice.capability.offer`
   `end_call_stop` record; bounded idle (45 s) / overall (300 s)
   timeouts. Plus step 4's mic fix for live input and 24 kHz
   AudioTrack playback for output. **If realtime lands, the Muse
   phone app on the Shield is unnecessary for voice — the gadget
   session carries it natively.**

## Evidence

`evidence/`: v0.9.4 — `v0.9.4-voice-listening.png` (LISTENING
state: plain mic glyph beside the avatar, no slash, no
badge, hint below, full-colour scheme, pure black),
`v0.9.4-voice-muted.png` (same conversation after the
toggle: the same glyph with the red slash, grey scheme),
`v0.9.4-verification-logcat.txt` (the two-invocation run
from a closed conversation, 23:51:00–23:51:04 EST).
v0.9.3 — `v0.9.3-voice-muted.png` (MUTED state:
mic-off glyph beside the avatar, no badge, no state text,
grey scheme, pure black), `v0.9.3-voice-listening.png`
(same conversation seconds earlier: LISTENING badge + hint,
unchanged), `v0.9.3-verification-logcat.txt` (the two-
invocation mute run, 23:42:01–23:42:06 EST). v0.9.2 — `v0.9.2-silent-room-logcat.txt`
(persistent-conversation toggle runs: capture mute/unmute,
expiry to conversation-idle, beep bursts from idle, one
beep-mute engage + one restore per recognizer span, zero
conversation teardowns), `v0.9.2-voice-black.png` (voice
screen on pure #000000, pixel-sampled). v0.9.1 —
`v0.9.1-silent-room-logcat.txt` (one 10 s phase, six chained
sessions, 10 s expiry line). v0.9.0 — `v0.9.0-main.png` (main screen, dynamic
version line reading v0.9.0). v0.8.1 — `v0.8.1-voice-listening.png`
(header-less voice screen, Stef listening, "Listening — just
speak"), `v0.8.1-voice-idle-after-patient-window.png` (idle
after the 45 s patient window), `v0.8.1-main.png` (MainActivity
branding intact). v0.8.0 — `v0.8.0-voiceactivity.png` (Stef avatar
in LISTENING under the LISTENING badge), `v0.8.0-speaking.png`
(Stef avatar mid-SPEAKING during Jonathan's live turn),
`v0.8.0-ondevice-logcat.txt` (install/relaunch/verification
run, lengths and states only). v0.7.0 — `v0.7.0-mainactivity.png` (branded main
screen, VOICE MODE first/focused, relay OFF),
`v0.7.0-voiceactivity.png` (SDK pixel avatar in LISTENING mode
under the LISTENING badge), `v0.7.0-launcher-home.png`
(Projectivy home, apps row). v0.5.1 — `v0.5.1-ondevice-logcat.txt` (install/start
run of the SpeechRecognizer probe build; probe armed, not fired).
v0.5.0 — `v0.5.0-ondevice-logcat.txt` (install/start
run + the live toggle flip and relay send), `v0.5.0-mainactivity.png`
(couch UI, toggle ON). v0.4.7 — `v0.4.7-ondevice-logcat.txt` (install/start
run of the PROBE_HOLD build; hold probe armed, not fired).
v0.4.6 — `v0.4.6-audprobe-idle.txt` (idle AUDPROBE
sweep of the capture probe build). v0.4.5 — `v0.4.5-ondevice-logcat.txt` (install/start
run of the fetch_vms vm_list fix build). v0.4.4 — `v0.4.4-ondevice-logcat.txt` (install/start
run of the provision-verify diagnostic build). v0.4.3 — `v0.4.3-ondevice-logcat.txt` (install/start
run of the final hyphenated build). v0.4.2 — `v0.4.2-ondevice-logcat.txt` (install/start/
SELFTEST run of the no-hyphen correction build). v0.4 — `v0.4-desktop-tests.txt` (SDK vectors + pairing
loopback), `v0.4-link-interop.txt` (Noise/link interop vs SDK fake
VM), `v0.4-ondevice-logcat.txt` (install/start/SELFTEST run on the
Shield). v0.3 and earlier: `bt_manager_dump.txt` (pre-test),
`bt_manager_during_advertising.txt` (v0.1 run — its logcat lines,
including the DATA_TOO_LARGE failure, are quoted in the findings
above; `logcat_prototype_test.txt` holds a later run's tail),
`bt_manager_v02_during.txt`
(GATT-server + scanner registration), `logcat_v02_full.txt` (named
advertising success), `logcat_v03_micprobe.txt` (full mic sweep).
Test harnesses live in `tests/` (`VectorMain`, `LoopbackDevice`,
`phone_loopback.py`, `fake_vm.py`, `LinkMain`, `link_interop.py`,
`ws_bridge.py`).

## Status note 2026-11-14 (v0.10.0 BoardLink, on hardware)

v0.10.0 / versionCode 26 installed on the Shield 2026-11-14
03:29 EST (`install -r`; replaced v0.9.6/vc25). The living-room
board (ESP32-C5, MuseGadget-DDA658) was plugged into the
Shield's USB at 03:26 EST. BoardLink verification that night:
enumeration confirmed (VID 0x303A, PID 0x1001, CDC control
class 2 + CDC data class 10); USB permission granted at the TV
at 22:52:34 EST and persisted in dumpsys usb (a GadgetService
restart reconnects with no new dialog); CDC-ACM setup OK
(`BOARD cdc setup lineCoding=7 controlLineState=0`,
`BOARD connected`); MainActivity board area shows "connected".
STATUS round trip NOT proven: 3 STATUS_REQ + 1 PING sent across
two GadgetService sessions, zero frames decoded (reader stop
line: badCrc=0 decoded=0), MainActivity Status field empty.
The board stayed healthy on its Wi-Fi tunnel throughout
(uptime 70,221 s at the 22:56 EST check). Open defect;
suspected cause is the stock console/token channel owning the
C5's USB serial/JTAG endpoint. No code was changed in the
verification run.

## v0.11.3 (2026-11-21) — renamed Muse Gadget for Shield TV + unique icon + new TV banner

Icon/name release (versionCode 30). (1) RENAMED at Jonathan's order: the
app is now **Muse Gadget for Shield TV** (app_name, notification title,
in-app header; package, node, BLE name and link display name unchanged).
(2) Launcher icon is now this app's own from the approved unique Muse app
icon set (`~/workspace/muse-app-logos/`): original Muse blue M on a light
tile plus a teal TV badge (antenna TV with play triangle; Jonathan replaced the original shield-with-check glyph with the TV glyph on 2026-11-21, same handling), superseding the 2026-11-01 all-same-icon
rule; the app previously had no launcher icon of its own. Legacy mipmaps +
round + adaptive icon (background #F0F2F5, foreground at 76% centred).
(3) New Android TV channel banner (320x180, drawable + drawable-xhdpi
tv_banner.png, from the 640x360 master in `~/workspace/muse-app-logos/banners/`):
light ground, M + TV badge, full new name in art. Verified: badging
shows label "Muse Gadget for Shield TV", icon and banner extracted from
the built APK are pixel-identical to the approved art/exports; build
desktop checks pass. APK: muse-gadget-shield-prototype.apk, SHA-256 `d8f691ce0f54ffc3ffc6f98016b1b3c7f93150280fb96c89c67d37cb2a5c0962`.
Not installed by this handling (Shield install is a deliberate step);
ready to install.

## v0.11.4 (2026-11-21) — TV banner revision: no icon tile

Banner-only release (versionCode 31). At Jonathan's order (2026-11-21
23:21 EST), the Android TV channel banner no longer shows the app icon
tile/board: the banner is a solid light background (#F0F2F5) with the
original Muse M and the teal TV badge directly on it, full app name in
art. tv_banner.png (320x180, drawable + drawable-xhdpi) replaced with the
revised export from `~/workspace/muse-app-logos/banners/`
(banner-muse-gadget-shield-320x180.png). Verified: banner extracted from
the built APK (both drawable entries) is pixel-identical to the new
export (0 differing px); apksigner verify passed; build desktop checks
pass. APK: muse-gadget-shield-prototype.apk, SHA-256 `4952eae1431c94b038ec34c3083d0616bbda1dca9343e8b6c7963eacfdd86ace`.
Not installed by this handling (Shield install is a deliberate step);
ready to install.

**Installed 2026-11-21 23:35 EST** at Jonathan's direct order:
`install -r` over v0.11.2 (vc29) via the tailnet lane (127.0.0.1:5556).
On-device read-back: versionName 0.11.4 / versionCode 31. MainActivity
relaunched after install; GadgetService foreground; logcat confirms
`Link session registered as homelink-fa76f8` and fetch_vms HTTP 200. The
installed base.apk pulled back from the device is hash-identical to the
staged APK above, and its tv_banner resources are pixel-identical
(0 differing px) to the revised no-tile export.

## v0.11.5 (2026-11-21) — TV banner final layout: small logo + name on one line

Banner-only release (versionCode 32). Jonathan's photo of the live TV
launcher (2026-11-21 23:37 EST) showed the no-tile banner's mark and
two-line name read oversized next to the other channel banners, so the
banner layout was finalized as: a SMALL logo (M + teal TV badge) on the
left, the app name on the right on ONE line, no tagline. Banner text was
then shortened to just "Muse Gadget" (Jonathan's order 23:39 EST; the
app/launcher name stays Muse Gadget for Shield TV — banner art only).
tv_banner.png (320x180, drawable + drawable-xhdpi) replaced with the
final export from `~/workspace/muse-app-logos/banners/`
(banner-muse-gadget-shield-320x180.png). Verified: banner extracted from
the built APK (both drawable entries) is pixel-identical to the final
export (0 differing px); apksigner verify passed (v2 + v3); build
desktop checks pass. APK: muse-gadget-shield-prototype.apk, SHA-256
`954b884170e2b3f63faaca90529d4517c04e434d01a404f1a457dd3345fddbb9`.

**Installed 2026-11-21 23:52 EST** at Jonathan's direction:
`install -r` over v0.11.4 (vc31) via the tailnet lane (127.0.0.1:5556).
On-device read-back: versionName 0.11.5 / versionCode 32. MainActivity
relaunched after install; GadgetService foreground; logcat confirms
`Link session registered as homelink-fa76f8` and fetch_vms HTTP 200.
The installed base.apk pulled back from the device is hash-identical to
the staged APK above, so its tv_banner resources are the final export
byte-for-byte. HOME sent at the end of the install handling.

## v0.11.6 (2026-11-22) — TV banner adopts the Google TV template packaging

Banner-packaging-only release (versionCode 33), at Jonathan's order
("Adopt", 2026-11-22 00:15 EST) after the gap analysis against the
official Google TV Figma template (@tv, "TV App Icon Template
[Android TV & Google TV]"). The banner art is unchanged (final layout:
small logo left, "Muse Gadget" on one line); what changed is the
resource structure, now the template's:

- Legacy ladder: `tv_banner.png` in drawable-mdpi (160x90), drawable-hdpi
  (240x135), drawable-xhdpi (320x180), drawable-xxhdpi (480x270) and
  drawable-xxxhdpi (640x360), plus the kept unqualified drawable copy.
- Adaptive set: `drawable-anydpi-v26/tv_banner.xml` (`<adaptive-icon>`
  with `@drawable/tv_banner_background` — solid #F0F2F5 — and
  `@drawable/tv_banner_foreground` — logo + name on transparent, both
  in drawable-xxxhdpi). minSdk is 26, so the adaptive banner is the
  resolved banner on the Shield (API 30); the flat PNGs remain the
  fallback for pre-26 / non-adaptive launchers.

Exports come from `~/workspace/muse-app-logos/build_banners.py`, which
now emits the full set from the same composition (fg-over-bg verified
to reproduce the master exactly). Verified: every banner entry
extracted from the built APK is pixel-identical to its export
(0 differing px, all sizes + both layers); aapt2 badging shows the
application banner resolving to `res/drawable-anydpi-v26/tv_banner.xml`;
apksigner verify passed (v2 + v3); build desktop checks pass
(SELFTEST + FETCHVMS PARSE + DESKTOP CHECKS PASS). APK:
muse-gadget-shield-prototype.apk, SHA-256
`36a87541a9ca8b92e501b1f91fd19bf421f2d14d183ac1c3298dedc0d5e1288a`.

**Installed 2026-11-22** at Jonathan's direction: `install -r` over
v0.11.5 (vc32) via the tailnet lane (127.0.0.1:5556). On-device
read-back: versionName 0.11.6 / versionCode 33. MainActivity relaunched
after install; GadgetService foreground; logcat confirms
`Link session registered as homelink-fa76f8`. The installed base.apk
pulled back from the device is hash-identical to the staged APK above.
HOME sent at the end of the install handling.

## v0.11.7 (2026-11-22) — TV banner rollback: adaptive wrapper removed (bitmap banner restored)

The v0.11.6 template adoption made `android:banner` resolve to the
`drawable-anydpi-v26/tv_banner.xml` `<adaptive-icon>` on API 26+. On
the Shield, Projectivy renders an adaptive banner drawable with the
AdaptiveIconDrawable safe-zone crop: agy's measured consult
(`~/workspace/muse-app-logos/agy-banner-consult/agy-report.md`) found
an isotropic ~1.50x center-zoom losing ~16.7% on every edge (only the
center ~66.7% visible), clipping the M's left peak and truncating the
name in Projectivy's favorites row, while the stock launcher and the
pre-adoption flat-PNG banners rendered uncropped in the identical
slot. Root-cause fix, artwork unchanged: the anydpi-v26 banner XML
and its `tv_banner_foreground` / `tv_banner_background` layers are
deleted; `android:banner` again resolves to the plain bitmap ladder
(drawable-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi + unqualified drawable,
all pixel-identical to the `~/workspace/muse-app-logos/banners/`
exports, 0 differing px). The launcher adaptive icons
(mipmap-anydpi-v26) are untouched.

Verified: aapt2 badging shows the application + leanback banner
resolving to `res/drawable-mdpi-v4/tv_banner.png` (a bitmap, not the
anydpi XML); apksigner verify passed (v2 + v3); build desktop checks
pass. APK: muse-gadget-shield-prototype.apk, SHA-256
`5b3ff18e3b96a38d0900a8b54c4dcfb4f3298359622f42f51807736c04f4eb4b`.

**Installed 2026-11-22**: `install -r` over v0.11.6 (vc33) via the
tailnet lane (127.0.0.1:5556). On-device read-back: versionName
0.11.7 / versionCode 34; installed base.apk hash-identical to the
staged APK. Rendering verified on the TV after a `pm trim-caches` +
reboot cycle (Projectivy's favorites-row card cache): the focused
"Muse Gadget" card now shows the complete M, badge, and name with
margins, focused and unfocused
(`~/workspace/muse-app-logos/banner-fix-2026-11-22/bitmap-fix-gadget-focused.png`).
GadgetService relaunched after the reboot; logcat confirms
`Link session registered as homelink-fa76f8`.
