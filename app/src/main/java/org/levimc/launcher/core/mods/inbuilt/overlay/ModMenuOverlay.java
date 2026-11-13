package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.WindowInsets;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.inbuilt.ExternalModuleProvider;
import org.levimc.launcher.core.mods.inbuilt.InbuiltModuleProvider;
import org.levimc.launcher.core.mods.inbuilt.UnifiedMod;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ModMenuOverlay {
    private enum ModuleFilter {
        ALL,
        FAVORITES,
        ENABLED,
        INBUILT,
        EXTERNAL
    }

    private enum MenuSection {
        MODULES,
        SETTINGS,
        CONFIG,
        HUD_EDITOR
    }

    private final Activity activity;
    private Context menuContext;
    private ModMenuTheme theme;
    private ModMenuPreferences preferences;
    private final Rect safeArea = new Rect();
    private boolean rebuilding;
    private int appliedTextScale;
    private View.OnLayoutChangeListener geometryListener;
    private boolean hiding;
    private int availableWidth;
    private int availableHeight;
    private boolean sidebarCollapsed;
    private View overlayView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams wmParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;
    
    private RecyclerView modsRecycler;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchRunnable = this::applyFilters;
    private ModMenuAdapter adapter;
    private EditText searchInput;
    private ImageButton clearSearchBtn;
    private TextView navModules, navSettings, navHudEditor;
    private ImageButton compactNavModules, compactNavSettings, compactNavHudEditor;
    private TextView filterAll, filterFavorites, filterEnabled, filterInbuilt, filterExternal;
    private TextView moduleCountText, emptyStateText;
    private TextView compactFilterSelector, compactModuleCount;
    private View settingsContainer;
    private View modulesContainer;
    private View emptyState;
    private View menuContainer;
    private View modMenuSidebar;
    private View modMenuLogo;
    private View filterBar;
    private View compactFilterBar;
    private CompoundButton notificationsSwitch;
    private CompoundButton pauseMenuOnlySwitch;
    private CompoundButton compactModeSwitch;
    private TextView modMenuKeybindValue;
    private SeekBar modMenuOpacitySeekBar;
    private TextView modMenuOpacityText;
    private SeekBar modMenuButtonOpacitySeekBar;
    private TextView modMenuButtonOpacityText;
    private SeekBar hudButtonSizeSeekBar;
    private TextView hudButtonSizeText;
    private boolean updatingHudButtonSize = false;
    private boolean compactMode = false;
    private GridLayoutManager modsLayoutManager;
    
    private List<UnifiedMod> allMods = new ArrayList<>();
    private List<UnifiedMod> filteredMods = new ArrayList<>();
    private final Set<String> favoriteKeys = new HashSet<>();
    private ModuleFilter activeFilter = ModuleFilter.ALL;
    private MenuSection activeSection = MenuSection.MODULES;
    private String lastConfigModKey;
    private String lastSearchQuery = "";
    private Parcelable fullModsScrollState;
    private Parcelable compactModsScrollState;
    private int settingsScrollPosition;
    private final Map<String, Integer> legacyConfigScrollPositions = new HashMap<>();
    private boolean restoringLastSection;
    
    private ModMenuCallback callback;
    private ModNotificationManager notificationManager;
    
    private void crossfade(View view) {
        view.setAlpha(0f);
        view.setTranslationX(dp(8));
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(160)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
            .start();
    }

    private void animateMenuEnter(final View menuContainer) {
        menuContainer.setAlpha(0f);
        menuContainer.setScaleX(0.97f);
        menuContainer.setScaleY(0.97f);
        
        menuContainer.post(() -> {
            menuContainer.setPivotX(menuContainer.getWidth() / 2f);
            menuContainer.setPivotY(menuContainer.getHeight() / 2f);
            
            float targetAlpha = 1f;
            
            menuContainer.animate()
                .alpha(targetAlpha)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
        });
    }
    
    private void animateMenuExit(final View menuContainer, Runnable onEnd) {
        menuContainer.setPivotX(menuContainer.getWidth() / 2f);
        menuContainer.setPivotY(menuContainer.getHeight() / 2f);
        
        menuContainer.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(180)
            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
            .setListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    menuContainer.animate().setListener(null);
                    onEnd.run();
                }
            })
            .start();
    }

    private int getAccentColor() {
        return theme.accent;
    }
    
    public interface ModMenuCallback {
        void onModToggled(String modId, boolean enabled);
        void onButtonOpacityChanged(int opacity);
    }
    
    public ModMenuOverlay(Activity activity) {
        this.activity = activity;
        this.preferences = new ModMenuPreferences(activity);
        this.theme = new ModMenuTheme(activity);
        this.windowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
        this.notificationManager = new ModNotificationManager(activity);
    }
    
    public void setCallback(ModMenuCallback callback) {
        this.callback = callback;
    }
    
    public void show() {
        if (isShowing) {
            refreshMods();
            return;
        }
        showInternal();
    }
    
    private void prepareMenuKeyboard() {
        ModMenuKeyRoot root = (ModMenuKeyRoot) overlayView;
        root.setShortcutHandler(event -> {
            InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
            return overlayManager != null && overlayManager.handleModMenuShortcut(event);
        });
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private void showInternal() {
        if (isShowing || activity.isFinishing() || activity.isDestroyed()) return;
        
        try {
            overlayView = LayoutInflater.from(menuContext = ModMenuTheme.wrap(activity)).inflate(R.layout.overlay_mod_menu, null);
            prepareMenuKeyboard();
            
            int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            overlayView.setSystemUiVisibility(uiOptions);
            
            setupViews();
            loadMods();
            restoreModsScrollPosition();
            
            wmParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            );
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                wmParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            wmParams.gravity = Gravity.CENTER;
            wmParams.token = activity.getWindow().getDecorView().getWindowToken();
            
            windowManager.addView(overlayView, wmParams);
            isShowing = true;
            restoreLastSection();
            overlayView.requestFocus();
            
            overlayView.setAlpha(0f);
            overlayView.animate().alpha(1f).setDuration(220).start();
            
            View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
            if (menuContainer != null && activeSection != MenuSection.HUD_EDITOR) {
                animateMenuEnter(menuContainer);
            }
        } catch (Exception e) {
            showFallback();
        }
    }
    
    private void showFallback() {
        if (isShowing) return;
        ViewGroup rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;
        
        overlayView = LayoutInflater.from(menuContext = ModMenuTheme.wrap(activity)).inflate(R.layout.overlay_mod_menu, null);
        prepareMenuKeyboard();
        setupViews();
        loadMods();
        restoreModsScrollPosition();
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        rootView.addView(overlayView, params);
        isShowing = true;
        overlayView.requestFocus();
        wmParams = null;
        restoreLastSection();
        
        overlayView.setAlpha(0f);
        overlayView.animate().alpha(1f).setDuration(220).start();
        
        View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        if (menuContainer != null && activeSection != MenuSection.HUD_EDITOR) {
            animateMenuEnter(menuContainer);
        }
    }
    
    private void setupViews() {
        theme = new ModMenuTheme(menuContext);
        appliedTextScale = preferences.textScale();
        new ModMenuSettingsView(menuContext, overlayView.findViewById(R.id.menu_settings_content),
                this::rebuildAppearance, this::applyLiveAppearance, () -> notificationManager.preview());
        menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        modMenuSidebar = overlayView.findViewById(R.id.mod_menu_sidebar);
        modMenuLogo = overlayView.findViewById(R.id.mod_menu_logo);
        filterBar = overlayView.findViewById(R.id.filter_bar);
        compactFilterBar = overlayView.findViewById(R.id.compact_filter_bar);
        compactFilterSelector = overlayView.findViewById(R.id.compact_filter_selector);
        compactModuleCount = overlayView.findViewById(R.id.compact_module_count);
        ImageButton closeBtn = overlayView.findViewById(R.id.btn_close_menu);
        searchInput = overlayView.findViewById(R.id.search_input);
        clearSearchBtn = overlayView.findViewById(R.id.btn_clear_search);
        modsRecycler = overlayView.findViewById(R.id.mods_grid_recycler);
        navModules = overlayView.findViewById(R.id.nav_modules);
        navSettings = overlayView.findViewById(R.id.nav_settings);
        navHudEditor = overlayView.findViewById(R.id.nav_hud_editor);
        compactNavModules = overlayView.findViewById(R.id.nav_modules_compact);
        compactNavSettings = overlayView.findViewById(R.id.nav_settings_compact);
        compactNavHudEditor = overlayView.findViewById(R.id.nav_hud_editor_compact);
        filterAll = overlayView.findViewById(R.id.filter_all);
        filterFavorites = overlayView.findViewById(R.id.filter_favorites);
        filterEnabled = overlayView.findViewById(R.id.filter_enabled);
        filterInbuilt = overlayView.findViewById(R.id.filter_inbuilt);
        filterExternal = overlayView.findViewById(R.id.filter_external);
        moduleCountText = overlayView.findViewById(R.id.module_count_text);
        settingsContainer = overlayView.findViewById(R.id.settings_container);
        modulesContainer = overlayView.findViewById(R.id.modules_container);
        emptyState = overlayView.findViewById(R.id.empty_state);
        emptyStateText = overlayView.findViewById(R.id.empty_state_text);
        notificationsSwitch = overlayView.findViewById(R.id.switch_notifications);
        pauseMenuOnlySwitch = overlayView.findViewById(R.id.switch_pause_menu_only);
        compactModeSwitch = overlayView.findViewById(R.id.switch_compact_mod_menu);

        View hudEditorTools = overlayView.findViewById(R.id.hud_editor_tools);
        View hudEditorDragHandle = overlayView.findViewById(R.id.hud_editor_drag_handle);
        if (hudEditorDragHandle != null) setupHudEditorDragHandle(hudEditorDragHandle, hudEditorTools);
        View btnHudSave = overlayView.findViewById(R.id.btn_hud_save);
        View btnHudCancel = overlayView.findViewById(R.id.btn_hud_cancel);
        View modMenuContainer = overlayView.findViewById(R.id.mod_menu_container);
        hudButtonSizeSeekBar = overlayView.findViewById(R.id.seekbar_hud_button_size);
        hudButtonSizeText = overlayView.findViewById(R.id.text_hud_button_size);

        if (hudButtonSizeSeekBar != null) {
            hudButtonSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updatingHudButtonSize) return;
                    InbuiltOverlayManager manager = InbuiltOverlayManager.getInstance();
                    if (manager != null) {
                        manager.setSelectedHudEditorButtonSize(progress);
                    }
                    updateHudButtonSizeText(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            updateHudEditorSizeControls(0);
        }

        if (navHudEditor != null) {
            navHudEditor.setOnClickListener(v -> enterHudEditorMode(modMenuContainer, hudEditorTools));
        }
        if (compactNavHudEditor != null) {
            compactNavHudEditor.setOnClickListener(v -> enterHudEditorMode(modMenuContainer, hudEditorTools));
        }
        
        if (btnHudSave != null) {
            btnHudSave.setOnClickListener(v -> {
                exitHudEditorMode(modMenuContainer, hudEditorTools);
            });
        }
        
        View btnHudReset = overlayView.findViewById(R.id.btn_hud_reset);
        if (btnHudReset != null) {
            btnHudReset.setOnClickListener(v -> {
                InbuiltOverlayManager.getInstance().resetAllPositionsToCenter();
            });
        }

        if (btnHudCancel != null) {
            btnHudCancel.setOnClickListener(v -> {
                exitHudEditorMode(modMenuContainer, hudEditorTools);
            });
        }
        
        // Close on background tap
        overlayView.setOnClickListener(v -> {
            // Only hide if not in HUD editor mode
            if (hudEditorTools == null || hudEditorTools.getVisibility() != View.VISIBLE) {
                hide();
            }
        });
        menuContainer.setOnClickListener(v -> {}); // Consume clicks
        if (hudEditorTools != null) {
            hudEditorTools.setOnClickListener(v -> {}); // Consume clicks
        }
        
        closeBtn.setOnClickListener(v -> hide());
        
        // Search functionality
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastSearchQuery = s.toString();
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, 60L);
                clearSearchBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
        
        clearSearchBtn.setOnClickListener(v -> {
            searchInput.setText("");
            clearSearchBtn.setVisibility(View.GONE);
        });
        if (!lastSearchQuery.isEmpty()) searchInput.setText(lastSearchQuery);
        setupFilterButtons();
        setupCompactFilter();
        
        View btnBackToModules = overlayView.findViewById(R.id.btn_back_to_modules);
        if (btnBackToModules != null) {
            btnBackToModules.setOnClickListener(v -> showModulesSection());
        }
        
        // Navigation
        navModules.setOnClickListener(v -> showModulesSection());
        navSettings.setOnClickListener(v -> showSettingsSection());
        if (compactNavModules != null) compactNavModules.setOnClickListener(v -> showModulesSection());
        if (compactNavSettings != null) compactNavSettings.setOnClickListener(v -> showSettingsSection());

        // Settings
        InbuiltModManager modManager = InbuiltModManager.getInstance(activity);
        compactMode = modManager.isModMenuCompact();
        notificationsSwitch.setChecked(modManager.isNotificationsEnabled());
        notificationsSwitch.setOnCheckedChangeListener((btn, checked) -> {
            modManager.setNotificationsEnabled(checked);
            if (!checked) notificationManager.hideAll();
        });

        if (pauseMenuOnlySwitch != null) {
            pauseMenuOnlySwitch.setChecked(modManager.isPauseMenuOnly());
            pauseMenuOnlySwitch.setOnCheckedChangeListener((btn, checked) -> {
                modManager.setPauseMenuOnly(checked);
                if (InbuiltOverlayManager.getInstance() != null) InbuiltOverlayManager.getInstance().refreshRuntimeVisibility();
            });
        }

        modMenuKeybindValue = overlayView.findViewById(R.id.mod_menu_keybind_value);
        View modMenuKeybindSetting = overlayView.findViewById(R.id.setting_mod_menu_keybind);
        updateModMenuKeybindValue();
        if (modMenuKeybindSetting != null) {
            modMenuKeybindSetting.setOnClickListener(v -> showModMenuKeybindDialog());
        }

        if (compactModeSwitch != null) {
            compactModeSwitch.setChecked(compactMode);
            compactModeSwitch.setOnCheckedChangeListener((btn, checked) -> {
                modManager.setModMenuCompact(checked);
                setCompactMode(checked);
                rebuildAppearance();
            });
        }

        modMenuOpacitySeekBar = overlayView.findViewById(R.id.seekbar_mod_menu_opacity);
        modMenuOpacityText = overlayView.findViewById(R.id.text_mod_menu_opacity);
        int currentMenuOpacity = modManager.getModMenuOpacity();
        modMenuOpacitySeekBar.setProgress(currentMenuOpacity);
        modMenuOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, currentMenuOpacity));
        modMenuOpacitySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    modMenuOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, progress));
                    modManager.setModMenuOpacity(progress);
                    applyMenuOpacity();
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        modMenuButtonOpacitySeekBar = overlayView.findViewById(R.id.seekbar_mod_menu_button_opacity);
        modMenuButtonOpacityText = overlayView.findViewById(R.id.text_mod_menu_button_opacity);
        int currentButtonOpacity = modManager.getModMenuButtonOpacity();
        modMenuButtonOpacitySeekBar.setProgress(currentButtonOpacity);
        modMenuButtonOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, currentButtonOpacity));
        modMenuButtonOpacitySeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    modMenuButtonOpacityText.setText(activity.getString(R.string.mod_menu_percent_value, progress));
                    modManager.setModMenuButtonOpacity(progress);
                    if (callback != null) {
                        callback.onButtonOpacityChanged(progress);
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        applyMenuOpacity();
        
        adapter = new ModMenuAdapter();
        adapter.setCompactMode(compactMode);
        modsLayoutManager = new GridLayoutManager(activity, compactMode ? 1 : 4);
        modsLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter != null && adapter.isGroupHeader(position)
                    ? modsLayoutManager.getSpanCount()
                    : 1;
            }
        });
        modsRecycler.setLayoutManager(modsLayoutManager);
        modsRecycler.setItemAnimator(null);
        modsRecycler.setHasFixedSize(true);
        adapter.setOnModActionListener(new ModMenuAdapter.OnModActionListener() {
            @Override
            public void onToggle(UnifiedMod mod, boolean enabled) {
                mod.applyEnabled(enabled);
                InbuiltModManager modManager = InbuiltModManager.getInstance(activity);
                if (modManager.isNotificationsEnabled() && (enabled || preferences.notifyDisabled())) {
                    notificationManager.show(mod.getName(), mod.getId(), enabled);
                }
                if (callback != null) {
                    callback.onModToggled(mod.getStableKey(), enabled);
                }
                if (activeFilter == ModuleFilter.ENABLED) {
                    applyFilters();
                }
            }
            @Override
            public void onConfig(UnifiedMod mod) {
                showConfigSection(mod);
            }
            @Override
            public void onFavoriteChanged(UnifiedMod mod, boolean favorite) {
                InbuiltModManager.getInstance(activity).setModFavorite(mod.getStableKey(), favorite);
                if (favorite) {
                    favoriteKeys.add(mod.getStableKey());
                } else {
                    favoriteKeys.remove(mod.getStableKey());
                }
                if (activeFilter == ModuleFilter.FAVORITES) {
                    applyFilters();
                }
            }
        });
        modsRecycler.setAdapter(adapter);
        applyCompactModeLayout(compactMode);
        applyAppearance();
        setupWindowGestures();
        if (geometryListener != null) overlayView.removeOnLayoutChangeListener(geometryListener);
        geometryListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) updateMenuGeometry();
        };
        overlayView.addOnLayoutChangeListener(geometryListener);
        overlayView.setOnApplyWindowInsetsListener((v, insets) -> {
            updateMenuGeometry();
            return insets;
        });
        overlayView.post(this::updateMenuGeometry);
        overlayView.findViewById(R.id.btn_menu_search).setOnClickListener(v -> {
            showModulesSection();
            ModMenuDialogs.search(v, lastSearchQuery, value -> searchInput.setText(value));
        });
        overlayView.findViewById(R.id.btn_menu_compact).setOnClickListener(v -> compactModeSwitch.setChecked(!compactMode));
        searchInput.setOnFocusChangeListener((v, focused) -> {
            if (focused && activeSection != MenuSection.MODULES) showModulesSection();
        });
        overlayView.findViewById(R.id.menu_clear_filters).setOnClickListener(v -> {
            searchInput.setText("");
            setModuleFilter(ModuleFilter.ALL);
        });
    }

    private void updateModMenuKeybindValue() {
        if (modMenuKeybindValue == null) return;
        int keyCode = InbuiltModManager.getInstance(activity).getModMenuKeybind();
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            modMenuKeybindValue.setText(R.string.mod_menu_keybind_unbound);
            TextView footer = overlayView.findViewById(R.id.menu_sidebar_footer);
            if (footer != null) footer.setText(activity.getString(R.string.mw_shortcut_hint, modMenuKeybindValue.getText()));
            return;
        }
        String keyLabel = KeyEvent.keyCodeToString(keyCode);
        if (keyLabel != null && keyLabel.startsWith("KEYCODE_")) {
            keyLabel = keyLabel.substring(8);
        }
        modMenuKeybindValue.setText(keyLabel == null ? "" : keyLabel.replace('_', ' '));
        TextView footer = overlayView.findViewById(R.id.menu_sidebar_footer);
        if (footer != null) footer.setText(activity.getString(R.string.mw_shortcut_hint, modMenuKeybindValue.getText()));
    }

    private void showModMenuKeybindDialog() {
        ModMenuDialogs.keybind(overlayView.findViewById(R.id.setting_mod_menu_keybind),
                activity.getString(R.string.mod_menu_keybind), InbuiltModManager.getInstance(activity).getModMenuKeybind(),
                KeyEvent.KEYCODE_M, key -> {
                    InbuiltModManager.getInstance(activity).setModMenuKeybind(key);
                    updateModMenuKeybindValue();
                });
    }

    private void restoreLastSection() {
        restoringLastSection = true;
        try {
            switch (activeSection) {
                case SETTINGS:
                    showSettingsSection();
                    break;
                case CONFIG:
                    boolean found = false;
                    for (UnifiedMod mod : allMods) {
                        if (mod.getStableKey().equals(lastConfigModKey) && mod.hasConfig()) {
                            showConfigSection(mod);
                            found = true;
                            break;
                        }
                    }
                    if (!found) showModulesSection();
                    break;
                case HUD_EDITOR:
                    enterHudEditorMode(menuContainer, overlayView.findViewById(R.id.hud_editor_tools));
                    break;
                case MODULES:
                default:
                    showModulesSection();
                    break;
            }
        } finally {
            restoringLastSection = false;
        }
    }

    private void saveVisibleScrollPosition() {
        if (restoringLastSection || !isShowing || overlayView == null) return;
        if (activeSection == MenuSection.MODULES && modulesContainer != null
                && modulesContainer.getVisibility() == View.VISIBLE && modsLayoutManager != null
                && modsRecycler != null && modsRecycler.getChildCount() > 0) {
            Parcelable state = modsLayoutManager.onSaveInstanceState();
            if (compactMode) compactModsScrollState = state;
            else fullModsScrollState = state;
        } else if (activeSection == MenuSection.SETTINGS && settingsContainer instanceof ScrollView) {
            settingsScrollPosition = ((ScrollView) settingsContainer).getScrollY();
        } else if (activeSection == MenuSection.CONFIG) {
            ViewGroup content = overlayView.findViewById(R.id.mod_config_content);
            RuntimeConfigView.save(content);
            if (content != null && content.getChildCount() > 0
                    && content.getChildAt(0) instanceof ScrollView && lastConfigModKey != null) {
                legacyConfigScrollPositions.put(lastConfigModKey,
                        ((ScrollView) content.getChildAt(0)).getScrollY());
            }
        }
    }

    private void restoreModsScrollPosition() {
        if (modsLayoutManager == null || adapter == null || adapter.getItemCount() == 0) return;
        Parcelable state = compactMode ? compactModsScrollState : fullModsScrollState;
        if (state != null) modsLayoutManager.onRestoreInstanceState(state);
    }

    private void showModulesSection() {
        ModMenuDialogs.dismissAll();
        saveVisibleScrollPosition();
        if (overlayView != null) RuntimeConfigView.stop(overlayView.findViewById(R.id.mod_config_content));
        activeSection = MenuSection.MODULES;
        updatePageTitle(R.string.mod_menu_modules, true);
        updateNavigationItem(navModules, compactNavModules, true);
        updateNavigationItem(navSettings, compactNavSettings, false);
        updateNavigationItem(navHudEditor, compactNavHudEditor, false);
        
        if (modulesContainer.getVisibility() != View.VISIBLE) {
            modulesContainer.setVisibility(View.VISIBLE);
            crossfade(modulesContainer);
        }
        settingsContainer.setVisibility(View.GONE);
        
        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            if (configHeader != null) configHeader.setVisibility(View.GONE);
            updateFilterBarVisibility();
        }
    }
    
    private void showSettingsSection() {
        ModMenuDialogs.dismissAll();
        saveVisibleScrollPosition();
        if (overlayView != null) RuntimeConfigView.stop(overlayView.findViewById(R.id.mod_config_content));
        activeSection = MenuSection.SETTINGS;
        updatePageTitle(R.string.mm_customize, false);
        updateNavigationItem(navSettings, compactNavSettings, true);
        updateNavigationItem(navModules, compactNavModules, false);
        updateNavigationItem(navHudEditor, compactNavHudEditor, false);
        
        modulesContainer.setVisibility(View.GONE);
        if (settingsContainer.getVisibility() != View.VISIBLE) {
            settingsContainer.setVisibility(View.VISIBLE);
            crossfade(settingsContainer);
        }
        
        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            if (modConfigContainer != null) modConfigContainer.setVisibility(View.GONE);
            if (searchContainer != null) searchContainer.setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            if (configHeader != null) configHeader.setVisibility(View.GONE);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
        }
        if (restoringLastSection && settingsContainer instanceof ScrollView) {
            View current = settingsContainer;
            int position = settingsScrollPosition;
            current.post(() -> {
                if (overlayView != null && settingsContainer == current) {
                    ((ScrollView) current).scrollTo(0, position);
                }
            });
        }
    }
    
    private void showConfigSection(UnifiedMod mod) {
        ModMenuDialogs.dismissAll();
        saveVisibleScrollPosition();
        if (mod.openCustomConfig()) {
            hide();
            return;
        }
        activeSection = MenuSection.CONFIG;
        updatePageTitle(0, false);
        lastConfigModKey = mod.getStableKey();
        updateNavigationItem(navModules, compactNavModules, false);
        updateNavigationItem(navSettings, compactNavSettings, false);
        updateNavigationItem(navHudEditor, compactNavHudEditor, false);
        
        modulesContainer.setVisibility(View.GONE);
        settingsContainer.setVisibility(View.GONE);
        
        if (overlayView != null) {
            View modConfigContainer = overlayView.findViewById(R.id.mod_config_container);
            View searchContainer = overlayView.findViewById(R.id.search_container);
            View configHeader = overlayView.findViewById(R.id.config_header);
            ViewGroup modConfigContent = overlayView.findViewById(R.id.mod_config_content);
            TextView configTitle = overlayView.findViewById(R.id.config_title);
            
            if (modConfigContainer != null) {
                modConfigContainer.setVisibility(View.VISIBLE);
                crossfade(modConfigContainer);
            }
            if (searchContainer != null) searchContainer.setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            if (configHeader != null) configHeader.setVisibility(View.VISIBLE);
            if (filterBar != null) filterBar.setVisibility(View.GONE);
            if (compactFilterBar != null) compactFilterBar.setVisibility(View.GONE);
            if (configTitle != null) configTitle.setText(mod.getName());
            
            if (modConfigContent != null) {
                ModConfigView.render(menuContext, modConfigContent, mod, compactMode, () -> {
                    InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
                    if (overlayManager != null) {
                        overlayManager.applyConfigurationChanges(mod.getId());
                    }
                });
                if (modConfigContent.getChildCount() > 0
                        && modConfigContent.getChildAt(0) instanceof ScrollView) {
                    ScrollView scroll = (ScrollView) modConfigContent.getChildAt(0);
                    int position = legacyConfigScrollPositions.getOrDefault(mod.getStableKey(), 0);
                    scroll.post(() -> {
                        if (overlayView != null && scroll.getParent() == modConfigContent) {
                            scroll.scrollTo(0, position);
                        }
                    });
                }
            }
        }
    }

    private void setupHudEditorDragHandle(View handle, View tools) {
        final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private int pointerId = -1;
            private boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (overlayView == null || tools == null || tools.getVisibility() != View.VISIBLE) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointerId = event.getPointerId(0);
                        downX = event.getRawX();
                        downY = event.getRawY();
                        dragging = false;
                        if (wmParams != null) {
                            startX = wmParams.x;
                            startY = wmParams.y;
                        } else {
                            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
                            startX = params.leftMargin;
                            startY = params.topMargin;
                        }
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (pointerId < 0 || event.getPointerId(0) != pointerId) return true;
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) dragging = true;
                        if (dragging) moveHudEditorTools(startX + Math.round(dx), startY + Math.round(dy));
                        return true;
                    case MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) != pointerId) return true;
                        pointerId = -1;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (pointerId >= 0 && !dragging) view.performClick();
                        // Fall through to release the gesture on both up and cancellation.
                    case MotionEvent.ACTION_CANCEL:
                        pointerId = -1;
                        dragging = false;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void moveHudEditorTools(int x, int y) {
        if (overlayView == null) return;
        OverlayBounds.Position position = OverlayBounds.clampPosition(activity, overlayView, x, y);
        if (wmParams != null && windowManager != null) {
            wmParams.x = position.x;
            wmParams.y = position.y;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.leftMargin = position.x;
            params.topMargin = position.y;
            overlayView.setLayoutParams(params);
        }
    }

    private void enterHudEditorMode(View modMenuContainer, View hudEditorTools) {
        saveVisibleScrollPosition();
        activeSection = MenuSection.HUD_EDITOR;
        RuntimeConfigView.stop(overlayView.findViewById(R.id.mod_config_content));
        updateNavigationItem(navModules, compactNavModules, false);
        updateNavigationItem(navSettings, compactNavSettings, false);
        updateNavigationItem(navHudEditor, compactNavHudEditor, true);

        if (modMenuContainer != null) {
            modMenuContainer.setVisibility(View.GONE);
        }
        if (hudEditorTools != null) {
            hudEditorTools.setVisibility(View.VISIBLE);
            hudEditorTools.setAlpha(1f);
            hudEditorTools.setTranslationX(0f);
        }
        if (overlayView != null) {
            overlayView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlayView.setClickable(false);
        }
        if (wmParams != null && windowManager != null) {
            wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
            wmParams.gravity = Gravity.TOP | Gravity.LEFT;
            wmParams.x = 0;
            wmParams.y = 0;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView != null && overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.leftMargin = 0;
            params.topMargin = 0;
            overlayView.setLayoutParams(params);
        }
        // Wait for the compact tool window to be measured before centering it.
        final View editorRoot = overlayView;
        if (editorRoot != null) editorRoot.post(() -> {
            if (overlayView != editorRoot || hudEditorTools == null || hudEditorTools.getVisibility() != View.VISIBLE) return;
            OverlayBounds.Position rightEdge = OverlayBounds.clampPosition(
                    activity, editorRoot, Integer.MAX_VALUE, 0);
            moveHudEditorTools(rightEdge.x / 2, 0);
        });
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.setHudEditorSelectionListener(this::updateHudEditorSizeControls);
            overlayManager.setHudEditorMode(true);
        }
    }

    private void exitHudEditorMode(View modMenuContainer, View hudEditorTools) {
        if (hudEditorTools != null) {
            hudEditorTools.setVisibility(View.GONE);
        }
        if (overlayView != null) {
            overlayView.setClickable(true);
        }
        if (wmParams != null && windowManager != null) {
            wmParams.width = WindowManager.LayoutParams.MATCH_PARENT;
            wmParams.height = WindowManager.LayoutParams.MATCH_PARENT;
            wmParams.gravity = Gravity.CENTER;
            wmParams.x = 0;
            wmParams.y = 0;
            windowManager.updateViewLayout(overlayView, wmParams);
        } else if (overlayView != null && overlayView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlayView.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.CENTER;
            params.leftMargin = 0;
            params.topMargin = 0;
            overlayView.setLayoutParams(params);
        }
        if (modMenuContainer != null) {
            modMenuContainer.setVisibility(View.VISIBLE);
            animateMenuEnter(modMenuContainer);
        }
        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.setHudEditorMode(false);
            overlayManager.setHudEditorSelectionListener(null);
        }
        showModulesSection();
        applyAppearance();
        overlayView.post(this::updateMenuGeometry);
    }

    private void updateHudEditorSizeControls(int sizeDp) {
        if (hudButtonSizeSeekBar == null) return;
        boolean hasSelection = sizeDp > 0;
        hudButtonSizeSeekBar.setEnabled(hasSelection);
        if (!hasSelection) {
            updateHudButtonSizeText(0);
            return;
        }
        updatingHudButtonSize = true;
        hudButtonSizeSeekBar.setProgress(sizeDp);
        updatingHudButtonSize = false;
        updateHudButtonSizeText(sizeDp);
    }

    private void updateHudButtonSizeText(int size) {
        if (hudButtonSizeText == null) return;
        if (size <= 0) {
            hudButtonSizeText.setText(activity.getString(R.string.overlay_button_size));
        } else {
            hudButtonSizeText.setText(activity.getString(R.string.overlay_button_size_value, size));
        }
    }

    private void setupCompactFilter() {
        if (compactFilterSelector == null) return;
        compactFilterSelector.setOnClickListener(this::showCompactFilterMenu);
        updateCompactFilterSelector();
    }

    private void showCompactFilterMenu(View anchor) {
        ModuleFilter[] filters = ModuleFilter.values();
        String[] labels = new String[filters.length];
        for (int i = 0; i < filters.length; i++) labels[i] = activity.getString(getFilterLabelRes(filters[i]));
        ModMenuDialogs.choices(anchor, activity.getString(R.string.mod_menu_modules), labels,
                activeFilter.ordinal(), null, index -> setModuleFilter(filters[index]));
    }

    private void setCompactMode(boolean compact) {
        saveVisibleScrollPosition();
        compactMode = compact;
        if (modsLayoutManager != null) {
            modsLayoutManager.setSpanCount(ModMenuGeometry.columns(modsRecycler.getWidth(), activity.getResources().getDisplayMetrics().density, compact));
        }
        if (adapter != null) {
            adapter.setCompactMode(compact);
            adapter.updateMods(filteredMods, favoriteKeys);
        }
        restoreModsScrollPosition();
        applyCompactModeLayout(compact);
        updateFilterBarVisibility();
        updateFilterButtons();
        updateModuleCount();
    }

    private void applyCompactModeLayout(boolean compact) {
        if (menuContainer == null) return;
        if (modMenuSidebar != null) {
            ViewGroup.LayoutParams params = modMenuSidebar.getLayoutParams();
            params.width = dp(sidebarCollapsed ? 48 : 152);
            modMenuSidebar.setLayoutParams(params);
        }
        setNavigationMode(navModules, compactNavModules, R.string.mod_menu_modules, compact);
        setNavigationMode(navHudEditor, compactNavHudEditor, R.string.mod_menu_hud_editor, compact);
        setNavigationMode(navSettings, compactNavSettings, R.string.mm_customize, compact);
        updateNavigationItem(navModules, compactNavModules, activeSection == MenuSection.MODULES);
        updateNavigationItem(navSettings, compactNavSettings, activeSection == MenuSection.SETTINGS);
        updateNavigationItem(navHudEditor, compactNavHudEditor, activeSection == MenuSection.HUD_EDITOR);
        if (modsRecycler != null) modsRecycler.setPadding(dp(7), 0, dp(7), dp(18));
        updateMenuGeometry();
    }

    private void setNavigationMode(TextView fullView, ImageButton compactView, int textRes, boolean compact) {
        if (fullView != null) {
            fullView.setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            fullView.setText(textRes);
            fullView.setGravity(Gravity.CENTER_VERTICAL);
            fullView.setIncludeFontPadding(false);
            fullView.setPadding(dp(8), 0, dp(6), 0);
            fullView.setCompoundDrawablePadding(dp(8));
            Drawable icon = fullView.getCompoundDrawablesRelative()[0];
            if (icon != null) icon.setBounds(0, 0, dp(18), dp(18));
            fullView.setCompoundDrawablesRelative(icon, null, null, null);
        }
        if (compactView != null) compactView.setVisibility(sidebarCollapsed ? View.VISIBLE : View.GONE);
    }

    private void updateFilterBarVisibility() {
        boolean modulesVisible = modulesContainer != null && modulesContainer.getVisibility() == View.VISIBLE;
        if (filterBar != null) {
            filterBar.setVisibility(modulesVisible && !compactMode ? View.VISIBLE : View.GONE);
        }
        if (compactFilterBar != null) {
            compactFilterBar.setVisibility(modulesVisible && compactMode ? View.VISIBLE : View.GONE);
        }
    }

    private void updateCompactFilterSelector() {
        if (compactFilterSelector == null) return;
        compactFilterSelector.setText(getFilterLabelRes(activeFilter));
        compactFilterSelector.setTextColor(getAccentColor());
    }

    private int getFilterLabelRes(ModuleFilter filter) {
        switch (filter) {
            case FAVORITES:
                return R.string.mod_menu_favorites;
            case ENABLED:
                return R.string.mod_menu_filter_enabled;
            case INBUILT:
                return R.string.mod_menu_filter_inbuilt;
            case EXTERNAL:
                return R.string.mod_menu_filter_external;
            case ALL:
            default:
                return R.string.filter_all;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void setupFilterButtons() {
        if (filterAll != null) {
            filterAll.setOnClickListener(v -> setModuleFilter(ModuleFilter.ALL));
        }
        if (filterFavorites != null) {
            filterFavorites.setOnClickListener(v -> setModuleFilter(ModuleFilter.FAVORITES));
        }
        if (filterEnabled != null) {
            filterEnabled.setOnClickListener(v -> setModuleFilter(ModuleFilter.ENABLED));
        }
        if (filterInbuilt != null) {
            filterInbuilt.setOnClickListener(v -> setModuleFilter(ModuleFilter.INBUILT));
        }
        if (filterExternal != null) {
            filterExternal.setOnClickListener(v -> setModuleFilter(ModuleFilter.EXTERNAL));
        }
        updateFilterButtons();
    }

    private void setModuleFilter(ModuleFilter filter) {
        activeFilter = filter;
        updateFilterButtons();
        applyFilters();
    }

    private void applyFilters() {
        filteredMods.clear();
        String query = searchInput != null
            ? searchInput.getText().toString().trim().toLowerCase(Locale.ROOT)
            : "";

        Map<String, GroupedMods> groupedMatches = new LinkedHashMap<>();
        for (UnifiedMod mod : allMods) {
            if (matchesActiveFilter(mod) && matchesQuery(mod, query)) {
                GroupedMods group = groupedMatches.computeIfAbsent(
                    mod.getGroupId(), ignored -> new GroupedMods());
                group.add(mod, isFavorite(mod));
            }
        }
        for (GroupedMods group : groupedMatches.values()) {
            group.appendTo(filteredMods);
        }

        if (adapter != null) {
            adapter.updateMods(filteredMods, favoriteKeys);
        }
        updateEmptyState();
        updateModuleCount();
    }

    private boolean matchesActiveFilter(UnifiedMod mod) {
        switch (activeFilter) {
            case FAVORITES:
                return isFavorite(mod);
            case ENABLED:
                return mod.isEnabled();
            case INBUILT:
                return mod.getSource() == UnifiedMod.Source.INBUILT;
            case EXTERNAL:
                return mod.getSource() == UnifiedMod.Source.EXTERNAL;
            case ALL:
            default:
                return true;
        }
    }

    private boolean matchesQuery(UnifiedMod mod, String query) {
        if (query.isEmpty()) return true;
        String searchText = (
            safeString(mod.getName()) + " " +
            safeString(mod.getDescription()) + " " +
            safeString(mod.getId()) + " " +
            safeString(mod.getModId()) + " " +
            safeString(mod.getGroupName()) + " " +
            safeString(mod.getGroupId())
        ).toLowerCase(Locale.ROOT);
        return searchText.contains(query);
    }

    private boolean isFavorite(UnifiedMod mod) {
        return favoriteKeys.contains(mod.getStableKey());
    }

    private String safeString(String value) {
        return value != null ? value : "";
    }

    private void updateFilterButtons() {
        updateFilterButton(filterAll, activeFilter == ModuleFilter.ALL);
        updateFilterButton(filterFavorites, activeFilter == ModuleFilter.FAVORITES);
        updateFilterButton(filterEnabled, activeFilter == ModuleFilter.ENABLED);
        updateFilterButton(filterInbuilt, activeFilter == ModuleFilter.INBUILT);
        updateFilterButton(filterExternal, activeFilter == ModuleFilter.EXTERNAL);
        updateCompactFilterSelector();
    }

    private void updateFilterButton(TextView view, boolean selected) {
        if (view == null) return;
        view.setTextColor(selected ? theme.text : theme.secondary);
        view.setAlpha(1f);
        view.setSelected(selected);
        view.setBackground(theme.touch(selected ? theme.selected : Color.TRANSPARENT, 6, false));
    }

    private void updateNavigationItem(TextView fullView, ImageButton compactView, boolean selected) {
        int color = selected ? theme.text : theme.secondary;
        if (fullView != null) {
            fullView.setTextColor(color);
            fullView.setAlpha(1f);
            fullView.setSelected(selected);
            fullView.setCompoundDrawableTintList(ColorStateList.valueOf(selected ? theme.accent : theme.secondary));
            fullView.setBackground(theme.touch(selected ? theme.selected : Color.TRANSPARENT, 6, false));
        }
        if (compactView != null) {
            compactView.setImageTintList(ColorStateList.valueOf(selected ? theme.text : theme.secondary));
            compactView.setBackground(theme.touch(selected ? theme.selected : Color.TRANSPARENT, 6, false));
        }
    }

    private void updateModuleCount() {
        String value = activity.getString(
            R.string.mod_menu_module_count,
            filteredMods.size(),
            allMods.size());
        if (moduleCountText != null) {
            moduleCountText.setText(value);
        }
        if (compactModuleCount != null) {
            compactModuleCount.setText(value);
        }
    }

    private void loadMods() {
        allMods.clear();

        InbuiltModManager manager = InbuiltModManager.getInstance(activity);
        favoriteKeys.clear();
        favoriteKeys.addAll(manager.getFavoriteModKeys());
        allMods.addAll(InbuiltModuleProvider.load(activity));
        allMods.addAll(ExternalModuleProvider.load(activity));

        applyFilters();
    }
    
    private void filterMods(String query) {
        applyFilters();
    }
    
    private void updateEmptyState() {
        if (emptyState != null) {
            emptyState.setVisibility(filteredMods.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (emptyStateText != null) {
            String query = searchInput != null ? searchInput.getText().toString().trim() : "";
            if (!query.isEmpty()) {
                emptyStateText.setText(R.string.mod_menu_no_matches);
            } else if (activeFilter == ModuleFilter.FAVORITES) {
                emptyStateText.setText(R.string.mod_menu_no_favorites);
            } else {
                emptyStateText.setText(R.string.mod_menu_no_mods);
            }
        }
    }
    
    public void refreshMods() {
        loadMods();
    }

    private void applyMenuOpacity() {
        if (overlayView == null) return;
        int opacity = InbuiltModManager.getInstance(activity).getModMenuOpacity();
        View panel = overlayView.findViewById(R.id.mod_menu_surface);
        if (panel != null) panel.setBackground(theme.panel(opacity));
        if (modMenuSidebar != null) {
            int rail = (theme.sidebar & 0xFFFFFF) | (Math.round(Math.min(100, opacity) * 2.55f) << 24);
            modMenuSidebar.setBackgroundColor(rail);
        }
    }
    
    public void hide() {
        if (!isShowing || overlayView == null || hiding) return;
        hiding = true;
        ModMenuDialogs.dismissAll();
        saveVisibleScrollPosition();
        searchHandler.removeCallbacks(searchRunnable);
        RuntimeConfigView.stop(overlayView.findViewById(R.id.mod_config_content));

        InbuiltOverlayManager overlayManager = InbuiltOverlayManager.getInstance();
        if (overlayManager != null) {
            overlayManager.setHudEditorMode(false);
            overlayManager.setHudEditorSelectionListener(null);
        }
        
        Runnable performHide = () -> {
            handler.post(() -> {
                try {
                    if (wmParams != null && windowManager != null) {
                        windowManager.removeView(overlayView);
                    } else {
                        ViewGroup rootView = activity.findViewById(android.R.id.content);
                        if (rootView != null) {
                            rootView.removeView(overlayView);
                        }
                    }
                } catch (Exception ignored) {}
                overlayView = null;
                isShowing = false;
                hiding = false;
            });
        };
        
        View menuContainer = overlayView.findViewById(R.id.mod_menu_container);
        if (menuContainer != null && menuContainer.getVisibility() == View.VISIBLE) {
            animateMenuExit(menuContainer, performHide);
        } else {
            performHide.run();
        }
        overlayView.animate().alpha(0f).setDuration(180).start();
    }
    
    public boolean isShowing() {
        return isShowing;
    }

    private void updatePageTitle(int title, boolean search) {
        if (overlayView == null) return;
        TextView label = overlayView.findViewById(R.id.menu_page_title);
        label.setVisibility(title == 0 ? View.GONE : View.VISIBLE);
        if (title != 0) label.setText(title);
        overlayView.findViewById(R.id.menu_header_space).setVisibility(View.GONE);
    }

    private void applyAppearance() {
        if (overlayView == null) return;
        androidx.cardview.widget.CardView card = (androidx.cardview.widget.CardView) menuContainer;
        card.setCardBackgroundColor(Color.TRANSPARENT);
        card.setRadius(dp(theme.radius));
        card.setCardElevation(dp(10));
        card.setClipToOutline(true);
        ModMenuSurface surface = overlayView.findViewById(R.id.mod_menu_surface);
        surface.setRadius(dp(theme.radius));
        applyMenuOpacity();
        overlayView.setBackgroundColor(Color.argb(Math.round(preferences.dim() * 2.55f), 0, 0, 0));
        ((TextView) overlayView.findViewById(R.id.menu_page_title)).setTextColor(theme.text);
        ((TextView) overlayView.findViewById(R.id.config_title)).setTextColor(theme.text);
        searchInput.setTextColor(theme.text);
        searchInput.setHintTextColor(theme.secondary);
        overlayView.findViewById(R.id.search_container).setBackground(theme.shape(theme.raised, 6, true));
        for (int id : new int[]{R.id.btn_close_menu, R.id.btn_clear_search, R.id.btn_back_to_modules,
                R.id.menu_resize_handle, R.id.btn_menu_search, R.id.btn_menu_compact}) {
            ImageButton button = overlayView.findViewById(id);
            button.setBackgroundTintList(null);
            button.setImageTintList(ColorStateList.valueOf(theme.secondary));
            button.setBackground(theme.touch(Color.TRANSPARENT, 5, false));
        }
        ViewGroup search = overlayView.findViewById(R.id.search_container);
        ((android.widget.ImageView) search.getChildAt(0)).setImageTintList(ColorStateList.valueOf(theme.secondary));
        android.widget.ImageView logo = (android.widget.ImageView) modMenuLogo;
        logo.setImageTintList(ColorStateList.valueOf(theme.text));
        logo.setBackground(theme.shape(theme.buttonColor, 7, false));
        ViewGroup brand = overlayView.findViewById(R.id.menu_brand_labels);
        ((TextView) brand.getChildAt(0)).setTextColor(theme.text);
        ((TextView) brand.getChildAt(1)).setTextColor(theme.secondary);
        ((TextView) overlayView.findViewById(R.id.menu_navigation_label)).setTextColor(theme.secondary);
        ((TextView) overlayView.findViewById(R.id.menu_sidebar_footer)).setTextColor(theme.secondary);
        overlayView.findViewById(R.id.menu_sidebar_border).setBackgroundColor(theme.outline);
        overlayView.findViewById(R.id.menu_header_border).setBackgroundColor(theme.outline);
        emptyStateText.setTextColor(theme.secondary);
        ((android.widget.ImageView) ((ViewGroup) emptyState).getChildAt(0)).setImageTintList(ColorStateList.valueOf(theme.secondary));
        theme.button(overlayView.findViewById(R.id.menu_clear_filters), false);
        compactFilterSelector.setBackground(theme.touch(theme.buttonColor, 6, true));
        compactFilterSelector.setCompoundDrawableTintList(ColorStateList.valueOf(theme.secondary));
        moduleCountText.setTextColor(theme.secondary);
        compactModuleCount.setTextColor(theme.secondary);
        updateFilterButtons();
        ViewGroup hud = overlayView.findViewById(R.id.hud_editor_tools);
        ((androidx.cardview.widget.CardView) hud).setCardBackgroundColor(theme.raised);
        theme.controls(hud);
        View handle = overlayView.findViewById(R.id.hud_editor_drag_handle);
        handle.setBackground(theme.touch(theme.selected, 6, false));
        ((android.widget.ImageView) ((ViewGroup) handle).getChildAt(0)).setImageTintList(ColorStateList.valueOf(theme.secondary));
        for (int id : new int[]{R.id.btn_hud_save, R.id.btn_hud_reset, R.id.btn_hud_cancel}) {
            ImageButton button = overlayView.findViewById(id);
            button.setBackgroundTintList(null);
            button.setImageTintList(ColorStateList.valueOf(theme.text));
            button.setBackground(theme.touch(Color.TRANSPARENT, 6, false));
        }
        if (adapter != null) adapter.refreshTheme();
    }

    private void applyLiveAppearance(String key) {
        if (overlayView == null || hiding) return;
        if ("text_scale".equals(key)) {
            int scale = preferences.textScale();
            scaleText(overlayView, scale / (float) appliedTextScale);
            appliedTextScale = scale;
            menuContext = ModMenuTheme.wrap(activity);
        } else if ("radius".equals(key)) {
            theme = new ModMenuTheme(menuContext);
            ((androidx.cardview.widget.CardView) menuContainer).setRadius(dp(theme.radius));
            ((ModMenuSurface) overlayView.findViewById(R.id.mod_menu_surface)).setRadius(dp(theme.radius));
            applyMenuOpacity();
        } else if ("element_radius".equals(key)) {
            theme = new ModMenuTheme(menuContext);
            roundElements(overlayView, dp(theme.corner));
        } else if ("width".equals(key) || "list_width".equals(key) || "height".equals(key)) {
            updateMenuGeometry();
        } else if ("dim".equals(key)) {
            overlayView.setBackgroundColor(Color.argb(Math.round(preferences.dim() * 2.55f), 0, 0, 0));
        } else if ("button_size".equals(key)) {
            if (callback != null) callback.onButtonOpacityChanged(InbuiltModManager.getInstance(activity).getModMenuButtonOpacity());
        } else if (key.startsWith("notification_")) {
            notificationManager.refreshAppearance();
        }
    }

    private void scaleText(View view, float ratio) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * ratio);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scaleText(group.getChildAt(i), ratio);
        }
    }

    private void roundElements(View view, int radius) {
        if (view.getId() != R.id.mod_menu_surface && view != menuContainer) ModMenuTheme.round(view.getBackground(), radius);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) roundElements(group.getChildAt(i), radius);
        }
    }

    private void rebuildAppearance() {
        if (overlayView == null || rebuilding || hiding) return;
        rebuilding = true;
        View old = overlayView;
        old.post(() -> {
            rebuilding = false;
            if (old != overlayView || hiding || activity.isDestroyed() || activity.isFinishing()) return;
            saveVisibleScrollPosition();
            ModMenuDialogs.dismissAll();
            RuntimeConfigView.stop(old.findViewById(R.id.mod_config_content));
            searchHandler.removeCallbacks(searchRunnable);
            ViewGroup root = (ViewGroup) old;
            ViewGroup content = (ViewGroup) LayoutInflater.from(menuContext = ModMenuTheme.wrap(activity)).inflate(R.layout.overlay_mod_menu, null);
            root.removeAllViews();
            while (content.getChildCount() > 0) {
                View child = content.getChildAt(0);
                content.removeViewAt(0);
                root.addView(child);
            }
            setupViews();
            loadMods();
            restoreModsScrollPosition();
            restoreLastSection();
            updateMenuGeometry();
            if (callback != null) callback.onButtonOpacityChanged(InbuiltModManager.getInstance(activity).getModMenuButtonOpacity());
        });
    }

    private void updateMenuGeometry() {
        if (overlayView == null || menuContainer == null || activeSection == MenuSection.HUD_EDITOR) return;
        int width = overlayView.getWidth();
        int height = overlayView.getHeight();
        if (width <= 0 || height <= 0) return;
        int left = dp(8), top = dp(8), right = dp(8), bottom = dp(8);
        WindowInsets insets = overlayView.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
            left = Math.max(left, insets.getSystemWindowInsetLeft());
            right = Math.max(right, insets.getSystemWindowInsetRight());
            bottom = Math.max(bottom, insets.getSystemWindowInsetBottom());
            if (Build.VERSION.SDK_INT >= 30) bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
        }
        safeArea.set(left, top, right, bottom);
        availableWidth = Math.max(1, width - left - right);
        availableHeight = Math.max(1, height - top - bottom);
        ModMenuGeometry geometry = ModMenuGeometry.fit(availableWidth, availableHeight,
                activity.getResources().getDisplayMetrics().density, preferences.width(compactMode), preferences.height(),
                preferences.positionX(), preferences.positionY());
        applyGeometry(geometry.width, geometry.height, geometry.x + left, geometry.y + top);
    }

    private void applyGeometry(int width, int height, int x, int y) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) menuContainer.getLayoutParams();
        if (params.width != width || params.height != height || params.leftMargin != x || params.topMargin != y
                || params.gravity != (Gravity.TOP | Gravity.LEFT)) {
            params.width = width; params.height = height; params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setMargins(x, y, 0, 0);
            menuContainer.setLayoutParams(params);
        }
        boolean collapsed = compactMode || width < dp(560);
        int sidebarWidth = collapsed ? 48 : width < dp(680) ? 140 : 152;
        ViewGroup.LayoutParams sidebarParams = modMenuSidebar.getLayoutParams();
        boolean navigationChanged = sidebarCollapsed != collapsed || sidebarParams.width != dp(sidebarWidth);
        sidebarCollapsed = collapsed;
        if (sidebarParams.width != dp(sidebarWidth)) {
            sidebarParams.width = dp(sidebarWidth);
            modMenuSidebar.setLayoutParams(sidebarParams);
        }
        int padding = dp(sidebarCollapsed ? 4 : 8);
        if (modMenuSidebar.getPaddingLeft() != padding || modMenuSidebar.getPaddingRight() != padding) {
            modMenuSidebar.setPadding(padding, 0, padding, 0);
        }
        if (navigationChanged || overlayView.findViewById(R.id.menu_brand_labels).getVisibility() != (collapsed ? View.GONE : View.VISIBLE)) {
            ((android.widget.LinearLayout) overlayView.findViewById(R.id.menu_brand)).setGravity(
                    sidebarCollapsed ? Gravity.CENTER : Gravity.CENTER_VERTICAL | Gravity.START);
            overlayView.findViewById(R.id.menu_brand_labels).setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            overlayView.findViewById(R.id.menu_navigation_label).setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            overlayView.findViewById(R.id.menu_sidebar_footer).setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            overlayView.findViewById(R.id.search_container).setVisibility(sidebarCollapsed ? View.GONE : View.VISIBLE);
            overlayView.findViewById(R.id.btn_menu_search).setVisibility(sidebarCollapsed ? View.VISIBLE : View.GONE);
            setNavigationMode(navModules, compactNavModules, R.string.mod_menu_modules, compactMode);
            setNavigationMode(navHudEditor, compactNavHudEditor, R.string.mod_menu_hud_editor, compactMode);
            setNavigationMode(navSettings, compactNavSettings, R.string.mm_customize, compactMode);
        }
        if (modsLayoutManager != null) {
            int columns = ModMenuGeometry.columns(Math.max(1, width - dp(sidebarWidth + 24)),
                    activity.getResources().getDisplayMetrics().density, compactMode);
            if (modsLayoutManager.getSpanCount() != columns) modsLayoutManager.setSpanCount(columns);
        }
    }

    private void setupWindowGestures() {
        setupWindowGesture(overlayView.findViewById(R.id.menu_page_title), false);
        setupWindowGesture(overlayView.findViewById(R.id.menu_resize_handle), true);
        setupWindowGesture(modMenuLogo, false);
    }

    private void setupWindowGesture(View handle, boolean resize) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY, startWidth, startHeight;
            int pointerId = -1;
            boolean dragged;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (menuContainer == null || activeSection == MenuSection.HUD_EDITOR) return false;
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) menuContainer.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pointerId = event.getPointerId(0);
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = params.leftMargin;
                        startY = params.topMargin;
                        startWidth = params.width;
                        startHeight = params.height;
                        dragged = false;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (pointerId < 0 || event.getPointerId(0) != pointerId) return true;
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        if (Math.abs(dx) + Math.abs(dy) > ViewConfiguration.get(activity).getScaledTouchSlop()) dragged = true;
                        if (!dragged) return true;
                        if (resize) {
                            int width = ModMenuGeometry.clamp(startWidth + dx, Math.min(Math.max(dp(320), Math.round(availableWidth * 0.35f)), availableWidth), availableWidth);
                            int height = ModMenuGeometry.clamp(startHeight + dy, Math.min(Math.max(dp(210), Math.round(availableHeight * 0.55f)), availableHeight), availableHeight);
                            int x = ModMenuGeometry.clamp(startX, safeArea.left, safeArea.left + availableWidth - width);
                            int y = ModMenuGeometry.clamp(startY, safeArea.top, safeArea.top + availableHeight - height);
                            applyGeometry(width, height, x, y);
                        } else {
                            applyGeometry(startWidth, startHeight,
                                    ModMenuGeometry.clamp(startX + dx, safeArea.left, safeArea.left + availableWidth - startWidth),
                                    ModMenuGeometry.clamp(startY + dy, safeArea.top, safeArea.top + availableHeight - startHeight));
                        }
                        return true;
                    case MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) != pointerId) return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pointerId = -1;
                        if (dragged) {
                            preferences.saveGeometry(Math.round(params.width * 100f / Math.max(1, availableWidth)),
                                    Math.round(params.height * 100f / Math.max(1, availableHeight)),
                                    ModMenuGeometry.fraction(params.leftMargin - safeArea.left, availableWidth - params.width),
                                    ModMenuGeometry.fraction(params.topMargin - safeArea.top, availableHeight - params.height), compactMode);
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                        dragged = false;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default: return true;
                }
            }
        });
    }

    private static class GroupedMods {
        private final List<UnifiedMod> favorites = new ArrayList<>();
        private final List<UnifiedMod> others = new ArrayList<>();

        void add(UnifiedMod mod, boolean favorite) {
            if (favorite) {
                favorites.add(mod);
            } else {
                others.add(mod);
            }
        }

        void appendTo(List<UnifiedMod> target) {
            sort(favorites);
            sort(others);
            target.addAll(favorites);
            target.addAll(others);
        }

        private void sort(List<UnifiedMod> mods) {
            mods.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        }
    }
}
