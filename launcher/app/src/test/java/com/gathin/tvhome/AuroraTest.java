package com.gathin.tvhome;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the aurora: the slow random drift between kinds, and the painted curtain. */
public class AuroraTest {

    private static final long MIN = 60_000L;

    @Test
    public void theNextKindIsNeverTheSameKind() {
        Aurora.Schedule s = new Aurora.Schedule(new Random(1), 0L);
        Aurora.Kind last = s.current();
        long t = 0L;
        for (int i = 0; i < 200; i++) {
            t = s.fadeEnd();
            s.levels(t + 1, new float[Aurora.LAYERS]);
            assertNotEquals(last, s.current());
            last = s.current();
        }
    }

    @Test
    public void eachKindIsHeldForFourToTenMinutesThenFadesForNinetySeconds() {
        Aurora.Schedule s = new Aurora.Schedule(new Random(7), 0L);
        long holdStart = 0L;
        for (int i = 0; i < 100; i++) {
            long hold = s.holdEnd() - holdStart;
            assertTrue("hold " + hold, hold >= 4 * MIN && hold <= 10 * MIN);
            assertEquals(Aurora.FADE_MS, s.fadeEnd() - s.holdEnd());
            holdStart = s.fadeEnd();
            s.levels(holdStart, new float[Aurora.LAYERS]);
        }
    }

    @Test
    public void commonKindsComeUpMoreOften() {
        Aurora.Schedule s = new Aurora.Schedule(new Random(3), 0L);
        Map<Aurora.Kind, Integer> seen = new EnumMap<>(Aurora.Kind.class);
        for (int i = 0; i < 4000; i++) {
            s.levels(s.fadeEnd(), new float[Aurora.LAYERS]);
            seen.merge(s.current(), 1, Integer::sum);
        }
        for (Aurora.Kind k : Aurora.Kind.values()) assertTrue(k + " never came up", seen.getOrDefault(k, 0) > 0);
        assertTrue(seen.get(Aurora.Kind.GREEN) > seen.get(Aurora.Kind.RED));
        assertTrue(seen.get(Aurora.Kind.PINK) > seen.get(Aurora.Kind.VIOLET));
    }

    @Test
    public void levelsHoldTheKindAndBlendSmoothlyThroughTheFade() {
        Aurora.Schedule s = new Aurora.Schedule(new Random(5), 0L);
        float[] out = new float[Aurora.LAYERS];
        s.levels(s.holdEnd() - 1, out);
        assertArrayEquals(Aurora.levelsOf(s.current()), out, 1e-6f);

        Aurora.Kind from = s.current();
        Aurora.Kind to = s.next();
        float[] prev = out.clone();
        long fadeEnd = s.fadeEnd();
        for (long t = s.holdEnd(); t <= fadeEnd; t += 1000) {
            s.levels(t, out);
            for (int i = 0; i < Aurora.LAYERS; i++) {
                assertTrue("jump in layer " + i + " at " + t, Math.abs(out[i] - prev[i]) < 0.05f);
                float lo = Math.min(Aurora.levelsOf(from)[i], Aurora.levelsOf(to)[i]);
                float hi = Math.max(Aurora.levelsOf(from)[i], Aurora.levelsOf(to)[i]);
                assertTrue(out[i] >= lo - 1e-6f && out[i] <= hi + 1e-6f);
            }
            prev = out.clone();
        }
    }

    @Test
    public void aLongPauseStartsAFreshHoldInsteadOfReplayingTheMissedOnes() {
        Aurora.Schedule s = new Aurora.Schedule(new Random(9), 0L);
        long later = 3L * 24 * 60 * MIN;   // Home was in the background for three days
        s.levels(later, new float[Aurora.LAYERS]);
        assertTrue(s.holdEnd() > later && s.holdEnd() - later <= 10 * MIN);
    }

    private static final int W = 128, H = 64;

    private static int[] bake(float[] levels) {
        int[] px = new int[W * H];
        Aurora.bake(px, W, H, 0.4f, levels, Aurora.FRONT);
        return px;
    }

    private static float[] only(int layer) {
        float[] l = new float[Aurora.LAYERS];
        l[layer] = 1f;
        return l;
    }

    private static int alpha(int c) {
        return c >>> 24;
    }

    @Test
    public void nothingLitPaintsNothing() {
        for (int c : bake(new float[Aurora.LAYERS])) assertEquals(0, alpha(c));
    }

    @Test
    public void theBottomOfTheTextureStaysClear() {
        int[] px = bake(new float[]{1f, 1f, 1f, 1f, 1f});
        for (int x = 0; x < W; x++) assertTrue(alpha(px[(H - 1) * W + x]) < 8);
    }

    @Test
    public void greenIsGreenAndRedSitsAboveIt() {
        int[] green = bake(only(Aurora.GREEN));
        int[] red = bake(only(Aurora.RED_GLOW));
        long gRow = 0, rRow = 0, gSum = 0, rSum = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int g = green[y * W + x], r = red[y * W + x];
                if (alpha(g) > 40) assertTrue(((g >> 8) & 0xFF) >= ((g >> 16) & 0xFF));
                if (alpha(r) > 40) assertTrue(((r >> 16) & 0xFF) > ((r >> 8) & 0xFF));
                gRow += (long) alpha(g) * y; gSum += alpha(g);
                rRow += (long) alpha(r) * y; rSum += alpha(r);
            }
        }
        assertTrue(gSum > 0 && rSum > 0);
        assertTrue("red should be higher in the sky than green", rRow / rSum < gRow / gSum);
    }

    @Test
    public void violetHangsBelowThePinkEdge() {
        int[] pink = bake(only(Aurora.PINK));
        int[] violet = bake(only(Aurora.VIOLET));
        assertTrue(centreRow(violet) > centreRow(pink));
    }

    @Test
    public void theCurtainChangesShapeOverTime() {
        float[] l = Aurora.levelsOf(Aurora.Kind.GREEN);
        int[] a = new int[W * H], b = new int[W * H];
        Aurora.bake(a, W, H, 0.4f, l, Aurora.FRONT);
        Aurora.bake(b, W, H, 0.6f, l, Aurora.FRONT);
        assertFalse(java.util.Arrays.equals(a, b));
        Aurora.bake(b, W, H, 0.4f, l, Aurora.FRONT);
        assertArrayEquals(a, b);
    }

    private static long centreRow(int[] px) {
        long sum = 0, w = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            sum += (long) alpha(px[y * W + x]) * y;
            w += alpha(px[y * W + x]);
        }
        return sum / Math.max(1, w);
    }
}
