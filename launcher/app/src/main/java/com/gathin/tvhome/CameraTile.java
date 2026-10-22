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
        handler.removeCallbacks(retry);
        release();
        frame.setFocusable(false);
        frame.setVisibility(View.INVISIBLE);
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
                frame.setVisibility(View.VISIBLE);
                frame.setFocusable(true);
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
