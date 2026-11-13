package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public final class ModMenuSurface extends FrameLayout {
    private final Path clip = new Path();
    private float radius;

    public ModMenuSurface(Context context, AttributeSet attrs) { super(context, attrs); }

    void setRadius(float radius) { this.radius = radius; updateClip(); invalidate(); }

    private void updateClip() {
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, Path.Direction.CW);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateClip();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
