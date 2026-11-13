package org.levimc.launcher.ui.dialogs;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.core.minecraft.MinecraftLoadingActivity;
import org.levimc.launcher.ui.activities.SplashActivity;

public class LogcatOverlayManager {
    private static volatile LogcatOverlayManager instance;
    private final Application app;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        if ("settings_json".equals(key)) updateVisibility();
    };
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private LogcatOverlay overlay;
    private boolean resumed;
    private volatile boolean gameHudHidden;

    private LogcatOverlayManager(Application app) {
        this.app = app;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                currentActivity = new WeakReference<>(activity);
                resumed = true;
                updateVisibility();
            }
            @Override public void onActivityPaused(Activity activity) {
                if (currentActivity.get() != activity) return;
                resumed = false;
                if (overlay != null) overlay.hide();
            }
            @Override public void onActivityDestroyed(Activity activity) {
                if (currentActivity.get() != activity) return;
                resumed = false;
                currentActivity.clear();
                if (overlay != null) {
                    overlay.hide();
                    if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        });
        app.getSharedPreferences("feature_settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener);
    }

    public static void init(Application app) {
        if (instance == null) {
            synchronized (LogcatOverlayManager.class) {
                if (instance == null) instance = new LogcatOverlayManager(app);
            }
        }
    }

    public static LogcatOverlayManager getInstance() { return instance; }

    private void updateVisibility() {
        if (Looper.myLooper() == Looper.getMainLooper()) applyVisibility();
        else mainHandler.post(this::applyVisibility);
    }

    private void applyVisibility() {
        Activity activity = currentActivity.get();
        boolean enabled = FeatureSettings.getInstance().isLogcatOverlayEnabled();
        boolean show = resumed && activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !(activity instanceof SplashActivity) && !(activity instanceof MinecraftLoadingActivity)
                && !gameHudHidden && enabled;
        if (!show) {
            if (overlay != null) {
                overlay.hide();
                if (!enabled && overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            return;
        }
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        if (overlay == null) overlay = new LogcatOverlay(app);
        if (overlay.getParent() != root) {
            overlay.hide();
            if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            root.addView(overlay);
        }
        overlay.show();
    }

    public void setGameHudHidden(boolean hidden) {
        if (gameHudHidden == hidden) return;
        gameHudHidden = hidden;
        updateVisibility();
    }

    public void refreshVisibility() { updateVisibility(); }
}
