package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The moving aurora behind Home (config.xml, aurora; off by default). Three curtains, each a small texture
 * painted by {@link Aurora#bake} on a background thread. Every frame, at 20 frames a second, the GPU bends each
 * texture through a grid ({@link AuroraMotion}: the lower edge dances, folds travel and curl, rays flicker and
 * sections brighten and fade) and adds the curtains like light. No pixels are computed per frame; what costs is
 * that every frame redraws all of Home. Every 10 seconds one curtain is repainted a little further along in its
 * shape and in the current kind of aurora, and crossfades in over 8 seconds, so the curtains slowly reshape and
 * the colours drift from one kind to the next. Nothing runs while Home is not showing.
 */
public class AuroraView extends View {

    private static final int TEX_W = 512;
    private static final int TEX_H = 192;
    private static final long FRAME_MS = 50;
    private static final long REPAINT_MS = 10_000;
    private static final long CROSSFADE_MS = 8_000;
    private static final long FIRST_FADE_MS = 3_000;
    /** How far a curtain's shape moves on at each repaint. */
    private static final float SHAPE_STEP = 0.12f;

    /** Full-screen: without this every redraw of Home goes through an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private final class Layer {
        final Aurora.Curtain curtain;
        final AuroraMotion.Style style;
        float t;
        Bitmap shown, incoming;
        long fadeStart = -1, fadeLength;

        Layer(Aurora.Curtain curtain, AuroraMotion.Style style) {
            this.curtain = curtain;
            this.style = style;
        }
    }

    private final Layer[] layers = {new Layer(Aurora.FAR, AuroraMotion.FAR), new Layer(Aurora.BACK, AuroraMotion.BACK),
            new Layer(Aurora.FRONT, AuroraMotion.FRONT)};
    private final float[] verts = new float[(AuroraMotion.COLS + 1) * (AuroraMotion.ROWS + 1) * 2];
    private final int[] colours = new int[(AuroraMotion.COLS + 1) * (AuroraMotion.ROWS + 1)];
    private final long born = SystemClock.uptimeMillis();

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService painter;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Random random = new Random();
    private Aurora.Schedule schedule;
    private boolean enabled, animate = true, running;
    private int nextRepaint;
    private long nextRepaintAt;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (now >= nextRepaintAt) {
                nextRepaintAt = now + REPAINT_MS;
                Layer layer = layers[nextRepaint];
                nextRepaint = (nextRepaint + 1) % layers.length;
                repaint(layer, CROSSFADE_MS);
            }
            invalidate();
            main.postDelayed(this, FRAME_MS);
        }
    };

    public AuroraView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // Curtains add up like light rather than cover each other.
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        setWillNotDraw(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** config aurora, and whether it may move (config animations); false for either leaves a still frame or nothing. */
    public void configure(boolean on, boolean animations) {
        enabled = on;
        animate = animations;
        setVisibility(on ? VISIBLE : GONE);
        if (!on) stop();
    }

    /** Home is showing: paint the curtains if needed and start moving them. */
    public void start() {
        if (!enabled || running) return;
        running = true;
        if (painter == null) painter = Executors.newSingleThreadExecutor();
        long now = System.currentTimeMillis();
        if (schedule == null) schedule = new Aurora.Schedule(random, now);
        for (Layer layer : layers) if (layer.shown == null && layer.incoming == null) repaint(layer, FIRST_FADE_MS);
        nextRepaintAt = SystemClock.uptimeMillis() + REPAINT_MS;
        if (animate) main.post(frame);
        else running = false;
    }

    /** Home is hidden: stop moving, keep the textures for a quick return. */
    public void stop() {
        running = false;
        main.removeCallbacks(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
        if (painter != null) painter.shutdownNow();
        painter = null;
    }

    /** Paints the layer's next shape off the main thread and fades it in over {@code fadeMs}. */
    private void repaint(Layer layer, long fadeMs) {
        if (painter == null || layer.fadeStart >= 0) return;   // still fading in the last one
        float[] levels = new float[Aurora.LAYERS];
        // Aim for the colours as they will be halfway through the crossfade.
        schedule.levels(System.currentTimeMillis() + fadeMs / 2, levels);
        float t = layer.shown == null ? layer.t : layer.t + SHAPE_STEP;
        painter.execute(() -> {
            int[] px = new int[TEX_W * TEX_H];
            Aurora.bake(px, TEX_W, TEX_H, t, levels, layer.curtain);
            main.post(() -> {
                if (painter == null) return;
                Bitmap target = layer.incoming;
                if (target == null) target = Bitmap.createBitmap(TEX_W, TEX_H, Bitmap.Config.ARGB_8888);
                target.setPixels(px, 0, TEX_W, 0, 0, TEX_W, TEX_H);
                layer.incoming = target;
                layer.t = t;
                layer.fadeLength = fadeMs;
                layer.fadeStart = SystemClock.uptimeMillis();
                invalidate();
            });
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!enabled) return;
        long now = SystemClock.uptimeMillis();
        float secs = animate ? (now - born) / 1000f : 0f;
        boolean fading = false;
        for (Layer layer : layers) {
            AuroraMotion.mesh(verts, colours, getWidth(), getHeight(), secs, layer.style);
            float f = 1f;
            if (layer.fadeStart >= 0) {
                f = Math.min(1f, (now - layer.fadeStart) / (float) layer.fadeLength);
                if (f >= 1f) {
                    // The new shape is fully in: it becomes the shown one, the old bitmap is reused next time.
                    Bitmap old = layer.shown;
                    layer.shown = layer.incoming;
                    layer.incoming = old;
                    layer.fadeStart = -1;
                } else {
                    fading = true;
                }
            }
            if (layer.shown != null && f < 1f) {
                paint.setAlpha(Math.round(255 * (1f - f)));
                draw(canvas, layer.shown);
            }
            Bitmap top = layer.fadeStart >= 0 ? layer.incoming : layer.shown;
            if (top != null) {
                paint.setAlpha(Math.round(255 * (layer.fadeStart >= 0 ? f : 1f)));
                draw(canvas, top);
            }
        }
        // A still aurora (animations off) still needs frames while a fade finishes.
        if (fading && !running) postInvalidateDelayed(FRAME_MS);
    }

    private void draw(Canvas canvas, Bitmap texture) {
        canvas.drawBitmapMesh(texture, AuroraMotion.COLS, AuroraMotion.ROWS, verts, 0, colours, 0, paint);
    }
}
