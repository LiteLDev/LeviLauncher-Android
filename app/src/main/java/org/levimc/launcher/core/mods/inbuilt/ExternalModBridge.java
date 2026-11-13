package org.levimc.launcher.core.mods.inbuilt;

import android.util.Log;

import org.levimc.launcher.core.mods.ModManager;

public class ExternalModBridge {
    private static final String TAG = "ExternalModBridge";

    private static native int nativeGetExternalModCount();
    private static native boolean nativeIsFloatingModMenuButtonHidden();
    private static native String nativeGetExternalModInfo(int index);
    private static native String nativeGetExternalModsInfo();
    private static native String nativeGetExternalModConfigSchema(String moduleId);
    private static native long nativeGetExternalModConfigSchemaRevision(String moduleId);
    private static native void nativeToggleExternalMod(String moduleId, boolean enabled);
    private static native void nativeSetExternalModConfig(String moduleId, String key, String value);
    private static native int nativeGetExternalButtonCount();
    private static native String nativeGetExternalButtonInfo(int index);
    private static native byte[] nativeGetExternalButtonIconBytes(String buttonId, int width, int height, boolean active);
    private static native void nativeDispatchExternalButtonEvent(String buttonId, int event, float value);
    public static native byte[] nativeGetRegisteredFontBytes(String fontId);
    public static native Object[] nativeGetRegisteredImage(String imageId);

    public static int getExternalModCount() {
        if (!ModManager.ensurePreloaderLoaded()) return 0;
        try {
            return nativeGetExternalModCount();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeGetExternalModCount not available", e);
            return 0;
        }
    }

    private static volatile boolean floatingButtonApiAvailable = true;

    public static boolean isFloatingModMenuButtonHidden() {
        if (!floatingButtonApiAvailable || !ModManager.ensurePreloaderLoaded()) return false;
        try {
            return nativeIsFloatingModMenuButtonHidden();
        } catch (UnsatisfiedLinkError e) {
            floatingButtonApiAvailable = false;
            return false;
        }
    }

    public static String getExternalModInfo(int index) {
        if (!ModManager.ensurePreloaderLoaded()) return "{}";
        try {
            return nativeGetExternalModInfo(index);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeGetExternalModInfo not available", e);
            return "{}";
        }
    }

