package com.gathin.tvhome;

import java.util.Random;

/**
 * The aurora behind Home (config.xml, aurora): which kind of aurora is showing, the slow random drift from
 * one kind to the next, and the painting of one curtain into a small ARGB texture. No Android graphics here,
 * so it runs in plain JVM tests; AuroraView draws the textures.
 *
 * The layers follow the altitude of the light: a diffuse red glow highest (atomic oxygen, thin air), the green
 * curtain below it (oxygen, the common colour), a pink border at the curtain's lower edge, and violet-blue
 * hanging lowest (molecular nitrogen, deep electrons). Yellow is where red and green meet in the brightest
 * rays. The noise is written for this launcher: folded triangle waves along the curtain, no copied shader code.
 */
final class Aurora {

    static final int RED_GLOW = 0, GREEN = 1, PINK = 2, VIOLET = 3, YELLOW = 4, LAYERS = 5;

    /** A kind is held for 4 to 10 minutes, then fades into the next over this long. */
    static final long FADE_MS = 90_000L;
    private static final long MIN_HOLD_MS = 4 * 60_000L;
    private static final long MAX_HOLD_MS = 10 * 60_000L;

    /** The kinds of aurora, how often each comes up (relative weight), and how strongly each layer shows. */
    enum Kind {
        //          weight  red   green  pink  violet yellow
        GREEN(35, 0.05f, 0.80f, 0.05f, 0.05f, 0.10f),
        RED(15, 0.90f, 0.70f, 0.05f, 0.05f, 0.15f),
        VIOLET(15, 0.10f, 0.85f, 0.15f, 0.90f, 0.10f),
        PINK(20, 0.10f, 1.00f, 0.95f, 0.20f, 0.15f),
        YELLOW(15, 0.60f, 0.85f, 0.10f, 0.05f, 0.80f);

        final int weight;
        final float[] levels;

        Kind(int weight, float... levels) {
            this.weight = weight;
            this.levels = levels;
        }
    }

    static float[] levelsOf(Kind kind) {
        return kind.levels.clone();
    }

    /** The slow random drift: hold one kind, fade to another (never the same one), repeat. */
    static final class Schedule {
        private final Random random;
        private Kind current, next;
        private long holdEnd, fadeEnd;

        Schedule(Random random, long now) {
            this.random = random;
            current = pick(null);
            next = pick(current);
            holdEnd = now + hold();
            fadeEnd = holdEnd + FADE_MS;
        }

        Kind current() {
            return current;
        }

        Kind next() {
            return next;
        }

        long holdEnd() {
            return holdEnd;
        }

        long fadeEnd() {
            return fadeEnd;
        }

        /** The layer strengths at {@code now}, into {@code out} (LAYERS long). */
        void levels(long now, float[] out) {
            if (now - fadeEnd > MAX_HOLD_MS) {
                // Home was away for longer than a whole hold: start a fresh one rather than replay the missed ones.
                advance();
                holdEnd = now + hold();
                fadeEnd = holdEnd + FADE_MS;
            }
            while (now >= fadeEnd) {
                advance();
                holdEnd = fadeEnd + hold();
                fadeEnd = holdEnd + FADE_MS;
            }
            float f = now < holdEnd ? 0f : smooth((now - holdEnd) / (float) FADE_MS);
            for (int i = 0; i < LAYERS; i++) out[i] = current.levels[i] + (next.levels[i] - current.levels[i]) * f;
        }

        private void advance() {
            current = next;
            next = pick(current);
        }

        private long hold() {
            return MIN_HOLD_MS + (long) (random.nextDouble() * (MAX_HOLD_MS - MIN_HOLD_MS));
        }

        private Kind pick(Kind not) {
            int total = 0;
            for (Kind k : Kind.values()) if (k != not) total += k.weight;
            int r = random.nextInt(total);
            for (Kind k : Kind.values()) {
                if (k == not) continue;
                r -= k.weight;
                if (r < 0) return k;
            }
            throw new AssertionError();
        }
    }

    /** One curtain: where its lower edge sits (fraction of the texture height), how bright, and its own shape. */
    static final class Curtain {
        final float edge, gain, seed;

        Curtain(float edge, float gain, float seed) {
            this.edge = edge;
            this.gain = gain;
            this.seed = seed;
        }
    }

    /** The bright curtain in front, a dimmer, higher one behind it, and a faint one far behind both. */
    static final Curtain FRONT = new Curtain(0.66f, 1.00f, 1.7f);
    static final Curtain BACK = new Curtain(0.50f, 0.55f, 4.3f);
    static final Curtain FAR = new Curtain(0.40f, 0.30f, 8.9f);

    //                                         r      g      b
    private static final float[][] COLOURS = {{1.00f, 0.12f, 0.25f},   // red glow
                                              {0.25f, 1.00f, 0.50f},   // green
                                              {1.00f, 0.35f, 0.70f},   // pink
                                              {0.45f, 0.30f, 1.00f},   // violet
                                              {0.95f, 0.90f, 0.30f}};  // yellow

