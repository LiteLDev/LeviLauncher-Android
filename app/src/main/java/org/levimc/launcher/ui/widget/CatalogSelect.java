package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.levimc.launcher.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Windows-style labelled select for touch: a {@code Widget.Levi.Input} trigger (value + chevron) that opens a
 * bottom sheet listing the options, the current one highlighted.
 */
public final class CatalogSelect {

    public interface OnPick {
        void onPick(int index);
    }

    private final TextView trigger;
    private CharSequence title;
    private List<String> items = Collections.emptyList();
    private int selected;
    @Nullable
    private OnPick listener;

    public CatalogSelect(TextView trigger, CharSequence title) {
        this.trigger = trigger;
        this.title = title;
        trigger.setOnClickListener(v -> open());
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setItems(List<String> items, int selected) {
        this.items = new ArrayList<>(items == null ? Collections.emptyList() : items);
        setSelected(selected);
    }

    public void setSelected(int index) {
        selected = items.isEmpty() ? 0 : Math.max(0, Math.min(index, items.size() - 1));
        trigger.setText(items.isEmpty() ? "" : items.get(selected));
    }

    public int getSelected() {
        return selected;
    }

    public void setOnPick(@Nullable OnPick listener) {
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) {
        trigger.setEnabled(enabled);
        trigger.setAlpha(enabled ? 1f : 0.5f);
    }

    private void open() {
        if (items.isEmpty() || !trigger.isEnabled()) {
            return;
        }
        show(trigger.getContext(), title, items, selected, index -> {
            if (index == selected) {
                return;
            }
            setSelected(index);
            if (listener != null) {
                listener.onPick(index);
            }
        });
    }

    /** Shows a one-choice bottom sheet; {@code onPick} gets the tapped row. */
    public static void show(Context ctx, CharSequence title, List<String> items, int selected, OnPick onPick) {
        BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        Typeface font = ResourcesCompat.getFont(ctx, R.font.misans);
        int accentText = UiKit.accentText(ctx);
        int primaryText = UiKit.color(ctx, R.color.text_primary);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(ctx, R.color.surface));
        root.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 12));

        TextView heading = new TextView(ctx);
        heading.setText(title);
        heading.setTextAppearance(R.style.TextAppearance_Levi_SectionTitle);
        heading.setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 8));
        root.addView(heading);

        NestedScrollView scroll = new NestedScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            boolean isSelected = i == selected;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(ctx, 52));
            row.setPadding(dp(ctx, 14), 0, dp(ctx, 10), 0);
            if (isSelected) {
                UiKit.tintSoftAccent(row, 12);
            } else {
                row.setBackgroundResource(R.drawable.bg_ui_row);
            }

            TextView label = new TextView(ctx);
            label.setText(items.get(i));
            label.setTypeface(font, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(isSelected ? accentText : primaryText);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (isSelected) {
                ImageView check = new ImageView(ctx);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(accentText));
                row.addView(check, new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)));
            }
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.onPick(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(ctx, 2);
            list.addView(row, lp);
        }

        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            dialog.getBehavior().setSkipCollapsed(true);
            if (selected > 0 && selected < list.getChildCount()) {
                View target = list.getChildAt(selected);
                scroll.post(() -> scroll.scrollTo(0, Math.max(0, target.getTop() - dp(ctx, 52))));
            }
        });
        dialog.show();
    }

    private static int dp(Context ctx, float v) {
        return Math.round(UiKit.dp(ctx, v));
    }
}