    public static String getExternalModsInfo() {
        if (!ModManager.ensurePreloaderLoaded()) return null;
        try {
            return nativeGetExternalModsInfo();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    public static String getExternalModConfigSchema(String moduleId) {
        if (!ModManager.ensurePreloaderLoaded()) return null;
        try {
            return nativeGetExternalModConfigSchema(moduleId);
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    public static long getExternalModConfigSchemaRevision(String moduleId) {
        if (!ModManager.ensurePreloaderLoaded()) return 0L;
        try {
            return nativeGetExternalModConfigSchemaRevision(moduleId);
        } catch (UnsatisfiedLinkError e) {
            return 0L;
        }
    }

    public static void toggleExternalMod(String moduleId, boolean enabled) {
        if (!ModManager.ensurePreloaderLoaded()) return;
        try {
            nativeToggleExternalMod(moduleId, enabled);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeToggleExternalMod not available", e);
        }
    }

    public static void setExternalModConfig(String moduleId, String key, String value) {
        if (!ModManager.ensurePreloaderLoaded()) return;
        try {
            nativeSetExternalModConfig(moduleId, key, value);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeSetExternalModConfig not available", e);
        }
    }

    public static int getExternalButtonCount() {
        if (!ModManager.ensurePreloaderLoaded()) return 0;
        try {
            return nativeGetExternalButtonCount();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeGetExternalButtonCount not available", e);
            return 0;
        }
    }

    public static String getExternalButtonInfo(int index) {
        if (!ModManager.ensurePreloaderLoaded()) return "{}";
        try {
            return nativeGetExternalButtonInfo(index);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeGetExternalButtonInfo not available", e);
            return "{}";
        }
    }

    public static void dispatchExternalButtonEvent(String buttonId, int event, float value) {
        if (!ModManager.ensurePreloaderLoaded()) return;
        try {
            nativeDispatchExternalButtonEvent(buttonId, event, value);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeDispatchExternalButtonEvent not available", e);
        }
    }

    public static byte[] getExternalButtonIconBytes(String buttonId, int width, int height, boolean active) {
        if (!ModManager.ensurePreloaderLoaded()) return null;
        try {
            return nativeGetExternalButtonIconBytes(buttonId, width, height, active);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "nativeGetExternalButtonIconBytes not available", e);
            return null;
        }
    }

    public static class ExternalButton {
        public static final int BEHAVIOR_CLICK = 0;
        public static final int BEHAVIOR_HOLD = 1;
        public static final int BEHAVIOR_TOGGLE = 2;

        public static final int EVENT_CLICK = 0;
        public static final int EVENT_DOWN = 1;
        public static final int EVENT_UP = 2;
        public static final int EVENT_STATE_CHANGED = 3;
        public static final int EVENT_SCROLL = 4;

        public static final int STYLE_KEYCAP = 0;
        public static final int STYLE_ACCENT = 1;

        public static final int ICON_AUTO = 0;
        public static final int ICON_PNG = 1;
        public static final int ICON_WEBP = 2;
        public static final int ICON_SVG = 3;
        public static final int ICON_RESOURCE = 4;

        public String buttonId;
        public String moduleId;
        public String displayName;
        public String modId;
        public String label;
        public int androidKeyCode;
        public int behavior;
        public boolean defaultVisible;
        public boolean moduleEnabled;
        public int stylePreset = STYLE_KEYCAP;
        public int normalBgColor;
        public int activeBgColor;
        public int borderColor;
        public int textColor;
        public int activeTextColor;
        public float widthScale;
        public float heightScale;
        public boolean hasIcon;
        public int iconFormat = ICON_AUTO;
        public boolean hideLabelWhenIconPresent = true;

        public String positionKey() {
            return "external_button:" + buttonId;
        }
    }

    public static ExternalButton getExternalButton(int index) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(getExternalButtonInfo(index));
            String buttonId = obj.optString("button_id", "");
            String moduleId = obj.optString("module_id", "");
            if (buttonId.isEmpty() || moduleId.isEmpty()) return null;

            ExternalButton button = new ExternalButton();
            button.buttonId = buttonId;
            button.moduleId = moduleId;
            button.displayName = obj.optString("display_name", buttonId);
            button.modId = obj.optString("mod_id", "");
            button.label = obj.optString("label", "");
            button.androidKeyCode = obj.optInt("android_key_code", 0);
            button.behavior = obj.optInt("behavior", ExternalButton.BEHAVIOR_CLICK);
            button.defaultVisible = obj.optBoolean("default_visible", true);
            button.moduleEnabled = obj.optBoolean("module_enabled", false);
            button.hasIcon = obj.optBoolean("has_icon", false);
            button.iconFormat = obj.optInt("icon_format", ExternalButton.ICON_AUTO);
            button.hideLabelWhenIconPresent = obj.optBoolean("hide_label_when_icon_present", true);
            org.json.JSONObject style = obj.optJSONObject("style");
            if (style != null) {
                button.stylePreset = style.optInt("preset", ExternalButton.STYLE_KEYCAP);
                button.normalBgColor = optColor(style, "normal_bg_color");
                button.activeBgColor = optColor(style, "active_bg_color");
                button.borderColor = optColor(style, "border_color");
                button.textColor = optColor(style, "text_color");
                button.activeTextColor = optColor(style, "active_text_color");
                button.widthScale = (float) style.optDouble("width_scale", 0.0);
                button.heightScale = (float) style.optDouble("height_scale", 0.0);
            }
            return button;
        } catch (Exception e) {
            return null;
        }
    }

    private static int optColor(org.json.JSONObject obj, String key) {
        long value = obj.optLong(key, 0L);
        return (int) (value & 0xFFFFFFFFL);
    }

    private static final DrawCommand[] EMPTY_DRAW_COMMANDS = new DrawCommand[0];
    private static volatile boolean drawRevisionAvailable = true;
    private static volatile boolean drawCommandsAvailable = true;
    public static native Object[] nativeGetDrawCommands();
    private static native long nativeGetDrawCommandsRevision();
    private static native String nativeGetHudEditorElementsInfo();
    private static native void nativeSetHudSurfaceSize(float width, float height);

    public static class DrawCommand {
        public static final int TYPE_TEXT = 0;
        public static final int TYPE_RECT = 1;
        public static final int TYPE_LINE = 2;
        public static final int TYPE_RECT_FILLED = 3;
        public static final int TYPE_CIRCLE_FILLED = 4;
        public static final int TYPE_TRIANGLE_FILLED = 5;
        public static final int TYPE_IMAGE = 6;

        public int type;
        public float x, y, w, h;
        public float x3, y3;
        public int color;
        public float size;
        public String text;
        public String moduleId;
        public String fontId;
        public String imageId;
    }

    public static class HudEditorElement {
        public static final int SNAP_GRID = 1;
        public static final int SNAP_ELEMENTS = 1 << 1;
        public static final int SNAP_SCREEN_CENTER = 1 << 2;

        public String moduleId;
        public String elementId;
        public String displayName;
        public String positionKeyX;
        public String positionKeyY;
        public String snapGroup;
        public float x, y, width, height;
        public float gridSize;
        public float snapThreshold;
        public float gridGap;
        public int snapFlags;
    }

    public static HudEditorElement[] getHudEditorElements() {
        if (!ModManager.ensurePreloaderLoaded()) return new HudEditorElement[0];
        try {
            String json = nativeGetHudEditorElementsInfo();
            if (json == null || json.isEmpty()) return new HudEditorElement[0];
            org.json.JSONArray array = new org.json.JSONArray(json);
            HudEditorElement[] elements = new HudEditorElement[array.length()];
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                HudEditorElement element = new HudEditorElement();
                element.moduleId = obj.optString("module_id", "");
                element.elementId = obj.optString("element_id", "");
                element.displayName = obj.optString("display_name", element.elementId);
                element.positionKeyX = obj.optString("position_key_x", "hudPosX");
                element.positionKeyY = obj.optString("position_key_y", "hudPosY");
                element.snapGroup = obj.optString("snap_group", "");
                element.x = (float) obj.optDouble("x", 0.0);
                element.y = (float) obj.optDouble("y", 0.0);
                element.width = (float) obj.optDouble("width", 0.0);
                element.height = (float) obj.optDouble("height", 0.0);
                element.gridSize = (float) obj.optDouble("grid_size", 0.0);
                element.snapThreshold = (float) obj.optDouble("snap_threshold", 12.0);
                element.gridGap = (float) obj.optDouble("grid_gap", 4.0);
                element.snapFlags = obj.optInt("snap_flags", 0);
                elements[i] = element;
            }
            return elements;
        } catch (Throwable e) {
            return new HudEditorElement[0];
        }
    }