    private static final float TAU = (float) (2 * Math.PI);

    /**
     * Paints {@code curtain} at noise time {@code t} into {@code px} (w by h, unpremultiplied ARGB). The same
     * arguments always give the same pixels; a slightly later t gives a slightly different curtain.
     */
    static void bake(int[] px, int w, int h, float t, float[] levels, Curtain curtain) {
        float[] layer = new float[LAYERS];
        for (int x = 0; x < w; x++) {
            float u = x / (float) (w - 1);
            float s = curtain.seed;
            // The curtain's lower edge waves slowly along its length.
            float edge = curtain.edge
                    + 0.070f * (float) Math.sin(TAU * (u * 1.3f + 0.11f * t) + s)
                    + 0.035f * (float) Math.sin(TAU * (u * 3.1f - 0.17f * t) + 2 * s)
                    + 0.015f * (float) Math.sin(TAU * (u * 7.3f + 0.29f * t) + 3 * s);
            float rays = rays(u, t, s);
            float bright = 0.35f + 0.65f * rays * rays;
            float tall = 0.22f + 0.22f * folded(u * 2.3f + 0.07f * t + s);   // rays of different lengths
            float redSwell = 0.6f + 0.4f * (float) Math.sin(TAU * (u * 0.8f - 0.05f * t) + 5 * s);
            float ends = smooth(u / 0.04f) * smooth((1f - u) / 0.04f);
            // The curtain is not one even wall: it brightens and fades along its length.
            float patch = 0.15f + 0.85f * smooth(0.5f
                    + 0.45f * (float) Math.sin(TAU * (u * 1.7f + 0.06f * t) + 7 * s)
                    + 0.35f * (float) Math.sin(TAU * (u * 0.9f - 0.04f * t) + 11 * s));
            for (int y = 0; y < h; y++) {
                float d = edge - y / (float) h;   // height above the lower edge, as a fraction of the texture
                layer[GREEN] = (d >= 0 ? (float) Math.exp(-d / tall) : (float) Math.exp(d / 0.015f)) * bright;
                layer[YELLOW] = (d >= 0 ? (float) Math.exp(-d / 0.06f) : (float) Math.exp(d / 0.012f)) * rays * rays;
                layer[PINK] = gauss(d + 0.012f, 0.020f) * (0.5f + 0.5f * rays);
                layer[VIOLET] = gauss(d + 0.070f, 0.045f) * (0.6f + 0.4f * rays);
                layer[RED_GLOW] = gauss(d - 0.45f, 0.22f) * redSwell;
                float r = 0, g = 0, b = 0;
                for (int i = 0; i < LAYERS; i++) {
                    float v = levels[i] * layer[i] * (i == RED_GLOW ? 1f : patch);
                    r += v * COLOURS[i][0];
                    g += v * COLOURS[i][1];
                    b += v * COLOURS[i][2];
                }
                float k = curtain.gain * ends;
                px[y * w + x] = argb(r * k, g * k, b * k);
            }
        }
    }

    /** Vertical rays along the curtain: folded triangle waves at a few scales, drifting at different speeds. */
    private static float rays(float u, float t, float s) {
        float v = 0, amp = 0.5f, f = 9f;
        for (int o = 0; o < 4; o++) {
            float warp = 0.35f * folded(u * f * 0.37f - t * (0.13f + 0.05f * o) + s * (o + 1));
            v += amp * folded(u * f + t * (0.21f - 0.07f * o) + warp + s * 1.31f * (o + 1));
            amp *= 0.55f;
            f *= 2.1f;
        }
        float n = Math.min(1f, Math.max(0f, (v - 0.25f) / 0.6f));
        return n * n * (3 - 2 * n);
    }

    /** A triangle wave from 0 to 1 and back over each unit. */
    private static float folded(float x) {
        float f = x - (float) Math.floor(x);
        return 1f - Math.abs(2f * f - 1f);
    }

    private static float gauss(float x, float width) {
        float q = x / width;
        return (float) Math.exp(-q * q);
    }

    private static float smooth(float x) {
        float c = Math.min(1f, Math.max(0f, x));
        return c * c * (3 - 2 * c);
    }

    /** Linear light to an unpremultiplied colour int, its alpha the brightest channel. */
    private static int argb(float r, float g, float b) {
        float a = Math.min(1f, Math.max(r, Math.max(g, b)));
        if (a <= 0.002f) return 0;
        int ai = Math.round(a * 255);
        int ri = Math.min(255, Math.round(r / a * 255));
        int gi = Math.min(255, Math.round(g / a * 255));
        int bi = Math.min(255, Math.round(b / a * 255));
        return ai << 24 | ri << 16 | gi << 8 | bi;
    }

    private Aurora() {
    }
}
