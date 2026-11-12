package org.levimc.launcher.ui.views;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.widget.UiKit;

import java.util.ArrayList;
import java.util.List;

public final class ContentActionPopup {
    public static final class Action {
        private final int iconRes;
        private final int labelRes;
        private final boolean destructive;
        private final Runnable callback;

        public Action(int iconRes, int labelRes, boolean destructive, Runnable callback) {
            this.iconRes = iconRes;
            this.labelRes = labelRes;
            this.destructive = destructive;
            this.callback = callback;
        }
    }

    private ContentActionPopup() {
    }

    public static void show(View anchor, CharSequence title, List<Action> actions) {
        if (!anchor.isAttachedToWindow() || actions == null || actions.isEmpty()) return;
        List<Action> choices = new ArrayList<>(actions);
        Context context = anchor.getContext();
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Typeface font = ResourcesCompat.getFont(context, R.font.misans);
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        Rect bounds = new Rect();
        anchor.getWindowVisibleDisplayFrame(bounds);
        int availableWidth = bounds.width() > 0 ? bounds.width() : context.getResources().getDisplayMetrics().widthPixels;
        int availableHeight = Math.max(bounds.height(), anchor.getRootView().getHeight());
        if (availableHeight <= 0) availableHeight = context.getResources().getDisplayMetrics().heightPixels;
        int columns = Math.min(3, choices.size());
        int rows = (choices.size() + columns - 1) / columns;
        int tileHeight = Math.max(dp(context, 48), Math.min(dp(context, 72),
                (availableHeight - dp(context, 88) - dp(context, 8) * (rows - 1)) / rows));
        boolean compact = tileHeight < dp(context, 64);
        int iconSize = compact ? (tileHeight < dp(context, 52) ? 14 : 16) : 20;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12));
        GradientDrawable surface = shape(context, UiKit.color(context, R.color.surface_variant), 14);
        surface.setStroke(dp(context, 1), UiKit.color(context, R.color.ui_border));
        root.setBackground(surface);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTypeface(font, Typeface.BOLD);
        heading.setTextSize(14);
        heading.setTextColor(UiKit.color(context, R.color.text_primary));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setContentDescription(title);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(UiKit.color(context, R.color.text_secondary)));
        close.setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7));
        close.setContentDescription(context.getString(R.string.close));
        close.setFocusable(true);
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20808080), null, shape(context, Color.WHITE, 16)));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(context, 32)));
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < columns; column++) {
                int index = rowIndex * columns + column;
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, tileHeight, 1);
                if (column > 0) tileParams.setMarginStart(dp(context, 8));
                if (index >= choices.size()) {
                    row.addView(new View(context), tileParams);
                    continue;
                }
                Action action = choices.get(index);
                LinearLayout tile = new LinearLayout(context);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(context, 6), dp(context, compact ? 2 : 4), dp(context, 6), dp(context, compact ? 2 : 4));
                int labelColor = UiKit.color(context, action.destructive ? R.color.ui_danger : R.color.text_primary);
                int fillColor = UiKit.color(context, action.destructive ? R.color.ui_danger_soft : R.color.ui_default);
                tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20808080),
                        shape(context, fillColor, 9), shape(context, Color.WHITE, 9)));
                ImageView icon = new ImageView(context);
                icon.setImageResource(action.iconRes);
                icon.setImageTintList(ColorStateList.valueOf(labelColor));
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                tile.addView(icon, new LinearLayout.LayoutParams(dp(context, iconSize), dp(context, iconSize)));
                TextView label = new TextView(context);
                label.setText(action.labelRes);
                label.setTypeface(font);
                label.setTextSize(compact ? 11 : 12);
                label.setTextColor(labelColor);
                label.setGravity(Gravity.CENTER);
                label.setIncludeFontPadding(false);
                label.setMaxLines(2);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
                labelParams.topMargin = dp(context, compact ? 3 : 5);
                tile.addView(label, labelParams);
                tile.setContentDescription(context.getString(action.labelRes));
                tile.setFocusable(true);
                tile.setOnClickListener(v -> {
                    dialog.dismiss();
                    action.callback.run();
                });
                row.addView(tile, tileParams);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(context, 8);
            root.addView(row, rowParams);
        }
        dialog.setContentView(root);
        View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                dialog.dismiss();
            }
        };
        anchor.addOnAttachStateChangeListener(attachment);
        dialog.setOnDismissListener(ignored -> anchor.removeOnAttachStateChangeListener(attachment));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.35f);
            window.setGravity(Gravity.CENTER);
            window.setLayout(Math.min(dp(context, 480), Math.max(1, availableWidth - dp(context, 32))), -2);
        }
    }

    private static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
