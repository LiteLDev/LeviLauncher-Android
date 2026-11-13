package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;

public final class ModMenuToggle extends CompoundButton {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private ModMenuTheme palette;
    private float position;
    private ValueAnimator animator;

    public ModMenuToggle(Context context) { this(context, null); }

    public ModMenuToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
        setButtonDrawable(null);
        setBackground(null);
        setBackgroundTintList(null);
        setPadding(0, 0, 0, 0);
        setMinWidth(dp(44));
        setMinHeight(dp(40));
        setFocusable(true);
        setClickable(true);
        palette = new ModMenuTheme(context);
        position = isChecked() ? 1 : 0;
    }

    void setPalette(ModMenuTheme palette) { this.palette = palette; invalidate(); }

    @Override public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (!changed) return;
        if (animator != null) animator.cancel();
        float target = checked ? 1 : 0;
        if (!isAttachedToWindow()) { position = target; invalidate(); return; }
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(130);
        animator.addUpdateListener(value -> { position = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (palette == null) return;
        float width = dp(36), height = dp(21);
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        track.set(left, top, left + width, top + height);
        paint.setStyle(Paint.Style.FILL);
        int off = palette.dark ? 0xFF44444D : 0xFFB0B0B8;
        paint.setColor(ModMenuTheme.mix(off, palette.primary, position));
        paint.setAlpha(isEnabled() ? 255 : 130);
        canvas.drawRoundRect(track, height / 2, height / 2, paint);
        paint.setColor(0xFFF8F8FA);
        paint.setAlpha(isEnabled() ? 255 : 160);
        float travel = width - height;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) travel *= -1;
        float start = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? left + width - height / 2 : left + height / 2;
        canvas.drawCircle(start + travel * position, top + height / 2, dp(8f), paint);
        if (isFocused()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(palette.accent);
            track.inset(-dp(2), -dp(2));
            canvas.drawRoundRect(track, dp(12), dp(12), paint);
        }
        paint.setAlpha(255);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        position = isChecked() ? 1 : 0;
        super.onDetachedFromWindow();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(isChecked());
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
