package org.levimc.launcher.ui.world;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

public final class WorldUi {
    private WorldUi() {}

    public static int dp(Context c, float value) {
        return Math.round(c.getResources().getDisplayMetrics().density * value);
    }

    public static int color(Context c, int resource) {
        int base = c.getResources().getColor(resource, c.getTheme());
        if (resource == R.color.background
                || resource == R.color.surface
                || resource == R.color.surface_variant) {
            int custom = new PersonalizationManager(c).getAccentColor();
            if (custom != 0) {
                float[] surface = new float[3], accent = new float[3];
                androidx.core.graphics.ColorUtils.colorToHSL(base, surface);
                androidx.core.graphics.ColorUtils.colorToHSL(custom, accent);
                surface[0] = accent[0];
                surface[1] = Math.min(.12f, accent[1] * .12f);
                return androidx.core.graphics.ColorUtils.HSLToColor(surface);
            }
        }
        return base;
    }

    public static int accent(Context c) {
        int custom = new PersonalizationManager(c).getAccentColor();
        return custom == 0 ? color(c, R.color.primary) : custom;
    }

    public static int translucent(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    public static GradientDrawable background(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    public static android.graphics.drawable.RippleDrawable ripple(
            Context c, int color, int radius) {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(translucent(accent(c), 36)),
                background(c, color, radius),
                background(c, Color.WHITE, radius));
    }

    public static TextView text(Context c, String text, float size, boolean secondary) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color(c, secondary ? R.color.text_secondary : R.color.on_surface));
        Typeface font = ResourcesCompat.getFont(c, R.font.misans);
        if (font != null) v.setTypeface(font);
        return v;
    }

    public static MaterialButton button(Context c, String label, Runnable click) {
        MaterialButton b =
                new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonStyle);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        b.setStrokeWidth(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(ResourcesCompat.getFont(c, R.font.misans));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_enabled}, new int[] {}},
                        new int[] {accent(c), translucent(color(c, R.color.on_surface), 85)}));
        b.setRippleColor(ColorStateList.valueOf(translucent(accent(c), 36)));
        b.setCornerRadius(dp(c, 10));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    public static ImageView iconButton(Context c, int icon, String label, Runnable action) {
        ImageView view = new ImageView(c);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(color(c, R.color.text_secondary)));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(c, 11), dp(c, 11), dp(c, 11), dp(c, 11));
        view.setContentDescription(label);
        view.setTooltipText(label);
        android.util.TypedValue background = new android.util.TypedValue();
        c.getTheme()
                .resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, background, true);
        view.setBackgroundResource(background.resourceId);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    public static ImageView backButton(Context c, Runnable action) {
        ImageView view =
                iconButton(c, R.drawable.ic_arrow_down, c.getString(R.string.back), action);
        view.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        view.setRotation(90);
        return view;
    }

    public static EditText input(Context c, String hint, String value, boolean multiline) {
        EditText e = new EditText(c);
        e.setTypeface(ResourcesCompat.getFont(c, R.font.misans));
        e.setTextSize(13);
        e.setTextColor(color(c, R.color.on_surface));
        e.setHintTextColor(color(c, R.color.text_secondary));
        e.setHint(hint);
        e.setText(value);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setBackground(background(c, color(c, R.color.surface_variant), 8));
        e.setSingleLine(!multiline);
        e.setInputType(
                InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        e.setBackgroundTintList(null);
        e.setHighlightColor(translucent(accent(c), 60));
        return e;
    }

    public static LinearLayout column(Context c, int padding) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padding);
        v.setPadding(p, p, p, p);
        return v;
    }

    public static void gap(LinearLayout c, int dp) {
        View v = new View(c.getContext());
        c.addView(v, new LinearLayout.LayoutParams(1, dp(c.getContext(), dp)));
    }

    public static AlertDialog show(Context c, MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(dialog, 560);
        dialog.show();
        org.levimc.launcher.ui.dialogs.LauncherDialogs.style(dialog, 560);
        return dialog;
    }
}
