package org.levimc.launcher.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.ThemePalette;

/**
 * Helpers for the Windows-aligned look that need the chosen theme color at runtime
 * (XML can only reference the default emerald).
 */
public final class UiKit {
    private UiKit() {
    }

    // Icon-chip palette of the Windows launcher (Tailwind 500 colors).
    public static final int BLUE = 0xFF3B82F6;
    public static final int PURPLE = 0xFFA855F7;
    public static final int ORANGE = 0xFFF97316;
    public static final int PINK = 0xFFEC4899;
    public static final int INDIGO = 0xFF6366F1;
    public static final int TEAL = 0xFF14B8A6;
    public static final int AMBER = 0xFFF59E0B;
    public static final int ROSE = 0xFFF43F5E;
    public static final int GREEN = 0xFF22C55E;
    public static final int RED = 0xFFEF4444;

    public static boolean isDark(Context ctx) {
        return new PersonalizationManager(ctx).isDarkMode(ctx);
    }

    public static int accent(Context ctx) {
        return new PersonalizationManager(ctx).getAccentColor(isDark(ctx));
    }

    /** Page title color (Windows brand-heading). */
    public static int heading(Context ctx) {
        return ThemePalette.heading(accent(ctx), isDark(ctx));
    }

    /** Accent text on surfaces (Windows brand-foreground). */
    public static int accentText(Context ctx) {
        return ThemePalette.foreground(accent(ctx), isDark(ctx));
    }

    /** Soft accent fill (selected rows, accent tags): brand-500 at 10-15%. */
    public static int accentSoft(Context ctx) {
        return ThemePalette.withAlpha(accent(ctx), isDark(ctx) ? 0.16f : 0.12f);
    }

    public static void applyHeading(TextView title) {
        title.setTextColor(heading(title.getContext()));
    }

    /** Filled theme-color button. */
    public static void applyPrimary(MaterialButton button) {
        int accent = accent(button.getContext());
        int label = ThemePalette.onAccent(accent);
        button.setBackgroundTintList(ColorStateList.valueOf(accent));
        button.setTextColor(label);
        button.setIconTint(ColorStateList.valueOf(label));
    }

    /** Soft accent pill or tag background, keeping the view's corner radius. */
    public static void tintSoftAccent(View view, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(view.getContext(), radiusDp));
        gd.setColor(accentSoft(view.getContext()));
        view.setBackground(gd);
        if (view instanceof TextView) ((TextView) view).setTextColor(accentText(view.getContext()));
    }

    public static void tintIconChip(View chip, ImageView icon, int color) {
        Context ctx = chip.getContext();
        PersonalizationManager personalization = new PersonalizationManager(ctx);
        boolean dark = personalization.isDarkMode(ctx);
        float opacity = dark ? 0.55f : 0.12f;
        if (personalization.hasBackgroundImage()) {
            opacity = Math.min(opacity, Color.alpha(personalization.getEffectiveSurfaceColor()) / 255f);
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(ctx, 10));
        gd.setColor(ThemePalette.withAlpha(dark ? ThemePalette.shade(color, 900) : color, opacity));
        chip.setBackground(gd);
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(color));
    }

    /** Selected-row / current-item highlight: soft accent fill with a 2dp accent ring. */
    public static Drawable selectedRing(Context ctx, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(ctx, radiusDp));
        gd.setColor(ThemePalette.withAlpha(accent(ctx), isDark(ctx) ? 0.12f : 0.07f));
        gd.setStroke(Math.round(dp(ctx, 2)), accent(ctx));
        return gd;
    }

    public static int color(Context ctx, int res) {
        return ContextCompat.getColor(ctx, res);
    }

    public static int secondaryText(Context ctx) {
        return ContextCompat.getColor(ctx, R.color.text_secondary);
    }

    public static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    public static int alpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
