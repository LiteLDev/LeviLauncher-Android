package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.InstallPlanner;
import org.levimc.launcher.core.packages.LeviLamina;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.activities.InstanceSettingsActivity;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class InstallDialog extends Dialog implements DefaultLifecycleObserver {
    private static final List<String> METHODS = Arrays.asList("Install", "Update");

    public interface InstanceMemory {
        void onInstanceChosen(GameVersion instance);
    }

    private final Activity activity;
    private final CatalogModels.Pkg pkg;
    private final CatalogModels.Variant variant;
    private final String version;
    @Nullable
    private final GameVersion lastChosen;
    @Nullable
    private final GameVersion preferred;
    @Nullable
    private final InstanceMemory memory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Cancel ctx = new Cancel();

    private List<GameVersion> instances;
    @Nullable
    private GameVersion instance;
    private LeviLamina.Db db;
    private boolean dbLoading = true;
    @Nullable
    private CatalogModels.InstallState loaderState;
    @Nullable
    private CatalogModels.InstallState packageState;
    @Nullable
    private TaskSession session;
    private boolean ownSession;
    private InstallDialog.Model model;
    private int queryRequest;
    private boolean bindingSpinner;
    private boolean closed;

    private Spinner instanceSpinner;
    private TextView variantValue;
    private TextView versionValue;
    private TextView installedValue;
    private TextView gameValue;
    private TextView availabilityChip;
    private TextView availabilityPending;
    private LinearLayout notices;
    private TaskProgressView progress;
    private Button cancelButton;
    private Button primaryButton;

    private InstallDialog(Activity activity, CatalogModels.Pkg pkg, CatalogModels.Variant variant, String version,
                             @Nullable GameVersion lastChosen, @Nullable GameVersion preferred,
                             @Nullable InstanceMemory memory) {
        super(activity);
        this.activity = activity;
        this.pkg = pkg;
        this.variant = variant;
        this.version = version;
        this.lastChosen = lastChosen;
        this.preferred = preferred;
        this.memory = memory;
    }

    public static void show(Activity activity, CatalogModels.Pkg pkg, CatalogModels.Variant variant, String version,
                            @Nullable GameVersion lastChosen, @Nullable GameVersion preferred,
                            @Nullable InstanceMemory memory) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        InstallDialog d = new InstallDialog(activity, pkg, variant, version, lastChosen, preferred, memory);
        try {
            d.show();
        } catch (WindowManager.BadTokenException e) {
            d.release();
        }
    }

    // #region Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_pkg_install);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(this, 560);
        setCanceledOnTouchOutside(false);

        instanceSpinner = findViewById(R.id.pkg_install_instance);
        variantValue = findViewById(R.id.pkg_install_variant);
        versionValue = findViewById(R.id.pkg_install_version);
        installedValue = findViewById(R.id.pkg_install_installed);
        gameValue = findViewById(R.id.pkg_install_game);
        availabilityChip = findViewById(R.id.pkg_install_availability);
        availabilityPending = findViewById(R.id.pkg_install_availability_pending);
        notices = findViewById(R.id.pkg_install_notices);
        progress = findViewById(R.id.pkg_install_progress);
        cancelButton = findViewById(R.id.pkg_install_btn_cancel);
        primaryButton = findViewById(R.id.pkg_install_btn_primary);

        progress.setShowCancel(true);
        progress.setShowStatus(true);

        variantValue.setText(PackageActivity.variantLabel(getContext(), variant));
        versionValue.setText(version);

        int accent = PkgUi.accent(getContext());
        primaryButton.setBackgroundTintList(ColorStateList.valueOf(accent));
        primaryButton.setTextColor(org.levimc.launcher.util.ThemePalette.onAccent(accent));
        DynamicAnim.applyPressScale(primaryButton);
        DynamicAnim.applyPressScale(cancelButton);
        cancelButton.setOnClickListener(v -> {
            if (session == null || !session.isRunning() || !ownSession) {
                dismiss();
            }
        });
        primaryButton.setOnClickListener(v -> onPrimary());

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.6f;
            window.setAttributes(params);
            float density = getContext().getResources().getDisplayMetrics().density;
            int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screenWidth * 0.92f), (int) (520 * density)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        if (activity instanceof LifecycleOwner) {
            ((LifecycleOwner) activity).getLifecycle().addObserver(this);
        }

        bindInstances();
        View content = findViewById(android.R.id.content);
        if (content != null) {
            DynamicAnim.animateDialogShow(content);
        }
    }

    @Override
    public void show() {
        Window window = getWindow();
        if (window != null) {
            // Keep the activity's immersive mode (as CustomAlertDialog does).
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        super.show();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    @Override
    public void dismiss() {
        if (closed) {
            return;
        }
        closed = true;
        release();
        View content = findViewById(android.R.id.content);
        Window window = getWindow();
        if (content == null || window == null || window.getDecorView().getParent() == null || !isShowing()) {
            dismissNow();
            return;
        }
        DynamicAnim.animateDialogDismiss(content, this::dismissNow);
    }

    private void dismissNow() {
        try {
            if (isShowing()) {
                super.dismiss();
            }
        } catch (RuntimeException ignored) {
            // The window is already gone (activity destroyed).
        }
    }

    private void release() {
        queryRequest++;
        ctx.cancel();
        main.removeCallbacksAndMessages(null);
        if (progress != null) {
            progress.unbind();
        }
        if (activity instanceof LifecycleOwner) {
            ((LifecycleOwner) activity).getLifecycle().removeObserver(this);
        }
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        closed = true;
        release();
        dismissNow();
    }

    // #endregion

    // #region Instance and states

    private void bindInstances() {
        Context c = getContext();
        instances = PackagesActivity.Instances.all(c);
        String[] labels = new String[instances.size()];
        for (int i = 0; i < instances.size(); i++) {
            GameVersion v = instances.get(i);
            String name = PackagesActivity.Instances.displayName(v);
            String game = PackagesActivity.Instances.gameVersion(v);
            labels[i] = game.isEmpty() || game.equals(name) ? name : c.getString(R.string.pkg_target_value, name, game);
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(c, R.layout.spinner_item, labels);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bindingSpinner = true;
        instanceSpinner.setAdapter(a);
        int index = defaultIndex(c);
        if (index >= 0) {
            instanceSpinner.setSelection(index, false);
        }
        bindingSpinner = false;
        instanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingSpinner || position < 0 || position >= instances.size()) {
                    return;
                }
                GameVersion v = instances.get(position);
                if (!PackagesActivity.Instances.same(v, instance)) {
                    if (memory != null) {
                        memory.onInstanceChosen(v);
                    }
                    selectInstance(v);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        selectInstance(index >= 0 ? instances.get(index) : null);
    }

    private int defaultIndex(Context c) {
        if (instances.isEmpty()) {
            return -1;
        }
        int i = PackagesActivity.Instances.indexOf(instances, lastChosen);
        if (i < 0) {
            i = PackagesActivity.Instances.indexOf(instances, preferred);
        }
        if (i < 0) {
            i = PackagesActivity.Instances.indexOf(instances, PackagesActivity.Instances.selected(c));
        }
        return Math.max(i, 0);
    }

    private void selectInstance(@Nullable GameVersion v) {
        instance = v;
        loaderState = null;
        packageState = null;
        String game = PackagesActivity.Instances.gameVersion(v);
        gameValue.setText(game.isEmpty() ? getContext().getString(R.string.pkg_none) : game);
        attachRunningSession();
        render();
        queryStates();
    }

    private void attachRunningSession() {
        if (session != null && session.isRunning() && ownSession) {
            return;
        }
        session = null;
        ownSession = false;
        if (instance == null) {
            progress.bind(null);
            return;
        }
        TaskSession running = TaskSession.findRunning(PackagesActivity.Instances.targetName(getContext(), instance), METHODS);
        if (running != null) {
            bindSession(running, false);
        } else {
            progress.bind(null);
        }
    }

    private void queryStates() {
        int request = ++queryRequest;
        GameVersion target = instance;
        if (target == null) {
            // No instance to install to (all were removed): nothing to query, nothing stays "Checking…".
            dbLoading = false;
            loaderState = CatalogModels.InstallState.failed(PackageService.ERR_TARGET_NOT_FOUND);
            packageState = loaderState;
            render();
            return;
        }
        String packageId = variant.packageIdentifier;
        PackageService.EXECUTOR.execute(() -> {
            // The install states are a local read: shown before the database, which may need the network.
            CatalogModels.InstallState ll;
            CatalogModels.InstallState p;
            try {
                List<CatalogModels.InstallState> states = PackageService.get().installStates(target,
                        Arrays.asList(PackageService.LEVILAMINA_PACKAGE_ID, packageId));
                ll = states.get(0);
                p = states.get(1);
            } catch (RuntimeException e) {
                PackageService.warn("install dialog: state query failed", e);
                ll = CatalogModels.InstallState.failed("ERR_PACKAGE_QUERY_FAILED");
                p = ll;
            }
            CatalogModels.InstallState llState = ll;
            CatalogModels.InstallState pkgState = p;
            main.post(() -> {
                if (request != queryRequest || closed) {
                    return;
                }
                loaderState = llState;
                packageState = pkgState;
                render();
            });
            LeviLamina.Db d = null;
            try {
                d = LeviLamina.DbClient.get().load(ctx, false).db;
            } catch (PackageService.Failure | RuntimeException e) {
                PackageService.warn("install dialog: LeviLamina database unavailable: " + e.getMessage());
            }
            LeviLamina.Db loaded = d;
            main.post(() -> {
                if (request != queryRequest || closed) {
                    return;
                }
                db = loaded;
                dbLoading = false;
                render();
            });
        });
    }

    // #endregion

    // #region Rendering

    private void render() {
        Context c = getContext();
        InstallDialog.Model.Input in = new InstallDialog.Model.Input();
        in.variant = variant;
        in.version = version;
        in.gameVersion = PackagesActivity.Instances.gameVersion(instance);
        in.db = db;
        in.dbLoading = dbLoading;
        in.loader = loaderState;
        in.pkg = packageState;
        in.taskRunning = session != null && session.isRunning() && !ownSession;
        in.rid = PackageService.rid();
        model = InstallDialog.Model.of(in);

        if (packageState == null) {
            installedValue.setText(R.string.pkg_checking_installed);
        } else if (packageState.installed && !packageState.installedVersion.isEmpty()) {
            installedValue.setText(packageState.explicit ? packageState.installedVersion
                    : c.getString(R.string.pkg_installed_as_dependency, packageState.installedVersion));
        } else {
            installedValue.setText(R.string.pkg_none);
        }
        if (model.plan != null && !model.plan.gameVersion.isEmpty()
                && !model.plan.gameVersion.equals(in.gameVersion)) {
            gameValue.setText(c.getString(R.string.pkg_game_resolved, in.gameVersion, model.plan.gameVersion));
        }
        if (model.availability() != null) {
            availabilityPending.setVisibility(View.GONE);
            PkgUi.bindBadge(availabilityChip, model.availability());
        } else {
            availabilityChip.setVisibility(View.GONE);
            availabilityPending.setVisibility(View.VISIBLE);
        }

        notices.removeAllViews();
        float d = c.getResources().getDisplayMetrics().density;
        for (InstallDialog.Model.Notice n : model.notices) {
            String text = noticeText(c, n);
            if (text.isEmpty()) {
                continue;
            }
            TextView tv = new TextView(c);
            tv.setText(text);
            tv.setTextSize(12);
            tv.setTextColor(PkgUi.noticeColor(c, n.level));
            tv.setLineSpacing(2 * d, 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(4 * d);
            notices.addView(tv, lp);
        }
        notices.setVisibility(notices.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        boolean running = session != null && session.isRunning();
        // While this dialog's own install runs it cannot be closed (upstream isDismissable=!actionRunning);
        // a task found running on the instance only blocks installing there.
        boolean ownRunning = running && ownSession;
        primaryButton.setText(primaryLabel(model.primary));
        boolean enabled = instance != null && model.primaryEnabled && !running;
        primaryButton.setEnabled(enabled);
        primaryButton.setAlpha(enabled ? 1f : 0.5f);
        instanceSpinner.setEnabled(!ownRunning);
        cancelButton.setEnabled(!ownRunning);
        cancelButton.setAlpha(ownRunning ? 0.5f : 1f);
        setCancelable(!ownRunning);
    }

    private String noticeText(Context c, InstallDialog.Model.Notice n) {
        InstallDialog.Model m = model;
        switch (n.type) {
            case CHECKING_LOADER:
                return c.getString(R.string.pkg_notice_checking_ll);
            case MAPPING_UNAVAILABLE:
                return c.getString(R.string.pkg_notice_mapping_unavailable);
            case LOADER_MISSING:
                return c.getString(R.string.pkg_notice_ll_missing);
            case LOADER_INCOMPATIBLE: {
                String installed = m.plan == null || m.plan.installedLoader.isEmpty()
                        ? c.getString(R.string.pkg_none) : m.plan.installedLoader;
                return c.getString(R.string.pkg_notice_ll_incompatible, installed);
            }
            case GAME_INCOMPATIBLE: {
                String game = PackagesActivity.Instances.gameVersion(instance);
                return c.getString(R.string.pkg_notice_game_incompatible, version,
                        game.isEmpty() ? c.getString(R.string.pkg_none) : game);
            }
            case NO_ANDROID_BUILD: {
                CatalogModels.PackageFile f = m.plan == null ? null : m.plan.file;
                String platforms = f == null ? "" : String.join(", ", f.platforms());
                return c.getString(R.string.pkg_notice_no_android_build, platforms);
            }
            case PLATFORM_UNSPECIFIED:
                return c.getString(R.string.pkg_notice_platform_unspecified);
            case BLOCKED:
                return PkgUi.ErrorText.describe(c, n.code);
            case PACKAGE_STATE_FAILED:
                return c.getString(R.string.pkg_notice_package_state_failed, PkgUi.ErrorText.describe(c, n.code));
            case TASK_RUNNING:
                return c.getString(R.string.pkg_notice_task_running);
            default:
                return "";
        }
    }

    private String primaryLabel(InstallDialog.Model.Primary p) {
        switch (p) {
            case REINSTALL:
                return getContext().getString(R.string.pkg_action_reinstall);
            case UPGRADE:
                return getContext().getString(R.string.pkg_action_upgrade);
            case DOWNGRADE:
                return getContext().getString(R.string.pkg_action_downgrade);
            case PROMOTE:
                return getContext().getString(R.string.pkg_action_promote);
            case GO_TO_LOADER:
                return getContext().getString(R.string.pkg_action_go_to_loader);
            default:
                return getContext().getString(R.string.pkg_action_install);
        }
    }

    // #endregion

    // #region Actions

    private void onPrimary() {
        InstallDialog.Model m = model;
        GameVersion target = instance;
        if (m == null || target == null || !m.primaryEnabled || (session != null && session.isRunning())) {
            return;
        }
        if (m.primary == InstallDialog.Model.Primary.GO_TO_LOADER) {
            goToLoader(target);
            return;
        }
        confirmThenInstall(m, target);
    }

    private void goToLoader(GameVersion target) {
        Intent intent = new Intent(activity, InstanceSettingsActivity.class);
        intent.putExtra("version", target);
        intent.putExtra(PackagesActivity.Links.EXTRA_TAB, PackagesActivity.Links.TAB_LOADER);
        dismiss();
        Toast.makeText(activity, R.string.pkg_ll_missing_redirect, Toast.LENGTH_SHORT).show();
        activity.startActivity(intent);
    }

    private void confirmThenInstall(InstallDialog.Model m, GameVersion target) {
        Context c = getContext();
        String name = PackagesActivity.Instances.displayName(target);
        if (m.confirmUnspecified) {
            new CustomAlertDialog(activity)
                    .setTitleText(c.getString(R.string.pkg_confirm_install_title))
                    .setMessage(c.getString(R.string.pkg_confirm_unspecified_platform, pkg.name, version))
                    .setPositiveButton(c.getString(R.string.pkg_confirm_install_anyway),
                            v -> afterPlatformConfirmed(m, target, name))
                    .setNegativeButton(c.getString(R.string.pkg_task_cancel), null)
                    .show();
            return;
        }
        afterPlatformConfirmed(m, target, name);
    }

    private void afterPlatformConfirmed(InstallDialog.Model m, GameVersion target, String name) {
        if (closed || !PackagesActivity.Instances.same(target, instance)) {
            return;
        }
        if (m.confirmDowngrade) {
            Context c = getContext();
            String from = m.plan == null ? "" : m.plan.installedVersion;
            new CustomAlertDialog(activity)
                    .setTitleText(c.getString(R.string.pkg_confirm_install_title))
                    .setMessage(c.getString(R.string.pkg_confirm_downgrade, pkg.name, from, version, name))
                    .setPositiveButton(c.getString(R.string.pkg_action_downgrade), v -> install(m, target))
                    .setNegativeButton(c.getString(R.string.pkg_task_cancel), null)
                    .show();
            return;
        }
        install(m, target);
    }

    private void install(InstallDialog.Model m, GameVersion target) {
        if (closed || !PackagesActivity.Instances.same(target, instance) || (session != null && session.isRunning())) {
            return;
        }
        InstallDialog.Model.Primary primary = m.primary;
        String label = primaryLabel(primary);
        String identifier = variant.packageIdentifier;
        String v = version;
        TaskSession.Options options = TaskSession.Options
                .builder(PackagesActivity.Instances.targetName(getContext(), target))
                .title(InstallDialog.Model.taskAction(primary))
                .methods(METHODS)
                .feedbackMode(TaskSession.FeedbackMode.INLINE)
                .build();
        WeakReference<InstallDialog> self = new WeakReference<>(this);
        Context app = activity.getApplicationContext();
        TaskSession s = TaskDialog.start(activity, options,
                tools -> {
                    tools.addLog(TaskSession.LogLevel.INFO, label + " " + identifier + "@" + v);
                    return PackageService.get().installPackage(tools.cancel(), tools.listener(), target, identifier, v);
                },
                (finished, result) -> {
                    InstallDialog d = self.get();
                    if (d != null) {
                        d.onFinished(finished, result, primary);
                    } else {
                        toastResult(app, result, primary);
                    }
                });
        bindSession(s, true);
    }

    private void bindSession(TaskSession s, boolean own) {
        session = s;
        ownSession = own;
        progress.setVisibility(View.VISIBLE);
        progress.bind(s);
        if (!own) {
            // A task started elsewhere: when it ends, the states of the instance changed.
            WeakReference<InstallDialog> self = new WeakReference<>(this);
            s.addCallback((finished, result) -> {
                InstallDialog d = self.get();
                if (d != null && !d.closed && d.session == finished) {
                    d.loaderState = null;
                    d.packageState = null;
                    d.render();
                    d.queryStates();
                }
            });
        }
        render();
    }

    private void onFinished(TaskSession s, TaskSession.Result result, InstallDialog.Model.Primary primary) {
        // The activity carries the launcher's language; after it is gone the application context will do.
        toastResult(activity.isDestroyed() ? activity.getApplicationContext() : activity, result, primary);
        if (closed || session != s) {
            return;
        }
        if (result.isSuccess()) {
            dismiss();
            return;
        }
        // Failed or cancelled: the dialog stays (the console shows a failure); refresh the states.
        loaderState = null;
        packageState = null;
        render();
        queryStates();
    }

    private static void toastResult(Context c, TaskSession.Result result, InstallDialog.Model.Primary primary) {
        if (result.isSuccess()) {
            int id;
            switch (primary) {
                case REINSTALL:
                    id = R.string.pkg_reinstall_success;
                    break;
                case UPGRADE:
                    id = R.string.pkg_upgrade_success;
                    break;
                case DOWNGRADE:
                    id = R.string.pkg_downgrade_success;
                    break;
                case PROMOTE:
                    id = R.string.pkg_promote_success;
                    break;
                default:
                    id = R.string.pkg_install_success;
            }
            Toast.makeText(c, id, Toast.LENGTH_SHORT).show();
        } else if (result.isCancelled()) {
            Toast.makeText(c, R.string.pkg_err_task_cancelled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(c, c.getString(R.string.pkg_operation_failed, PkgUi.ErrorText.describe(c, result)),
                    Toast.LENGTH_LONG).show();
        }
    }

    // #endregion

    public final static class Model {
        public enum Primary {
            INSTALL, REINSTALL, UPGRADE, DOWNGRADE, PROMOTE,
            GO_TO_LOADER
        }

        public enum Level {
            INFO, WARNING, ERROR
        }

        public enum NoticeType {
            CHECKING_LOADER,
            CHECKING_PACKAGE,
            MAPPING_UNAVAILABLE,
            LOADER_MISSING,
            LOADER_INCOMPATIBLE,
            GAME_INCOMPATIBLE,
            NO_ANDROID_BUILD,
            PLATFORM_UNSPECIFIED,
            BLOCKED,
            PACKAGE_STATE_FAILED,
            TASK_RUNNING
        }

        public static final class Notice {
            public final NoticeType type;
            public final Level level;
            public final String code;

            Notice(NoticeType type, Level level, String code) {
                this.type = type;
                this.level = level;
                this.code = code == null ? "" : code;
            }

            @Override
            public String toString() {
                return type + (code.isEmpty() ? "" : "(" + code + ")");
            }
        }

        public static final class Input {
            public CatalogModels.Variant variant;
            public String version = "";
            public String gameVersion = "";
            public LeviLamina.Db db;
            public boolean dbLoading;
            public CatalogModels.InstallState loader;
            public CatalogModels.InstallState pkg;
            public boolean taskRunning;
            public String rid;
        }

        public final Primary primary;
        public final boolean primaryEnabled;
        public final List<Notice> notices;
        public final InstallPlanner.Plan plan;
        public final boolean confirmUnspecified;
        public final boolean confirmDowngrade;

        private Model(Primary primary, boolean enabled, List<Notice> notices, InstallPlanner.Plan plan) {
            this.primary = primary;
            this.primaryEnabled = enabled;
            this.notices = Collections.unmodifiableList(notices);
            this.plan = plan;
            this.confirmUnspecified = plan != null && plan.needsConfirm;
            this.confirmDowngrade = primary == Primary.DOWNGRADE;
        }

        public static int taskAction(Primary p) {
            switch (p) {
                case UPGRADE:
                    return R.string.pkg_task_title_upgrade;
                case DOWNGRADE:
                    return R.string.pkg_task_title_downgrade;
                case REINSTALL:
                    return R.string.pkg_task_title_reinstall;
                default:
                    return R.string.pkg_task_title_install;
            }
        }

        public static Primary primaryOf(String action) {
            if (InstallPlanner.ACTION_UPGRADE.equals(action)) {
                return Primary.UPGRADE;
            }
            if (InstallPlanner.ACTION_DOWNGRADE.equals(action)) {
                return Primary.DOWNGRADE;
            }
            if (InstallPlanner.ACTION_REINSTALL.equals(action)) {
                return Primary.REINSTALL;
            }
            if (InstallPlanner.ACTION_PROMOTE.equals(action)) {
                return Primary.PROMOTE;
            }
            return Primary.INSTALL;
        }

        public static Model of(Input in) {
            List<Notice> notices = new ArrayList<>();
            boolean needsLoaderCheck = in.variant != null && fileNeedsLoader(in);
            if (in.loader == null || in.pkg == null || in.dbLoading) {
                if (needsLoaderCheck && (in.loader == null || in.dbLoading)) {
                    notices.add(new Notice(NoticeType.CHECKING_LOADER, Level.INFO, ""));
                }
                if (in.pkg == null) {
                    notices.add(new Notice(NoticeType.CHECKING_PACKAGE, Level.INFO, ""));
                }
                if (in.taskRunning) {
                    notices.add(new Notice(NoticeType.TASK_RUNNING, Level.INFO, ""));
                }
                return new Model(Primary.INSTALL, false, notices, null);
            }
            InstallPlanner.Input pi = new InstallPlanner.Input();
            pi.variant = in.variant;
            pi.version = in.version == null ? "" : in.version;
            pi.gameVersion = in.gameVersion == null ? "" : in.gameVersion;
            pi.db = in.db;
            pi.loader = in.loader;
            pi.pkg = in.pkg;
            pi.rid = in.rid;
            InstallPlanner.Plan plan = InstallPlanner.decide(pi);

            String blocker = plan.blocker;
            // A version without an Android build cannot be installed whatever LeviLamina does, so the
            // dialog does not send the user to the Loader settings for it (Android addition).
            boolean noAndroidBuild = InstallPlanner.BLOCK_NO_ANDROID_BUILD.equals(blocker);
            boolean loaderMissing = plan.needsLoader && !plan.replacesLoader && !noAndroidBuild;
            if (InstallPlanner.BLOCK_LOADER_DB_UNAVAILABLE.equals(blocker)) {
                notices.add(new Notice(NoticeType.MAPPING_UNAVAILABLE, Level.WARNING, blocker));
            }
            if (loaderMissing) {
                notices.add(new Notice(NoticeType.LOADER_MISSING, Level.WARNING, ""));
            }
            if (plan.replacesLoader && !noAndroidBuild) {
                notices.add(new Notice(NoticeType.LOADER_INCOMPATIBLE, Level.ERROR, ""));
            }
            if (InstallPlanner.BLOCK_GAME_UNSUPPORTED.equals(blocker)) {
                notices.add(new Notice(NoticeType.GAME_INCOMPATIBLE, Level.ERROR, blocker));
            }
            if (noAndroidBuild) {
                notices.add(new Notice(NoticeType.NO_ANDROID_BUILD, Level.ERROR, blocker));
            }
            if (plan.needsConfirm) {
                notices.add(new Notice(NoticeType.PLATFORM_UNSPECIFIED, Level.WARNING, ""));
            }
            if (!blocker.isEmpty() && !InstallPlanner.BLOCK_LOADER_DB_UNAVAILABLE.equals(blocker)
                    && !InstallPlanner.BLOCK_GAME_UNSUPPORTED.equals(blocker)
                    && !InstallPlanner.BLOCK_NO_ANDROID_BUILD.equals(blocker)) {
                notices.add(new Notice(NoticeType.BLOCKED, Level.ERROR, blocker));
            }
            if (!in.pkg.installed && !in.pkg.error.isEmpty()) {
                notices.add(new Notice(NoticeType.PACKAGE_STATE_FAILED, Level.WARNING, in.pkg.error));
            }
            if (in.taskRunning) {
                notices.add(new Notice(NoticeType.TASK_RUNNING, Level.INFO, ""));
            }
            Primary primary = loaderMissing ? Primary.GO_TO_LOADER : primaryOf(plan.action);
            boolean enabled = blocker.isEmpty() && !plan.replacesLoader && !in.taskRunning && plan.file != null;
            return new Model(primary, enabled, notices, plan);
        }

        private static boolean fileNeedsLoader(Input in) {
            if (in.variant == null) {
                return false;
            }
            CatalogModels.PackageFile f = in.variant.findFile(in.version);
            return f != null && !f.llRanges.isEmpty();
        }

        public CatalogModels.Availability availability() {
            return plan == null ? null : plan.availability;
        }
    }
}
