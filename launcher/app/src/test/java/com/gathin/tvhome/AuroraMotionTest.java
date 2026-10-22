package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the aurora's per-frame grid: where the curtain bends to and how bright each part is. */
public class AuroraMotionTest {

    private static final float W = 1920, H = 1080;
    private static final int N = (AuroraMotion.COLS + 1) * (AuroraMotion.ROWS + 1);

    private static float[] verts = new float[N * 2];
    private static int[] colours = new int[N];

    private static void at(float secs, AuroraMotion.Style style) {
        AuroraMotion.mesh(verts, colours, W, H, secs, style);
    }

    @Test
    public void theGridStaysAroundTheTopOfTheScreen() {
        for (AuroraMotion.Style style : new AuroraMotion.Style[]{AuroraMotion.FAR, AuroraMotion.BACK, AuroraMotion.FRONT}) {
            for (float s = 0; s < 3600; s += 7.3f) {
                at(s, style);
                for (int i = 0; i < N; i++) {
                    float x = verts[2 * i], y = verts[2 * i + 1];
                    assertTrue("x " + x, x > -0.3f * W && x < 1.3f * W);
                    assertTrue("y " + y, y > -0.15f * H && y < 0.9f * H);
                }
            }
        }
    }

    @Test
    public void brightnessIsAGreyLevelThatRisesAndFades() {
        int min = 255, max = 0;
        for (float s = 0; s < 600; s += 1.7f) {
            at(s, AuroraMotion.FRONT);
            for (int c : colours) {
                assertEquals(0xFF, c >>> 24);
                int v = c & 0xFF;
                assertEquals(v, (c >> 8) & 0xFF);
                assertEquals(v, (c >> 16) & 0xFF);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        assertTrue("sections should fade far down, min " + min, min < 90);
        assertTrue("and flare up, max " + max, max > 230);
    }

    @Test
    public void motionIsContinuousFromFrameToFrame() {
        float[] prev = new float[N * 2];
        at(100f, AuroraMotion.FRONT);
        System.arraycopy(verts, 0, prev, 0, prev.length);
        for (float s = 100.04f; s < 160f; s += 0.04f) {   // 25 frames a second
            at(s, AuroraMotion.FRONT);
            for (int i = 0; i < prev.length; i++) {
                assertTrue("vertex jumped at " + s, Math.abs(verts[i] - prev[i]) < 0.006f * W);
            }
            System.arraycopy(verts, 0, prev, 0, prev.length);
        }
    }

    @Test
    public void theCurtainActuallyMoves() {
        at(10f, AuroraMotion.FRONT);
        float[] a = verts.clone();
        at(12f, AuroraMotion.FRONT);
        float most = 0;
        for (int i = 0; i < a.length; i++) most = Math.max(most, Math.abs(verts[i] - a[i]));
        assertTrue("barely moved: " + most, most > 4f);
    }

    @Test
    public void theTopRowHangsFromTheTopAndTheLowerEdgeDances() {
        float topSpread = 0, bottomSpread = 0;
        for (float s = 0; s < 120; s += 3f) {
            at(s, AuroraMotion.FRONT);
            int bottom = AuroraMotion.ROWS * (AuroraMotion.COLS + 1);
            for (int c = 0; c <= AuroraMotion.COLS; c++) {
                topSpread = Math.max(topSpread, Math.abs(verts[2 * c + 1]));
                bottomSpread = Math.max(bottomSpread, Math.abs(verts[2 * (bottom + c) + 1] - 0.72f * H));
            }
        }
        assertTrue(topSpread < 0.02f * H);
        assertTrue(bottomSpread > 0.03f * H);
    }

    @Test
    public void sameTimeSameFrame() {
        at(42f, AuroraMotion.BACK);
        float[] a = verts.clone();
        int[] c = colours.clone();
        at(43f, AuroraMotion.BACK);
        assertFalse(java.util.Arrays.equals(a, verts));
        at(42f, AuroraMotion.BACK);
        assertArrayEquals(a, verts, 0f);
        assertArrayEquals(c, colours);
    }
}