    public static void setHudSurfaceSize(float width, float height) {
        if (!ModManager.ensurePreloaderLoaded()) return;
        try {
            nativeSetHudSurfaceSize(width, height);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    public static long getDrawCommandsRevision() {
        if (!drawRevisionAvailable || !ModManager.ensurePreloaderLoaded()) return -1L;
        try {
            return nativeGetDrawCommandsRevision();
        } catch (UnsatisfiedLinkError e) {
            drawRevisionAvailable = false;
            return -1L;
        }
    }

    public static DrawCommand[] getDrawCommands() {
        return getHudDrawCommands(null);
    }

    public static DrawCommand[] getHudDrawCommands(DrawCommand[] previous) {
        if (!drawCommandsAvailable || !ModManager.ensurePreloaderLoaded()) return EMPTY_DRAW_COMMANDS;
        try {
            Object[] arrays = nativeGetDrawCommands();
            return decodeDrawCommands(arrays, previous);
        } catch (UnsatisfiedLinkError e) {
            drawCommandsAvailable = false;
            Log.e(TAG, "nativeGetDrawCommands not available", e);
            return EMPTY_DRAW_COMMANDS;
        }
    }
    private static DrawCommand[] decodeDrawCommands(Object[] arrays, DrawCommand[] previous) {
        if (arrays == null || arrays.length < 8) return EMPTY_DRAW_COMMANDS;

        int[] types = (int[]) arrays[0];
        float[] rects = (float[]) arrays[1];
        int[] colors = (int[]) arrays[2];
        float[] sizes = (float[]) arrays[3];
        String[] texts = (String[]) arrays[4];
        String[] moduleIds = (String[]) arrays[5];
        String[] fontIds = (String[]) arrays[6];
        String[] imageIds = (String[]) arrays[7];

        if (types == null || types.length == 0) return EMPTY_DRAW_COMMANDS;

        int n = types.length;
        if (previous != null && previous.length == n) {
            boolean identical = true;
            for (int i = 0; i < n && identical; i++) {
                DrawCommand old = previous[i];
                identical = old != null && old.type == types[i] && old.color == colors[i]
                        && sameFloat(old.x, rects[i * 6]) && sameFloat(old.y, rects[i * 6 + 1])
                        && sameFloat(old.w, rects[i * 6 + 2]) && sameFloat(old.h, rects[i * 6 + 3])
                        && sameFloat(old.x3, rects[i * 6 + 4]) && sameFloat(old.y3, rects[i * 6 + 5])
                        && sameFloat(old.size, sizes[i])
                        && java.util.Objects.equals(old.text, texts == null ? null : texts[i])
                        && java.util.Objects.equals(old.moduleId, moduleIds == null ? null : moduleIds[i])
                        && java.util.Objects.equals(old.fontId, fontIds == null ? null : fontIds[i])
                        && java.util.Objects.equals(old.imageId, imageIds == null ? null : imageIds[i]);
            }
            if (identical) return previous;
        }
        DrawCommand[] cmds = new DrawCommand[n];
        for (int i = 0; i < n; i++) {
            DrawCommand cmd = new DrawCommand();
            cmd.type = types[i];
            cmd.x = rects[i * 6 + 0];
            cmd.y = rects[i * 6 + 1];
            cmd.w = rects[i * 6 + 2];
            cmd.h = rects[i * 6 + 3];
            cmd.x3 = rects[i * 6 + 4];
            cmd.y3 = rects[i * 6 + 5];
            cmd.color = colors[i];
            cmd.size = sizes[i];
            cmd.text = texts != null ? texts[i] : null;
            cmd.moduleId = moduleIds != null ? moduleIds[i] : null;
            cmd.fontId = fontIds != null ? fontIds[i] : null;
            cmd.imageId = imageIds != null ? imageIds[i] : null;
            cmds[i] = cmd;
        }
        return cmds;
    }

    private static boolean sameFloat(float first, float second) {
        return Float.floatToRawIntBits(first) == Float.floatToRawIntBits(second);
    }
}
