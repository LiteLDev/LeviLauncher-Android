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
import org.levimc.launcher.util.ThemePalette;
import org.levimc.launcher.util.UiSettings;
import org.levimc.launcher.util.WallpaperFolder;
import org.levimc.launcher.ui.widget.UiKit;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

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
    private int appliedLayoutGeneration = -1;
    private boolean navBarInjected = false;
    private int activeNavTabId = View.NO_ID;
    private boolean finishingByBack;
    private final Handler scheduleHandler = new Handler(Looper.getMainLooper());
    private final Runnable scheduleCheck = new Runnable() {
        @Override
        public void run() {
            if (new ThemeManager(BaseActivity.this).refreshScheduledMode()) {
                recreate();
                return;
            }
            scheduleHandler.postDelayed(this, 60_000L);
        }
    };

    /** Pages left with Back, for the Forward button (Windows browser-style history). */
    private static final ArrayDeque<Intent> sForwardHistory = new ArrayDeque<>();
    /** Page classes alive in the task, so breadcrumbs only jump to pages that can be restored. */
    private static final Set<Class<?>> sLivePages = new HashSet<>();
    private static final String EXTRA_FROM_FORWARD = "org.levimc.launcher.extra.FROM_FORWARD";
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
        appliedLayoutGeneration = UiSettings.getLayoutGeneration();
        UiSettings.refreshAnimations(this);
        super.onCreate(savedInstanceState);
        sLivePages.add(getClass());
        if (savedInstanceState == null && !getIntent().getBooleanExtra(EXTRA_FROM_FORWARD, false)) {
            sForwardHistory.clear();
        }
        // Lowest-priority back handler: pages' own callbacks run first. Marks a back navigation so
        // finish() can offer the page to the Forward button.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishingByBack = true;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
                if (!isFinishing()) finishingByBack = false;
            }
        });
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
            if (avoidsCutoutWithoutShell()) org.levimc.launcher.ui.widget.CutoutAvoider.attachToPage(contentView);
            applyPersonalization();
            return;
        }

        boolean navbar = UiSettings.useTopNavbar(this);
        View shell = LayoutInflater.from(this).inflate(
                navbar ? R.layout.shell_navbar : R.layout.shell_sidebar, null, false);
        FrameLayout holder = shell.findViewById(R.id.shell_content);
        holder.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!UiSettings.animationsDisabled()) {
            contentView.setAlpha(0f);
            contentView.setTranslationY(8f * getResources().getDisplayMetrics().density);
        }

        super.setContentView(shell);
        org.levimc.launcher.ui.widget.CutoutAvoider.attachToShell(shell, this::placeRailIndicator);
        navBarInjected = true;
        setupBaseNavBar();

        applyPersonalization();
        WallpaperFolder.rotateOnLaunch(this);

        if (!UiSettings.animationsDisabled()) {
            contentView.post(() -> {
                DynamicAnim.springAlphaTo(contentView, 1f).start();
                DynamicAnim.springTranslationYTo(contentView, 0f).start();
            });
        }
    }

    private void applyPersonalization() {
        PersonalizationManager pm = new PersonalizationManager(this);
        pm.applyToActivity(this);
    }

    protected boolean shouldSkipNavBar() {
        return false;
    }

    /** Full-screen pages without the shell (splash, game loading) draw edge to edge on purpose. */
    protected boolean avoidsCutoutWithoutShell() {
        return !(this instanceof SplashActivity);
    }

    private static final int[] NAV_TAB_IDS = {
            R.id.nav_tab_launch, R.id.nav_tab_download, R.id.nav_tab_instances,
            R.id.nav_tab_about, R.id.nav_tab_settings
    };

    private void setupBaseNavBar() {
        PersonalizationManager pm = new PersonalizationManager(this);
        boolean navbar = UiSettings.useTopNavbar(this);

        // Over a wallpaper the chrome becomes translucent (Windows NAVBAR_BG) and the page area clear.
        if (pm.hasBackgroundImage()) {
            int chrome = pm.getChromeGlassColor(this);
            View root = findViewById(R.id.shell_root);
            View bar = findViewById(R.id.nav_bar_root);
            View content = findViewById(R.id.shell_content);
            if (navbar) {
                if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
                if (bar != null) bar.setBackgroundColor(chrome);
            } else {
                if (root != null) root.setBackgroundColor(chrome);
                if (content != null) {
                    GradientDrawable frame = new GradientDrawable();
                    float r = 20f * getResources().getDisplayMetrics().density;
                    frame.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, 0, 0});
                    frame.setColor(Color.TRANSPARENT);
                    frame.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)),
                            ContextCompat.getColor(this, R.color.ui_border));
                    content.setBackground(new android.graphics.drawable.InsetDrawable(frame, 0, 0, -4, -4));
                }
            }
        }

        for (int id : NAV_TAB_IDS) styleNavTab(findViewById(id), false);

        View backButton = findViewById(R.id.nav_back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
        View forwardButton = findViewById(R.id.nav_forward_button);
        if (forwardButton != null) {
            forwardButton.setOnClickListener(v -> {
                Intent next = sForwardHistory.pollFirst();
                if (next == null) return;
                next.putExtra(EXTRA_FROM_FORWARD, true);
                startActivity(next);
            });
        }
        refreshHistoryButtons();

        View themeToggle = findViewById(R.id.nav_theme_toggle);
        if (themeToggle instanceof ImageView) {
            ThemeManager tm = new ThemeManager(this);
            if (tm.canQuickToggle()) {
                boolean dark = tm.getCurrentMode() == ThemeManager.MODE_DARK;
                ((ImageView) themeToggle).setImageResource(dark ? R.drawable.ic_moon : R.drawable.ic_sun);
                themeToggle.setVisibility(View.VISIBLE);
                themeToggle.setOnClickListener(v -> new ThemeManager(this).setThemeMode(
                        dark ? ThemeManager.MODE_LIGHT : ThemeManager.MODE_DARK));
            } else {
                themeToggle.setVisibility(View.GONE);
            }
        }

        View signIn = findViewById(R.id.nav_sign_in_button);
        if (signIn != null) {
            signIn.setOnClickListener(v -> navAccountLoginLauncher.launch(new Intent(this, MsftLoginActivity.class)));
        }

        View avatarContainer = findViewById(R.id.nav_account_avatar_container);
        if (avatarContainer != null) {
            avatarContainer.setOnClickListener(v -> {
                if (!(this instanceof AccountsActivity)) startActivity(new Intent(this, AccountsActivity.class));
            });
            DynamicAnim.applyPressScale(avatarContainer);
        }

        View news = findViewById(R.id.nav_news_container);
        if (news != null) {
            news.setOnClickListener(v -> {
                if (!(this instanceof NewsActivity)) {
                    startActivity(new Intent(this, NewsActivity.class));
                }
            });
        }

        bindNavTab(R.id.nav_tab_launch, MainActivity.class);
        bindNavTab(R.id.nav_tab_download, GameDownloadActivity.class);
        bindNavTab(R.id.nav_tab_instances, InstancesActivity.class);
        bindNavTab(R.id.nav_tab_about, AboutActivity.class);
        bindNavTab(R.id.nav_tab_settings, SettingsActivity.class);

        View railScroll = findViewById(R.id.nav_rail_scroll);
        if (railScroll != null) {
            railScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> placeRailIndicator());
        }

        refreshBreadcrumbs();
        refreshNavAccountUI();
        refreshNewsBadge();
    }

    /** Top-level pages are switched like tabs: reuse the page if it is open instead of stacking copies. */
    private void bindNavTab(int id, Class<? extends android.app.Activity> page) {
        View tab = findViewById(id);
        if (tab == null) return;
        tab.setOnClickListener(v -> {
            if (page.isInstance(this)) return;
            Intent intent = new Intent(this, page);
            if (page == MainActivity.class || sLivePages.contains(page)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            startActivity(intent);
        });
    }

    private void refreshHistoryButtons() {
        View back = findViewById(R.id.nav_back_button);
        if (back != null) {
            boolean canBack = !isTaskRoot();
            back.setEnabled(canBack);
            back.setAlpha(canBack ? 1f : 0.35f);
        }
        View forward = findViewById(R.id.nav_forward_button);
        if (forward != null) {
            boolean canForward = !sForwardHistory.isEmpty();
            forward.setEnabled(canForward);
            forward.setAlpha(canForward ? 1f : 0.35f);
        }
    }

    /** Title of this page in the breadcrumbs. Pages with a dynamic title (a world, a mod) override it. */
    @Nullable
    protected CharSequence getBreadcrumbTitle() {
        NavRoutes.Route route = NavRoutes.get(getClass());
        return route != null ? getString(route.title) : getTitle();
    }

    /** Rebuilds the breadcrumbs, e.g. after the page title changed. */
    protected void refreshBreadcrumbs() {
        LinearLayout crumbs = findViewById(R.id.nav_breadcrumbs);
        if (crumbs == null) return;
        TextView appName = findViewById(R.id.nav_app_name);
        for (int i = crumbs.getChildCount() - 1; i >= 0; i--) {
            if (crumbs.getChildAt(i) != appName) crumbs.removeViewAt(i);
        }
        if (this instanceof MainActivity) {
            if (appName != null) appName.setVisibility(View.VISIBLE);
            return;
        }
        if (appName != null) appName.setVisibility(View.GONE);

        java.util.List<Class<? extends android.app.Activity>> chain = new java.util.ArrayList<>();
        NavRoutes.Route route = NavRoutes.get(getClass());
        Class<? extends android.app.Activity> parent = route != null ? route.parent : MainActivity.class;
        while (parent != null && chain.size() < 6) {
            chain.add(0, parent);
            NavRoutes.Route r = NavRoutes.get(parent);
            parent = r != null ? r.parent : null;
        }
        float density = getResources().getDisplayMetrics().density;
        int muted = ContextCompat.getColor(this, R.color.text_secondary);
        for (Class<? extends android.app.Activity> page : chain) {
            NavRoutes.Route r = NavRoutes.get(page);
            TextView crumb = makeCrumb(r != null ? getString(r.title) : page.getSimpleName(), false);
            boolean reachable = r != null && (r.standalone || sLivePages.contains(page));
            if (reachable) {
                crumb.setBackgroundResource(R.drawable.bg_shell_icon_button);
                crumb.setOnClickListener(v -> {
                    Intent intent = new Intent(this, page);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                });
            }
            crumbs.addView(crumb);
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_ui_chevron_right);
            chevron.setImageTintList(ColorStateList.valueOf(muted));
            int size = Math.round(16 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(Math.round(2 * density));
            lp.setMarginEnd(Math.round(2 * density));
            crumbs.addView(chevron, lp);
        }
        CharSequence title = getBreadcrumbTitle();
        crumbs.addView(makeCrumb(title != null ? title : "", true));
    }

    private TextView makeCrumb(CharSequence text, boolean current) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setSingleLine(true);
        t.setTextSize(14);
        t.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.misans),
                current ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(ContextCompat.getColor(this, current ? R.color.text_primary : R.color.text_secondary));
        float density = getResources().getDisplayMetrics().density;
        int padH = Math.round(6 * density);
        t.setPadding(padH, 0, padH, 0);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setMinHeight(Math.round(40 * density));
        return t;
    }

    private void styleNavTab(@Nullable View view, boolean active) {
        if (!(view instanceof TextView)) return;
        TextView tab = (TextView) view;
        boolean navbar = UiSettings.useTopNavbar(this);
        PersonalizationManager pm = new PersonalizationManager(this);
        boolean dark = pm.isDarkMode(this);
        int accent = pm.getAccentColor(dark);
        float density = getResources().getDisplayMetrics().density;
        if (navbar) {
            int text = ContextCompat.getColor(this, R.color.text_primary);
            tab.setTextColor(text);
            tab.setTypeface(tab.getTypeface(), active ? Typeface.BOLD : Typeface.NORMAL);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(text));
            if (active) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(12 * density);
                bg.setColor(ContextCompat.getColor(this, R.color.surface_variant));
                tab.setBackground(bg);
            } else {
                tab.setBackgroundResource(R.drawable.bg_shell_icon_button);
            }
        } else {
            int icon = active ? ThemePalette.foreground(accent, dark) : ContextCompat.getColor(this, R.color.ui_muted);
            TextViewCompat.setCompoundDrawableTintList(tab, ColorStateList.valueOf(icon));
            if (active) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(12 * density);
                bg.setColor(ThemePalette.withAlpha(accent, 0.10f));
                tab.setBackground(bg);
            } else {
                tab.setBackgroundResource(R.drawable.bg_shell_icon_button);
            }
        }
    }

    /** Moves the 4dp accent pill next to the active rail item (Windows Sidebar indicator). */
    private void placeRailIndicator() {
        View indicator = findViewById(R.id.nav_rail_indicator);
        if (indicator == null) return;
        View tab = activeNavTabId != View.NO_ID ? findViewById(activeNavTabId) : null;
        if (tab == null) {
            indicator.setVisibility(View.INVISIBLE);
            return;
        }
        indicator.setBackgroundTintList(ColorStateList.valueOf(new PersonalizationManager(this).getAccentColor(
                new PersonalizationManager(this).isDarkMode(this))));
        View rail = findViewById(R.id.nav_rail);
        tab.post(() -> {
            // Window coordinates: the rail scrolls and items may be moved away from the camera.
            int[] tabLoc = new int[2];
            int[] railLoc = new int[2];
            tab.getLocationInWindow(tabLoc);
            (rail != null ? rail : (View) indicator.getParent()).getLocationInWindow(railLoc);
            float y = tabLoc[1] - railLoc[1] + (tab.getHeight() - indicator.getHeight()) / 2f;
            indicator.setTranslationY(y);
            indicator.setVisibility(View.VISIBLE);
        });
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

    /** The wallpaper was replaced in place on this page; do not recreate it for that change on resume. */
    public void markPersonalizationApplied() {
        appliedPersonalizationGeneration = PersonalizationManager.getChangeGeneration();
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
        activeNavTabId = activeTabId;
        for (int id : NAV_TAB_IDS) styleNavTab(findViewById(id), id == activeTabId);
        placeRailIndicator();
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
        if (new ThemeManager(this).refreshScheduledMode()) currentGen = ThemeManager.getThemeChangeGeneration();
        int currentLayout = UiSettings.getLayoutGeneration();
        if (appliedThemeGeneration != currentGen || appliedPersonalizationGeneration != currentPGen
                || appliedLayoutGeneration != currentLayout) {
            appliedThemeGeneration = currentGen;
            appliedPersonalizationGeneration = currentPGen;
            appliedLayoutGeneration = currentLayout;
            recreate();
            return;
        }
        UiSettings.refreshAnimations(this);
        refreshHistoryButtons();
        scheduleHandler.removeCallbacks(scheduleCheck);
        scheduleHandler.postDelayed(scheduleCheck, 60_000L);
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
        scheduleHandler.removeCallbacks(scheduleCheck);
        hideSystemUI();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        navAccountExecutor.shutdownNow();
        if (isFinishing()) sLivePages.remove(getClass());
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
        if (finishingByBack && navBarInjected && !isTaskRoot()) {
            Intent again = new Intent(getIntent());
            again.removeExtra(EXTRA_FROM_FORWARD);
            again.setFlags(0);
            sForwardHistory.addFirst(again);
            while (sForwardHistory.size() > 20) sForwardHistory.pollLast();
        }
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
