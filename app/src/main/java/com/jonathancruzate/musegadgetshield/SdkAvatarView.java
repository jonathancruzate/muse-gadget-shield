package com.jonathancruzate.musegadgetshield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * The Muse Gadget SDK's avatar system, ported to Android —
 * with the character redrawn as the PAIRED Muse's own avatar
 * (v0.8.0).
 *
 * Whose face this is (Jonathan's order, 2026-11-09 21:20 EST —
 * "avatar has to use the current users who's paired avatar not
 * the default let the gadget sdk"): the SDK's DEFAULT character
 * (Meta's furry hood creature) must never be drawn here. The
 * SDK's own recipe for exactly this replacement is
 * esp32/tools/muse/AVATAR_RECIPE.md + avatar_prompt.md: "Your
 * Muse has its own avatar, and it can redraw that avatar as a
 * new muse_pixel.c... The default avatar is Meta's character."
 * On ESP32 the custom renderer replaces the default in place,
 * keeping the whole animation system; v0.8.0 ports that recipe
 * to this Android renderer. The paired Muse is Stef, so the
 * character below is STEF, designed from her active Muse
 * avatar (big smile, red lipstick, long dark wavy hair, black
 * cutout top, gold "Stephany" nameplate necklace): a human
 * woman at 64x64 — warm skin, long dark hair framing the face
 * where the fur hood used to be, brown eyes with lashes and
 * thick brows, red lips that articulate when speaking, blush,
 * a black top at the base, and the gold nameplate at her neck.
 * Do not bring the SDK default creature back without
 * Jonathan's say-so.
 *
 * What the SDK's avatar system is (esp32/avatar/muse_pixel.c +
 * esp32/components/muse/muse_pixel.h, muse_state.h,
 * muse_speaking.h; docs in esp32/docs/speaking-animations.md):
 * a PROCEDURAL pixel-art renderer. There are no image assets and
 * nothing is server-driven: each frame, a pose — mode, time in
 * mode, live audio level — is rendered fresh on a 64x64 grid
 * with a small palette, ordered (Bayer) dithering and hard
 * outlines, then blown up nearest-neighbour so the pixels stay
 * chunky.
 *
 * This view keeps the v0.7.0 faithful port of that system
 * intact — only the character's geometry and fixed palette
 * changed in v0.8.0:
 *   - the same modes (BOOT/IDLE/LISTENING/THINKING/SPEAKING/
 *     ERROR/OFF) with the same per-mode colour schemes and
 *     accents, blended at the SDK's rate — plus one mode of
 *     our own (v0.9.3): MUTED, the voice-mode mic toggle the
 *     SDK's push-to-talk hardware never needed, rendered as
 *     the calm IDLE character under a grey scheme with a
 *     mic-off glyph beside the head (drawMic) — and since
 *     v0.9.4 LISTENING draws the same glyph at the same
 *     anchor WITHOUT the slash, so the mic pair carries
 *     both states and no badge rectangle remains;
 *   - the same analytic-part construction (superellipse mass —
 *     dome exponent 2.7 on top, 3.6 at the base — inner face
 *     panel, rotated-ellipse limbs, ground shadow), redrawn:
 *     the superellipse is now her hair, the face panel is a
 *     taller tapered human face with a hairline and side locks,
 *     the limbs are black-top sleeves, and the base is her
 *     shoulders/top with the neck, cutout and nameplate;
 *   - the same idle behaviour: breathing, blinking (occasional
 *     double-blink), wandering gaze with per-mode gaze targets;
 *   - the same per-mode dressing: aura glow, expanding dotted
 *     rings and sound waves while listening/speaking, thought
 *     dots while thinking, sparkles, an alert mark on error;
 *   - the speaking choreography ({@link SpeakingChoreo}, the
 *     muse_speaking.h port): gesture cycle, mouth articulation
 *     from speech energy, and the silent caption-paced rhythm
 *     when no audio energy exists — exactly the SDK's fallback;
 *   - the 0.28 s hand-pose blend when entering/leaving SPEAKING.
 *
 * Simplifications vs the C renderer (visual only): float math
 * instead of the fixed-point LUTs (invisible at 64 px, and this
 * SoC has an FPU), and the dimmed cell-edge grid texture of
 * muse_pixel_scale() is not reproduced. The background cell is
 * pure black — the voice screen's background (v0.9.2: actual
 * #000000, no gradient, no blue) — so the avatar sits in the
 * screen seamlessly.
 *
 * Hosts drive it with setMode()/setLevel(); frames run at
 * ~30 fps while the view is attached and running.
 */
public class SdkAvatarView extends View {
    // MUTED (v0.9.3) is appended AFTER Off on purpose: SCHEMES is
    // indexed by ordinal, and the existing modes must not shift.
    // MUTED is this app's own addition (the SDK has no mic-mute
    // state — its hardware model is push-to-talk + a speaker
    // toggle): the character renders calm like IDLE under a
    // desaturated grey scheme, and a mic-off glyph drawn beside
    // the head carries the state — no badge, no text (Jonathan's
    // order, 2026-11-09 23:35 EST).
    public enum Mode { BOOT, IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFF, MUTED }

    private static final int W = 64, H = 64;
    private static final float TAU = 6.2831853f;

    // Palette indices, mirroring muse_pixel.c's enum roles.
    // v0.8.0 repurposes the body tones as Stef's hair tones and
    // adds four roles past the SDK's 29 (still under the recipe's
    // 32-entry cap): lipstick, the nameplate gold, and her black
    // top (dark + lit).
    private static final int C_BG = 0, C_OUT = 1, C_OUT2 = 2, C_BD = 3, C_BM = 4,
            C_BL = 5, C_BH = 6, C_RIM = 7, C_SKIND = 8, C_SKIN = 9, C_SKINL = 10,
            C_IRIS = 11, C_SHINE = 12, C_BROW = 13, C_BLUSH = 14, C_BLUSHD = 15,
            C_MOUTH = 16, C_TONGUE = 17, C_G0 = 18, C_G1 = 19, C_G2 = 20,
            C_G3 = 21, C_AURA1 = 22, C_AURA2 = 23, C_SPK = 24, C_ACC = 25,
            C_SHADOW = 26, C_HEART = 27, C_WHITE = 28, C_LIP = 29,
            C_GOLD = 30, C_TOP = 31, C_TOPL = 32, C_COUNT = 33;

    private static final int M_NONE = 0, M_BODY = 1, M_ARM = 2, M_FOOT = 3, M_FACE = 4;

    /** Per-mode glow ramp (bright -> deep) and accent, from the SDK. */
    private static final int[][] SCHEMES = {
            {0xffffff, 0xcfe0ff, 0x8fa8ff, 0x5a5fe0, 0xa9c0ff}, // BOOT
            {0xf4e8ff, 0xc7a4ff, 0x9a6bff, 0x5b3fd9, 0xa77dff}, // IDLE
            {0xe8faff, 0x8fdcff, 0x3fa2ff, 0x2a5bd7, 0x5cb8ff}, // LISTENING
            {0xffe6ff, 0xff9cf0, 0xd35bff, 0x7a2bd9, 0xe07bff}, // THINKING
            {0xeafff4, 0x9ff5cf, 0x3fd9a0, 0x1f9a7a, 0x6ff0bf}, // SPEAKING
            {0xffd6d6, 0xff6b6b, 0xc7304a, 0x6b1a3a, 0xff5c5c}, // ERROR
            {0xd8d4ff, 0x8f86d9, 0x5a4fb0, 0x2e2870, 0x7c72d0}, // OFF
            {0xf0f0f5, 0xb0b0c0, 0x74748a, 0x46465a, 0x9a9ab0}, // MUTED (v0.9.3: desaturated greys)
    };

    /**
     * Stef's FIXED palette (v0.8.0, replacing the SDK default
     * creature's cream fur / peach face): near-black brown hair
     * tones (BD darkest -> BH sheen), warm medium skin, dark
     * brown iris, near-black lash/outline, her red lipstick and
     * the gold of the nameplate necklace.
     */
    private static final int[] FIXED = new int[C_COUNT];
    static {
        FIXED[C_OUT] = 0x1c1214;
        FIXED[C_OUT2] = 0x2b1d21;
        FIXED[C_BD] = 0x241a20;
        FIXED[C_BM] = 0x35262c;
        FIXED[C_BL] = 0x4d3740;
        FIXED[C_BH] = 0x71525e;
        FIXED[C_SKIND] = 0xd99a6c;
        FIXED[C_SKIN] = 0xeab086;
        FIXED[C_SKINL] = 0xf7cf9f;
        FIXED[C_IRIS] = 0x5a3418;
        FIXED[C_SHINE] = 0xffffff;
        FIXED[C_BROW] = 0x2e1c12;
        FIXED[C_BLUSH] = 0xf0a18e;
        FIXED[C_BLUSHD] = 0xe58a76;
        FIXED[C_MOUTH] = 0x571e1c;
        FIXED[C_TONGUE] = 0xe86a7a;
        FIXED[C_SHADOW] = 0x16101f;
        FIXED[C_HEART] = 0xff4f8b;
        FIXED[C_WHITE] = 0xffffff;
        FIXED[C_LIP] = 0xd92632;
        FIXED[C_GOLD] = 0xd9a441;
        FIXED[C_TOP] = 0x1c1a20;
        FIXED[C_TOPL] = 0x322e38;
    }

    private static final int[][] BAYER4 = {
            {0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    private final int[] fb = new int[W * H];
    private final byte[] cellIdx = new byte[W * H];
    private final byte[] mask = new byte[W * H];
    private final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint();
    private final Rect destRect = new Rect();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SpeakingChoreo speech = new SpeakingChoreo();

    private Mode mode = Mode.IDLE;
    private boolean running;
    private long startUptimeMs = SystemClock.uptimeMillis();
    private long modeStartUptimeMs = startUptimeMs;
    private long lastFrameUptimeMs;
    private float levelTarget;
    private float level;

    // Blended scheme (f0..f3, acc), as rgb floats 0..255.
    private final float[][] scheme = new float[5][3];
    private boolean schemeInit;
    private volatile int accentColor = 0xffa77dff;

    // Idle eyes (the SDK's eyes_t).
    private float nextBlink = 1.5f, blinkStart = -10f, nextGaze = 1.0f;
    private float gx, gy, tgx, tgy;
    private int rngState = 0x9e3779b9;

    // Arm transition blend (entering/leaving SPEAKING).
    private final float[][] prevArms = new float[2][3];
    private final float[][] fromArms = new float[2][3];
    private Mode prevMode;
    private float prevT = -1;
    private float armTransition = 1;

    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            renderFrame();
            invalidate();
            handler.postDelayed(this, 33);
        }
    };

    public SdkAvatarView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
    }

    /** Switch avatar mode (the SDK's muse_state_set_mode). */
    public void setMode(Mode next) {
        if (next == mode) return;
        mode = next;
        modeStartUptimeMs = SystemClock.uptimeMillis();
    }

    public Mode getMode() { return mode; }

    /** Live audio level 0..1 (mic while listening, playback while speaking). */
    public void setLevel(float value) {
        levelTarget = Math.max(0f, Math.min(1f, value));
    }

    /** The current mode's accent colour, blended (for surrounding UI). */
    public int accentColor() { return accentColor; }

    public void setRunning(boolean run) {
        if (run == running) return;
        running = run;
        if (run) {
            lastFrameUptimeMs = 0;
            handler.post(frameLoop);
        } else {
            handler.removeCallbacks(frameLoop);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setRunning(true);
    }

    @Override protected void onDetachedFromWindow() {
        setRunning(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size) / 2;
        destRect.set(left, top, left + size, top + size);
        canvas.drawBitmap(bitmap, null, destRect, paint);
    }

    // ------------------------------------------------------------ frame

    private void renderFrame() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameUptimeMs > 0
                ? clamp((now - lastFrameUptimeMs) / 1000f, 0, 0.2f) : 0.04f;
        lastFrameUptimeMs = now;
        float t = (now - startUptimeMs) / 1000f;
        float modeT = (now - modeStartUptimeMs) / 1000f;
        level += (levelTarget - level) * clamp(dt * 14, 0, 1);

        speech.update(mode == Mode.SPEAKING, dt, modeT, level);
        int[] pal = updatePalette(dt);
        float blink = eyesUpdate(t, dt, modeT);

        int bg = 0xff000000; // pure black — the v0.9.2 voice screen
        for (int i = 0; i < fb.length; i++) {
            fb[i] = bg;
            cellIdx[i] = C_BG;
        }

        float happy = 0f;
        float fade = mode == Mode.OFF ? clamp(1 - modeT / 1.3f, 0, 1) : 1f;

        // Body motion.
        float bob, lean = 0;
        switch (mode) {
            case LISTENING: bob = (float) Math.sin(t * 3.0) * 0.6f; break;
            case THINKING: bob = (float) Math.sin(t * 2.4) * 0.8f; lean = (float) Math.sin(t * 1.3) * 1.2f; break;
            case SPEAKING:
                bob = (float) Math.sin(t * 2.5) * 0.25f - speech.energy * 0.65f;
                lean = (speech.lift[1] - speech.lift[0]) * 0.65f;
                break;
            case ERROR: bob = 1.0f; lean = (float) Math.sin(t * 18.0) * (modeT < 0.6f ? 1.0f : 0.0f); break;
            default: bob = (float) Math.sin(t * 1.8) * 1.0f; break;
        }
        float hop = 0;
        float pop = mode == Mode.BOOT ? clamp(modeT / 0.6f, 0, 1) : 1f;
        float squash = 1 - (1 - pop) * 0.35f + (float) Math.sin(pop * 3.1416f) * 0.06f;
        float breathe = (float) Math.sin(t * 2.0 + 1.0) * 0.03f;

        float ja = 16 * (1 + breathe) * (2 - squash) + level * 0.8f;
        float jb = 23 * (1 - breathe) * squash;
        float jcx = 32 + lean;
        float jcy = 56.5f - jb + bob * 0.5f - hop;
        // Stef's face panel: a human face is taller than the
        // default creature's wide peach panel — narrower across
        // the cheeks, much longer chin-to-hairline (v0.8.0).
        float jfa = ja * 0.62f;
        float jfb = 10.6f * squash;
        float jfx = jcx + lean * 0.3f;
        float jfy = jcy - jb * 0.30f + bob * 0.3f;
        if (mode == Mode.SPEAKING) jfy += speech.nod * 0.8f;

        // Background layers.
        float auraR = 29 + level * 4 + (float) Math.sin(t * 1.5) * 1.0f;
        float auraS = (0.75f * (mode == Mode.BOOT ? clamp(modeT / 1.4f, 0, 1) : 1f)
                + level * 0.4f) * fade;
        drawAura(pal, jcx, jcy - 3, auraR, auraS);
        if (mode == Mode.LISTENING) drawRings(pal, jcx, jfy + 2, t, level, 0.9f);
        else if (mode == Mode.SPEAKING) drawRings(pal, jcx, jfy + 2, t, level, 0.6f);
        drawShadow(pal, jcx, 58.5f, 13 - hop * 0.8f);
        float spkSpeed = mode == Mode.THINKING ? 2.8f : mode == Mode.LISTENING ? 1.2f
                : mode == Mode.SPEAKING ? 1.5f : 0.6f;
        int spkCount = mode == Mode.BOOT ? (int) (clamp(modeT / 1.4f, 0, 1) * 6) : (int) (6 * fade);
        drawSparkles(pal, jcx, jcy, false, spkSpeed, spkCount, t);

        // Limbs.
        float base = jcy + jb;
        float[][] feet = {
                {jcx - 7, base - 0.5f, -0.15f},
                {jcx + 7, base - 0.5f, 0.15f}};
        float[][] arms = new float[2][3];
        float adx = ja + 0.3f;
        float ay = jcy + 4;
        switch (mode) {
            case LISTENING:
                arms[0] = new float[]{jcx - adx + 1, jfy + 5, 0.55f};
                arms[1] = new float[]{jcx + adx - 1, jfy + 5, -0.55f};
                break;
            case THINKING:
                arms[0] = new float[]{jcx - adx, ay, -0.35f};
                arms[1] = new float[]{jcx + 7.5f, jfy + jfb + 0.5f, -1.1f};
                break;
            case OFF: {
                float w = (float) Math.sin(t * 12) * 0.35f * fade;
                arms[0] = new float[]{jcx - adx, ay, -0.3f};
                arms[1] = new float[]{jcx + adx + 1, jcy - 4, -2.3f - w};
                break;
            }
            case SPEAKING:
                for (int i = 0; i < 2; i++) {
                    float side = i == 1 ? 1 : -1;
                    arms[i] = new float[]{
                            jcx + side * (adx + speech.extend[i] * 3.2f - speech.inward[i] * 8.5f),
                            ay - speech.lift[i] * 10 - speech.extend[i] * 2,
                            side * (0.30f + speech.lift[i] * 1.05f) + speech.wrist[i]};
                }
                break;
            default: {
                float sway = (float) Math.sin(t * 1.8 + 0.6) * 0.08f;
                arms[0] = new float[]{jcx - adx, ay, -0.3f + sway};
                arms[1] = new float[]{jcx + adx, ay, 0.3f - sway};
                break;
            }
        }
        // 0.28 s hand blend when entering/leaving SPEAKING.
        if (prevT > 0 && t - prevT < 0.25f && mode != prevMode
                && (mode == Mode.SPEAKING || prevMode == Mode.SPEAKING)) {
            for (int i = 0; i < 2; i++) System.arraycopy(prevArms[i], 0, fromArms[i], 0, 3);
            armTransition = 0;
        }
        if (t - prevT > 0.25f) armTransition = 1;
        if (armTransition < 1) {
            armTransition = clamp(armTransition + dt / 0.28f, 0, 1);
            float blend = ease(armTransition);
            for (int i = 0; i < 2; i++) {
                for (int k = 0; k < 3; k++) {
                    arms[i][k] = fromArms[i][k] + (arms[i][k] - fromArms[i][k]) * blend;
                }
            }
        }
        for (int i = 0; i < 2; i++) System.arraycopy(arms[i], 0, prevArms[i], 0, 3);
        prevMode = mode;
        prevT = t;

        drawAvatar(pal, jcx, jcy, ja, jb, jfx, jfy, jfa, jfb, arms, feet);

        // Face (Stef's features on the taller panel, v0.8.0):
        // eyes a little above centre, thick brows above them,
        // a small nose shadow, blush on the cheeks, and the red
        // lips low on the face so the mouth has room to open.
        float eyeY = jfy - 1.5f;
        float eyeDx = jfa * 0.50f;
        int style = 0; // 0 normal, 1 wide, 2 happy, 3 X
        float open = 1 - blink;
        int mouth = 0; // 0 smile, 1 O, 2 hmm, 3 talk, 4 grin, 5 flat
        float mouthOpen = 0;
        switch (mode) {
            case BOOT: open = modeT < 0.9f ? 0 : clamp((modeT - 0.9f) / 0.3f, 0, 1); break;
            case LISTENING: style = 1; mouth = 1; break;
            case THINKING: open *= 0.85f; mouth = 2; break;
            case SPEAKING:
                mouth = 3;
                mouthOpen = speech.mouth;
                if (mouthOpen > 0.16f && mouthOpen < 0.55f
                        && (!speech.heardVoice || level < speech.energy * 0.8f)) mouth = 1;
                if (speech.eyeWide > 0.4f) style = 1;
                break;
            case ERROR: style = 3; mouth = 5; break;
            case OFF: open = clamp((1 - modeT / 1.0f) * 1.5f, 0, 1); break;
            default: break;
        }
        float gazeX = gx;
        if (mode == Mode.SPEAKING) gazeX = clamp(gazeX + speech.gaze, -1, 1);
        drawEye(pal, jfx - eyeDx, eyeY, open, style, gazeX, gy);
        drawEye(pal, jfx + eyeDx, eyeY, open, style, gazeX, gy);

        // Her thick dark brows: a 5 px arc over each eye. The
        // per-mode expression is a raise/tilt of the same arcs
        // (thinking cocks one brow, listening raises both,
        // speaking follows the choreography's brow value).
        int browY = iround(eyeY) - 5;
        int leftRaise = 0, rightRaise = 0;
        if (mode == Mode.THINKING) {
            leftRaise = 0; rightRaise = 1;
        } else if (mode == Mode.LISTENING) {
            leftRaise = 1; rightRaise = 1;
        } else if (mode == Mode.SPEAKING) {
            leftRaise = rightRaise = iround(speech.brow * 2);
        }
        drawBrow(pal, iround(jfx - eyeDx), browY - leftRaise, false);
        drawBrow(pal, iround(jfx + eyeDx), browY - rightRaise, true);

        // Small nose shadow between/below the eyes.
        px(pal, iround(jfx) + 1, iround(eyeY + 2), C_SKIND);
        px(pal, iround(jfx), iround(eyeY + 3), C_SKIND);

        float blush = 0.55f + (mode == Mode.SPEAKING ? 0.15f : 0f);
        drawBlush(pal, iround(jfx - jfa * 0.74f), iround(eyeY + 3), blush);
        drawBlush(pal, iround(jfx + jfa * 0.74f), iround(eyeY + 3), blush);
        drawMouth(pal, iround(jfx), iround(eyeY + 6), mouth, mouthOpen);

        // The gold "Stephany" nameplate at her neck — a chain
        // arc over the top's cutout with a bright pendant row
        // and one pink heart pixel at its centre (v0.8.0).
        drawNecklace(pal, jfx, jfy + jfb + 2.5f);

        // Foreground.
        drawSparkles(pal, jcx, jcy, true, spkSpeed, spkCount, t);
        float top = jcy - jb;
        if (mode == Mode.LISTENING || mode == Mode.SPEAKING) {
            drawWaves(pal, jcx, jfy + 2, ja, level, t);
        }
        if (mode == Mode.THINKING) drawThoughtDots(pal, jcx + 14, top + 2, t);
        if (mode == Mode.ERROR) drawAlert(pal, iround(jcx + 18), iround(top - 1));
        if (mode == Mode.MUTED) drawMic(pal, iround(jcx + 20), iround(jfy + 1), true);
        if (mode == Mode.LISTENING) drawMic(pal, iround(jcx + 20), iround(jfy + 1), false);

        bitmap.setPixels(fb, 0, W, 0, 0, W, H);
    }

    // ------------------------------------------------------------ palette

    private int[] updatePalette(float dt) {
        int modeIdx = mode.ordinal();
        int[] target = SCHEMES[modeIdx];
        float k = schemeInit ? 1 - (float) Math.exp(-dt * 7.0) : 1f;
        for (int i = 0; i < 5; i++) {
            float[] rgb = hexToRgb(target[i]);
            if (!schemeInit) {
                scheme[i] = rgb;
            } else {
                for (int c = 0; c < 3; c++) scheme[i][c] += (rgb[c] - scheme[i][c]) * k;
            }
        }
        schemeInit = true;
        float[][] palF = new float[C_COUNT][3];
        for (int i = 0; i < C_COUNT; i++) palF[i] = hexToRgb(FIXED[i]);
        for (int i = 0; i < 4; i++) palF[C_G0 + i] = scheme[i];
        palF[C_ACC] = scheme[4];
        palF[C_RIM] = mix(palF[C_BL], scheme[4], 0.45f);
        palF[C_AURA1] = scale(scheme[4], 0.16f);
        palF[C_AURA2] = scale(scheme[4], 0.34f);
        palF[C_SPK] = mix(scheme[4], palF[C_WHITE], 0.45f);
        int[] pal = new int[C_COUNT];
        for (int i = 0; i < C_COUNT; i++) pal[i] = toArgb(palF[i]);
        accentColor = pal[C_ACC];
        return pal;
    }

    // ------------------------------------------------------------ eyes

    private float frand() {
        rngState ^= rngState << 13;
        rngState ^= rngState >>> 17;
        rngState ^= rngState << 5;
        return (rngState & 0xffffff) / (float) 0x1000000;
    }

    private float eyesUpdate(float t, float dt, float modeT) {
        if (t >= nextBlink) {
            blinkStart = t;
            nextBlink = t + (frand() < 0.2f ? 0.28f : 2.2f + frand() * 3.0f);
        }
        if (t >= nextGaze) {
            nextGaze = t + 1.2f + frand() * 2.4f;
            if (frand() < 0.35f) {
                tgx = 0; tgy = 0;
            } else {
                tgx = frand() * 2 - 1;
                tgy = (frand() * 2 - 1) * 0.6f;
            }
        }
        float tx = tgx, ty = tgy;
        switch (mode) {
            case LISTENING: tx = 0; ty = 0.1f; break;
            case THINKING:
                tx = 0.75f * (float) Math.sin(modeT * 1.3) + 0.25f;
                ty = -0.85f;
                break;
            case SPEAKING: tx *= 0.3f; ty = 0; break;
            default: break;
        }
        float k = 1 - (float) Math.exp(-dt * 14.0);
        gx += (tx - gx) * k;
        gy += (ty - gy) * k;
        float bt = (t - blinkStart) / 0.16f;
        if (bt < 0 || bt > 1) return 0;
        return 1 - Math.abs(bt * 2 - 1);
    }

    // ------------------------------------------------------------ layers

    private void drawAura(int[] pal, float cx, float cy, float radius, float strength) {
        int x0 = (int) (cx - radius - 1), x1 = (int) (cx + radius + 1);
        int y0 = (int) (cy - radius - 1), y1 = (int) (cy + radius + 1);
        for (int y = y0; y <= y1; y++) {
            float dy = (y + 0.5f - cy) * 1.1f;
            if (dy * dy >= radius * radius) continue;
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx;
                float d2 = dx * dx + dy * dy;
                if (d2 >= radius * radius) continue;
                float i = (1 - (float) Math.sqrt(d2 / (radius * radius))) * strength;
                float b = bayer(x, y);
                if (i > 0.55f + b * 0.35f) px(pal, x, y, C_AURA2);
                else if (i > b * 0.9f) px(pal, x, y, C_AURA1);
            }
        }
    }

    private void drawRings(int[] pal, float cx, float cy, float t, float lvl, float speed) {
        for (int k = 0; k < 2; k++) {
            float ph = frac(t * speed + k * 0.5f);
            float r = 20 + ph * 11;
            float fade = (1 - ph) * (0.35f + lvl);
            int n = (int) (r * 2.2f);
            for (int i = 0; i < n; i++) {
                float a = i * TAU / n;
                int x = iround(cx + (float) Math.cos(a) * r);
                int y = iround(cy + (float) Math.sin(a) * r * 0.92f);
                int cur = getCell(x, y);
                if (cur == C_BG || cur == C_AURA1) {
                    if (bayer(x, y) < fade) setCell(pal, x, y, fade > 0.6f ? C_ACC : C_AURA2);
                }
            }
        }
    }

    private void drawShadow(int[] pal, float cx, float y, float halfW) {
        for (int row = 0; row < 3; row++) {
            float hw = halfW * (row == 1 ? 1.0f : 0.72f);
            for (int x = iround(cx - hw); x <= iround(cx + hw); x++) {
                float edge = Math.abs(x + 0.5f - cx) / hw;
                if (bayer(x, (int) y + row) > edge * 0.8f) px(pal, x, (int) y + row, C_SHADOW);
            }
        }
    }

    private void drawSparkles(int[] pal, float cx, float cy, boolean front,
            float speed, int count, float t) {
        for (int i = 0; i < count; i++) {
            float a = t * speed + i * TAU / count;
            float s = (float) Math.sin(a);
            if ((s > 0) != front) continue;
            float rr = 25 + 2 * (float) Math.sin(i * 1.9f + t * 0.7f);
            int x = iround(cx + (float) Math.cos(a) * rr);
            int y = iround(cy - 3 + s * rr * 0.42f);
            float tw = 0.5f + 0.5f * (float) Math.sin(t * 5.0 + i * 1.7f);
            drawSparkle(pal, x, y, tw, front);
        }
    }

    private void drawSparkle(int[] pal, int x, int y, float twinkle, boolean front) {
        int arm = front ? C_ACC : C_AURA2;
        int core = front ? C_WHITE : C_SPK;
        if (twinkle > 0.8f) {
            px(pal, x, y, core);
            for (int k = 1; k <= 2; k++) {
                int c = k == 1 ? (front ? C_SPK : arm) : arm;
                px(pal, x + k, y, c); px(pal, x - k, y, c);
                px(pal, x, y + k, c); px(pal, x, y - k, c);
            }
        } else if (twinkle > 0.45f) {
            px(pal, x, y, front ? C_SPK : arm);
            px(pal, x + 1, y, arm); px(pal, x - 1, y, arm);
            px(pal, x, y + 1, arm); px(pal, x, y - 1, arm);
        } else if (twinkle > 0.15f) {
            px(pal, x, y, arm);
        }
    }

    private void drawWaves(int[] pal, float cx, float cy, float bodyRx, float lvl, float t) {
        int n = 1 + (int) (clamp(lvl, 0, 1) * 3.2f);
        if (n > 3) n = 3;
        for (int k = 0; k < n; k++) {
            float r = bodyRx + 5 + k * 3.0f;
            int span = 2 + k;
            float flick = 0.5f + 0.5f * (float) Math.sin(t * 12.0 - k * 1.4f);
            for (int side = -1; side <= 1; side += 2) {
                for (int j = -span; j <= span; j++) {
                    float xo = r - (float) (j * j) / (2.0f * r) * 3.0f;
                    int x = iround(cx + side * xo);
                    int y = iround(cy - 2 + j);
                    if (k == 0 || bayer(x, y) < 0.35f + flick * 0.65f) {
                        px(pal, x, y, k == 0 ? C_ACC : (k == 1 ? C_G1 : C_G2));
                    }
                }
            }
        }
    }

    private void drawThoughtDots(int[] pal, float x, float y, float t) {
        int active = (int) (frac(t * 1.6f) * 3.0f);
        for (int i = 0; i < 3; i++) {
            int bx = iround(x + i * 4);
            int by = iround(y - i * 3) - (i == active ? 1 : 0);
            int c = i == active ? C_G0 : C_ACC;
            px(pal, bx, by, c); px(pal, bx + 1, by, c);
            px(pal, bx, by + 1, c); px(pal, bx + 1, by + 1, i == active ? C_G1 : C_G2);
        }
    }

    private void drawAlert(int[] pal, int x, int y) {
        String[] bang = {".##.", ".##.", ".##.", ".##.", "....", ".##."};
        stamp(pal, bang, x - 2, y, C_ACC, C_ACC);
    }

    /**
     * The mic glyph pair (v0.9.3 MUTED — Jonathan's order: the
     * state is carried by a drawn glyph "instead of the
     * rectangle and text"; v0.9.4 LISTENING — the same stamp
     * WITHOUT the slash, so the pair reads as one mic in one
     * place with the slash appearing/disappearing as the mic
     * toggles). A 13x14 stamp in the same pixel language as
     * drawAlert / drawWaves, centred beside the face where the
     * waves sit for SPEAKING: the mic capsule in the scheme's
     * bright tone (C_G0), its cradle, stem and base in the mid
     * tone (C_G2), and — only when off — the slash over it in
     * her lipstick red (C_LIP — the palette's red, and the
     * universal mute colour). The colours resolve through the
     * active mode's scheme, so LISTENING shows the glyph in
     * its normal colours and MUTED in the grey scheme. The
     * SDK ships no mic-mute asset (checked 2026-11-09: its
     * only mute is the StopWatch face's speaker toggle, drawn
     * in firmware); the StopWatch muted icon is the reading
     * reference — a plain silhouette with a slash, legible at
     * a glance.
     */
    private void drawMic(int[] pal, int cx, int cy, boolean off) {
        String[] mic = {
                "....#####....",
                "...#######...",
                "...#######...",
                "...#######...",
                "...#######...",
                "...#######...",
                "....#####....",
                "..o.......o..",
                "..o.......o..",
                "...o.....o...",
                "....ooooo....",
                "......o......",
                "......o......",
                "....ooooo...."};
        int x0 = cx - 6, y0 = cy - 7;
        stamp(pal, mic, x0, y0, C_G0, C_G2);
        if (!off) return;
        // The slash (mic-off only): top-left to bottom-right,
        // 2 px thick, over the whole glyph (drawn last, on
        // top of the mic).
        for (int r = 0; r < mic.length; r++) {
            int c = 1 + iround(r * 10.0f / (mic.length - 1));
            px(pal, x0 + c, y0 + r, C_LIP);
            px(pal, x0 + c + 1, y0 + r, C_LIP);
        }
    }

    // ------------------------------------------------------------ avatar

    /**
     * Stef's body (v0.8.0, same construction as the SDK
     * renderer's drawAvatar, new character): the superellipse
     * mass is her long dark hair; inside it the taller face
     * panel, narrowed toward the chin, with an arched hairline
     * (higher at the centre part) and side locks overlapping
     * the cheeks; below the chin a short skin neck, then her
     * black top with the skin cutout wedge at the centre. The
     * limbs are her sleeves in the black top, and the "feet"
     * ellipses land in the top's base as shoulder weight.
     */
    private void drawAvatar(int[] pal, float cx, float cy, float a, float b,
            float fx, float fy, float fa, float fb, float[][] arms, float[][] feet) {
        for (int i = 0; i < mask.length; i++) mask[i] = M_NONE;
        int ox = iround(cx), oy = iround(cy);
        int x0 = Math.max(0, (int) (cx - a * 1.1f - 8)), x1 = Math.min(W - 1, (int) (cx + a * 1.1f + 8));
        int y0 = Math.max(0, (int) (cy - b - 8)), y1 = Math.min(H - 1, (int) (cy + b + 5));
        for (int i = 0; i < 2; i++) {
            x0 = Math.min(x0, (int) (arms[i][0] - 6)); x1 = Math.max(x1, (int) (arms[i][0] + 6));
            y0 = Math.min(y0, (int) (arms[i][1] - 6)); y1 = Math.max(y1, (int) (arms[i][1] + 6));
        }
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(W - 1, x1); y1 = Math.min(H - 1, y1);
        float neckY = fy + fb;        // chin line
        float topY = fy + fb + 3.0f;  // where the black top starts
        for (int y = y0; y <= y1; y++) {
            float yc = y + 0.5f;
            float v = (yc - cy) / b;
            float aRow = a * (1 + 0.07f * clamp(v, -1, 1));
            double n = v < 0 ? 2.7 : 3.6;
            double vPow = Math.pow(Math.abs(v), n);
            float fv = clamp((yc - fy) / fb, -8, 8);
            double fv4 = Math.min(Math.pow(fv, 4), 16);
            for (int x = x0; x <= x1; x++) {
                float xc = x + 0.5f;
                // Arms sit in front of the body — hers are
                // sleeves of the black top (v0.8.0).
                boolean isArm = false;
                for (int ai = 0; ai < 2 && !isArm; ai++) {
                    float[] l = limbLocal(arms[ai], 2.9f, 5.2f, xc, yc);
                    if (l != null) {
                        mask[y * W + x] = M_ARM;
                        setCell(pal, x, y, l[1] < -0.25f ? C_TOPL : C_TOP);
                        isArm = true;
                    }
                }
                if (isArm) continue;
                float u = (xc - cx) / aRow;
                double field = Math.pow(Math.abs(u), n) + vPow;
                float tuft = ((hash16(x - ox, y - oy) - 32768) / 32768f) * 0.08f;
                if (Math.abs(v) <= 1.2 && field <= 1 + tuft) {
                    float fu = clamp((xc - fx) / fa, -3, 3);
                    // Chin taper: the face narrows below its
                    // centre line, like a real jaw.
                    float fuT = fv > 0.15f ? fu * (1 + (fv - 0.15f) * 0.38f) : fu;
                    double ff = Math.pow(fuT, 4) + fv4;
                    float bay = bayer(x, y);
                    // Hairline: an arch over the forehead, higher
                    // at the centre part; side locks fall over
                    // the outer cheeks.
                    boolean hairline = fv < -0.60f + 0.22f * Math.abs(fu);
                    boolean sideLock = Math.abs(fu) > 0.84f && fv < 0.45f
                            && ff <= 1.35f;
                    if (ff <= 1 && !hairline && !sideLock) {
                        mask[y * W + x] = M_FACE;
                        int c = C_SKIN;
                        if (fv < -0.55f && bay < (-fv - 0.45f) * 1.8f) c = C_SKIND;
                        else if (fu * fu * 1.4f + (fv + 0.15f) * (fv + 0.15f) < 0.32f
                                && bay < 0.55f) c = C_SKINL;
                        else if (fv > 0.75f && bay < 0.4f) c = C_SKIND;
                        setCell(pal, x, y, c);
                    } else {
                        mask[y * W + x] = M_BODY;
                        if (yc >= topY) {
                            // Her black top, with the skin cutout
                            // wedge at the centre chest.
                            float cut = 4.2f - (yc - topY) * 0.62f;
                            if (Math.abs(xc - fx) < cut && yc < topY + 6.5f) {
                                setCell(pal, x, y,
                                        Math.abs(xc - fx) < cut - 1.4f ? C_SKINL : C_SKIN);
                            } else {
                                setCell(pal, x, y,
                                        (u * -0.4f + v * -0.5f) > 0.15f ? C_TOPL : C_TOP);
                            }
                        } else if (yc >= neckY) {
                            // The short neck under the chin,
                            // shadowed at its sides.
                            setCell(pal, x, y,
                                    Math.abs(fu) < 0.42f ? C_SKIN
                                            : (ff < 1.3 ? C_SKIND : C_BD));
                        } else if (ff < 1.75 && (ff < 1.3 || bay < (1.75 - ff) * 1.4)) {
                            // Hair framing the face: the dark
                            // inner shadow ring around the panel.
                            setCell(pal, x, y, ff < 1.3 ? C_OUT2 : C_BD);
                        } else {
                            setCell(pal, x, y, hair(pal, u * 0.95f, v * 0.95f, x, y, ox, oy));
                        }
                    }
                    continue;
                }
                for (int fi = 0; fi < 2; fi++) {
                    float[] l = limbLocal(feet[fi], 4.4f, 2.6f, xc, yc);
                    if (l != null) {
                        mask[y * W + x] = M_FOOT;
                        setCell(pal, x, y, l[1] < -0.2f ? C_TOPL : C_TOP);
                        break;
                    }
                }
            }
        }
        // Hard outline on the silhouette and part seams.
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int m = mask[y * W + x];
                if (m == M_NONE || m == M_FACE) continue;
                for (int[] d : n4) {
                    int xx = x + d[0], yy = y + d[1];
                    int nn = (xx >= 0 && xx < W && yy >= 0 && yy < H) ? mask[yy * W + xx] : M_NONE;
                    if (nn == M_NONE || (m == M_ARM && (nn == M_BODY || nn == M_FACE))
                            || (m == M_BODY && nn == M_FOOT)) {
                        px(pal, x, y, C_OUT);
                        break;
                    }
                }
            }
        }
    }

    /** Local coords inside a rotated limb ellipse, or null outside. */
    private static float[] limbLocal(float[] limb, float rx, float ry, float pxX, float pxY) {
        float dx = pxX - limb[0], dy = pxY - limb[1];
        float r = Math.max(rx, ry);
        if (Math.abs(dx) > r || Math.abs(dy) > r) return null;
        float c = (float) Math.cos(limb[2]), s = (float) Math.sin(limb[2]);
        float u = (dx * c + dy * s) / rx;
        float v = (dy * c - dx * s) / ry;
        if (u * u + v * v > 1) return null;
        return new float[]{u, v};
    }

    /**
     * Hair shading (v0.8.0 — the SDK renderer's fur shading,
     * restyled): the same fake-normal + Bayer + hash lighting,
     * but the hash streaks run VERTICALLY, following falling
     * strands, and the ramp is Stef's dark hair tones with the
     * mode-tinted rim light on the lit edge.
     */
    private int hair(int[] pal, float nx, float ny, int x, int y, int ox, int oy) {
        float r2 = nx * nx + ny * ny;
        float nz = r2 >= 1 ? 0 : (float) Math.sqrt(1 - r2);
        float l = -0.40f * nx - 0.50f * ny + 0.76f * nz;
        float b = bayer(x, y);
        int rx = x - ox, ry = y - oy;
        float streak = ((hash16(rx * 3 + ry / 4, ry) >> 4) / 4096f) - 0.5f;
        float lv = l + (b - 0.5f) * 0.2f + streak * 0.22f;
        if (r2 > 0.86f && nx * 0.6f + ny * 0.8f > 0.72f && b < 0.4f) return C_RIM;
        return lv > 0.95f ? C_BH : lv > 0.62f ? C_BL : lv > 0.28f ? C_BM : C_BD;
    }

    /** The gold nameplate chain + pendant (v0.8.0). */
    private void drawNecklace(int[] pal, float cx, float baseY) {
        int cy = iround(baseY);
        // Chain: a shallow gold arc dipping at the centre.
        int[][] chain = {{-6, 0}, {-5, 1}, {-4, 1}, {-3, 2}, {-2, 2},
                {-1, 3}, {0, 3}, {1, 3}, {2, 2}, {3, 2}, {4, 1}, {5, 1}, {6, 0}};
        for (int[] p : chain) px(pal, iround(cx) + p[0], cy + p[1], C_GOLD);
        // Nameplate: a bright gold bar where the chain dips,
        // with the little pink heart at its centre — the
        // "Stephany" script itself is sub-pixel at 64 px, so
        // the plate + heart carry it.
        for (int i = -2; i <= 2; i++) px(pal, iround(cx) + i, cy + 4, C_GOLD);
        px(pal, iround(cx), cy + 4, C_HEART);
        px(pal, iround(cx), cy + 5, C_GOLD);
    }

    // ------------------------------------------------------------ face

    /**
     * Stef's eyes (v0.8.0 — replacing the default creature's
     * bead eyes): a white almond with a brown iris, dark pupil
     * and shine, framed by a thick dark lash line on top with a
     * flick at the outer corner. Wide style (LISTENING) opens
     * the almond a row taller; happy and X styles keep the
     * SDK's stamped shapes, restyled to lash black.
     */
    private void drawEye(int[] pal, float ex, float ey, float openness, int style,
            float gazeX, float gazeY) {
        if (style == 2) {
            stamp(pal, new String[]{".##.", "#..#"}, iround(ex) - 2, iround(ey), C_OUT, C_OUT);
            return;
        }
        if (style == 3) {
            stamp(pal, new String[]{"#..#", ".##.", ".##.", "#..#"},
                    iround(ex) - 2, iround(ey) - 1, C_OUT, C_OUT);
            return;
        }
        int cx = iround(ex), cy = iround(ey);
        if (openness < 0.3f) {
            // Closed: a soft lash line, ends flicking down.
            stamp(pal, new String[]{"#....#", ".####."}, cx - 3, cy + 1, C_OUT, C_OUT);
            return;
        }
        float irisX = gazeX * 0.9f, irisY = gazeY * 0.6f;
        int top = style == 1 ? -3 : -2;
        int skip = iround((1 - openness) * 2);
        for (int dy = top; dy <= 2; dy++) {
            if (dy < top + skip) continue;
            for (int dx = -3; dx <= 3; dx++) {
                float e = (dx * dx) / 9.0f + ((dy - 0.2f) * (dy - 0.2f)) / 6.5f;
                if (e > 1) continue;
                float idx = dx - irisX, idy = (dy - 0.2f) - irisY;
                float id2 = idx * idx + idy * idy;
                int c;
                if (dy <= top + (style == 1 ? 1 : 0) + skip) c = C_OUT; // lash line
                else if (id2 < 0.8f) c = C_OUT;                          // pupil
                else if (id2 < 2.6f) c = C_IRIS;                         // brown iris
                else c = C_WHITE;                                        // sclera
                px(pal, cx + dx, cy + dy, c);
            }
        }
        // Shine + the outer-corner lash flick.
        px(pal, cx + iround(irisX) - 1, cy - 1 + iround(irisY), C_SHINE);
        int flickX = ex < 32 ? cx - 4 : cx + 4;
        px(pal, flickX, cy + top + 1, C_OUT);
    }

    /** One of Stef's thick dark brows: a 5 px shallow arc. */
    private void drawBrow(int[] pal, int cx, int y, boolean mirror) {
        int[][] arc = {{-3, 1}, {-2, 0}, {-1, 0}, {0, 0}, {1, 0}, {2, 0}, {3, 1}};
        for (int[] p : arc) px(pal, cx + p[0], y + p[1], C_BROW);
        px(pal, cx, y - 1, C_BROW);
    }

    private void drawBlush(int[] pal, int x, int y, float strength) {
        String[] cheek = {".##.", "####", ".##."};
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 4; i++) {
                if (cheek[j].charAt(i) != '#') continue;
                float b = bayer(x + i, y + j);
                if (b < strength) {
                    px(pal, x - 2 + i, y + j, (j == 1 && b < strength * 0.5f) ? C_BLUSHD : C_BLUSH);
                }
            }
        }
    }

    /**
     * Stef's red lips (v0.8.0 — replacing the default
     * creature's dark mouth shapes): the same six mouth states
     * the SDK's modes drive, drawn as lipstick. Her signature
     * is the big smile, so the grin and the open speaking mouth
     * show the white teeth row under the upper lip.
     */
    private void drawMouth(int[] pal, int x, int y, int mouth, float open) {
        switch (mouth) {
            case 0: { // smile: a red arc, corners up
                int[][] arc = {{-3, -1}, {-2, 0}, {-1, 1}, {0, 1},
                        {1, 1}, {2, 0}, {3, -1}};
                for (int[] p : arc) px(pal, x + p[0], y + p[1], C_LIP);
                px(pal, x - 1, y, C_LIP); px(pal, x, y, C_LIP); px(pal, x + 1, y, C_LIP);
                break;
            }
            case 1: // small "o": a lip ring around a dark centre
                stamp(pal, new String[]{".##.", "#oo#", ".##."}, x - 2, y - 1, C_LIP, C_MOUTH);
                break;
            case 2: // hmm: a short off-centre flat line
                stamp(pal, new String[]{"..#", "##."}, x - 1, y, C_LIP, C_LIP);
                break;
            case 3: { // speaking: dark red interior framed by
                // lips, teeth row on top when well open, tongue low
                int h = 1 + iround(clamp(open, 0, 1) * 4.0f);
                int w = h > 2 ? 6 : 5;
                for (int j = 0; j < h; j++) {
                    int inset = (j == 0 || j == h - 1) && h > 2 ? 1 : 0;
                    for (int i = inset; i < w - inset; i++) {
                        px(pal, x - w / 2 + i, y + j, C_LIP);
                    }
                }
                if (h >= 2) {
                    for (int j = 0; j < h - 1; j++) {
                        int inset = (j == 0 || j == h - 2) && h > 3 ? 1 : 0;
                        for (int i = 1 + inset; i < w - 1 - inset; i++) {
                            boolean teeth = j == 0 && h >= 3;
                            boolean tongue = h >= 4 && j == h - 2
                                    && i > 1 && i < w - 2;
                            px(pal, x - w / 2 + i, y + j,
                                    teeth ? C_WHITE : tongue ? C_TONGUE : C_MOUTH);
                        }
                    }
                }
                break;
            }
            case 4: { // grin: her big smile — teeth framed in red
                stamp(pal, new String[]{"#######", "#ooooo#", ".#####."},
                        x - 3, y - 1, C_LIP, C_WHITE);
                break;
            }
            case 5: // flat
                stamp(pal, new String[]{"###"}, x - 1, y + 1, C_LIP, C_LIP);
                break;
            default: break;
        }
    }

    // ------------------------------------------------------------ helpers

    private void px(int[] pal, int x, int y, int colorIdx) {
        if (x >= 0 && x < W && y >= 0 && y < H) {
            fb[y * W + x] = pal[colorIdx];
            cellIdx[y * W + x] = (byte) colorIdx;
        }
    }

    private void setCell(int[] pal, int x, int y, int colorIdx) {
        px(pal, x, y, colorIdx);
    }

    private int getCell(int x, int y) {
        if (x < 0 || x >= W || y < 0 || y >= H) return C_BG;
        return cellIdx[y * W + x];
    }

    private void stamp(int[] pal, String[] rows, int x0, int y0, int fill, int alt) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                char ch = rows[r].charAt(c);
                if (ch == '#') px(pal, x0 + c, y0 + r, fill);
                else if (ch == 'o') px(pal, x0 + c, y0 + r, alt);
            }
        }
    }

    private static float bayer(int x, int y) {
        return (BAYER4[y & 3][x & 3] + 0.5f) / 16.0f;
    }

    private static int hash16(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0xffff;
    }

    private static int iround(float v) { return (int) Math.floor(v + 0.5f); }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }

    private static float ease(float x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static float[] hexToRgb(int hex) {
        return new float[]{(hex >> 16) & 0xff, (hex >> 8) & 0xff, hex & 0xff};
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t};
    }

    private static float[] scale(float[] a, float k) {
        return new float[]{a[0] * k, a[1] * k, a[2] * k};
    }

    private static int toArgb(float[] rgb) {
        int r = clamp255(rgb[0]), g = clamp255(rgb[1]), b = clamp255(rgb[2]);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int argb(int r, int g, int b) {
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
