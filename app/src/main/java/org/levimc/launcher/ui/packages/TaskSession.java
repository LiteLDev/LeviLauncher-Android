package org.levimc.launcher.ui.packages;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.StringRes;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.core.packages.engine.Listener;
import org.levimc.launcher.core.packages.engine.PackageException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * One package task the UI runs and shows: a runner on a worker thread that reports through {@link Tools}
 * (the engine's log and progress, plus a batch counter), a cancellable token with the task timeout, and
 * the result code. Sessions live in the process while they run, so a screen opened later finds them.
 * All state changes and callbacks happen on the main thread.
 */
public final class TaskSession {
    public static final int MAX_LOG_COUNT = 800;

    /** How a screen shows the session: dialog with progress, inline, or a console on error only. */
    public enum FeedbackMode { PROGRESS, INLINE, ON_ERROR }

    public enum Status { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

    public enum LogLevel {
        INFO, SUCCESS, WARNING, ERROR;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final class LogItem {
        public final long id, timestamp;
        public final LogLevel level;
        public final String message;

        LogItem(long id, long timestamp, LogLevel level, String message) {
            this.id = id;
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }

        @Override
        public String toString() {
            return "[" + level.value() + "] " + message;
        }
    }

    public static final class Options {
        /** The title (pkg_task_title_*). */
        @StringRes
        public final int title;
        /** The instance (task target name) the session changes; "" for none. */
        public final String target;
        /** Tags of what it does ("Install", "Update", ...), for {@link #findRunning}. */
        public final List<String> methods;
        public final FeedbackMode feedbackMode;
        public final boolean errorConsole;

        private Options(Builder b) {
            title = b.title;
            target = b.target == null ? "" : b.target.trim();
            methods = Collections.unmodifiableList(new ArrayList<>(b.methods));
            feedbackMode = b.feedbackMode;
            errorConsole = b.errorConsole;
        }

        public static Builder builder(String target) {
            return new Builder(target);
        }

        public static final class Builder {
            private final String target;
            private final List<String> methods = new ArrayList<>();
            private int title = R.string.pkg_task_title_generic;
            private FeedbackMode feedbackMode = FeedbackMode.PROGRESS;
            private boolean errorConsole = true;

            Builder(String target) {
                this.target = target;
            }

            public Builder title(@StringRes int title) {
                this.title = title;
                return this;
            }

            public Builder methods(String... methods) {
                Collections.addAll(this.methods, methods);
                return this;
            }

            public Builder methods(Collection<String> methods) {
                this.methods.addAll(methods);
                return this;
            }

            public Builder feedbackMode(FeedbackMode mode) {
                this.feedbackMode = mode;
                return this;
            }

            public Builder errorConsole(boolean errorConsole) {
                this.errorConsole = errorConsole;
                return this;
            }

            public Options build() {
                return new Options(this);
            }
        }
    }

    /** What a runner reports through; usable from any thread. */
    public interface Tools {
        void addLog(LogLevel level, String message);

        /** The batch counter: item completed of total, labelled. */
        void setOuter(int completed, int total, String label);

        /** The task's token: cancelled by {@link #cancel} or the task timeout. */
        Cancel cancel();

        /** The engine listener feeding this session's log and bars. */
        Listener listener();

        boolean isCancelled();
    }

    @FunctionalInterface
    public interface Runner {
        /** Returns "" or a result code. */
        String run(Tools tools) throws Exception;
    }

    @FunctionalInterface
    public interface Callback {
        void onFinished(TaskSession session, Result result);
    }

    @FunctionalInterface
    public interface Observer {
        void onTaskSessionChanged(TaskSession session);
    }

    /** Told when any session starts or finishes (refreshes of screens showing an instance). */
    public interface GlobalListener {
        void onSessionStarted(TaskSession session);

        void onSessionFinished(TaskSession session);
    }

    public static final class Result {
        public final Status status;
        public final String code, detail;

        Result(Status status, String code, String detail) {
            this.status = status;
            this.code = code;
            this.detail = detail;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean isCancelled() {
            return status == Status.CANCELLED;
        }

        @Override
        public String toString() {
            return "Result{" + status + " " + code + " " + detail + "}";
        }
    }

    /** Threads and clock of sessions (tests replace them). */
    public static final class Env {
        final Executor main, worker;
        final LongSupplier clock;

        public Env(Executor main, Executor worker, LongSupplier clock) {
            this.main = main;
            this.worker = worker;
            this.clock = clock;
        }

        private static Env defaults;

        static synchronized Env defaults() {
            if (defaults == null) {
                Handler handler = new Handler(Looper.getMainLooper());
                defaults = new Env(handler::post, PackageService.EXECUTOR, System::currentTimeMillis);
            }
            return defaults;
        }
    }

    private static final List<TaskSession> RUNNING = new ArrayList<>();
    private static final List<GlobalListener> GLOBAL = new CopyOnWriteArrayList<>();

    private final Env env;
    private final Context app;
    private final Options options;
    private final Cancel cancel = new Cancel().withTimeout(PackageService.TASK_TIMEOUT_MS);
    private final ProgressModel progress = new ProgressModel();
    private final Deque<LogItem> logs = new ArrayDeque<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final List<Callback> callbacks = new ArrayList<>();
    private final Map<String, ProgressModel.Bar> pendingBars = new LinkedHashMap<>();
    private boolean flushScheduled;
    private long logSeq;
    private Status status = Status.IDLE;
    private String lastError = "";
    private Result result;

    private TaskSession(Env env, Context context, Options options) {
        this.env = env;
        this.app = context == null ? null : context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.options = options;
    }

    public static TaskSession start(Context context, Options options, Runner runner, Callback callback) {
        return start(Env.defaults(), context, options, runner, callback);
    }

    public static TaskSession start(Env env, Context context, Options options, Runner runner, Callback callback) {
        TaskSession s = new TaskSession(env, context, options);
        if (callback != null) s.callbacks.add(callback);
        s.status = Status.RUNNING;
        s.appendLog(LogLevel.INFO, s.text(R.string.pkg_task_waiting, "Waiting to start"));
        RUNNING.add(s);
        for (GlobalListener l : GLOBAL) l.onSessionStarted(s);
        Tools tools = s.new SessionTools();
        env.worker.execute(() -> {
            String code;
            String detail = "";
            try {
                code = runner.run(tools);
            } catch (Exception e) {
                PackageService.warn("package task failed", e);
                code = PackageService.code(e);
                detail = PackageService.message(e);
            }
            String c = code == null ? "" : code, d = detail;
            env.main.execute(() -> s.complete(c, d));
        });
        return s;
    }

    /** The running session matching options (same target and a shared method), with callback added; or null. */
    public static TaskSession observe(Context context, Options options, Callback callback) {
        TaskSession s = findRunning(options.target, options.methods);
        if (s != null && callback != null) s.callbacks.add(callback);
        return s;
    }

    public static List<TaskSession> running() {
        return new ArrayList<>(RUNNING);
    }

    /** The latest running session on target ("" = any) sharing one of methods (empty = any); or null. */
    public static TaskSession findRunning(String target, Collection<String> methods) {
        String t = target == null ? "" : target.trim();
        for (int i = RUNNING.size() - 1; i >= 0; i--) {
            TaskSession s = RUNNING.get(i);
            if (!t.isEmpty() && !s.options.target.isEmpty() && !t.equalsIgnoreCase(s.options.target)) continue;
            if (methods != null && !methods.isEmpty() && !s.options.methods.isEmpty()
                    && Collections.disjoint(lower(methods), lower(s.options.methods))) continue;
            return s;
        }
        return null;
    }

    private static List<String> lower(Collection<String> values) {
        List<String> out = new ArrayList<>();
        for (String v : values) out.add(v.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    public static void addGlobalListener(GlobalListener l) {
        GLOBAL.add(l);
    }

    public static void removeGlobalListener(GlobalListener l) {
        GLOBAL.remove(l);
    }

    // #region State

    public Options options() {
        return options;
    }

    public String target() {
        return options.target;
    }

    public List<String> methods() {
        return options.methods;
    }

    public FeedbackMode feedbackMode() {
        return options.feedbackMode;
    }

    public boolean errorConsole() {
        return options.errorConsole;
    }

    public Status status() {
        return status;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public List<LogItem> logs() {
        return new ArrayList<>(logs);
    }

    public ProgressModel progress() {
        return progress;
    }

    public Result result() {
        return result;
    }

    /** Whether the user cancelled it (not a timeout). */
    public boolean isCancelRequested() {
        return cancel.reason() == PackageException.Kind.CANCELLED;
    }

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    public void addCallback(Callback callback) {
        if (callback == null) return;
        if (result != null) callback.onFinished(this, result);
        else callbacks.add(callback);
    }

    /** Requests cancellation: the engine stops and undoes its changes; the session ends as cancelled. */
    public boolean cancel() {
        if (status != Status.RUNNING) return false;
        if (!cancel.isCancelled()) {
            appendLog(LogLevel.WARNING, text(R.string.pkg_task_log_cancelling, "Cancelling"));
            cancel.cancel();
            notifyChanged();
        }
        return true;
    }

    // #endregion

    // #region Reporting

    private final class SessionTools implements Tools, Listener {
        @Override
        public void addLog(LogLevel level, String message) {
            env.main.execute(() -> {
                if (status == Status.RUNNING) {
                    appendLog(level == null ? LogLevel.INFO : level, message);
                    notifyChanged();
                }
            });
        }

        @Override
        public void setOuter(int completed, int total, String label) {
            env.main.execute(() -> {
                if (status == Status.RUNNING) {
                    progress.setOuter(completed, total, label);
                    notifyChanged();
                }
            });
        }

        @Override
        public Cancel cancel() {
            return cancel;
        }

        @Override
        public Listener listener() {
            return this;
        }

        @Override
        public boolean isCancelled() {
            return cancel.isCancelled();
        }

        @Override
        public void log(Level level, String message) {
            if (level == Level.ERROR) {
                env.main.execute(() -> lastError = message == null ? "" : message.trim());
            }
            addLog(level == Level.ERROR ? LogLevel.ERROR : level == Level.WARN ? LogLevel.WARNING : LogLevel.INFO, message);
        }

        @Override
        public void progress(String id, Stage stage, String pkg, long current, long total, boolean bytes) {
            synchronized (pendingBars) {
                pendingBars.put(id, new ProgressModel.Bar(id, stage, pkg, current, total, bytes));
                if (flushScheduled) return;
                flushScheduled = true;
            }
            // Coalesced: at most one pending flush, so the UI renders at its own pace.
            env.main.execute(TaskSession.this::flushBars);
        }
    }

    private void flushBars() {
        List<ProgressModel.Bar> bars;
        synchronized (pendingBars) {
            bars = new ArrayList<>(pendingBars.values());
            pendingBars.clear();
            flushScheduled = false;
        }
        if (status != Status.RUNNING) return;
        for (ProgressModel.Bar b : bars) progress.apply(b);
        notifyChanged();
    }

    private void appendLog(LogLevel level, String message) {
        String m = message == null ? "" : message.trim();
        if (m.isEmpty()) return;
        logs.addLast(new LogItem(++logSeq, env.clock.getAsLong(), level, m));
        while (logs.size() > MAX_LOG_COUNT) logs.removeFirst();
    }

    private void complete(String code, String detail) {
        if (status != Status.RUNNING) return;
        RUNNING.remove(this);
        String c = code.trim();
        if (c.isEmpty()) {
            status = Status.SUCCESS;
            result = new Result(status, "", "");
        } else {
            String d = !lastError.isEmpty() ? lastError : detail.trim();
            appendLog(LogLevel.ERROR, c);
            status = isCancelRequested() || PackageService.ERR_PKG_TASK_CANCELLED.equals(c) ? Status.CANCELLED : Status.FAILED;
            result = new Result(status, c, d);
        }
        progress.clearBars();
        notifyChanged();
        List<Callback> cbs = new ArrayList<>(callbacks);
        callbacks.clear();
        for (Callback cb : cbs) {
            try {
                cb.onFinished(this, result);
            } catch (RuntimeException e) {
                PackageService.warn("package task callback failed", e);
            }
        }
        for (GlobalListener l : GLOBAL) l.onSessionFinished(this);
    }

    private void notifyChanged() {
        for (Observer o : observers) o.onTaskSessionChanged(this);
    }

    private String text(int res, String fallback) {
        try {
            return app == null ? fallback : app.getString(res);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // #endregion

    /** The bars of a session: the engine's open progress bars and the batch counter. */
    public static final class ProgressModel {
        /** One engine progress bar. */
        public static final class Bar {
            public final String id, packageName;
            public final Listener.Stage stage;
            public final long current, total;
            public final boolean bytes;

            Bar(String id, Listener.Stage stage, String pkg, long current, long total, boolean bytes) {
                this.id = id;
                this.stage = stage;
                this.packageName = pkg == null ? "" : pkg;
                this.current = current;
                this.total = total;
                this.bytes = bytes;
            }

            /** 0-100, or null when the total is unknown. */
            public Double percentage() {
                return total <= 0 ? null : clampPercentage(100.0 * current / total);
            }

            public boolean done() {
                return total >= 0 && current >= total;
            }
        }

        /** The batch counter. */
        public static final class Outer {
            public final int current, total;
            public final String label;

            Outer(int current, int total, String label) {
                this.current = current;
                this.total = total;
                this.label = label == null ? "" : label;
            }

            public double percentage() {
                return total <= 0 ? 0 : clampPercentage(100.0 * current / total);
            }
        }

        private final Map<String, Bar> bars = new LinkedHashMap<>();
        private Outer outer;

        void apply(Bar b) {
            if (b.done()) bars.remove(b.id);
            else bars.put(b.id, b);
        }

        void setOuter(int completed, int total, String label) {
            outer = total > 0 ? new Outer(completed, total, label) : null;
            bars.clear();
        }

        void clearBars() {
            bars.clear();
        }

        /** The open bars, oldest first. */
        public List<Bar> bars() {
            return new ArrayList<>(bars.values());
        }

        public Outer outer() {
            return outer;
        }

        public static double clampPercentage(double v) {
            return Double.isNaN(v) ? 0 : Math.max(0, Math.min(100, v));
        }
    }
}
