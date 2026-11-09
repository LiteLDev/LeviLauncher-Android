package org.levimc.launcher.ui.packages;

import android.content.Context;
import android.graphics.Color;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.ArrayList;
import java.util.List;

public class TaskProgressView extends LinearLayout implements TaskSession.Observer {
    public static final int MAX_SECONDARY = 3;

    private final BarRow outerRow;
    private final BarRow primaryRow;
    private final LinearLayout secondaryContainer;
    private final List<BarRow> secondaryRows = new ArrayList<>();
    private final View statusRow;
    private final TextView statusText;
    private final TextView cancelLink;

    private TaskSession session;
    private boolean attached;
    private boolean hideWhenIdle = true;
    private boolean showStatus = true;
    private boolean showCancel = true;
    private int accent;
    private boolean accentResolved;

    public TaskProgressView(Context context) {
        this(context, null);
    }

    public TaskProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_pkg_task_progress, this, true);
        outerRow = new BarRow(findViewById(R.id.pkg_progress_outer));
        primaryRow = new BarRow(findViewById(R.id.pkg_progress_primary));
        secondaryContainer = findViewById(R.id.pkg_progress_secondary);
        statusRow = findViewById(R.id.pkg_progress_status_row);
        statusText = findViewById(R.id.pkg_progress_status);
        cancelLink = findViewById(R.id.pkg_progress_cancel);
        cancelLink.setOnClickListener(v -> {
            if (session != null) {
                session.cancel();
            }
        });
        outerRow.setEmphasis(true);
        primaryRow.setEmphasis(true);
        if (isInEditMode()) {
            return;
        }
        setVisibility(GONE);
    }

    // #region Configuration

    public void bind(@Nullable TaskSession session) {
        if (this.session == session) {
            render();
            return;
        }
        if (this.session != null) {
            this.session.removeObserver(this);
        }
        this.session = session;
        if (session != null && attached) {
            session.addObserver(this);
        }
        render();
    }

    public void unbind() {
        bind(null);
    }

    @Nullable
    public TaskSession session() {
        return session;
    }

    public void setHideWhenIdle(boolean hide) {
        hideWhenIdle = hide;
        render();
    }

    public void setShowStatus(boolean show) {
        showStatus = show;
        render();
    }

    public void setShowCancel(boolean show) {
        showCancel = show;
        render();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (session != null) {
            session.addObserver(this);
        }
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        if (session != null) {
            session.removeObserver(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onTaskSessionChanged(TaskSession s) {
        if (s == session) {
            render();
        }
    }

    // #endregion

    // #region Rendering

    private void render() {
        TaskSession s = session;
        if (s == null) {
            setVisibility(hideWhenIdle ? GONE : VISIBLE);
            outerRow.hide();
            primaryRow.hide();
            secondaryContainer.setVisibility(GONE);
            statusRow.setVisibility(GONE);
            return;
        }
        TaskSession.Status status = s.status();
        boolean running = status == TaskSession.Status.RUNNING;
        if (hideWhenIdle && !running) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);

        Context ctx = getContext();
        int color = statusColor(ctx, status, accent());
        TaskSession.ProgressModel model = s.progress();
        TaskSession.ProgressModel.Outer outer = model.outer();
        List<TaskSession.ProgressModel.Bar> bars = running ? model.bars() : new ArrayList<>();

        if (outer != null && outer.total > 0) {
            outerRow.show(ctx.getString(R.string.pkg_item_of, Math.min(outer.current, outer.total), outer.total),
                    outer.label, "", outer.percentage(), "", color);
        } else {
            outerRow.hide();
        }
        if (!bars.isEmpty()) {
            TaskSession.ProgressModel.Bar primary = bars.remove(0);
            primaryRow.show(stageLabel(ctx, primary), primary.packageName, amountText(ctx, primary), primary.percentage(), "", color);
        } else if (outer != null && outer.total > 0) {
            primaryRow.hide();
        } else {
            // Nothing measured yet.
            primaryRow.show(ctx.getString(R.string.pkg_task_waiting), "", "", running ? null : 0.0, "", color);
        }

        if (!bars.isEmpty()) {
            secondaryContainer.setVisibility(VISIBLE);
            secondaryContainer.setBackgroundResource(R.drawable.bg_pkg_secondary_bars);
            secondaryContainer.setPaddingRelative(dp(12), 0, 0, 0);
            int n = Math.min(MAX_SECONDARY, bars.size());
            while (secondaryRows.size() < n) {
                View v = LayoutInflater.from(ctx).inflate(R.layout.item_pkg_task_bar, secondaryContainer, false);
                LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                if (!secondaryRows.isEmpty()) {
                    lp.topMargin = dp(10);
                }
                secondaryContainer.addView(v, lp);
                secondaryRows.add(new BarRow(v));
            }
            for (int i = 0; i < secondaryRows.size(); i++) {
                if (i < n) {
                    TaskSession.ProgressModel.Bar bar = bars.get(i);
                    secondaryRows.get(i).show(stageLabel(ctx, bar), bar.packageName, amountText(ctx, bar), bar.percentage(), "", color);
                } else {
                    secondaryRows.get(i).hide();
                }
            }
        } else {
            secondaryContainer.setVisibility(GONE);
        }

        if (showStatus) {
            statusRow.setVisibility(VISIBLE);
            statusText.setText(statusLabel(ctx, status, s.isCancelRequested()));
            boolean cancellable = showCancel && running && !s.isCancelRequested();
            cancelLink.setVisibility(cancellable ? VISIBLE : GONE);
        } else {
            statusRow.setVisibility(GONE);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static String statusLabel(Context ctx, TaskSession.Status status, boolean cancelling) {
        switch (status) {
            case SUCCESS:
                return ctx.getString(R.string.pkg_task_status_success);
            case FAILED:
                return ctx.getString(R.string.pkg_task_status_failed);
            case CANCELLED:
                return ctx.getString(R.string.pkg_task_status_cancelled);
            default:
                return ctx.getString(cancelling ? R.string.pkg_task_cancelling : R.string.pkg_task_status_running);
        }
    }

    static int statusColor(Context ctx, TaskSession.Status status, int accent) {
        switch (status) {
            case FAILED:
            case CANCELLED:
                return ctx.getColor(R.color.error);
            case SUCCESS:
                return ctx.getColor(R.color.pkg_status_success);
            default:
                return accent;
        }
    }

    private int accent() {
        if (!accentResolved) {
            accent = accentColor(getContext());
            accentResolved = true;
        }
        return accent;
    }

    static int accentColor(Context ctx) {
        try {
            int accent = new PersonalizationManager(ctx).getAccentColor();
            if (accent != 0) {
                return accent;
            }
        } catch (RuntimeException ignored) {
        }
        return MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, ctx.getColor(R.color.primary));
    }

    static String stageLabel(Context ctx, TaskSession.ProgressModel.Bar bar) {
        switch (bar.stage) {
            case RESOLVE:
                return ctx.getString(R.string.pkg_stage_resolve);
            case GIT_FETCH:
                return ctx.getString(R.string.pkg_stage_git_fetch);
            case DOWNLOAD:
                return ctx.getString(R.string.pkg_stage_download);
            case EXTRACT:
                return ctx.getString(R.string.pkg_stage_extract);
            case PLACE:
                return ctx.getString(R.string.pkg_stage_place);
            case SCRIPTS:
                return ctx.getString(R.string.pkg_stage_scripts);
            case REMOVE:
                return ctx.getString(R.string.pkg_stage_remove);
            default:
                return ctx.getString(R.string.pkg_stage_generic);
        }
    }

    static String amountText(Context ctx, TaskSession.ProgressModel.Bar bar) {
        if (bar.bytes) {
            String current = Formatter.formatShortFileSize(ctx, bar.current);
            return bar.total > 0
                    ? ctx.getString(R.string.pkg_progress_bytes, current, Formatter.formatShortFileSize(ctx, bar.total))
                    : current;
        }
        String unit;
        switch (bar.stage) {
            case RESOLVE:
                unit = ctx.getString(R.string.pkg_unit_manifests);
                break;
            case SCRIPTS:
                unit = ctx.getString(R.string.pkg_unit_commands);
                break;
            default:
                unit = ctx.getString(R.string.pkg_unit_files);
        }
        return bar.total > 0
                ? ctx.getString(R.string.pkg_progress_amount, unit, bar.current, bar.total)
                : ctx.getString(R.string.pkg_progress_amount_unknown, unit, bar.current);
    }

    // #endregion

    private static final class BarRow {
        final View root;
        final TextView label;
        final TextView detail;
        final TextView amount;
        final TextView percent;
        final LinearProgressIndicator bar;
        final TextView item;
        private Boolean indeterminate;
        private int color;

        BarRow(@NonNull View root) {
            this.root = root;
            label = root.findViewById(R.id.pkg_bar_label);
            detail = root.findViewById(R.id.pkg_bar_detail);
            amount = root.findViewById(R.id.pkg_bar_amount);
            percent = root.findViewById(R.id.pkg_bar_percent);
            bar = root.findViewById(R.id.pkg_bar_progress);
            item = root.findViewById(R.id.pkg_bar_item);
        }

        void setEmphasis(boolean emphasis) {
            label.setTypeface(null, emphasis ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, emphasis ? 13 : 12);
        }

        void hide() {
            root.setVisibility(GONE);
        }

        void show(String labelText, String detailText, String amountText, Double value, String itemText, int c) {
            root.setVisibility(VISIBLE);
            label.setText(labelText);
            setOptional(detail, detailText);
            setOptional(amount, amountText);
            if (value == null) {
                percent.setVisibility(GONE);
            } else {
                percent.setVisibility(VISIBLE);
                int floor = (int) Math.floor(TaskSession.ProgressModel.clampPercentage(value));
                percent.setText(root.getContext().getString(R.string.pkg_progress_percent, floor));
            }
            setOptional(item, itemText);
            if (color != c) {
                color = c;
                bar.setIndicatorColor(c);
                bar.setTrackColor(Color.argb(48, Color.red(c), Color.green(c), Color.blue(c)));
            }
            boolean ind = value == null;
            if (indeterminate == null || indeterminate != ind) {
                indeterminate = ind;
                if (ind) {
                    bar.setIndeterminate(true);
                } else {
                    bar.setIndeterminate(false);
                    bar.setProgressCompat(toProgress(value), false);
                    return;
                }
            }
            if (!ind) {
                bar.setProgressCompat(toProgress(value), true);
            }
        }

        private static int toProgress(double value) {
            return (int) Math.round(TaskSession.ProgressModel.clampPercentage(value) * 10);
        }

        private static void setOptional(TextView v, String text) {
            if (text == null || text.isEmpty()) {
                v.setVisibility(GONE);
                v.setText("");
            } else {
                v.setVisibility(VISIBLE);
                v.setText(text);
            }
        }
    }
}
