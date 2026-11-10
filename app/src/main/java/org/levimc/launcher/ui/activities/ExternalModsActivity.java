package org.levimc.launcher.ui.activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.PopupMenu;
import androidx.core.widget.CompoundButtonCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.modcatalog.ModCatalog;
import org.levimc.launcher.core.modcatalog.ModCatalogInstaller;
import org.levimc.launcher.core.modcatalog.ModCatalogRepository;
import org.levimc.launcher.core.mods.FileHandler;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.adapter.ExternalModsAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.InstallProgressDialog;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.UiSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ExternalModsActivity extends BaseActivity {
    private static final String COMMUNITY_DISCORD_URL = "https://discord.gg/rMgdpTFFVg";
    private final List<ModCatalog.CatalogMod> allMods = new ArrayList<>();
    private ExternalModsAdapter adapter;
    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView empty;
    private TextView status;
    private EditText search;
    private CompoundButton compatibleFilter;
    private MaterialButton sort;
    private MaterialButton discord;
    private MaterialButton refresh;
    private static final int SORT_NEWEST = 0;
    private static final int SORT_NAME = 1;
    private int sortMode = SORT_NEWEST;
    private ModCatalogInstaller installer;
    private InstallProgressDialog installDialog;
    private MainViewModel viewModel;
    private String minecraftVersion = "";
    private boolean compatibleOnly;
    private boolean installing;
    private boolean refreshFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(UiSettings.useTwoPane(this)
                ? R.layout.activity_external_mods : R.layout.activity_external_mods_stacked);

        VersionManager versionManager = VersionManager.get(this);
        GameVersion selectedVersion = versionManager.getSelectedVersion();
        if (selectedVersion != null && selectedVersion.versionCode != null) {
            minecraftVersion = selectedVersion.versionCode;
        }

        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication()))
                .get(MainViewModel.class);
        if (selectedVersion != null) viewModel.setCurrentVersion(selectedVersion);
        installer = new ModCatalogInstaller(this, new FileHandler(this, viewModel, versionManager));

        bindViews();
        setupRecycler();
        setupControls();
        setupFilters();

        TextView subtitle = findViewById(R.id.external_mods_subtitle);
        subtitle.setText(minecraftVersion.isEmpty()
                ? getString(R.string.external_mods_no_version)
                : getString(R.string.external_mods_subtitle, minecraftVersion));

        viewModel.getModsLiveData().observe(this, ignored -> updateInstalledMods());
        ModCatalogRepository.loadCached(this, (catalog, error) -> renderCatalog(catalog));
        refreshCatalog(false);
        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
        new PersonalizationManager(this).applyAccentToView(findViewById(android.R.id.content), this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateInstalledMods();
    }

    private void bindViews() {
        recycler = findViewById(R.id.external_mods_recycler);
        progress = findViewById(R.id.external_mods_progress);
        empty = findViewById(R.id.external_mods_empty);
        status = findViewById(R.id.external_mods_status);
        search = findViewById(R.id.external_mods_search);
        compatibleFilter = findViewById(R.id.external_mods_compatible_filter);
        sort = findViewById(R.id.external_mods_sort);
        discord = findViewById(R.id.external_mods_discord);
        refresh = findViewById(R.id.external_mods_refresh);
    }

    private void setupRecycler() {
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExternalModsAdapter(minecraftVersion, this::handleModAction);
        recycler.setAdapter(adapter);
    }

    private void setupControls() {
        discord.setOnClickListener(v -> openCommunityDiscord());
        refresh.setOnClickListener(v -> refreshCatalog(true));
        DynamicAnim.applyPressScale(discord);
        DynamicAnim.applyPressScale(refresh);
        DynamicAnim.applyPressScale(sort);
        // PersonalizationManager re-tints switches and buttons, not checkboxes.
        int[][] states = {{android.R.attr.state_checked}, {}};
        CompoundButtonCompat.setButtonTintList(compatibleFilter, new ColorStateList(states,
                new int[]{UiKit.accent(this), UiKit.secondaryText(this)}));
    }

    private void openCommunityDiscord() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY_DISCORD_URL)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.external_mods_discord_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupFilters() {
        compatibleFilter.setEnabled(!minecraftVersion.isEmpty());
        compatibleOnly = !minecraftVersion.isEmpty();
        compatibleFilter.setChecked(compatibleOnly);
        compatibleFilter.setOnCheckedChangeListener((button, checked) -> {
            compatibleOnly = checked;
            applyFilters();
        });

        updateSortText();
        sort.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add(0, SORT_NEWEST, 0, R.string.external_mods_sort_newest);
            menu.getMenu().add(0, SORT_NAME, 1, R.string.external_mods_sort_name);
            menu.setOnMenuItemClickListener(item -> {
                sortMode = item.getItemId();
                updateSortText();
                applyFilters();
                return true;
            });
            menu.show();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void updateSortText() {
        sort.setText(getString(R.string.mods_external_sort, getString(sortMode == SORT_NAME
                ? R.string.external_mods_sort_name
                : R.string.external_mods_sort_newest)));
    }

    private void refreshCatalog(boolean userRequested) {
        refresh.setEnabled(false);
        if (allMods.isEmpty()) progress.setVisibility(View.VISIBLE);
        ModCatalogRepository.refresh(this, (catalog, error) -> {
            refresh.setEnabled(true);
            progress.setVisibility(View.GONE);
            refreshFailed = error != null;
            renderCatalog(catalog);
            if (userRequested && error == null) {
                Toast.makeText(this, R.string.external_mods_refreshed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void renderCatalog(ModCatalog catalog) {
        allMods.clear();
        if (catalog != null && catalog.mods != null) allMods.addAll(catalog.mods);
        if (adapter != null && catalog != null) adapter.setGithubProxy(catalog.githubProxy);
        progress.setVisibility(View.GONE);
        applyFilters();
    }

    private void applyFilters() {
        if (adapter == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean filterCompatibleOnly = compatibleOnly && !minecraftVersion.isEmpty();
        List<ModCatalog.CatalogMod> filtered = new ArrayList<>();
        for (ModCatalog.CatalogMod mod : allMods) {
            if (filterCompatibleOnly && mod.compatibleRelease(minecraftVersion) == null) continue;
            if (!query.isEmpty() && !searchableText(mod).contains(query)) continue;
            filtered.add(mod);
        }

        if (sortMode == SORT_NAME) {
            filtered.sort(Comparator.comparing(mod -> value(mod.name).toLowerCase(Locale.ROOT)));
        } else {
            filtered.sort(Comparator.comparing(
                    (ModCatalog.CatalogMod mod) -> publishedAt(mod)).reversed());
        }

        adapter.submit(filtered);
        empty.setVisibility(filtered.isEmpty() && progress.getVisibility() != View.VISIBLE
                ? View.VISIBLE : View.GONE);
        recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
        updateStatus();
        recycler.post(() -> DynamicAnim.staggerRecyclerChildren(recycler));
    }

    private void handleModAction(ModCatalog.CatalogMod mod, ModCatalog.CatalogRelease release) {
        if (release.opensInBrowser()) {
            showExternalDownloadDialog(mod, release);
            return;
        }
        List<ModCatalog.CatalogAsset> assets = release.directAssets();
        if (assets.size() == 1) {
            installDirect(mod, release, assets.get(0));
        } else if (assets.size() > 1) {
            showAssetChooser(mod, release, assets);
        }
    }

    private void showAssetChooser(ModCatalog.CatalogMod mod, ModCatalog.CatalogRelease release,
                                  List<ModCatalog.CatalogAsset> assets) {
        String[] items = new String[assets.size()];
        for (int index = 0; index < assets.size(); index++) {
            items[index] = assetDisplayText(assets.get(index));
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.external_mods_choose_download))
                .setItems(items, (dialog, which) -> installDirect(mod, release, assets.get(which)))
                .setNegativeButton(getString(R.string.cancel), null)
                .setUseBorderedBackground(true)
                .setBlurBackground(true)
                .show();
    }

    private String assetDisplayText(ModCatalog.CatalogAsset asset) {
        String name = value(asset.name);
        String label = value(asset.label);
        String text = label.isEmpty() || label.equals(name) ? name : label + " — " + name;
        if (asset.size > 0) {
            text += " • " + Formatter.formatShortFileSize(this, asset.size);
        }
        return text;
    }

    private void installDirect(ModCatalog.CatalogMod mod, ModCatalog.CatalogRelease release,
                               ModCatalog.CatalogAsset asset) {
        if (installing) return;
        installing = true;
        adapter.setBusyModId(mod.id);
        installDialog = new InstallProgressDialog(this);
        installDialog.setTitleText(getString(R.string.external_mods_download, mod.name));
        installDialog.setStatusText(getString(R.string.external_mods_download_file,
                release.version, value(asset.name)));
        installDialog.show();
        installDialog.setProgress(0);

        installer.install(mod, release, asset, new ModCatalogInstaller.Callback() {
            @Override
            public void onProgress(int value, boolean importing) {
                if (installDialog == null) return;
                installDialog.setProgress(value);
                if (importing) installDialog.setStatusText(getString(R.string.external_mods_importing));
            }

            @Override
            public void onSuccess() {
                finishInstallState();
                updateInstalledMods();
                Toast.makeText(ExternalModsActivity.this,
                        getString(R.string.external_mods_installed, mod.name), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                finishInstallState();
                Toast.makeText(ExternalModsActivity.this,
                        getString(R.string.external_mods_install_failed, mod.name, message),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showExternalDownloadDialog(ModCatalog.CatalogMod mod,
                                            ModCatalog.CatalogRelease release) {
        boolean adDownload = release.isAdDownload();
        new CustomAlertDialog(this)
                .setTitleText(getString(adDownload
                        ? R.string.external_mods_ad_dialog_title
                        : R.string.external_mods_external_dialog_title))
                .setMessage(getString(adDownload
                                ? R.string.external_mods_ad_dialog_message
                                : R.string.external_mods_external_dialog_message,
                        value(mod.name)))
                .setPositiveButton(getString(R.string.external_mods_open_link),
                        v -> launchExternalDownload(release))
                .setNegativeButton(getString(R.string.cancel), null)
                .setUseBorderedBackground(true)
                .setBlurBackground(true)
                .show();
    }

    private void launchExternalDownload(ModCatalog.CatalogRelease release) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.external_mods_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishInstallState() {
        installing = false;
        adapter.setBusyModId(null);
        dismissInstallDialog();
    }

    private void dismissInstallDialog() {
        if (installDialog != null && installDialog.isShowing()) installDialog.dismiss();
        installDialog = null;
    }

    private void updateInstalledMods() {
        if (adapter != null) adapter.setInstalledMods(ModManager.getInstance().getMods());
    }

    private void updateStatus() {
        if (minecraftVersion.isEmpty()) {
            status.setText(R.string.external_mods_no_version);
            status.setVisibility(View.VISIBLE);
        } else if (refreshFailed) {
            status.setText(R.string.external_mods_load_failed);
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
    }

    private String searchableText(ModCatalog.CatalogMod mod) {
        StringBuilder text = new StringBuilder();
        text.append(value(mod.name)).append(' ')
                .append(value(mod.author)).append(' ')
                .append(value(mod.description)).append(' ');
        if (mod.tags != null) text.append(TextUtils.join(" ", mod.tags)).append(' ');
        if (mod.releases != null) {
            for (ModCatalog.CatalogRelease release : mod.releases) {
                text.append(value(release.version)).append(' ');
                if (release.minecraftVersions != null) {
                    text.append(TextUtils.join(" ", release.minecraftVersions)).append(' ');
                }
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private String publishedAt(ModCatalog.CatalogMod mod) {
        ModCatalog.CatalogRelease release = mod.latestRelease();
        return release == null ? "" : value(release.publishedAt);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
