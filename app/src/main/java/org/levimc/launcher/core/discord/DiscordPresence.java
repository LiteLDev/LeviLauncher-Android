package org.levimc.launcher.core.discord;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import com.discord.socialsdk.DiscordSocialSdkInit;

import org.levimc.launcher.BuildConfig;
import org.levimc.launcher.core.minecraft.MinecraftActivity;
import org.levimc.launcher.core.minecraft.MinecraftLoadingActivity;
import org.levimc.launcher.core.minecraft.MinecraftRuntimePreparer;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.activities.SplashActivity;
import org.levimc.launcher.util.NativeBridgeHelper;

import java.lang.ref.WeakReference;
import java.util.Objects;

public final class DiscordPresence implements Application.ActivityLifecycleCallbacks {
    private static final long START_DELAY_MS = 750;
    private static final long BACKGROUND_DELAY_MS = 1000;
    private static DiscordPresence instance;

    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reconcileTask = this::reconcile;
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private WeakReference<Activity> gameActivity = new WeakReference<>(null);
    private Engine engine;
    private boolean stopping;
    private boolean unavailable;
    private boolean shuttingDown;
    private int startedActivities;
    private int phase = -1;
    private String version = "";
    private long startedAt;

    private DiscordPresence(Application application) {
        this.application = application;
        application.registerActivityLifecycleCallbacks(this);
    }

    public static void initialize(Application application) {
        if (instance == null) instance = new DiscordPresence(application);
    }

    public static boolean isConfigured() {
        return BuildConfig.DISCORD_APPLICATION_ID > 0;
    }

    public static void refresh() {
        DiscordPresence presence = instance;
        if (presence != null) presence.main.post(() -> presence.schedule(0));
    }

    public static void shutdown() {
        DiscordPresence presence = instance;
        if (presence == null) return;
        presence.main.post(() -> {
            presence.shuttingDown = true;
            presence.main.removeCallbacks(presence.reconcileTask);
            presence.stopEngine();
        });
    }

    private void schedule(long delay) {
        main.removeCallbacks(reconcileTask);
        main.postDelayed(reconcileTask, delay);
    }

    private boolean isAlive(Activity activity) {
        return activity != null && !activity.isDestroyed() && !activity.isFinishing();
    }

    private boolean isLauncherActivity(Activity activity) {
        return activity.getClass().getName().startsWith("org.levimc.launcher.")
                && !(activity instanceof SplashActivity);
    }

    private Activity chooseActivity() {
        Activity current = currentActivity.get();
        if (startedActivities > 0 && isAlive(current)) return current;
        return null;
    }

    private Snapshot snapshot(Activity activity) {
        Activity game = gameActivity.get();
        int nextPhase = isAlive(game) ? 2 : activity instanceof MinecraftLoadingActivity ? 1 : 0;
        Activity source = nextPhase == 2 ? game : activity;
        String nextVersion = "";
        if (nextPhase != 0) {
            GameVersion selected = MinecraftRuntimePreparer.resolveGameVersion(source.getIntent());
            if (selected != null && selected.versionCode != null) {
                nextVersion = selected.versionCode.trim();
                if (nextVersion.length() > 64) nextVersion = nextVersion.substring(0, 64);
            }
        }
        if (phase != nextPhase || !version.equals(nextVersion)) {
            phase = nextPhase;
            version = nextVersion;
            startedAt = System.currentTimeMillis() / 1000;
        }
        String details = nextPhase == 2 ? "Playing Minecraft"
                : nextPhase == 1 ? "Starting Minecraft" : "Active in launcher";
        String state = nextPhase == 0 ? "Minecraft Launcher for Android"
                : nextVersion.isEmpty() ? "Minecraft Bedrock" : "Minecraft " + nextVersion;
        return new Snapshot(details, state, startedAt);
    }

