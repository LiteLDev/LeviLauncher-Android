package org.levimc.launcher.ui.activities;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import org.levimc.launcher.R;
import org.levimc.launcher.core.auth.MsftAccountStore;
import org.levimc.launcher.core.news.NewsFeed;
import org.levimc.launcher.core.news.NewsRepository;
import org.levimc.launcher.core.news.NewsState;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.AccountTextUtils;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.LauncherBackgroundController;
import org.levimc.launcher.util.ThemeManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BaseActivity extends AppCompatActivity {
    private int appliedThemeGeneration = -1;
    private int appliedPersonalizationGeneration = -1;
    private int appliedBackgroundEffectGeneration = -1;
    private boolean navBarInjected = false;
    private final OkHttpClient navAvatarClient = new OkHttpClient();
    private final ExecutorService navAccountExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent> navAccountLoginLauncher;
    private boolean newsReceiverRegistered;
    private final BroadcastReceiver newsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshNewsBadge();
            onNewsChanged();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        String languageCode = LanguageManager.getSavedLanguage(newBase);
        Locale locale = Locale.forLanguageTag(languageCode);
        Locale.setDefault(locale);
        Resources res = newBase.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        Context localizedContext = newBase.createConfigurationContext(config);
        super.attachBaseContext(localizedContext);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager themeManager = new ThemeManager(this);
        themeManager.applyTheme();
        appliedThemeGeneration = ThemeManager.getThemeChangeGeneration();
        appliedPersonalizationGeneration = PersonalizationManager.getChangeGeneration();
        appliedBackgroundEffectGeneration = PersonalizationManager.getBackgroundEffectGeneration();
        super.onCreate(savedInstanceState);
        navAccountLoginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleNavAccountLoginResult(result.getResultCode(), result.getData()));
        hideSystemUI();
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                visibility -> getWindow().getDecorView().post(this::hideSystemUI));
    }

    @Override
    public void setContentView(int layoutResID) {
        View contentView = LayoutInflater.from(this).inflate(layoutResID, null);
        wrapWithNavBar(contentView);
    }

    @Override
    public void setContentView(View view) {
        wrapWithNavBar(view);
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        wrapWithNavBar(view);
    }

    private void wrapWithNavBar(View contentView) {
        if (shouldSkipNavBar()) {
            super.setContentView(contentView);
            applyPersonalization();
            return;
        }

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        View navBar = LayoutInflater.from(this).inflate(R.layout.nav_bar, wrapper, false);
        wrapper.addView(navBar);

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentView.setLayoutParams(contentParams);
        wrapper.addView(contentView);

        contentView.setAlpha(0f);
        contentView.setTranslationY(8f * getResources().getDisplayMetrics().density);

        super.setContentView(wrapper);
        navBarInjected = true;
        setupBaseNavBar();

        applyPersonalization();

        contentView.post(() -> {
            DynamicAnim.springAlphaTo(contentView, 1f).start();
            DynamicAnim.springTranslationYTo(contentView, 0f).start();
        });
    }

    private void applyPersonalization() {
        PersonalizationManager pm = new PersonalizationManager(this);
        pm.applyToActivity(this);
    }

    protected boolean shouldSkipNavBar() {
        return false;
    }

    private void setupBaseNavBar() {
        int[] tabIds = {
            R.id.nav_tab_launch, R.id.nav_tab_instances,
            R.id.nav_tab_about, R.id.nav_tab_settings
        };

        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();

        for (int id : tabIds) {
            TextView tab = findViewById(id);
            if (tab == null) continue;
            int color = getResources().getColor(R.color.text_secondary, getTheme());
            tab.setTextColor(color);
            tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.NORMAL);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(color));
        }

        if (pm.hasBackgroundImage()) {
            View navRoot = findViewById(R.id.nav_bar_root);
            if (navRoot != null) {
                boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                navRoot.setBackgroundColor(isDark
                        ? android.graphics.Color.argb(90, 25, 25, 25)
                        : android.graphics.Color.argb(110, 255, 255, 255));
            }
            View navDivider = findViewById(R.id.nav_divider);
            if (navDivider != null) {
                boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                navDivider.setBackgroundColor(isDark
                        ? android.graphics.Color.argb(40, 255, 255, 255)
                        : android.graphics.Color.argb(40, 0, 0, 0));
            }
        }

        View backButton = findViewById(R.id.nav_back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
            DynamicAnim.applyPressScale(backButton);
        }

        View signIn = findViewById(R.id.nav_sign_in_button);
        if (signIn != null) {
            signIn.setOnClickListener(v -> navAccountLoginLauncher.launch(new Intent(this, MsftLoginActivity.class)));
            DynamicAnim.applyPressScale(signIn);
        }

        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (avatarContainer != null) {
            avatarContainer.setOnClickListener(v -> startActivity(new Intent(this, AccountsActivity.class)));
            DynamicAnim.applyPressScale(avatarContainer);
        }

        View news = findViewById(R.id.nav_news_container);
        if (news != null) {
            news.setOnClickListener(v -> {
                if (!(this instanceof NewsActivity)) {
                    startActivity(new Intent(this, NewsActivity.class));
                }
            });
            DynamicAnim.applyPressScale(news);
        }

        findViewById(R.id.nav_tab_launch).setOnClickListener(v -> {
            if (!(this instanceof MainActivity)) {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            }
        });
        findViewById(R.id.nav_tab_instances).setOnClickListener(v -> {
            if (!(this instanceof InstancesActivity)) {
                startActivity(new Intent(this, InstancesActivity.class));
            }
        });
        findViewById(R.id.nav_tab_about).setOnClickListener(v -> {
            if (!(this instanceof AboutActivity)) {
                startActivity(new Intent(this, AboutActivity.class));
            }
        });
        findViewById(R.id.nav_tab_settings).setOnClickListener(v -> {
            if (!(this instanceof SettingsActivity)) {
                startActivity(new Intent(this, SettingsActivity.class));
            }
        });

        refreshNavAccountUI();
        refreshNewsBadge();
    }

    private void refreshNewsBadge() {
        if (!navBarInjected) return;
        NewsRepository.loadCached(this, (feed, error) -> applyNewsBadge(feed));
        NewsRepository.refreshIfStale(this, (feed, error) -> applyNewsBadge(feed));
    }

    private void applyNewsBadge(NewsFeed feed) {
        if (isFinishing() || isDestroyed()) return;
        int unread = NewsState.getUnreadCount(this, feed);
        View badge = findViewById(R.id.nav_news_badge);
        View container = findViewById(R.id.nav_news_container);
        if (badge != null) badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        if (container != null) {
            container.setContentDescription(unread > 0
                    ? getString(R.string.news_unread_description, unread)
                    : getString(R.string.news_title));
        }
    }

    protected void onNewsChanged() {
    }

    protected void refreshNavAccountUI() {
        if (!navBarInjected) return;
        java.util.List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        MsftAccountStore.MsftAccount active = null;
        for (MsftAccountStore.MsftAccount a : list) if (a.active) { active = a; break; }
        View signIn = findViewById(R.id.nav_sign_in_button);
        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (active == null) {
            if (signIn != null) signIn.setVisibility(View.VISIBLE);
            if (avatarContainer != null) avatarContainer.setVisibility(View.GONE);
            clearNavAvatar();
        } else {
            if (signIn != null) signIn.setVisibility(View.GONE);
            if (avatarContainer != null) avatarContainer.setVisibility(View.VISIBLE);
            loadNavXboxAvatar(active);
        }
    }

    private void clearNavAvatar() {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.nav_account_avatar);
        ProgressBar progress = findViewById(R.id.nav_avatar_progress);
        if (avatar != null) avatar.setImageResource(R.drawable.ic_minecraft_cube);
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void loadNavXboxAvatar(MsftAccountStore.MsftAccount active) {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.nav_account_avatar);
        ProgressBar progress = findViewById(R.id.nav_avatar_progress);
        if (avatar == null) return;

        String url = AccountTextUtils.sanitizeUrl(active != null ? active.xboxAvatarUrl : null);
        if (url == null) {
            avatar.setImageResource(R.drawable.ic_minecraft_cube);
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        Object currentUrl = avatar.getTag(R.id.nav_account_avatar);
        if (url.equals(currentUrl) && avatar.getDrawable() != null) {
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        Bitmap cached = AccountTextUtils.getCachedAvatar(url);
        if (cached != null) {
            avatar.setTag(R.id.nav_account_avatar, url);
            avatar.setImageBitmap(cached);
            if (progress != null) progress.setVisibility(View.GONE);
            return;
        }

        avatar.setTag(R.id.nav_account_avatar, url);
        avatar.setImageResource(R.drawable.ic_minecraft_cube);
        if (progress != null) progress.setVisibility(View.VISIBLE);
        navAccountExecutor.execute(() -> {
            Bitmap bmp = null;
            try (Response imgResp = navAvatarClient.newCall(new Request.Builder().url(url).build()).execute()) {
                if (imgResp.isSuccessful() && imgResp.body() != null) {
                    bmp = android.graphics.BitmapFactory.decodeStream(imgResp.body().byteStream());
                }
            } catch (Exception ignored) {
            }

            final Bitmap loaded = bmp;
            runOnUiThread(() -> {
                if (!url.equals(avatar.getTag(R.id.nav_account_avatar))) return;
                if (loaded != null) {
                    AccountTextUtils.cacheAvatar(url, loaded);
                    avatar.setImageBitmap(loaded);
                }
                if (progress != null) progress.setVisibility(View.GONE);
            });
        });
    }

    private void handleNavAccountLoginResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null
                && data.getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
            String name = data.getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
            String statusName = name != null ? name : getString(R.string.not_signed_in);
            Toast.makeText(this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
            refreshNavAccountUI();
            onNavAccountChanged();
            return;
        }
        refreshNavAccountUI();
    }

    protected void onNavAccountChanged() {
    }

    protected void setActiveNavTab(int activeTabId) {
        if (!navBarInjected) return;
        int[] tabIds = {
            R.id.nav_tab_launch, R.id.nav_tab_instances,
            R.id.nav_tab_about, R.id.nav_tab_settings
        };

        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();

        for (int id : tabIds) {
            TextView tab = findViewById(id);
            if (tab == null) continue;
            int color;
            if (id == activeTabId) {
                color = accent != 0 ? accent : getResources().getColor(R.color.on_surface, getTheme());
                tab.setTextColor(color);
                tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
            } else {
                color = getResources().getColor(R.color.text_secondary, getTheme());
                tab.setTextColor(color);
                tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.NORMAL);
            }
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(color));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!newsReceiverRegistered) {
            ContextCompat.registerReceiver(
                    this,
                    newsReceiver,
                    new IntentFilter(NewsState.ACTION_NEWS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            newsReceiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int currentGen = ThemeManager.getThemeChangeGeneration();
        int currentPGen = PersonalizationManager.getChangeGeneration();
        if (appliedThemeGeneration != currentGen || appliedPersonalizationGeneration != currentPGen) {
            appliedThemeGeneration = currentGen;
            appliedPersonalizationGeneration = currentPGen;
            recreate();
            return;
        }
        getDelegate().applyDayNight();
        hideSystemUI();
        int currentEffects = PersonalizationManager.getBackgroundEffectGeneration();
        if (appliedBackgroundEffectGeneration != currentEffects) {
            appliedBackgroundEffectGeneration = currentEffects;
            new PersonalizationManager(this).refreshBackgroundEffects(this);
        }
        LauncherBackgroundController.resume(this);
        refreshNavAccountUI();
        refreshNewsBadge();
    }

    @Override
    protected void onStop() {
        LauncherBackgroundController.stopped(this);
        if (newsReceiverRegistered) {
            unregisterReceiver(newsReceiver);
            newsReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        hideSystemUI();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        navAccountExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        getDelegate().applyDayNight();
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    protected void hideSystemUI() {
        View decorView = getWindow().getDecorView();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }

        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private boolean shouldSuppressTransition(Intent intent) {
        return intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0;
    }

    private boolean hasLauncherBackground() {
        return getClass().getName().startsWith("org.levimc.launcher.ui.activities.")
                && !(this instanceof SplashActivity)
                && !LauncherBackgroundController.isMinecraftBlocked()
                && new PersonalizationManager(this).hasBackgroundImage();
    }

    private boolean isLauncherNavigation(Intent intent) {
        return intent != null && intent.getComponent() != null
                && getPackageName().equals(intent.getComponent().getPackageName())
                && intent.getComponent().getClassName().startsWith("org.levimc.launcher.ui.activities.")
                && !SplashActivity.class.getName().equals(intent.getComponent().getClassName());
    }

    private void applyExitTransition(boolean keepBackground) {
        if (keepBackground) overridePendingTransition(0, 0);
        else overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    public void finish() {
        boolean keepBackground = !isTaskRoot() && hasLauncherBackground();
        if (!isTaskRoot()) LauncherBackgroundController.navigating(this);
        super.finish();
        applyExitTransition(keepBackground);
    }

    @Override
    public void startActivity(Intent intent) {
        prepareBackgroundNavigation(intent);
        try {
            super.startActivity(intent);
        } catch (RuntimeException e) {
            LauncherBackgroundController.navigationFailed(this);
            throw e;
        }
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else overridePendingTransition(0, 0);
    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {
        prepareBackgroundNavigation(intent);
        try {
            super.startActivity(intent, options);
        } catch (RuntimeException e) {
            LauncherBackgroundController.navigationFailed(this);
            throw e;
        }
        if (!shouldSuppressTransition(intent)) {
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        } else overridePendingTransition(0, 0);
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, @Nullable Bundle options) {
        prepareBackgroundNavigation(intent);
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException e) {
            LauncherBackgroundController.navigationFailed(this);
            throw e;
        }
        if (shouldSuppressTransition(intent)) overridePendingTransition(0, 0);
    }

    @Override
    public void finishAfterTransition() {
        boolean keepBackground = !isTaskRoot() && hasLauncherBackground();
        if (!isTaskRoot()) LauncherBackgroundController.navigating(this);
        super.finishAfterTransition();
        applyExitTransition(keepBackground);
    }

    @Override
    public void recreate() {
        LauncherBackgroundController.navigating(this);
        super.recreate();
    }

    private void prepareBackgroundNavigation(Intent intent) {
        if (isLauncherNavigation(intent)) {
            LauncherBackgroundController.navigating(this);
            if (hasLauncherBackground()) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
    }
}
