package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogFacets;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.Readme;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.widget.CatalogSelect;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.UIHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BrowseFragment extends Fragment implements PackagesActivity.TargetListener {
    private static final List<String> SORT_KEYS = Catalog.Search.SORTS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BrowseFragment.Filters filters = new BrowseFragment.Filters();
    private Cancel ctx = new Cancel();

    private EditText searchBox;
    private MaterialButton refreshButton;
    private CatalogSelect gameSelect;
    private CatalogSelect loaderSelect;
    private CatalogSelect sortSelect;
    private CatalogSelect orderSelect;
    private MaterialButton filtersButton;
    private View activeFiltersScroll;
    private ChipGroup activeFilters;
    private TextView mappingHint;
    private TextView staleBanner;
    private RecyclerView results;
    private View emptyView;
    private TextView emptyText;
    private MaterialButton emptyAction;
    private ProgressBar loading;
    private BrowseFragment.Adapter adapter;

    private List<CatalogModels.Pkg> packages = Collections.emptyList();
    @Nullable
    private Catalog.Search.Result result;
    private boolean loadingCatalog;
    private boolean catalogFailed;
    private int page = 1;
    private int catalogRequest;
    private int contextRequest;
    private boolean resumedOnce;

    /** Opens a package page; its "More by owner" link comes back here as the owner to list. */
    private final ActivityResultLauncher<Intent> openPackage = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                Intent data = r.getData();
                String owner = r.getResultCode() == Activity.RESULT_OK && data != null
                        ? data.getStringExtra(PackageActivity.RESULT_AUTHOR) : null;
                if (owner != null && !owner.trim().isEmpty()) {
                    showAuthor(owner.trim());
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_pkg_browse, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ctx = new Cancel();
        searchBox = view.findViewById(R.id.pkg_search_box);
        MaterialButton searchButton = view.findViewById(R.id.pkg_btn_search);
        refreshButton = view.findViewById(R.id.pkg_btn_refresh);
        MaterialButton guideButton = view.findViewById(R.id.pkg_btn_guide);
        gameSelect = new CatalogSelect(view.findViewById(R.id.pkg_spinner_game), getString(R.string.pkg_game_version_label));
        loaderSelect = new CatalogSelect(view.findViewById(R.id.pkg_spinner_ll), getString(R.string.pkg_ll_version_label));
        sortSelect = new CatalogSelect(view.findViewById(R.id.pkg_spinner_sort), getString(R.string.pkg_sort_by));
        orderSelect = new CatalogSelect(view.findViewById(R.id.pkg_spinner_order), getString(R.string.catalog_order));
        filtersButton = view.findViewById(R.id.pkg_btn_filters);
        activeFiltersScroll = view.findViewById(R.id.pkg_active_filters_scroll);
        activeFilters = view.findViewById(R.id.pkg_active_filters);
        mappingHint = view.findViewById(R.id.pkg_mapping_hint);
        staleBanner = view.findViewById(R.id.pkg_stale_banner);
        results = view.findViewById(R.id.pkg_results);
        emptyView = view.findViewById(R.id.pkg_empty);
        emptyText = view.findViewById(R.id.pkg_empty_text);
        emptyAction = view.findViewById(R.id.pkg_empty_action);
        loading = view.findViewById(R.id.pkg_loading);

        UiKit.applyPrimary(searchButton);

        adapter = new BrowseFragment.Adapter(new Adapter.Listener() {
            @Override
            public void onPackageClick(CatalogModels.Pkg pkg) {
                BrowseFragment.this.onPackageClick(pkg);
            }

            @Override
            public void onOpenProject(CatalogModels.Pkg pkg) {
                BrowseFragment.this.onOpenProject(pkg);
            }

            @Override
            public void onInstall(CatalogModels.Pkg pkg) {
                BrowseFragment.this.onInstallClick(pkg);
            }

            @Override
            public void onOpenAuthor(CatalogModels.Pkg pkg) {
                PkgUi.openUrl(requireContext(), pkg.ownerUrl());
            }

            @Override
            public void onPage(int page) {
                BrowseFragment.this.onPage(page);
            }
        }, PackageService.rid());
        results.setLayoutManager(new LinearLayoutManager(requireContext()));
        results.setItemAnimator(null);
        results.setAdapter(adapter);

        searchButton.setOnClickListener(v -> runSearch());
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                runSearch();
                return true;
            }
            return false;
        });
        refreshButton.setOnClickListener(v -> {
            page = 1;
            loadCatalog(true);
            retryFilterContextIfUnavailable();
        });
        guideButton.setOnClickListener(v -> BrowseFragment.DevGuideDialog.show(requireActivity()));
        filtersButton.setOnClickListener(v -> FiltersSheet.show(this));

        setupSortSpinner();
        setupVersionSpinners();

        PackagesActivity host = host();
        if (host != null) {
            host.bindHeader(view, 0);
            host.addPkgTargetListener(this);
        }
        loadCatalog(false);
        loadFilterContext();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The installed LeviLamina may have changed elsewhere (Loader settings, a package install):
        // re-read the filter context, as upstream does on every instance snapshot change.
        if (resumedOnce) {
            loadFilterContext();
        }
        resumedOnce = true;
    }

    @Override
    public void onDestroyView() {
        PackagesActivity host = host();
        if (host != null) {
            host.removePkgTargetListener(this);
        }
        catalogRequest++;
        contextRequest++;
        ctx.cancel();
        main.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    @Nullable
    private PackagesActivity host() {
        return getActivity() instanceof PackagesActivity ? (PackagesActivity) getActivity() : null;
    }

    @Nullable
    private GameVersion target() {
        PackagesActivity host = host();
        return host == null ? null : host.getPkgTarget();
    }

    @Override
    public void onPkgTargetChanged(@Nullable GameVersion target) {
        PackagesActivity host = host();
        if (host != null && getView() != null) {
            host.renderTargetTag(getView().findViewById(R.id.pkg_header_target));
        }
        loadFilterContext();
    }

    // #region Controls

    private void runSearch() {
        filters.setQuery(searchBox.getText().toString());
        page = 1;
        UIHelper.hideKeyboard(requireActivity());
        searchBox.clearFocus();
        render();
    }

    private void setupSortSpinner() {
        List<String> sortLabels = java.util.Arrays.asList(getString(R.string.pkg_sort_relevance),
                getString(R.string.pkg_sort_stars), getString(R.string.pkg_sort_updated),
                getString(R.string.pkg_sort_newest));
        sortSelect.setItems(sortLabels, Math.max(0, SORT_KEYS.indexOf(filters.sort())));
        sortSelect.setOnPick(position -> {
            String key = SORT_KEYS.get(position);
            if (key.equals(filters.sort())) {
                return;
            }
            filters.setSort(key);
            page = 1;
            render();
        });
        orderSelect.setItems(java.util.Arrays.asList(getString(R.string.catalog_order_desc),
                getString(R.string.catalog_order_asc)), filters.descending() ? 0 : 1);
        orderSelect.setOnPick(position -> {
            filters.setDescending(position == 0);
            page = 1;
            render();
        });
    }

    private void setupVersionSpinners() {
        gameSelect.setOnPick(position -> {
            String game = position == 0 ? BrowseFragment.Filters.ALL : filters.gameOptions().get(position - 1);
            if (game.equals(filters.selectedGame())) {
                return;
            }
            filters.selectGame(game);
            page = 1;
            bindVersionSpinners();
            render();
        });
        loaderSelect.setOnPick(position -> {
            String ll = position == 0 ? BrowseFragment.Filters.ALL : filters.loaderOptions().get(position - 1);
            if (ll.equals(filters.selectedLoader())) {
                return;
            }
            filters.selectLoader(ll);
            page = 1;
            render();
        });
        bindVersionSpinners();
    }

    private void bindVersionSpinners() {
        if (gameSelect == null) {
            return;
        }
        List<String> games = new ArrayList<>();
        games.add(getString(R.string.pkg_game_all_versions));
        games.addAll(filters.gameOptions());
        List<String> lls = new ArrayList<>();
        lls.add(getString(R.string.pkg_ll_all_versions));
        lls.addAll(filters.loaderOptions());
        gameSelect.setItems(games, filters.gameOptions().indexOf(filters.selectedGame()) + 1);
        loaderSelect.setItems(lls, filters.loaderOptions().indexOf(filters.selectedLoader()) + 1);
        // The game filter needs the catalogue's game versions; only the LeviLamina filter needs the database.
        gameSelect.setEnabled(!filters.gameOptions().isEmpty());
        boolean mapping = filters.mappingAvailable();
        loaderSelect.setEnabled(mapping);
        mappingHint.setVisibility(filters.contextReady() && !mapping ? View.VISIBLE : View.GONE);
    }

    // #endregion

    // #region Loading

    private void loadCatalog(boolean refresh) {
        int request = ++catalogRequest;
        loadingCatalog = true;
        refreshButton.setEnabled(false);
        render();
        Cancel c = ctx;
        PackageService.EXECUTOR.execute(() -> {
            Catalog.Result result = null;
            PackageService.Failure error = null;
            try {
                result = Catalog.get().load(c, refresh);
            } catch (PackageService.Failure e) {
                error = e;
                PackageService.warn("catalogue: load failed: " + PackageService.message(e));
            } catch (RuntimeException e) {
                error = new PackageService.Failure(PackageService.ERR_PKG_INDEX_UNAVAILABLE, String.valueOf(e), null);
                PackageService.warn("catalogue: load failed", e);
            }
            Catalog.Result r = result;
            PackageService.Failure err = error;
            main.post(() -> onCatalog(request, r, err));
        });
    }

    private void onCatalog(int request, @Nullable Catalog.Result result, @Nullable PackageService.Failure error) {
        if (request != catalogRequest || getView() == null) {
            return;
        }
        loadingCatalog = false;
        refreshButton.setEnabled(true);
        if (result != null) {
            packages = result.index.packages();
            catalogFailed = false;
            String prevGame = filters.selectedGame();
            filters.setGameOptions(Catalog.Search.options(packages).gv);
            bindVersionSpinners();
            if (!prevGame.equals(filters.selectedGame())) {
                page = 1;
            }
            if (result.stale) {
                String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                        getResources().getConfiguration().getLocales().get(0)).format(new Date(result.fetchedAtMs));
                staleBanner.setText(getString(R.string.pkg_catalog_stale, when));
                staleBanner.setVisibility(View.VISIBLE);
            } else {
                staleBanner.setVisibility(View.GONE);
            }
        } else {
            catalogFailed = true;
            staleBanner.setVisibility(View.GONE);
            if (!packages.isEmpty() && error != null && getContext() != null) {
                android.widget.Toast.makeText(requireContext(),
                        getString(R.string.pkg_operation_failed, PkgUi.ErrorText.describe(requireContext(),
                                PackageService.code(error), PackageService.message(error))),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        render();
        results.scrollToPosition(0);
    }

    private void loadFilterContext() {
        int request = ++contextRequest;
        GameVersion target = target();
        Cancel c = ctx;
        PackageService.EXECUTOR.execute(() -> {
            LeviLamina.Db db = null;
            try {
                db = LeviLamina.DbClient.get().load(c, false).db;
            } catch (PackageService.Failure | RuntimeException e) {
                PackageService.warn("catalogue: LeviLamina database unavailable: " + e.getMessage());
            }
            String installedLL = "";
            if (target != null) {
                try {
                    CatalogModels.InstallState s = PackageService.get().installState(target,
                            PackageService.LEVILAMINA_PACKAGE_ID);
                    if (s.installed) {
                        installedLL = s.installedVersion;
                    }
                } catch (RuntimeException e) {
                    PackageService.warn("catalogue: LeviLamina state unavailable", e);
                }
            }
            LeviLamina.Db d = db;
            String ll = installedLL;
            main.post(() -> {
                if (request != contextRequest || getView() == null) {
                    return;
                }
                String prevGame = filters.selectedGame();
                String prevLoader = filters.selectedLoader();
                filters.setContext(d, PackagesActivity.Instances.gameVersion(target), ll);
                bindVersionSpinners();
                if (!prevGame.equals(filters.selectedGame()) || !prevLoader.equals(filters.selectedLoader())) {
                    page = 1;
                }
                render();
            });
        });
    }

    private void retryFilterContextIfUnavailable() {
        if (filters.contextReady() && !filters.mappingAvailable()) {
            loadFilterContext();
        }
    }

    // #endregion

    // #region Results

    private void render() {
        if (adapter == null) {
            return;
        }
        result = Catalog.Search.run(packages, filters.query());
        List<CatalogModels.Pkg> sorted = result.matched;
        if (!filters.descending()) {
            sorted = new ArrayList<>(sorted);
            Collections.reverse(sorted);
        }
        int pages = BrowseFragment.Filters.pageCount(sorted.size());
        page = BrowseFragment.Filters.clampPage(page, sorted.size());
        List<CatalogModels.Pkg> items = BrowseFragment.Filters.page(sorted, page);
        boolean showList = !loadingCatalog && !items.isEmpty();
        adapter.setPage(showList ? items : Collections.emptyList(), page, pages);
        results.setVisibility(showList ? View.VISIBLE : View.INVISIBLE);
        loading.setVisibility(loadingCatalog ? View.VISIBLE : View.GONE);
        int facets = filters.facetCount();
        styleFiltersButton(facets);
        bindActiveFilters();

        emptyAction.setVisibility(View.GONE);
        if (loadingCatalog || showList) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.VISIBLE);
        if (catalogFailed && packages.isEmpty()) {
            emptyText.setText(R.string.pkg_catalog_load_failed);
            emptyAction.setText(R.string.pkg_retry);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setOnClickListener(v -> {
                loadCatalog(true);
                retryFilterContextIfUnavailable();
            });
        } else if (facets > 0) {
            emptyText.setText(R.string.pkg_empty_filters);
            emptyAction.setText(R.string.pkg_filters_clear_all);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setOnClickListener(v -> {
                filters.clearFacets();
                onFiltersChanged();
            });
        } else {
            emptyText.setText(R.string.pkg_no_results);
        }
    }

    /** The Filters icon button: accent tinted while facet filters are on (the chips below list them). */
    private void styleFiltersButton(int facets) {
        Context c = requireContext();
        filtersButton.setContentDescription(facets > 0
                ? getString(R.string.pkg_filters_count, facets) : getString(R.string.pkg_filters));
        int fill = facets > 0 ? UiKit.accentSoft(c) : c.getColor(R.color.ui_default);
        int ink = facets > 0 ? UiKit.accentText(c) : c.getColor(R.color.text_primary);
        filtersButton.setBackgroundTintList(ColorStateList.valueOf(fill));
        filtersButton.setIconTint(ColorStateList.valueOf(ink));
    }

    /** The facet filters in effect as chips; tapping one removes it. */
    private void bindActiveFilters() {
        activeFilters.removeAllViews();
        Context c = requireContext();
        Catalog.Search.Facet tags = filters.tags();
        for (String key : tags.inc) {
            addActive(c, PkgUi.tagLabel(c, key), () -> filters.removeTag(key));
        }
        for (String key : tags.exc) {
            addActive(c, getString(R.string.pkg_filter_not, PkgUi.tagLabel(c, key)), () -> filters.removeTag(key));
        }
        for (String l : filters.license()) {
            addActive(c, CatalogFacets.FAMILIES.contains(l) ? PkgUi.familyName(c, l) : l, () -> filters.toggleLicense(l));
        }
        for (String d : filters.dep()) {
            addActive(c, getString(R.string.pkg_filter_depends_on, FiltersSheet.depName(packages, d)), () -> filters.toggleDep(d));
        }
        for (String a : filters.author()) {
            addActive(c, getString(R.string.pkg_by_author, a), () -> filters.toggleAuthor(a));
        }
        activeFiltersScroll.setVisibility(activeFilters.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addActive(Context c, String label, Runnable remove) {
        Chip chip = new Chip(c);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setText(label);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setTextColor(c.getColor(R.color.on_surface));
        chip.setChipBackgroundColor(ColorStateList.valueOf(PkgUi.withAlpha(PkgUi.accent(c), 56)));
        chip.setCloseIconVisible(true);
        chip.setContentDescription(getString(R.string.pkg_filter_remove, label));
        View.OnClickListener onRemove = v -> {
            remove.run();
            onFiltersChanged();
        };
        chip.setOnClickListener(onRemove);
        chip.setOnCloseIconClickListener(onRemove);
        activeFilters.addView(chip);
    }

    /** The query's result for the current state (the filters sheet's options and counts). */
    Catalog.Search.Result result() {
        if (result == null) {
            result = Catalog.Search.run(packages, filters.query());
        }
        return result;
    }

    void onFiltersChanged() {
        page = 1;
        render();
        if (results != null) {
            results.scrollToPosition(0);
        }
    }

    /** Lists one owner's packages: the author filter alone, with no search (the version filters and sort stay). */
    private void showAuthor(String owner) {
        filters.showAuthor(owner);
        if (searchBox != null) {
            searchBox.setText("");
        }
        onFiltersChanged();
    }

    public void onPackageClick(CatalogModels.Pkg pkg) {
        openPackage.launch(PackageActivity.intent(requireContext(), pkg.identifier, target()));
    }

    /** The row's Install button: the package page on its Versions tab (pick a version, then install). */
    public void onInstallClick(CatalogModels.Pkg pkg) {
        openPackage.launch(PackageActivity.intent(requireContext(), pkg.identifier, target())
                .putExtra(PackageActivity.EXTRA_SHOW_VERSIONS, true));
    }

    public void onOpenProject(CatalogModels.Pkg pkg) {
        PkgUi.openUrl(requireContext(), pkg.projectUrl);
    }

    public void onPage(int p) {
        page = p;
        render();
        results.scrollToPosition(0);
    }

    // #endregion

    /**
     * The filter state of the catalogue screen and paging: the game version (one of the catalogue's
     * gameVersions, defaulting to the target instance's game), the LeviLamina version (the versions the database
     * has for that game, defaulting to the installed one; off without the database), the tags, license,
     * dependency and author filters, the search and the sort. {@link #ALL} ("") is "All versions".
     */
    public final static class Filters {
        public static final String ALL = "";
        public static final int PAGE_SIZE = 20;

        private LeviLamina.Db db;
        private List<String> gameOptions = Collections.emptyList();
        private List<String> llOptions = Collections.emptyList();
        private String instanceGame = "";
        private String currentGame = "";
        private String installedLL = "";
        private String selectedGame = ALL;
        private String selectedLL = ALL;
        private boolean gameInitialized;
        private boolean llInitialized;
        private boolean contextReady;

        private String query = "";
        private String sort = Catalog.Search.SORT_RELEVANCE;
        private boolean descending = true;
        private Catalog.Search.Facet tags = Catalog.Search.Facet.EMPTY;
        private List<String> license = new ArrayList<>();
        private List<String> dep = new ArrayList<>();
        private List<String> author = new ArrayList<>();

        // #region Context

        /**
         * The LeviLamina database (null when unavailable), the target instance's game version and its installed
         * LeviLamina. Once the catalogue's game versions are known the game filter defaults to the instance's game
         * and the LeviLamina filter to the installed version; later calls keep valid choices, and a new
         * instance's game replaces "All".
         */
        public void setContext(LeviLamina.Db db, String instanceGameVersion, String installedLoaderVersion) {
            this.db = db;
            instanceGame = CatalogModels.Versions.normalizeSemverInput(instanceGameVersion == null ? "" : instanceGameVersion);
            installedLL = Catalog.Search.normalizeLoaderFilter(installedLoaderVersion == null ? "" : installedLoaderVersion);
            contextReady = true;
            updateGame();
            updateLoaderOptions();
        }

        /** The catalogue's game versions (newest first): the game filter's options. */
        public void setGameOptions(List<String> games) {
            gameOptions = Collections.unmodifiableList(new ArrayList<>(games == null ? Collections.emptyList() : games));
            updateGame();
            updateLoaderOptions();
        }

        /**
         * The catalogue game version an instance's game is: the option equal to it ("1.20.61.1" is "1.20.61.01"),
         * else the longest option it starts with on segment boundaries ("1.26.60.5" is "1.26.60"); "" for none.
         */
        static String matchGame(String instance, List<String> options) {
            if (instance == null || instance.isEmpty()) {
                return "";
            }
            for (String o : options) {
                if (LeviLamina.Db.versionEqual(o, instance)) {
                    return o;
                }
            }
            String best = "";
            for (String o : options) {
                if (LeviLamina.Db.hasVersionPrefix(instance, o) && o.split("\\.").length > best.split("\\.").length) {
                    best = o;
                }
            }
            return best;
        }

        private void updateGame() {
            String prevCurrent = currentGame;
            currentGame = matchGame(instanceGame, gameOptions);
            if (!contextReady || gameOptions.isEmpty()) {
                if (!gameOptions.isEmpty() && !gameOptions.contains(selectedGame)) {
                    selectedGame = ALL;
                }
                return;
            }
            String prev = selectedGame;
            boolean offered = !currentGame.isEmpty();
            if (!gameInitialized) {
                gameInitialized = true;
                selectedGame = offered ? currentGame : ALL;
            } else if (ALL.equals(prev) && offered && !currentGame.equals(prevCurrent)) {
                selectedGame = currentGame;
            } else if (!prev.isEmpty() && prev.equals(prevCurrent) && !offered) {
                selectedGame = ALL;
            } else if (gameOptions.contains(prev)) {
                selectedGame = prev;
            } else {
                selectedGame = ALL;
            }
        }

        private void updateLoaderOptions() {
            if (!contextReady) {
                return;
            }
            List<String> next = new ArrayList<>();
            if (mappingAvailable()) {
                if (ALL.equals(selectedGame)) {
                    for (List<String> vs : db.asMap().values()) next.addAll(vs);
                } else {
                    String key = db.resolveGame(selectedGame);
                    if (key != null) next.addAll(db.versions(key));
                }
                next = sortLoaderDesc(next);
                if (!installedLL.isEmpty() && !next.contains(installedLL) && ALL.equals(selectedGame)) {
                    next.add(0, installedLL);
                }
            }
            llOptions = Collections.unmodifiableList(next);
            String prev = selectedLL;
            if (!llInitialized) {
                llInitialized = true;
                selectedLL = !installedLL.isEmpty() && next.contains(installedLL) ? installedLL : ALL;
            } else if (ALL.equals(prev) && !installedLL.isEmpty() && next.contains(installedLL)) {
                selectedLL = installedLL;
            } else if (next.contains(prev)) {
                selectedLL = prev;
            } else {
                selectedLL = ALL;
            }
        }

        private static List<String> sortLoaderDesc(List<String> values) {
            Set<String> unique = new LinkedHashSet<>();
            for (String v : values) {
                String t = Catalog.Search.normalizeLoaderFilter(v == null ? "" : v.trim());
                if (!t.isEmpty()) {
                    unique.add(t);
                }
            }
            List<String> out = new ArrayList<>(unique);
            out.sort((a, b) -> CatalogModels.Versions.compare(b, a));
            return out;
        }

        // #endregion

        // #region Version filters

        public boolean mappingAvailable() {
            return db != null && !db.isEmpty();
        }

        public boolean contextReady() {
            return contextReady;
        }

        public LeviLamina.Db db() {
            return db;
        }

        public List<String> gameOptions() {
            return gameOptions;
        }

        public List<String> loaderOptions() {
            return llOptions;
        }

        public String selectedGame() {
            return selectedGame;
        }

        public String selectedLoader() {
            return selectedLL;
        }

        public String currentGame() {
            return currentGame;
        }

        public void selectGame(String game) {
            selectedGame = game == null ? ALL : game;
            updateLoaderOptions();
        }

        public void selectLoader(String version) {
            selectedLL = version == null ? ALL : version;
        }

        // #endregion

        // #region Search, sort and facets

        public String searchText() {
            return query;
        }

        public void setQuery(String q) {
            query = q == null ? "" : q.trim();
        }

        public String sort() {
            return sort;
        }

        /** One of {@link Catalog.Search#SORTS}; anything else is relevance. */
        public void setSort(String key) {
            sort = Catalog.Search.SORTS.contains(key) ? key : Catalog.Search.SORT_RELEVANCE;
        }

        /** Descending is the catalogue's order for the sort (most, latest or best match first); ascending reverses it. */
        public boolean descending() {
            return descending;
        }

        public void setDescending(boolean descending) {
            this.descending = descending;
        }

        public Catalog.Search.Facet tags() {
            return tags;
        }

        /** Cycles a tag: included, excluded, off. */
        public void cycleTag(String tag) {
            tags = Catalog.Search.cycleTag(tags, tag);
        }

        /** Clears a tag (included or excluded). */
        public void removeTag(String key) {
            List<String> inc = new ArrayList<>(tags.inc);
            List<String> exc = new ArrayList<>(tags.exc);
            inc.remove(key);
            exc.remove(key);
            tags = new Catalog.Search.Facet(inc, exc);
        }

        /** License families and SPDX ids. */
        public List<String> license() {
            return Collections.unmodifiableList(license);
        }

        public void toggleLicense(String value) {
            license = Catalog.Search.toggle(license, value);
        }

        /** Bare ids. */
        public List<String> dep() {
            return Collections.unmodifiableList(dep);
        }

        public void toggleDep(String id) {
            dep = Catalog.Search.toggle(dep, id);
        }

        /** Owners. */
        public List<String> author() {
            return Collections.unmodifiableList(author);
        }

        public void toggleAuthor(String owner) {
            author = Catalog.Search.toggle(author, owner);
        }

        /** "More by owner": owner's packages alone (the other facets and the search cleared; the version filters and sort stay). */
        public void showAuthor(String owner) {
            clearFacets();
            query = "";
            author.add(owner);
        }

        /** How many facet filters are on (tags, license, dependencies, author; the Filters button's count). */
        public int facetCount() {
            return tags.inc.size() + tags.exc.size() + license.size() + dep.size() + author.size();
        }

        /** Clears the facet filters (the version filters, search and sort stay). */
        public void clearFacets() {
            tags = Catalog.Search.Facet.EMPTY;
            license = new ArrayList<>();
            dep = new ArrayList<>();
            author = new ArrayList<>();
        }

        /** The catalogue query of this state. */
        public Catalog.Search.Query query() {
            Catalog.Search.Query q = new Catalog.Search.Query();
            q.text = query;
            q.sort = sort;
            if (!ALL.equals(selectedGame)) {
                q.gv = new ArrayList<>(Collections.singletonList(selectedGame));
            }
            q.tags = tags;
            q.licenses = new ArrayList<>(license);
            q.deps = new ArrayList<>(dep);
            q.authors = new ArrayList<>(author);
            q.db = db;
            q.loaderVersion = mappingAvailable() ? selectedLL : "";
            return q;
        }

        // #endregion

        // #region Paging

        public static int pageCount(int count) {
            return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
        }

        public static int clampPage(int page, int count) {
            return Math.max(1, Math.min(page, pageCount(count)));
        }

        public static List<CatalogModels.Pkg> page(List<CatalogModels.Pkg> sorted, int page) {
            int p = clampPage(page, sorted.size());
            int start = (p - 1) * PAGE_SIZE;
            int end = Math.min(sorted.size(), start + PAGE_SIZE);
            if (start >= end) {
                return Collections.emptyList();
            }
            return new ArrayList<>(sorted.subList(start, end));
        }

        // #endregion
    }

    public static class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        public interface Listener {
            void onPackageClick(CatalogModels.Pkg pkg);

            void onOpenProject(CatalogModels.Pkg pkg);

            void onInstall(CatalogModels.Pkg pkg);

            void onOpenAuthor(CatalogModels.Pkg pkg);

            void onPage(int page);
        }

        static final int TYPE_ITEM = 0;
        static final int TYPE_FOOTER = 1;
        private static final int MAX_TAGS = 5;

        private final Listener listener;
        private final String rid;
        private List<CatalogModels.Pkg> items = new ArrayList<>();
        private int page = 1;
        private int pages = 1;

        public Adapter(Listener listener, String rid) {
            this.listener = listener;
            this.rid = rid;
        }

        public void setPage(List<CatalogModels.Pkg> items, int page, int pages) {
            this.items = new ArrayList<>(items);
            this.page = page;
            this.pages = pages;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return position == items.size() ? TYPE_FOOTER : TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            if (items.isEmpty()) {
                return 0;
            }
            return pages > 1 ? items.size() + 1 : items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_FOOTER) {
                return new FooterHolder(inflater.inflate(R.layout.item_pkg_page_footer, parent, false));
            }
            return new ItemHolder(inflater.inflate(R.layout.item_pkg_package, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof ItemHolder) {
                ((ItemHolder) holder).bind(items.get(position), position == items.size() - 1);
            } else if (holder instanceof FooterHolder) {
                ((FooterHolder) holder).bind();
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof ItemHolder) {
                ImageView icon = ((ItemHolder) holder).icon;
                Glide.with(icon).clear(icon);
            }
            super.onViewRecycled(holder);
        }

        final class ItemHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView author;
            final ImageView open;
            final TextView description;
            final TextView badge;
            final TextView meta;
            final ViewGroup tags;
            final View install;
            final View divider;

            ItemHolder(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.pkg_pkg_icon);
                name = v.findViewById(R.id.pkg_pkg_name);
                author = v.findViewById(R.id.pkg_pkg_author);
                open = v.findViewById(R.id.pkg_pkg_open);
                description = v.findViewById(R.id.pkg_pkg_description);
                badge = v.findViewById(R.id.pkg_pkg_badge);
                meta = v.findViewById(R.id.pkg_pkg_meta);
                tags = v.findViewById(R.id.pkg_pkg_tags);
                install = v.findViewById(R.id.pkg_pkg_install);
                divider = v.findViewById(R.id.pkg_pkg_divider);
                new PersonalizationManager(v.getContext()).applyAccentToView(v, v.getContext());
            }

            void bind(CatalogModels.Pkg pkg, boolean last) {
                Context ctx = itemView.getContext();
                name.setText(pkg.name);
                boolean hasOwner = !pkg.author.trim().isEmpty();
                author.setText("| " + ctx.getString(R.string.pkg_by_author, hasOwner ? pkg.author : ctx.getString(R.string.pkg_unknown)));
                author.setClickable(hasOwner);
                author.setOnClickListener(hasOwner ? v -> listener.onOpenAuthor(pkg) : null);
                author.setContentDescription(hasOwner ? ctx.getString(R.string.pkg_open_owner, pkg.author) : null);
                description.setText(pkg.description.trim().isEmpty()
                        ? ctx.getString(R.string.pkg_no_description) : pkg.description);
                PkgUi.bindBadge(badge, pkg.availability(rid));
                meta.setText(PkgUi.metaLine(String.format(Locale.getDefault(), "★ %d", (long) pkg.stars),
                        PkgUi.formatDate(ctx, pkg.updated), PkgUi.license(ctx, pkg)));
                PkgUi.bindTags(ctx, tags, pkg.tags, MAX_TAGS);

                if (!pkg.avatarUrl.isEmpty()) {
                    Glide.with(icon)
                            .load(pkg.avatarUrl)
                            .centerCrop()
                            .placeholder(R.drawable.ic_minecraft_cube)
                            .error(R.drawable.ic_minecraft_cube)
                            .into(icon);
                } else {
                    Glide.with(icon).clear(icon);
                    icon.setImageResource(R.drawable.ic_minecraft_cube);
                }
                boolean hasProject = Readme.Markdown.isOpenableExternal(pkg.projectUrl);
                open.setVisibility(hasProject ? View.VISIBLE : View.GONE);
                open.setOnClickListener(v -> listener.onOpenProject(pkg));
                View row = itemView.findViewById(R.id.pkg_pkg_row);
                row.setContentDescription(ctx.getString(R.string.pkg_view_details, pkg.name));
                row.setOnClickListener(v -> listener.onPackageClick(pkg));
                install.setOnClickListener(v -> listener.onInstall(pkg));
                divider.setVisibility(last ? View.GONE : View.VISIBLE);
            }
        }

        final class FooterHolder extends RecyclerView.ViewHolder {
            private static final int WINDOW = 5;
            final View prev;
            final View next;
            final TextView info;
            final ViewGroup numbers;

            FooterHolder(@NonNull View v) {
                super(v);
                prev = v.findViewById(R.id.pkg_page_prev);
                next = v.findViewById(R.id.pkg_page_next);
                info = v.findViewById(R.id.pkg_page_info);
                numbers = v.findViewById(R.id.pkg_page_numbers);
            }

            void bind() {
                Context ctx = itemView.getContext();
                info.setText(ctx.getString(R.string.pkg_page_of, page, pages));
                itemView.setContentDescription(info.getText());
                boolean hasPrev = page > 1;
                boolean hasNext = page < pages;
                prev.setEnabled(hasPrev);
                prev.setAlpha(hasPrev ? 1f : 0.4f);
                next.setEnabled(hasNext);
                next.setAlpha(hasNext ? 1f : 0.4f);
                prev.setOnClickListener(v -> listener.onPage(page - 1));
                next.setOnClickListener(v -> listener.onPage(page + 1));

                numbers.removeAllViews();
                int first = Math.max(1, Math.min(page - WINDOW / 2, pages - WINDOW + 1));
                int lastPage = Math.min(pages, first + WINDOW - 1);
                int size = Math.round(UiKit.dp(ctx, 36));
                int accent = UiKit.accent(ctx);
                for (int p = first; p <= lastPage; p++) {
                    final int target = p;
                    TextView n = new TextView(ctx);
                    n.setText(String.valueOf(p));
                    n.setGravity(Gravity.CENTER);
                    n.setTypeface(ResourcesCompat.getFont(ctx, R.font.misans), Typeface.BOLD);
                    n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    if (p == page) {
                        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        dot.setColor(accent);
                        n.setBackground(dot);
                        n.setTextColor(org.levimc.launcher.util.ThemePalette.onAccent(accent));
                    } else {
                        n.setBackgroundResource(R.drawable.bg_ui_row);
                        n.setTextColor(ctx.getColor(R.color.text_primary));
                        n.setOnClickListener(v -> listener.onPage(target));
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                    lp.setMarginStart(Math.round(UiKit.dp(ctx, 2)));
                    lp.setMarginEnd(Math.round(UiKit.dp(ctx, 2)));
                    numbers.addView(n, lp);
                }
            }
        }
    }

    /**
     * The Filters sheet (levimc.org's filter groups on a phone): Tags (the featured ones with their sprites, then
     * the others; a tap includes, a second excludes, a third clears), License (each family with its SPDX ids),
     * Dependencies and Author, every option with the number of results it would give. Each change applies at once.
     */
    final static class FiltersSheet {
        private static final int OFF = 0;
        private static final int INCLUDED = 1;
        private static final int EXCLUDED = 2;

        private final Context ctx;
        private final BrowseFragment host;
        private final Filters filters;
        private final BottomSheetDialog dialog;
        private final LinearLayout body;
        private final MaterialButton showResults;
        private final int accent;
        private final float density;

        private FiltersSheet(BrowseFragment host) {
            this.ctx = host.requireContext();
            this.host = host;
            this.filters = host.filters;
            this.accent = PkgUi.accent(ctx);
            this.density = ctx.getResources().getDisplayMetrics().density;
            dialog = new BottomSheetDialog(ctx);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(ctx.getColor(R.color.surface));
            int pad = dp(16);
            root.setPadding(pad, dp(12), pad, pad);

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(ctx.getString(R.string.pkg_filters), 16, R.color.on_surface);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView clear = text(ctx.getString(R.string.pkg_filters_clear_all), 13, R.color.on_surface);
            clear.setTextColor(accent);
            clear.setPadding(dp(8), dp(8), dp(8), dp(8));
            clear.setClickable(true);
            clear.setFocusable(true);
            clear.setOnClickListener(v -> {
                filters.clearFacets();
                changed();
            });
            header.addView(clear);
            root.addView(header);

            ScrollView scroll = new ScrollView(ctx);
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            showResults = new MaterialButton(ctx);
            showResults.setAllCaps(false);
            showResults.setBackgroundTintList(ColorStateList.valueOf(accent));
            showResults.setTextColor(Color.luminance(accent) > 0.42f ? Color.BLACK : Color.WHITE);
            showResults.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            root.addView(showResults, lp);
            dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.8f)));
        }

        static void show(BrowseFragment host) {
            FiltersSheet s = new FiltersSheet(host);
            s.rebuild();
            s.dialog.getBehavior().setSkipCollapsed(true);
            s.dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            s.dialog.show();
        }

        private void changed() {
            host.onFiltersChanged();
            rebuild();
        }

        private void rebuild() {
            Catalog.Search.Result r = host.result();
            body.removeAllViews();
            int n = r.matched.size();
            showResults.setText(ctx.getResources().getQuantityString(R.plurals.pkg_filters_show_results, n, n));

            // Tags: the featured ones offered while they match something (or are picked), then the others.
            section(R.string.pkg_filters_tags);
            body.addView(text(ctx.getString(R.string.pkg_filters_tags_hint), 11, R.color.text_secondary));
            ChipGroup featured = group();
            ChipGroup other = group();
            Catalog.Search.Facet tags = filters.tags();
            for (String key : r.options.tags) {
                int state = tags.inc.contains(key) ? INCLUDED : tags.exc.contains(key) ? EXCLUDED : OFF;
                int count = r.tagCounts.getOrDefault(key, 0);
                if (count == 0 && state == OFF) {
                    continue;
                }
                boolean isFeatured = CatalogFacets.featured(key) != null;
                CharSequence label = isFeatured ? PkgUi.withRaw(ctx, PkgUi.tagLabel(ctx, key), key) : PkgUi.tagLabel(ctx, key);
                Chip chip = chip(label, count, state, isFeatured ? PkgUi.tagIcon(ctx, key) : null);
                chip.setOnClickListener(v -> {
                    filters.cycleTag(key);
                    changed();
                });
                (isFeatured ? featured : other).addView(chip);
            }
            addGroup(featured);
            if (featured.getChildCount() > 0 && other.getChildCount() > 0) {
                body.addView(text(ctx.getString(R.string.pkg_filters_other_tags), 12, R.color.text_secondary));
            }
            addGroup(other);

            // License: each family, then its SPDX ids.
            section(R.string.pkg_filters_license);
            for (Catalog.Search.LicenseOption o : r.options.licenses) {
                ChipGroup g = group();
                String family = PkgUi.familyName(ctx, o.family);
                Chip fam = chip(family, r.licenseCounts.getOrDefault(o.family, 0), on(filters.license(), o.family) ? INCLUDED : OFF, null);
                fam.setTypeface(Typeface.DEFAULT_BOLD);
                fam.setOnClickListener(v -> {
                    filters.toggleLicense(o.family);
                    changed();
                });
                g.addView(fam);
                for (String id : o.ids) {
                    Chip c = chip(id, r.licenseCounts.getOrDefault(id, 0), on(filters.license(), id) ? INCLUDED : OFF, null);
                    c.setContentDescription(ctx.getString(R.string.pkg_filters_license_ids, family) + ": " + id);
                    c.setOnClickListener(v -> {
                        filters.toggleLicense(id);
                        changed();
                    });
                    g.addView(c);
                }
                addGroup(g);
            }
            if (r.options.licenses.isEmpty()) {
                body.addView(text(ctx.getString(R.string.pkg_filters_none), 12, R.color.text_secondary));
            }

            // Dependencies (all of them) and authors (any of them).
            section(R.string.pkg_filters_dependencies);
            ChipGroup deps = group();
            for (String id : r.options.deps) {
                boolean on = on(filters.dep(), id);
                int count = r.depCounts.getOrDefault(id, 0);
                if (count == 0 && !on) {
                    continue;
                }
                Chip c = chip(depName(host.packages, id), count, on ? INCLUDED : OFF, null);
                c.setContentDescription(ctx.getString(R.string.pkg_filter_depends_on, id));
                c.setOnClickListener(v -> {
                    filters.toggleDep(id);
                    changed();
                });
                deps.addView(c);
            }
            addGroupOrNone(deps);

            section(R.string.pkg_filters_author);
            ChipGroup authors = group();
            for (String owner : r.options.authors) {
                boolean on = on(filters.author(), owner);
                int count = r.authorCounts.getOrDefault(owner, 0);
                if (count == 0 && !on) {
                    continue;
                }
                Chip c = chip(owner, count, on ? INCLUDED : OFF, null);
                c.setOnClickListener(v -> {
                    filters.toggleAuthor(owner);
                    changed();
                });
                authors.addView(c);
            }
            addGroupOrNone(authors);
        }

        /** A dependency's name as the catalogue shows it: the catalogue package's with that id, else the id's last segment. */
        static String depName(List<CatalogModels.Pkg> packages, String id) {
            String key = Catalog.Index.lookupKey(id);
            for (CatalogModels.Pkg p : packages) {
                if (Catalog.Index.lookupKey(p.identifier).equals(key) && !p.name.isEmpty()) {
                    return p.name;
                }
            }
            return id.substring(id.lastIndexOf('/') + 1);
        }

        private static boolean on(List<String> values, String v) {
            for (String x : values) if (x.equalsIgnoreCase(v)) return true;
            return false;
        }

        private int dp(float v) {
            return Math.round(v * density);
        }

        private TextView text(String s, float sp, int color) {
            TextView t = new TextView(ctx);
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(ctx.getColor(color));
            t.setTypeface(ResourcesCompat.getFont(ctx, R.font.misans));
            return t;
        }

        private void section(int title) {
            TextView t = text(ctx.getString(title), 13, R.color.on_surface);
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            lp.bottomMargin = dp(4);
            body.addView(t, lp);
        }

        private ChipGroup group() {
            ChipGroup g = new ChipGroup(ctx);
            g.setChipSpacingHorizontal(dp(6));
            g.setChipSpacingVertical(dp(2));
            return g;
        }

        private void addGroup(ChipGroup g) {
            if (g.getChildCount() > 0) {
                body.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        private void addGroupOrNone(ChipGroup g) {
            if (g.getChildCount() == 0) {
                body.addView(text(ctx.getString(R.string.pkg_filters_none), 12, R.color.text_secondary));
            } else {
                addGroup(g);
            }
        }

        /** A filter option: its label, the results it would give, and whether it is included or excluded. */
        private Chip chip(CharSequence label, int count, int state, @Nullable Drawable icon) {
            Chip c = new Chip(ctx);
            c.setEnsureMinTouchTargetSize(false);
            c.setCheckable(false);
            SpannableStringBuilder text = new SpannableStringBuilder();
            if (state == EXCLUDED) {
                // "not %1$s" around the label, keeping the label's spans (the grey raw tag)
                String marker = "\u0000";
                String template = ctx.getString(R.string.pkg_filter_not, marker);
                int at = template.indexOf(marker);
                if (at < 0) {
                    text.append(template).append(' ').append(label);
                } else {
                    text.append(template, 0, at).append(label).append(template, at + marker.length(), template.length());
                }
            } else {
                text.append(label);
            }
            text.append(PkgUi.withRaw(ctx, "", ctx.getString(R.string.pkg_filter_option_count, "", count).trim()));
            c.setText(text);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.setTextColor(ctx.getColor(R.color.on_surface));
            int base = state == EXCLUDED ? ctx.getColor(R.color.error) : accent;
            c.setChipBackgroundColor(ColorStateList.valueOf(state == OFF ? ctx.getColor(R.color.surface_variant) : PkgUi.withAlpha(base, 56)));
            c.setChipStrokeWidth(state == OFF ? 0f : dp(1));
            c.setChipStrokeColor(ColorStateList.valueOf(base));
            if (icon != null) {
                c.setChipIcon(icon);
                c.setChipIconSize(dp(16));
                c.setChipIconVisible(true);
            }
            if (count == 0 && state == OFF) {
                c.setAlpha(0.5f);
            }
            return c;
        }
    }

    final static class DevGuideDialog {
        private DevGuideDialog() {
        }

        static void show(Activity activity) {
            View body = LayoutInflater.from(activity).inflate(R.layout.dialog_pkg_dev_guide, null, false);
            new CustomAlertDialog(activity)
                    .setTitleText(activity.getString(R.string.pkg_guide_title))
                    .setCustomView(body)
                    .setPositiveButton(activity.getString(R.string.pkg_guide_docs),
                            v -> PkgUi.openUrl(activity, PackagesActivity.Links.DEVELOPER_GUIDE_URL))
                    .setNegativeButton(activity.getString(R.string.pkg_guide_close), null)
                    .show();
        }
    }
}
