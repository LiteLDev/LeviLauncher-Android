package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.content.Context;
import android.content.SharedPreferences;

final class ModMenuPreferences {
    private final SharedPreferences prefs;

    ModMenuPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("mod_menu_wind_appearance", Context.MODE_PRIVATE);
    }

    int number(String key, int fallback, int min, int max) {
        return ModMenuGeometry.clamp(prefs.getInt(key, fallback), min, max);
    }

    boolean flag(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }
    void put(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    void put(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    int mode() { return number("theme", 1, 0, 2); }
    int accentSource() { return number("accent_source", 0, 0, 1); }
    int customAccent() { return prefs.getInt("accent", 0xFF737380) | 0xFF000000; }
    int palette() { return number("palette", 0, 0, ModMenuTheme.PRESETS.length - 1); }
    int elementRadius() { return number("element_radius", 8, 3, 14); }
    int radius() { return number("radius", 14, 6, 22); }
    int textScale() { return number("text_scale", 100, 85, 135); }
    int width(boolean compact) { return number(compact ? "list_width" : "width", compact ? 52 : 100, 35, 100); }
    int height() { return number("height", 100, 55, 100); }
    int positionX() { return number("position_x", 500, 0, 1000); }
    int positionY() { return number("position_y", 500, 0, 1000); }
    int dim() { return number("dim", 10, 0, 60); }
    int buttonSize() { return number("button_size", 53, 32, 76); }
    int buttonShape() { return number("button_shape", 1, 0, 2); }
    int notificationPosition() { return number("notification_position", 5, 0, 5); }
    int notificationDuration() { return number("notification_duration", 2500, 1000, 6000); }
    int notificationLimit() { return number("notification_limit", 3, 1, 4); }
    boolean notificationProgress() { return flag("notification_progress", true); }
    boolean notificationCompact() { return flag("notification_compact", false); }
    boolean notifyDisabled() { return flag("notification_disabled", false); }

    void saveGeometry(int width, int height, int x, int y, boolean compact) {
        prefs.edit().putInt(compact ? "list_width" : "width", width).putInt("height", height)
                .putInt("position_x", x).putInt("position_y", y).apply();
    }

    void resetPosition() {
        prefs.edit().remove("width").remove("list_width").remove("height")
                .remove("position_x").remove("position_y").apply();
    }

    void reset() { prefs.edit().clear().apply(); }
}
