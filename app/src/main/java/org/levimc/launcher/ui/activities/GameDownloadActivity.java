package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.TooltipCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.play.GameDownloads;
import org.levimc.launcher.core.play.GameRelease;
import org.levimc.launcher.core.play.PlayClient;
import org.levimc.launcher.core.play.PlayException;
import org.levimc.launcher.core.play.VersionCatalog;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.ApkImportManager;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.StorageAccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameDownloadActivity extends BaseActivity implements GameDownloads.Listener {
    private static final int SOURCE_PLAY = 0;
    private static final int SOURCE_VERSIONS = 1;
    private static final int SOURCE_DOWNLOADED = 2;
    private static final int TYPE_ALL = 0;
    private static final int TYPE_RELEASE = 1;
    private static final int TYPE_BETA = 2;
    private static final int STATUS_ALL = 0;
    private static final int STATUS_DOWNLOADED = 1;
    private static final int STATUS_NOT_DOWNLOADED = 2;
    private static final String STATE_SOURCE = "download_source_v2";
    private static final String STATE_TYPE = "download_type";
    private static final String STATE_STATUS = "download_status";
    private static final String STATE_QUERY = "download_query";

    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService dataExecutor = Executors.newSingleThreadExecutor();
    private final List<GameRelease> catalog = new ArrayList<>();
    private final List<GameRelease> history = new ArrayList<>();
    private final List<GameRelease> local = new ArrayList<>();
    private final List<GameRelease> visible = new ArrayList<>();
    private final Map<Long, GameDownloads.Downloaded> downloadedFiles = new HashMap<>();
    private final Set<String> installedVersionNames = new HashSet<>();

    private GameDownloads downloads;
    private VersionCatalog versionCatalog;
    private PersonalizationManager personalization;
    private ApkImportManager apkImportManager;
    private ActivityResultLauncher<Intent> googleLoginLauncher;
    private ActivityResultLauncher<Intent> apkImportLauncher;
    private TextView subtitle;
    private TextView status;
    private TextView count;
    private MaterialButton accountButton;
    private MaterialButton refreshButton;
    private MaterialButton filterButton;
    private EditText search;
    private SegmentedControl sources;
    private View versionSource;
    private View betaRow;
    private SwitchMaterial betaSwitch;
    private ProgressBar progress;
    private View empty;
    private TextView emptyTitle;
    private TextView emptyText;
    private MaterialButton emptyAction;
    private ReleaseAdapter adapter;
    private ColorStateList accentTint;

    private int sourceMode;
    private int typeFilter;
    private int statusFilter;
    private long latestVersionCode;
    private long accountGeneration = -1;
    private int catalogRequest;
    private int dataRequest;
    private int historyRequest;
    private boolean signedIn;
    private boolean remoteChecked;
    private boolean refreshing;
    private boolean loadingLocal;
    private boolean updatingBetaSwitch;
    private boolean changingBeta;
    private boolean betaAvailable;
    private boolean historyLoaded;
    private boolean historyRefreshing;
    private volatile boolean disposed;
    private String catalogError;
    private String historyError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_download);
        downloads = GameDownloads.get(this);
        versionCatalog = new VersionCatalog(this);
        personalization = new PersonalizationManager(this);
        if (savedInstanceState != null) {
            int restored = savedInstanceState.containsKey(STATE_SOURCE) ? savedInstanceState.getInt(STATE_SOURCE, SOURCE_PLAY)
                    : savedInstanceState.getInt("download_source", SOURCE_PLAY) == 1 ? SOURCE_DOWNLOADED : SOURCE_PLAY;
            sourceMode = restored >= SOURCE_PLAY && restored <= SOURCE_DOWNLOADED ? restored : SOURCE_PLAY;
            typeFilter = clampFilter(savedInstanceState.getInt(STATE_TYPE, TYPE_ALL));
            statusFilter = clampFilter(savedInstanceState.getInt(STATE_STATUS, STATUS_ALL));
        }

        apkImportManager = new ApkImportManager(this, null);
        apkImportManager.setOnImportCompleteListener(() -> {
            refreshInstalled();
            loadLocal();
        });
        apkImportLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> apkImportManager.handleActivityResult(result.getResultCode(), result.getData()));
        googleLoginLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            syncAccount(true);
        });

        subtitle = findViewById(R.id.game_download_subtitle);
        status = findViewById(R.id.game_download_status);
        count = findViewById(R.id.game_download_count);
        accountButton = findViewById(R.id.game_download_account);
        refreshButton = findViewById(R.id.game_download_refresh);
        filterButton = findViewById(R.id.game_download_filters);
        search = findViewById(R.id.game_download_search);
        sources = findViewById(R.id.game_download_sources);
        versionSource = findViewById(R.id.game_download_version_source);
        betaRow = findViewById(R.id.game_download_beta_row);
        betaSwitch = findViewById(R.id.game_download_beta_switch);
        RecyclerView recycler = findViewById(R.id.game_download_recycler);
        progress = findViewById(R.id.game_download_progress);
        empty = findViewById(R.id.game_download_empty);
        emptyTitle = findViewById(R.id.game_download_empty_title);
        emptyText = findViewById(R.id.game_download_empty_text);
        emptyAction = findViewById(R.id.game_download_empty_action);
        accentTint = ColorStateList.valueOf(UiKit.accent(this));
        progress.setIndeterminateTintList(accentTint);
        UiKit.applyPrimary(emptyAction);
        adapter = new ReleaseAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);
        recycler.setAdapter(adapter);

        sources.setEqualWidth(false);
        sources.setItems(getString(R.string.game_download_source_play), getString(R.string.game_download_source_versions),
                getString(R.string.game_download_source_local));
        sources.setSelectedIndex(sourceMode, false);
        sources.setOnSegmentSelectedListener(index -> {
            sourceMode = index;
            applyFilters();
            if (sourceMode == SOURCE_VERSIONS && !historyLoaded) refreshHistory();
        });
        accountButton.setOnClickListener(v -> onAccountClicked());
        refreshButton.setOnClickListener(v -> reload());
        filterButton.setOnClickListener(this::showFilters);
        findViewById(R.id.game_download_import_apk).setOnClickListener(v -> importApk());
        MaterialButton byCode = findViewById(R.id.game_download_by_code);
        if (getResources().getConfiguration().screenWidthDp >= 600) byCode.setText(R.string.game_download_code_short);
        byCode.setOnClickListener(v -> showVersionCodeDialog());
        TooltipCompat.setTooltipText(byCode, getString(R.string.game_download_by_code));
        TooltipCompat.setTooltipText(refreshButton, getString(R.string.game_download_refresh));
        versionSource.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(VersionCatalog.SOURCE_URL)));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, R.string.game_download_browser_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        TooltipCompat.setTooltipText(versionSource, getString(R.string.game_download_version_source));
        emptyAction.setOnClickListener(v -> onEmptyAction());
        betaSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingBetaSwitch) setBetaChannel(checked);
        });
        search.setSaveEnabled(false);
        search.addTextChangedListener(SimpleTextWatcher.after(s -> applyFilters()));
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(v.getWindowToken(), 0);
            search.clearFocus();
            return true;
        });
        if (savedInstanceState != null) search.setText(savedInstanceState.getString(STATE_QUERY, ""));
        syncAccount(true);
        if (sourceMode == SOURCE_VERSIONS) refreshHistory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SOURCE, sourceMode);
        outState.putInt(STATE_TYPE, typeFilter);
        outState.putInt(STATE_STATUS, statusFilter);
        outState.putString(STATE_QUERY, search.getText().toString());
    }

    @Override
    protected void onStart() {
        super.onStart();
        downloads.addListener(this);
    }

    @Override
    protected void onStop() {
        downloads.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setActiveNavTab(R.id.nav_tab_download);
        syncAccount(true);
        if (!loadingLocal) loadLocal();
        refreshInstalled();
    }

    @Override
    protected void onDestroy() {
        disposed = true;
        catalogRequest++;
        dataRequest++;
        historyRequest++;
        versionCatalog.close();
        networkExecutor.shutdownNow();
        dataExecutor.shutdownNow();
        super.onDestroy();
    }

    private static int clampFilter(int value) {
        return value >= 0 && value <= 2 ? value : 0;
    }

    private boolean current(long generation) {
        return alive() && generation == PlayClient.sessionGeneration();
    }

    private boolean alive() {
        return !disposed && !isFinishing() && !isDestroyed();
    }

    private void syncAccount(boolean checkRemote) {
        if (disposed) return;
        long generation = PlayClient.sessionGeneration();
        boolean authenticated = PlayClient.isSignedIn(this);
        boolean changed = generation != accountGeneration || signedIn != authenticated;
        if (changed) {
            accountGeneration = generation;
            signedIn = authenticated;
            catalogRequest++;
            dataRequest++;
            refreshing = false;
            loadingLocal = false;
            changingBeta = false;
            remoteChecked = false;
            catalogError = null;
            latestVersionCode = 0;
            betaAvailable = false;
            catalog.clear();
            betaRow.setVisibility(View.GONE);
            betaSwitch.setEnabled(true);
            updatingBetaSwitch = true;
            betaSwitch.setChecked(false);
            updatingBetaSwitch = false;
        }
        subtitle.setText(signedIn ? R.string.game_download_subtitle_signed_in : R.string.game_download_subtitle_signed_out);
        int accountLabel = signedIn ? R.string.game_download_sign_out : R.string.game_download_google_sign_in;
        accountButton.setContentDescription(getString(accountLabel));
        TooltipCompat.setTooltipText(accountButton, getString(accountLabel));
        accountButton.setText(signedIn ? R.string.game_download_sign_out : R.string.game_download_sign_in_short);
        accountButton.setIconPadding(Math.round(UiKit.dp(this, signedIn ? 0 : 6)));
        int padding = Math.round(UiKit.dp(this, 12));
        accountButton.setPaddingRelative(padding, 0, padding, 0);
        if (signedIn) {
            accountButton.setIcon(null);
            applySecondary(accountButton);
            accountButton.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            accountButton.setStrokeColor(ColorStateList.valueOf(UiKit.alpha(getColor(R.color.text_secondary), 100)));
            accountButton.setStrokeWidth(Math.max(1, Math.round(UiKit.dp(this, 1))));
        } else {
            accountButton.setIconResource(R.drawable.ic_ui_person);
            accountButton.setStrokeWidth(0);
            UiKit.applyPrimary(accountButton);
        }
        applyFilters();
        if (changed) loadLocal();
        if (checkRemote && signedIn && !remoteChecked) refreshFromPlay();
    }

    private void onAccountClicked() {
        syncAccount(false);
        if (!signedIn) {
            googleLoginLauncher.launch(new Intent(this, GoogleLoginActivity.class));
            return;
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.game_download_google_account))
                .setMessage(getString(R.string.game_download_sign_out_message))
                .setPositiveButton(getString(R.string.game_download_sign_out), v -> {
                    PlayClient.signOut(this);
                    syncAccount(false);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private boolean requireSignIn() {
        syncAccount(false);
        if (signedIn) return true;
        Toast.makeText(this, R.string.game_download_sign_in_required, Toast.LENGTH_SHORT).show();
        googleLoginLauncher.launch(new Intent(this, GoogleLoginActivity.class));
        return false;
    }

    private void reload() {
        syncAccount(false);
        loadLocal();
        if (sourceMode == SOURCE_VERSIONS) refreshHistory();
        else if (sourceMode == SOURCE_PLAY && signedIn) refreshFromPlay();
    }

    private void refreshHistory() {
        if (!alive() || historyRefreshing) return;
        historyRefreshing = true;
        historyError = null;
        boolean loadCache = !historyLoaded;
        int request = ++historyRequest;
        updateState();
        networkExecutor.execute(() -> {
            if (loadCache) {
                List<GameRelease> cached = versionCatalog.cached();
                runOnUiThread(() -> {
                    if (!alive() || request != historyRequest) return;
                    history.clear();
                    history.addAll(cached);
                    historyLoaded = true;
                    applyFilters();
                });
            }
            List<GameRelease> fresh = null;
            boolean failed = false;
            try {
                fresh = versionCatalog.refresh();
            } catch (java.io.IOException error) {
                failed = true;
            }
            List<GameRelease> result = fresh;
            boolean error = failed;
            runOnUiThread(() -> {
                if (!alive() || request != historyRequest) return;
                historyRefreshing = false;
                historyLoaded = true;
                if (result != null) {
                    history.clear();
                    history.addAll(result);
                }
                historyError = error ? getString(R.string.game_download_versions_refresh_failed) : null;
                applyFilters();
            });
        });
    }

    private void loadLocal() {
        if (disposed) return;
        int request = ++dataRequest;
        long generation = accountGeneration;
        boolean includeCatalog = signedIn;
        loadingLocal = true;
        updateState();
        dataExecutor.execute(() -> {
            List<GameRelease> localReleases = downloads.downloadedReleases();
            List<GameRelease> remoteReleases = includeCatalog ? downloads.catalogReleases() : new ArrayList<>();
            Map<Long, GameDownloads.Downloaded> files = new HashMap<>();
            for (GameRelease release : localReleases) {
                GameDownloads.Downloaded item = downloads.downloaded(release.versionCode);
                if (item != null) files.put(release.versionCode, item);
            }
            runOnUiThread(() -> {
                if (!current(generation) || request != dataRequest) return;
                loadingLocal = false;
                local.clear();
                local.addAll(localReleases);
                downloadedFiles.clear();
                downloadedFiles.putAll(files);
                catalog.clear();
                if (signedIn) catalog.addAll(remoteReleases);
                mergeTasks();
                applyFilters();
            });
        });
    }

    private void mergeTasks() {
        if (!signedIn) return;
        Map<Long, GameRelease> merged = new LinkedHashMap<>();
        for (GameRelease release : catalog) merged.put(release.versionCode, release);
        for (GameDownloads.Task task : downloads.tasks()) {
            if (task.accountGeneration != accountGeneration || task.state == GameDownloads.State.CANCELLED) continue;
            GameRelease existing = merged.get(task.versionCode);
            if (existing == null || !TextUtils.isEmpty(task.versionName)) {
                String name = TextUtils.isEmpty(task.versionName) && existing != null ? existing.versionName : task.versionName;
                long size = task.bytesTotal > 0 || existing == null ? task.bytesTotal : existing.size;
                merged.put(task.versionCode, new GameRelease(task.versionCode, name, size, existing != null ? existing.seenAt : 0));
            }
        }
        catalog.clear();
        catalog.addAll(merged.values());
        catalog.sort(GameRelease::compareNewestFirst);
    }

    private void refreshFromPlay() {
        if (!signedIn || refreshing || changingBeta || disposed) return;
        refreshing = true;
        catalogError = null;
        int request = ++catalogRequest;
        long generation = accountGeneration;
        updateState();
        networkExecutor.execute(() -> {
            PlayClient.AppDetails result = null;
            PlayException problem = null;
            try {
                result = new PlayClient(getApplicationContext()).details(GameDownloads.MINECRAFT_PACKAGE);
                GameRelease release = result.versionCode > 0
                        ? new GameRelease(result.versionCode, result.versionName, result.downloadSize, System.currentTimeMillis())
                        : null;
                if (!downloads.rememberListing(release, result.offerType, generation)) {
                    throw new PlayException(PlayException.Kind.UNAUTHORIZED, "Google Play session changed");
                }
            } catch (PlayException error) {
                problem = error;
            }
            PlayClient.AppDetails details = result;
            PlayException error = problem;
            runOnUiThread(() -> {
                if (!current(generation) || request != catalogRequest) return;
                refreshing = false;
                remoteChecked = true;
                if (error != null && error.kind == PlayException.Kind.UNAUTHORIZED) {
                    PlayClient.signOut(this);
                    syncAccount(false);
                    catalogError = describe(error);
                    updateState();
                    return;
                }
                if (details != null) {
                    latestVersionCode = details.versionCode;
                    catalogError = details.versionCode > 0 ? null : getString(R.string.download_ui_no_version_for_device);
                    updateBetaSwitch(details);
                } else {
                    catalogError = describe(error);
                }
                loadLocal();
            });
        });
    }

    private void updateBetaSwitch(PlayClient.AppDetails details) {
        betaAvailable = details.testingProgramAvailable || details.testingProgramSubscribed;
        betaRow.setVisibility(sourceMode == SOURCE_PLAY && betaAvailable ? View.VISIBLE : View.GONE);
        updatingBetaSwitch = true;
        betaSwitch.setChecked(details.testingProgramSubscribed);
        updatingBetaSwitch = false;
    }

    private void setBetaChannel(boolean join) {
        if (!requireSignIn() || changingBeta) return;
        changingBeta = true;
        refreshing = false;
        catalogRequest++;
        long generation = accountGeneration;
        betaSwitch.setEnabled(false);
        updateState();
        networkExecutor.execute(() -> {
            boolean changed = false;
            String message;
            try {
                changed = new PlayClient(getApplicationContext()).setTestingProgram(GameDownloads.MINECRAFT_PACKAGE, join);
                message = getString(changed
                        ? (join ? R.string.game_download_beta_joined : R.string.game_download_beta_left)
                        : R.string.game_download_beta_failed);
            } catch (PlayException error) {
                message = describe(error);
            }
            boolean success = changed;
            String result = message;
            runOnUiThread(() -> {
                if (!current(generation)) return;
                changingBeta = false;
                betaSwitch.setEnabled(true);
                if (!success) {
                    updatingBetaSwitch = true;
                    betaSwitch.setChecked(!join);
                    updatingBetaSwitch = false;
                }
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                remoteChecked = false;
                refreshFromPlay();
            });
        });
    }

    private void refreshInstalled() {
        if (disposed) return;
        dataExecutor.execute(() -> {
            VersionManager manager = VersionManager.get(getApplicationContext());
            Set<String> names = new HashSet<>();
            List<GameVersion> versions = new ArrayList<>();
            if (manager.getInstalledVersions() != null) versions.addAll(manager.getInstalledVersions());
            if (manager.getCustomVersions() != null) versions.addAll(manager.getCustomVersions());
            for (GameVersion version : versions) {
                if (version != null && version.versionCode != null) names.add(version.versionCode);
            }
            runOnUiThread(() -> {
                if (disposed || isFinishing() || isDestroyed()) return;
                installedVersionNames.clear();
                installedVersionNames.addAll(names);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void showFilters(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        SubMenu types = menu.getMenu().addSubMenu(R.string.game_download_filter_type);
        int[] labels = {R.string.game_download_type_all, R.string.game_download_type_release, R.string.game_download_type_beta};
        for (int i = 0; i < labels.length; i++) types.add(1, 100 + i, i, labels[i]).setCheckable(true).setChecked(i == typeFilter);
        types.setGroupCheckable(1, true, true);
        if (sourceMode != SOURCE_DOWNLOADED) {
            SubMenu states = menu.getMenu().addSubMenu(R.string.game_download_filter_status);
            int[] statuses = {R.string.game_download_status_all, R.string.game_download_status_downloaded, R.string.game_download_status_not_downloaded};
            for (int i = 0; i < statuses.length; i++) states.add(2, 200 + i, i, statuses[i]).setCheckable(true).setChecked(i == statusFilter);
            states.setGroupCheckable(2, true, true);
        }
        menu.getMenu().add(0, 300, 10, R.string.game_download_filter_clear);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100 && id < 103) typeFilter = id - 100;
            else if (id >= 200 && id < 203) statusFilter = id - 200;
            else if (id == 300) resetFilters();
            else return false;
            applyFilters();
            return true;
        });
        menu.show();
    }

    private void resetFilters() {
        typeFilter = TYPE_ALL;
        statusFilter = STATUS_ALL;
        search.setText("");
        applyFilters();
    }

    private boolean hasFilters() {
        return typeFilter != TYPE_ALL || (sourceMode != SOURCE_DOWNLOADED && statusFilter != STATUS_ALL)
                || !search.getText().toString().trim().isEmpty();
    }

    private void applyFilters() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        List<GameRelease> releases = sourceMode == SOURCE_DOWNLOADED ? local : sourceMode == SOURCE_VERSIONS ? history : catalog;
        if (sourceMode != SOURCE_PLAY || signedIn) {
            for (GameRelease release : releases) {
                if (typeFilter == TYPE_RELEASE && release.isBeta()) continue;
                if (typeFilter == TYPE_BETA && !release.isBeta()) continue;
                if (sourceMode != SOURCE_DOWNLOADED) {
                    boolean saved = downloadedFiles.containsKey(release.versionCode);
                    if (statusFilter == STATUS_DOWNLOADED && !saved) continue;
                    if (statusFilter == STATUS_NOT_DOWNLOADED && saved) continue;
                }
                if (!query.isEmpty() && !release.displayName().toLowerCase(Locale.ROOT).contains(query)
                        && !String.valueOf(release.versionCode).contains(query)) continue;
                visible.add(release);
            }
        }
        adapter.notifyDataSetChanged();
        count.setText(getResources().getQuantityString(R.plurals.game_download_version_count, visible.size(), visible.size()));
        int filters = (typeFilter == TYPE_ALL ? 0 : 1) + (sourceMode != SOURCE_DOWNLOADED && statusFilter != STATUS_ALL ? 1 : 0);
        filterButton.setText(filters == 0 ? getString(R.string.game_download_filters)
                : getString(R.string.game_download_filters_active, filters));
        updateState();
    }

    private void updateState() {
        if (emptyTitle == null) return;
        boolean busy = sourceMode == SOURCE_VERSIONS ? historyRefreshing
                : sourceMode == SOURCE_PLAY ? refreshing || (signedIn && loadingLocal) : loadingLocal;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(sourceMode == SOURCE_VERSIONS ? !historyRefreshing
                : sourceMode == SOURCE_PLAY ? !refreshing && !loadingLocal && !changingBeta : !loadingLocal);
        versionSource.setVisibility(sourceMode == SOURCE_VERSIONS ? View.VISIBLE : View.GONE);
        betaRow.setVisibility(sourceMode == SOURCE_PLAY && signedIn && betaAvailable ? View.VISIBLE : View.GONE);
        String message = sourceMode == SOURCE_VERSIONS ? historyError : sourceMode == SOURCE_PLAY ? catalogError : null;
        boolean showError = !busy && !TextUtils.isEmpty(message);
        status.setVisibility(showError && !visible.isEmpty() ? View.VISIBLE : View.GONE);
        status.setText(showError ? message : "");
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (!visible.isEmpty()) return;
        emptyAction.setVisibility(View.VISIBLE);
        if (sourceMode == SOURCE_PLAY && !signedIn) {
            emptyTitle.setText(R.string.game_download_empty_title_sign_in);
            emptyText.setText(showError ? message : getString(R.string.game_download_empty_signed_out));
            emptyAction.setText(R.string.game_download_google_sign_in);
        } else if (busy) {
            emptyTitle.setText(sourceMode == SOURCE_VERSIONS ? R.string.game_download_versions_loading
                    : refreshing ? R.string.game_download_checking_play : R.string.game_download_loading_local);
            emptyText.setText("");
            emptyAction.setVisibility(View.GONE);
        } else if (hasFilters()) {
            emptyTitle.setText(R.string.game_download_empty_title_filtered);
            emptyText.setText(R.string.game_download_empty_filtered);
            emptyAction.setText(R.string.game_download_filter_clear);
        } else if (sourceMode == SOURCE_DOWNLOADED) {
            emptyTitle.setText(R.string.game_download_empty_title_local);
            emptyText.setText(R.string.game_download_empty_local);
            emptyAction.setVisibility(View.GONE);
        } else {
            emptyTitle.setText(showError ? R.string.game_download_empty_title_error : R.string.game_download_empty_title_available);
            emptyText.setText(showError ? message : getString(sourceMode == SOURCE_VERSIONS
                    ? R.string.game_download_versions_empty : R.string.game_download_empty_signed_in));
            emptyAction.setText(R.string.game_download_refresh);
        }
    }

    private void onEmptyAction() {
        if (sourceMode == SOURCE_PLAY && !signedIn) onAccountClicked();
        else if (hasFilters()) resetFilters();
        else if (sourceMode != SOURCE_DOWNLOADED) reload();
    }

    private void importApk() {
        apkImportLauncher.launch(StorageAccess.downloadsPicker(false));
    }

    private String describe(PlayException error) {
        if (error == null) return getString(R.string.game_download_error_generic);
        switch (error.kind) {
            case UNAUTHORIZED: return getString(R.string.game_download_error_unauthorized);
            case NOT_PURCHASED: return getString(R.string.game_download_error_not_purchased);
            case NOT_AVAILABLE: return getString(R.string.game_download_error_not_available);
            case RATE_LIMITED: return getString(R.string.game_download_error_rate_limited);
            case NETWORK: return getString(R.string.game_download_error_network);
            default: return getString(R.string.game_download_error_generic);
        }
    }

    private void showVersionCodeDialog() {
        if (!requireSignIn()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.game_download_by_code_hint);
        input.setSingleLine(true);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        FrameLayout container = new FrameLayout(this);
        int padding = Math.round(UiKit.dp(this, 4));
        container.setPadding(padding, padding, padding, padding);
        container.addView(input);
        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.game_download_by_code))
                .setMessage(getString(R.string.game_download_by_code_message))
                .setCustomView(container)
                .setPositiveButton(getString(R.string.game_download_download), null)
                .setNegativeButton(getString(R.string.cancel), null);
        dialog.show();
        dialog.getPositiveButton().setOnClickListener(v -> {
            long code;
            try {
                code = Long.parseLong(input.getText().toString().trim());
                if (code <= 0) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                input.setError(getString(R.string.game_download_by_code_invalid));
                return;
            }
            if (!requireSignIn()) return;
            dialog.dismiss();
            resetFilters();
            startDownload(code, null);
        });
    }

    private void startDownload(long code, String name) {
        if (!requireSignIn()) return;
        if (sourceMode == SOURCE_DOWNLOADED) {
            sourceMode = SOURCE_PLAY;
            sources.setSelectedIndex(sourceMode, false);
        }
        downloads.start(code, name);
        mergeTasks();
        applyFilters();
    }

    private void install(GameRelease release) {
        List<java.io.File> files = downloads.downloadedFiles(release.versionCode);
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.game_download_missing_files, Toast.LENGTH_SHORT).show();
            loadLocal();
            return;
        }
        GameDownloads.Downloaded item = downloadedFiles.get(release.versionCode);
        String name = item != null && !TextUtils.isEmpty(item.versionName) ? item.versionName : release.displayName();
        apkImportManager.installDownloadedFiles(files, name);
    }

    private void confirmDelete(GameRelease release) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.game_download_delete_title))
                .setMessage(getString(R.string.game_download_delete_message, release.displayName()))
                .setPositiveButton(getString(R.string.delete), v -> dataExecutor.execute(() -> {
                    boolean deleted = downloads.delete(release.versionCode);
                    runOnUiThread(() -> {
                        if (disposed || isFinishing() || isDestroyed()) return;
                        if (!deleted) Toast.makeText(this, R.string.game_download_delete_failed, Toast.LENGTH_SHORT).show();
                        loadLocal();
                    });
                }))
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    @Override
    public void onTaskChanged(GameDownloads.Task task) {
        if (disposed || task.accountGeneration != accountGeneration || !current(accountGeneration)) return;
        if (task.state == GameDownloads.State.DONE) {
            loadLocal();
            return;
        }
        mergeTasks();
        applyFilters();
    }

    private void applySecondary(MaterialButton button) {
        ColorStateList label = ColorStateList.valueOf(getColor(R.color.text_primary));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.ui_default)));
        button.setTextColor(label);
        button.setIconTint(label);
        personalization.applyGlassToView(button);
    }

    private void styleTag(TextView tag, int color) {
        if (color == 0) {
            UiKit.tintSoftAccent(tag, 6);
            return;
        }
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(UiKit.dp(this, 6));
        background.setColor(UiKit.alpha(color, UiKit.isDark(this) ? 38 : 25));
        tag.setBackground(background);
        tag.setTextColor(color);
    }

    private final class ReleaseAdapter extends RecyclerView.Adapter<ReleaseHolder> {
        @NonNull
        @Override
        public ReleaseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ReleaseHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_game_download, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ReleaseHolder holder, int position) {
            holder.bind(visible.get(position), position == 0);
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
    }

    private final class ReleaseHolder extends RecyclerView.ViewHolder {
        final View divider;
        final TextView name;
        final TextView type;
        final TextView latest;
        final TextView state;
        final TextView meta;
        final MaterialButton menu;
        final MaterialButton action;
        final View progressRow;
        final ProgressBar progressBar;
        final TextView progressText;
        final TextView error;

        ReleaseHolder(View view) {
            super(view);
            divider = view.findViewById(R.id.game_release_divider);
            name = view.findViewById(R.id.game_release_name);
            type = view.findViewById(R.id.game_release_type);
            latest = view.findViewById(R.id.game_release_latest);
            state = view.findViewById(R.id.game_release_state);
            meta = view.findViewById(R.id.game_release_meta);
            menu = view.findViewById(R.id.game_release_menu);
            action = view.findViewById(R.id.game_release_action);
            progressRow = view.findViewById(R.id.game_release_progress_row);
            progressBar = view.findViewById(R.id.game_release_progress);
            progressText = view.findViewById(R.id.game_release_progress_text);
            error = view.findViewById(R.id.game_release_error);
            progressBar.setProgressTintList(accentTint);
            progressBar.setIndeterminateTintList(accentTint);
            styleTag(latest, 0);
            personalization.applyGlassToTree(view, GameDownloadActivity.this);
            TooltipCompat.setTooltipText(menu, getString(R.string.game_download_version_actions));
        }

        void bind(GameRelease release, boolean first) {
            divider.setVisibility(first ? View.GONE : View.VISIBLE);
            GameDownloads.Task task = downloads.task(release.versionCode);
            if (task != null && task.accountGeneration != accountGeneration) task = null;
            GameDownloads.Downloaded item = downloadedFiles.get(release.versionCode);
            String versionName = item != null && !TextUtils.isEmpty(item.versionName) ? item.versionName
                    : task != null && !TextUtils.isEmpty(task.versionName) ? task.versionName : release.versionName;
            GameRelease shown = new GameRelease(release.versionCode, versionName, release.size, release.seenAt);
            shown.channel = release.channel;
            name.setText(shown.displayName());
            type.setVisibility(TextUtils.isEmpty(versionName) ? View.GONE : View.VISIBLE);
            type.setText(shown.isBeta() ? R.string.game_download_type_beta : R.string.game_download_type_release);
            styleTag(type, shown.isBeta() ? UiKit.ORANGE : 0);
            boolean active = task != null && task.isActive();
            boolean failed = task != null && task.state == GameDownloads.State.FAILED;
            boolean installed = !TextUtils.isEmpty(versionName) && installedVersionNames.contains(versionName);
            latest.setVisibility(sourceMode == SOURCE_PLAY && release.versionCode == latestVersionCode
                    && item == null && !installed ? View.VISIBLE : View.GONE);
            state.setVisibility(!active && (installed || item != null) ? View.VISIBLE : View.GONE);
            state.setText(installed ? R.string.game_download_state_installed : R.string.game_download_state_downloaded);
            styleTag(state, installed ? 0 : UiKit.BLUE);
            long size = item != null && item.size > 0 ? item.size : release.size;
            String text = getString(R.string.game_download_version_code_short, release.versionCode);
            if (size > 0) text += " · " + Formatter.formatShortFileSize(GameDownloadActivity.this, size);
            meta.setText(text);
            meta.setContentDescription(getString(R.string.game_download_version_code, release.versionCode));
            progressRow.setVisibility(active ? View.VISIBLE : View.GONE);
            error.setVisibility(failed ? View.VISIBLE : View.GONE);
            menu.setVisibility(!active && (item != null || failed) ? View.VISIBLE : View.GONE);
            action.setEnabled(true);
            if (active) {
                bindProgress(task);
                action.setText(R.string.cancel);
                applySecondary(action);
                action.setOnClickListener(v -> downloads.cancel(release.versionCode));
            } else if (item != null) {
                action.setText(R.string.install);
                UiKit.applyPrimary(action);
                action.setOnClickListener(v -> install(shown));
            } else {
                action.setText(!signedIn ? R.string.game_download_sign_in_short
                        : failed ? R.string.game_download_retry : R.string.game_download_download);
                UiKit.applyPrimary(action);
                action.setOnClickListener(v -> startDownload(release.versionCode, versionName));
            }
            if (failed) error.setText(describe(new PlayException(task.errorKind != null ? task.errorKind : PlayException.Kind.UNKNOWN, "")));
            menu.setOnClickListener(v -> {
                PopupMenu actions = new PopupMenu(GameDownloadActivity.this, menu);
                if (item != null) {
                    if (signedIn) actions.getMenu().add(0, 1, 0, R.string.game_download_redownload);
                    actions.getMenu().add(0, 2, 1, R.string.delete);
                }
                if (failed) actions.getMenu().add(0, 3, 2, R.string.game_download_dismiss_error);
                actions.setOnMenuItemClickListener(option -> {
                    if (option.getItemId() == 1) startDownload(release.versionCode, versionName);
                    else if (option.getItemId() == 2) confirmDelete(shown);
                    else if (option.getItemId() == 3) {
                        downloads.dismiss(release.versionCode);
                        loadLocal();
                    } else return false;
                    return true;
                });
                actions.show();
            });
        }

        private void bindProgress(GameDownloads.Task task) {
            if (task.state == GameDownloads.State.QUEUED || task.state == GameDownloads.State.RESOLVING
                    || task.state == GameDownloads.State.VERIFYING) {
                progressBar.setIndeterminate(true);
                progressText.setText(task.state == GameDownloads.State.QUEUED ? R.string.game_download_queued
                        : task.state == GameDownloads.State.RESOLVING ? R.string.game_download_resolving : R.string.game_download_verifying);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(task.percent());
                progressText.setText(getString(R.string.game_download_progress, task.percent(),
                        Formatter.formatShortFileSize(GameDownloadActivity.this, task.bytesDone),
                        Formatter.formatShortFileSize(GameDownloadActivity.this, task.bytesTotal),
                        Formatter.formatShortFileSize(GameDownloadActivity.this, task.bytesPerSecond)));
            }
        }
    }
}
