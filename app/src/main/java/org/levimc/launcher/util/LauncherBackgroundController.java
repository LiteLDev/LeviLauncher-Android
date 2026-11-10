package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.levimc.launcher.core.minecraft.MinecraftActivityState;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class LauncherBackgroundController {
    private static volatile boolean minecraftBlocked;
    private static volatile boolean importing;
    private static LauncherBackgroundController instance;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile ExecutorService executor;
    private final CopyOnWriteArrayList<ExecutorService> retiringExecutors = new CopyOnWriteArrayList<>();
    private WeakReference<Activity> owner = new WeakReference<>(null);
    private WeakReference<Activity> navigatingFrom = new WeakReference<>(null);
    private ImageView animatedView;
    private AnimatedImageDrawable animation;
    private Future<?> imageDecode;
    private String sourcePath;
    private int generation;
    private boolean visible;

    private LauncherBackgroundController(Context context) {
        this.context = context.getApplicationContext();
    }

    private static LauncherBackgroundController get(Context context) {
        if (instance == null) instance = new LauncherBackgroundController(context);
        return instance;
    }

    public static boolean isMinecraftBlocked() {
        return minecraftBlocked || MinecraftActivityState.isRunning();
    }

    public static void resume(Activity activity) {
        if (isMinecraftBlocked()
                || activity instanceof org.levimc.launcher.core.minecraft.MinecraftLoadingActivity
                || activity instanceof org.levimc.launcher.core.minecraft.LauncherRestartActivity
                || activity instanceof org.levimc.launcher.ui.activities.SplashActivity) return;
        LauncherBackgroundController controller = get(activity);
        PersonalizationManager manager = new PersonalizationManager(activity);
        if (!manager.hasAnimatedBackground()) {
            controller.releasePlayback();
            controller.owner = new WeakReference<>(activity);
            return;
        }
        controller.attach(activity, manager);
        controller.navigatingFrom.clear();
    }

    public static void stopped(Activity activity) {
        if (instance == null || instance.owner.get() != activity) return;
        instance.visible = false;
        if (instance.navigatingFrom.get() != activity || isMinecraftBlocked()) instance.pausePlayback();
        instance.detachView();
        instance.owner.clear();
    }

    public static void navigating(Activity activity) {
        if (instance != null && instance.owner.get() == activity && !isMinecraftBlocked()) {
            instance.navigatingFrom = new WeakReference<>(activity);
        }
    }

    public static void navigationFailed(Activity activity) {
        if (instance != null && instance.navigatingFrom.get() == activity) instance.navigatingFrom.clear();
    }

    public static void refreshEffects() {
        if (instance != null && !isMinecraftBlocked()) instance.applyEffects();
    }

    public static boolean isImporting() {
        return importing;
    }

    public static void backgroundCleared() {
        if (instance != null) instance.releasePlayback();
    }

    public static void suspendForMinecraft() {
        minecraftBlocked = true;
        importing = false;
        if (instance == null) return;
        instance.releasePlayback();
        instance.handler.removeCallbacksAndMessages(null);
        ExecutorService worker = instance.executor;
        instance.executor = null;
        if (worker != null) {
            instance.retiringExecutors.add(worker);
            worker.shutdownNow();
        }
    }

    public static void awaitShutdown() throws InterruptedException {
        if (instance == null) return;
        for (ExecutorService worker : instance.retiringExecutors) {
            worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            instance.retiringExecutors.remove(worker);
        }
    }

    public static void allowLauncherPlayback() {
        minecraftBlocked = false;
    }

    public static void launchFailed(Activity activity) {
        if (MinecraftActivityState.isRunning()) return;
        allowLauncherPlayback();
        new PersonalizationManager(activity).applyToActivity(activity);
        resume(activity);
    }

    public static void importBackground(Context context, Uri uri, Consumer<Boolean> callback) {
        if (isMinecraftBlocked() || importing) {
            callback.accept(false);
            return;
        }
        LauncherBackgroundController controller = get(context);
        importing = true;
        try {
            controller.worker().execute(() -> {
                boolean success;
                try {
                    success = new PersonalizationManager(controller.context)
                            .setBackgroundImage(uri, controller.context);
                } finally {
                    importing = false;
                }
                controller.handler.post(() -> {
                    if (isMinecraftBlocked()) return;
                    Activity activity = controller.owner.get();
                    if (success && activity != null && !activity.isDestroyed()) {
                        new PersonalizationManager(activity).applyToActivity(activity);
                        resume(activity);
                        if (activity instanceof org.levimc.launcher.ui.activities.BaseActivity) {
                            ((org.levimc.launcher.ui.activities.BaseActivity) activity).markPersonalizationApplied();
                        }
                    }
                    callback.accept(success);
                });
            });
        } catch (RejectedExecutionException e) {
            importing = false;
            callback.accept(false);
        }
    }

    private ExecutorService worker() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "LauncherBackgroundMedia");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        return executor;
    }

    private void attach(Activity activity, PersonalizationManager manager) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        FrameLayout host = root == null ? null : root.findViewWithTag("personalization_media");
        if (host == null) return;
        String path = manager.getBackgroundImagePath();
        if (!path.equals(sourcePath)) {
            releasePlayback();
            sourcePath = path;
        }
        if (animatedView == null) createAnimatedView();
        if (animatedView.getParent() != host) {
            detachView();
            host.addView(animatedView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        owner = new WeakReference<>(activity);
        visible = true;
        applyEffects();
        startPlayback();
    }

    private void createAnimatedView() {
        animatedView = new ImageView(context);
        animatedView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        animatedView.setClickable(false);
        animatedView.setFocusable(false);
        String path = sourcePath;
        int ticket = generation;
        imageDecode = worker().submit(() -> {
            Drawable decoded;
            try {
                decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(new File(path)),
                        (decoder, info, source) -> {
                            int width = info.getSize().getWidth();
                            int height = info.getSize().getHeight();
                            float scale = Math.min(1f, 720f / Math.max(width, height));
                            decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                    Math.max(1, Math.round(height * scale)));
                        });
            } catch (Exception | OutOfMemoryError e) {
                return;
            }
            if (Thread.currentThread().isInterrupted() || isMinecraftBlocked()) return;
            handler.post(() -> {
                if (ticket != generation || isMinecraftBlocked() || animatedView == null) return;
                animatedView.setImageDrawable(decoded);
                if (decoded instanceof AnimatedImageDrawable) {
                    animation = (AnimatedImageDrawable) decoded;
                    animation.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                }
                applyEffects();
                startPlayback();
            });
        });
    }

    private void startPlayback() {
        if (!visible || isMinecraftBlocked()) return;
        if (animation != null && !animation.isRunning()) animation.start();
    }

    private void pausePlayback() {
        if (animation != null) animation.stop();
    }

    private void detachView() {
        if (animatedView != null && animatedView.getParent() instanceof ViewGroup) {
            ((ViewGroup) animatedView.getParent()).removeView(animatedView);
        }
    }

    private void releasePlayback() {
        generation++;
        navigatingFrom.clear();
        visible = false;
        if (imageDecode != null) imageDecode.cancel(true);
        imageDecode = null;
        pausePlayback();
        if (animation != null) {
            animation.clearAnimationCallbacks();
            animation.setCallback(null);
            animation = null;
        }
        if (animatedView != null) animatedView.setImageDrawable(null);
        detachView();
        animatedView = null;
    }

    private void applyEffects() {
        if (animatedView != null) {
            new PersonalizationManager(context).applyBackgroundImageEffects(animatedView);
        }
    }
}
