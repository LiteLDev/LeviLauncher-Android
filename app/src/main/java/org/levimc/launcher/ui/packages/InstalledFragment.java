package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.divider.MaterialDividerItemDecoration;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.ui.dialogs.SimpleTextWatcher;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.InstallPlanner;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.LocalSources;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.PackageEngine;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.views.ContentActionPopup;
import org.levimc.launcher.util.UIHelper;
import org.levimc.launcher.util.UiSettings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class InstalledFragment extends Fragment implements PackagesActivity.TargetListener,
        TaskSession.GlobalListener {
    static final long SNAPSHOT_TTL_MS = 30_000;
    private static final long RELOAD_DEBOUNCE_MS = 400;
    private static final List<String> WRITE_METHODS = Arrays.asList("Install", "Update", "Uninstall", "Recover");

    private final Handler main = new Handler(Looper.getMainLooper());
    private Cancel ctx = new Cancel();

    private EditText searchBox;
    private MaterialButton refreshButton;
    private MaterialButton fileButton;
    private MaterialButton idButton;
    private SegmentedControl filterTabs;
    private SwitchMaterial onlyEnabledSwitch;
    private TextView summary;
    private View selectionBar;
    private TextView selectedCount;
    private MaterialButton updateSelected;
    private MaterialButton uninstallSelected;
    private TextView notice;
    private TaskProgressView progress;
    private RecyclerView list;
    private View emptyView;
    private TextView emptyText;
    private MaterialButton emptyAction;
    private ProgressBar loading;
    private InstalledFragment.Adapter adapter;
    private int accent;

    private List<InstalledFragment.Model.Row> rows = Collections.emptyList();
    @Nullable
    private InstalledPackages workspace;
    private InstalledFragment.Loader.CatalogState catalog = InstalledFragment.Loader.CatalogState.LOADING;
    @Nullable
    private String loadError;
    private boolean loadingRows;
    private long loadedAt;
    private int request;
    private InstalledFragment.Model.Filter filter = InstalledFragment.Model.Filter.ALL;
    private boolean onlyEnabled;
    private String query = "";
    private boolean selecting;
    private final Set<String> selected = new LinkedHashSet<>();
    private String targetName = "";

    private ActivityResultLauncher<String[]> pickArchive;
    private ActivityResultLauncher<Uri> pickFolder;
    @Nullable
    private GameVersion pickTarget;

    private final Runnable reload = () -> load(false, true);

    public InstalledFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickArchive = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> onPicked(uri, false));
        pickFolder = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                uri -> onPicked(uri, true));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(UiSettings.useTwoPane(requireContext())
                ? R.layout.fragment_pkg_installed : R.layout.fragment_pkg_installed_stacked, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ctx = new Cancel();
        searchBox = view.findViewById(R.id.pkg_inst_search);
        refreshButton = view.findViewById(R.id.pkg_inst_btn_refresh);
        fileButton = view.findViewById(R.id.pkg_inst_btn_file);
        idButton = view.findViewById(R.id.pkg_inst_btn_id);
        filterTabs = view.findViewById(R.id.pkg_inst_filter);
        onlyEnabledSwitch = view.findViewById(R.id.pkg_inst_only_enabled);
        summary = view.findViewById(R.id.pkg_inst_summary);
        selectionBar = view.findViewById(R.id.pkg_inst_selection_bar);
        selectedCount = view.findViewById(R.id.pkg_inst_selected_count);
        MaterialButton selectAll = view.findViewById(R.id.pkg_inst_select_all);
        updateSelected = view.findViewById(R.id.pkg_inst_update_selected);
        uninstallSelected = view.findViewById(R.id.pkg_inst_uninstall_selected);
        MaterialButton selectionDone = view.findViewById(R.id.pkg_inst_selection_done);
        notice = view.findViewById(R.id.pkg_inst_notice);
        progress = view.findViewById(R.id.pkg_inst_progress);
        list = view.findViewById(R.id.pkg_inst_list);
        emptyView = view.findViewById(R.id.pkg_inst_empty);
        emptyText = view.findViewById(R.id.pkg_inst_empty_text);
        emptyAction = view.findViewById(R.id.pkg_inst_empty_action);
        loading = view.findViewById(R.id.pkg_inst_loading);

        accent = PkgUi.accent(requireContext());
        UiKit.applyPrimary(idButton);
        UiKit.applyPrimary(selectionDone);
        progress.setShowCancel(true);
        progress.setShowStatus(true);

        adapter = new InstalledFragment.Adapter(new Adapter.Listener() {
            @Override
            public void onRowClick(Model.Row row, View view) {
                InstalledFragment.this.onRowClick(row, view);
            }

            @Override
            public void onRowLongClick(Model.Row row) {
                InstalledFragment.this.onRowLongClick(row);
            }

            @Override
            public void onUpdateClick(Model.Row row) {
                InstalledFragment.this.onUpdateClick(row);
            }

            @Override
            public void onEnableChanged(Model.Row row, boolean enabled) {
                InstalledFragment.this.onEnableChanged(row, enabled);
            }

            @Override
            public void onMore(Model.Row row, View anchor) {
                InstalledFragment.this.onMore(row, anchor);
            }
        }, accent);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        MaterialDividerItemDecoration divider = new MaterialDividerItemDecoration(requireContext(),
                LinearLayoutManager.VERTICAL);
        divider.setDividerColor(requireContext().getColor(R.color.divider));
        divider.setLastItemDecorated(false);
        list.addItemDecoration(divider);
        list.setItemAnimator(null);
        list.setAdapter(adapter);

        refreshButton.setOnClickListener(v -> load(true, false));
        fileButton.setOnClickListener(v -> installFromFile());
        idButton.setOnClickListener(v -> installById());
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                UIHelper.hideKeyboard(requireActivity());
                searchBox.clearFocus();
                return true;
            }
            return false;
        });
        searchBox.addTextChangedListener(SimpleTextWatcher.after(s -> {
            query = s.toString();
            render();
        }));
        if (UiSettings.useTwoPane(requireContext())) {
            filterTabs.setVertical(true);
        } else {
            filterTabs.setEqualWidth(false);
        }
        filterTabs.setItems(getString(R.string.pkg_installed_filter_all),
                getString(R.string.pkg_installed_filter_explicit),
                getString(R.string.pkg_installed_filter_dependency));
        filterTabs.setOnSegmentSelectedListener(i -> setFilter(i == 1 ? InstalledFragment.Model.Filter.EXPLICIT
                : i == 2 ? InstalledFragment.Model.Filter.DEPENDENCY : InstalledFragment.Model.Filter.ALL));
        onlyEnabledSwitch.setOnCheckedChangeListener((b, checked) -> {
            onlyEnabled = checked;
            render();
        });
        selectAll.setOnClickListener(v -> {
            for (InstalledFragment.Model.Row r : visibleRows()) {
                selected.add(r.key());
            }
            render();
        });
        updateSelected.setOnClickListener(v -> runBatch(true));
        uninstallSelected.setOnClickListener(v -> runBatch(false));
        selectionDone.setOnClickListener(v -> endSelection());
        emptyAction.setOnClickListener(v -> load(true, false));
        for (View b : new View[]{refreshButton, fileButton, idButton,
                selectAll, updateSelected, uninstallSelected, selectionDone}) {
            DynamicAnim.applyPressScale(b);
        }

        PackagesActivity host = host();
        if (host != null) {
            host.bindHeader(view, 1);
            host.addPkgTargetListener(this);
        }
        TaskSession.addGlobalListener(this);
        targetName = targetNameOf(target());
        load(false, false);
        attachProgress();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadIfOld();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            reloadIfOld();
        }
    }

    @Override
    public void onDestroyView() {
        PackagesActivity host = host();
        if (host != null) {
            host.removePkgTargetListener(this);
        }
        TaskSession.removeGlobalListener(this);
        request++;
        ctx.cancel();
        main.removeCallbacksAndMessages(null);
        if (progress != null) {
            progress.unbind();
        }
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

    private String targetNameOf(@Nullable GameVersion v) {
        return v == null || getContext() == null ? "" : PackagesActivity.Instances.targetName(requireContext(), v);
    }

    @Override
    public void onPkgTargetChanged(@Nullable GameVersion target) {
        PackagesActivity host = host();
        if (host != null && getView() != null) {
            host.renderTargetTag(getView().findViewById(R.id.pkg_header_target));
        }
        targetName = targetNameOf(target);
        selecting = false;
        selected.clear();
        rows = Collections.emptyList();
        workspace = null;
        progress.unbind();
        load(false, false);
        attachProgress();
    }

    // #region Loading

    private void reloadIfOld() {
        if (getView() != null && !isHidden() && loadedAt > 0
                && SystemClock.elapsedRealtime() - loadedAt > SNAPSHOT_TTL_MS) {
            load(false, false);
        }
    }

    private void load(boolean refreshCatalog, boolean applyOrder) {
        if (getView() == null) {
            return;
        }
        main.removeCallbacks(reload);
        GameVersion v = target();
        int req = ++request;
        if (v == null) {
            rows = Collections.emptyList();
            workspace = null;
            loadError = null;
            loadingRows = false;
            render();
            return;
        }
        loadingRows = true;
        refreshButton.setEnabled(false);
        render();
        Cancel c = ctx;
        PackageService.EXECUTOR.execute(() -> {
            InstalledPackages ws;
            try {
                ws = InstalledFragment.Loader.workspace(v);
            } catch (PackageService.Failure | RuntimeException e) {
                String msg = e instanceof PackageService.Failure ? PackageService.message(e) : String.valueOf(e);
                PackageService.warn("installed packages: cannot read the workspace of " + v.directoryName + ": " + msg);
                main.post(() -> onError(req, msg));
                return;
            }
            List<InstalledFragment.Model.ModEntry> mods;
            try {
                ModManager mm = ModManager.forVersion(v);
                if (applyOrder && !mm.isAppManager()) {
                    InstalledPackages.applyLoadOrder(mm, ws);
                }
                mods = InstalledFragment.Model.ModEntry.of(mm.getMods());
            } catch (RuntimeException e) {
                PackageService.warn("installed packages: cannot read the mods of " + v.directoryName, e);
                mods = Collections.emptyList();
            }
            InstalledFragment.Loader.Snapshot local = InstalledFragment.Loader.local(v, ws, mods);
            boolean needOnline = refreshCatalog || local.catalog != InstalledFragment.Loader.CatalogState.READY;
            main.post(() -> onSnapshot(req, local, needOnline && !ws.isEmpty()));
            if (!needOnline || ws.isEmpty()) {
                return;
            }
            try {
                InstalledFragment.Loader.Snapshot online = InstalledFragment.Loader.online(c, v, ws, mods, refreshCatalog);
                main.post(() -> onSnapshot(req, online, false));
            } catch (PackageService.Failure ignored) {
                // The view is gone.
            }
        });
    }

    private void onSnapshot(int req, InstalledFragment.Loader.Snapshot s, boolean more) {
        if (req != request || getView() == null) {
            return;
        }
        rows = s.rows;
        workspace = s.workspace;
        catalog = s.catalog;
        loadError = null;
        loadingRows = false;
        loadedAt = SystemClock.elapsedRealtime();
        refreshButton.setEnabled(!more);
        Set<String> keys = new LinkedHashSet<>();
        for (InstalledFragment.Model.Row r : rows) {
            keys.add(r.key());
        }
        selected.retainAll(keys);
        render();
    }

    private void onError(int req, String message) {
        if (req != request || getView() == null) {
            return;
        }
        rows = Collections.emptyList();
        workspace = null;
        loadError = message;
        loadingRows = false;
        loadedAt = SystemClock.elapsedRealtime();
        refreshButton.setEnabled(true);
        render();
    }

    private void attachProgress() {
        if (progress == null || targetName.isEmpty()) {
            return;
        }
        TaskSession bound = progress.session();
        if (bound != null && bound.isRunning() && sameTarget(bound.target())) {
            return;
        }
        TaskSession s = TaskSession.findRunning(targetName, Collections.emptyList());
        if (s == null) {
            s = TaskSession.observe(requireContext(), TaskSession.Options.builder(targetName)
                    .feedbackMode(TaskSession.FeedbackMode.INLINE).errorConsole(false).build(), null);
        }
        if (s != null) {
            progress.bind(s);
        }
    }

    @Override
    public void onSessionStarted(TaskSession session) {
        if (getView() != null && sameTarget(session.target())) {
            attachProgress();
            render();
        }
    }

    @Override
    public void onSessionFinished(TaskSession session) {
        if (getView() != null && sameTarget(session.target())) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, RELOAD_DEBOUNCE_MS);
        }
    }

    private boolean sameTarget(@Nullable String name) {
        return name != null && !targetName.isEmpty() && name.trim().equalsIgnoreCase(targetName);
    }

    private boolean busy() {
        return !targetName.isEmpty() && TaskSession.findRunning(targetName, WRITE_METHODS) != null;
    }

    // #endregion

    // #region Rendering

    private List<InstalledFragment.Model.Row> visibleRows() {
        return InstalledFragment.Model.filter(rows, filter, onlyEnabled, query);
    }

    private void setFilter(InstalledFragment.Model.Filter f) {
        filter = f;
        render();
    }

    private void render() {
        if (getView() == null || adapter == null) {
            return;
        }
        Context c = requireContext();
        GameVersion v = target();
        filterTabs.setSelectedIndex(filter == InstalledFragment.Model.Filter.EXPLICIT ? 1
                : filter == InstalledFragment.Model.Filter.DEPENDENCY ? 2 : 0, false);
        fileButton.setEnabled(v != null);
        idButton.setEnabled(v != null);

        if (workspace != null && !rows.isEmpty()) {
            String env = workspace.side().isEmpty() ? PackageEngine.SIDE + "/" + PackageService.rid()
                    : workspace.side() + "/" + workspace.platform();
            summary.setText(c.getResources().getQuantityString(R.plurals.pkg_installed_summary, rows.size(),
                    rows.size(), workspace.explicitCount(), env));
            summary.setVisibility(View.VISIBLE);
        } else {
            summary.setVisibility(View.GONE);
        }

        if (!rows.isEmpty() && catalog == InstalledFragment.Loader.CatalogState.LOADING) {
            notice.setText(R.string.pkg_installed_catalog_loading);
            notice.setTextColor(c.getColor(R.color.text_secondary));
            notice.setVisibility(View.VISIBLE);
        } else if (!rows.isEmpty() && catalog == InstalledFragment.Loader.CatalogState.UNAVAILABLE) {
            notice.setText(R.string.pkg_installed_catalog_unavailable);
            notice.setTextColor(c.getColor(R.color.pkg_log_warning));
            notice.setVisibility(View.VISIBLE);
        } else if (!rows.isEmpty() && !selecting) {
            notice.setText(R.string.pkg_installed_select_hint);
            notice.setTextColor(c.getColor(R.color.text_secondary));
            notice.setVisibility(View.VISIBLE);
        } else {
            notice.setVisibility(View.GONE);
        }

        boolean busy = busy();
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectedCount.setText(c.getString(R.string.pkg_installed_selected, selected.size()));
        updateSelected.setEnabled(!selected.isEmpty() && !busy);
        uninstallSelected.setEnabled(!selected.isEmpty() && !busy);

        List<InstalledFragment.Model.Row> visible = visibleRows();
        adapter.setRows(visible, selecting, selected, busy);
        boolean showList = !visible.isEmpty();
        list.setVisibility(showList ? View.VISIBLE : View.INVISIBLE);
        loading.setVisibility(loadingRows && rows.isEmpty() ? View.VISIBLE : View.GONE);

        emptyAction.setVisibility(View.GONE);
        if (showList || loadingRows && rows.isEmpty()) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.VISIBLE);
        if (v == null) {
            emptyText.setText(R.string.pkg_installed_no_target);
        } else if (loadError != null) {
            emptyText.setText(c.getString(R.string.pkg_installed_load_failed, loadError));
            emptyAction.setVisibility(View.VISIBLE);
        } else if (rows.isEmpty()) {
            emptyText.setText(R.string.pkg_installed_empty);
        } else {
            emptyText.setText(R.string.pkg_installed_no_match);
        }
    }

    // #endregion

    // #region Row actions

    public void onRowClick(InstalledFragment.Model.Row row, View view) {
        if (selecting) {
            toggle(row);
            return;
        }
        showActions(row, view);
    }

    public void onRowLongClick(InstalledFragment.Model.Row row) {
        if (!selecting) {
            selecting = true;
            selected.clear();
        }
        toggle(row);
    }

    private void toggle(InstalledFragment.Model.Row row) {
        if (!selected.remove(row.key())) {
            selected.add(row.key());
        }
        render();
    }

    private void endSelection() {
        selecting = false;
        selected.clear();
        render();
    }

    public void onUpdateClick(InstalledFragment.Model.Row row) {
        Activity a = getActivity();
        GameVersion v = target();
        if (a != null && v != null) {
            PackageActions.update(a, v, row, done());
        }
    }

    public void onEnableChanged(InstalledFragment.Model.Row row, boolean enabled) {
        GameVersion v = target();
        if (v == null) {
            return;
        }
        List<String> folders = new ArrayList<>(row.loadableFolders);
        int req = request;
        PackageService.EXECUTOR.execute(() -> {
            try {
                ModManager mm = ModManager.forVersion(v);
                for (String f : folders) {
                    mm.setModEnabled(f, enabled);
                }
            } catch (RuntimeException e) {
                PackageService.warn("installed packages: cannot change the mods of " + row.pkg.identity, e);
            }
            main.post(() -> {
                if (req == request) {
                    load(false, false);
                }
            });
        });
    }

    public void onMore(InstalledFragment.Model.Row row, View anchor) {
        if (selecting) {
            toggle(row);
            return;
        }
        showActions(row, anchor);
    }

    private void showActions(InstalledFragment.Model.Row row, View anchor) {
        Activity a = getActivity();
        GameVersion v = target();
        if (a == null || v == null) {
            return;
        }
        boolean busy = busy();
        List<ContentActionPopup.Action> actions = new ArrayList<>();
        if (row.canUpdate() && !busy) {
            actions.add(new ContentActionPopup.Action(R.drawable.ic_download, R.string.pkg_menu_update, false,
                    () -> PackageActions.update(a, v, row, done())));
        }
        if (!row.explicit() && !row.leviLamina && !busy) {
            actions.add(new ContentActionPopup.Action(R.drawable.ic_check, R.string.pkg_action_promote, false,
                    () -> PackageActions.promote(a, v, row, done())));
        }
        if (row.hasPackagePage()) {
            actions.add(new ContentActionPopup.Action(R.drawable.ic_internet, R.string.pkg_menu_open_package, false,
                    () -> PackageActions.openPackage(a, v, row)));
        }
        actions.add(new ContentActionPopup.Action(R.drawable.ic_content, R.string.pkg_menu_show_files, false,
                () -> PackageActions.showFiles(a, row)));
        if (row.leviLamina) {
            actions.add(new ContentActionPopup.Action(R.drawable.ic_settings, R.string.pkg_menu_loader, false,
                    () -> PackageActions.openLoaderSettings(a, v)));
        } else if (!busy) {
            actions.add(new ContentActionPopup.Action(R.drawable.ic_delete, R.string.pkg_menu_uninstall, true,
                    () -> PackageActions.uninstall(a, v, row, done())));
        }
        ContentActionPopup.show(anchor, row.name, actions);
    }

    private void runBatch(boolean update) {
        Activity a = getActivity();
        GameVersion v = target();
        if (a == null || v == null || selected.isEmpty()) {
            return;
        }
        List<InstalledFragment.Model.Row> chosen = new ArrayList<>();
        for (InstalledFragment.Model.Row r : rows) {
            if (selected.contains(r.key())) {
                chosen.add(r);
            }
        }
        WeakReference<InstalledFragment> self = new WeakReference<>(this);
        PackageActions.Done done = changed -> {
            InstalledFragment f = self.get();
            if (f != null && f.getView() != null) {
                if (changed) {
                    f.endSelection();
                    f.load(false, true);
                }
            }
        };
        if (update) {
            PackageActions.batchUpdate(a, v, chosen, done);
        } else {
            PackageActions.batchUninstall(a, v, chosen, done);
        }
    }

    private PackageActions.Done done() {
        WeakReference<InstalledFragment> self = new WeakReference<>(this);
        return changed -> {
            InstalledFragment f = self.get();
            if (f != null && f.getView() != null && changed) {
                f.load(false, true);
            }
        };
    }

    // #endregion

    // #region Installing

    private void installFromFile() {
        Activity a = getActivity();
        GameVersion v = target();
        if (a == null || v == null) {
            return;
        }
        new CustomAlertDialog(a)
                .setTitleText(getString(R.string.pkg_install_file_title))
                .setMessage(getString(R.string.pkg_install_file_body))
                .setPositiveButton(getString(R.string.pkg_install_file_zip), x -> {
                    pickTarget = v;
                    pickArchive.launch(new String[]{"application/zip", "application/x-zip-compressed",
                            "application/octet-stream", "*/*"});
                })
                .setNeutralButton(getString(R.string.pkg_install_file_folder), x -> {
                    pickTarget = v;
                    pickFolder.launch(null);
                })
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    private void onPicked(@Nullable Uri uri, boolean folder) {
        Activity a = getActivity();
        GameVersion v = pickTarget != null ? pickTarget : target();
        pickTarget = null;
        if (uri == null || a == null || v == null) {
            return;
        }
        Context app = a.getApplicationContext();
        // The document's display name is a query to its provider: asked on the task's thread.
        Callable<String> label = folder ? () -> folderLabel(uri)
                : () -> LocalSources.Saf.displayName(app, uri, "package.zip");
        PackageActions.installSource(a, v, label, PackageActions.copied(() -> folder
                ? LocalSources.Saf.importTree(app, uri) : LocalSources.Saf.importDocument(app, uri)), done());
    }

    static String folderLabel(Uri treeUri) {
        String id;
        try {
            id = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            id = String.valueOf(treeUri.getLastPathSegment());
        }
        int cut = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        String name = id.substring(cut + 1).trim();
        return name.isEmpty() ? id : name;
    }

    private void installById() {
        Activity a = getActivity();
        GameVersion v = target();
        if (a != null && v != null) {
            InstallByIdDialog.show(a, v, done());
        }
    }

    // #endregion

    public final static class Model {
        private Model() {
        }

        public enum Filter {
            ALL, EXPLICIT, DEPENDENCY
        }

        public enum UpdateState {
            AVAILABLE,
            LATEST,
            /** A newer version exists, but none the instance's LeviLamina accepts; targetVersion is the newest. */
            INCOMPATIBLE,
            NO_SOURCE
        }

        public static final class ModEntry {
            public final String folder;
            public final String name;
            public final boolean enabled;

            public ModEntry(String folder, String name, boolean enabled) {
                this.folder = folder == null ? "" : folder;
                this.name = name == null ? "" : name;
                this.enabled = enabled;
            }

            public static ModEntry of(Mod mod) {
                return new ModEntry(mod.getId(), mod.getDisplayName(), mod.isEnabled());
            }

            public static List<ModEntry> of(@Nullable List<Mod> mods) {
                List<ModEntry> out = new ArrayList<>();
                if (mods != null) {
                    for (Mod m : mods) {
                        out.add(of(m));
                    }
                }
                return out;
            }
        }

        public static final class Row {
            public final InstalledPackages.Pkg pkg;
            public final String name;
            @Nullable
            public final String identifier;
            @Nullable
            public final CatalogModels.Pkg catalog;
            public final boolean leviLamina;
            public final List<String> folders;
            public final List<String> loadableFolders;
            public final boolean allEnabled;
            public final boolean anyEnabled;
            public final UpdateState update;
            public final String targetVersion;
            public final List<String> requiredBy;
            public final List<String> explicitRequiredBy;

            Row(InstalledPackages.Pkg pkg, String name, @Nullable String identifier, @Nullable CatalogModels.Pkg catalog,
                boolean leviLamina, List<String> loadableFolders, boolean allEnabled, boolean anyEnabled,
                UpdateState update, String targetVersion, List<String> requiredBy, List<String> explicitRequiredBy) {
                this.pkg = pkg;
                this.name = name;
                this.identifier = identifier;
                this.catalog = catalog;
                this.leviLamina = leviLamina;
                this.folders = pkg.folders;
                this.loadableFolders = Collections.unmodifiableList(loadableFolders);
                this.allEnabled = allEnabled;
                this.anyEnabled = anyEnabled;
                this.update = update;
                this.targetVersion = targetVersion;
                this.requiredBy = Collections.unmodifiableList(requiredBy);
                this.explicitRequiredBy = Collections.unmodifiableList(explicitRequiredBy);
            }

            @Nullable
            public CatalogModels.Availability targetAvailability(String rid) {
                if (catalog == null || targetVersion.isEmpty()) {
                    return null;
                }
                CatalogModels.Variant v = updateVariant(catalog, pkg.variant);
                CatalogModels.PackageFile file = v == null ? null : v.findFile(targetVersion);
                return file == null ? null : file.availability(rid);
            }

            public String key() {
                return PackageService.key(pkg.identity);
            }

            public boolean explicit() {
                return pkg.explicit;
            }

            public boolean notLoadable() {
                return loadableFolders.isEmpty();
            }

            public boolean canUpdate() {
                return update == UpdateState.AVAILABLE;
            }

            public boolean hasPackagePage() {
                return identifier != null;
            }

            public List<String> previewFolders() {
                return folders.size() <= PREVIEW_FOLDERS ? folders : folders.subList(0, PREVIEW_FOLDERS);
            }

            public int extraFolders() {
                return Math.max(0, folders.size() - PREVIEW_FOLDERS);
            }

            public boolean ownsFolder(String folder) {
                if (folder == null) {
                    return false;
                }
                for (String f : folders) {
                    if (f.equalsIgnoreCase(folder.trim())) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String toString() {
                return "Row{" + pkg.identity + "@" + pkg.version + " " + name + " " + update + " " + targetVersion + "}";
            }
        }

        static final int PREVIEW_FOLDERS = 3;

        public static List<Row> build(InstalledPackages ws, @Nullable Catalog.Index index, @Nullable List<ModEntry> mods,
                                      String rid) {
            return build(ws, index, mods, rid, null, "");
        }

        /**
         * db and gameVersion: what tells, when LeviLamina is not installed, which LeviLamina an update may need
         * (none: any); with LeviLamina installed, an update is a version that LeviLamina accepts.
         */
        public static List<Row> build(InstalledPackages ws, @Nullable Catalog.Index index, @Nullable List<ModEntry> mods,
                                      String rid, @Nullable LeviLamina.Db db, String gameVersion) {
            String installedLoader = "";
            for (InstalledPackages.Pkg p : ws.packages()) {
                if (PackageService.isLeviLamina(p.id)) {
                    installedLoader = p.version;
                }
            }
            String loader = installedLoader;
            Predicate<CatalogModels.PackageFile> fitsLoader = f -> InstallPlanner.fitsLoader(f, loader, db, gameVersion);
            Map<String, ModEntry> modsByFolder = new HashMap<>();
            if (mods != null) {
                for (ModEntry m : mods) {
                    modsByFolder.put(folderKey(m.folder), m);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, CatalogModels.Pkg> catalog = new HashMap<>();
            for (InstalledPackages.Pkg p : ws.packages()) {
                CatalogModels.Pkg c = index == null ? null : findCatalog(index, p);
                if (c != null) {
                    catalog.put(p.identity, c);
                }
                names.put(p.identity, displayName(p, c, modsByFolder));
            }
            List<Row> rows = new ArrayList<>();
            for (InstalledPackages.Pkg p : ws.packages()) {
                CatalogModels.Pkg c = catalog.get(p.identity);
                boolean ll = PackageService.isLeviLamina(p.id);
                List<String> loadable = new ArrayList<>();
                boolean all = true;
                boolean any = false;
                for (String f : p.folders) {
                    ModEntry m = modsByFolder.get(folderKey(f));
                    if (m == null) {
                        continue;
                    }
                    loadable.add(f);
                    if (m.enabled) {
                        any = true;
                    } else {
                        all = false;
                    }
                }
                all = all && !loadable.isEmpty();
                String target = "";
                UpdateState update = UpdateState.NO_SOURCE;
                String identifier = PackageService.frontendIdentifier(p.id, p.variant);
                if (!ll && c != null && identifier != null) {
                    target = targetVersion(c, p.variant, rid, f -> true);
                    String fitting = targetVersion(c, p.variant, rid, fitsLoader);
                    if (!fitting.isEmpty() && CatalogModels.Versions.canUpdate(p.version, fitting)) {
                        target = fitting;
                        update = UpdateState.AVAILABLE;
                    } else if (!target.isEmpty()) {
                        update = CatalogModels.Versions.canUpdate(p.version, target) ? UpdateState.INCOMPATIBLE
                                : UpdateState.LATEST;
                    }
                }
                List<String> requiredBy = new ArrayList<>();
                for (InstalledPackages.Pkg d : ws.dependentsOf(p)) {
                    requiredBy.add(names.get(d.identity));
                }
                List<String> explicitRequiredBy = new ArrayList<>();
                for (InstalledPackages.Pkg d : ws.explicitDependentsOf(p)) {
                    explicitRequiredBy.add(names.get(d.identity));
                }
                rows.add(new Row(p, names.get(p.identity), identifier, c, ll, loadable, all, any, update,
                        update == UpdateState.NO_SOURCE ? "" : target, requiredBy, explicitRequiredBy));
            }
            rows.sort((a, b) -> {
                int r = a.name.compareToIgnoreCase(b.name);
                return r != 0 ? r : a.pkg.identity.compareToIgnoreCase(b.pkg.identity);
            });
            return rows;
        }

        @Nullable
        static CatalogModels.Pkg findCatalog(Catalog.Index index, InstalledPackages.Pkg p) {
            if (PackageService.frontendIdentifier(p.id, "") == null) {
                return null;
            }
            return index.find(p.id);
        }

        static String targetVersion(CatalogModels.Pkg c, String variant, String rid,
                                    Predicate<CatalogModels.PackageFile> fits) {
            CatalogModels.Variant v = updateVariant(c, variant);
            List<String> versions = v != null ? v.versions : c.versions;
            List<String> candidates = new ArrayList<>();
            for (String version : versions) {
                CatalogModels.PackageFile file = v != null ? v.findFile(version) : null;
                if (file == null || (file.availability(rid) != CatalogModels.Availability.UNAVAILABLE && fits.test(file))) {
                    candidates.add(version);
                }
            }
            return CatalogModels.Versions.pickTargetVersion(candidates);
        }

        @Nullable
        static CatalogModels.Variant updateVariant(CatalogModels.Pkg c, String variant) {
            CatalogModels.Variant v = c.findVariant(variant);
            if (v == null && (variant.isEmpty() || variant.equalsIgnoreCase(PackageService.CLIENT_VARIANT))) {
                v = c.defaultVariant();
            }
            return v;
        }

        @Nullable
        public static CatalogModels.Availability catalogAvailability(@Nullable Catalog.Index index, String identifier, String version,
                                                       String rid) {
            if (index == null) {
                return null;
            }
            PackageService.Request req = PackageService.Request.parse(identifier);
            if (req == null) {
                return null;
            }
            CatalogModels.Pkg c = index.find(req.packageId);
            if (c == null) {
                return null;
            }
            CatalogModels.Variant v;
            if (req.variant.isEmpty() && req.requestRef.equals(req.packageId)) {
                // "owner/repo#": the bare variant.
                v = c.findVariant("");
            } else if (req.variant.isEmpty()) {
                // The client variant, else the one the launcher falls back to.
                v = c.findVariant(PackageService.CLIENT_VARIANT);
                if (v == null) {
                    v = c.defaultVariant();
                }
            } else {
                v = c.findVariant(req.variant);
            }
            CatalogModels.PackageFile file = v == null ? null : v.findFile(version);
            return file == null ? null : file.availability(rid);
        }

        private static String displayName(InstalledPackages.Pkg p, @Nullable CatalogModels.Pkg c,
                                          Map<String, ModEntry> mods) {
            if (c != null && c.name != null && !c.name.trim().isEmpty()) {
                return c.name.trim();
            }
            ModEntry only = null;
            int count = 0;
            for (String f : p.folders) {
                ModEntry m = mods.get(folderKey(f));
                if (m != null) {
                    only = m;
                    count++;
                }
            }
            if (count == 1 && !only.name.trim().isEmpty()) {
                return only.name.trim();
            }
            return p.identity;
        }

        public static List<Row> filter(List<Row> rows, Filter filter, boolean onlyEnabled, String query) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            List<Row> out = new ArrayList<>();
            for (Row r : rows) {
                if (filter == Filter.EXPLICIT && !r.explicit() || filter == Filter.DEPENDENCY && r.explicit()) {
                    continue;
                }
                if (onlyEnabled && !r.anyEnabled) {
                    continue;
                }
                if (!q.isEmpty() && !matches(r, q)) {
                    continue;
                }
                out.add(r);
            }
            return out;
        }

        private static boolean matches(Row r, String q) {
            if (r.name.toLowerCase(Locale.ROOT).contains(q) || r.pkg.identity.toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
            for (String f : r.folders) {
                if (f.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        public static Row ownerOf(List<Row> rows, String folder) {
            for (Row r : rows) {
                if (r.ownsFolder(folder)) {
                    return r;
                }
            }
            return null;
        }

        public static Map<String, Row> byFolder(List<Row> rows) {
            Map<String, Row> out = new HashMap<>();
            for (Row r : rows) {
                for (String f : r.folders) {
                    out.putIfAbsent(folderKey(f), r);
                }
            }
            return out;
        }

        public static String folderKey(String folder) {
            return folder == null ? "" : folder.trim().toLowerCase(Locale.ROOT);
        }
    }

    final static class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        interface Listener {
            void onRowClick(InstalledFragment.Model.Row row, View view);

            void onRowLongClick(InstalledFragment.Model.Row row);

            void onUpdateClick(InstalledFragment.Model.Row row);

            void onEnableChanged(InstalledFragment.Model.Row row, boolean enabled);

            void onMore(InstalledFragment.Model.Row row, View anchor);
        }

        private final Listener listener;
        private final int accent;
        private List<InstalledFragment.Model.Row> rows = Collections.emptyList();
        private Set<String> selected = Collections.emptySet();
        private boolean selecting;
        private boolean busy;

        Adapter(Listener listener, int accent) {
            this.listener = listener;
            this.accent = accent;
        }

        void setRows(List<InstalledFragment.Model.Row> rows, boolean selecting, Set<String> selected, boolean busy) {
            this.rows = new ArrayList<>(rows);
            this.selecting = selecting;
            this.selected = selected;
            this.busy = busy;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pkg_installed, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            InstalledFragment.Model.Row row = rows.get(position);
            Context c = h.itemView.getContext();
            h.name.setText(row.name);
            h.kind.setText(row.explicit() ? R.string.pkg_installed_explicit : R.string.pkg_installed_dependency);
            int kindColor = row.explicit() ? accent : c.getColor(R.color.pkg_status_neutral);
            h.kind.setTextColor(kindColor);
            h.kind.setBackgroundTintList(ColorStateList.valueOf(PkgUi.withAlpha(kindColor, 38)));
            h.local.setVisibility(row.pkg.isLocal() ? View.VISIBLE : View.GONE);
            h.identity.setText(c.getString(R.string.pkg_installed_identity_version, row.pkg.identity, row.pkg.version));

            if (row.folders.isEmpty()) {
                h.includes.setText(R.string.pkg_installed_no_folders);
            } else if (row.extraFolders() > 0) {
                h.includes.setText(c.getString(R.string.pkg_installed_includes_more,
                        String.join(", ", row.previewFolders()), row.extraFolders()));
            } else {
                h.includes.setText(c.getString(R.string.pkg_installed_includes, String.join(", ", row.folders)));
            }
            h.notLoadable.setVisibility(row.notLoadable() ? View.VISIBLE : View.GONE);
            if (row.requiredBy.isEmpty()) {
                h.requiredBy.setVisibility(View.GONE);
            } else {
                h.requiredBy.setVisibility(View.VISIBLE);
                h.requiredBy.setText(c.getString(R.string.pkg_installed_required_by, String.join(", ", row.requiredBy)));
            }

            bindUpdate(h, row, c);

            h.enabled.setOnCheckedChangeListener(null);
            h.enabled.setVisibility(row.notLoadable() ? View.INVISIBLE : View.VISIBLE);
            // On when all its mods are enabled (upstream isSelected={allEnabled}):
            // a partly enabled package is off, and switching it on enables all.
            h.enabled.setChecked(row.allEnabled);
            h.enabled.setEnabled(!selecting && !busy);
            h.enabled.setOnCheckedChangeListener((b, checked) -> {
                if (checked != row.allEnabled) {
                    listener.onEnableChanged(row, checked);
                }
            });

            h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            h.check.setChecked(selected.contains(row.key()));
            h.more.setVisibility(selecting ? View.GONE : View.VISIBLE);
            h.more.setContentDescription(c.getString(R.string.pkg_installed_actions, row.name));
            h.more.setOnClickListener(v -> listener.onMore(row, v));
            h.itemView.setOnClickListener(v -> listener.onRowClick(row, v));
            h.itemView.setOnLongClickListener(v -> {
                listener.onRowLongClick(row);
                return true;
            });

            String avatar = row.catalog == null ? "" : row.catalog.avatarUrl;
            if (avatar != null && !avatar.isEmpty()) {
                Glide.with(h.icon).load(avatar).placeholder(R.drawable.ic_minecraft_cube)
                        .error(R.drawable.ic_minecraft_cube).into(h.icon);
            } else {
                Glide.with(h.icon).clear(h.icon);
                h.icon.setImageResource(R.drawable.ic_minecraft_cube);
            }
        }

        private void bindUpdate(Holder h, InstalledFragment.Model.Row row, Context c) {
            int color;
            String text;
            boolean clickable = false;
            if (row.leviLamina) {
                text = c.getString(R.string.pkg_installed_ll_managed);
                color = c.getColor(R.color.pkg_status_neutral);
            } else if (row.update == InstalledFragment.Model.UpdateState.AVAILABLE) {
                text = c.getString(R.string.pkg_installed_update_available, row.targetVersion);
                color = c.getColor(R.color.pkg_status_success);
                clickable = !selecting && !busy;
            } else if (row.update == InstalledFragment.Model.UpdateState.INCOMPATIBLE) {
                text = c.getString(R.string.pkg_installed_update_incompatible, row.targetVersion);
                color = c.getColor(R.color.pkg_log_warning);
            } else if (row.update == InstalledFragment.Model.UpdateState.LATEST) {
                text = c.getString(R.string.pkg_installed_update_latest);
                color = c.getColor(R.color.pkg_status_neutral);
            } else {
                text = c.getString(R.string.pkg_installed_no_update_source);
                color = c.getColor(R.color.pkg_status_neutral);
            }
            h.update.setText(text);
            h.update.setTextColor(color);
            h.update.setBackgroundTintList(ColorStateList.valueOf(PkgUi.withAlpha(color, 38)));
            h.update.setClickable(clickable);
            h.update.setOnClickListener(clickable ? v -> listener.onUpdateClick(row) : null);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final CheckBox check;
            final ImageView icon;
            final TextView name;
            final TextView kind;
            final TextView local;
            final TextView identity;
            final TextView includes;
            final TextView notLoadable;
            final TextView requiredBy;
            final TextView update;
            final SwitchCompat enabled;
            final ImageView more;

            Holder(View v) {
                super(v);
                check = v.findViewById(R.id.pkg_inst_check);
                icon = v.findViewById(R.id.pkg_inst_icon);
                name = v.findViewById(R.id.pkg_inst_name);
                kind = v.findViewById(R.id.pkg_inst_kind);
                local = v.findViewById(R.id.pkg_inst_local);
                identity = v.findViewById(R.id.pkg_inst_identity);
                includes = v.findViewById(R.id.pkg_inst_includes);
                notLoadable = v.findViewById(R.id.pkg_inst_not_loadable);
                requiredBy = v.findViewById(R.id.pkg_inst_required_by);
                update = v.findViewById(R.id.pkg_inst_update);
                enabled = v.findViewById(R.id.pkg_inst_switch);
                more = v.findViewById(R.id.pkg_inst_more);
            }
        }
    }

    public final static class Loader {
        private Loader() {
        }

        public enum CatalogState {
            READY,
            LOADING,
            UNAVAILABLE
        }

        public static final class Snapshot {
            public final InstalledPackages workspace;
            public final List<InstalledFragment.Model.Row> rows;
            public final CatalogState catalog;

            Snapshot(InstalledPackages workspace, List<InstalledFragment.Model.Row> rows, CatalogState catalog) {
                this.workspace = workspace;
                this.rows = Collections.unmodifiableList(rows);
                this.catalog = catalog;
            }
        }

        static InstalledPackages workspace(GameVersion v) throws PackageService.Failure {
            return InstalledPackages.of(v);
        }

        /** The rows, with the LeviLamina database in memory for an instance without LeviLamina. */
        static List<InstalledFragment.Model.Row> rows(GameVersion v, InstalledPackages ws, @Nullable Catalog.Index index,
                                                      @Nullable List<InstalledFragment.Model.ModEntry> mods) {
            LeviLamina.DbClient.Result db = LeviLamina.DbClient.get().cached();
            return InstalledFragment.Model.build(ws, index, mods, PackageService.rid(), db == null ? null : db.db,
                    PackagesActivity.Instances.gameVersion(v));
        }

        static Snapshot local(GameVersion v, InstalledPackages ws, List<InstalledFragment.Model.ModEntry> mods) {
            Catalog.Result cached = Catalog.get().cached();
            Catalog.Index index = cached == null ? null : cached.index;
            CatalogState state = cached == null ? CatalogState.LOADING : cached.stale ? CatalogState.UNAVAILABLE : CatalogState.READY;
            return new Snapshot(ws, rows(v, ws, index, mods), state);
        }

        static Snapshot online(Cancel ctx, GameVersion v, InstalledPackages ws, List<InstalledFragment.Model.ModEntry> mods,
                               boolean refresh) throws PackageService.Failure {
            Catalog.Index index = null;
            CatalogState state;
            try {
                Catalog.Result r = Catalog.get().load(ctx, refresh);
                index = r.index;
                state = r.stale ? CatalogState.UNAVAILABLE : CatalogState.READY;
            } catch (PackageService.Failure | RuntimeException e) {
                if (ctx.isCancelled()) throw PackageService.cancelled();
                PackageService.warn("installed packages: catalogue unavailable: "
                        + (e instanceof PackageService.Failure ? PackageService.message(e) : String.valueOf(e)));
                Catalog.Result cached = Catalog.get().cached();
                index = cached == null ? null : cached.index;
                state = CatalogState.UNAVAILABLE;
            }
            return new Snapshot(ws, rows(v, ws, index, mods), state);
        }

        public static void loadForMods(Cancel ctx, @Nullable GameVersion v, @Nullable List<Mod> mods,
                                       Consumer<List<InstalledFragment.Model.Row>> onRows) {
            Handler main = new Handler(Looper.getMainLooper());
            if (v == null) {
                main.post(() -> {
                    if (!ctx.isCancelled()) {
                        onRows.accept(Collections.emptyList());
                    }
                });
                return;
            }
            List<InstalledFragment.Model.ModEntry> entries = InstalledFragment.Model.ModEntry.of(mods);
            PackageService.EXECUTOR.execute(() -> {
                List<InstalledFragment.Model.Row> rows = Collections.emptyList();
                try {
                    InstalledPackages ws = workspace(v);
                    if (!ws.isEmpty()) {
                        // Badges and update chips only: the catalogue in memory or on disk, never the network.
                        rows = rows(v, ws, Catalog.get().offlineIndex(), entries);
                    }
                } catch (PackageService.Failure | RuntimeException e) {
                    if (ctx.isCancelled()) return;
                    PackageService.warn("cannot read the packages of " + v.directoryName + ": " + e.getMessage());
                }
                List<InstalledFragment.Model.Row> out = rows;
                main.post(() -> {
                    if (!ctx.isCancelled()) {
                        onRows.accept(out);
                    }
                });
            });
        }
    }
}
