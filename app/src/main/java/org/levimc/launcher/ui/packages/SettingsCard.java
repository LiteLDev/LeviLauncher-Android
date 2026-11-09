package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.settings.FeatureSettings;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class SettingsCard {
    private static final String CACHE_CLEAN = "CacheClean";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Cancel ctx = new Cancel();

    private final TextView cacheSizeView;
    private final Button cacheCleanButton;
    private final TaskProgressView cacheProgress;
    private final Spinner mirrorSpinner;
    private final Spinner proxyPreset;
    private final EditText proxyInput;
    private final Button proxySave;
    private final Button proxyReset;
    private final TextView proxySourceView;
    private final TextView proxyErrorView;
    private final SwitchMaterial scriptsSwitch;
    private final TextView catalogAgeView;
    private final Button catalogRefresh;

    private boolean destroyed;
    private boolean proxyEdited;
    private boolean settingProxyText;
    private boolean refreshingCatalog;
    private int sizeRequest;
    private int proxyRequest;
    @Nullable
    private TaskSession cacheSession;

    public SettingsCard(Activity activity, View root) {
        this.activity = activity;
        cacheSizeView = root.findViewById(R.id.pkg_settings_cache_size);
        cacheCleanButton = root.findViewById(R.id.pkg_settings_cache_clean);
        cacheProgress = root.findViewById(R.id.pkg_settings_cache_progress);
        mirrorSpinner = root.findViewById(R.id.pkg_settings_mirror_spinner);
        proxyPreset = root.findViewById(R.id.pkg_settings_proxy_preset);
        proxyInput = root.findViewById(R.id.pkg_settings_proxy_input);
        proxySave = root.findViewById(R.id.pkg_settings_proxy_save);
        proxyReset = root.findViewById(R.id.pkg_settings_proxy_reset);
        proxySourceView = root.findViewById(R.id.pkg_settings_proxy_source);
        proxyErrorView = root.findViewById(R.id.pkg_settings_proxy_error);
        scriptsSwitch = root.findViewById(R.id.pkg_settings_scripts_switch);
        catalogAgeView = root.findViewById(R.id.pkg_settings_catalog_age);
        catalogRefresh = root.findViewById(R.id.pkg_settings_catalog_refresh);

        applyAccent();
        setupCache();
        setupMirror();
        setupProxy();
        setupScripts();
        catalogRefresh.setOnClickListener(v -> refreshCatalog());
    }

    public void onResume() {
        if (destroyed) {
            return;
        }
        attachCacheClean();
        loadCacheSize();
        loadProxy(false);
        loadCatalogAge();
        renderButtons();
    }

    public void onDestroy() {
        destroyed = true;
        ctx.cancel();
        main.removeCallbacksAndMessages(null);
        cacheProgress.unbind();
    }

    public void applyAccent() {
        int accent = PkgUi.accent(activity);
        ColorStateList tint = ColorStateList.valueOf(accent);
        cacheCleanButton.setBackgroundTintList(tint);
        proxySave.setBackgroundTintList(tint);
        catalogRefresh.setBackgroundTintList(tint);
        int[][] states = {{android.R.attr.state_checked}, {}};
        scriptsSwitch.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFAAAAAA}));
        int trackChecked = Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent));
        scriptsSwitch.setTrackTintList(new ColorStateList(states, new int[]{trackChecked, 0xFF555555}));
    }

    // #region Cache

    private void setupCache() {
        cacheProgress.setShowCancel(true);
        cacheCleanButton.setOnClickListener(v -> cleanCache());
    }

    private void loadCacheSize() {
        int req = ++sizeRequest;
        PackageService.EXECUTOR.execute(() -> {
            long size;
            try {
                size = PackageService.get().config().cacheSize();
            } catch (RuntimeException e) {
                // An uncaught exception on a worker thread would end the app.
                PackageService.warn("package settings: cannot measure the cache", e);
                return;
            }
            main.post(() -> {
                if (destroyed || req != sizeRequest) {
                    return;
                }
                cacheSizeView.setText(activity.getString(R.string.pkg_settings_cache_size,
                        Formatter.formatShortFileSize(activity, size)));
            });
        });
    }


    private void cleanCache() {
        if (cacheCleanRunning()) {
            return;
        }
        TaskSession.Options options = TaskSession.Options.builder("")
                .title(R.string.pkg_task_title_generic)
                .methods(CACHE_CLEAN)
                .feedbackMode(TaskSession.FeedbackMode.INLINE)
                .errorConsole(false)
                .build();
        long[] freed = {0};
        WeakReference<SettingsCard> ref = new WeakReference<>(this);
        Context app = activity.getApplicationContext();
        TaskSession s = TaskDialog.start(activity, options, t -> {
            long before = PackageService.get().config().cacheSize();
            String code = PackageService.get().cacheClean(t.cancel(), t.listener());
            if (code.isEmpty()) {
                freed[0] = SettingsCard.Model.freed(before, PackageService.get().config().cacheSize());
            }
            return code;
        }, (session, result) -> {
            SettingsCard c = ref.get();
            if (c != null && c.destroyed) {
                c = null;
            }
            Context t = c != null ? c.activity : app;
            if (result.isSuccess()) {
                toast(t, t.getString(R.string.pkg_settings_cache_cleaned, Formatter.formatShortFileSize(t, freed[0])));
            } else {
                toast(t, t.getString(R.string.pkg_operation_failed, PkgUi.ErrorText.describe(t, result)));
            }
            if (c != null) {
                c.onCacheCleanEnded();
            }
        });
        cacheSession = s;
        cacheProgress.bind(s);
        renderButtons();
    }

    private void attachCacheClean() {
        if (cacheSession != null && cacheSession.isRunning()) {
            return;
        }
        for (TaskSession s : TaskSession.running()) {
            if (s.isRunning() && s.options().methods.contains(CACHE_CLEAN)) {
                cacheSession = s;
                cacheProgress.bind(s);
                WeakReference<SettingsCard> ref = new WeakReference<>(this);
                s.addCallback((session, result) -> {
                    SettingsCard c = ref.get();
                    if (c != null && !c.destroyed) {
                        c.onCacheCleanEnded();
                    }
                });
                return;
            }
        }
    }

    private void onCacheCleanEnded() {
        cacheSession = null;
        renderButtons();
        loadCacheSize();
    }

    private boolean cacheCleanRunning() {
        return cacheSession != null && cacheSession.isRunning();
    }

    // #endregion

    // #region Mirror

    private void setupMirror() {
        boolean detected = PackageService.mirrorEnabled(PackageService.MirrorMode.AUTO, Locale.getDefault(), TimeZone.getDefault().getID());
        String[] labels = {
                activity.getString(R.string.pkg_settings_mirror_auto, activity.getString(
                        detected ? R.string.pkg_settings_mirror_on : R.string.pkg_settings_mirror_off)),
                activity.getString(R.string.pkg_settings_mirror_on),
                activity.getString(R.string.pkg_settings_mirror_off)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        mirrorSpinner.setAdapter(adapter);
        mirrorSpinner.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        int current = SettingsCard.Model.mirrorIndex(PackageService.mirrorMode());
        mirrorSpinner.setSelection(current, false);
        mirrorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                PackageService.MirrorMode mode = SettingsCard.Model.mirrorAt(position);
                if (mode == PackageService.mirrorMode()) {
                    return;
                }
                PackageService.setMirrorMode(mode);
                // The proxy's source label says whether the default proxy applies.
                loadProxy(false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // #endregion

    // #region GitHub proxy

    private void setupProxy() {
        String[] labels = new String[Model.PROXY_PRESETS.length + 1];
        for (int i = 0; i < Model.PROXY_PRESETS.length; i++) labels[i] = Model.PROXY_PRESETS[i].replaceFirst("^https?://", "");
        labels[Model.PROXY_PRESETS.length] = activity.getString(R.string.pkg_settings_proxy_preset_custom);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        proxyPreset.setAdapter(adapter);
        proxyPreset.setPopupBackgroundResource(R.drawable.bg_popup_menu_rounded);
        proxyPreset.setSelection(Model.PROXY_PRESETS.length, false);
        // Idempotent, so the selections made to follow the field (and the spinner's first callback) do nothing.
        proxyPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int current = Model.presetIndex(proxyInput.getText() == null ? "" : proxyInput.getText().toString());
                if (position == current || position == Model.PROXY_PRESETS.length && current < 0) {
                    return;
                }
                if (position < Model.PROXY_PRESETS.length) {
                    proxyInput.setText(Model.PROXY_PRESETS[position]);
                    saveProxy();
                } else {
                    proxyInput.setText("");
                    proxyInput.requestFocus();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        proxyInput.addTextChangedListener(SimpleTextWatcher.after(s -> {
            int preset = Model.presetIndex(s.toString());
            proxyPreset.setSelection(preset < 0 ? Model.PROXY_PRESETS.length : preset);
            if (!settingProxyText) {
                proxyEdited = true;
                proxyErrorView.setVisibility(View.GONE);
            }
        }));
        proxyInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveProxy();
                return true;
            }
            return false;
        });
        proxySave.setOnClickListener(v -> saveProxy());
        proxyReset.setOnClickListener(v -> resetProxy());
    }

    private static final class ProxyState {
        String display = "";
        SettingsCard.Model.ProxySource source = SettingsCard.Model.ProxySource.DEFAULT_MIRROR_OFF;
    }

    private static ProxyState readProxy() {
        ProxyState s = new ProxyState();
        String configured = PackageService.get().config().githubProxy();
        s.display = configured != null ? configured : PackageService.DEFAULT_GITHUB_PROXY;
        s.source = SettingsCard.Model.proxySource(configured != null, PackageService.mirrorEnabled());
        return s;
    }

    private void loadProxy(boolean replace) {
        int req = ++proxyRequest;
        PackageService.EXECUTOR.execute(() -> {
            ProxyState s;
            try {
                s = readProxy();
            } catch (RuntimeException e) {
                PackageService.warn("package settings: cannot read the GitHub proxy", e);
                return;
            }
            main.post(() -> {
                if (destroyed || req != proxyRequest) {
                    return;
                }
                showProxy(s, replace);
            });
        });
    }

    private void showProxy(ProxyState s, boolean replace) {
        if (replace || !proxyEdited) {
            settingProxyText = true;
            proxyInput.setText(s.display);
            settingProxyText = false;
            proxyEdited = false;
        }
        proxySourceView.setText(sourceText(s.source));
    }

    private String sourceText(SettingsCard.Model.ProxySource source) {
        switch (source) {
            case SET:
                return activity.getString(R.string.pkg_settings_proxy_source_set);
            case DEFAULT_MIRROR_ON:
                return activity.getString(R.string.pkg_settings_proxy_source_default_on);
            default:
                return activity.getString(R.string.pkg_settings_proxy_source_default_off);
        }
    }

    private void saveProxy() {
        String draft = proxyInput.getText() == null ? "" : proxyInput.getText().toString().trim();
        proxyErrorView.setVisibility(View.GONE);
        String code = PackageService.get().setGithubProxy(draft);
        if (code.isEmpty()) {
            proxyEdited = false;
            loadProxy(true);
            toast(activity, activity.getString(R.string.pkg_settings_proxy_saved));
        } else {
            showProxyError(code);
        }
    }

    private void resetProxy() {
        proxyErrorView.setVisibility(View.GONE);
        if (PackageService.get().setGithubProxy("").isEmpty()) {
            proxyEdited = false;
            loadProxy(true);
            toast(activity, activity.getString(R.string.pkg_settings_proxy_reset_done));
        } else {
            showProxyError(PackageService.ERR_WRITE_FILE);
        }
    }

    private void showProxyError(String code) {
        String known = SettingsCard.Model.saveErrorCode(code);
        proxyErrorView.setText(known != null ? PkgUi.ErrorText.describe(activity, known)
                : activity.getString(R.string.pkg_settings_proxy_save_failed));
        proxyErrorView.setVisibility(View.VISIBLE);
    }

    // #endregion

    // #region Scripts

    private void setupScripts() {
        FeatureSettings fs = FeatureSettings.getInstance();
        scriptsSwitch.setChecked(fs.isPkgRunScripts());
        scriptsSwitch.setOnCheckedChangeListener((b, checked) -> fs.setPkgRunScripts(checked));
    }

    // #endregion

    // #region Catalogue

    private void loadCatalogAge() {
        PackageService.EXECUTOR.execute(() -> {
            long at;
            try {
                at = Catalog.get().lastFetchedAtMs();
            } catch (RuntimeException e) {
                PackageService.warn("package settings: cannot read the catalogue age", e);
                at = 0;
            }
            long fetchedAt = at;
            main.post(() -> {
                if (!destroyed) {
                    showCatalogAge(fetchedAt);
                }
            });
        });
    }

    private void showCatalogAge(long atMs) {
        if (atMs <= 0) {
            catalogAgeView.setText(R.string.pkg_settings_catalog_never);
            return;
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(atMs));
        catalogAgeView.setText(activity.getString(R.string.pkg_settings_catalog_age, when));
    }

    private void refreshCatalog() {
        if (refreshingCatalog) {
            return;
        }
        refreshingCatalog = true;
        renderButtons();
        Cancel c = ctx;
        PackageService.EXECUTOR.execute(() -> {
            int count = -1;
            long at = 0;
            String code = "";
            String detail = "";
            try {
                Catalog.Result r = Catalog.get().load(c, true);
                at = r.fetchedAtMs;
                if (r.stale && r.error != null) {
                    code = PackageService.code(r.error);
                    detail = PackageService.message(r.error);
                } else {
                    count = r.index.size();
                }
            } catch (PackageService.Failure e) {
                if (e.code.equals(PackageService.ERR_PKG_TASK_CANCELLED)) return;
                code = PackageService.code(e);
                detail = PackageService.message(e);
            } catch (RuntimeException e) {
                // A parse failure of the fetched index; also keeps the button from staying "Refreshing…".
                code = PackageService.ERR_PKG_INDEX_UNAVAILABLE;
                detail = String.valueOf(e);
            }
            if (code.isEmpty()) {
                try {
                    LeviLamina.DbClient.Result db = LeviLamina.DbClient.get().load(c, true);
                    if (db.stale && db.error != null) {
                        code = PackageService.code(db.error);
                        detail = PackageService.message(db.error);
                    }
                } catch (PackageService.Failure e) {
                    if (e.code.equals(PackageService.ERR_PKG_TASK_CANCELLED)) return;
                    code = PackageService.code(e);
                    detail = PackageService.message(e);
                } catch (RuntimeException e) {
                    code = PackageService.ERR_FETCH_LL_DB;
                    detail = String.valueOf(e);
                }
            }
            if (!code.isEmpty()) {
                PackageService.warn("package settings: catalogue refresh failed: " + detail);
            }
            int n = count;
            long fetchedAt = at;
            String failure = code;
            String failureDetail = detail;
            main.post(() -> {
                if (destroyed) {
                    return;
                }
                refreshingCatalog = false;
                renderButtons();
                if (fetchedAt > 0) {
                    showCatalogAge(fetchedAt);
                }
                if (failure.isEmpty()) {
                    toast(activity, activity.getString(R.string.pkg_settings_catalog_refreshed, n));
                } else {
                    toast(activity, activity.getString(R.string.pkg_operation_failed,
                            PkgUi.ErrorText.describe(activity, failure, failureDetail)));
                }
            });
        });
    }

    // #endregion

    private void renderButtons() {
        boolean cleaning = cacheCleanRunning();
        cacheCleanButton.setText(cleaning ? R.string.pkg_settings_cache_cleaning : R.string.pkg_settings_cache_clean);
        setEnabled(cacheCleanButton, !cleaning);
        catalogRefresh.setText(refreshingCatalog ? R.string.pkg_settings_catalog_refreshing
                : R.string.pkg_settings_catalog_refresh);
        setEnabled(catalogRefresh, !refreshingCatalog);
    }

    private static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.55f);
    }

    private static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    public final static class Model {
        public static final PackageService.MirrorMode[] MIRROR_MODES = {PackageService.MirrorMode.AUTO, PackageService.MirrorMode.ON, PackageService.MirrorMode.OFF};

        /** Prefix-style GitHub proxies offered by the picker (LeviLauncher's presets); any other URL can be typed. */
        public static final String[] PROXY_PRESETS = {PackageService.DEFAULT_GITHUB_PROXY, "https://gh-proxy.org",
                "https://hk.gh-proxy.org", "https://cdn.gh-proxy.org", "https://edgeone.gh-proxy.org", "https://ghproxy.vip"};

        public enum ProxySource {
            SET,
            DEFAULT_MIRROR_ON,
            DEFAULT_MIRROR_OFF
        }

        private Model() {
        }

        public static int mirrorIndex(PackageService.MirrorMode mode) {
            for (int i = 0; i < MIRROR_MODES.length; i++) {
                if (MIRROR_MODES[i] == mode) {
                    return i;
                }
            }
            return 0;
        }

        public static PackageService.MirrorMode mirrorAt(int index) {
            return index >= 0 && index < MIRROR_MODES.length ? MIRROR_MODES[index] : PackageService.MirrorMode.AUTO;
        }

        public static ProxySource proxySource(boolean configured, boolean mirrorEnabled) {
            if (configured) {
                return ProxySource.SET;
            }
            return mirrorEnabled ? ProxySource.DEFAULT_MIRROR_ON : ProxySource.DEFAULT_MIRROR_OFF;
        }

        /** The preset that {@code url} is (trimmed, without trailing '/'), or -1. */
        public static int presetIndex(String url) {
            return Arrays.asList(PROXY_PRESETS).indexOf(url.trim().replaceAll("/+$", ""));
        }

        public static String saveErrorCode(String code) {
            if (PackageService.ERR_PKG_INVALID_GITHUB_PROXY.equals(code) || PackageService.ERR_WRITE_FILE.equals(code)) {
                return code;
            }
            return null;
        }

        public static long freed(long before, long after) {
            return Math.max(0, before - after);
        }
    }
}
