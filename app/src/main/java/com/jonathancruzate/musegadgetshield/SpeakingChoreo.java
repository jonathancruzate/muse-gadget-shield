package com.jonathancruzate.musegadgetshield;

/**
 * Port of the Muse Gadget SDK's speaking choreography
 * (esp32/components/muse/muse_speaking.h) — the normalized,
 * interruptible gesture controller that drives the SDK avatar
 * while Muse speaks. Pure state machine, no clock or allocation
 * of its own: the renderer supplies (speaking, dt, mode_t, level)
 * each frame and reads back hand pose (lift/extend/inward/wrist
 * per hand), mouth openness, brow, eye widening, gaze and nod.
 *
 * The vocabulary and timings are the SDK's (see
 * esp32/docs/speaking-animations.md): right-hand raise (1.15 s),
 * step-by-step (1.90 s), left-hand raise (1.15 s), open
 * presentation (1.65 s), with rests between; the sequence cycles
 * raise → steps → raise → open, and the opening gesture rotates
 * across replies. Speech energy scales articulation; after real
 * voice energy, a quiet period longer than 0.28 s settles the
 * hands. Entirely silent (caption-paced) playback gets a visual
 * speaking rhythm for the mouth instead — syllable pulses with a
 * short phrase breath every 2.8 s — until real voice is heard.
 */
final class SpeakingChoreo {
    static final int REST = 0;
    static final int RAISE_RIGHT = 1;
    static final int STEPS = 2;
    static final int RAISE_LEFT = 3;
    static final int OPEN = 4;

    boolean active;
    boolean heardVoice;
    int action = REST;
    int next = RAISE_RIGHT;
    int side;
    int leader;
    int opener;
    private int seed = 0x2545;
    float time;
    float duration = 0.12f;
    private float modeT;
    private float quiet;
    float energy;
    final float[] lift = new float[2];
    final float[] extend = new float[2];
    final float[] inward = new float[2];
    final float[] wrist = new float[2];
    float mouth;
    float brow;
    float eyeWide;
    float gaze;
    float nod;

