package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Fades the rows out as they scroll up under the clock, instead of cutting them off at the grid's top edge.
 * It erases the top of the grid's own picture, so whatever is behind Home (the aurora, a wallpaper) shows
 * through: the grid must draw into a hardware layer ({@link #attach}), or the erasing would punch through the
 * whole window. Android's own fading edge draws in the wrong place with this grid's clipChildren=false.
 * The fade grows in over the first {@code length} pixels of scrolling, so the dock at rest is untouched.
 */
final class TopFade extends RecyclerView.ItemDecoration {

    private final float length;
    private final Paint paint = new Paint();
    private float shaderLength = -1f;

    private TopFade(float length) {
        this.length = length;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    static void attach(RecyclerView grid, float length) {
        grid.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        grid.addItemDecoration(new TopFade(length));
    }

    @Override
    public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int scrolled = parent.computeVerticalScrollOffset();
        if (scrolled <= 0) return;
        float fade = Math.min(length, scrolled);
        if (fade != shaderLength) {
            paint.setShader(new LinearGradient(0f, 0f, 0f, fade, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            shaderLength = fade;
        }
        c.drawRect(0f, 0f, parent.getWidth(), fade, paint);
    }
}
