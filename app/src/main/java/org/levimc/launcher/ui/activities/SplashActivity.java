package org.levimc.launcher.ui.activities;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.ViewTreeObserver;
import org.levimc.launcher.BuildConfig;
import org.levimc.launcher.R;
import org.levimc.launcher.databinding.ActivitySplashBinding;
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.ThemePalette;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends BaseActivity {
    private ActivitySplashBinding binding;
    private boolean navigated;
    private boolean ready;
    private boolean resumed;
    private ViewTreeObserver.OnPreDrawListener firstFrame;
    private final Runnable openLauncher = this::navigateToMain;

    @Override protected boolean shouldSkipNavBar() { return true; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        int accent = UiKit.accent(this);
        int surface = getColor(R.color.background);
        binding.getRoot().setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ThemePalette.mix(surface, accent, UiKit.isDark(this) ? 0.12f : 0.06f), surface, surface}));
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(Color.WHITE);
        tile.setCornerRadius(UiKit.dp(this, 22));
        tile.setStroke(Math.round(UiKit.dp(this, 1)), ThemePalette.withAlpha(accent, 0.3f));
        binding.imgLeaf.setBackground(tile);
        binding.loadingBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        binding.tvVersion.setText(getString(R.string.splash_version, BuildConfig.VERSION_NAME));
        Context app = getApplicationContext();
        new Thread(() -> LauncherStorage.ensureNoMedia(app), "launcher-storage-warmup").start();
        PreloaderSignatureRulesManager.refreshOnLauncherStart(app);
        firstFrame = () -> {
            removeFirstFrameListener();
            ready = true;
            binding.getRoot().postOnAnimation(openLauncher);
            return true;
        };
        binding.getRoot().getViewTreeObserver().addOnPreDrawListener(firstFrame);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (ready) binding.getRoot().postOnAnimation(openLauncher);
    }

    @Override protected void onPause() {
        resumed = false;
        binding.getRoot().removeCallbacks(openLauncher);
        super.onPause();
    }

    @Override protected void onDestroy() {
        removeFirstFrameListener();
        binding.getRoot().removeCallbacks(openLauncher);
        super.onDestroy();
    }

    private void removeFirstFrameListener() {
        if (firstFrame != null && binding.getRoot().getViewTreeObserver().isAlive()) {
            binding.getRoot().getViewTreeObserver().removeOnPreDrawListener(firstFrame);
        }
        firstFrame = null;
    }

    private void navigateToMain() {
        if (!resumed || !ready || navigated || isFinishing() || isDestroyed() || !hasWindowFocus()) return;
        navigated = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && ready && !navigated) binding.getRoot().postOnAnimation(openLauncher);
    }
}
