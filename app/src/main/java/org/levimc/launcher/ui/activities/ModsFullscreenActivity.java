package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import com.google.android.material.button.MaterialButton;
import androidx.appcompat.widget.TooltipCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.core.app.ActivityOptionsCompat;
import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.FileHandler;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.adapter.ModsAdapter;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.ui.packages.InstalledFragment;
import org.levimc.launcher.ui.packages.PackageActions;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.util.StorageAccess;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.ui.widget.LauncherPopup;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;
import org.levimc.launcher.util.PersonalizationManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModsFullscreenActivity extends BaseActivity {

    private RecyclerView modsRecycler;
    private ModsAdapter modsAdapter;
    private MainViewModel viewModel;
    private TextView subtitle;
    private EditText searchInput;
    private SegmentedControl filterTabs;
    private MaterialButton sortButton;
    private MaterialButton filtersButton;
    private TextView countLabel;
    private TextView emptyTitle;
    private MaterialButton emptyAction;
    private ProgressBar progress;
    private boolean loading = true;
    private int statusFilter;
    private static final int STATUS_ALL = 0;
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 2;
    private View emptyView;
    private TextView emptyText;
    /** Every mod of the instance in load order; the adapter shows the filtered / sorted subset. */
    private final List<Mod> allMods = new ArrayList<>();
    private Map<String, InstalledFragment.Model.Row> pkgRowsByFolder = Collections.emptyMap();
    private static final int TAB_ALL = 0;
    private static final int TAB_NORMAL = 1;
    private static final int TAB_LEVIMC = 2;
    private static final int SORT_NONE = 0;
    private static final int SORT_ASC = 1;
    private static final int SORT_DESC = 2;
    private int sortMode = SORT_NONE;
    private ActivityResultLauncher<Intent> pickModLauncher;
    private FileHandler fileHandler;
    private int lastModsCount = -1;
    /** Cancelled when the screen goes away: the package rows load after the mods. */
    private final Cancel pkgCancel = new Cancel();
    private int pkgRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mods_fullscreen);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            DynamicAnim.applyPressScaleRecursively(root);
        }

        if (savedInstanceState != null) {
            sortMode = bounded(savedInstanceState.getInt("mods_sort", SORT_NONE));
            statusFilter = bounded(savedInstanceState.getInt("mods_status", STATUS_ALL));
        }
        setupViews();
        setupRecyclerView();
        if (savedInstanceState != null) {
            filterTabs.setSelectedIndex(bounded(savedInstanceState.getInt("mods_source", TAB_ALL)), false);
            searchInput.setText(savedInstanceState.getString("mods_query", ""));
        }
        setupViewModel();
        fileHandler = new FileHandler(this, viewModel, VersionManager.get(this));
        // The first applyFilters() runs when the mod list arrives, so the empty state is not shown while loading.

        pickModLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        for (Uri uri : StorageAccess.selectedUris(result.getData())) {
                            StorageAccess.retainReadPermission(this, result.getData(), uri);
                        }
                        fileHandler.processIncomingFilesWithConfirmation(
                                result.getData(), createImportCallback(), true);
                    }
                }
        );
    }

    private void setupViews() {
        MaterialButton addModButton = findViewById(R.id.add_mod_fullscreen_button);
        UiKit.applyPrimary(addModButton);
        addModButton.setOnClickListener(v -> startFilePicker());
        findViewById(R.id.mods_get_button).setOnClickListener(this::showGetModsMenu);
        subtitle = findViewById(R.id.mods_subtitle);
        countLabel = findViewById(R.id.mods_count);
        progress = findViewById(R.id.mods_progress);
        progress.setIndeterminateTintList(ColorStateList.valueOf(UiKit.accent(this)));
        emptyView = findViewById(R.id.mods_empty);
        emptyText = findViewById(R.id.mods_empty_text);
        emptyTitle = findViewById(R.id.mods_empty_title);
        emptyAction = findViewById(R.id.mods_empty_action);
        UiKit.applyPrimary(emptyAction);
        emptyAction.setOnClickListener(v -> resetFilters());
        searchInput = findViewById(R.id.mods_search);
        searchInput.setSaveEnabled(false);
        searchInput.addTextChangedListener(SimpleTextWatcher.after(value -> applyFilters()));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH && actionId != EditorInfo.IME_ACTION_DONE) return false;
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(v.getWindowToken(), 0);
            searchInput.clearFocus();
            return true;
        });
        filtersButton = findViewById(R.id.mods_filters_button);
        filtersButton.setOnClickListener(this::showFilters);
        filterTabs = findViewById(R.id.mods_filter_tabs);
        filterTabs.setEqualWidth(false);
        filterTabs.setItems(getString(R.string.mods_filter_all), getString(R.string.mods_filter_normal),
                getString(R.string.pkg_title_mods));
        filterTabs.setSelectedIndex(TAB_ALL, false);
        filterTabs.setOnSegmentSelectedListener(i -> applyFilters());
        sortButton = findViewById(R.id.mods_sort_button);
        sortButton.setOnClickListener(this::showSortMenu);
        updateSortHeader();
        View refresh = findViewById(R.id.mods_refresh_button);
        TooltipCompat.setTooltipText(refresh, getString(R.string.mods_refresh));
        refresh.setOnClickListener(v -> {
            if (viewModel == null) return;
            loading = true;
            applyFilters();
            viewModel.reloadMods();
        });
        View root = findViewById(android.R.id.content);
        if (root != null) new PersonalizationManager(this).applyAccentToView(root, this);
    }

    /** "Get mods": LeviMC mods (package browser) or the external mod catalog. */
    private void showGetModsMenu(View anchor) {
        new LauncherPopup(anchor)
                .section(getString(R.string.mods_get_mods))
                .action(1, getString(R.string.pkg_title_mods), R.drawable.ic_pkg_package)
                .action(2, getString(R.string.external_mods), R.drawable.ic_ui_cloud_download)
                .onSelect(id -> {
                    Intent intent = id == 1
                            ? org.levimc.launcher.ui.packages.PackagesActivity.intent(this, VersionManager.get(this).getSelectedVersion(), null)
                            : new Intent(this, ExternalModsActivity.class);
                    startActivity(intent, ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out).toBundle());
                }).show();
    }

    private void updateSortHeader() {
        int label = sortMode == SORT_ASC ? R.string.mods_sort_name_asc
                : sortMode == SORT_DESC ? R.string.mods_sort_name_desc : R.string.mods_sort_load_order;
        sortButton.setContentDescription(getString(R.string.mods_sort) + ": " + getString(label));
        TooltipCompat.setTooltipText(sortButton, getString(label));
    }

    private void showSortMenu(View anchor) {
        new LauncherPopup(anchor)
                .section(getString(R.string.mods_sort))
                .choice(SORT_NONE, getString(R.string.mods_sort_load_order), sortMode == SORT_NONE)
                .choice(SORT_ASC, getString(R.string.mods_sort_name_asc), sortMode == SORT_ASC)
                .choice(SORT_DESC, getString(R.string.mods_sort_name_desc), sortMode == SORT_DESC)
                .onSelect(id -> {
                    sortMode = id;
                    updateSortHeader();
                    applyFilters();
                }).show();
    }

    private void showFilters(View anchor) {
        new LauncherPopup(anchor).width(300)
                .group(getString(R.string.mods_status), new int[]{100, 101, 102},
                        new CharSequence[]{getString(R.string.mods_status_all), getString(R.string.mods_status_enabled),
                                getString(R.string.mods_status_disabled)}, statusFilter)
                .divider().action(300, getString(R.string.mods_clear_filters), R.drawable.ic_close)
                .onSelect(id -> {
                    if (id >= 100 && id < 103) statusFilter = id - 100;
                    else if (id == 300) resetFilters();
                    applyFilters();
                }).show();
    }

    private void resetFilters() {
        statusFilter = STATUS_ALL;
        sortMode = SORT_NONE;
        searchInput.setText("");
        updateSortHeader();
        applyFilters();
    }

    private static int bounded(int value) {
        return value >= 0 && value <= 2 ? value : 0;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("mods_sort", sortMode);
        outState.putInt("mods_status", statusFilter);
        outState.putInt("mods_source", filterTabs.getSelectedIndex());
        outState.putString("mods_query", searchInput.getText().toString());
    }

    /** A mod folder a LeviMC (lip) package owns. */
    private boolean isLeviMcMod(Mod mod) {
        return pkgRowsByFolder.containsKey(InstalledFragment.Model.folderKey(mod.getId()));
    }

    /** Shows the mods matching the search, the "only enabled" box and the source tab, in the chosen order. */
    private void applyFilters() {
        if (modsAdapter == null || filterTabs == null) return;
        String query = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        int tab = filterTabs.getSelectedIndex();
        List<Mod> shown = new ArrayList<>();
        int categoryCount = 0;
        for (Mod mod : allMods) {
            if (tab == TAB_NORMAL && isLeviMcMod(mod)) continue;
            if (tab == TAB_LEVIMC && !isLeviMcMod(mod)) continue;
            categoryCount++;
            if (statusFilter == STATUS_ENABLED && !mod.isEnabled()) continue;
            if (statusFilter == STATUS_DISABLED && mod.isEnabled()) continue;
            if (!query.isEmpty() && !matches(mod, query)) continue;
            shown.add(mod);
        }
        if (sortMode != SORT_NONE) {
            Comparator<Mod> byName = Comparator.comparing(m -> lower(m.getDisplayName()));
            shown.sort(sortMode == SORT_ASC ? byName : byName.reversed());
        }
        boolean filtered = !query.isEmpty() || statusFilter != STATUS_ALL || tab != TAB_ALL || sortMode != SORT_NONE;
        modsAdapter.setReorderEnabled(!filtered && !loading);
        modsAdapter.updateMods(shown);
        countLabel.setText(getResources().getQuantityString(R.plurals.mods_visible_count, shown.size(), shown.size()));
        filtersButton.setText(statusFilter == STATUS_ALL ? getString(R.string.mods_filters)
                : getString(R.string.mods_filters_active, 1));
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.mods_refresh_button).setEnabled(!loading);
        boolean empty = shown.isEmpty();
        modsRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        int title = R.string.mods_empty_filtered;
        int help = R.string.mods_empty_filter_help;
        if (categoryCount == 0) {
            title = tab == TAB_LEVIMC ? R.string.mods_empty_levimc
                    : tab == TAB_NORMAL ? R.string.mods_empty_normal : R.string.mods_empty;
            help = tab == TAB_LEVIMC ? R.string.mods_empty_levimc_help
                    : tab == TAB_NORMAL ? R.string.mods_empty_normal_help : R.string.mods_empty_help;
        }
        emptyTitle.setText(loading ? R.string.mods_loading : title);
        emptyText.setText(loading ? "" : getString(help));
        boolean hasFilters = !query.isEmpty() || statusFilter != STATUS_ALL;
        emptyAction.setVisibility(!loading && categoryCount > 0 && hasFilters ? View.VISIBLE : View.GONE);
        emptyAction.setText(R.string.mods_clear_filters);
    }

    private static boolean matches(Mod mod, String query) {
        return lower(mod.getDisplayName()).contains(query)
                || lower(mod.getId()).contains(query)
                || lower(mod.getAuthor()).contains(query);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void startFilePicker() {
        pickModLauncher.launch(StorageAccess.downloadsPicker(true));
    }

    private FileHandler.FileOperationCallback createImportCallback() {
        return new FileHandler.FileOperationCallback() {
            @Override
            public void onSuccess(int processedFiles) {
                Toast.makeText(ModsFullscreenActivity.this,
                        getString(R.string.files_processed, processedFiles), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(ModsFullscreenActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgressUpdate(int progress) {
            }
        };
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication())).get(MainViewModel.class);

        org.levimc.launcher.core.versions.GameVersion selectedVersion = VersionManager.get(this).getSelectedVersion();
        if (selectedVersion != null) {
            viewModel.setCurrentVersion(selectedVersion);
        }

        viewModel.getModsLiveData().observe(this, this::updateModsUI);
    }

    private void setupRecyclerView() {
        modsRecycler = findViewById(R.id.mods_recycler_fullscreen);
        modsAdapter = new ModsAdapter(new ArrayList<>());
        modsRecycler.setLayoutManager(new LinearLayoutManager(this));
        modsRecycler.setAdapter(modsAdapter);
        if (modsRecycler.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) modsRecycler.getItemAnimator()).setSupportsChangeAnimations(false);
        }
        modsAdapter.setOnModClickListener((mod, position, sharedView) -> {
            Intent intent = new Intent(this, ModDetailActivity.class);
            intent.putExtra("mod_filename", mod.getId());
            intent.putExtra("mod_position", position);
            startActivity(intent);
        });
        modsAdapter.setOnPkgUpdateClickListener((mod, row) -> {
            GameVersion version = currentVersion();
            if (version != null) PackageActions.update(this, version, row, pkgDone(null));
        });
        modsAdapter.setOnModUninstallListener(mod -> {
            GameVersion version = currentVersion();
            if (version != null) PackageActions.deleteModFolder(this, version, mod, pkgDone(mod));
        });
        modsAdapter.setOnModEnableChangeListener((mod, enabled) -> {
            if (viewModel == null) return;
            viewModel.setModEnabled(mod.getId(), enabled);
            updateModsCount();
            applyFilters();
        });
        modsAdapter.setOnModReorderListener(reorderedMods -> {
            if (viewModel == null) return;
            allMods.clear();
            allMods.addAll(reorderedMods);
            viewModel.reorderMods(reorderedMods);
            Toast.makeText(this, R.string.mod_reordered, Toast.LENGTH_SHORT).show();
        });
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            private RecyclerView.ViewHolder reorderedHolder;

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public int getDragDirs(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
                return modsAdapter.isReorderEnabled() ? super.getDragDirs(recycler, holder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int start = from.getBindingAdapterPosition();
                int end = to.getBindingAdapterPosition();
                if (!modsAdapter.isReorderEnabled() || start < 0 || end < 0 || start == end
                        || start >= modsAdapter.getItemCount() || end >= modsAdapter.getItemCount()) return false;
                modsAdapter.moveItem(start, end);
                reorderedHolder = from;
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
                super.clearView(recycler, holder);
                if (holder == reorderedHolder) {
                    if (modsAdapter.isReorderEnabled()) modsAdapter.commitReorder();
                    reorderedHolder = null;
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                int position = holder.getBindingAdapterPosition();
                if (position < 0 || position >= modsAdapter.getItemCount()) return;
                Mod mod = modsAdapter.getItem(position);
                GameVersion version = currentVersion();
                if (version == null) {
                    modsAdapter.notifyItemChanged(position);
                    return;
                }
                PackageActions.deleteModFolder(ModsFullscreenActivity.this, version, mod, pkgDone(mod));
            }
        };
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(modsRecycler);
        modsAdapter.setItemTouchHelper(touchHelper);
    }

    /** The instance whose mods are listed (the one the mod manager is bound to). */
    private GameVersion currentVersion() {
        GameVersion v = ModManager.getInstance().getCurrentVersion();
        return v != null ? v : VersionManager.get(this).getSelectedVersion();
    }

    /**
     * After a package action: puts a swiped row back (found by its folder: the
     * list may have changed while the action asked and ran; the reload
     * drops it when it is gone) and reloads the mods. Holds the activity
     * weakly, since the action's task may outlive it.
     */
    private PackageActions.Done pkgDone(Mod swiped) {
        WeakReference<ModsFullscreenActivity> self = new WeakReference<>(this);
        String swipedId = swiped == null ? null : swiped.getId();
        return changed -> {
            ModsFullscreenActivity a = self.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) {
                return;
            }
            if (swipedId != null && a.modsAdapter != null) {
                for (int i = 0; i < a.modsAdapter.getItemCount(); i++) {
                    if (swipedId.equals(a.modsAdapter.getItem(i).getId())) {
                        a.modsAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
            if (changed && a.viewModel != null) {
                a.viewModel.reloadMods();
            }
        };
    }

    /** Marks the folders packages own (badge, "Update to X") once their packages are read. */
    private void loadPkgRows(List<Mod> mods) {
        int request = ++pkgRequest;
        InstalledFragment.Loader.loadForMods(pkgCancel, currentVersion(), mods, rows -> {
            if (request == pkgRequest && modsAdapter != null && !isFinishing() && !isDestroyed()) {
                pkgRowsByFolder = InstalledFragment.Model.byFolder(rows);
                modsAdapter.setPkgRows(pkgRowsByFolder);
                if (filterTabs != null && filterTabs.getSelectedIndex() != TAB_ALL) {
                    applyFilters();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        pkgCancel.cancel();
        super.onDestroy();
    }

    private void updateModsUI(List<Mod> mods) {
        if (modsAdapter != null) {
            loading = false;
            allMods.clear();
            if (mods != null) allMods.addAll(mods);
            applyFilters();
            loadPkgRows(mods);
            updateModsCount();
            if (modsRecycler != null) {
                int count = (mods != null) ? mods.size() : 0;
                if (lastModsCount == -1 || count != lastModsCount) {
                    modsRecycler.post(() -> DynamicAnim.staggerRecyclerChildren(modsRecycler));
                }
                lastModsCount = count;
            }
        }
    }

    private void updateModsCount() {
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        
        int total = (mods != null ? mods.size() : 0);
        int enabled = 0;
        
        if (mods != null) {
            for (Mod mod : mods) {
                if (mod.isEnabled()) {
                    enabled++;
                }
            }
        }

        GameVersion version = currentVersion();
        String instance = version == null ? null
                : (version.displayName != null && !version.displayName.trim().isEmpty()
                        ? version.displayName : version.directoryName);
        subtitle.setText(instance == null
                ? getString(R.string.mods_subtitle_counts, total, enabled)
                : getString(R.string.mods_subtitle, instance, total, enabled));
        subtitle.setContentDescription(subtitle.getText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewModel != null) {
            viewModel.refreshMods();
        }
        updateModsCount();
    }

}
