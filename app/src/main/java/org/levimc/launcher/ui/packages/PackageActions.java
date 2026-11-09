package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Source;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.ui.activities.InstanceSettingsActivity;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.Callable;

public final class PackageActions {
    private static final String[] WRITE_METHODS = {"Install", "Update"};
    private static final String[] UNINSTALL_METHODS = {"Uninstall"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PackageActions() {
    }

    public interface Done {
        void onDone(boolean changed);
    }

    // #region Single package

    public static void update(Activity a, GameVersion v, InstalledFragment.Model.Row row, @Nullable Done done) {
        Done once = once(done);
        String check = PackageActions.Batch.checkUpdate(row);
        if (!check.isEmpty()) {
            toastError(a, check, "");
            once.onDone(false);
            return;
        }
        // The install dialog's platform rule (design FINAL DECISIONS 1): a
        // target version without a declared platform asks first (the
        // confirmation names the package and version), instead of the plain
        // update confirmation.
        boolean unspecified = row.targetAvailability(PackageService.rid()) == CatalogModels.Availability.UNKNOWN;
        String body = unspecified
                ? a.getString(R.string.pkg_confirm_unspecified_platform, row.name, row.targetVersion)
                : a.getString(R.string.pkg_confirm_update_body, row.name, row.targetVersion, instanceName(v));
        String positive = a.getString(unspecified ? R.string.pkg_confirm_install_anyway : R.string.pkg_menu_update);
        confirm(a, a.getString(R.string.pkg_confirm_install_title), body, positive, () -> {
                    Supplier<Context> texts = texts(a);
                    PackageActions.Batch.Outcome[] out = new PackageActions.Batch.Outcome[1];
                    start(a, v, R.string.pkg_task_title_update, WRITE_METHODS, true, t -> {
                        out[0] = PackageActions.Batch.updateOne(t, texts, v, row);
                        return out[0].code();
                    }, (ctx, live, result) -> {
                        if (result.isSuccess()) {
                            toast(ctx, ctx.getString(R.string.pkg_update_done, row.name));
                        } else {
                            toastResult(ctx, result);
                        }
                        once.onDone(true);
                    });
                }, () -> once.onDone(false));
    }

    public static void uninstall(Activity a, GameVersion v, InstalledFragment.Model.Row row, @Nullable Done done) {
        Done once = once(done);
        if (row.leviLamina) {
            showLeviLaminaManaged(a, v);
            once.onDone(false);
            return;
        }
        if (!row.explicit()) {
            showRequiredBy(a, row.name, row.explicitRequiredBy);
            once.onDone(false);
            return;
        }
        confirm(a, a.getString(R.string.pkg_confirm_uninstall_title),
                a.getString(R.string.pkg_confirm_uninstall_body, row.name, instanceName(v)),
                a.getString(R.string.pkg_menu_uninstall), () -> {
                    Supplier<Context> texts = texts(a);
                    PackageActions.Batch.Outcome[] out = new PackageActions.Batch.Outcome[1];
                    start(a, v, R.string.pkg_task_title_uninstall, UNINSTALL_METHODS, true, t -> {
                        out[0] = PackageActions.Batch.uninstallOne(t, texts, v, row);
                        // Required-by and kept-as-dependency are answers, not failures.
                        String code = out[0].code();
                        return PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS.equals(code) ? "" : code;
                    }, (ctx, live, result) -> {
                        PackageActions.Batch.Outcome o = out[0];
                        if (result.isSuccess() && o != null && !o.demoted.isEmpty() && live != null) {
                            showDemoted(live, o.demoted);
                        } else if (result.isSuccess() && o != null && !o.failed.isEmpty() && live != null) {
                            showRequiredBy(live, row.name, row.explicitRequiredBy);
                        } else if (result.isSuccess()) {
                            toast(ctx, ctx.getString(R.string.pkg_uninstall_done, row.name));
                        } else {
                            toastResult(ctx, result);
                        }
                        once.onDone(true);
                    });
                }, () -> once.onDone(false));
    }

    public static void promote(Activity a, GameVersion v, InstalledFragment.Model.Row row, @Nullable Done done) {
        Done once = once(done);
        if (row.leviLamina) {
            showLeviLaminaManaged(a, v);
            once.onDone(false);
            return;
        }
        confirm(a, a.getString(R.string.pkg_confirm_install_title),
                a.getString(R.string.pkg_confirm_promote_body, row.name, row.pkg.version, instanceName(v)),
                a.getString(R.string.pkg_action_promote), () -> {
                    Supplier<Context> texts = texts(a);
                    start(a, v, R.string.pkg_task_title_install, WRITE_METHODS, true,
                            t -> PackageActions.Batch.promote(t, texts, v, row), (ctx, live, result) -> {
                                if (result.isSuccess()) {
                                    toast(ctx, ctx.getString(R.string.pkg_promote_success));
                                } else {
                                    toastResult(ctx, result);
                                }
                                once.onDone(true);
                            });
                }, () -> once.onDone(false));
    }

    public static void openPackage(Activity a, @Nullable GameVersion v, InstalledFragment.Model.Row row) {
        if (row.identifier == null) {
            return;
        }
        a.startActivity(PackageActivity.intent(a, row.identifier, v));
    }

    public static void showFiles(Activity a, InstalledFragment.Model.Row row) {
        StringBuilder b = new StringBuilder();
        b.append(row.pkg.identity).append(" @ ").append(row.pkg.version).append('\n');
        b.append(row.folders.isEmpty() ? a.getString(R.string.pkg_installed_no_folders)
                : a.getString(R.string.pkg_files_folders, String.join(", ", row.folders))).append('\n');
        if (!row.pkg.ref.isEmpty()) {
            b.append(a.getString(R.string.pkg_files_source, row.pkg.ref)).append('\n');
        }
        int n = row.pkg.files.size();
        b.append(a.getResources().getQuantityString(R.plurals.pkg_n_files, n, n)).append('\n');
        int shown = Math.min(n, MAX_FILES_SHOWN);
        for (int i = 0; i < shown; i++) {
            b.append("\n").append(row.pkg.files.get(i));
        }
        if (n > shown) {
            b.append("\n").append(a.getString(R.string.pkg_files_more, n - shown));
        }
        new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.pkg_files_title, row.name))
                .setMessage(b.toString())
                .setPositiveButton(a.getString(R.string.pkg_task_close), null)
                .show();
    }

    static final int MAX_FILES_SHOWN = 200;

    public static void openLoaderSettings(Activity a, @Nullable GameVersion v) {
        Intent intent = new Intent(a, InstanceSettingsActivity.class);
        if (v != null) {
            intent.putExtra("version", v);
        }
        intent.putExtra(PackagesActivity.Links.EXTRA_TAB, PackagesActivity.Links.TAB_MANAGEMENT);
        intent.putExtra(PackagesActivity.Links.EXTRA_SCROLL_TO, PackagesActivity.Links.SCROLL_TO_LOADER);
        a.startActivity(intent);
    }

    // #endregion

    // #region Batches

    public static void batchUpdate(Activity a, GameVersion v, List<InstalledFragment.Model.Row> selected,
                                   @Nullable Done done) {
        Done once = once(done);
        List<InstalledFragment.Model.Row> rows = PackageActions.Batch.distinct(selected);
        List<InstalledFragment.Model.Row> updatable = PackageActions.Batch.updatable(rows);
        if (updatable.isEmpty()) {
            toast(a, a.getString(R.string.pkg_batch_nothing_to_update));
            once.onDone(false);
            return;
        }
        String body = a.getResources().getQuantityString(R.plurals.pkg_batch_update_body, rows.size(), rows.size(),
                updatable.size());
        // Targets without a declared platform are named in the same
        // confirmation (design FINAL DECISIONS 1).
        List<String> unspecified = new ArrayList<>();
        for (InstalledFragment.Model.Row r : updatable) {
            if (r.targetAvailability(PackageService.rid()) == CatalogModels.Availability.UNKNOWN) {
                unspecified.add(r.name + " " + r.targetVersion);
            }
        }
        if (!unspecified.isEmpty()) {
            body = body + "\n\n" + a.getString(R.string.pkg_batch_confirm_unspecified_platform,
                    android.text.TextUtils.join(", ", unspecified));
        }
        confirm(a, a.getString(R.string.pkg_batch_update_title), body,
                a.getString(unspecified.isEmpty() ? R.string.pkg_menu_update : R.string.pkg_confirm_install_anyway),
                () -> {
                    Supplier<Context> texts = texts(a);
                    PackageActions.Batch.Outcome[] out = new PackageActions.Batch.Outcome[1];
                    start(a, v, R.string.pkg_task_title_update, WRITE_METHODS, false, t -> {
                        out[0] = PackageActions.Batch.update(t, texts, v, updatable);
                        return out[0].code();
                    }, (ctx, live, result) -> {
                        finishBatch(live, ctx, out[0], result, R.plurals.pkg_batch_update_done);
                        once.onDone(out[0] == null || out[0].changed);
                    });
                }, () -> once.onDone(false));
    }

    public static void batchUninstall(Activity a, GameVersion v, List<InstalledFragment.Model.Row> selected,
                                      @Nullable Done done) {
        Done once = once(done);
        List<InstalledFragment.Model.Row> rows = PackageActions.Batch.distinct(selected);
        if (rows.isEmpty()) {
            once.onDone(false);
            return;
        }
        confirm(a, a.getString(R.string.pkg_batch_uninstall_title),
                a.getResources().getQuantityString(R.plurals.pkg_batch_uninstall_body, rows.size(), rows.size(),
                        instanceName(v)),
                a.getString(R.string.pkg_menu_uninstall), () -> {
                    Supplier<Context> texts = texts(a);
                    PackageActions.Batch.Outcome[] out = new PackageActions.Batch.Outcome[1];
                    start(a, v, R.string.pkg_task_title_uninstall, UNINSTALL_METHODS, false, t -> {
                        out[0] = PackageActions.Batch.uninstall(t, texts, v, rows);
                        return out[0].code();
                    }, (ctx, live, result) -> {
                        finishBatch(live, ctx, out[0], result, R.plurals.pkg_batch_uninstall_done);
                        once.onDone(out[0] == null || out[0].changed);
                    });
                }, () -> once.onDone(false));
    }

    private static void finishBatch(@Nullable Activity live, Context ctx, @Nullable PackageActions.Batch.Outcome o,
                                    TaskSession.Result result, int donePlural) {
        if (o == null) {
            toastResult(ctx, result);
            return;
        }
        if (!o.failed.isEmpty() && live != null) {
            PackageActions.BatchResultDialog.show(live, o);
            return;
        }
        if (o.cancelled) {
            toastResult(ctx, result);
        } else if (!o.done.isEmpty()) {
            toast(ctx, ctx.getResources().getQuantityString(donePlural, o.done.size(), o.done.size()));
        }
        if (!o.demoted.isEmpty() && live != null) {
            showDemoted(live, o.demoted);
        }
    }

    // #endregion

    // #region Installing new packages

    public static void installIdentifier(Activity a, GameVersion v, String identifier, String version,
                                         @Nullable Done done) {
        Done once = once(done);
        // The install dialog's platform rule (design FINAL DECISIONS 1), from
        // the catalogue in memory: no Android build blocks, an unspecified
        // platform asks first; a version the catalogue does not list is left to the engine.
        Catalog.Result cached = Catalog.get().cached();
        CatalogModels.Availability availability = InstalledFragment.Model.catalogAvailability(cached == null ? null : cached.index,
                identifier, version, PackageService.rid());
        if (availability == CatalogModels.Availability.UNAVAILABLE) {
            toastError(a, PackageService.ERR_PKG_NO_ANDROID_BUILD, "");
            once.onDone(false);
            return;
        }
        if (availability == CatalogModels.Availability.UNKNOWN) {
            confirm(a, a.getString(R.string.pkg_confirm_install_title),
                    a.getString(R.string.pkg_confirm_unspecified_platform, identifier, version),
                    a.getString(R.string.pkg_confirm_install_anyway),
                    () -> runInstallIdentifier(a, v, identifier, version, once), () -> once.onDone(false));
            return;
        }
        runInstallIdentifier(a, v, identifier, version, once);
    }

    private static void runInstallIdentifier(Activity a, GameVersion v, String identifier, String version,
                                             Done once) {
        String label = identifier + "@" + version;
        String logInstall = a.getString(R.string.pkg_log_install, label);
        String logPkg = a.getString(R.string.pkg_log_install_pkg);
        start(a, v, R.string.pkg_task_title_install, WRITE_METHODS, true, t -> {
            t.addLog(TaskSession.LogLevel.INFO, logInstall);
            t.addLog(TaskSession.LogLevel.INFO, logPkg);
            return PackageService.get().installPackage(t.cancel(), t.listener(), v, identifier, version);
        }, (ctx, live, result) -> {
            if (result.isSuccess()) {
                toast(ctx, ctx.getString(R.string.pkg_install_success));
            } else {
                toastResult(ctx, result);
            }
            once.onDone(true);
        });
    }

    public static void installSource(Activity a, GameVersion v, String label, Callable<String> source,
                                     @Nullable Done done) {
        installSource(a, v, () -> label, source, done);
    }

    public static void installSource(Activity a, GameVersion v, Callable<String> labelOf, Callable<String> source,
                                     @Nullable Done done) {
        Done once = once(done);
        Context app = a.getApplicationContext();
        WeakReference<Context> texts = new WeakReference<>(a);
        String logPkg = a.getString(R.string.pkg_log_install_pkg);
        start(a, v, R.string.pkg_task_title_install, WRITE_METHODS, true, t -> {
            String label;
            try {
                label = labelOf.call();
            } catch (Exception e) {
                label = "";
            }
            Context c = texts.get();
            t.addLog(TaskSession.LogLevel.INFO, (c != null ? c : app).getString(R.string.pkg_log_install, label));
            String arg;
            try {
                arg = source.call();
            } catch (Exception e) {
                t.addLog(TaskSession.LogLevel.ERROR, label + ": " + e.getMessage());
                return PackageService.ERR_PKG_LOCAL_SOURCE_FAILED;
            }
            if (t.isCancelled()) {
                return PackageService.ERR_PKG_TASK_CANCELLED;
            }
            t.addLog(TaskSession.LogLevel.INFO, logPkg);
            return PackageService.get().installSource(t.cancel(), t.listener(), v, arg);
        }, (ctx, live, result) -> {
            if (result.isSuccess()) {
                toast(ctx, ctx.getString(R.string.pkg_install_success));
            } else {
                toastResult(ctx, result);
            }
            once.onDone(true);
        });
    }

    static Callable<String> copied(Callable<File> copy) {
        return () -> copy.call().getAbsolutePath();
    }

    // #endregion

    // #region Mod screens

    public static void deleteModFolder(Activity a, GameVersion v, Mod mod, @Nullable Done done) {
        Done once = once(done);
        String folder = mod.getId();
        WeakReference<Activity> ref = new WeakReference<>(a);
        PackageService.EXECUTOR.execute(() -> {
            InstalledPackages ws = null;
            String error = null;
            try {
                ws = InstalledPackages.of(v);
            } catch (PackageService.Failure | RuntimeException e) {
                error = e instanceof PackageService.Failure ? PackageService.message(e) : String.valueOf(e);
            }
            InstalledPackages w = ws;
            String err = error;
            MAIN.post(() -> {
                Activity act = ref.get();
                if (act == null || act.isFinishing() || act.isDestroyed()) {
                    once.onDone(false);
                    return;
                }
                if (w == null) {
                    toastError(act, PackageService.ERR_PKG_WORKSPACE_INVALID, err);
                    once.onDone(false);
                    return;
                }
                InstalledPackages.Pkg owner = w.ownerOf(folder);
                confirmDelete(act, v, mod, w, owner, once);
            });
        });
    }

    private static void confirmDelete(Activity a, GameVersion v, Mod mod, InstalledPackages ws,
                                      @Nullable InstalledPackages.Pkg owner, Done once) {
        String folder = mod.getId();
        if (owner != null && PackageService.isLeviLamina(owner.id)) {
            showLeviLaminaManaged(a, v);
            once.onDone(false);
            return;
        }
        if (owner != null && !owner.explicit) {
            showRequiredBy(a, owner.identity, identities(ws.explicitDependentsOf(owner)));
            once.onDone(false);
            return;
        }
        if (owner == null) {
            String logUninstall = a.getString(R.string.pkg_log_uninstall, mod.getDisplayName());
            String logFallback = a.getString(R.string.pkg_log_fallback_delete);
            confirm(a, a.getString(R.string.dialog_title_delete_mod), a.getString(R.string.dialog_message_delete_mod),
                    a.getString(R.string.dialog_positive_delete), () ->
                            start(a, v, R.string.pkg_task_title_uninstall, UNINSTALL_METHODS,
                                    TaskSession.FeedbackMode.ON_ERROR, true, t -> {
                                        t.addLog(TaskSession.LogLevel.INFO, logUninstall);
                                        t.addLog(TaskSession.LogLevel.WARNING, logFallback);
                                        return InstalledPackages.removeFolder(t.cancel(), t.listener(), v, folder).code;
                                    }, (ctx, live, result) -> {
                                        if (result.isSuccess()) {
                                            toast(ctx, ctx.getString(R.string.delete_mod));
                                        } else {
                                            toastResult(ctx, result);
                                        }
                                        once.onDone(true);
                                    }), () -> once.onDone(false));
            return;
        }
        confirm(a, a.getString(R.string.pkg_confirm_uninstall_title),
                a.getString(R.string.pkg_confirm_delete_owned, owner.identity, instanceName(v)),
                a.getString(R.string.pkg_menu_uninstall), () -> {
                    InstalledPackages.RemoveResult[] out = new InstalledPackages.RemoveResult[1];
                    String logUninstall = a.getString(R.string.pkg_log_uninstall, owner.identity);
                    Supplier<Context> texts = texts(a);
                    start(a, v, R.string.pkg_task_title_uninstall, UNINSTALL_METHODS, true, t -> {
                        t.addLog(TaskSession.LogLevel.INFO, logUninstall);
                        InstalledPackages.RemoveResult r = InstalledPackages.removeFolder(t.cancel(), t.listener(), v, folder);
                        out[0] = r;
                        if (PackageService.ERR_PACKAGE_DEMOTED_TO_DEPENDENCY.equals(r.code)
                                || PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS.equals(r.code)) {
                            t.addLog(TaskSession.LogLevel.WARNING, owner.identity + ": " + r.code);
                            return "";
                        }
                        if (r.ok() && !r.keptFolders.isEmpty()) {
                            t.addLog(TaskSession.LogLevel.WARNING, Batch.keptUserData(texts, owner.identity, r.keptFolders));
                        }
                        return r.code;
                    }, (ctx, live, result) -> {
                        InstalledPackages.RemoveResult r = out[0];
                        if (result.isSuccess() && r != null
                                && PackageService.ERR_PACKAGE_DEMOTED_TO_DEPENDENCY.equals(r.code) && live != null) {
                            showDemoted(live, Collections.singletonList(owner.identity));
                        } else if (result.isSuccess() && r != null
                                && PackageService.ERR_PACKAGE_REQUIRED_BY_DEPENDENTS.equals(r.code) && live != null) {
                            showRequiredBy(live, owner.identity, identities(r.dependents));
                        } else if (result.isSuccess()) {
                            toast(ctx, ctx.getString(R.string.pkg_uninstall_done, owner.identity));
                        } else {
                            toastResult(ctx, result);
                        }
                        once.onDone(true);
                    });
                }, () -> once.onDone(false));
    }

    // #endregion

    // #region Dialogs

    static void showRequiredBy(Activity a, String name, List<String> dependents) {
        String who = dependents == null || dependents.isEmpty() ? a.getString(R.string.pkg_required_by_unknown)
                : String.join(", ", dependents);
        new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.pkg_required_by_title))
                .setMessage(a.getString(R.string.pkg_required_by_body, name, who))
                .setPositiveButton(a.getString(R.string.pkg_task_close), null)
                .show();
    }

    static void showDemoted(Activity a, List<String> names) {
        List<String> unique = new ArrayList<>();
        for (String n : names) {
            String t = n == null ? "" : n.trim();
            if (!t.isEmpty() && !unique.contains(t)) {
                unique.add(t);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.pkg_demoted_title))
                .setMessage(a.getString(R.string.pkg_demoted_body,
                        a.getString(R.string.pkg_err_package_demoted_to_dependency), String.join("\n", unique)))
                .setPositiveButton(a.getString(R.string.pkg_task_close), null)
                .show();
    }

    static void showLeviLaminaManaged(Activity a, @Nullable GameVersion v) {
        new CustomAlertDialog(a)
                .setTitleText(a.getString(R.string.pkg_title_mods))
                .setMessage(a.getString(R.string.pkg_err_ll_managed_in_version_settings))
                .setPositiveButton(a.getString(R.string.pkg_menu_loader), x -> openLoaderSettings(a, v))
                .setNegativeButton(a.getString(R.string.pkg_task_close), null)
                .show();
    }

    static void confirm(Activity a, String title, String message, String positive, Runnable onYes, Runnable onNo) {
        boolean[] answered = {false};
        CustomAlertDialog d = new CustomAlertDialog(a)
                .setTitleText(title)
                .setMessage(message)
                .setPositiveButton(positive, x -> {
                    answered[0] = true;
                    onYes.run();
                })
                .setNegativeButton(a.getString(R.string.dialog_negative_cancel), null);
        d.setOnDismissListener(x -> {
            if (!answered[0]) {
                answered[0] = true;
                onNo.run();
            }
        });
        d.show();
    }

    // #endregion

    // #region Helpers

    interface Finished {
        void onFinished(Context ctx, @Nullable Activity live, TaskSession.Result result);
    }

    private static void start(Activity a, GameVersion v, int action, String[] methods,
                              boolean errorConsole, TaskSession.Runner runner, Finished finished) {
        start(a, v, action, methods, TaskSession.FeedbackMode.PROGRESS, errorConsole, runner, finished);
    }

    private static void start(Activity a, GameVersion v, int action, String[] methods,
                              TaskSession.FeedbackMode mode, boolean errorConsole, TaskSession.Runner runner,
                              Finished finished) {
        TaskSession.Options options = TaskSession.Options.builder(PackagesActivity.Instances.targetName(a, v))
                .title(action)
                .methods(methods)
                .feedbackMode(mode)
                .errorConsole(errorConsole)
                .build();
        WeakReference<Activity> ref = new WeakReference<>(a);
        Context app = a.getApplicationContext();
        TaskDialog.start(a, options, runner, (s, result) -> {
            Activity act = ref.get();
            Activity live = act == null ? null : live(act);
            // The activity carries the launcher's language; after it is gone the application context will do.
            Context ctx = act != null && !act.isDestroyed() ? act : app;
            finished.onFinished(ctx, live, result);
        });
    }

    /** The context for task log texts: the activity while it lives (it carries the launcher's language), else the app. */
    static Supplier<Context> texts(Context context) {
        WeakReference<Context> ref = new WeakReference<>(context);
        Context app = context.getApplicationContext();
        return () -> {
            Context c = ref.get();
            return c != null ? c : app;
        };
    }

    static String instanceName(@Nullable GameVersion v) {
        return PackagesActivity.Instances.displayName(v);
    }

    private static List<String> identities(List<InstalledPackages.Pkg> pkgs) {
        List<String> out = new ArrayList<>();
        for (InstalledPackages.Pkg p : pkgs) {
            out.add(p.identity);
        }
        return out;
    }

    @Nullable
    private static Activity live(Activity a) {
        return a.isFinishing() || a.isDestroyed() ? null : a;
    }

    private static Done once(@Nullable Done done) {
        boolean[] called = {false};
        return changed -> {
            if (!called[0]) {
                called[0] = true;
                if (done != null) {
                    done.onDone(changed);
                }
            }
        };
    }

    static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    static void toastResult(Context c, TaskSession.Result result) {
        toast(c, c.getString(R.string.pkg_operation_failed, PkgUi.ErrorText.describe(c, result)));
    }

    static void toastError(Context c, String code, @Nullable String detail) {
        toast(c, c.getString(R.string.pkg_operation_failed, PkgUi.ErrorText.describe(c, code, detail == null ? "" : detail)));
    }

    // #endregion

    public final static class Batch {
        private Batch() {
        }

        public static final class Failure {
            public final String name;
            public final String code;

            Failure(String name, String code) {
                this.name = name;
                this.code = code;
            }

            @Override
            public String toString() {
                return name + ": " + code;
            }
        }

        public static final class Outcome {
            public final List<String> done = new ArrayList<>();
            public final List<String> demoted = new ArrayList<>();
            public final List<Failure> failed = new ArrayList<>();
            public boolean changed;
            public boolean cancelled;

            public String code() {
                if (cancelled) {
                    return PackageService.ERR_PKG_TASK_CANCELLED;
                }
                return failed.isEmpty() ? "" : failed.get(0).code;
            }

            public boolean isEmpty() {
                return done.isEmpty() && demoted.isEmpty() && failed.isEmpty();
            }

            @Override
            public String toString() {
                return "Outcome{done=" + done + " demoted=" + demoted + " failed=" + failed + " cancelled=" + cancelled
                        + "}";
            }
        }

        public static List<InstalledFragment.Model.Row> updatable(List<InstalledFragment.Model.Row> rows) {
            List<InstalledFragment.Model.Row> out = new ArrayList<>();
            for (InstalledFragment.Model.Row r : rows) {
                if (r.canUpdate() && r.identifier != null) {
                    out.add(r);
                }
            }
            return out;
        }

        public static String checkUpdate(InstalledFragment.Model.Row row) {
            if (row.leviLamina) {
                return PackageService.ERR_LL_MANAGED_IN_VERSION_SETTINGS;
            }
            if (row.identifier == null || row.update == InstalledFragment.Model.UpdateState.NO_SOURCE
                    || row.targetVersion.isEmpty()) {
                return PackageService.ERR_NO_UPDATE_SOURCE;
            }
            if (row.update != InstalledFragment.Model.UpdateState.AVAILABLE) {
                return PackageService.ERR_ALREADY_LATEST;
            }
            return "";
        }

        public static Outcome update(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v,
                                     List<InstalledFragment.Model.Row> rows) {
            Outcome out = new Outcome();
            List<InstalledFragment.Model.Row> items = updatable(rows);
            Set<String> handled = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                InstalledFragment.Model.Row row = items.get(i);
                if (tools.isCancelled()) {
                    out.cancelled = true;
                    break;
                }
                tools.setOuter(i, items.size(), row.name);
                if (handled.add(row.key()) && !updateItem(tools, texts, v, row, out)) {
                    break;
                }
            }
            if (!out.cancelled) {
                tools.setOuter(items.size(), items.size(), "");
            }
            return out;
        }

        public static Outcome updateOne(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v, InstalledFragment.Model.Row row) {
            Outcome out = new Outcome();
            updateItem(tools, texts, v, row, out);
            return out;
        }

        private static boolean updateItem(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v,
                                          InstalledFragment.Model.Row row, Outcome out) {
            tools.addLog(TaskSession.LogLevel.INFO, texts.get().getString(R.string.pkg_log_update, row.name));
            String err = checkUpdate(row);
            if (err.isEmpty()) {
                err = PackageService.get().installPackage(tools.cancel(), tools.listener(), v, row.identifier, row.targetVersion);
                out.changed = true;
            }
            return record(tools, row, err, out);
        }

        /** Adds row's result to out; false when the batch stops (cancelled). */
        private static boolean record(TaskSession.Tools tools, InstalledFragment.Model.Row row, String err, Outcome out) {
            if (err.isEmpty()) {
                out.done.add(row.name);
                tools.addLog(TaskSession.LogLevel.SUCCESS, row.name + ": ok");
                return true;
            }
            tools.addLog(TaskSession.LogLevel.ERROR, row.name + ": " + err);
            out.failed.add(new Failure(row.name, err));
            out.cancelled = PackageService.ERR_PKG_TASK_CANCELLED.equals(err);
            return !out.cancelled;
        }

        public static Outcome uninstall(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v,
                                        List<InstalledFragment.Model.Row> rows) {
            Outcome out = new Outcome();
            Set<String> handled = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                InstalledFragment.Model.Row row = rows.get(i);
                if (tools.isCancelled()) {
                    out.cancelled = true;
                    break;
                }
                tools.setOuter(i, rows.size(), row.name);
                if (handled.add(row.key()) && !uninstallItem(tools, texts, v, row, out)) {
                    break;
                }
            }
            if (!out.cancelled) {
                tools.setOuter(rows.size(), rows.size(), "");
            }
            return out;
        }

        public static Outcome uninstallOne(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v, InstalledFragment.Model.Row row) {
            Outcome out = new Outcome();
            uninstallItem(tools, texts, v, row, out);
            return out;
        }

        /** Uninstalls row; the engine refuses a dependency (ERR_PACKAGE_REQUIRED_BY_DEPENDENTS). */
        private static boolean uninstallItem(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v,
                                             InstalledFragment.Model.Row row, Outcome out) {
            if (row.leviLamina) {
                return record(tools, row, PackageService.ERR_LL_MANAGED_IN_VERSION_SETTINGS, out);
            }
            tools.addLog(TaskSession.LogLevel.INFO, texts.get().getString(R.string.pkg_log_uninstall, row.name));
            String code = PackageService.get().uninstallById(tools.cancel(), tools.listener(), v, row.pkg.id, row.pkg.identity);
            out.changed = true;
            if (PackageService.ERR_PACKAGE_DEMOTED_TO_DEPENDENCY.equals(code)) {
                tools.addLog(TaskSession.LogLevel.WARNING, row.name + ": " + code);
                out.demoted.add(row.name);
                return true;
            }
            List<String> kept = code.isEmpty()
                    ? InstalledPackages.keptFolders(PackageService.get().workspaceDir(v), row.pkg) : Collections.emptyList();
            if (!kept.isEmpty()) {
                tools.addLog(TaskSession.LogLevel.WARNING, keptUserData(texts, row.name, kept));
            }
            return record(tools, row, code, out);
        }

        static String keptUserData(Supplier<Context> texts, String name, List<String> folders) {
            return texts.get().getString(R.string.pkg_log_kept_user_data, name, String.join(", ", folders));
        }

        /** Marks a dependency as installed by the user: its recorded source installed again at its version. */
        public static String promote(TaskSession.Tools tools, Supplier<Context> texts, GameVersion v, InstalledFragment.Model.Row row) {
            if (row.leviLamina) {
                return PackageService.ERR_LL_MANAGED_IN_VERSION_SETTINGS;
            }
            String version = row.pkg.version.trim();
            if (version.isEmpty()) {
                return PackageService.ERR_PACKAGE_VERSION_REQUIRED;
            }
            tools.addLog(TaskSession.LogLevel.INFO, texts.get().getString(R.string.pkg_log_promote, row.name, version));
            String source = promoteSource(row.pkg.identity, row.pkg.ref, version);
            if (source == null) {
                return PackageService.ERR_PACKAGE_INVALID_IDENTIFIER;
            }
            return PackageService.get().installSource(tools.cancel(), tools.listener(), v, source);
        }

        @Nullable
        static String promoteSource(String identity, String ref, String version) {
            String r = ref == null ? "" : ref.trim();
            if (r.regionMatches(true, 0, "file:", 0, 5) || r.startsWith("https://")) {
                // A local or URL source is installed again from where it came from.
                return Source.check(r) == null ? r : null;
            }
            return identity + "@" + version;
        }

        public static List<InstalledFragment.Model.Row> distinct(List<InstalledFragment.Model.Row> rows) {
            List<InstalledFragment.Model.Row> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (InstalledFragment.Model.Row r : rows) {
                if (seen.add(PackageService.key(r.pkg.identity))) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    public final static class BatchResultDialog {
        private BatchResultDialog() {
        }

        static int titleRes(PackageActions.Batch.Outcome o) {
            if (o.failed.isEmpty()) {
                return R.string.pkg_result_title_done;
            }
            if (!o.done.isEmpty() || !o.demoted.isEmpty()) {
                return R.string.pkg_result_title_partial;
            }
            return R.string.pkg_result_title_failed;
        }

        public static void show(Activity a, PackageActions.Batch.Outcome o) {
            if (a.isFinishing() || a.isDestroyed() || o.isEmpty()) {
                return;
            }
            View body = LayoutInflater.from(a).inflate(R.layout.dialog_pkg_batch_result, null, false);
            LinearLayout sections = body.findViewById(R.id.pkg_result_sections);
            addSection(a, sections, R.string.pkg_result_done, a.getColor(R.color.pkg_status_success), o.done);
            addSection(a, sections, R.string.pkg_result_kept, a.getColor(R.color.pkg_log_warning), o.demoted);
            List<String> failed = new ArrayList<>();
            for (PackageActions.Batch.Failure f : o.failed) {
                failed.add(a.getString(R.string.pkg_result_item_error, f.name, PkgUi.ErrorText.describe(a, f.code)));
            }
            addSection(a, sections, R.string.pkg_result_failed, a.getColor(R.color.error), failed);
            new CustomAlertDialog(a)
                    .setTitleText(a.getString(titleRes(o)))
                    .setCustomView(body)
                    .setPositiveButton(a.getString(R.string.pkg_task_close), null)
                    .show();
        }

        private static void addSection(Activity a, ViewGroup parent, int heading, int color, List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            View section = LayoutInflater.from(a).inflate(R.layout.item_pkg_result_section, parent, false);
            TextView title = section.findViewById(R.id.pkg_result_heading);
            TextView list = section.findViewById(R.id.pkg_result_items);
            title.setText(a.getString(heading) + " (" + items.size() + ")");
            title.setTextColor(color);
            list.setText(String.join("\n", items));
            parent.addView(section);
        }
    }
}
