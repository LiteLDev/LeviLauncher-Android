package org.levimc.launcher.ui.packages;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TaskDialog extends Dialog implements TaskSession.Observer, DefaultLifecycleObserver {
    private final Activity activity;
    private final TaskSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView titleView;
    private TextView targetView;
    private TextView statusChip;
    private TextView progressLabel;
    private TaskProgressView progressView;
    private TextView errorView;
    private TextView logToggle;
    private TextView logTitle;
    private View logPanel;
    private RecyclerView logList;
    private TextView logEmpty;
    private Button copyButton;
    private View buttonSpacing;
    private Button mainButton;
    private LogAdapter logAdapter;

    private boolean logExpanded;
    private Boolean compactShown;
    private TaskSession.Status lastStatus;
    private boolean closing;
    private int accent;

    protected TaskDialog(@NonNull Activity activity, @NonNull TaskSession session) {
        super(activity);
        this.activity = activity;
        this.session = session;
    }

    // #region Entry points

    public static TaskSession start(@NonNull Activity activity, @NonNull TaskSession.Options options,
                                       @NonNull TaskSession.Runner runner,
                                       @Nullable TaskSession.Callback callback) {
        final TaskDialog[] shown = new TaskDialog[1];
        // The session (kept in a static registry while it runs, up to the task
        // timeout) must not keep a closed activity alive through this wrapper.
        final WeakReference<Activity> activityRef = new WeakReference<>(activity);
        TaskSession.Callback wrapped = (s, result) -> {
            Activity a = activityRef.get();
            if (a != null && result.status == TaskSession.Status.FAILED && s.errorConsole()
                    && (shown[0] == null || !shown[0].isShowing())) {
                show(a, s);
            }
            if (callback != null) {
                callback.onFinished(s, result);
            }
        };
        TaskSession session = TaskSession.start(activity, options, runner, wrapped);
        TaskSession.FeedbackMode mode = options.feedbackMode;
        if (session.isRunning() && mode == TaskSession.FeedbackMode.PROGRESS) {
            shown[0] = show(activity, session);
        }
        return session;
    }

    @Nullable
    public static TaskDialog show(@NonNull Activity activity, @NonNull TaskSession session) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        TaskSession.Status status = session.status();
        boolean failedConsole = status == TaskSession.Status.FAILED && session.errorConsole();
        if (status != TaskSession.Status.RUNNING && !failedConsole) {
            return null;
        }
        TaskDialog dialog = new TaskDialog(activity, session);
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException e) {
            // onCreate already ran inside show() and registered the dialog with
            // the session and the activity's lifecycle: undo that.
            dialog.closing = true;
            dialog.release();
            return null;
        }
        return dialog;
    }

    public TaskSession session() {
        return session;
    }

    // #endregion

    // #region Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_pkg_task);
        org.levimc.launcher.ui.dialogs.LauncherDialogs.bind(this, 620);
        setCanceledOnTouchOutside(false);

        titleView = findViewById(R.id.pkg_task_title);
        targetView = findViewById(R.id.pkg_task_target);
        statusChip = findViewById(R.id.pkg_task_status_chip);
        progressLabel = findViewById(R.id.pkg_task_progress_label);
        progressView = findViewById(R.id.pkg_task_progress);
        errorView = findViewById(R.id.pkg_task_error);
        logToggle = findViewById(R.id.pkg_task_log_toggle);
        logTitle = findViewById(R.id.pkg_task_log_title);
        logPanel = findViewById(R.id.pkg_task_log_panel);
        logList = findViewById(R.id.pkg_task_log_list);
        logEmpty = findViewById(R.id.pkg_task_log_empty);
        copyButton = findViewById(R.id.pkg_task_btn_copy);
        buttonSpacing = findViewById(R.id.pkg_task_btn_spacing);
        mainButton = findViewById(R.id.pkg_task_btn_main);

        progressView.setHideWhenIdle(false);
        progressView.setShowStatus(false);
        progressView.bind(session);

        logAdapter = new LogAdapter(getContext());
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true);
        logList.setLayoutManager(lm);
        logList.setAdapter(logAdapter);
        logList.setItemAnimator(null);

        logToggle.setOnClickListener(v -> {
            logExpanded = !logExpanded;
            render();
        });
        copyButton.setOnClickListener(v -> copyLogs());
        mainButton.setOnClickListener(v -> {
            if (session.isRunning()) {
                session.cancel();
            } else {
                dismiss();
            }
        });

        accent = TaskProgressView.accentColor(getContext());
        mainButton.setBackgroundTintList(ColorStateList.valueOf(accent));
        mainButton.setTextColor(Color.WHITE);
        DynamicAnim.applyPressScale(mainButton);
        DynamicAnim.applyPressScale(copyButton);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.6f;
            window.setAttributes(params);
        }

        session.addObserver(this);
        if (activity instanceof LifecycleOwner) {
            ((LifecycleOwner) activity).getLifecycle().addObserver(this);
        }
        lastStatus = session.status();
        render();

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
        if (closing) {
            return;
        }
        closing = true;
        handler.removeCallbacksAndMessages(null);
        session.removeObserver(this);
        View content = findViewById(android.R.id.content);
        Window window = getWindow();
        if (content == null || window == null || window.getDecorView().getParent() == null || !isShowing()) {
            dismissNow();
            return;
        }
        DynamicAnim.animateDialogDismiss(content, this::dismissNow);
    }

    private void dismissNow() {
        release();
        try {
            if (isShowing()) {
                super.dismiss();
            }
        } catch (RuntimeException ignored) {
            // The window is already gone (activity destroyed).
        }
    }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        session.removeObserver(this);
        if (progressView != null) {
            progressView.unbind();
        }
        if (activity instanceof LifecycleOwner) {
            ((LifecycleOwner) activity).getLifecycle().removeObserver(this);
        }
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        closing = true;
        dismissNow();
    }

    // #endregion

    // #region Rendering

    @Override
    public void onTaskSessionChanged(TaskSession s) {
        if (closing) {
            return;
        }
        TaskSession.Status status = s.status();
        if (status != lastStatus) {
            lastStatus = status;
            switch (status) {
                case SUCCESS:
                    dismiss();
                    return;
                case CANCELLED:
                    dismiss();
                    return;
                case FAILED:
                    if (!s.errorConsole()) {
                        dismiss();
                        return;
                    }
                    break;
                default:
                    break;
            }
        }
        render();
    }

    private void render() {
        if (titleView == null) {
            return;
        }
        Context ctx = getContext();
        TaskSession.Status status = session.status();
        boolean running = status == TaskSession.Status.RUNNING;
        boolean compact = session.feedbackMode() == TaskSession.FeedbackMode.PROGRESS
                && status != TaskSession.Status.FAILED;

        titleView.setText(title(ctx, session));
        String target = session.target();
        targetView.setText(target.isEmpty() ? "-" : target);
        statusChip.setText(TaskProgressView.statusLabel(ctx, status, session.isCancelRequested()));
        int statusColor = TaskProgressView.statusColor(ctx, status, accent);
        statusChip.setBackgroundTintList(ColorStateList.valueOf(withAlpha(statusColor, 40)));
        statusChip.setTextColor(statusColor);

        progressLabel.setVisibility(compact ? View.GONE : View.VISIBLE);

        TaskSession.Result result = session.result();
        if (!compact && result != null && !result.isSuccess()) {
            errorView.setVisibility(View.VISIBLE);
            errorView.setText(PkgUi.ErrorText.full(ctx, result.code, result.detail));
        } else {
            errorView.setVisibility(View.GONE);
        }

        if (compact) {
            logToggle.setVisibility(View.VISIBLE);
            logToggle.setText(logExpanded ? R.string.pkg_task_hide_log : R.string.pkg_task_show_log);
            logToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            logTitle.setVisibility(View.GONE);
            logPanel.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
        } else {
            logToggle.setVisibility(View.GONE);
            logTitle.setVisibility(View.VISIBLE);
            logPanel.setVisibility(View.VISIBLE);
        }
        if (logPanel.getVisibility() == View.VISIBLE) {
            List<TaskSession.LogItem> logs = session.logs();
            logAdapter.update(logs);
            logEmpty.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
            if (!logs.isEmpty()) {
                logList.scrollToPosition(logs.size() - 1);
            }
        }

        copyButton.setVisibility(compact ? View.GONE : View.VISIBLE);
        buttonSpacing.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (running) {
            mainButton.setText(session.isCancelRequested() ? R.string.pkg_task_cancelling : R.string.pkg_task_cancel);
            mainButton.setEnabled(!session.isCancelRequested());
        } else {
            mainButton.setText(R.string.pkg_task_close);
            mainButton.setEnabled(true);
        }
        setCancelable(!running);

        if (compactShown == null || compactShown != compact) {
            compactShown = compact;
            applySize(compact);
        }
    }

    private void applySize(boolean compact) {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        float density = getContext().getResources().getDisplayMetrics().density;
        int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;
        int width = compact
                ? Math.min((int) (screenWidth * 0.9f), (int) (400 * density))
                : Math.min((int) (screenWidth * 0.94f), (int) (600 * density));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);

        // Upstream: min(30vh, 16rem) (at least 8rem) compact, a larger panel in the console.
        int logHeight = compact
                ? Math.max((int) (128 * density), Math.min((int) (screenHeight * 0.3f), (int) (256 * density)))
                : Math.max((int) (120 * density), Math.min((int) (screenHeight * 0.4f), (int) (320 * density)));
        ViewGroup.LayoutParams lp = logPanel.getLayoutParams();
        lp.height = logHeight;
        logPanel.setLayoutParams(lp);
    }

    static String title(Context ctx, TaskSession session) {
        int id = session.options().title;
        String base = ctx.getString(id);
        String target = session.target();
        return target.isEmpty() ? base : ctx.getString(R.string.pkg_task_title_with_target, base, target);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // #endregion

    // #region Copy

    private void copyLogs() {
        Context ctx = getContext();
        String text = logText(ctx, session);
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                throw new IllegalStateException("no clipboard");
            }
            cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.pkg_task_log_title), text));
            Toast.makeText(ctx, R.string.pkg_task_copy_success, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(ctx, R.string.pkg_task_copy_failed, Toast.LENGTH_SHORT).show();
        }
    }

    static String logText(Context ctx, TaskSession session) {
        StringBuilder b = new StringBuilder();
        String target = session.target();
        b.append(ctx.getString(R.string.pkg_task_target_label)).append(": ")
                .append(target.isEmpty() ? "-" : target).append('\n');
        b.append(ctx.getString(R.string.pkg_task_status_label)).append(": ")
                .append(TaskProgressView.statusLabel(ctx, session.status(), session.isCancelRequested()))
                .append('\n');
        TaskSession.Result result = session.result();
        if (result != null && !result.isSuccess()) {
            b.append(ctx.getString(R.string.pkg_task_status_failed)).append(": ")
                    .append(PkgUi.ErrorText.full(ctx, result.code, result.detail)).append('\n');
        }
        b.append('\n');
        DateFormat time = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault());
        for (TaskSession.LogItem item : session.logs()) {
            b.append('[').append(time.format(new Date(item.timestamp))).append("] [")
                    .append(item.level.value()).append("] ").append(item.message).append('\n');
        }
        return b.toString();
    }

    // #endregion

    private static final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {
        private final List<TaskSession.LogItem> items = new ArrayList<>();
        private final DateFormat time = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault());
        private final int colorDefault;
        private final int colorError;
        private final int colorWarning;
        private final int colorSuccess;

        LogAdapter(Context ctx) {
            colorDefault = ctx.getColor(R.color.text_primary);
            colorError = ctx.getColor(R.color.error);
            colorWarning = ctx.getColor(R.color.pkg_log_warning);
            colorSuccess = ctx.getColor(R.color.pkg_log_success);
        }

        void update(List<TaskSession.LogItem> next) {
            if (items.isEmpty() || next.isEmpty()) {
                items.clear();
                items.addAll(next);
                notifyDataSetChanged();
                return;
            }
            long firstNew = next.get(0).id;
            long lastOld = items.get(items.size() - 1).id;
            int removed = 0;
            while (removed < items.size() && items.get(removed).id < firstNew) {
                removed++;
            }
            if (removed > 0) {
                items.subList(0, removed).clear();
                notifyItemRangeRemoved(0, removed);
            }
            int start = items.size();
            for (TaskSession.LogItem item : next) {
                if (item.id > lastOld) {
                    items.add(item);
                }
            }
            if (items.size() > start) {
                notifyItemRangeInserted(start, items.size() - start);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pkg_task_log, parent, false);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            TaskSession.LogItem item = items.get(position);
            holder.text.setText("[" + time.format(new Date(item.timestamp)) + "] " + item.message);
            int color;
            switch (item.level) {
                case ERROR:
                    color = colorError;
                    break;
                case WARNING:
                    color = colorWarning;
                    break;
                case SUCCESS:
                    color = colorSuccess;
                    break;
                default:
                    color = colorDefault;
                    break;
            }
            holder.text.setTextColor(color);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(TextView text) {
                super(text);
                this.text = text;
            }
        }
    }
}
