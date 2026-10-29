package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * A live view of an RTSP camera in the top right of Home, under the top buttons (camera_rtsp_url, set at build
 * time from launcher/local.properties so the credentials never reach git). It is muted until it takes focus
 * (Down from Settings, or Up from the dock below it), plays the camera's sound while focused and mutes again when
 * focus leaves. It plays only while Home is started and releases the player and decoder the moment an app opens.
 * A dropped stream is retried every few seconds. Nothing is shown until the first frame arrives, so a camera
 * that is offline leaves no black box and takes no focus.
 */
@OptIn(markerClass = UnstableApi.class)
final class CameraTile {
    private static final String TAG = "CameraTile";
    private static final long RETRY_MS = 5000;

    private final Context context;
    private final Uri uri;
    private final FrameLayout frame;
    private final SurfaceView video;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::play;
    private final GradientDrawable ring;
    private ExoPlayer player;
    private boolean started;
    /** A picture has arrived, and the rows have not scrolled up under the tile. */
    private boolean hasPicture, scrolledAway;
    /** OK on the tile fills the screen with it; OK, Back or Home puts it back (the small size is kept here). */
    private boolean full;
    private int smallWidth, smallHeight, smallTop, smallEnd;

    /** The tile added to root, or null when no camera is configured. */
    static CameraTile attach(Context context, FrameLayout root) {
        String url = context.getString(R.string.camera_rtsp_url).trim();
        return url.isEmpty() ? null : new CameraTile(context, root, Uri.parse(url));
    }

    private CameraTile(Context context, FrameLayout root, Uri uri) {
        this.context = context;
        this.uri = uri;
        float density = context.getResources().getDisplayMetrics().density;
        float radius = 16 * density;
        frame = new FrameLayout(context);
        frame.setBackgroundColor(0xFF000000);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        // Focusable once a frame is showing; focus turns the sound on and draws a white ring like a tile's.
        frame.setFocusable(false);
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(Math.round(3 * density), 0xFFFFFFFF);
        ring.setColor(0x00000000);
        frame.setOnFocusChangeListener((v, hasFocus) -> {
            frame.setForeground(hasFocus ? ring : null);
            applyVolume();
        });
        frame.setVisibility(View.INVISIBLE);
        // A SurfaceView, not a TextureView: the MediaTek decoder on TPM171E draws nothing into a TextureView.
        video = new SurfaceView(context);
        frame.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(320 * density),
                Math.round(180 * density), Gravity.TOP | Gravity.END);
        // Right edge level with the Settings pill, top just under the pill row.
        lp.topMargin = Math.round(125 * density);
        lp.setMarginEnd(Math.round(57 * density));
        root.addView(frame, lp);
    }

    void start() {
        started = true;
        play();
    }

    /**
     * Shrinks the tile, keeping 16:9, so it ends above {@code bottomPx} (window coordinates), for layouts whose dock
     * sits higher (more columns, rows under the dock). Never larger than 320x180 dp, never smaller than 160x90 dp.
     */
    void fitAbove(int bottomPx) {
        float density = context.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
        int top = full ? smallTop : lp.topMargin;
        int h = Math.max(Math.round(90 * density), Math.min(Math.round(180 * density), bottomPx - top));
        if (full) {
            smallHeight = h;
            smallWidth = h * 16 / 9;
            return;
        }
        if (lp.height == h) return;
        lp.height = h;
        lp.width = h * 16 / 9;
        frame.setLayoutParams(lp);
    }

    boolean isFull() {
        return full;
    }

    /** Fills the screen with the live view, or puts it back where it was. */
    void setFull(boolean on) {
        if (on == full) return;
        full = on;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
        if (on) {
            smallWidth = lp.width;
            smallHeight = lp.height;
            smallTop = lp.topMargin;
            smallEnd = lp.getMarginEnd();
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.topMargin = 0;
            lp.setMarginEnd(0);
            frame.setClipToOutline(false);
            frame.setForeground(null);
            frame.bringToFront();
        } else {
            lp.width = smallWidth;
            lp.height = smallHeight;
            lp.topMargin = smallTop;
            lp.setMarginEnd(smallEnd);
            frame.setClipToOutline(true);
            frame.setForeground(frame.hasFocus() ? ring : null);
        }
        frame.setLayoutParams(lp);
    }

    /** The focusable tile, for the activity's key handling. */
    View view() {
        return frame;
    }

    /** Showing a picture, so it can take focus. */
    boolean isShowing() {
        return frame.getVisibility() == View.VISIBLE;
    }

    void stop() {
        started = false;
        hasPicture = false;
        setFull(false);
        handler.removeCallbacks(retry);
        release();
        updateShown();
    }

    /**
     * The third or a later row has focus, so the grid scrolls into the tile's space (a SurfaceView ignores the
     * grid's clipping). It returns on either of the first two rows. The stream keeps running, so the picture is
     * back at once.
     */
    void setScrolledAway(boolean away) {
        if (away == scrolledAway) return;
        scrolledAway = away;
        updateShown();
    }

    private void updateShown() {
        boolean show = hasPicture && !scrolledAway;
        frame.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        frame.setFocusable(show);
    }

    private void applyVolume() {
        if (player != null) player.setVolume(frame.hasFocus() ? 1f : 0f);
    }

    private void play() {
        if (!started) return;
        release();
        player = new ExoPlayer.Builder(context)
                // A live view: start on the first half second and never build a long buffer behind the door.
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 250).build())
                .build();
        applyVolume();
        player.setVideoSurfaceView(video);
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                Log.i(TAG, "first frame");
                hasPicture = true;
                updateShown();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "stream failed, retrying: " + error.getErrorCodeName());
                release();
                if (started) handler.postDelayed(retry, RETRY_MS);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                // The camera ended the session (a restart, a firmware update): connect again.
                if (state == Player.STATE_ENDED && started) {
                    release();
                    handler.postDelayed(retry, RETRY_MS);
                }
            }
        });
        // TCP keeps the stream whole over Wi-Fi, where UDP packets from the camera are easily lost.
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(uri)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void release() {
        if (player == null) return;
        player.release();
        player = null;
    }
}
