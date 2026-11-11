package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class UiSettings {
    private UiSettings() {
    }

    public static final String BASE_NONE = "none";
    public static final String BASE_THEME = "theme";
    public static final String BASE_COLOR = "color";

    private static final String PREFS = "ui_prefs";

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String mode(boolean dark) {
        return dark ? "dark" : "light";
    }

    // ---- Base layer under the wallpaper ----

    public static String getBaseMode(Context context, boolean dark) {
        String v = prefs(context).getString("base_mode_" + mode(dark), BASE_NONE);
        return BASE_THEME.equals(v) || BASE_COLOR.equals(v) ? v : BASE_NONE;
    }

    public static int getBaseColor(Context context, boolean dark) {
        return prefs(context).getInt("base_color_" + mode(dark), dark ? 0xFF18181B : 0xFFFFFFFF);
    }

    public static int getBaseOpacity(Context context, boolean dark) {
        return clamp(prefs(context).getInt("base_opacity_" + mode(dark), 50), 0, 100);
    }

    public static void setBase(Context context, boolean dark, String baseMode, int color, int opacity) {
        prefs(context).edit()
                .putString("base_mode_" + mode(dark), baseMode)
                .putInt("base_color_" + mode(dark), color | 0xFF000000)
                .putInt("base_opacity_" + mode(dark), clamp(opacity, 0, 100))
                .apply();
        PersonalizationManager.bumpBackgroundEffects();
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
