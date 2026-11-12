package org.levimc.launcher.core.mods.inbuilt.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.levimc.launcher.core.mods.inbuilt.model.ModIds;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class InbuiltModManager {
    private static final String PREFS_NAME = "inbuilt_mods_prefs";
    private static final String KEY_AUTOSPRINT_KEY = "autosprint_key";
    private static final String KEY_INBUILT_KEYBIND_PREFIX = "inbuilt_keybind_";
    private static final String KEY_OVERLAY_BUTTON_SIZE_PREFIX = "overlay_button_size_";
    private static final String KEY_OVERLAY_OPACITY_PREFIX = "overlay_opacity_";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_MOD_MENU_OPACITY = "mod_menu_opacity";
    private static final String KEY_MOD_MENU_BUTTON_OPACITY = "mod_menu_button_opacity";
    private static final String KEY_MOD_MENU_COMPACT = "mod_menu_compact";
    private static final String KEY_MOD_MENU_KEYBIND = "mod_menu_keybind";
    private static final String KEY_PAUSE_MENU_ONLY = "pause_menu_only";
    private static final String KEY_HIDE_LAUNCHER_HUD = "hide_launcher_hud_with_minecraft";
    private static final String KEY_FAVORITE_MOD_KEYS = "favorite_mod_keys";
    private static final String KEY_INBUILT_MOD_ENABLED_PREFIX = "inbuilt_mod_enabled_";
    private static final String KEY_EXTERNAL_MODULE_ENABLED_PREFIX = "external_module_enabled_";
    private static final String KEY_ZOOM_LEVEL = "zoom_level";
    private static final String KEY_ZOOM_KEYBIND = "zoom_keybind";
    private static final String KEY_ZOOM_TRANSITION_DURATION = "zoom_transition_duration";
    private static final String KEY_CURSOR_SENSITIVITY = "cursor_sensitivity";
    private static final String KEY_GYRO_SENSITIVITY_MULTIPLIER = "gyro_sensitivity_multiplier";
    private static final String KEY_GYRO_SENSITIVITY_X = "gyro_sensitivity_x";
    private static final String KEY_GYRO_SENSITIVITY_Y = "gyro_sensitivity_y";
    private static final String KEY_GYRO_INVERT_X = "gyro_invert_x";
    private static final String KEY_GYRO_INVERT_Y = "gyro_invert_y";
    private static final String KEY_GYRO_DEADZONE = "gyro_deadzone";
    private static final String KEY_OVERLAY_POSITION_X_PREFIX = "overlay_pos_x_";
    private static final String KEY_OVERLAY_POSITION_Y_PREFIX = "overlay_pos_y_";
    private static final String KEY_OVERLAY_LOCK_PREFIX = "overlay_lock_";
    private static final String KEY_OVERLAY_SHOW_EVERYWHERE_PREFIX = "overlay_show_everywhere_";
    private static final String KEY_HOTBAR_ITEM_ICONS = "hotbar_item_icons";
    private static final String KEY_HOTBAR_ITEM_COUNTS = "hotbar_item_counts";
    private static final String KEY_HOTBAR_SLOT_ENABLED_PREFIX = "hotbar_slot_enabled_";
    private static final int DEFAULT_OVERLAY_BUTTON_SIZE = 56;
    private static final int DEFAULT_OVERLAY_OPACITY = 100;
    private static final int MIN_MOD_MENU_OPACITY = 70;
    private static final int DEFAULT_ZOOM_LEVEL = 10;
    private static final int DEFAULT_ZOOM_TRANSITION_DURATION = 150;
    private static final int DEFAULT_CURSOR_SENSITIVITY = 120;
    private static final int DEFAULT_GYRO_SENSITIVITY_MULTIPLIER = 100;
    private static final int DEFAULT_GYRO_SENSITIVITY = 100;
    private static final int DEFAULT_GYRO_DEADZONE = 5;

    private static volatile InbuiltModManager instance;
    private final SharedPreferences prefs;
    private final AtomicLong overlayVisibilityRevision = new AtomicLong(1L);
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    private InbuiltModManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferenceListener = (sharedPreferences, key) -> {
            if (KEY_PAUSE_MENU_ONLY.equals(key) ||
                    (key != null && key.startsWith(KEY_OVERLAY_SHOW_EVERYWHERE_PREFIX))) {
                overlayVisibilityRevision.incrementAndGet();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    public static InbuiltModManager getInstance(Context context) {
        if (instance == null) {
            synchronized (InbuiltModManager.class) {
                if (instance == null) {
                    instance = new InbuiltModManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public int getAutoSprintKeybind() {
        return prefs.getInt(KEY_AUTOSPRINT_KEY, KeyEvent.KEYCODE_CTRL_LEFT);
    }

    public void setAutoSprintKeybind(int keyCode) {
        prefs.edit().putInt(KEY_AUTOSPRINT_KEY, keyCode).apply();
    }

    public int getDefaultInbuiltModKeybind(String modId) {
        if (ModIds.ZOOM.equals(modId)) return KeyEvent.KEYCODE_C;
        if (ModIds.AUTO_SPRINT.equals(modId)) return KeyEvent.KEYCODE_CTRL_LEFT;
        if (ModIds.SNAPLOOK.equals(modId)) return KeyEvent.KEYCODE_X;
        return KeyEvent.KEYCODE_UNKNOWN;
    }

    public int getInbuiltModKeybind(String modId) {
        if (ModIds.ZOOM.equals(modId)) return getZoomKeybind();
        if (ModIds.AUTO_SPRINT.equals(modId)) return getAutoSprintKeybind();
        return prefs.getInt(KEY_INBUILT_KEYBIND_PREFIX + modId, getDefaultInbuiltModKeybind(modId));
    }

    public void setInbuiltModKeybind(String modId, int keyCode) {
        if (modId == null || modId.isEmpty()) return;
        int value = Math.max(KeyEvent.KEYCODE_UNKNOWN, keyCode);
        if (ModIds.ZOOM.equals(modId)) setZoomKeybind(value);
        else if (ModIds.AUTO_SPRINT.equals(modId)) setAutoSprintKeybind(value);
        else prefs.edit().putInt(KEY_INBUILT_KEYBIND_PREFIX + modId, value).apply();
    }

    private String sharedOverlaySettingsId(String modId) {
        if (modId != null && modId.startsWith(ModIds.HOTBAR_SLOT + ":")) return ModIds.HOTBAR_SLOT;
        return modId;
    }

    public int getOverlayButtonSize(String modId) {
        String key = KEY_OVERLAY_BUTTON_SIZE_PREFIX + modId;
        if (prefs.contains(key)) return prefs.getInt(key, DEFAULT_OVERLAY_BUTTON_SIZE);
        String sharedId = sharedOverlaySettingsId(modId);
        if (sharedId != null && !sharedId.equals(modId)) {
            return prefs.getInt(KEY_OVERLAY_BUTTON_SIZE_PREFIX + sharedId, DEFAULT_OVERLAY_BUTTON_SIZE);
        }
        return DEFAULT_OVERLAY_BUTTON_SIZE;
    }

    public void setOverlayButtonSize(String modId, int sizeDp) {
        prefs.edit().putInt(KEY_OVERLAY_BUTTON_SIZE_PREFIX + modId, sizeDp).apply();
    }

    public int getOverlayOpacity(String modId) {
        String key = KEY_OVERLAY_OPACITY_PREFIX + modId;
        if (prefs.contains(key)) return prefs.getInt(key, DEFAULT_OVERLAY_OPACITY);
        String sharedId = sharedOverlaySettingsId(modId);
        if (sharedId != null && !sharedId.equals(modId)) {
            return prefs.getInt(KEY_OVERLAY_OPACITY_PREFIX + sharedId, DEFAULT_OVERLAY_OPACITY);
        }
        return DEFAULT_OVERLAY_OPACITY;
    }

    public void setOverlayOpacity(String modId, int opacity) {
        prefs.edit().putInt(KEY_OVERLAY_OPACITY_PREFIX + modId, Math.max(0, Math.min(100, opacity))).apply();
    }

    public boolean isLauncherHudHideEnabled() {
        return prefs.getBoolean(KEY_HIDE_LAUNCHER_HUD, true);
    }

    public void setLauncherHudHideEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HIDE_LAUNCHER_HUD, enabled).apply();
    }

    public boolean isNotificationsEnabled() {
        return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    public void setNotificationsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    public int getModMenuOpacity() {
        return Math.max(MIN_MOD_MENU_OPACITY, prefs.getInt(KEY_MOD_MENU_OPACITY, DEFAULT_OVERLAY_OPACITY));
    }

    public void setModMenuOpacity(int opacity) {
        prefs.edit().putInt(KEY_MOD_MENU_OPACITY, Math.max(MIN_MOD_MENU_OPACITY, Math.min(100, opacity))).apply();
    }

    public int getModMenuButtonOpacity() {
        return prefs.getInt(KEY_MOD_MENU_BUTTON_OPACITY, DEFAULT_OVERLAY_OPACITY);
    }

    public void setModMenuButtonOpacity(int opacity) {
        prefs.edit().putInt(KEY_MOD_MENU_BUTTON_OPACITY, Math.max(0, Math.min(100, opacity))).apply();
    }

    public boolean isModMenuCompact() {
        return prefs.getBoolean(KEY_MOD_MENU_COMPACT, false);
    }

    public void setModMenuCompact(boolean compact) {
        prefs.edit().putBoolean(KEY_MOD_MENU_COMPACT, compact).apply();
    }

    public int getModMenuKeybind() {
        return prefs.getInt(KEY_MOD_MENU_KEYBIND, KeyEvent.KEYCODE_M);
    }

    public void setModMenuKeybind(int keyCode) {
        prefs.edit().putInt(KEY_MOD_MENU_KEYBIND, Math.max(KeyEvent.KEYCODE_UNKNOWN, keyCode)).apply();
    }

    public boolean isPauseMenuOnly() {
        return prefs.getBoolean(KEY_PAUSE_MENU_ONLY, false);
    }

    public void setPauseMenuOnly(boolean enabled) {
        prefs.edit().putBoolean(KEY_PAUSE_MENU_ONLY, enabled).apply();
    }

    public Set<String> getFavoriteModKeys() {
        return new HashSet<>(prefs.getStringSet(KEY_FAVORITE_MOD_KEYS, new HashSet<>()));
    }

    public void setModFavorite(String favoriteKey, boolean favorite) {
        if (favoriteKey == null || favoriteKey.isEmpty()) return;
        Set<String> favorites = getFavoriteModKeys();
        if (favorite) {
            favorites.add(favoriteKey);
        } else {
            favorites.remove(favoriteKey);
        }
        prefs.edit().putStringSet(KEY_FAVORITE_MOD_KEYS, favorites).apply();
    }

    public boolean resolveInbuiltModEnabled(String modId, boolean defaultEnabled) {
        if (modId == null || modId.isEmpty()) return defaultEnabled;
        return prefs.getBoolean(KEY_INBUILT_MOD_ENABLED_PREFIX + modId, defaultEnabled);
    }

    public void setInbuiltModEnabled(String modId, boolean enabled) {
        if (modId == null || modId.isEmpty()) return;
        prefs.edit().putBoolean(KEY_INBUILT_MOD_ENABLED_PREFIX + modId, enabled).apply();
    }

    public boolean resolveExternalModuleEnabled(String moduleId, boolean defaultEnabled) {
        if (moduleId == null || moduleId.isEmpty()) return defaultEnabled;
        String key = KEY_EXTERNAL_MODULE_ENABLED_PREFIX + moduleId;
        if (!prefs.contains(key)) {
            prefs.edit().putBoolean(key, defaultEnabled).apply();
            return defaultEnabled;
        }
        return prefs.getBoolean(key, defaultEnabled);
    }

    public void setExternalModuleEnabled(String moduleId, boolean enabled) {
        if (moduleId == null || moduleId.isEmpty()) return;
        prefs.edit().putBoolean(KEY_EXTERNAL_MODULE_ENABLED_PREFIX + moduleId, enabled).apply();
    }

    public int getZoomLevel() {
        try {
            return prefs.getInt(KEY_ZOOM_LEVEL, DEFAULT_ZOOM_LEVEL);
        } catch (ClassCastException e) {
            prefs.edit().remove(KEY_ZOOM_LEVEL).apply();
            return DEFAULT_ZOOM_LEVEL;
        }
    }

    public void setZoomLevel(int level) {
        prefs.edit().putInt(KEY_ZOOM_LEVEL, Math.max(-20, Math.min(100, level))).apply();
    }

    public int getZoomKeybind() {
        return prefs.getInt(KEY_ZOOM_KEYBIND, KeyEvent.KEYCODE_C);
    }

    public void setZoomKeybind(int keyCode) {
        prefs.edit().putInt(KEY_ZOOM_KEYBIND, keyCode).apply();
    }

    public int getZoomTransitionDuration() {
        return prefs.getInt(KEY_ZOOM_TRANSITION_DURATION, DEFAULT_ZOOM_TRANSITION_DURATION);
    }

    public void setZoomTransitionDuration(int duration) {
        prefs.edit().putInt(KEY_ZOOM_TRANSITION_DURATION, Math.max(0, Math.min(1000, duration))).apply();
    }

    public int getCursorSensitivity() {
        return prefs.getInt(KEY_CURSOR_SENSITIVITY, DEFAULT_CURSOR_SENSITIVITY);
    }

    public void setCursorSensitivity(int sensitivity) {
        prefs.edit().putInt(KEY_CURSOR_SENSITIVITY, Math.max(10, Math.min(300, sensitivity))).apply();
    }

    public int getOverlayPositionX(String modId, int defaultX) {
        return prefs.getInt(KEY_OVERLAY_POSITION_X_PREFIX + modId, defaultX);
    }

    public int getOverlayPositionY(String modId, int defaultY) {
        return prefs.getInt(KEY_OVERLAY_POSITION_Y_PREFIX + modId, defaultY);
    }

    public void setOverlayPosition(String modId, int x, int y) {
        prefs.edit()
            .putInt(KEY_OVERLAY_POSITION_X_PREFIX + modId, x)
            .putInt(KEY_OVERLAY_POSITION_Y_PREFIX + modId, y)
            .apply();
    }

    public boolean isOverlayLocked(String modId) {
        return prefs.getBoolean(KEY_OVERLAY_LOCK_PREFIX + sharedOverlaySettingsId(modId), false);
    }

    public void setOverlayLocked(String modId, boolean locked) {
        prefs.edit().putBoolean(KEY_OVERLAY_LOCK_PREFIX + sharedOverlaySettingsId(modId), locked).apply();
    }

    public boolean isOverlayShowEverywhere(String modId) {
        return prefs.getBoolean(KEY_OVERLAY_SHOW_EVERYWHERE_PREFIX + sharedOverlaySettingsId(modId), false);
    }

    public void setOverlayShowEverywhere(String modId, boolean showEverywhere) {
        prefs.edit().putBoolean(KEY_OVERLAY_SHOW_EVERYWHERE_PREFIX + sharedOverlaySettingsId(modId), showEverywhere).apply();
    }

    public void clearOverlaySettings(String modId) {
        if (modId == null || modId.isEmpty()) return;
        prefs.edit()
                .remove(KEY_OVERLAY_BUTTON_SIZE_PREFIX + modId)
                .remove(KEY_OVERLAY_OPACITY_PREFIX + modId)
                .remove(KEY_OVERLAY_POSITION_X_PREFIX + modId)
                .remove(KEY_OVERLAY_POSITION_Y_PREFIX + modId)
                .remove(KEY_OVERLAY_LOCK_PREFIX + modId)
                .remove(KEY_OVERLAY_SHOW_EVERYWHERE_PREFIX + modId)
                .apply();
        overlayVisibilityRevision.incrementAndGet();
    }

    public long getOverlayVisibilityRevision() {
        return overlayVisibilityRevision.get();
    }

    public int getGyroSensitivityMultiplier() {
        return prefs.getInt(KEY_GYRO_SENSITIVITY_MULTIPLIER, DEFAULT_GYRO_SENSITIVITY_MULTIPLIER);
    }

    public void setGyroSensitivityMultiplier(int multiplier) {
        prefs.edit().putInt(KEY_GYRO_SENSITIVITY_MULTIPLIER, Math.max(25, Math.min(5000, multiplier))).apply();
    }

    public int getGyroSensitivityX() {
        return prefs.getInt(KEY_GYRO_SENSITIVITY_X, DEFAULT_GYRO_SENSITIVITY);
    }

    public void setGyroSensitivityX(int sensitivity) {
        prefs.edit().putInt(KEY_GYRO_SENSITIVITY_X, Math.max(10, Math.min(300, sensitivity))).apply();
    }

    public int getGyroSensitivityY() {
        return prefs.getInt(KEY_GYRO_SENSITIVITY_Y, DEFAULT_GYRO_SENSITIVITY);
    }

    public void setGyroSensitivityY(int sensitivity) {
        prefs.edit().putInt(KEY_GYRO_SENSITIVITY_Y, Math.max(10, Math.min(300, sensitivity))).apply();
    }

    public boolean isGyroInvertX() {
        return prefs.getBoolean(KEY_GYRO_INVERT_X, false);
    }

    public void setGyroInvertX(boolean invert) {
        prefs.edit().putBoolean(KEY_GYRO_INVERT_X, invert).apply();
    }

    public boolean isGyroInvertY() {
        return prefs.getBoolean(KEY_GYRO_INVERT_Y, false);
    }

    public void setGyroInvertY(boolean invert) {
        prefs.edit().putBoolean(KEY_GYRO_INVERT_Y, invert).apply();
    }

    public int getGyroDeadzone() {
        return prefs.getInt(KEY_GYRO_DEADZONE, DEFAULT_GYRO_DEADZONE);
    }

    public void setGyroDeadzone(int deadzone) {
        prefs.edit().putInt(KEY_GYRO_DEADZONE, Math.max(0, Math.min(100, deadzone))).apply();
    }
    public boolean isHotbarItemIconsEnabled() {
        return prefs.getBoolean(KEY_HOTBAR_ITEM_ICONS, false);
    }

    public void setHotbarItemIconsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HOTBAR_ITEM_ICONS, enabled).apply();
    }

    public boolean isHotbarItemCountsEnabled() {
        return prefs.getBoolean(KEY_HOTBAR_ITEM_COUNTS, false);
    }

    public void setHotbarItemCountsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HOTBAR_ITEM_COUNTS, enabled).apply();
    }

    public boolean isHotbarSlotEnabled(int slot) {
        if (slot < 1 || slot > 9) return false;
        return prefs.getBoolean(KEY_HOTBAR_SLOT_ENABLED_PREFIX + slot, true);
    }

    public void setHotbarSlotEnabled(int slot, boolean enabled) {
        if (slot < 1 || slot > 9) return;
        prefs.edit().putBoolean(KEY_HOTBAR_SLOT_ENABLED_PREFIX + slot, enabled).apply();
    }

}
