package org.levimc.launcher.core.mods.inbuilt.overlay;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.levimc.launcher.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ModNotificationManager {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private final List<NotificationEntry> entries = new ArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();

    private static final class NotificationEntry {
        String id;
        View view;
        WindowManager.LayoutParams params;
        Runnable dismiss;
        ObjectAnimator progress;
        int height;
        int position;
        boolean removed;
        long shownAt;
        ProgressBar bar;
        TextView message;
    }

    public ModNotificationManager(Activity activity) {
        this.activity = activity;
        windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle state) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) { if (a == activity) hideAll(); }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            @Override public void onActivityDestroyed(Activity a) {
                if (a != activity) return;
                hideAll();
                a.getApplication().unregisterActivityLifecycleCallbacks(this);
            }
        });
    }

    public void show(String modName, String modId) { show(modName, modId, true); }

    public void show(String modName, String modId, boolean enabled) {
        int expected = generation.get();
        handler.post(() -> {
            if (generation.get() != expected || activity.isFinishing() || activity.isDestroyed()) return;
            showInternal(modName, modId, enabled, false);
        });
    }

    void preview() {
        int expected = generation.get();
        handler.post(() -> {
            if (generation.get() == expected) showInternal(activity.getString(R.string.mm_notification_preview), "preview", true, true);
        });
    }

    private void showInternal(String title, String id, boolean enabled, boolean preview) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context context = ModMenuTheme.wrap(activity);
        ModMenuTheme theme = new ModMenuTheme(context);
        ModMenuPreferences prefs = new ModMenuPreferences(activity);
        for (NotificationEntry entry : new ArrayList<>(entries)) {
            if (entry.id.equals(id) || entry.position != prefs.notificationPosition()) remove(entry);
        }
        while (entries.size() >= prefs.notificationLimit()) remove(entries.get(entries.size() - 1));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8));
        card.setBackground(theme.shape(theme.raised, Math.min(10, theme.radius), true));
        card.setElevation(theme.dp(6));
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(context);
        icon.setImageResource(preview ? R.drawable.ic_modmenu_bell : ModIconHelper.getModIcon(id == null ? "" : id));
        icon.setImageTintList(preview ? android.content.res.ColorStateList.valueOf(theme.accent) : null);
        icon.setBackground(theme.shape(theme.selected, 6, false));
        icon.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4));
        row.addView(icon, new LinearLayout.LayoutParams(theme.dp(26), theme.dp(26)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(theme.dp(10), 0, 0, 0);
        TextView name = theme.label(title, 12, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView message = theme.label(activity.getString(preview ? R.string.mm_notification_preview_hint
                    : enabled ? R.string.mod_status_enabled : R.string.mod_status_disabled), 10.5f, false);
            message.setPadding(0, theme.dp(4), 0, 0);
            message.setVisibility(prefs.notificationCompact() ? View.GONE : View.VISIBLE);
            labels.addView(message);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        View dot = new View(context);
        dot.setBackground(theme.shape(enabled ? theme.accent : theme.secondary, 4, false));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(theme.dp(5), theme.dp(5));
        dotParams.setMarginStart(theme.dp(8));
        row.addView(dot, dotParams);
        card.addView(row);
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.outline));
        {
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, theme.dp(2));
            barParams.topMargin = theme.dp(7);
            bar.setVisibility(prefs.notificationProgress() ? View.VISIBLE : View.GONE);
            card.addView(bar, barParams);
        }
        NotificationEntry entry = new NotificationEntry();
        entry.id = id == null ? "" : id;
        entry.view = card;
        entry.position = prefs.notificationPosition();
        entry.shownAt = android.os.SystemClock.uptimeMillis();
        entry.bar = bar;
        entry.message = message;
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(theme.dp(prefs.notificationCompact() ? 200 : 240), Math.max(theme.dp(160), screenWidth - theme.dp(40)));
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        entry.height = card.getMeasuredHeight();
        entry.params = new WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        entry.params.gravity = Gravity.TOP | Gravity.LEFT;
        entry.params.token = activity.getWindow().getDecorView().getWindowToken();
        try {
            position(entry, 0, theme);
            windowManager.addView(card, entry.params);
            entries.add(0, entry);
            reflow();
            card.setTranslationY(theme.dp(entry.position < 3 ? -10 : 10));
            card.setAlpha(0f);
            card.animate().translationY(0).alpha(1f).setDuration(180).start();
            if (prefs.notificationProgress()) {
                entry.progress = ObjectAnimator.ofInt(bar, "progress", 1000, 0);
                entry.progress.setInterpolator(new android.view.animation.LinearInterpolator());
                entry.progress.setDuration(prefs.notificationDuration());
                entry.progress.start();
            }
            entry.dismiss = () -> {
                card.animate().alpha(0f).translationY(theme.dp(entry.position < 3 ? -8 : 8)).setDuration(140)
                        .withEndAction(() -> { remove(entry); reflow(); }).start();
            };
            handler.postDelayed(entry.dismiss, prefs.notificationDuration());
        } catch (RuntimeException ignored) {
            remove(entry);
        }
    }

    void refreshAppearance() {
        ModMenuPreferences prefs = new ModMenuPreferences(activity);
        ModMenuTheme theme = new ModMenuTheme(activity);
        while (entries.size() > prefs.notificationLimit()) remove(entries.get(entries.size() - 1));
        for (NotificationEntry entry : new ArrayList<>(entries)) {
            entry.position = prefs.notificationPosition();
            entry.message.setVisibility(prefs.notificationCompact() ? View.GONE : View.VISIBLE);
            entry.bar.setVisibility(prefs.notificationProgress() ? View.VISIBLE : View.GONE);
            entry.params.width = Math.min(theme.dp(prefs.notificationCompact() ? 200 : 240),
                    Math.max(theme.dp(160), activity.getResources().getDisplayMetrics().widthPixels - theme.dp(40)));
            entry.view.measure(View.MeasureSpec.makeMeasureSpec(entry.params.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            entry.height = entry.view.getMeasuredHeight();
            long remaining = prefs.notificationDuration() - (android.os.SystemClock.uptimeMillis() - entry.shownAt);
            if (remaining <= 0) { remove(entry); continue; }
            handler.removeCallbacks(entry.dismiss);
            handler.postDelayed(entry.dismiss, remaining);
            if (entry.progress != null) entry.progress.cancel();
            if (prefs.notificationProgress()) {
                int progress = Math.round(remaining * 1000f / prefs.notificationDuration());
                entry.bar.setProgress(progress);
                entry.progress = ObjectAnimator.ofInt(entry.bar, "progress", progress, 0);
                entry.progress.setInterpolator(new android.view.animation.LinearInterpolator());
                entry.progress.setDuration(remaining);
                entry.progress.start();
            }
        }
        reflow();
    }

    private void position(NotificationEntry entry, int offset, ModMenuTheme theme) {
        View decor = activity.getWindow().getDecorView();
        int width = decor.getWidth() > 0 ? decor.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int height = decor.getHeight() > 0 ? decor.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int left = theme.dp(16), right = theme.dp(16), top = theme.dp(16), bottom = theme.dp(16);
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) {
            left += insets.getSystemWindowInsetLeft();
            right += insets.getSystemWindowInsetRight();
            top += insets.getSystemWindowInsetTop();
            bottom += insets.getSystemWindowInsetBottom();
            if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft() + theme.dp(8));
                right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight() + theme.dp(8));
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop() + theme.dp(8));
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom() + theme.dp(8));
            }
        }
        int column = entry.position % 3;
        entry.params.x = column == 0 ? left : column == 1 ? (width - entry.params.width) / 2 : width - right - entry.params.width;
        entry.params.x = Math.max(left, Math.min(width - right - entry.params.width, entry.params.x));
        entry.params.y = entry.position < 3 ? top + offset : height - bottom - entry.height - offset;
        entry.params.y = Math.max(top, Math.min(height - bottom - entry.height, entry.params.y));
    }

    private void reflow() {
        ModMenuTheme theme = new ModMenuTheme(activity);
        int offset = 0;
        int availableHeight = activity.getResources().getDisplayMetrics().heightPixels - theme.dp(40);
        for (NotificationEntry entry : new ArrayList<>(entries)) {
            if (offset > 0 && offset + entry.height > availableHeight) { remove(entry); continue; }
            position(entry, offset, theme);
            try { windowManager.updateViewLayout(entry.view, entry.params); } catch (RuntimeException ignored) { remove(entry); }
            offset += entry.height + theme.dp(8);
        }
    }

    private void remove(NotificationEntry entry) {
        if (entry.removed) return;
        entry.removed = true;
        entries.remove(entry);
        if (entry.dismiss != null) handler.removeCallbacks(entry.dismiss);
        if (entry.progress != null) entry.progress.cancel();
        entry.view.animate().withEndAction(null).cancel();
        try { windowManager.removeViewImmediate(entry.view); } catch (RuntimeException ignored) {}
    }

    public void hideAll() {
        generation.incrementAndGet();
        handler.post(() -> {
            for (NotificationEntry entry : new ArrayList<>(entries)) remove(entry);
        });
    }
}
