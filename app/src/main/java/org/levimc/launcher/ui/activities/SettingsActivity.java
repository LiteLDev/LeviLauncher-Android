package org.levimc.launcher.ui.activities;

import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.crash.CrashReporter;
import org.levimc.launcher.core.discord.DiscordPresence;
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.LogcatOverlayManager;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;
import org.levimc.launcher.ui.packages.SettingsCard;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.LauncherBackgroundController;
import org.levimc.launcher.util.PermissionsHandler;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.ThemeManager;
import org.levimc.launcher.util.ThemePalette;
import org.levimc.launcher.util.UiSettings;
import org.levimc.launcher.util.WallpaperFolder;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends BaseActivity {

    /** Optional search text: the page opens on the first setting that matches it. */
    public static final String EXTRA_SEARCH = "org.levimc.launcher.settings.SEARCH";

    private static final String PAGE_PREFS = "settings_page_prefs";
    private static final String KEY_SELECTED_TAB = "selected_tab_index";
    private static final String KEY_PERSONALIZE_SECTION = "personalize_section_index";
    private static final String KEY_WAS_DARK = "settings_was_dark";
    private static final String KEY_EDIT_DARK = "edit_dark";
    private static final String KEY_MATERIAL_DARK = "material_dark";
    private static final String ROW_TAG = "settings_row";

    private static final int TAB_PERSONALIZE = 1;
    private static final int SECTION_WALLPAPER = 2;

    // Order of the Windows "外观模式" tabs.
    private static final int[] THEME_MODES = {ThemeManager.MODE_LIGHT, ThemeManager.MODE_DARK,
            ThemeManager.MODE_SCHEDULE, ThemeManager.MODE_SUN, ThemeManager.MODE_FOLLOW_SYSTEM};
    private static final String[] BASE_MODES = {UiSettings.BASE_NONE, UiSettings.BASE_THEME, UiSettings.BASE_COLOR};
    private static final String[] MATERIAL_PRESETS = {UiSettings.MATERIAL_BALANCED, UiSettings.MATERIAL_CLEAR,
            UiSettings.MATERIAL_SOLID};
    private static final int[] FIT_LABELS = {R.string.settings_ui_fit_smart, R.string.settings_ui_fit_center,
            R.string.settings_ui_fit_fit, R.string.settings_ui_fit_stretch, R.string.settings_ui_fit_tile,
            R.string.settings_ui_fit_top_left, R.string.settings_ui_fit_top_right};

    private static final int EASTER_EGG_TAP_COUNT = 3;
    private static final long TAP_TIMEOUT_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private PermissionsHandler permissionsHandler;
    private ActivityResultLauncher<Intent> permissionResultLauncher;
    private ActivityResultLauncher<Intent> bgImagePickerLauncher;
    private ActivityResultLauncher<Uri> bgFolderPickerLauncher;
    private int updateButtonTapCount = 0;
    private long lastUpdateButtonTapTime = 0;

    private PersonalizationManager personalizationManager;
    private ThemeManager themeManager;
    private SharedPreferences pagePrefs;

    private ScrollView scrollView;
    private LinearLayout scrollContent;
    private SegmentedControl settingsTabs;
    private boolean twoPane;
    private View[] sections;
    private MaterialButton[] personalizeNavs;
    private View[] personalizeGroups;
    private int selectedTabIndex = 0;
    private int personalizeSectionIndex = 0;

    private EditText searchInput;
    private TextView searchEmpty;
    private final Runnable searchRunnable = this::runSearch;
    private View highlightedRow;
    private Drawable highlightedRowBackground;

    // Theme and colors
    private SegmentedControl themeModeTabs;
    private boolean editDark;
    private LinearLayout presetGrid;
    private LinearLayout moreGrid;

    // Wallpaper
    private ImageView bgImagePreview;
    private TextView bgFolderName;
    private TextView bgImageStatus;
    private TextView bgImageCount;
    private TextView bgImageBlurValue;
    private TextView bgImageBrightnessValue;
    private TextView bgImageOpacityValue;
    private int folderImageCount = -1;
    private int thumbnailRequest;

    // Material
    private boolean materialDark;
    private boolean bindingMaterial;

    private TextView preloaderSigsLastUpdateText;
    private MaterialButton preloaderSigsUpdateButton;
    private SettingsCard pkgSettingsSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        twoPane = UiSettings.useTwoPane(this);
        setContentView(twoPane ? R.layout.activity_settings : R.layout.activity_settings_stacked);

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
        setActiveNavTab(R.id.nav_tab_settings);

        personalizationManager = new PersonalizationManager(this);
        themeManager = new ThemeManager(this);
        pagePrefs = getSharedPreferences(PAGE_PREFS, MODE_PRIVATE);

        // Windows remembers the last settings tab and appearance section.
        selectedTabIndex = pagePrefs.getInt(KEY_SELECTED_TAB, 0);
        personalizeSectionIndex = pagePrefs.getInt(KEY_PERSONALIZE_SECTION, 0);
        boolean dark = isDark();
        editDark = dark;
        materialDark = dark;
        if (savedInstanceState != null) {
            selectedTabIndex = savedInstanceState.getInt(KEY_SELECTED_TAB, selectedTabIndex);
            personalizeSectionIndex = savedInstanceState.getInt(KEY_PERSONALIZE_SECTION, personalizeSectionIndex);
            // Keep the light/dark editing tabs across a recreate, but start from the applied mode
            // again when the appearance mode itself changed (as Windows does).
            if (savedInstanceState.getBoolean(KEY_WAS_DARK, dark) == dark) {
                editDark = savedInstanceState.getBoolean(KEY_EDIT_DARK, dark);
                materialDark = savedInstanceState.getBoolean(KEY_MATERIAL_DARK, dark);
            }
        }

        permissionsHandler = PermissionsHandler.getInstance();
        permissionResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (permissionsHandler != null) {
                        permissionsHandler.onActivityResult(result.getResultCode(), result.getData());
                    }
                }
        );
        permissionsHandler.setActivity(this, permissionResultLauncher);

        bgImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) importSingleImage(uri);
                    }
                }
        );
        bgFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

        initTabs();
        setupSearch();
        setupBasicSection();
        pkgSettingsSection = new SettingsCard(this, findViewById(R.id.pkg_settings_card));
        setupThemeSection();
        setupLayoutSection();
        setupWallpaperSection();
        setupMaterialSection();
        setupUpdatesSection();
        setupAboutSection();

        selectTab(selectedTabIndex, false);
        selectPersonalizeSection(personalizeSectionIndex);

        String query = getIntent() != null ? getIntent().getStringExtra(EXTRA_SEARCH) : null;
        if (savedInstanceState == null && query != null && !query.trim().isEmpty()) {
            searchInput.setText(query);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTabIndex);
        outState.putInt(KEY_PERSONALIZE_SECTION, personalizeSectionIndex);
        outState.putBoolean(KEY_EDIT_DARK, editDark);
        outState.putBoolean(KEY_MATERIAL_DARK, materialDark);
        outState.putBoolean(KEY_WAS_DARK, isDark());
    }

    private boolean isDark() {
        return personalizationManager.isDarkMode(this);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // #region Tabs

    private void initTabs() {
        scrollView = findViewById(R.id.settings_scroll);
        scrollContent = findViewById(R.id.settings_scroll_content);
        settingsTabs = findViewById(R.id.settings_tabs);
        if (twoPane) settingsTabs.setVertical(true);
        settingsTabs.setItems(
                getString(R.string.settings_tab_basic),
                getString(R.string.settings_tab_personalize),
                getString(R.string.settings_tab_components),
                getString(R.string.settings_tab_others),
                getString(R.string.settings_tab_privacy),
                getString(R.string.settings_ui_tab_updates),
                getString(R.string.settings_tab_about));
        settingsTabs.setOnSegmentSelectedListener(index -> {
            selectTab(index, true);
            // The right pane keeps its scroll position across sections; start each one at the top.
            if (twoPane) scrollView.scrollTo(0, 0);
        });

        sections = new View[]{
                findViewById(R.id.section_basic),
                findViewById(R.id.section_personalize),
                findViewById(R.id.section_components),
                findViewById(R.id.section_others),
                findViewById(R.id.section_privacy),
                findViewById(R.id.section_updates),
                findViewById(R.id.section_about)};

        personalizeNavs = new MaterialButton[]{
                findViewById(R.id.personalize_nav_theme),
                findViewById(R.id.personalize_nav_layout),
                findViewById(R.id.personalize_nav_wallpaper),
                findViewById(R.id.personalize_nav_material)};
        personalizeGroups = new View[]{
                findViewById(R.id.personalize_theme_group),
                findViewById(R.id.personalize_layout_group),
                findViewById(R.id.personalize_wallpaper_group),
                findViewById(R.id.personalize_material_group)};
        for (int i = 0; i < personalizeNavs.length; i++) {
            final int index = i;
            personalizeNavs[i].setOnClickListener(v -> selectPersonalizeSection(index));
        }
    }

    private void selectTab(int index, boolean animate) {
        if (index < 0 || index >= sections.length) index = 0;
        selectedTabIndex = index;
        pagePrefs.edit().putInt(KEY_SELECTED_TAB, index).apply();
        settingsTabs.setSelectedIndex(index, false);
        for (int i = 0; i < sections.length; i++) {
            View section = sections[i];
            if (i == index) {
                section.setVisibility(View.VISIBLE);
                if (animate && !UiSettings.animationsDisabled()) {
                    section.setAlpha(0f);
                    section.animate().alpha(1f).setDuration(200).start();
                } else {
                    section.setAlpha(1f);
                }
            } else {
                section.setVisibility(View.GONE);
            }
        }
    }

    /** Personalization sub-sections, a button row as on Windows: selected = primary, others secondary. */
    private void selectPersonalizeSection(int index) {
        if (index < 0 || index >= personalizeGroups.length) index = 0;
        personalizeSectionIndex = index;
        pagePrefs.edit().putInt(KEY_PERSONALIZE_SECTION, index).apply();
        ColorStateList secondaryFill = ColorStateList.valueOf(getColor(R.color.ui_default));
        ColorStateList secondaryText = ColorStateList.valueOf(getColor(R.color.text_primary));
        for (int i = 0; i < personalizeNavs.length; i++) {
            MaterialButton nav = personalizeNavs[i];
            if (i == index) {
                UiKit.applyPrimary(nav);
            } else {
                nav.setBackgroundTintList(secondaryFill);
                nav.setTextColor(secondaryText);
            }
            personalizeGroups[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (index == SECTION_WALLPAPER) updateBgImageUI();
    }

    // #endregion

    // #region Search

    private void setupSearch() {
        searchInput = findViewById(R.id.settings_search);
        searchEmpty = findViewById(R.id.settings_search_empty);
        // A restored query would jump around again after recreate().
        searchInput.setSaveEnabled(false);
        searchInput.addTextChangedListener(SimpleTextWatcher.after(s -> {
            handler.removeCallbacks(searchRunnable);
            handler.postDelayed(searchRunnable, 300);
        }));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                handler.removeCallbacks(searchRunnable);
                runSearch();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });
    }

    /** Opens the tab (and appearance section) of the first setting that matches, and scrolls to it. */
    private void runSearch() {
        String query = searchInput.getText().toString().trim().toLowerCase(Locale.getDefault());
        searchEmpty.setVisibility(View.GONE);
        if (query.isEmpty()) return;

        for (int tab = 0; tab < sections.length; tab++) {
            List<View> rows = new ArrayList<>();
            collectRows(sections[tab], rows);
            for (View row : rows) {
                if (!isRowAvailable(row, sections[tab])) continue;
                if (textOf(row).contains(query)) {
                    jumpTo(tab, subSectionOf(row), row);
                    return;
                }
            }
            if (tab == TAB_PERSONALIZE) {
                for (int i = 0; i < personalizeNavs.length; i++) {
                    if (personalizeNavs[i].getText().toString().toLowerCase(Locale.getDefault()).contains(query)) {
                        jumpTo(tab, i, null);
                        return;
                    }
                }
            }
            if (settingsTabs.getSegment(tab).getText().toString().toLowerCase(Locale.getDefault()).contains(query)) {
                jumpTo(tab, -1, null);
                return;
            }
        }
        searchEmpty.setVisibility(View.VISIBLE);
    }

    private void collectRows(View view, List<View> out) {
        if (ROW_TAG.equals(view.getTag())) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectRows(group.getChildAt(i), out);
        }
    }

    /** False when the row is hidden by its own state (e.g. wallpaper sliders without a wallpaper). */
    private boolean isRowAvailable(View row, View section) {
        View v = row;
        while (v != null && v != section) {
            boolean switchable = false;
            for (View group : personalizeGroups) if (group == v) switchable = true;
            if (!switchable && v.getVisibility() == View.GONE) return false;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return true;
    }

    private int subSectionOf(View row) {
        View v = row;
        while (v != null) {
            for (int i = 0; i < personalizeGroups.length; i++) if (personalizeGroups[i] == v) return i;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return -1;
    }

    private String textOf(View view) {
        StringBuilder sb = new StringBuilder();
        appendText(view, sb);
        return sb.toString().toLowerCase(Locale.getDefault());
    }

    private void appendText(View view, StringBuilder sb) {
        if (view instanceof EditText) return;
        if (view instanceof TextView) {
            sb.append(((TextView) view).getText()).append(' ');
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) appendText(group.getChildAt(i), sb);
        }
    }

    private void jumpTo(int tab, int subSection, View row) {
        if (tab != selectedTabIndex) selectTab(tab, false);
        if (subSection >= 0 && subSection != personalizeSectionIndex) selectPersonalizeSection(subSection);
        scrollView.post(() -> {
            if (row == null) {
                scrollView.smoothScrollTo(0, 0);
                return;
            }
            Rect rect = new Rect();
            row.getDrawingRect(rect);
            scrollContent.offsetDescendantRectToMyCoords(row, rect);
            scrollView.smoothScrollTo(0, Math.max(0, rect.top - dp(16)));
            highlightRow(row);
        });
    }

    private void highlightRow(View row) {
        clearHighlight();
        highlightedRow = row;
        highlightedRowBackground = row.getBackground();
        row.setBackground(UiKit.selectedRing(this, 12));
        handler.postDelayed(this::clearHighlight, 1600);
    }

    private void clearHighlight() {
        if (highlightedRow != null) {
            highlightedRow.setBackground(highlightedRowBackground);
            highlightedRow = null;
            highlightedRowBackground = null;
        }
    }

    // #endregion

    // #region Basic

    private void setupBasicSection() {
        LanguageManager languageManager = new LanguageManager(this);
        FeatureSettings fs = FeatureSettings.getInstance();

        // Keep English at the top (default language).
        // Sort all other languages alphabetically by their display name.
        String[] languageOptions = {
                getString(R.string.english),
                getString(R.string.chinese),
                getString(R.string.french),
                getString(R.string.hindi),
                getString(R.string.indonesian),
                getString(R.string.japanese),
                getString(R.string.portuguese),
                getString(R.string.russian),
                getString(R.string.spanish),
                getString(R.string.turkish),
                getString(R.string.vietnamese)
        };
        String[] languageCodes = {"en", "zh-CN", "fr", "hi", "id", "ja", "pt", "ru", "es", "tr", "vi"};

        String currentCode = Locale.forLanguageTag(languageManager.getCurrentLanguage()).getLanguage();
        int defaultIdx = switch (currentCode) {
            case "zh" -> 1;
            case "fr" -> 2;
            case "hi" -> 3;
            case "id", "in" -> 4;
            case "ja" -> 5;
            case "pt" -> 6;
            case "ru" -> 7;
            case "es" -> 8;
            case "tr" -> 9;
            case "vi" -> 10;
            default -> 0;
        };

        TextView languageCurrent = findViewById(R.id.language_current);
        languageCurrent.setText(languageOptions[defaultIdx]);
        // Windows: the row shows the current language and "Change" opens the choice.
        findViewById(R.id.language_change).setOnClickListener(v -> new CustomAlertDialog(this)
                .setTitleText(getString(R.string.language))
                .setItems(languageOptions, (dialog, which) -> {
                    String code = languageCodes[which];
                    languageCurrent.setText(languageOptions[which]);
                    if (!code.equals(languageManager.getCurrentLanguage())) {
                        languageManager.setAppLanguage(code);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show());

        SwitchMaterial switchLogcat = findViewById(R.id.switch_logcat);
        switchLogcat.setChecked(fs.isLogcatOverlayEnabled());
        switchLogcat.setOnCheckedChangeListener((btn, checked) -> {
            fs.setLogcatOverlayEnabled(checked);
            try {
                LogcatOverlayManager mgr = LogcatOverlayManager.getInstance();
                if (mgr != null) mgr.refreshVisibility();
            } catch (Throwable ignored) {}
        });

        SwitchMaterial switchCrashUpload = findViewById(R.id.switch_crash_upload);
        switchCrashUpload.setChecked(fs.isCrashUploadEnabled());
        switchCrashUpload.setOnCheckedChangeListener((btn, checked) -> {
            fs.setCrashUploadEnabled(checked);
            CrashReporter.refreshCrashlyticsCollection(this);
        });

        SwitchMaterial switchDiscordPresence = findViewById(R.id.switch_discord_presence);
        switchDiscordPresence.setChecked(fs.isDiscordRichPresenceEnabled());
        switchDiscordPresence.setEnabled(DiscordPresence.isConfigured());
        switchDiscordPresence.setOnCheckedChangeListener((btn, checked) -> {
            fs.setDiscordRichPresenceEnabled(checked);
            DiscordPresence.refresh();
        });

        SwitchMaterial switchManagedLogin = findViewById(R.id.switch_managed_login);
        switchManagedLogin.setChecked(fs.isLauncherManagedMcLoginEnabled());
        switchManagedLogin.setOnCheckedChangeListener((btn, checked) -> fs.setLauncherManagedMcLoginEnabled(checked));
    }

    // #endregion

    // #region Theme and colors

    private void setupThemeSection() {
        themeModeTabs = findViewById(R.id.theme_mode_tabs);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_light), R.drawable.ic_sun);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_dark), R.drawable.ic_moon);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_schedule), R.drawable.ic_ui_schedule);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_sun), R.drawable.ic_ui_sunrise);
        themeModeTabs.addSegment(getString(R.string.settings_ui_mode_system), R.drawable.ic_ui_monitor);
        if (twoPane) {
            // Five equal segments do not fit the narrow right pane: compact segments sized to their labels.
            themeModeTabs.setEqualWidth(false);
            themeModeTabs.setTextSizeSp(13f);
            int pad = Math.round(10 * getResources().getDisplayMetrics().density);
            for (int i = 0; i < themeModeTabs.getSegmentCount(); i++) {
                themeModeTabs.getSegment(i).setPadding(pad, 0, pad, 0);
            }
        }
        int mode = themeManager.getCurrentMode();
        themeModeTabs.setSelectedIndex(themeModeIndex(mode), false);
        themeModeTabs.setOnSegmentSelectedListener(index -> {
            updateThemeModePanel(THEME_MODES[index]);
            themeManager.setThemeMode(THEME_MODES[index]);
        });
        updateThemeModePanel(mode);

        findViewById(R.id.theme_schedule_start).setOnClickListener(v -> pickScheduleTime(true));
        findViewById(R.id.theme_schedule_end).setOnClickListener(v -> pickScheduleTime(false));
        findViewById(R.id.theme_sun_refresh).setOnClickListener(v -> {
            updateThemeModePanel(themeManager.getCurrentMode());
            if (themeManager.refreshScheduledMode()) recreate();
        });
        UiKit.tintIconChip(findViewById(R.id.theme_sunrise_chip), findViewById(R.id.theme_sunrise_icon), UiKit.AMBER);
        UiKit.tintIconChip(findViewById(R.id.theme_sunset_chip), findViewById(R.id.theme_sunset_icon), UiKit.INDIGO);

        View bar = findViewById(R.id.mode_config_bar);
        bar.setBackground(roundRect(UiKit.accent(this), 2));

        SegmentedControl editTabs = findViewById(R.id.edit_mode_tabs);
        editTabs.setEqualWidth(false);
        editTabs.setItems(getString(R.string.settings_ui_mode_light), getString(R.string.settings_ui_mode_dark));
        editTabs.setSelectedIndex(editDark ? 1 : 0, false);
        editTabs.setOnSegmentSelectedListener(index -> {
            editDark = index == 1;
            refreshEditMode();
        });

        presetGrid = findViewById(R.id.color_preset_grid);
        moreGrid = findViewById(R.id.color_more_grid);
        View.OnLayoutChangeListener widthListener = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(this::rebuildSwatches);
        };
        presetGrid.addOnLayoutChangeListener(widthListener);
        moreGrid.addOnLayoutChangeListener(widthListener);

        SegmentedControl baseTabs = findViewById(R.id.base_mode_tabs);
        baseTabs.setItems(getString(R.string.settings_ui_base_none), getString(R.string.settings_ui_base_theme),
                getString(R.string.settings_ui_base_color));
        baseTabs.setOnSegmentSelectedListener(index -> {
            UiSettings.setBase(this, editDark, BASE_MODES[index], UiSettings.getBaseColor(this, editDark),
                    UiSettings.getBaseOpacity(this, editDark));
            onBaseChanged();
        });
        EditText baseInput = findViewById(R.id.base_color_input);
        findViewById(R.id.base_color_apply).setOnClickListener(v -> {
            Integer color = parseColorInput(baseInput.getText().toString());
            if (color == null) {
                Toast.makeText(this, R.string.invalid_color_format, Toast.LENGTH_SHORT).show();
                return;
            }
            UiSettings.setBase(this, editDark, UiSettings.BASE_COLOR, color, UiSettings.getBaseOpacity(this, editDark));
            baseInput.clearFocus();
            onBaseChanged();
        });
        SeekBar baseOpacity = findViewById(R.id.base_opacity_seek);
        baseOpacity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setValueText(R.id.base_opacity_value, R.string.settings_ui_value_percent, progress);
                if (!fromUser) return;
                UiSettings.setBase(SettingsActivity.this, editDark, UiSettings.getBaseMode(SettingsActivity.this, editDark),
                        UiSettings.getBaseColor(SettingsActivity.this, editDark), progress);
                if (editDark == isDark()) personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
            }
        });

        refreshEditMode();
    }

    private static int themeModeIndex(int mode) {
        for (int i = 0; i < THEME_MODES.length; i++) if (THEME_MODES[i] == mode) return i;
        return THEME_MODES.length - 1;
    }

    private void updateThemeModePanel(int mode) {
        View panel = findViewById(R.id.theme_mode_panel);
        TextView desc = findViewById(R.id.theme_mode_panel_desc);
        View schedule = findViewById(R.id.theme_schedule_box);
        View sun = findViewById(R.id.theme_sun_box);
        boolean showSchedule = mode == ThemeManager.MODE_SCHEDULE;
        boolean showSun = mode == ThemeManager.MODE_SUN;
        boolean showSystem = mode == ThemeManager.MODE_FOLLOW_SYSTEM;
        panel.setVisibility(showSchedule || showSun || showSystem ? View.VISIBLE : View.GONE);
        schedule.setVisibility(showSchedule ? View.VISIBLE : View.GONE);
        sun.setVisibility(showSun ? View.VISIBLE : View.GONE);
        if (showSchedule) {
            desc.setText(R.string.settings_ui_schedule_desc);
            ((TextView) findViewById(R.id.theme_schedule_start)).setText(formatClock(themeManager.getScheduleStart()));
            ((TextView) findViewById(R.id.theme_schedule_end)).setText(formatClock(themeManager.getScheduleEnd()));
        } else if (showSun) {
            desc.setText(R.string.settings_ui_sun_desc);
            String[] times = ThemeManager.getSunTimes();
            ((TextView) findViewById(R.id.theme_sunrise_time)).setText(formatClock(times[0]));
            ((TextView) findViewById(R.id.theme_sunset_time)).setText(formatClock(times[1]));
        } else if (showSystem) {
            desc.setText(R.string.settings_ui_mode_system_desc);
        }
    }

    private String formatClock(String hhmm) {
        int minutes = ThemeManager.parseMinutes(hhmm);
        if (minutes < 0) return hhmm;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        return android.text.format.DateFormat.getTimeFormat(this).format(c.getTime());
    }

    private void pickScheduleTime(boolean start) {
        String current = start ? themeManager.getScheduleStart() : themeManager.getScheduleEnd();
        int minutes = ThemeManager.parseMinutes(current);
        if (minutes < 0) minutes = start ? 20 * 60 : 7 * 60;
        new TimePickerDialog(this, (view, hour, minute) -> {
            String value = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
            boolean wasDark = PersonalizationManager.isDarkActive(this);
            if (start) themeManager.setSchedule(value, themeManager.getScheduleEnd());
            else themeManager.setSchedule(themeManager.getScheduleStart(), value);
            updateThemeModePanel(themeManager.getCurrentMode());
            if (wasDark != PersonalizationManager.isDarkActive(this)) recreate();
        }, minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    /** The light/dark tab of "按模式分别设置" decides which mode the color and base edits apply to. */
    private void refreshEditMode() {
        ImageView icon = findViewById(R.id.edit_mode_icon);
        icon.setImageResource(editDark ? R.drawable.ic_moon : R.drawable.ic_sun);
        icon.setImageTintList(ColorStateList.valueOf(editDark ? UiKit.INDIGO : UiKit.AMBER));
        ((TextView) findViewById(R.id.edit_mode_title))
                .setText(editDark ? R.string.settings_ui_mode_dark : R.string.settings_ui_mode_light);
        rebuildSwatches();
        refreshBaseUI();
    }

    private void rebuildSwatches() {
        if (presetGrid == null) return;
        int selected = personalizationManager.getAccentColor(editDark) | 0xFF000000;
        boolean custom = !contains(PersonalizationManager.PRESET_COLORS, selected)
                && !contains(PersonalizationManager.MORE_COLORS, selected);
        fillSwatchGrid(presetGrid, PersonalizationManager.PRESET_COLORS, false, selected, custom);
        fillSwatchGrid(moreGrid, PersonalizationManager.MORE_COLORS, true, selected, custom);
    }

    private static boolean contains(int[] colors, int color) {
        for (int c : colors) if ((c | 0xFF000000) == color) return true;
        return false;
    }

    private void fillSwatchGrid(LinearLayout grid, int[] colors, boolean withCustom, int selected, boolean custom) {
        int available = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        if (available <= 0) return;
        grid.removeAllViews();
        int perRow = Math.max(1, available / dp(40));
        int total = colors.length + (withCustom ? 1 : 0);
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(i < colors.length
                    ? makeSwatch(colors[i] | 0xFF000000, (colors[i] | 0xFF000000) == selected)
                    : makeCustomSwatch(selected, custom));
        }
    }

    /** 32dp color circle in a 40dp touch cell; the selected one gets a ring and a check (Windows swatch). */
    private View makeSwatch(int color, boolean selected) {
        FrameLayout cell = swatchCell(color, selected);
        if (selected) cell.addView(swatchIcon(R.drawable.ic_check, ThemePalette.onAccent(color)));
        cell.setContentDescription(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        cell.setOnClickListener(v -> setAccent(color));
        DynamicAnim.applyPressScale(cell);
        return cell;
    }

    private View makeCustomSwatch(int selected, boolean custom) {
        int fill = custom ? selected : getColor(R.color.ui_default);
        FrameLayout cell = swatchCell(fill, custom);
        cell.addView(swatchIcon(R.drawable.ic_ui_palette,
                custom ? ThemePalette.onAccent(selected) : getColor(R.color.text_secondary)));
        cell.setContentDescription(getString(R.string.settings_ui_custom_color));
        cell.setOnClickListener(v -> showCustomColorDialog(custom ? selected : UiKit.accent(this)));
        DynamicAnim.applyPressScale(cell);
        return cell;
    }

    private FrameLayout swatchCell(int color, boolean selected) {
        FrameLayout cell = new FrameLayout(this);
        cell.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        if (selected) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(dp(2), color);
            cell.setBackground(ring);
        }
        View circle = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        circle.setBackground(dot);
        cell.addView(circle, new FrameLayout.LayoutParams(dp(selected ? 30 : 32), dp(selected ? 30 : 32), Gravity.CENTER));
        cell.setClickable(true);
        cell.setFocusable(true);
        return cell;
    }

    private ImageView swatchIcon(int res, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setLayoutParams(new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        return icon;
    }

    private void setAccent(int color) {
        personalizationManager.setAccentColor(editDark, color);
        // The color of the mode in use repaints the whole page; the other mode's color only updates the grid.
        if (editDark == isDark()) recreate();
        else rebuildSwatches();
    }

    private void showCustomColorDialog(int initial) {
        EditText input = new EditText(this, null, 0, R.style.Widget_Levi_Input);
        input.setHint(R.string.custom_color_hint);
        input.setText(String.format(Locale.ROOT, "#%06X", initial & 0xFFFFFF));
        input.setSingleLine(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(4));
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.settings_ui_custom_color))
                .setCustomView(box)
                .setPositiveButton(getString(R.string.apply), v -> {
                    Integer color = parseColorInput(input.getText().toString());
                    if (color == null) {
                        Toast.makeText(this, R.string.invalid_color_format, Toast.LENGTH_SHORT).show();
                    } else {
                        setAccent(color);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    /** Accepts "#RRGGBB", "RRGGBB" or "R,G,B"; null when invalid. */
    private static Integer parseColorInput(String raw) {
        String input = raw.trim();
        try {
            if (input.startsWith("#")) return Color.parseColor(input) | 0xFF000000;
            if (input.contains(",")) {
                String[] parts = input.split(",");
                if (parts.length != 3) return null;
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return null;
                return Color.rgb(r, g, b);
            }
            return Color.parseColor("#" + input) | 0xFF000000;
        } catch (Exception e) {
            return null;
        }
    }

    private void onBaseChanged() {
        refreshBaseUI();
        if (editDark == isDark()) personalizationManager.refreshBackgroundEffects(this);
    }

    private void refreshBaseUI() {
        String mode = UiSettings.getBaseMode(this, editDark);
        int color = UiSettings.getBaseColor(this, editDark);
        int opacity = UiSettings.getBaseOpacity(this, editDark);
        int index = 0;
        for (int i = 0; i < BASE_MODES.length; i++) if (BASE_MODES[i].equals(mode)) index = i;
        ((SegmentedControl) findViewById(R.id.base_mode_tabs)).setSelectedIndex(index, false);
        findViewById(R.id.base_color_box).setVisibility(UiSettings.BASE_COLOR.equals(mode) ? View.VISIBLE : View.GONE);
        findViewById(R.id.base_opacity_box).setVisibility(UiSettings.BASE_NONE.equals(mode) ? View.GONE : View.VISIBLE);

        String hex = String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
        ((TextView) findViewById(R.id.base_color_value)).setText(hex);
        GradientDrawable swatch = roundRect(color, 12);
        swatch.setStroke(dp(1), getColor(R.color.ui_border));
        findViewById(R.id.base_color_swatch).setBackground(swatch);
        EditText input = findViewById(R.id.base_color_input);
        if (!input.hasFocus()) input.setText(hex);

        SeekBar seek = findViewById(R.id.base_opacity_seek);
        seek.setProgress(opacity);
        setValueText(R.id.base_opacity_value, R.string.settings_ui_value_percent, opacity);
    }

    private GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(radiusDp));
        gd.setColor(color);
        return gd;
    }

    private void setValueText(int viewId, int formatRes, int value) {
        TextView view = findViewById(viewId);
        if (view != null) view.setText(getString(formatRes, value));
    }

    // #endregion

    // #region Layout and motion

    private void setupLayoutSection() {
        SwitchMaterial navbar = findViewById(R.id.switch_top_navbar);
        navbar.setChecked(UiSettings.useTopNavbar(this));
        navbar.setOnCheckedChangeListener((btn, checked) -> {
            UiSettings.setLayoutMode(this, checked ? UiSettings.LAYOUT_NAVBAR : UiSettings.LAYOUT_SIDEBAR);
            recreate();
        });

        SwitchMaterial twoPaneSwitch = findViewById(R.id.switch_two_pane);
        twoPaneSwitch.setChecked(twoPane);
        twoPaneSwitch.setOnCheckedChangeListener((btn, checked) -> {
            UiSettings.setTwoPane(this, checked);
            recreate();
        });

        SwitchMaterial animations = findViewById(R.id.switch_disable_animations);
        animations.setChecked(UiSettings.isAnimationsDisabledSetting(this));
        animations.setOnCheckedChangeListener((btn, checked) -> UiSettings.setAnimationsDisabled(this, checked));
    }

    // #endregion

    // #region Wallpaper

    private void setupWallpaperSection() {
        bgImagePreview = findViewById(R.id.bg_image_preview);
        bgImagePreview.setClipToOutline(true);
        bgFolderName = findViewById(R.id.bg_folder_name);
        bgImageStatus = findViewById(R.id.bg_image_status);
        bgImageCount = findViewById(R.id.bg_image_count);

        findViewById(R.id.btn_select_bg_folder).setOnClickListener(v -> {
            try {
                bgFolderPickerLauncher.launch(null);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.settings_ui_folder_failed, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_select_bg_image).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            bgImagePickerLauncher.launch(intent);
        });
        findViewById(R.id.btn_clear_bg_image).setOnClickListener(v -> {
            String folder = personalizationManager.getBackgroundFolder();
            personalizationManager.setBackgroundFolder(null);
            releaseFolderPermission(this, folder);
            personalizationManager.clearBackgroundImage();
            recreate();
        });
        findViewById(R.id.btn_next_bg_image).setOnClickListener(v -> showNextWallpaper());

        SegmentedControl order = findViewById(R.id.bg_play_order);
        order.setItems(getString(R.string.settings_ui_play_random), getString(R.string.settings_ui_play_sequential));
        order.setSelectedIndex(PersonalizationManager.ORDER_SEQUENTIAL.equals(
                personalizationManager.getBackgroundPlayOrder()) ? 1 : 0, false);
        order.setOnSegmentSelectedListener(index -> personalizationManager.setBackgroundPlayOrder(
                index == 1 ? PersonalizationManager.ORDER_SEQUENTIAL : PersonalizationManager.ORDER_RANDOM));

        TextView fit = findViewById(R.id.bg_fit_mode);
        fit.setText(fitLabel(personalizationManager.getBackgroundFitMode()));
        fit.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            for (int i = 0; i < FIT_LABELS.length; i++) menu.getMenu().add(0, i, i, FIT_LABELS[i]);
            menu.setOnMenuItemClickListener(item -> {
                String mode = PersonalizationManager.FIT_MODES[item.getItemId()];
                personalizationManager.setBackgroundFitMode(mode);
                fit.setText(fitLabel(mode));
                personalizationManager.refreshBackgroundColorEffects(this);
                return true;
            });
            menu.show();
        });

        setupBackgroundImageControls();
        updateBgImageUI();
        setBackgroundImportState(LauncherBackgroundController.isImporting());
    }

    private int fitLabel(String mode) {
        for (int i = 0; i < PersonalizationManager.FIT_MODES.length; i++) {
            if (PersonalizationManager.FIT_MODES[i].equals(mode)) return FIT_LABELS[i];
        }
        return FIT_LABELS[0];
    }

    private void setupBackgroundImageControls() {
        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        SeekBar brightnessSeek = findViewById(R.id.seek_bg_image_brightness);
        SeekBar opacitySeek = findViewById(R.id.seek_bg_image_opacity);
        bgImageBlurValue = findViewById(R.id.bg_image_blur_value);
        bgImageBrightnessValue = findViewById(R.id.bg_image_brightness_value);
        bgImageOpacityValue = findViewById(R.id.bg_image_opacity_value);

        blurSeek.setMax(PersonalizationManager.BG_BLUR_MAX);
        blurSeek.setProgress(personalizationManager.getBackgroundImageBlur());
        updateBgImageBlurValue(blurSeek.getProgress());
        blurSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBgImageBlurValue(progress);
                if (!fromUser) return;
                personalizationManager.setBackgroundImageBlur(progress);
                if (personalizationManager.supportsRealtimeBackgroundBlur()) {
                    personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
                }
                updateMaterialStatus();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
                onWallpaperEffectReleased();
            }
        });

        brightnessSeek.setMin(PersonalizationManager.BG_BRIGHTNESS_MIN);
        brightnessSeek.setMax(PersonalizationManager.BG_BRIGHTNESS_MAX);
        brightnessSeek.setProgress(personalizationManager.getBackgroundImageBrightness());
        setBgValue(bgImageBrightnessValue, R.string.settings_ui_value_percent, brightnessSeek.getProgress());
        brightnessSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setBgValue(bgImageBrightnessValue, R.string.settings_ui_value_percent, progress);
                if (!fromUser) return;
                personalizationManager.setBackgroundImageBrightness(progress);
                personalizationManager.refreshBackgroundColorEffects(SettingsActivity.this);
                updateMaterialStatus();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onWallpaperEffectReleased();
            }
        });

        opacitySeek.setMax(100);
        opacitySeek.setProgress(personalizationManager.getBackgroundOpacity());
        setBgValue(bgImageOpacityValue, R.string.settings_ui_value_percent, opacitySeek.getProgress());
        opacitySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setBgValue(bgImageOpacityValue, R.string.settings_ui_value_percent, progress);
                if (!fromUser) return;
                personalizationManager.setBackgroundOpacity(progress);
                personalizationManager.refreshBackgroundColorEffects(SettingsActivity.this);
                updateMaterialStatus();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onWallpaperEffectReleased();
            }
        });
    }

    private void updateBgImageBlurValue(int blurRadius) {
        setBgValue(bgImageBlurValue, R.string.settings_ui_value_px, blurRadius);
    }

    private void setBgValue(TextView view, int formatRes, int value) {
        if (view != null) view.setText(getString(formatRes, value));
    }

    private void updateBgImageUI() {
        if (bgImageStatus == null) return;
        boolean hasImage = personalizationManager.hasBackgroundImage();
        String folder = personalizationManager.getBackgroundFolder();

        SeekBar blurSeek = findViewById(R.id.seek_bg_image_blur);
        boolean canBlur = personalizationManager.canBlurBackground();
        blurSeek.setEnabled(canBlur);
        if (canBlur) updateBgImageBlurValue(blurSeek.getProgress());
        else bgImageBlurValue.setText(R.string.bg_media_blur_unavailable);

        findViewById(R.id.bg_image_effect_controls).setVisibility(hasImage ? View.VISIBLE : View.GONE);
        findViewById(R.id.bg_play_order_row).setVisibility(folder != null ? View.VISIBLE : View.GONE);

        if (folder != null) {
            String path = folderPath(folder);
            int slash = path.lastIndexOf('/');
            bgFolderName.setText(slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path);
            bgImageStatus.setText(path);
            bgImageStatus.setVisibility(View.VISIBLE);
            loadFolderCount(folder);
        } else {
            folderImageCount = -1;
            bgImageCount.setVisibility(View.GONE);
            bgFolderName.setText(hasImage ? R.string.settings_ui_single_image : R.string.settings_ui_no_folder);
            bgImageStatus.setText(R.string.bg_image_selected);
            bgImageStatus.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        }

        MaterialButton clear = findViewById(R.id.btn_clear_bg_image);
        clear.setVisibility(hasImage || folder != null ? View.VISIBLE : View.GONE);
        clear.setText(folder != null ? R.string.settings_ui_clear_folder : R.string.settings_ui_clear_image);
        findViewById(R.id.btn_next_bg_image).setVisibility(folder != null ? View.VISIBLE : View.GONE);
        updateNextButton();

        loadThumbnail(hasImage);
        View noImage = findViewById(R.id.material_no_image);
        if (noImage != null) {
            noImage.setVisibility(hasImage && personalizationManager.getBackgroundOpacity() > 0
                    ? View.GONE : View.VISIBLE);
        }
    }

    /** "primary:Pictures/Wallpapers" style path of a document tree. */
    private static String folderPath(String treeUri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.isEmpty() ? id : path;
        } catch (Exception e) {
            return treeUri;
        }
    }

    private void loadFolderCount(String folder) {
        Context app = getApplicationContext();
        WeakReference<SettingsActivity> ref = new WeakReference<>(this);
        new Thread(() -> {
            int count = WallpaperFolder.count(app, folder);
            handler.post(() -> {
                SettingsActivity activity = ref.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                if (!folder.equals(activity.personalizationManager.getBackgroundFolder())) return;
                activity.folderImageCount = count;
                activity.bgImageCount.setText(activity.getString(R.string.settings_ui_image_count, count));
                activity.bgImageCount.setVisibility(View.VISIBLE);
                activity.updateNextButton();
            });
        }, "WallpaperFolderCount").start();
    }

    private void loadThumbnail(boolean hasImage) {
        int request = ++thumbnailRequest;
        if (!hasImage) {
            showThumbnailPlaceholder();
            return;
        }
        PersonalizationManager pm = personalizationManager;
        int size = dp(48) * 2;
        WeakReference<SettingsActivity> ref = new WeakReference<>(this);
        new Thread(() -> {
            Bitmap full = pm.loadBackgroundBitmap();
            Bitmap thumb = null;
            if (full != null) {
                float scale = Math.min(1f, (float) size / Math.min(full.getWidth(), full.getHeight()));
                thumb = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(full.getWidth() * scale)),
                        Math.max(1, Math.round(full.getHeight() * scale)), true);
                if (thumb != full) full.recycle();
            }
            Bitmap result = thumb;
            handler.post(() -> {
                SettingsActivity activity = ref.get();
                if (activity == null || activity.isDestroyed() || request != activity.thumbnailRequest) return;
                if (result == null) {
                    activity.showThumbnailPlaceholder();
                } else {
                    activity.bgImagePreview.setImageTintList(null);
                    activity.bgImagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    activity.bgImagePreview.setImageBitmap(result);
                }
            });
        }, "WallpaperThumbnail").start();
    }

    private void showThumbnailPlaceholder() {
        bgImagePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bgImagePreview.setImageResource(R.drawable.ic_ui_image);
        bgImagePreview.setImageTintList(ColorStateList.valueOf(getColor(R.color.ui_muted)));
    }

    private void updateNextButton() {
        View next = findViewById(R.id.btn_next_bg_image);
        next.setEnabled(folderImageCount >= 2 && !LauncherBackgroundController.isImporting()
                && !LauncherBackgroundController.isMinecraftBlocked());
    }

    private void setBackgroundImportState(boolean importing) {
        boolean blocked = LauncherBackgroundController.isMinecraftBlocked();
        findViewById(R.id.btn_select_bg_image).setEnabled(!importing && !blocked);
        findViewById(R.id.btn_select_bg_folder).setEnabled(!importing && !blocked);
        findViewById(R.id.btn_clear_bg_image).setEnabled(!importing);
        findViewById(R.id.btn_next_bg_image).setEnabled(!importing && !blocked && folderImageCount >= 2);
        if (importing && bgImageStatus != null) {
            bgImageStatus.setText(R.string.bg_media_importing);
            bgImageStatus.setVisibility(View.VISIBLE);
        }
    }

    /** The single-image picker replaces a wallpaper folder. */
    private void importSingleImage(Uri uri) {
        setBackgroundImportState(true);
        WeakReference<SettingsActivity> ref = new WeakReference<>(this);
        LauncherBackgroundController.importBackground(getApplicationContext(), uri, success -> {
            SettingsActivity activity = ref.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.setBackgroundImportState(false);
            if (success) {
                String folder = activity.personalizationManager.getBackgroundFolder();
                activity.personalizationManager.setBackgroundFolder(null);
                releaseFolderPermission(activity, folder);
                activity.recreate();
            } else {
                activity.updateBgImageUI();
                Toast.makeText(activity, R.string.bg_media_import_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onFolderPicked(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        String previous = personalizationManager.getBackgroundFolder();
        String picked = uri.toString();
        personalizationManager.setBackgroundFolder(picked);
        setBackgroundImportState(true);
        Context app = getApplicationContext();
        WeakReference<SettingsActivity> ref = new WeakReference<>(this);
        WallpaperFolder.next(app, ok -> {
            SettingsActivity activity = ref.get();
            if (ok) {
                if (previous != null && !previous.equals(picked)) releaseFolderPermission(app, previous);
            } else {
                new PersonalizationManager(app).setBackgroundFolder(previous);
                if (!picked.equals(previous)) releaseFolderPermission(app, picked);
            }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.setBackgroundImportState(false);
            if (ok) {
                activity.recreate();
            } else {
                activity.updateBgImageUI();
                Toast.makeText(activity, R.string.settings_ui_folder_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showNextWallpaper() {
        setBackgroundImportState(true);
        WeakReference<SettingsActivity> ref = new WeakReference<>(this);
        WallpaperFolder.next(getApplicationContext(), ok -> {
            SettingsActivity activity = ref.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            activity.setBackgroundImportState(false);
            if (ok) {
                activity.recreate();
            } else {
                activity.updateBgImageUI();
                Toast.makeText(activity, R.string.bg_media_import_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void releaseFolderPermission(Context context, String treeUri) {
        if (treeUri == null) return;
        try {
            context.getContentResolver().releasePersistableUriPermission(Uri.parse(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    // #endregion

    // #region Material

    private void setupMaterialSection() {
        SegmentedControl modeTabs = findViewById(R.id.material_mode_tabs);
        modeTabs.setEqualWidth(false);
        modeTabs.setItems(getString(R.string.settings_ui_mode_light), getString(R.string.settings_ui_mode_dark));
        modeTabs.setSelectedIndex(materialDark ? 1 : 0, false);
        modeTabs.setOnSegmentSelectedListener(index -> {
            materialDark = index == 1;
            refreshMaterialUI();
        });

        SimpleSeekListener sliderListener = new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMaterialValues();
                if (!fromUser || bindingMaterial) return;
                saveMaterialFromControls();
                // The wallpaper overlay can follow the slider live; the surfaces update on release.
                if (materialDark == isDark()) personalizationManager.refreshBackgroundEffects(SettingsActivity.this);
                updateMaterialStatus();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onMaterialChanged();
            }
        };
        ((SeekBar) findViewById(R.id.seek_material_opacity)).setOnSeekBarChangeListener(sliderListener);
        ((SeekBar) findViewById(R.id.seek_material_overlay)).setOnSeekBarChangeListener(sliderListener);

        SwitchMaterial readability = findViewById(R.id.switch_material_readability);
        readability.setOnCheckedChangeListener((btn, checked) -> {
            if (bindingMaterial) return;
            saveMaterialFromControls();
            onMaterialChanged();
        });

        findViewById(R.id.material_reset).setOnClickListener(v -> {
            UiSettings.applyMaterialPreset(this, materialDark, UiSettings.MATERIAL_BALANCED);
            onMaterialChanged();
        });

        refreshMaterialUI();
    }

    private void saveMaterialFromControls() {
        UiSettings.setMaterial(this, materialDark,
                ((SeekBar) findViewById(R.id.seek_material_opacity)).getProgress(),
                ((SeekBar) findViewById(R.id.seek_material_overlay)).getProgress(),
                ((SwitchMaterial) findViewById(R.id.switch_material_readability)).isChecked());
    }

    /**
     * Wallpaper brightness, blur and opacity feed readability protection, which sets the surface
     * opacity of every page: rebuild this page and let the others rebuild on resume.
     */
    private void onWallpaperEffectReleased() {
        if (!personalizationManager.hasBackgroundImage()) return;
        PersonalizationManager.bumpChange();
        recreate();
    }

    /** Material is only visible over a wallpaper; then the page is rebuilt with the new surfaces. */
    private void onMaterialChanged() {
        if (materialDark == isDark() && personalizationManager.hasBackgroundImage()) recreate();
        else refreshMaterialUI();
    }

    private void refreshMaterialUI() {
        bindingMaterial = true;
        ((SeekBar) findViewById(R.id.seek_material_opacity)).setProgress(UiSettings.getSurfaceOpacity(this, materialDark));
        ((SeekBar) findViewById(R.id.seek_material_overlay)).setProgress(UiSettings.getOverlayOpacity(this, materialDark));
        ((SwitchMaterial) findViewById(R.id.switch_material_readability))
                .setChecked(UiSettings.getReadability(this, materialDark));
        bindingMaterial = false;
        updateMaterialValues();

        // A fresh control shows no selection for a custom (slider-edited) material, as on Windows.
        FrameLayout holder = findViewById(R.id.material_preset_holder);
        holder.removeAllViews();
        SegmentedControl presets = new SegmentedControl(this);
        presets.addSegment(getString(R.string.settings_ui_material_balanced), 0);
        presets.addSegment(getString(R.string.settings_ui_material_clear), 0);
        presets.addSegment(getString(R.string.settings_ui_material_solid), 0);
        String preset = UiSettings.getMaterialPreset(this, materialDark);
        for (int i = 0; i < MATERIAL_PRESETS.length; i++) {
            if (MATERIAL_PRESETS[i].equals(preset)) presets.setSelectedIndex(i, false);
        }
        presets.setOnSegmentSelectedListener(index -> {
            UiSettings.applyMaterialPreset(this, materialDark, MATERIAL_PRESETS[index]);
            onMaterialChanged();
        });
        holder.addView(presets, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateMaterialStatus();
    }

    private void updateMaterialValues() {
        setValueText(R.id.material_opacity_value, R.string.settings_ui_value_percent,
                ((SeekBar) findViewById(R.id.seek_material_opacity)).getProgress());
        setValueText(R.id.material_overlay_value, R.string.settings_ui_value_percent,
                ((SeekBar) findViewById(R.id.seek_material_overlay)).getProgress());
    }

    private void updateMaterialStatus() {
        TextView status = findViewById(R.id.material_status);
        if (status == null) return;
        PersonalizationManager pm = personalizationManager;
        int surface = UiSettings.getSurfaceOpacity(this, materialDark);
        int effective = UiSettings.getEffectiveSurfaceOpacity(this, materialDark,
                pm.getBackgroundImageBrightness(), pm.getBackgroundImageBlur(), pm.getBackgroundOpacity());
        boolean hasImage = pm.hasBackgroundImage() && pm.getBackgroundOpacity() > 0;
        if (hasImage && effective > surface) {
            status.setText(getString(R.string.settings_ui_material_protected_opacity, effective));
        } else {
            status.setText(UiSettings.getReadability(this, materialDark)
                    ? R.string.settings_ui_material_protected : R.string.settings_ui_material_unprotected);
        }
    }

    // #endregion

    @Override
    protected void onResume() {
        super.onResume();
        if (personalizationManager != null) {
            updateBgImageUI();
            setBackgroundImportState(LauncherBackgroundController.isImporting());
            updateMaterialStatus();
        }
        if (pkgSettingsSection != null) {
            pkgSettingsSection.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pkgSettingsSection != null) {
            pkgSettingsSection.onDestroy();
            pkgSettingsSection = null;
        }
        super.onDestroy();
    }

    // #region Updates and about

    private void setupUpdatesSection() {
        try {
            String localVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView versionText = findViewById(R.id.version_text);
            versionText.setText(localVersion);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        findViewById(R.id.btn_check_update).setOnClickListener(v -> handleUpdateButtonClick());

        preloaderSigsLastUpdateText = findViewById(R.id.preloader_sigs_last_update);
        preloaderSigsUpdateButton = findViewById(R.id.btn_update_preloader_sigs);
        refreshPreloaderSigsLastUpdateUi();
        if (preloaderSigsUpdateButton != null) {
            preloaderSigsUpdateButton.setOnClickListener(v -> handlePreloaderSigsUpdateClick());
        }
    }

    private void handlePreloaderSigsUpdateClick() {
        if (preloaderSigsUpdateButton == null) {
            return;
        }
        if (!PreloaderSignatureRulesManager.hasRemoteRulesUrl()) {
            Toast.makeText(this, R.string.preloader_sigs_no_remote_url, Toast.LENGTH_SHORT).show();
            return;
        }

        preloaderSigsUpdateButton.setEnabled(false);
        preloaderSigsUpdateButton.setText(R.string.preloader_sigs_updating);
        PreloaderSignatureRulesManager.refreshNow(this, result -> {
            if (isFinishing()) {
                return;
            }
            preloaderSigsUpdateButton.setEnabled(true);
            preloaderSigsUpdateButton.setText(R.string.preloader_sigs_update);
            refreshPreloaderSigsLastUpdateUi();

            if (result.success) {
                Toast.makeText(this, R.string.preloader_sigs_update_success, Toast.LENGTH_SHORT).show();
            } else {
                String detail = result.message.isEmpty()
                        ? getString(R.string.unknown_error)
                        : result.message;
                Toast.makeText(this, getString(R.string.preloader_sigs_update_failed, detail), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void refreshPreloaderSigsLastUpdateUi() {
        if (preloaderSigsLastUpdateText == null) {
            return;
        }

        long updateTime = PreloaderSignatureRulesManager.getLastSuccessfulUpdateTime(this);
        String updateText = updateTime > 0L
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(updateTime))
                : getString(R.string.preloader_sigs_never_updated);
        preloaderSigsLastUpdateText.setText(getString(R.string.preloader_sigs_last_update, updateText));
    }

    private void setupAboutSection() {
        findViewById(R.id.settings_btn_github).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LiteLDev/LeviLauncher-Android"))));

        findViewById(R.id.settings_btn_discord).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/jsnzw4ueAt"))));
    }

    private void handleUpdateButtonClick() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdateButtonTapTime > TAP_TIMEOUT_MS) {
            updateButtonTapCount = 0;
        }

        updateButtonTapCount++;
        lastUpdateButtonTapTime = currentTime;

        if (updateButtonTapCount >= EASTER_EGG_TAP_COUNT) {
            updateButtonTapCount = 0;
            triggerEasterEgg();
        } else {
            new GithubReleaseUpdater(this, "LiteLDev", "LeviLauncher-Android", permissionResultLauncher).checkUpdate();
        }
    }

    private void triggerEasterEgg() {
        try {
            String encoded = "aHR0cHM6Ly95b3V0dS5iZS9GdHV0TEE2M0NwOD9zaT1CSExEWHZLOTZPZ1A0NUI4";
            String url = new String(Base64.decode(encoded, Base64.DEFAULT));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // #endregion

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
