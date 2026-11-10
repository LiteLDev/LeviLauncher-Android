package org.levimc.launcher.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layout, motion and material settings of LeviLauncher for Windows
 * (Settings > Personalization > Layout and motion / Materials and readability, and the base layer
 * under the wallpaper). Material and base-layer values are stored per light and dark mode, as on Windows.
 */
public final class UiSettings {
    private UiSettings() {
    }

    public static final String LAYOUT_SIDEBAR = "sidebar";
    public static final String LAYOUT_NAVBAR = "navbar";

    public static final String BASE_NONE = "none";
    public static final String BASE_THEME = "theme";
    public static final String BASE_COLOR = "color";

    public static final String MATERIAL_BALANCED = "balanced";
    public static final String MATERIAL_CLEAR = "clear";
    public static final String MATERIAL_SOLID = "solid";
    public static final String MATERIAL_CUSTOM = "custom";

    private static final String PREFS = "ui_prefs";
    private static final String KEY_LAYOUT_MODE = "layout_mode";
    private static final String KEY_DISABLE_ANIMATIONS = "disable_animations";

    private static final AtomicInteger sLayoutGeneration = new AtomicInteger();
    private static volatile boolean sAnimationsDisabled;

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String mode(boolean dark) {
        return dark ? "dark" : "light";
    }

    /** Bumped when a change needs open activities to rebuild their shell. */
    public static int getLayoutGeneration() {
        return sLayoutGeneration.get();
    }

    // ---- Layout and motion ----

    public static String getLayoutMode(Context context) {
        String mode = prefs(context).getString(KEY_LAYOUT_MODE, LAYOUT_SIDEBAR);
        return LAYOUT_NAVBAR.equals(mode) ? LAYOUT_NAVBAR : LAYOUT_SIDEBAR;
    }

    public static boolean useTopNavbar(Context context) {
        return LAYOUT_NAVBAR.equals(getLayoutMode(context));
    }

    public static void setLayoutMode(Context context, String mode) {
        String value = LAYOUT_NAVBAR.equals(mode) ? LAYOUT_NAVBAR : LAYOUT_SIDEBAR;
        if (value.equals(getLayoutMode(context))) return;
        prefs(context).edit().putString(KEY_LAYOUT_MODE, value).apply();
        sLayoutGeneration.incrementAndGet();
    }

    public static boolean isAnimationsDisabledSetting(Context context) {
        return prefs(context).getBoolean(KEY_DISABLE_ANIMATIONS, false);
    }

    public static void setAnimationsDisabled(Context context, boolean disabled) {
        prefs(context).edit().putBoolean(KEY_DISABLE_ANIMATIONS, disabled).apply();
        refreshAnimations(context);
    }

    /** Re-reads the setting; the system "remove animations" option also disables them, as on Windows. */
    public static void refreshAnimations(Context context) {
        float scale = 1f;
        try {
            scale = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Exception ignored) {
        }
        sAnimationsDisabled = isAnimationsDisabledSetting(context) || scale == 0f;
    }

    public static boolean animationsDisabled() {
        return sAnimationsDisabled;
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

    // ---- Interface material (used while a wallpaper is set) ----

    public static String getMaterialPreset(Context context, boolean dark) {
        String v = prefs(context).getString("material_preset_" + mode(dark), MATERIAL_BALANCED);
        switch (v) {
            case MATERIAL_CLEAR:
            case MATERIAL_SOLID:
            case MATERIAL_CUSTOM:
                return v;
            default:
                return MATERIAL_BALANCED;
        }
    }

    /** Interface opacity 0..100 (Windows surfaceOpacity). */
    public static int getSurfaceOpacity(Context context, boolean dark) {
        return clamp(prefs(context).getInt("material_surface_" + mode(dark),
                presetSurface(getMaterialPreset(context, dark))), 0, 100);
    }

    /** Wallpaper overlay 0..80 (Windows overlayOpacity). */
    public static int getOverlayOpacity(Context context, boolean dark) {
        return clamp(prefs(context).getInt("material_overlay_" + mode(dark), dark ? 18 : 8), 0, 80);
    }

    public static boolean getReadability(Context context, boolean dark) {
        return prefs(context).getBoolean("material_readability_" + mode(dark),
                !MATERIAL_CLEAR.equals(getMaterialPreset(context, dark)));
    }

    public static void applyMaterialPreset(Context context, boolean dark, String preset) {
        prefs(context).edit()
                .putString("material_preset_" + mode(dark), preset)
                .putInt("material_surface_" + mode(dark), presetSurface(preset))
                .putInt("material_overlay_" + mode(dark), dark ? 18 : 8)
                .putBoolean("material_readability_" + mode(dark), !MATERIAL_CLEAR.equals(preset))
                .apply();
        PersonalizationManager.bumpChange();
    }

    public static void setMaterial(Context context, boolean dark, int surfaceOpacity, int overlayOpacity,
                                   boolean readability) {
        prefs(context).edit()
                .putString("material_preset_" + mode(dark), MATERIAL_CUSTOM)
                .putInt("material_surface_" + mode(dark), clamp(surfaceOpacity, 0, 100))
                .putInt("material_overlay_" + mode(dark), clamp(overlayOpacity, 0, 80))
                .putBoolean("material_readability_" + mode(dark), readability)
                .apply();
        PersonalizationManager.bumpChange();
    }

    private static int presetSurface(String preset) {
        switch (preset) {
            case MATERIAL_CLEAR: return 35;
            case MATERIAL_SOLID: return 100;
            default: return 60;
        }
    }

    /**
     * Surface opacity actually used, with readability protection applied
     * (Windows backgroundAppearance.ts: minimum opacity from mode, brightness, blur and image opacity).
     */
    public static int getEffectiveSurfaceOpacity(Context context, boolean dark, int wallpaperBrightness,
                                                 int wallpaperBlur, int wallpaperOpacity) {
        int surface = getSurfaceOpacity(context, dark);
        if (!getReadability(context, dark)) return surface;
        float overlay = getOverlayOpacity(context, dark) / 100f;
        float imageOpacity = wallpaperOpacity / 100f;
        // Android has no interface blur, so only the wallpaper blur counts toward the detail guard.
        float guard = wallpaperBlur < 8 ? 0.06f : 0f;
        float brightest = (Math.min(1f, wallpaperBrightness / 100f) * imageOpacity + 1f - imageOpacity)
                * (1f - overlay);
        float floor = dark
                ? (brightest - (0.36f - guard)) / Math.max(0.01f, brightest - 27f / 255f)
                : (0.68f + guard - overlay) / Math.max(0.01f, 1f - overlay);
        floor = Math.min(1f, Math.max(0f, floor));
        return clamp(Math.max(surface, Math.round(floor * 100)), 0, 100);
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