    private void reconcile() {
        if (shuttingDown || unavailable || !isConfigured()
                || !FeatureSettings.getInstance().isDiscordRichPresenceEnabled()) {
            stopEngine();
            return;
        }
        Activity activity = chooseActivity();
        if (activity == null) {
            stopEngine();
            return;
        }
        if (stopping) return;
        if (engine != null && !isAlive(engine.owner.get())) {
            stopEngine();
            return;
        }
        if (engine == null) {
            Intent rpcService = new Intent("com.discord.socialsdk.rpc.IDiscordRpcService")
                    .setPackage("com.discord");
            try {
                if (application.getPackageManager().resolveService(rpcService, 0) == null) return;
                engine = new Engine(activity);
            } catch (LinkageError | RuntimeException error) {
                unavailable = true;
                return;
            }
        }
        engine.update(snapshot(activity));
    }

    private void stopEngine() {
        if (engine == null) return;
        Engine previous = engine;
        engine = null;
        stopping = true;
        previous.close(() -> {
            stopping = false;
            if (!shuttingDown) schedule(0);
        });
    }

    private void engineFailed(Engine failed, Throwable error) {
        if (engine != failed) return;
        unavailable = true;
        stopEngine();
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (activity instanceof MinecraftActivity) gameActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!isLauncherActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        schedule(START_DELAY_MS);
    }

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0) schedule(BACKGROUND_DELAY_MS);
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (currentActivity.get() == activity) currentActivity.clear();
        if (gameActivity.get() == activity) gameActivity.clear();
        if (engine != null && engine.owner.get() == activity) stopEngine();
        schedule(0);
    }

    private static final class Snapshot {
        final String details;
        final String state;
        final long startedAt;

        Snapshot(String details, String state, long startedAt) {
            this.details = details;
            this.state = state;
            this.startedAt = startedAt;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot snapshot = (Snapshot) other;
            return startedAt == snapshot.startedAt && details.equals(snapshot.details)
                    && state.equals(snapshot.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(details, state, startedAt);
        }
    }

    private final class Engine {
        final WeakReference<Activity> owner;
        final HandlerThread thread = new HandlerThread("DiscordPresence", Process.THREAD_PRIORITY_BACKGROUND);
        final Handler worker;
        volatile boolean active = true;
        long handle;
        Snapshot lastSnapshot;
        final Runnable pump = new Runnable() {
            @Override
            public void run() {
                if (!active || handle == 0) return;
                try {
                    DiscordPresenceNative.tick(handle);
                    worker.postDelayed(this, 100);
                } catch (LinkageError | RuntimeException error) {
                    main.post(() -> engineFailed(Engine.this, error));
                }
            }
        };

        Engine(Activity activity) {
            owner = new WeakReference<>(activity);
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(() -> {
                if (!active) return;
                try {
                    DiscordSocialSdkInit.setEngineActivity(activity);
                    if (!NativeBridgeHelper.ensureGxCoreLoaded()) {
                        throw new UnsatisfiedLinkError("GXCore is unavailable");
                    }
                    handle = DiscordPresenceNative.create(BuildConfig.DISCORD_APPLICATION_ID);
                    if (handle == 0) throw new IllegalStateException("Discord client was not created");
                    worker.postDelayed(pump, 100);
                } catch (LinkageError | RuntimeException error) {
                    main.post(() -> engineFailed(this, error));
                }
            });
        }

        void update(Snapshot snapshot) {
            if (snapshot.equals(lastSnapshot)) return;
            lastSnapshot = snapshot;
            worker.post(() -> {
                if (!active || handle == 0) return;
                try {
                    DiscordPresenceNative.update(handle, snapshot.details, snapshot.state,
                            snapshot.startedAt, BuildConfig.DISCORD_LARGE_IMAGE);
                } catch (LinkageError | RuntimeException error) {
                    main.post(() -> engineFailed(this, error));
                }
            });
        }

        void close(Runnable completion) {
            active = false;
            worker.post(() -> {
                worker.removeCallbacks(pump);
                try {
                    if (handle != 0) DiscordPresenceNative.destroy(handle);
                } catch (LinkageError | RuntimeException error) {
                } finally {
                    handle = 0;
                    thread.quitSafely();
                    // The SDK queues RPC unbinding on the main thread before this completion.
                    main.post(completion);
                }
            });
        }
    }
}
