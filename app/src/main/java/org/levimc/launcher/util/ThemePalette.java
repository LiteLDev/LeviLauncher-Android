package org.levimc.launcher.util;

import android.graphics.Color;

/**
 * Theme colors shared with LeviLauncher for Windows (frontend/src/constants/themes.ts).
 * PRESETS is the "Preset colors" group, MORE the "More colors" group; the default is emerald.
 */
public final class ThemePalette {
    private ThemePalette() {
    }

    public static final int DEFAULT_ACCENT = 0xFF10B981;

    /** Windows THEME_GROUPS.preset, in display order. */
    public static final int[] PRESETS = {
            0xFF10B981, // emerald
            0xFF3B82F6, // blue
            0xFF8B5CF6, // violet
            0xFFF59E0B, // amber
            0xFFFF6B8B, // pink
            0xFF06B6D4, // cyan
            0xFFFFB900, // Windows yellow
            0xFFFF8C00, // Windows orange
            0xFFD13438, // Windows red
            0xFF0078D7, // Windows blue
            0xFF10893E, // Windows green
            0xFF6B69D6, // Windows purple
            0xFF0099BC, // Windows teal
            0xFFE74856, // Windows pink red
    };

    /** Windows THEME_GROUPS.generated (Windows accent colors), in display order. */
    public static final int[] MORE = {
            0xFFF7630C, 0xFFCA5010, 0xFFDA3B01, 0xFFEF6950, 0xFFFF4343, 0xFFE81123,
            0xFFEA005E, 0xFFC30052, 0xFFE3008C, 0xFFBF0077, 0xFFC239B3, 0xFF9A0089,
            0xFF0063B1, 0xFF8E8CD8, 0xFF8764B8, 0xFF744DA9, 0xFFB146C2, 0xFF881798,
            0xFF2D7D9A, 0xFF00B7C3, 0xFF038387, 0xFF00B294, 0xFF018574, 0xFF00CC6A,
            0xFF7A7574, 0xFF5D5A58, 0xFF68768A, 0xFF515C6B, 0xFF567C73, 0xFF486860,
            0xFF498205, 0xFF107C10, 0xFF767676, 0xFF4C4A48, 0xFF69797E,
    };

    /** Mixes {@code color} toward {@code target} by {@code amount} (0..1), like CSS color-mix. */
    public static int mix(int color, int target, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(color) + (Color.red(target) - Color.red(color)) * a);
        int g = Math.round(Color.green(color) + (Color.green(target) - Color.green(color)) * a);
        int b = Math.round(Color.blue(color) + (Color.blue(target) - Color.blue(color)) * a);
        return Color.rgb(r, g, b);
    }

    /** Windows generateTheme(): shade 50..950 of {@code base} (500 = base). */
    public static int shade(int base, int step) {
        switch (step) {
            case 50: return mix(base, Color.WHITE, 0.95f);
            case 100: return mix(base, Color.WHITE, 0.90f);
            case 200: return mix(base, Color.WHITE, 0.75f);
            case 300: return mix(base, Color.WHITE, 0.50f);
            case 400: return mix(base, Color.WHITE, 0.25f);
            case 600: return mix(base, Color.BLACK, 0.15f);
            case 700: return mix(base, Color.BLACK, 0.30f);
            case 800: return mix(base, Color.BLACK, 0.50f);
            case 900: return mix(base, Color.BLACK, 0.70f);
            case 950: return mix(base, Color.BLACK, 0.85f);
            default: return base | 0xFF000000;
        }
    }

    public static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(Math.max(0f, Math.min(1f, alpha)) * 255),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    public static double luminance(int color) {
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color))
                + 0.0722 * channel(Color.blue(color));
    }

    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Accent made readable on {@code surface}: darkened (light theme) or lightened (dark theme)
     * until it reaches {@code ratio}, as Windows does for brand-foreground (4.6) and brand-heading (3.1).
     */
    public static int readable(int accent, int surface, boolean dark, double ratio) {
        int target = dark ? Color.WHITE : Color.BLACK;
        int c = accent | 0xFF000000;
        for (int i = 0; i <= 20 && contrast(c, surface) < ratio; i++) {
            c = mix(accent, target, i * 0.05f);
        }
        return c;
    }

    /** Heading color (page titles) for the accent: Windows brand-heading. */
    public static int heading(int accent, boolean dark) {
        return readable(accent, dark ? 0xFF27272A : 0xFFF4F4F5, dark, 3.1);
    }

    /** Text color for accent text on surfaces: Windows brand-foreground. */
    public static int foreground(int accent, boolean dark) {
        return readable(accent, dark ? 0xFF27272A : 0xFFF4F4F5, dark, 4.6);
    }

    /** Label color on a filled accent button. */
    public static int onAccent(int accent) {
        return contrast(accent, 0xFFF4F4F5) >= 2.2 ? 0xFFF4F4F5 : 0xFF18181B;
    }
}
