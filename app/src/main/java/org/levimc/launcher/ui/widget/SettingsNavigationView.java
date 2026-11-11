package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.ThemePalette;

import java.util.ArrayList;
import java.util.List;

public class SettingsNavigationView extends LinearLayout {
    public interface OnSegmentSelectedListener {
        void onSegmentSelected(int index);
    }

    private final List<TextView> labels = new ArrayList<>();
    private final Typeface font;
    private boolean vertical;
    private int verticalRowHeight;
    private int selected = -1;
    private OnSegmentSelectedListener listener;

    public SettingsNavigationView(Context context) {
        this(context, null);
    }

    public SettingsNavigationView(Context context, AttributeSet attrs) {
        super(context, attrs);
        font = ResourcesCompat.getFont(context, R.font.misans);
        verticalRowHeight = dp(40);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);
        setSaveEnabled(false);
    }

    public void setVertical(boolean vertical) {
        this.vertical = vertical;
        setOrientation(vertical ? VERTICAL : HORIZONTAL);
        setGravity(vertical ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        for (TextView label : labels) layoutSegment(label);
        refreshSelection();
    }

    public void setItems(String... items) {
        removeAllViews();
        labels.clear();
        selected = -1;
        for (String item : items) {
            final int index = labels.size();
            TextView label = new NavigationLabel(getContext());
            label.setText(item);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            label.setMinWidth(0);
            label.setMinimumWidth(0);
            label.setPadding(dp(12), 0, dp(12), 0);
            label.setClickable(true);
            label.setFocusable(true);
            label.setOnClickListener(v -> setSelectedIndex(index, true));
            labels.add(label);
            addView(label);
            layoutSegment(label);
        }
        refreshSelection();
    }

    private void layoutSegment(TextView label) {
        label.setLayoutParams(new LayoutParams(
                vertical ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT,
                vertical ? verticalRowHeight : dp(40)));
        label.setGravity(vertical ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
    }

    public void fitVerticalHeight(int availableHeight) {
        if (!vertical || labels.isEmpty() || availableHeight <= 0) return;
        int height = Math.max(dp(32), Math.min(dp(40),
                (availableHeight - getPaddingTop() - getPaddingBottom()) / labels.size()));
        if (height == verticalRowHeight) return;
        verticalRowHeight = height;
        for (TextView label : labels) layoutSegment(label);
    }

    public TextView getSegment(int index) {
        return labels.get(index);
    }

    public void setOnSegmentSelectedListener(OnSegmentSelectedListener listener) {
        this.listener = listener;
    }

    public void setSelectedIndex(int index, boolean notify) {
        if (index < 0 || index >= labels.size()) return;
        boolean changed = selected != index;
        selected = index;
        refreshSelection();
        post(this::revealSelection);
        if (notify && changed && listener != null) listener.onSegmentSelected(index);
    }

    private void refreshSelection() {
        int accent = UiKit.accent(getContext());
        int text = ThemePalette.foreground(accent, PersonalizationManager.isDarkActive(getContext()));
        int secondary = ContextCompat.getColor(getContext(), R.color.text_secondary);
        for (int i = 0; i < labels.size(); i++) {
            boolean active = i == selected;
            TextView label = labels.get(i);
            label.setSelected(active);
            label.setTextColor(active ? text : secondary);
            label.setTypeface(font, active ? Typeface.BOLD : Typeface.NORMAL);
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(dp(6));
            fill.setColor(active && vertical ? ThemePalette.withAlpha(accent, 0.10f) : Color.TRANSPARENT);
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(dp(6));
            mask.setColor(Color.WHITE);
            label.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(ThemePalette.withAlpha(accent, 0.16f)), fill, mask));
            label.invalidate();
        }
    }

    private void revealSelection() {
        if (vertical || selected < 0 || !(getParent() instanceof HorizontalScrollView)) return;
        HorizontalScrollView scroll = (HorizontalScrollView) getParent();
        View tab = getChildAt(selected);
        int left = scroll.getScrollX();
        int width = scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
        if (tab.getLeft() < left) scroll.smoothScrollTo(tab.getLeft(), 0);
        else if (tab.getRight() > left + width) scroll.smoothScrollTo(Math.max(0, tab.getRight() - width), 0);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) post(this::revealSelection);
    }

    private final class NavigationLabel extends TextView {
        private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);

        NavigationLabel(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!isSelected()) return;
            indicator.setColor(UiKit.accent(getContext()));
            if (vertical) {
                int left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - dp(2) : 0;
                canvas.drawRoundRect(left, dp(9), left + dp(2), getHeight() - dp(9), dp(1), dp(1), indicator);
            } else {
                canvas.drawRoundRect(dp(12), getHeight() - dp(2), getWidth() - dp(12), getHeight(),
                        dp(1), dp(1), indicator);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