    private static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    private static float ease(float x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private float random() {
        seed = seed * 1664525 + 1013904223;
        return ((seed >>> 16) & 0xffff) / 65535.0f;
    }

    private static float pulse(float t, float length, float rise, float fall) {
        return ease(t / rise) * (1 - ease((t - length + fall) / fall));
    }

    /** One frame of the SDK's muse_speaking_update(). */
    void update(boolean speaking, float dt, float modeTNow, float level) {
        dt = clamp(dt, 0, 0.2f);
        level = clamp(level, 0, 1);
        if (speaking && (!active || modeTNow < modeT)) {
            active = true;
            heardVoice = false;
            quiet = 0;
            action = REST;
            // Rotate openers: short replies must not always show
            // the same first gesture.
            next = RAISE_RIGHT + (opener++ % 4);
            time = 0;
            duration = 0.12f;
            leader = random() > 0.5f ? 1 : 0;
            modeT = modeTNow;
        }
        float advance = speaking ? clamp(modeTNow - modeT, 0, 0.2f) : dt;
        modeT = modeTNow;
        energy += ((speaking ? level : 0) - energy) * clamp(dt * 18, 0, 1);
        if (level > 0.06f && speaking) {
            heardVoice = true;
            quiet = 0;
        } else {
            quiet += advance;
        }
        boolean pause = speaking && heardVoice && quiet > 0.28f;
        if (!speaking || pause) {
            action = REST;
            time = 0;
            duration = 0.18f;
            if (!speaking) active = false;
        } else {
            time += advance;
            if (time >= duration) {
                time = 0;
                if (action == REST) {
                    action = next;
                    side = action == RAISE_RIGHT ? 1
                            : action == RAISE_LEFT ? 0 : leader;
                    duration = (action == RAISE_RIGHT || action == RAISE_LEFT) ? 1.15f
                            : action == STEPS ? 1.90f : 1.65f;
                    // Advance on START: a mid-gesture pause settles
                    // and resumes with a different family.
                    next = action == OPEN ? RAISE_RIGHT : action + 1;
                    if (action == OPEN) leader ^= 1;
                } else {
                    action = REST;
                    duration = 0.42f + 0.30f * random();
                }
            }
        }
        float[] tLift = {0, 0}, tExtend = {0, 0}, tInward = {0, 0}, tWrist = {0, 0};
        float tBrow = 0, tEyeWide = 0, tGaze = 0, tNod = 0;
        if (speaking && !pause && action != REST) {
            float amplitude = 0.88f + 0.12f * energy;
            if (action == RAISE_RIGHT || action == RAISE_LEFT) {
                int hand = side;
                float p = pulse(time, 1.10f, 0.28f, 0.34f) * amplitude;
                tLift[hand] = p;
                tExtend[hand] = p * 0.14f;
                tWrist[hand] = (hand == 1 ? -1 : 1) * p * 0.90f;
                tBrow = 0.75f * p;
                tGaze = (hand == 1 ? 1 : -1) * 0.65f * p;
                tNod = -0.12f * p;
            } else if (action == STEPS) {
                float envelope = pulse(time, duration, 0.25f, 0.30f);
                float beat = 0, progress = 0;
                for (int i = 0; i < 3; i++) {
                    float t = time - 0.24f - i * 0.43f;
                    beat += pulse(t, 0.34f, 0.10f, 0.17f);
                    progress += ease(t / 0.12f) / 3;
                }
                int hand = side, support = hand ^ 1;
                tLift[hand] = envelope * (0.20f + 0.28f * progress + 0.18f * beat) * amplitude;
                tInward[hand] = envelope * (0.96f - 0.27f * beat);
                tWrist[hand] = (hand == 1 ? 1 : -1) * envelope * 0.75f;
                tLift[support] = 0.12f * envelope;
                tInward[support] = 0.38f * envelope;
                tBrow = 0.30f * envelope + 0.40f * beat;
                tEyeWide = 0.25f * beat;
                tGaze = (hand == 1 ? 1 : -1) * 0.25f * envelope;
                tNod = 0.50f * beat;
            } else { // OPEN
                float p = pulse(time, duration, 0.36f, 0.42f) * amplitude;
                for (int i = 0; i < 2; i++) {
                    tLift[i] = p * 0.48f;
                    tExtend[i] = p;
                    tWrist[i] = (i == 1 ? 1 : -1) * p * 0.50f;
                }
                tBrow = 0.85f * p;
                tEyeWide = 0.80f * p;
                tNod = -0.40f * p;
            }
        }
        float blend = clamp(dt * 12, 0, 1);
        for (int i = 0; i < 2; i++) {
            lift[i] += (tLift[i] - lift[i]) * blend;
            extend[i] += (tExtend[i] - extend[i]) * blend;
            inward[i] += (tInward[i] - inward[i]) * blend;
            wrist[i] += (tWrist[i] - wrist[i]) * blend;
            if (lift[i] < 0.001f) lift[i] = 0;
            if (extend[i] < 0.001f) extend[i] = 0;
            if (inward[i] < 0.001f) inward[i] = 0;
            if (wrist[i] > -0.001f && wrist[i] < 0.001f) wrist[i] = 0;
        }
        brow += (tBrow - brow) * blend;
        eyeWide += (tEyeWide - eyeWide) * blend;
        gaze += (tGaze - gaze) * blend;
        nod += (tNod - nod) * blend;

        float m = speaking ? clamp((energy - 0.035f) * 1.5f, 0, 1) : 0;
        if (speaking && !heardVoice) {
            // Caption-paced (silent) replies: a visual speaking
            // rhythm — varied syllables, a short phrase breath.
            final float[] syllables = {0.50f, 0.90f, 0.65f, 0.80f, 0.40f, 0.75f, 0.60f};
            float t = modeT > 0.12f ? modeT - 0.12f : 0;
            float syllable = t * 5.2f;
            int index = (int) syllable;
            float phase = syllable - index;
            float phrase = t - (float) Math.floor(t / 2.8f) * 2.8f;
            float p = pulse(phase, 0.90f, 0.18f, 0.40f);
            m = phrase < 2.38f ? p * syllables[index % 7] : 0;
        }
        mouth += (m - mouth) * clamp(dt * 22, 0, 1);
        if (mouth < 0.001f) mouth = 0;
    }
}
