package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.TextViewCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Segmented tabs as in LeviLauncher for Windows (HeroUI Tabs): a grey track, the selected
 * segment is a raised white (dark: zinc) pill. Each segment is at least 40dp tall for touch.
 *
 * <pre>
 * control.setItems(getString(R.string.a), getString(R.string.b));
 * control.setOnSegmentSelectedListener(index -> ...);
 * control.setSelectedIndex(0, false);
 * </pre>
 */
public class SegmentedControl extends LinearLayout {
    public interface OnSegmentSelectedListener {
        void onSegmentSelected(int index);
    }

    private final List<TextView> segments = new ArrayList<>();
    private int selected = -1;
    private boolean equalWidth = true;
    private float textSizeSp = 13f;
    @Nullable
    private Typeface baseTypeface;
    @Nullable
    private OnSegmentSelectedListener listener;

    public SegmentedControl(Context context) {
        this(context, null);
    }

    public SegmentedControl(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        applyTrack();
    }

    /** True (default): segments share the width. False: each segment wraps its label (scrollable rows). */
    public void setEqualWidth(boolean equalWidth) {
        this.equalWidth = equalWidth;
        for (TextView t : segments) t.setLayoutParams(segmentParams());
    }

    public void setTextSizeSp(float sp) {
        textSizeSp = sp;
        for (TextView t : segments) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    public void setItems(CharSequence... labels) {
        removeAllViews();
        segments.clear();
        for (int i = 0; i < labels.length; i++) addSegment(labels[i], 0);
        if (labels.length > 0) setSelectedIndex(Math.max(0, Math.min(selected, labels.length - 1)), false);
    }

    /** Adds a segment with an optional leading icon (0 for none). Returns its index. */
    public int addSegment(CharSequence label, int iconRes) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        if (baseTypeface == null) baseTypeface = ResourcesCompat.getFont(getContext(), R.font.misans);
        t.setTypeface(baseTypeface);
        t.setMinHeight(dp(32));
        t.setPadding(dp(16), 0, dp(16), 0);
        if (iconRes != 0) {
            Drawable d = ContextCompat.getDrawable(getContext(), iconRes);
            if (d != null) {
                d.setBounds(0, 0, dp(16), dp(16));
                t.setCompoundDrawablesRelative(d, null, null, null);
                t.setCompoundDrawablePadding(dp(6));
            }
        }
        final int index = segments.size();
        t.setOnClickListener(v -> {
            if (index != selected) setSelectedIndex(index, true);
        });
        segments.add(t);
        addView(t, segmentParams());
        applyState();
        return index;
    }

    private LayoutParams segmentParams() {
        // v1.6.1 tab pills size to their label, 8dp apart.
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        return lp;
    }

    private void applyTrack() {
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setMinimumHeight(0);
        new PersonalizationManager(getContext()).applyGlassToView(this);
    }

    public void setOnSegmentSelectedListener(@Nullable OnSegmentSelectedListener listener) {
        this.listener = listener;
    }

    public int getSelectedIndex() {
        return selected;
    }

    public int getSegmentCount() {
        return segments.size();
    }

    public TextView getSegment(int index) {
        return segments.get(index);
    }

    public void setSelectedIndex(int index, boolean notify) {
        if (index < 0 || index >= segments.size()) return;
        selected = index;
        applyState();
        if (notify && listener != null) listener.onSegmentSelected(index);
    }

    private void applyState() {
        int active = Color.WHITE;
        int inactive = ContextCompat.getColor(getContext(), R.color.text_secondary);
        PersonalizationManager personalization = new PersonalizationManager(getContext());
        for (int i = 0; i < segments.size(); i++) {
            TextView t = segments.get(i);
            boolean on = i == selected;
            if (on) {
                GradientDrawable background = new GradientDrawable();
                background.setCornerRadius(dp(16));
                background.setColor(UiKit.accent(getContext()));
                t.setBackground(background);
            } else {
                t.setBackgroundResource(R.drawable.bg_tab_unselected);
            }
            t.setElevation(0);
            t.setTextColor(on ? active : inactive);
            t.setTypeface(Typeface.create(baseTypeface, on ? Typeface.BOLD : Typeface.NORMAL));
            TextViewCompat.setCompoundDrawableTintList(t, ColorStateList.valueOf(on ? active : inactive));
            t.setSelected(on);
            personalization.applyGlassToView(t);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (View v : segments) v.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.5f);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
