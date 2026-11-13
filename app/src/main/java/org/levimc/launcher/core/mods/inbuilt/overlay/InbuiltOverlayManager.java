package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.KeyEvent;

import org.levimc.launcher.core.mods.inbuilt.ExternalModBridge;
import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.mods.inbuilt.manager.MoreButtonsManager;
import org.levimc.launcher.core.mods.inbuilt.model.MoreButtonConfig;
import org.levimc.launcher.core.mods.inbuilt.model.ModIds;
import org.levimc.launcher.core.mods.inbuilt.nativemod.PojavControlsMod;
import org.levimc.launcher.core.mods.inbuilt.nativemod.HotbarSlotMod;
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager;
import org.levimc.pojavcontrols.PojavControls;
import org.levimc.pojavcontrols.PojavControlsHost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InbuiltOverlayManager {
    public interface HudEditorSelectionListener {
        void onHudEditorSelectionChanged(int currentSizeDp);
    }

    private static volatile InbuiltOverlayManager instance;
    private final Activity activity;
    private final boolean modMenuEnabled;
    private final List<BaseOverlayButton> overlays = new ArrayList<>();
    private static final String[] KEYBIND_MOD_IDS = {
            ModIds.QUICK_DROP, ModIds.CAMERA_PERSPECTIVE, ModIds.TOGGLE_HUD,
            ModIds.AUTO_SPRINT, ModIds.ZOOM, ModIds.FPS_DISPLAY, ModIds.CPS_DISPLAY,
            ModIds.SNAPLOOK, ModIds.VIRTUAL_CURSOR, ModIds.GYRO,
            ModIds.POJAV_CONTROLS, ModIds.HOTBAR_SLOT
    };
    private final Map<String, Boolean> modActiveStates = new HashMap<>();
    private final Map<String, BaseOverlayButton> keybindControllers = new HashMap<>();
    private final Map<Integer, List<String>> pressedKeybinds = new HashMap<>();
    private volatile boolean hasPressedKeybinds;
    private int modMenuShortcutHeld = KeyEvent.KEYCODE_UNKNOWN;
    private final Map<String, BaseOverlayButton> modOverlayMap = new HashMap<>();
    private final Map<String, ExternalButtonOverlay> externalButtonOverlayMap = new HashMap<>();
    private final Map<String, MoreButtonOverlay> moreButtonOverlayMap = new HashMap<>();
    private final Map<Integer, HotbarSlotOverlay> hotbarSlotOverlayMap = new HashMap<>();
    private final int[] hotbarItemCounts = new int[9];
    private volatile boolean hotbarTrackingEnabled;
    private boolean moreButtonsEditorOpen;
    private final Map<String, Integer> modPositionMap = new HashMap<>();
    private ChickPetOverlay chickPetOverlay;
    private ZoomOverlay zoomOverlay;
    private SnaplookOverlay snaplookOverlay;
    private GyroOverlay gyroOverlay;
    private FpsDisplayOverlay fpsDisplayOverlay;
    private CpsDisplayOverlay cpsDisplayOverlay;
    private ModMenuButton modMenuButton;
    private ModMenuOverlay modMenuOverlay;
    private boolean modMenuOpenedFromButton;
    private HudOverlay hudOverlay;
    private BaseOverlayButton selectedHudEditorOverlay;
    private String selectedDisplayModId;
    private HudEditorSelectionListener hudEditorSelectionListener;
    private boolean hudEditorMode = false;
    private int baseY = 150;
    private static final int SPACING = 70;
    private static final int START_X = 50;
    private long lastVisibilityStateHash = Long.MIN_VALUE;
    private volatile boolean launcherHudHidden;
    private final android.os.Handler runtimeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean runtimeUpdateQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean activityActive = true;
    private volatile boolean disposed;
    private volatile long lastRuntimeUpdate = -50L;
    private final Runnable runtimeUpdate = () -> {
        runtimeUpdateQueued.set(false);
        if (!isRuntimeEnabled()) return;
        lastRuntimeUpdate = android.os.SystemClock.uptimeMillis();
        updateRuntimeVisibility();
    };

    public InbuiltOverlayManager(Activity activity) {
        this.activity = activity;
        this.modMenuEnabled = activity.getIntent().getBooleanExtra(MinecraftLauncher.EXTRA_MOD_MENU_ENABLED, true);
        instance = this;
    }

    public static InbuiltOverlayManager getInstance() {
        return instance;
    }

    private ModMenuOverlay getOrCreateModMenuOverlay() {
        if (modMenuOverlay == null) {
            modMenuOverlay = new ModMenuOverlay(activity);
            modMenuOverlay.setCallback(new ModMenuOverlay.ModMenuCallback() {
                @Override
                public void onModToggled(String modId, boolean enabled) {
                }

                @Override
                public void onButtonOpacityChanged(int opacity) {
                    if (modMenuButton != null) {
                        modMenuButton.refreshOpacity();
                    }
                }
            });
        }
        return modMenuOverlay;
    }

    public void showModMenu() {
        if (!modMenuEnabled) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            releaseKeybinds();
            modMenuOpenedFromButton = false;
            getOrCreateModMenuOverlay().show();
        });
    }

    public void openPojavControlsEditor() {
        releaseKeybinds();
        hideModMenu();
        PojavControls.launchEditor(activity);
    }

    public void hideModMenu() {
        activity.runOnUiThread(() -> {
            modMenuOpenedFromButton = false;
            if (modMenuOverlay != null && modMenuOverlay.isShowing()) {
                modMenuOverlay.hide();
            }
        });
    }

    public void toggleModMenuFromButton() {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (!modMenuEnabled) return;
        if (manager.isPauseMenuOnly() &&
                (!org.levimc.launcher.preloader.PreloaderInput.isPauseMenuOpen() ||
                        !org.levimc.launcher.preloader.PreloaderInput.isShowingMenu())) {
            hideModMenu();
            return;
        }
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            ModMenuOverlay overlay = getOrCreateModMenuOverlay();
            if (overlay.isShowing()) {
                modMenuOpenedFromButton = false;
                overlay.hide();
            } else {
                releaseKeybinds();
                modMenuOpenedFromButton = true;
                overlay.show();
            }
        });
    }

    public boolean isModMenuShowing() {
        return modMenuOverlay != null && modMenuOverlay.isShowing();
    }

    public void showEnabledOverlays() {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (!modMenuEnabled) return;

        if (hudOverlay == null) {
            hudOverlay = new HudOverlay(activity);
        }
        hudOverlay.show();

        int nextY = baseY;

        modActiveStates.put(ModIds.QUICK_DROP, false);
        modActiveStates.put(ModIds.CAMERA_PERSPECTIVE, false);
        modActiveStates.put(ModIds.TOGGLE_HUD, false);
        modActiveStates.put(ModIds.AUTO_SPRINT, false);
        modActiveStates.put(ModIds.CHICK_PET, false);
        modActiveStates.put(ModIds.ZOOM, false);
        modActiveStates.put(ModIds.FPS_DISPLAY, false);
        modActiveStates.put(ModIds.CPS_DISPLAY, false);
        modActiveStates.put(ModIds.SNAPLOOK, false);
        modActiveStates.put(ModIds.VIRTUAL_CURSOR, false);
        modActiveStates.put(ModIds.GYRO, false);
        modActiveStates.put(ModIds.POJAV_CONTROLS, false);
        modActiveStates.put(ModIds.MORE_BUTTONS, false);
        modActiveStates.put(ModIds.HOTBAR_SLOT, false);

        modPositionMap.put(ModIds.QUICK_DROP, nextY + SPACING);
        modPositionMap.put(ModIds.CAMERA_PERSPECTIVE, nextY + SPACING * 2);
        modPositionMap.put(ModIds.TOGGLE_HUD, nextY + SPACING * 3);
        modPositionMap.put(ModIds.AUTO_SPRINT, nextY + SPACING * 4);
        modPositionMap.put(ModIds.ZOOM, nextY + SPACING * 5);
        modPositionMap.put(ModIds.FPS_DISPLAY, nextY + SPACING * 6);
        modPositionMap.put(ModIds.CPS_DISPLAY, nextY + SPACING * 7);
        modPositionMap.put(ModIds.SNAPLOOK, nextY + SPACING * 8);
        modPositionMap.put(ModIds.VIRTUAL_CURSOR, nextY + SPACING * 9);
        modPositionMap.put(ModIds.GYRO, nextY + SPACING * 10);

        if (zoomOverlay == null) {
            zoomOverlay = new ZoomOverlay(activity);
            zoomOverlay.initializeForKeyboard();
        }

        if (snaplookOverlay == null) {
            snaplookOverlay = new SnaplookOverlay(activity);
            snaplookOverlay.initializeForKeyboard();
        }

        restorePersistedInbuiltModState(manager, ModIds.QUICK_DROP);
        restorePersistedInbuiltModState(manager, ModIds.CAMERA_PERSPECTIVE);
        restorePersistedInbuiltModState(manager, ModIds.TOGGLE_HUD);
        restorePersistedInbuiltModState(manager, ModIds.AUTO_SPRINT);
        restorePersistedInbuiltModState(manager, ModIds.CHICK_PET);
        restorePersistedInbuiltModState(manager, ModIds.ZOOM);
        restorePersistedInbuiltModState(manager, ModIds.FPS_DISPLAY);
        restorePersistedInbuiltModState(manager, ModIds.CPS_DISPLAY);
        restorePersistedInbuiltModState(manager, ModIds.SNAPLOOK);
        restorePersistedInbuiltModState(manager, ModIds.VIRTUAL_CURSOR);
        restorePersistedInbuiltModState(manager, ModIds.GYRO);
        restorePersistedInbuiltModState(manager, ModIds.POJAV_CONTROLS);
        restorePersistedInbuiltModState(manager, ModIds.MORE_BUTTONS);
        restorePersistedInbuiltModState(manager, ModIds.HOTBAR_SLOT);

        modMenuButton = new ModMenuButton(activity);
        modMenuButton.show(START_X, nextY);
        refreshExternalButtons();
        refreshRuntimeVisibility();
    }

    private void restorePersistedInbuiltModState(InbuiltModManager manager, String modId) {
        if (manager.resolveInbuiltModEnabled(modId, false)) {
            handleModToggle(modId, true);
        }
    }

    public void setLauncherHudHidden(boolean hidden) {
        if (launcherHudHidden == hidden) return;
        launcherHudHidden = hidden;
        refreshRuntimeVisibility();
    }

    public void handleModToggle(String modId, boolean enabled) {
        boolean wasEnabled = modActiveStates.getOrDefault(modId, false);
        modActiveStates.put(modId, enabled);
        
        if (enabled && !wasEnabled) {
            showModOverlay(modId);
        } else if (!enabled) {
            releaseModKeybind(modId);
            if (wasEnabled) hideModOverlay(modId);
            else {
                BaseOverlayButton controller = keybindControllers.get(modId);
                if (controller != null) controller.hide();
            }
        }
    }

    private BaseOverlayButton getKeybindController(String modId) {
        BaseOverlayButton controller = modOverlayMap.get(modId);
        if (controller == null) controller = keybindControllers.get(modId);
        if (controller != null) {
            keybindControllers.put(modId, controller);
            return controller;
        }
        switch (modId) {
            case ModIds.QUICK_DROP: controller = new QuickDropOverlay(activity); break;
            case ModIds.CAMERA_PERSPECTIVE: controller = new CameraPerspectiveOverlay(activity); break;
            case ModIds.TOGGLE_HUD: controller = new ToggleHudOverlay(activity); break;
            case ModIds.AUTO_SPRINT: controller = new AutoSprintOverlay(activity); break;
            case ModIds.VIRTUAL_CURSOR: controller = new VirtualCursorOverlay(activity); break;
            case ModIds.ZOOM:
                if (zoomOverlay == null) zoomOverlay = new ZoomOverlay(activity);
                controller = zoomOverlay;
                break;
            case ModIds.SNAPLOOK:
                if (snaplookOverlay == null) snaplookOverlay = new SnaplookOverlay(activity);
                controller = snaplookOverlay;
                break;
            case ModIds.GYRO:
                if (gyroOverlay == null) gyroOverlay = new GyroOverlay(activity);
                controller = gyroOverlay;
                break;
            default: return null;
        }
        keybindControllers.put(modId, controller);
        return controller;
    }

    private void showModOverlay(String modId) {
        if (modOverlayMap.containsKey(modId)) {
            return;
        }

        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int centerX = metrics.widthPixels / 2 - (int)(26 * metrics.density);
        int centerY = metrics.heightPixels / 2 - (int)(26 * metrics.density);

        int savedX = manager.getOverlayPositionX(modId, centerX);
        int savedY = manager.getOverlayPositionY(modId, centerY);

        switch (modId) {
            case ModIds.QUICK_DROP:
                QuickDropOverlay quickDrop = (QuickDropOverlay) getKeybindController(ModIds.QUICK_DROP);
                quickDrop.show(savedX, savedY);
                overlays.add(quickDrop);
                modOverlayMap.put(modId, quickDrop);
                break;
            case ModIds.CAMERA_PERSPECTIVE:
                CameraPerspectiveOverlay camera = (CameraPerspectiveOverlay) getKeybindController(ModIds.CAMERA_PERSPECTIVE);
                camera.show(savedX, savedY);
                overlays.add(camera);
                modOverlayMap.put(modId, camera);
                break;
            case ModIds.TOGGLE_HUD:
                ToggleHudOverlay hud = (ToggleHudOverlay) getKeybindController(ModIds.TOGGLE_HUD);
                hud.show(savedX, savedY);
                overlays.add(hud);
                modOverlayMap.put(modId, hud);
                break;
            case ModIds.AUTO_SPRINT:
                AutoSprintOverlay sprint = (AutoSprintOverlay) getKeybindController(ModIds.AUTO_SPRINT);
                sprint.show(savedX, savedY);
                overlays.add(sprint);
                modOverlayMap.put(modId, sprint);
                break;
            case ModIds.CHICK_PET:
                if (chickPetOverlay == null) {
                    chickPetOverlay = new ChickPetOverlay(activity);
                    chickPetOverlay.show();
                }
                break;
            case ModIds.ZOOM:
                zoomOverlay = (ZoomOverlay) getKeybindController(ModIds.ZOOM);
                zoomOverlay.show(savedX, savedY);
                overlays.add(zoomOverlay);
                modOverlayMap.put(modId, zoomOverlay);
                break;
            case ModIds.FPS_DISPLAY:
                if (fpsDisplayOverlay == null) {
                    fpsDisplayOverlay = new FpsDisplayOverlay(activity);
                    fpsDisplayOverlay.show(savedX, savedY);
                }
                break;
            case ModIds.CPS_DISPLAY:
                if (cpsDisplayOverlay == null) {
                    cpsDisplayOverlay = new CpsDisplayOverlay(activity);
                    cpsDisplayOverlay.show(savedX, savedY);
                }
                break;
            case ModIds.SNAPLOOK:
                snaplookOverlay = (SnaplookOverlay) getKeybindController(ModIds.SNAPLOOK);
                snaplookOverlay.show(savedX, savedY);
                overlays.add(snaplookOverlay);
                modOverlayMap.put(modId, snaplookOverlay);
                break;
            case ModIds.VIRTUAL_CURSOR:
                VirtualCursorOverlay cursorOverlay = (VirtualCursorOverlay) getKeybindController(ModIds.VIRTUAL_CURSOR);
                cursorOverlay.show(savedX, savedY);
                overlays.add(cursorOverlay);
                modOverlayMap.put(modId, cursorOverlay);
                break;
            case ModIds.GYRO:
                gyroOverlay = (GyroOverlay) getKeybindController(ModIds.GYRO);
                gyroOverlay.show(savedX, savedY);
                overlays.add(gyroOverlay);
                modOverlayMap.put(modId, gyroOverlay);
                break;
            case ModIds.MORE_BUTTONS:
                refreshMoreButtons();
                break;
            case ModIds.HOTBAR_SLOT:
                refreshHotbarSlots();
                break;
            case ModIds.POJAV_CONTROLS:
                if (activity instanceof PojavControlsHost && PojavControlsMod.setEnabled(true)) {
                    PojavControls.setEnabled(activity, (PojavControlsHost) activity, true);
                }
                break;
        }
    }

    private void hideModOverlay(String modId) {
        if (modId.equals(ModIds.HOTBAR_SLOT)) {
            hideHotbarSlots();
            return;
        }
        if (modId.equals(ModIds.MORE_BUTTONS)) {
            hideMoreButtons();
            return;
        }
        if (modId.equals(ModIds.POJAV_CONTROLS)) {
            PojavControls.setEnabled(activity,
                    activity instanceof PojavControlsHost ? (PojavControlsHost) activity : null,
                    false);
            PojavControlsMod.setEnabled(false);
            return;
        }
        if (modId.equals(ModIds.CHICK_PET)) {
            if (chickPetOverlay != null) {
                chickPetOverlay.hide();
                chickPetOverlay = null;
            }
            return;
        }
        
        if (modId.equals(ModIds.ZOOM)) {
            if (zoomOverlay != null) {
                if (zoomOverlay == selectedHudEditorOverlay) {
                    selectHudEditorOverlay(null);
                }
                zoomOverlay.hide();
                overlays.remove(zoomOverlay);
                modOverlayMap.remove(modId);
            }
            return;
        }

        if (modId.equals(ModIds.FPS_DISPLAY)) {
            if (fpsDisplayOverlay != null) {
                fpsDisplayOverlay.hide();
                fpsDisplayOverlay = null;
            }
            return;
        }

        if (modId.equals(ModIds.CPS_DISPLAY)) {
            if (cpsDisplayOverlay != null) {
                cpsDisplayOverlay.hide();
                cpsDisplayOverlay = null;
            }
            return;
        }

        if (modId.equals(ModIds.SNAPLOOK)) {
            if (snaplookOverlay != null) {
                if (snaplookOverlay == selectedHudEditorOverlay) {
                    selectHudEditorOverlay(null);
                }
                snaplookOverlay.hide();
                overlays.remove(snaplookOverlay);
                modOverlayMap.remove(modId);
            }
            return;
        }

        if (modId.equals(ModIds.GYRO)) {
            if (gyroOverlay != null) {
                if (gyroOverlay == selectedHudEditorOverlay) {
                    selectHudEditorOverlay(null);
                }
                gyroOverlay.hide();
                overlays.remove(gyroOverlay);
                modOverlayMap.remove(modId);
            }
            return;
        }
        
        BaseOverlayButton overlay = modOverlayMap.get(modId);
        if (overlay != null) {
            if (overlay == selectedHudEditorOverlay) {
                selectHudEditorOverlay(null);
            }
            overlay.hide();
            overlays.remove(overlay);
            modOverlayMap.remove(modId);
        }
    }

    public void refreshMoreButtons() {
        if (moreButtonsEditorOpen || !modActiveStates.getOrDefault(ModIds.MORE_BUTTONS, false)) return;

        MoreButtonsManager buttonsManager = MoreButtonsManager.getInstance(activity);
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        java.util.List<MoreButtonConfig> configs = buttonsManager.getButtons();
        java.util.Set<String> validIds = new java.util.HashSet<>();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int centerX = metrics.widthPixels / 2 - (int)(28 * metrics.density);
        int centerY = metrics.heightPixels / 2 - (int)(28 * metrics.density);
        int visibleIndex = 0;

        for (MoreButtonConfig config : configs) {
            validIds.add(config.id);
            MoreButtonOverlay existing = moreButtonOverlayMap.get(config.id);
            if (!config.visible) {
                if (existing != null) removeMoreButtonOverlay(existing);
                continue;
            }

            if (existing != null) {
                existing.applyConfigurationChanges();
                continue;
            }

            int defaultX = centerX + (int)((visibleIndex % 4) * 64 * metrics.density);
            int defaultY = centerY + (int)((visibleIndex / 4) * 64 * metrics.density);
            int savedX = manager.getOverlayPositionX(config.overlayKey(), defaultX);
            int savedY = manager.getOverlayPositionY(config.overlayKey(), defaultY);
            MoreButtonOverlay overlay = new MoreButtonOverlay(activity, config);
            overlay.show(savedX, savedY);
            overlays.add(overlay);
            moreButtonOverlayMap.put(config.id, overlay);
            modOverlayMap.put(config.overlayKey(), overlay);
            visibleIndex++;
        }

        java.util.List<MoreButtonOverlay> stale = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, MoreButtonOverlay> entry : moreButtonOverlayMap.entrySet()) {
            if (!validIds.contains(entry.getKey())) stale.add(entry.getValue());
        }
        for (MoreButtonOverlay overlay : stale) removeMoreButtonOverlay(overlay);
    }

    public void setMoreButtonsEditorOpen(boolean open) {
        if (moreButtonsEditorOpen == open) return;
        moreButtonsEditorOpen = open;
        if (open) {
            releaseKeybinds();
            hideMoreButtons();
        }
        else refreshMoreButtons();
    }

    private void hideMoreButtons() {
        java.util.List<MoreButtonOverlay> copy = new java.util.ArrayList<>(moreButtonOverlayMap.values());
        for (MoreButtonOverlay overlay : copy) removeMoreButtonOverlay(overlay);
    }

    private void removeMoreButtonOverlay(MoreButtonOverlay overlay) {
        if (overlay == null) return;
        if (overlay == selectedHudEditorOverlay) selectHudEditorOverlay(null);
        overlay.hide();
        overlays.remove(overlay);
        moreButtonOverlayMap.remove(overlay.getButtonId());
        modOverlayMap.remove(overlay.getOverlayConfigKey());
    }

    public void refreshHotbarSlots() {
        if (!modActiveStates.getOrDefault(ModIds.HOTBAR_SLOT, false)) return;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int gap = (int)(4 * metrics.density);
        int enabledCount = 0;
        int total = 0;
        for (int slot = 1; slot <= 9; slot++) {
            if (!manager.isHotbarSlotEnabled(slot)) continue;
            total += getHotbarSlotButtonSizePx(manager, metrics, slot);
            enabledCount++;
        }
        if (enabledCount > 1) total += gap * (enabledCount - 1);
        int startX = Math.max(0, (metrics.widthPixels - total) / 2);
        int defaultY = Math.max(0, metrics.heightPixels - (int)(120 * metrics.density));
        int nextX = startX;
        for (int slot = 1; slot <= 9; slot++) {
            HotbarSlotOverlay existing = hotbarSlotOverlayMap.get(slot);
            if (!manager.isHotbarSlotEnabled(slot)) {
                removeHotbarSlotOverlay(existing);
                continue;
            }
            int button = getHotbarSlotButtonSizePx(manager, metrics, slot);
            if (existing != null) {
                existing.applyConfigurationChanges();
            } else {
                String key = ModIds.HOTBAR_SLOT + ":" + slot;
                int savedX = manager.getOverlayPositionX(key, nextX);
                int savedY = manager.getOverlayPositionY(key, defaultY);
                HotbarSlotOverlay overlay = new HotbarSlotOverlay(activity, slot);
                overlay.show(savedX, savedY);
                overlays.add(overlay);
                hotbarSlotOverlayMap.put(slot, overlay);
                modOverlayMap.put(key, overlay);
            }
            nextX += button + gap;
        }
        hotbarTrackingEnabled = !hotbarSlotOverlayMap.isEmpty() &&
                (manager.isHotbarItemIconsEnabled() || manager.isHotbarItemCountsEnabled());
        HotbarSlotMod.setOverlayEnabled(!hotbarSlotOverlayMap.isEmpty());
        HotbarSlotMod.setItemIconsEnabled(hotbarTrackingEnabled && manager.isHotbarItemIconsEnabled());
        HotbarSlotMod.setEnabled(hotbarTrackingEnabled);
    }

    private int getHotbarSlotButtonSizePx(InbuiltModManager manager,
                                           android.util.DisplayMetrics metrics, int slot) {
        String key = ModIds.HOTBAR_SLOT + ":" + slot;
        return (int)(manager.getOverlayButtonSize(key) * metrics.density);
    }

    private void removeHotbarSlotOverlay(HotbarSlotOverlay overlay) {
        if (overlay == null) return;
        if (overlay == selectedHudEditorOverlay) selectHudEditorOverlay(null);
        overlay.hide();
        overlays.remove(overlay);
        hotbarSlotOverlayMap.remove(overlay.getSlot());
        modOverlayMap.remove(ModIds.HOTBAR_SLOT + ":" + overlay.getSlot());
    }

    private void hideHotbarSlots() {
        HotbarSlotMod.setOverlayEnabled(false);
        hotbarTrackingEnabled = false;
        HotbarSlotMod.setEnabled(false);
        HotbarSlotMod.setItemIconsEnabled(false);
        java.util.List<HotbarSlotOverlay> copy = new java.util.ArrayList<>(hotbarSlotOverlayMap.values());
        for (HotbarSlotOverlay overlay : copy) removeHotbarSlotOverlay(overlay);
        hotbarSlotOverlayMap.clear();
    }

    public void handleExternalModuleToggle(String moduleId, boolean enabled) {
        if (enabled) {
            showExternalButtonsForModule(moduleId);
        } else {
            hideExternalButtonsForModule(moduleId);
        }
    }

    private void refreshExternalButtons() {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        java.util.Set<String> enabledModules = new java.util.HashSet<>();
        int extCount = ExternalModBridge.getExternalModCount();
        for (int i = 0; i < extCount; i++) {
            try {
                org.json.JSONObject obj = new org.json.JSONObject(ExternalModBridge.getExternalModInfo(i));
                String moduleId = obj.optString("module_id", "");
                if (moduleId.isEmpty()) continue;

                boolean nativeEnabled = obj.optBoolean("enabled", false);
                boolean enabled = manager.resolveExternalModuleEnabled(moduleId, nativeEnabled);
                if (enabled != nativeEnabled) {
                    ExternalModBridge.toggleExternalMod(moduleId, enabled);
                }
                if (enabled) {
                    enabledModules.add(moduleId);
                }
            } catch (Exception ignored) {}
        }

        for (String moduleId : enabledModules) {
            showExternalButtonsForModule(moduleId);
        }
        java.util.List<ExternalButtonOverlay> stale = new java.util.ArrayList<>();
        for (ExternalButtonOverlay overlay : externalButtonOverlayMap.values()) {
            if (!enabledModules.contains(overlay.getModuleId())) {
                stale.add(overlay);
            }
        }
        for (ExternalButtonOverlay overlay : stale) {
            if (overlay == selectedHudEditorOverlay) {
                selectHudEditorOverlay(null);
            }
            overlay.hide();
            overlays.remove(overlay);
            externalButtonOverlayMap.remove(overlay.getButtonId());
            modOverlayMap.remove(overlay.getModId());
        }
    }

    private void showExternalButtonsForModule(String moduleId) {
        if (moduleId == null || moduleId.isEmpty()) return;

        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int centerX = metrics.widthPixels / 2 - (int)(26 * metrics.density);
        int centerY = metrics.heightPixels / 2 - (int)(26 * metrics.density);

        int buttonCount = ExternalModBridge.getExternalButtonCount();
        for (int i = 0; i < buttonCount; i++) {
            ExternalModBridge.ExternalButton button = ExternalModBridge.getExternalButton(i);
            if (button == null || !moduleId.equals(button.moduleId)) continue;
            if (!button.defaultVisible || !button.moduleEnabled) continue;
            if (externalButtonOverlayMap.containsKey(button.buttonId)) continue;

            int savedX = manager.getOverlayPositionX(button.positionKey(), centerX);
            int savedY = manager.getOverlayPositionY(button.positionKey(), centerY);
            ExternalButtonOverlay overlay = new ExternalButtonOverlay(activity, button);
            overlay.show(savedX, savedY);
            overlays.add(overlay);
            externalButtonOverlayMap.put(button.buttonId, overlay);
            modOverlayMap.put(button.positionKey(), overlay);
        }
    }

    private void hideExternalButtonsForModule(String moduleId) {
        java.util.List<ExternalButtonOverlay> toHide = new java.util.ArrayList<>();
        for (ExternalButtonOverlay overlay : externalButtonOverlayMap.values()) {
            if (moduleId.equals(overlay.getModuleId())) {
                toHide.add(overlay);
            }
        }
        for (ExternalButtonOverlay overlay : toHide) {
            if (overlay == selectedHudEditorOverlay) {
                selectHudEditorOverlay(null);
            }
            overlay.hide();
            overlays.remove(overlay);
            externalButtonOverlayMap.remove(overlay.getButtonId());
            modOverlayMap.remove(overlay.getModId());
        }
    }

    public boolean isModActive(String modId) {
        return modActiveStates.getOrDefault(modId, false);
    }


    public void hideAllOverlays() {
        disposed = true;
        activityActive = false;
        runtimeHandler.removeCallbacks(runtimeUpdate);
        runtimeUpdateQueued.set(false);
        releaseModMenuShortcut();
        releaseKeybinds();
        for (BaseOverlayButton controller : keybindControllers.values()) controller.hide();
        keybindControllers.clear();
        HotbarSlotMod.setOverlayEnabled(false);
        hotbarTrackingEnabled = false;
        HotbarSlotMod.setEnabled(false);
        HotbarSlotMod.setItemIconsEnabled(false);
        PojavControls.setEnabled(activity,
                activity instanceof PojavControlsHost ? (PojavControlsHost) activity : null,
                false);
        PojavControlsMod.setEnabled(false);
        selectHudEditorOverlay(null);
        for (BaseOverlayButton overlay : overlays) {
            overlay.hide();
        }
        overlays.clear();
        modOverlayMap.clear();
        externalButtonOverlayMap.clear();
        moreButtonOverlayMap.clear();
        hotbarSlotOverlayMap.clear();
        modActiveStates.clear();
        modPositionMap.clear();
        launcherHudHidden = false;
        PojavControls.setHudVisible(true);
        LogcatOverlayManager logcatManager = LogcatOverlayManager.getInstance();
        if (logcatManager != null) logcatManager.setGameHudHidden(false);
        if (chickPetOverlay != null) {
            chickPetOverlay.hide();
            chickPetOverlay = null;
        }
        if (zoomOverlay != null) {
            zoomOverlay.hide();
            zoomOverlay = null;
        }
        if (fpsDisplayOverlay != null) {
            fpsDisplayOverlay.hide();
            fpsDisplayOverlay = null;
        }
        if (cpsDisplayOverlay != null) {
            cpsDisplayOverlay.hide();
            cpsDisplayOverlay = null;
        }
        if (snaplookOverlay != null) {
            snaplookOverlay.hide();
            snaplookOverlay = null;
        }
        if (gyroOverlay != null) {
            gyroOverlay.hide();
            gyroOverlay = null;
        }
        if (modMenuOverlay != null) {
            modMenuOverlay.hide();
            modMenuOverlay = null;
            modMenuOpenedFromButton = false;
        }
        if (modMenuButton != null) {
            modMenuButton.hide();
            modMenuButton = null;
        }
        if (hudOverlay != null) {
            hudOverlay.hide();
            hudOverlay = null;
        }
        instance = null;
    }

    public boolean handleModMenuShortcut(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (keyCode == modMenuShortcutHeld) {
            if (action == KeyEvent.ACTION_UP) {
                modMenuShortcutHeld = KeyEvent.KEYCODE_UNKNOWN;
            }
            return action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP;
        }
        if (action != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                || (!event.hasNoModifiers() && !KeyEvent.isModifierKey(keyCode))) return false;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (!modMenuEnabled || keyCode == KeyEvent.KEYCODE_UNKNOWN
                || keyCode != manager.getModMenuKeybind() || moreButtonsEditorOpen
                || PojavControls.isEditorOpen()) return false;
        if (manager.isPauseMenuOnly() && !isModMenuShowing()
                && !org.levimc.launcher.preloader.PreloaderInput.isPauseMenuOpen()) return false;
        modMenuShortcutHeld = keyCode;
        toggleModMenuFromButton();
        return true;
    }

    public void releaseModMenuShortcut() {
        modMenuShortcutHeld = KeyEvent.KEYCODE_UNKNOWN;
    }

    public boolean handleKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            List<String> mods = pressedKeybinds.remove(keyCode);
            hasPressedKeybinds = !pressedKeybinds.isEmpty();
            if (mods == null) return false;
            for (String modId : mods) releaseKeybindAction(modId);
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
        if (pressedKeybinds.containsKey(keyCode)) return true;
        if (event.getRepeatCount() != 0 || hudEditorMode || isModMenuShowing()
                || moreButtonsEditorOpen || PojavControls.isEditorOpen()
                || org.levimc.launcher.preloader.PreloaderInput.isShowingMenu()
                || org.levimc.launcher.preloader.PreloaderInput.isPauseMenuOpen()
                || !org.levimc.launcher.preloader.PreloaderInput.isHudScreenOpen()) return false;
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        List<String> matches = new ArrayList<>();
        for (String modId : KEYBIND_MOD_IDS) {
            if (manager.getInbuiltModKeybind(modId) == keyCode) matches.add(modId);
        }
        if (matches.isEmpty()) return false;
        pressedKeybinds.put(keyCode, new ArrayList<>(matches));
        hasPressedKeybinds = true;
        for (String modId : matches) activateKeybindAction(modId);
        return true;
    }

    private void activateKeybindAction(String modId) {
        if (ModIds.FPS_DISPLAY.equals(modId) || ModIds.CPS_DISPLAY.equals(modId)
                || ModIds.HOTBAR_SLOT.equals(modId) || ModIds.POJAV_CONTROLS.equals(modId)) {
            boolean enabled = !isModActive(modId);
            InbuiltModManager.getInstance(activity).setInbuiltModEnabled(modId, enabled);
            handleModToggle(modId, enabled);
            refreshRuntimeVisibility();
            return;
        }
        BaseOverlayButton controller = getKeybindController(modId);
        if (controller == null) return;
        switch (modId) {
            case ModIds.ZOOM: ((ZoomOverlay) controller).onKeyDown(); break;
            case ModIds.SNAPLOOK: ((SnaplookOverlay) controller).onKeyDown(); break;
            case ModIds.QUICK_DROP: controller.onButtonPressStart(); break;
            case ModIds.GYRO: ((GyroOverlay) controller).toggleGyro(); break;
            default: controller.onButtonClick(); break;
        }
    }

    private void releaseKeybindAction(String modId) {
        BaseOverlayButton controller = keybindControllers.get(modId);
        if (controller == null) return;
        switch (modId) {
            case ModIds.ZOOM: ((ZoomOverlay) controller).onKeyUp(); break;
            case ModIds.SNAPLOOK: ((SnaplookOverlay) controller).onKeyUp(); break;
            case ModIds.QUICK_DROP: controller.onButtonPressEnd(); break;
            default: break;
        }
    }

    private void releaseModKeybind(String modId) {
        for (List<String> mods : pressedKeybinds.values()) {
            if (mods.remove(modId)) releaseKeybindAction(modId);
        }
    }

    public void releaseKeybinds() {
        List<List<String>> pressed = new ArrayList<>(pressedKeybinds.values());
        pressedKeybinds.clear();
        hasPressedKeybinds = false;
        for (List<String> mods : pressed) {
            for (String modId : mods) releaseKeybindAction(modId);
        }
    }

    public boolean handleScrollEvent(float scrollDelta) {
        if (launcherHudHidden && InbuiltModManager.getInstance(activity).isLauncherHudHideEnabled()) return false;
        for (ExternalButtonOverlay overlay : externalButtonOverlayMap.values()) {
            if (overlay.onScroll(scrollDelta)) {
                return true;
            }
        }
        if (zoomOverlay != null && zoomOverlay.isZooming()) {
            zoomOverlay.onScroll(scrollDelta);
            return true;
        }
        return false;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (launcherHudHidden && InbuiltModManager.getInstance(activity).isLauncherHudHideEnabled()) return false;
        if (cpsDisplayOverlay != null) {
            return cpsDisplayOverlay.handleTouchEvent(event);
        }
        return false;
    }

    public boolean handleMouseEvent(MotionEvent event) {
        if (launcherHudHidden && InbuiltModManager.getInstance(activity).isLauncherHudHideEnabled()) return false;
        if (cpsDisplayOverlay != null) {
            return cpsDisplayOverlay.handleMouseEvent(event);
        }
        return false;
    }

    public void applyConfigurationChanges(String modId) {
        BaseOverlayButton overlay = modOverlayMap.get(modId);
        if (overlay != null) {
            overlay.applyConfigurationChanges();
        }

        BaseOverlayButton controller = keybindControllers.get(modId);
        if (controller != null && controller != overlay) controller.applyConfigurationChanges();

        if (modId.equals(ModIds.ZOOM) && zoomOverlay != null) {
            zoomOverlay.applyConfigurationChanges();
        }
        if (modId.equals(ModIds.SNAPLOOK) && snaplookOverlay != null) {
            snaplookOverlay.applyConfigurationChanges();
        }
        if (modId.equals(ModIds.GYRO) && gyroOverlay != null) {
            gyroOverlay.applyConfigurationChanges();
        }
        if (modId.equals(ModIds.FPS_DISPLAY) && fpsDisplayOverlay != null) {
            fpsDisplayOverlay.applyConfigurationChanges();
        }
        if (modId.equals(ModIds.CPS_DISPLAY) && cpsDisplayOverlay != null) {
            cpsDisplayOverlay.applyConfigurationChanges();
        }
        if (modId.equals(ModIds.MORE_BUTTONS)) {
            refreshMoreButtons();
        }
        if (modId.equals(ModIds.HOTBAR_SLOT)) {
            refreshHotbarSlots();
        }
    }

    public void setHudEditorMode(boolean active) {
        if (active) releaseKeybinds();
        hudEditorMode = active;
        for (BaseOverlayButton overlay : overlays) {
            overlay.setHudEditorMode(active);
        }
        if (fpsDisplayOverlay != null) {
            fpsDisplayOverlay.setHudEditorMode(active);
        }
        if (cpsDisplayOverlay != null) {
            cpsDisplayOverlay.setHudEditorMode(active);
        }
        if (hudOverlay != null) {
            hudOverlay.setHudEditorMode(active);
        }

        if (modMenuButton != null) {
            if (active) {
                modMenuButton.setVisibility(android.view.View.GONE);
            } else {
                int savedX = InbuiltModManager.getInstance(activity).getOverlayPositionX(ModIds.MOD_MENU, START_X);
                int savedY = InbuiltModManager.getInstance(activity).getOverlayPositionY(ModIds.MOD_MENU, baseY);
                modMenuButton.show(savedX, savedY);
            }
        }
        refreshRuntimeVisibility();

        if (active) {
            selectFirstHudEditorOverlay();
        } else {
            selectHudEditorOverlay(null);
        }
    }

    public void setHudEditorSelectionListener(HudEditorSelectionListener listener) {
        hudEditorSelectionListener = listener;
        if (listener != null) {
            listener.onHudEditorSelectionChanged(getSelectedHudEditorButtonSize());
        }
    }

    public void selectHudEditorOverlay(BaseOverlayButton overlay) {
        if (!hudEditorMode && overlay != null) {
            return;
        }
        selectedDisplayModId = null;
        if (selectedHudEditorOverlay == overlay) {
            notifySelectionListener();
            return;
        }
        if (selectedHudEditorOverlay != null) {
            selectedHudEditorOverlay.setHudEditorSelected(false);
        }
        selectedHudEditorOverlay = overlay;
        if (selectedHudEditorOverlay != null) {
            selectedHudEditorOverlay.setHudEditorSelected(true);
        }
        notifySelectionListener();
    }

    public void selectHudEditorDisplay(String modId) {
        if (!hudEditorMode || modId == null) return;
        if (selectedHudEditorOverlay != null) {
            selectedHudEditorOverlay.setHudEditorSelected(false);
            selectedHudEditorOverlay = null;
        }
        selectedDisplayModId = modId;
        notifySelectionListener();
    }

    private void notifySelectionListener() {
        if (hudEditorSelectionListener != null) {
            hudEditorSelectionListener.onHudEditorSelectionChanged(getSelectedHudEditorButtonSize());
        }
    }

    public int getSelectedHudEditorButtonSize() {
        if (selectedHudEditorOverlay != null) {
            return selectedHudEditorOverlay.getCurrentButtonSizeDp();
        }
        if (selectedDisplayModId != null) {
            return InbuiltModManager.getInstance(activity).getOverlayButtonSize(selectedDisplayModId);
        }
        return 0;
    }

    public void setSelectedHudEditorButtonSize(int sizeDp) {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        if (selectedHudEditorOverlay != null) {
            String configKey = selectedHudEditorOverlay.getOverlayConfigKey();
            manager.setOverlayButtonSize(configKey, sizeDp);
            selectedHudEditorOverlay.applyConfigurationChanges();
        } else if (selectedDisplayModId != null) {
            manager.setOverlayButtonSize(selectedDisplayModId, sizeDp);
            if (selectedDisplayModId.equals(ModIds.FPS_DISPLAY) && fpsDisplayOverlay != null) {
                fpsDisplayOverlay.applyConfigurationChanges();
            } else if (selectedDisplayModId.equals(ModIds.CPS_DISPLAY) && cpsDisplayOverlay != null) {
                cpsDisplayOverlay.applyConfigurationChanges();
            }
        }
    }

    private void selectFirstHudEditorOverlay() {
        if (selectedHudEditorOverlay != null) {
            selectedHudEditorOverlay.setHudEditorSelected(true);
            notifySelectionListener();
            return;
        }
        if (selectedDisplayModId != null) {
            notifySelectionListener();
            return;
        }
        if (!overlays.isEmpty()) {
            selectHudEditorOverlay(overlays.get(0));
        } else {
            selectHudEditorOverlay(null);
        }
    }

    public void resetAllPositionsToCenter() {
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int centerX = metrics.widthPixels / 2 - (int)(26 * metrics.density);
        int centerY = metrics.heightPixels / 2 - (int)(26 * metrics.density);

        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        
        for (java.util.Map.Entry<String, BaseOverlayButton> entry : modOverlayMap.entrySet()) {
            if (entry.getKey().startsWith(ModIds.HOTBAR_SLOT + ":")) continue;
            manager.setOverlayPosition(entry.getKey(), centerX, centerY);
            entry.getValue().updatePosition(centerX, centerY);
        }

        if (!hotbarSlotOverlayMap.isEmpty()) {
            int gap = (int)(4 * metrics.density);
            int total = 0;
            int enabledCount = 0;
            for (int slot = 1; slot <= 9; slot++) {
                if (!hotbarSlotOverlayMap.containsKey(slot)) continue;
                total += getHotbarSlotButtonSizePx(manager, metrics, slot);
                enabledCount++;
            }
            if (enabledCount > 1) total += gap * (enabledCount - 1);
            int startX = Math.max(0, (metrics.widthPixels - total) / 2);
            int defaultY = Math.max(0, metrics.heightPixels - (int)(120 * metrics.density));
            int nextX = startX;
            for (int slot = 1; slot <= 9; slot++) {
                HotbarSlotOverlay overlay = hotbarSlotOverlayMap.get(slot);
                if (overlay == null) continue;
                String key = ModIds.HOTBAR_SLOT + ":" + slot;
                manager.setOverlayPosition(key, nextX, defaultY);
                overlay.updatePosition(nextX, defaultY);
                nextX += getHotbarSlotButtonSizePx(manager, metrics, slot) + gap;
            }
        }
        
        if (fpsDisplayOverlay != null) {
            manager.setOverlayPosition(ModIds.FPS_DISPLAY, centerX, centerY);
            fpsDisplayOverlay.updatePosition(centerX, centerY);
        }
        if (cpsDisplayOverlay != null) {
            manager.setOverlayPosition(ModIds.CPS_DISPLAY, centerX, centerY);
            cpsDisplayOverlay.updatePosition(centerX, centerY);
        }
        
        java.util.Set<String> explicitHudModules = new java.util.HashSet<>();
        ExternalModBridge.HudEditorElement[] hudElements = ExternalModBridge.getHudEditorElements();
        if (hudElements != null) {
            for (ExternalModBridge.HudEditorElement element : hudElements) {
                if (element == null || element.moduleId == null || element.positionKeyX == null || element.positionKeyY == null) continue;
                explicitHudModules.add(element.moduleId);
                float elementX = metrics.widthPixels * 0.5f - Math.max(1f, element.width) * 0.5f;
                float elementY = metrics.heightPixels * 0.5f - Math.max(1f, element.height) * 0.5f;
                ExternalModBridge.setExternalModConfig(element.moduleId, element.positionKeyX, String.valueOf(elementX));
                ExternalModBridge.setExternalModConfig(element.moduleId, element.positionKeyY, String.valueOf(elementY));
            }
        }

        ExternalModBridge.DrawCommand[] cmds = ExternalModBridge.getDrawCommands();
        if (cmds != null) {
            java.util.Set<String> processed = new java.util.HashSet<>();
            for (ExternalModBridge.DrawCommand cmd : cmds) {
                if (cmd.moduleId != null && !explicitHudModules.contains(cmd.moduleId) && processed.add(cmd.moduleId)) {
                    ExternalModBridge.setExternalModConfig(cmd.moduleId, "hudPosX", String.valueOf(centerX));
                    ExternalModBridge.setExternalModConfig(cmd.moduleId, "hudPosY", String.valueOf(centerY));
                }
            }
        }
    }

    public void refreshRuntimeVisibility() {
        if (!modMenuEnabled || !activityActive || disposed) return;
        lastVisibilityStateHash = Long.MIN_VALUE;
        runtimeHandler.removeCallbacks(runtimeUpdate);
        runtimeUpdateQueued.set(false);
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) runtimeUpdate.run();
        else if (runtimeUpdateQueued.compareAndSet(false, true)) runtimeHandler.post(runtimeUpdate);
    }

    private boolean isRuntimeEnabled() {
        return modMenuEnabled && activityActive && !disposed;
    }

    public void tick() {
        if (!modMenuEnabled || !activityActive || disposed) return;
        if (android.os.SystemClock.uptimeMillis() - lastRuntimeUpdate < 50L) return;
        if (runtimeUpdateQueued.compareAndSet(false, true)) runtimeHandler.post(runtimeUpdate);
    }

    public void setActivityActive(boolean active) {
        if (disposed || activityActive == active) return;
        activityActive = active;
        if (hudOverlay != null) hudOverlay.setRuntimeActive(active);
        if (fpsDisplayOverlay != null) fpsDisplayOverlay.setRuntimeActive(active);
        if (cpsDisplayOverlay != null) cpsDisplayOverlay.setRuntimeActive(active);
        if (chickPetOverlay != null) chickPetOverlay.setRuntimeActive(active);
        if (gyroOverlay != null) gyroOverlay.setRuntimeActive(active);
        if (!active) {
            runtimeHandler.removeCallbacks(runtimeUpdate);
            runtimeUpdateQueued.set(false);
            releaseKeybinds();
            for (BaseOverlayButton overlay : overlays) overlay.pauseInput();
            if (modMenuOverlay != null) modMenuOverlay.hide();
        } else {
            refreshRuntimeVisibility();
        }
    }

    private void updateRuntimeVisibility() {
        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        boolean isPauseOnly = manager.isPauseMenuOnly();
        boolean isPauseOpen = org.levimc.launcher.preloader.PreloaderInput.isPauseMenuOpen();
        boolean isHudScreenOpen = org.levimc.launcher.preloader.PreloaderInput.isHudScreenOpen();
        boolean isShowingMenu = org.levimc.launcher.preloader.PreloaderInput.isShowingMenu();
        boolean isFloatingModMenuButtonHidden = ExternalModBridge.isFloatingModMenuButtonHidden();
        boolean showGameOverlays = isHudScreenOpen && !isShowingMenu && !isPauseOpen;
        boolean hideLauncherOverlays = launcherHudHidden && manager.isLauncherHudHideEnabled();
        if (!showGameOverlays && hasPressedKeybinds) activity.runOnUiThread(this::releaseKeybinds);
        boolean inbuiltVisible = hudEditorMode || showGameOverlays;
        int hotbarGameplayState = hotbarSlotOverlayMap.isEmpty() ? -1 : HotbarSlotMod.getGameplayVisibilityState();
        boolean hotbarGameplayVisible = hotbarGameplayState >= 0
                ? hotbarGameplayState == 1 : showGameOverlays;
        boolean hotbarVisible = !hideLauncherOverlays && (hudEditorMode || hotbarGameplayVisible ||
                manager.isOverlayShowEverywhere(ModIds.HOTBAR_SLOT));

        if (hotbarTrackingEnabled && hotbarVisible && !hotbarSlotOverlayMap.isEmpty()) {
            HotbarSlotMod.copyItemCounts(hotbarItemCounts);
            for (HotbarSlotOverlay hotbar : hotbarSlotOverlayMap.values()) {
                hotbar.updateItemCount(hotbarItemCounts[hotbar.getSlot() - 1]);
            }
        }

        for (HotbarSlotOverlay hotbar : hotbarSlotOverlayMap.values()) {
            hotbar.setRenderVisible(hotbarVisible);
        }

        long stateHash = manager.getOverlayVisibilityRevision();
        stateHash = 31L * stateHash + (hideLauncherOverlays ? 1L : 0L);
        stateHash = 31L * stateHash + (isPauseOnly ? 1L : 0L);
        stateHash = 31L * stateHash + (isPauseOpen ? 1L : 0L);
        stateHash = 31L * stateHash + (isHudScreenOpen ? 1L : 0L);
        stateHash = 31L * stateHash + (isShowingMenu ? 1L : 0L);
        stateHash = 31L * stateHash + (isFloatingModMenuButtonHidden ? 1L : 0L);
        stateHash = 31L * stateHash + (hudEditorMode ? 1L : 0L);
        stateHash = 31L * stateHash + (hotbarVisible ? 1L : 0L);
        stateHash = 31L * stateHash + overlays.size();
        for (BaseOverlayButton overlay : overlays) {
            stateHash = 31L * stateHash + System.identityHashCode(overlay);
        }
        stateHash = 31L * stateHash + (fpsDisplayOverlay == null ? 0L : System.identityHashCode(fpsDisplayOverlay));
        stateHash = 31L * stateHash + (cpsDisplayOverlay == null ? 0L : System.identityHashCode(cpsDisplayOverlay));
        stateHash = 31L * stateHash + (chickPetOverlay == null ? 0L : System.identityHashCode(chickPetOverlay));
        stateHash = 31L * stateHash + (modMenuButton == null ? 0L : System.identityHashCode(modMenuButton));
        stateHash = 31L * stateHash + (hudOverlay == null ? 0L : System.identityHashCode(hudOverlay));
        if (stateHash == lastVisibilityStateHash) return;
        lastVisibilityStateHash = stateHash;

        activity.runOnUiThread(() -> {
            PojavControls.setHudVisible(!hideLauncherOverlays);
            LogcatOverlayManager logcatManager = LogcatOverlayManager.getInstance();
            if (logcatManager != null) logcatManager.setGameHudHidden(hideLauncherOverlays);
            if (modMenuButton != null) {
                int visibility = !hideLauncherOverlays && !isFloatingModMenuButtonHidden && !hudEditorMode &&
                        (!isPauseOnly || (isPauseOpen && isShowingMenu))
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE;
                modMenuButton.setVisibility(visibility);
                if (!hudEditorMode && visibility == android.view.View.GONE &&
                        modMenuOpenedFromButton && isModMenuShowing()) {
                    hideModMenu();
                }
            }

            if (hudOverlay != null) {
                int visibility = !hideLauncherOverlays && (hudOverlay.isHudEditorMode() || showGameOverlays)
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE;
                if (hudOverlay.getVisibility() != visibility) {
                    hudOverlay.setVisibility(visibility);
                }
            }

            for (BaseOverlayButton overlay : overlays) {
                if (overlay.overlayView != null) {
                    boolean visible = overlay instanceof ToggleHudOverlay
                            ? inbuiltVisible || manager.isOverlayShowEverywhere(overlay.getOverlayConfigKey())
                            : !hideLauncherOverlays && (overlay instanceof HotbarSlotOverlay ? hotbarVisible :
                            inbuiltVisible || manager.isOverlayShowEverywhere(overlay.getOverlayConfigKey()));
                    int visibility = visible
                            ? android.view.View.VISIBLE
                            : android.view.View.GONE;
                    if (overlay.overlayView.getVisibility() != visibility) {
                        overlay.overlayView.setVisibility(visibility);
                    }
                }
            }

            if (chickPetOverlay != null) {
                int visibility = !hideLauncherOverlays && (inbuiltVisible || manager.isOverlayShowEverywhere(ModIds.CHICK_PET))
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE;
                chickPetOverlay.setVisibility(visibility);
            }

            if (fpsDisplayOverlay != null) {
                int visibility = !hideLauncherOverlays && (inbuiltVisible || manager.isOverlayShowEverywhere(ModIds.FPS_DISPLAY))
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE;
                fpsDisplayOverlay.setVisibility(visibility);
            }

            if (cpsDisplayOverlay != null) {
                int visibility = !hideLauncherOverlays && (inbuiltVisible || manager.isOverlayShowEverywhere(ModIds.CPS_DISPLAY))
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE;
                cpsDisplayOverlay.setVisibility(visibility);
            }
        });
    }
}
