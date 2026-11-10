package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Page area of the sidebar shell. Clips its children to a rounded top-left corner so the page meets
 * the top bar and rail with the concave corner of the Windows launcher, whatever the page paints.
 */
public class ShellContentFrame extends FrameLayout {
    private final Path clip = new Path();
    private final float radius;

    public ShellContentFrame(Context context) {
        this(context, null);
    }

    public ShellContentFrame(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        radius = 20f * getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), new float[]{radius, radius, 0, 0, 0, 0, 0, 0}, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
