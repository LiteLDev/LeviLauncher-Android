package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.PackageEngine;
import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LoaderCard implements TaskSession.GlobalListener {
    static final List<String> TASK_METHODS = Collections.unmodifiableList(
            Arrays.asList("Install", "Update", "Uninstall", "Recover"));
    private static final String[] INSTALL_METHODS = {"Install", "Update"};
    private static final String[] UNINSTALL_METHODS = {"Uninstall"};
    private static final String[] RECOVER_METHODS = {"Recover"};
    private static final long RELOAD_DEBOUNCE_MS = 400;

    private final Activity activity;
    private final GameVersion version;
    private final View root;
    private final String targetName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Cancel ctx = new Cancel();
    private final Runnable reload = () -> load(false);
    private final int accent;

    private final TextView statusView;
    private final TextView workspaceView;
    private final LinearLayout noticesView;
    private final View versionRow;
    private final Spinner versionSpinner;
    private final Button actionButton;
    private final Button uninstallButton;
    private final Button recoverButton;
    private final Button refreshButton;
    private final TaskProgressView progress;

    private final LoaderCard.Model.Input input = new LoaderCard.Model.Input();
    private LoaderCard.Model.View view;
    @Nullable
    private InstalledPackages workspace;
    private String workspaceLine = "";
    private int request;
    private boolean destroyed;
    private boolean visible;
    private boolean resumed;
    private boolean loadPending = true;
    private boolean settingSpinner;
    private List<String> spinnerLabels = Collections.emptyList();

    public LoaderCard(Activity activity, GameVersion version, View root) {
        this.activity = activity;
        this.version = version;
        this.root = root;
        this.targetName = PackagesActivity.Instances.targetName(activity, version);
        this.accent = PkgUi.accent(activity);

        statusView = root.findViewById(R.id.pkg_loader_status);
        workspaceView = root.findViewById(R.id.pkg_loader_workspace);
        noticesView = root.findViewById(R.id.pkg_loader_notices);
        versionRow = root.findViewById(R.id.pkg_loader_version_row);
        versionSpinner = root.findViewById(R.id.pkg_loader_version_spinner);
        actionButton = root.findViewById(R.id.pkg_loader_action);
        uninstallButton = root.findViewById(R.id.pkg_loader_uninstall);
        recoverButton = root.findViewById(R.id.pkg_loader_recover);
        refreshButton = root.findViewById(R.id.pkg_loader_refresh);
        progress = root.findViewById(R.id.pkg_loader_progress);

        actionButton.setBackgroundTintList(ColorStateList.valueOf(accent));
        actionButton.setOnClickListener(v -> onAction());
        uninstallButton.setOnClickListener(v -> onUninstall());
        recoverButton.setOnClickListener(v -> onRecover());
        refreshButton.setOnClickListener(v -> load(true));
        versionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                // Programmatic selections land on the model's choice and are ignored:
                // only a different entry is the user's pick.
                if (settingSpinner || view == null || position < 0 || position >= view.options.size()
                        || position == view.selectedIndex()) {
                    return;
                }
                input.selected = view.options.get(position).version;
                render();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        TaskSession.addGlobalListener(this);
        render();
    }

    public View root() {
        return root;
    }

    public void onResume() {
        if (destroyed) {
            return;
        }
        resumed = true;
        attachProgress();
        load(false);
    }

    public void setVisible(boolean shown) {
        if (destroyed || visible == shown) {
            return;
        }
        visible = shown;
        if (shown && resumed && loadPending) {
            load(false);
        }
    }

    public void onDestroy() {
        destroyed = true;
        ctx.cancel();
        main.removeCallbacksAndMessages(null);
        TaskSession.removeGlobalListener(this);
        progress.unbind();
    }

    // #region Loading

    private static final class Snapshot {
        String dirPath = "";
        boolean journal;
        InstalledPackages workspace;
        String workspaceError;
        CatalogModels.InstallState state;
        List<String> versions;
        String versionsError;
        String versionsDetail = "";
    }

    private void load(boolean refreshDb) {
        if (destroyed) {
            return;
        }
        if (!visible) {
            loadPending = true;
            return;
        }
        loadPending = false;
        int req = ++request;
        Context app = activity.getApplicationContext();
        Cancel c = ctx;
        GameVersion v = version;
        if (refreshDb) {
            input.versions = null;
            input.versionsError = null;
            render();
        }
        PackageService.EXECUTOR.execute(() -> {
            Snapshot s = new Snapshot();
            File dir = PackageService.get().workspaceDir(v);
            s.dirPath = dir.getAbsolutePath();
            s.journal = PackageEngine.hasJournal(dir);
            try {
                s.workspace = InstalledPackages.of(v);
            } catch (PackageService.Failure e) {
                s.workspaceError = PackageService.message(e);
            } catch (RuntimeException e) {
                s.workspaceError = String.valueOf(e);
            }
            try {
                s.state = PackageService.get().installState(v, PackageService.LEVILAMINA_PACKAGE_ID);
            } catch (RuntimeException e) {
                PackageService.warn("loader card: LeviLamina state unavailable", e);
                s.state = null;
            }
            main.post(() -> onState(req, s));
            if (c.isCancelled()) {
                return;
            }
            try {
                if (refreshDb) {
                    LeviLamina.DbClient.get().load(c, true);
                }
                s.versions = LeviLamina.supportedVersions(c, v == null ? "" : v.versionCode);
            } catch (PackageService.Failure e) {
                if (c.isCancelled()) return;
                s.versionsError = PackageService.code(e);
                s.versionsDetail = PackageService.message(e);
            } catch (RuntimeException e) {
                s.versionsError = PackageService.ERR_FETCH_LL_DB;
                s.versionsDetail = String.valueOf(e);
            }
            main.post(() -> onVersions(req, s));
        });
    }

    private void onState(int req, Snapshot s) {
        if (destroyed || req != request) {
            return;
        }
        workspace = s.workspace;
        input.journal = s.journal;
        input.workspaceError = s.workspaceError;
        CatalogModels.InstallState st = s.state;
        if (st == null) {
            input.stateKnown = false;
            input.stateError = PackageService.ERR_PACKAGE_QUERY_FAILED;
        } else if (st.error != null && !st.error.trim().isEmpty()) {
            input.stateKnown = false;
            input.stateError = st.error;
        } else {
            input.stateKnown = true;
            input.stateError = null;
            input.installed = st.installed;
            input.explicit = st.explicit;
            input.installedVersion = st.installedVersion == null ? "" : st.installedVersion;
        }
        workspaceLine = workspaceText(s);
        render();
    }

    private void onVersions(int req, Snapshot s) {
        if (destroyed || req != request) {
            return;
        }
        if (s.versionsError != null) {
            input.versions = null;
            input.versionsError = s.versionsError;
            versionsDetail = s.versionsDetail;
        } else {
            input.versions = s.versions == null ? Collections.emptyList() : s.versions;
            input.versionsError = null;
            versionsDetail = "";
        }
        render();
    }

    private String versionsDetail = "";

    private String workspaceText(Snapshot s) {
        StringBuilder b = new StringBuilder(activity.getString(R.string.pkg_loader_workspace, s.dirPath));
        InstalledPackages ws = s.workspace;
        if (ws != null) {
            if (ws.hasStateFile()) {
                b.append('\n').append(activity.getString(R.string.pkg_loader_workspace_env, ws.side(),
                        ws.platform()));
            }
            b.append('\n');
            if (ws.packages().isEmpty()) {
                b.append(activity.getString(R.string.pkg_loader_workspace_none));
            } else {
                b.append(activity.getString(R.string.pkg_loader_workspace_packages, ws.packages().size(),
                        ws.explicitCount()));
            }
        }
        return b.toString();
    }

    // #endregion

    // #region Tasks

    private boolean concerns(TaskSession s) {
        return !destroyed && s.target().equals(targetName)
                && (s.methods().isEmpty() || !java.util.Collections.disjoint(s.methods(), TASK_METHODS));
    }

    @Override
    public void onSessionStarted(TaskSession session) {
        if (concerns(session)) {
            attachProgress();
            render();
        }
    }

    @Override
    public void onSessionFinished(TaskSession session) {
        if (concerns(session)) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, RELOAD_DEBOUNCE_MS);
            render();
        }
    }

    private void attachProgress() {
        if (targetName.isEmpty()) {
            return;
        }
        TaskSession bound = progress.session();
        if (bound != null && bound.isRunning()) {
            return;
        }
        TaskSession s = TaskSession.findRunning(targetName, TASK_METHODS);
        if (s == null) {
            s = TaskSession.observe(activity, TaskSession.Options.builder(targetName)
                    .methods(TASK_METHODS)
                    .feedbackMode(TaskSession.FeedbackMode.INLINE)
                    .errorConsole(false)
                    .build(), null);
        }
        if (s != null) {
            progress.bind(s);
        }
    }

    private boolean busy() {
        if (targetName.isEmpty()) {
            return false;
        }
        TaskSession bound = progress.session();
        if (bound != null && bound.isRunning()) {
            return true;
        }
        return TaskSession.findRunning(targetName, TASK_METHODS) != null;
    }

    // #endregion

    // #region Rendering

    private void render() {
        if (destroyed) {
            return;
        }
        input.busy = busy();
        LoaderCard.Model.View m = LoaderCard.Model.of(input);
        view = m;

        statusView.setText(statusText(m));
        workspaceView.setText(workspaceLine);
        workspaceView.setVisibility(workspaceLine.isEmpty() ? View.GONE : View.VISIBLE);
        renderNotices(m);

        List<String> labels = new ArrayList<>();
        for (LoaderCard.Model.Option o : m.options) {
            labels.add(optionLabel(o));
        }
        if (!labels.equals(spinnerLabels)) {
            spinnerLabels = labels;
            ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, R.layout.spinner_item, labels);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            settingSpinner = true;
            versionSpinner.setAdapter(adapter);
            settingSpinner = false;
        }
        int index = m.selectedIndex();
        if (index >= 0 && versionSpinner.getSelectedItemPosition() != index) {
            // onItemSelected is delivered later; it sees the model's choice and ignores it.
            versionSpinner.setSelection(index, false);
        }
        versionRow.setVisibility(m.options.isEmpty() ? View.GONE : View.VISIBLE);
        versionSpinner.setEnabled(!input.busy);

        actionButton.setVisibility(m.action.isEmpty() ? View.GONE : View.VISIBLE);
        actionButton.setText(actionLabel(m.action));
        setEnabled(actionButton, m.actionEnabled);
        uninstallButton.setVisibility(m.uninstallVisible ? View.VISIBLE : View.GONE);
        setEnabled(uninstallButton, m.uninstallEnabled);
        recoverButton.setVisibility(input.journal ? View.VISIBLE : View.GONE);
        setEnabled(recoverButton, m.recoverEnabled);
        setEnabled(refreshButton, m.refreshEnabled);
    }

    private static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.5f);
    }

    private String statusText(LoaderCard.Model.View m) {
        String label = LoaderCard.Model.versionLabel(m.installedVersion);
        String v = label.isEmpty() ? activity.getString(R.string.pkg_unknown) : label;
        switch (m.status) {
            case NOT_INSTALLED:
                return activity.getString(R.string.pkg_loader_status_not_installed);
            case INSTALLED:
                return activity.getString(R.string.pkg_loader_status_installed, v);
            case DEPENDENCY:
                return activity.getString(R.string.pkg_loader_status_dependency, v);
            case UNKNOWN:
                return activity.getString(R.string.pkg_loader_status_unknown);
            default:
                return activity.getString(R.string.pkg_checking);
        }
    }

    private void renderNotices(LoaderCard.Model.View m) {
        noticesView.removeAllViews();
        float d = activity.getResources().getDisplayMetrics().density;
        for (LoaderCard.Model.Notice n : m.notices) {
            String text = noticeText(m, n);
            if (text.isEmpty()) {
                continue;
            }
            TextView tv = new TextView(activity);
            tv.setText(text);
            tv.setTextSize(12);
            tv.setTextColor(noticeColor(n));
            tv.setLineSpacing(2 * d, 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(4 * d);
            noticesView.addView(tv, lp);
        }
        noticesView.setVisibility(noticesView.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private String noticeText(LoaderCard.Model.View m, LoaderCard.Model.Notice n) {
        switch (n) {
            case TASK_RUNNING:
                return activity.getString(R.string.pkg_notice_task_running);
            case JOURNAL:
                return activity.getString(R.string.pkg_loader_notice_journal);
            case WORKSPACE_INVALID:
                return activity.getString(R.string.pkg_loader_notice_workspace_invalid, m.workspaceError);
            case CHECKING_VERSIONS:
                return activity.getString(R.string.pkg_loader_notice_checking_versions);
            case VERSIONS_FAILED:
                return PkgUi.ErrorText.full(activity, m.versionsError, versionsDetail);
            case NOT_SUPPORTED:
                return activity.getString(R.string.pkg_err_ll_not_supported);
            case STATE_FAILED:
                return activity.getString(R.string.pkg_loader_notice_state_failed,
                        PkgUi.ErrorText.describe(activity, m.stateError));
            case NO_ANDROID_BUILD:
                return activity.getString(R.string.pkg_loader_notice_no_android_build);
            case REQUIRED_BY_DEPENDENTS:
                return activity.getString(R.string.pkg_loader_notice_dependency);
            default:
                return "";
        }
    }

    private int noticeColor(LoaderCard.Model.Notice n) {
        switch (n) {
            case WORKSPACE_INVALID:
            case VERSIONS_FAILED:
            case STATE_FAILED:
            case NO_ANDROID_BUILD:
                return activity.getColor(R.color.error);
            case JOURNAL:
            case NOT_SUPPORTED:
            case REQUIRED_BY_DEPENDENTS:
                return activity.getColor(R.color.pkg_log_warning);
            default:
                return activity.getColor(R.color.text_secondary);
        }
    }

    private String optionLabel(LoaderCard.Model.Option o) {
        List<String> tags = new ArrayList<>();
        if (o.latestStable) {
            tags.add(activity.getString(R.string.pkg_loader_tag_latest));
        }
        if (o.prerelease) {
            tags.add(activity.getString(R.string.pkg_loader_tag_prerelease));
        }
        if (o.installed) {
            tags.add(activity.getString(R.string.pkg_loader_tag_installed));
        }
        if (tags.isEmpty()) {
            return o.version;
        }
        return activity.getString(R.string.pkg_loader_version_tags, o.version,
                String.join(activity.getString(R.string.pkg_loader_tag_separator), tags));
    }

    private String actionLabel(String action) {
        switch (action) {
            case LeviLamina.Db.ACTION_UPGRADE:
                return activity.getString(R.string.pkg_action_upgrade);
            case LeviLamina.Db.ACTION_DOWNGRADE:
                return activity.getString(R.string.pkg_action_downgrade);
            case LeviLamina.Db.ACTION_REINSTALL:
                return activity.getString(R.string.pkg_action_reinstall);
            case LeviLamina.Db.ACTION_CHANGE:
                return activity.getString(R.string.pkg_loader_action_change);
            default:
                return activity.getString(R.string.pkg_action_install);
        }
    }

    private static int successText(String action) {
        switch (action) {
            case LeviLamina.Db.ACTION_UPGRADE:
                return R.string.pkg_upgrade_success;
            case LeviLamina.Db.ACTION_DOWNGRADE:
                return R.string.pkg_downgrade_success;
            case LeviLamina.Db.ACTION_REINSTALL:
                return R.string.pkg_reinstall_success;
            default:
                return R.string.pkg_install_success;
        }
    }

    // #endregion

    // #region Actions

    private void onAction() {
        LoaderCard.Model.View m = view;
        if (m == null || !m.actionEnabled || m.selected.isEmpty()) {
            return;
        }
        String target = m.selected;
        String action = m.action;
        String label = actionLabel(action);
        PackageActions.confirm(activity, activity.getString(R.string.pkg_confirm_install_title),
                activity.getString(R.string.pkg_loader_confirm_install, label, target,
                        PackagesActivity.Instances.displayName(version)),
                label, () -> {
                    if (LoaderCard.Model.needsPrereleaseWarning(target)) {
                        PackageActions.confirm(activity, activity.getString(R.string.pkg_loader_prerelease_title),
                                activity.getString(R.string.pkg_loader_prerelease_body, target),
                                activity.getString(R.string.pkg_loader_continue),
                                () -> runInstall(target, action), () -> {
                                });
                    } else {
                        runInstall(target, action);
                    }
                }, () -> {
                });
    }

    private void runInstall(String target, String action) {
        if (refuseWhileBusy()) {
            return;
        }
        GameVersion v = version;
        String log = activity.getString(R.string.pkg_log_install, PackageService.LEVILAMINA_PACKAGE_ID + "@" + target);
        start(LoaderCard.Model.taskAction(action), INSTALL_METHODS, t -> {
            t.addLog(TaskSession.LogLevel.INFO, log);
            return LeviLamina.install(t.cancel(), t.listener(), v, target);
        }, (c, ctx, result) -> {
            if (result.isSuccess()) {
                if (c != null) {
                    c.input.noAndroidBuild = false;
                }
                toast(ctx, ctx.getString(successText(action)));
            } else if (LoaderCard.Model.isNoAndroidBuild(result.code)) {
                if (c != null) {
                    c.input.noAndroidBuild = true;
                }
                toast(ctx, ctx.getString(R.string.pkg_operation_failed,
                        ctx.getString(R.string.pkg_loader_notice_no_android_build)));
            } else if (PackageService.ERR_PACKAGE_INSTALL_FAILED.equals(result.code)) {
                toast(ctx, ctx.getString(R.string.pkg_loader_install_failed_suggestion));
            } else {
                PackageActions.toastResult(ctx, result);
            }
        });
    }

    private void onUninstall() {
        LoaderCard.Model.View m = view;
        if (m == null || !m.uninstallEnabled) {
            return;
        }
        if (m.uninstallBlocked) {
            PackageActions.showRequiredBy(activity, activity.getString(R.string.pkg_loader_name),
                    explicitDependents(workspace));
            return;
        }
        PackageActions.confirm(activity, activity.getString(R.string.pkg_loader_remove_title),
                activity.getString(R.string.pkg_loader_remove_body, PackagesActivity.Instances.displayName(version)),
                activity.getString(R.string.remove), this::runUninstall, () -> {
                });
    }

    private void runUninstall() {
        if (refuseWhileBusy()) {
            return;
        }
        GameVersion v = version;
        String log = activity.getString(R.string.pkg_log_uninstall, activity.getString(R.string.pkg_loader_name));
        String[] outcome = {""};
        List<String> dependents = new ArrayList<>();
        start(R.string.pkg_task_title_uninstall, UNINSTALL_METHODS, t -> {
            t.addLog(TaskSession.LogLevel.INFO, log);
            String code = LeviLamina.uninstall(t.cancel(), t.listener(), v);
            if (PackageService.ERR_PACKAGE_DEMOTED_TO_DEPENDENCY.equals(code)
                    || PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS.equals(code)) {
                // Answers, not failures (upstream logs a warning and explains them in a dialog).
                t.addLog(TaskSession.LogLevel.WARNING, "LeviLamina: " + code);
                outcome[0] = code;
                if (PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS.equals(code)) {
                    try {
                        dependents.addAll(explicitDependents(InstalledPackages.of(v)));
                    } catch (PackageService.Failure | RuntimeException e) {
                        PackageService.warn("loader card: cannot read the dependents of LeviLamina", e);
                    }
                }
                return "";
            }
            return code;
        }, (c, ctx, result) -> {
            Activity live = c == null ? null : c.liveActivity();
            if (result.isSuccess() && PackageService.ERR_PACKAGE_DEMOTED_TO_DEPENDENCY.equals(outcome[0])) {
                if (live != null) {
                    PackageActions.showDemoted(live,
                            Collections.singletonList(ctx.getString(R.string.pkg_loader_name)));
                } else {
                    toast(ctx, ctx.getString(R.string.pkg_err_package_demoted_to_dependency));
                }
            } else if (result.isSuccess() && PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS.equals(outcome[0])) {
                if (live != null) {
                    PackageActions.showRequiredBy(live, ctx.getString(R.string.pkg_loader_name), dependents);
                } else {
                    toast(ctx, ctx.getString(R.string.pkg_err_package_required_by_dependents));
                }
            } else if (result.isSuccess()) {
                toast(ctx, ctx.getString(R.string.pkg_loader_removed));
            } else {
                PackageActions.toastResult(ctx, result);
            }
        });
    }

    private void onRecover() {
        LoaderCard.Model.View m = view;
        if (m == null || !m.recoverEnabled || refuseWhileBusy()) {
            return;
        }
        GameVersion v = version;
        start(R.string.pkg_task_title_recover, RECOVER_METHODS,
                t -> PackageService.get().recover(t.cancel(), t.listener(), v), (c, ctx, result) -> {
                    if (result.isSuccess()) {
                        toast(ctx, ctx.getString(R.string.pkg_loader_recovered));
                    } else {
                        PackageActions.toastResult(ctx, result);
                    }
                });
    }

    private interface Finished {
        void onFinished(@Nullable LoaderCard c, Context ctx, TaskSession.Result result);
    }

    private void start(int action, String[] methods, TaskSession.Runner runner,
                       Finished finished) {
        TaskSession.Options options = TaskSession.Options.builder(targetName)
                .title(action)
                .methods(methods)
                .feedbackMode(TaskSession.FeedbackMode.INLINE)
                .build();
        // The session lives in a static registry while it runs: hold the card weakly.
        WeakReference<LoaderCard> ref = new WeakReference<>(this);
        Context app = activity.getApplicationContext();
        TaskSession s = TaskDialog.start(activity, options, runner, (session, result) -> {
            LoaderCard c = ref.get();
            if (c != null && c.destroyed) {
                c = null;
            }
            Context ctx = c != null ? c.activity : app;
            finished.onFinished(c, ctx, result);
            if (c != null) {
                c.load(false);
            }
        });
        progress.bind(s);
        render();
    }

    private boolean refuseWhileBusy() {
        if (destroyed) {
            return true;
        }
        if (busy()) {
            toast(activity, activity.getString(R.string.pkg_err_workspace_busy));
            render();
            return true;
        }
        return false;
    }

    @Nullable
    private Activity liveActivity() {
        return destroyed || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    private static List<String> explicitDependents(@Nullable InstalledPackages ws) {
        List<String> out = new ArrayList<>();
        if (ws == null) {
            return out;
        }
        InstalledPackages.Pkg ll = ws.byId(PackageService.LEVILAMINA_PACKAGE_ID);
        if (ll == null) {
            return out;
        }
        for (InstalledPackages.Pkg p : ws.explicitDependentsOf(ll)) {
            out.add(p.identity);
        }
        return out;
    }

    private static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    // #endregion

    public final static class Model {
        public enum Status {
            CHECKING,
            NOT_INSTALLED,
            INSTALLED,
            DEPENDENCY,
            UNKNOWN
        }

        public enum Notice {
            TASK_RUNNING,
            JOURNAL,
            WORKSPACE_INVALID,
            CHECKING_VERSIONS,
            VERSIONS_FAILED,
            NOT_SUPPORTED,
            STATE_FAILED,
            NO_ANDROID_BUILD,
            REQUIRED_BY_DEPENDENTS
        }

        public static final class Option {
            public final String version;
            public final boolean latestStable;
            public final boolean prerelease;
            public final boolean installed;

            Option(String version, boolean latestStable, boolean prerelease, boolean installed) {
                this.version = version;
                this.latestStable = latestStable;
                this.prerelease = prerelease;
                this.installed = installed;
            }

            @Override
            public String toString() {
                return version + (latestStable ? " [latest]" : "") + (prerelease ? " [pre]" : "")
                        + (installed ? " [installed]" : "");
            }
        }

        public static final class Input {
            public List<String> versions;
            public String versionsError;
            public boolean stateKnown;
            public boolean installed;
            public boolean explicit;
            public String installedVersion = "";
            public String stateError;
            public boolean journal;
            public String workspaceError;
            public boolean busy;
            public boolean noAndroidBuild;
            public String selected = "";
        }

        public static final class View {
            public final Status status;
            public final String installedVersion;
            public final List<Option> options;
            public final String selected;
            public final String action;
            public final boolean actionEnabled;
            public final boolean uninstallVisible;
            public final boolean uninstallEnabled;
            public final boolean uninstallBlocked;
            public final boolean recoverEnabled;
            public final boolean refreshEnabled;
            public final List<Notice> notices;
            public final String versionsError;
            public final String stateError;
            public final String workspaceError;

            View(Status status, String installedVersion, List<Option> options, String selected, String action,
                 boolean actionEnabled, boolean uninstallVisible, boolean uninstallEnabled, boolean uninstallBlocked,
                 boolean recoverEnabled, boolean refreshEnabled, List<Notice> notices, String versionsError,
                 String stateError, String workspaceError) {
                this.status = status;
                this.installedVersion = installedVersion;
                this.options = Collections.unmodifiableList(options);
                this.selected = selected;
                this.action = action;
                this.actionEnabled = actionEnabled;
                this.uninstallVisible = uninstallVisible;
                this.uninstallEnabled = uninstallEnabled;
                this.uninstallBlocked = uninstallBlocked;
                this.recoverEnabled = recoverEnabled;
                this.refreshEnabled = refreshEnabled;
                this.notices = Collections.unmodifiableList(notices);
                this.versionsError = versionsError;
                this.stateError = stateError;
                this.workspaceError = workspaceError;
            }

            public int selectedIndex() {
                for (int i = 0; i < options.size(); i++) {
                    if (options.get(i).version.equals(selected)) {
                        return i;
                    }
                }
                return -1;
            }
        }

        private Model() {
        }

        public static View of(Input in) {
            List<String> versions = in.versions == null ? null : clean(in.versions);
            boolean stateOk = in.stateKnown && empty(in.stateError);
            String current = stateOk && in.installed ? trim(in.installedVersion) : "";

            Status status;
            if (!empty(in.stateError)) {
                status = Status.UNKNOWN;
            } else if (!in.stateKnown) {
                status = Status.CHECKING;
            } else if (!in.installed) {
                status = Status.NOT_INSTALLED;
            } else {
                status = in.explicit ? Status.INSTALLED : Status.DEPENDENCY;
            }

            List<Option> options = new ArrayList<>();
            String selected = "";
            if (versions != null && !versions.isEmpty()) {
                String stable = latestStable(versions);
                for (String v : versions) {
                    options.add(new Option(v, v.equals(stable), CatalogModels.Versions.isPrerelease(v),
                            !current.isEmpty() && sameVersion(v, current)));
                }
                selected = defaultSelection(versions, stateOk && in.installed, current, in.selected);
            }

            boolean installed = stateOk && in.installed;
            String action = selected.isEmpty() ? "" : action(installed, current, selected);
            boolean actionEnabled = !in.busy && !action.isEmpty();
            boolean uninstallVisible = installed;
            boolean uninstallBlocked = installed && !in.explicit;
            boolean uninstallEnabled = uninstallVisible && !in.busy;
            boolean recoverEnabled = in.journal && !in.busy;

            List<Notice> notices = new ArrayList<>();
            if (in.busy) {
                notices.add(Notice.TASK_RUNNING);
            }
            if (in.journal) {
                notices.add(Notice.JOURNAL);
            }
            if (!empty(in.workspaceError)) {
                notices.add(Notice.WORKSPACE_INVALID);
            }
            if (versions == null) {
                notices.add(empty(in.versionsError) ? Notice.CHECKING_VERSIONS : Notice.VERSIONS_FAILED);
            } else if (versions.isEmpty()) {
                notices.add(Notice.NOT_SUPPORTED);
            }
            if (!empty(in.stateError)) {
                notices.add(Notice.STATE_FAILED);
            }
            if (in.noAndroidBuild) {
                notices.add(Notice.NO_ANDROID_BUILD);
            }
            if (uninstallBlocked) {
                notices.add(Notice.REQUIRED_BY_DEPENDENTS);
            }
            return new View(status, current, options, selected, action, actionEnabled, uninstallVisible,
                    uninstallEnabled, uninstallBlocked, recoverEnabled, !in.busy, notices,
                    in.versionsError, in.stateError, in.workspaceError);
        }

        public static String defaultSelection(List<String> versions, boolean installed, String current,
                                              String previous) {
            List<String> list = clean(versions);
            if (list.isEmpty()) {
                return "";
            }
            String prev = trim(previous);
            if (!prev.isEmpty() && list.contains(prev)) {
                return prev;
            }
            String latest = latestStable(list);
            String cur = trim(current);
            if (!cur.isEmpty() && LeviLamina.Db.compareLoaderVersions(latest, cur) > 0) {
                return latest;
            }
            if (installed && !cur.isEmpty()) {
                String offered = LeviLamina.Db.findLoaderVersion(list, cur);
                if (offered != null) {
                    return offered;
                }
            }
            return latest;
        }

        public static String latestStable(List<String> versions) {
            String stable = LeviLamina.Db.loaderLatestStable(versions);
            return stable.isEmpty() ? LeviLamina.Db.loaderLatest(versions) : stable;
        }

        public static String action(boolean installed, String current, String target) {
            if (!installed) {
                return LeviLamina.Db.ACTION_INSTALL;
            }
            if (trim(current).isEmpty()) {
                return LeviLamina.Db.ACTION_REINSTALL;
            }
            return LeviLamina.Db.loaderAction(current, target);
        }

        public static int taskAction(String action) {
            if (action == null) {
                return R.string.pkg_task_title_install;
            }
            switch (action) {
                case LeviLamina.Db.ACTION_UPGRADE:
                    return R.string.pkg_task_title_upgrade;
                case LeviLamina.Db.ACTION_DOWNGRADE:
                    return R.string.pkg_task_title_downgrade;
                case LeviLamina.Db.ACTION_REINSTALL:
                    return R.string.pkg_task_title_reinstall;
                default:
                    return R.string.pkg_task_title_install;
            }
        }

        public static String versionLabel(String version) {
            String v = trim(version);
            if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') && Character.isDigit(v.charAt(1))) {
                v = v.substring(1);
            }
            if (v.isEmpty()) {
                return "";
            }
            return Character.isDigit(v.charAt(0)) ? "v" + v : v;
        }

        public static boolean needsPrereleaseWarning(String version) {
            return CatalogModels.Versions.isPrerelease(trim(version));
        }

        public static boolean isNoAndroidBuild(String code) {
            return PackageService.ERR_PKG_NO_ANDROID_BUILD.equals(code);
        }

        private static boolean sameVersion(String a, String b) {
            return LeviLamina.Db.findLoaderVersion(Collections.singletonList(a), b) != null;
        }

        private static List<String> clean(List<String> versions) {
            List<String> out = new ArrayList<>();
            if (versions != null) {
                for (String v : versions) {
                    String t = trim(v);
                    if (!t.isEmpty() && !out.contains(t)) {
                        out.add(t);
                    }
                }
            }
            return out;
        }

        private static boolean empty(String s) {
            return s == null || s.trim().isEmpty();
        }

        private static String trim(String s) {
            return s == null ? "" : s.trim();
        }
    }
}
