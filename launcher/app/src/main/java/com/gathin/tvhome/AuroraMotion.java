package com.gathin.tvhome;

/**
 * The aurora's movement, for {@code Canvas.drawBitmapMesh}: each frame bends a curtain's texture through a
 * grid and gives every grid point a brightness. The lower edge dances with waves running along the curtain at
 * several speeds, now and then a fold travels along it and curls sideways, rays flicker and pulse, and whole
 * sections brighten and fade over several seconds. A few hundred numbers per curtain per frame; the GPU does
 * the bending. No Android graphics here, so it runs in plain JVM tests.
 */
final class AuroraMotion {

    static final int COLS = 40, ROWS = 6;
    /** The curtains cover the top of the screen; the dock and the rows sit below. */
    static final float COVER = 0.72f;
    /** Wider than the screen, so the sway never shows an end. */
    static final float OVERSCAN = 1.12f;

    /** How one curtain moves: the far ones slower and calmer. */
    static final class Style {
        final float swayAmp, swayPeriod, speed, amp, phase;

        Style(float swayAmp, float swayPeriod, float speed, float amp, float phase) {
            this.swayAmp = swayAmp;
            this.swayPeriod = swayPeriod;
            this.speed = speed;
            this.amp = amp;
            this.phase = phase;
        }
    }

    static final Style FAR = new Style(0.020f, 211f, 0.6f, 0.6f, 2.9f);
    static final Style BACK = new Style(0.030f, 173f, 0.8f, 0.8f, 1.1f);
    static final Style FRONT = new Style(0.045f, 97f, 1.0f, 1.0f, 0f);

    private static final float TAU = (float) (2 * Math.PI);
    // Per-column scratch for mesh(), on the UI thread only.
    private static final float[] colDance = new float[COLS + 1], colTwirl = new float[COLS + 1],
            colFold = new float[COLS + 1], colX = new float[COLS + 1], colLevel = new float[COLS + 1];

    /**
     * Fills {@code verts} ((COLS+1)*(ROWS+1) x,y pairs, row by row from the top) and {@code colours} (one opaque
     * grey per point, its level the brightness) for a {@code w} by {@code h} view at {@code secs} seconds.
     */
    static void mesh(float[] verts, int[] colours, float w, float h, float secs, Style st) {
        float t = secs * st.speed;
        float ph = st.phase;
        float sway = st.swayAmp * w * sin(TAU * secs / st.swayPeriod + ph);
        // A fold that travels along the curtain, swelling and dying away as it goes.
        float foldAt = frac(0.017f * t + ph * 0.13f) * 1.4f - 0.2f;
        float foldSwell = 0.5f + 0.5f * sin(TAU * 0.031f * t + ph);
        // Everything that depends only on the column is worked out once per column, not once per point.
        for (int c = 0; c <= COLS; c++) {
            float u = c / (float) COLS;
            colDance[c] = h * st.amp * (
                    0.035f * sin(TAU * (1.3f * u - 0.05f * t) + ph)
                    + 0.020f * sin(TAU * (3.7f * u + 0.11f * t) + 2 * ph)
                    + 0.010f * sin(TAU * (7.9f * u - 0.23f * t) + 3 * ph));
            colTwirl[c] = w * st.amp * 0.018f * sin(TAU * (0.9f * u + 0.07f * t) + ph);
            colFold[c] = w * st.amp * 0.05f * gauss((u - foldAt) / 0.07f) * foldSwell;
            colX[c] = (u * OVERSCAN - (OVERSCAN - 1f) / 2f) * w + sway;
            float section = 0.08f + 0.92f * smooth(0.5f
                    + 0.55f * sin(TAU * (0.6f * u - 0.06f * t) + 3 * ph)
                    + 0.40f * sin(TAU * (1.4f * u + 0.09f * t) + 5 * ph));
            float pulse = 0.72f + 0.28f * sin(TAU * (4.5f * u - 0.35f * t) + ph);
            float flicker = 0.88f + 0.12f * sin(TAU * (17f * u + 1.7f * t)) * sin(TAU * (11f * u - 2.3f * t) + ph);
            colLevel[c] = section * pulse * flicker * 1.15f;
        }
        int i = 0;
        for (int r = 0; r <= ROWS; r++) {
            float v = r / (float) ROWS;
            float hang = (float) Math.pow(v, 1.4);   // the top hangs still, the lower edge moves most
            float twirlHang = 1f - 0.6f * v;         // the top sways most
            float curl = sin((float) Math.PI * 1.3f * v + 0.9f * t);
            float rowLevel = 0.9f + 0.1f * v;
            for (int c = 0; c <= COLS; c++) {
                verts[2 * i] = colX[c] + colTwirl[c] * twirlHang + colFold[c] * curl;
                verts[2 * i + 1] = v * h * COVER + colDance[c] * hang;
                int g = Math.round(Math.min(1f, colLevel[c] * rowLevel) * 255);
                colours[i] = 0xFF000000 | g << 16 | g << 8 | g;
                i++;
            }
        }
    }

    private static float sin(float x) {
        return (float) Math.sin(x);
    }

    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    private static float gauss(float q) {
        return (float) Math.exp(-q * q);
    }

    private static float smooth(float x) {
        float c = Math.min(1f, Math.max(0f, x));
        return c * c * (3 - 2 * c);
    }

    private AuroraMotion() {
    }
}
