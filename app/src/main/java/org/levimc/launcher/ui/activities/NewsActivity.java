package org.levimc.launcher.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.news.NewsFeed;
import org.levimc.launcher.core.news.NewsItem;
import org.levimc.launcher.core.news.NewsNotificationHelper;
import org.levimc.launcher.core.news.NewsRepository;
import org.levimc.launcher.core.news.NewsState;
import org.levimc.launcher.ui.widget.SegmentedControl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NewsActivity extends BaseActivity {
    public static final String EXTRA_NEWS_ID = "news_id";
    public static final String EXTRA_NEWS_URL = "news_url";
    private RecyclerView recycler;
    private NewsAdapter adapter;
    private ProgressBar progress;
    private View empty;
    private View enableNotifications;
    private View refresh;
    private SegmentedControl filters;
    private TextView status;
    private NewsFeed currentFeed;
    private final Set<String> sessionUnread = new HashSet<>();
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean refreshing;
    private boolean resumed;
    private boolean remoteRendered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> updateNotificationButton());
        setContentView(R.layout.activity_news);
        setActiveNavTab(0);
        recycler = findViewById(R.id.news_recycler);
        progress = findViewById(R.id.news_progress);
        empty = findViewById(R.id.news_empty);
        enableNotifications = findViewById(R.id.news_enable_notifications);
        refresh = findViewById(R.id.news_refresh);
        filters = findViewById(R.id.news_filters);
        status = findViewById(R.id.news_status);
        adapter = new NewsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);
        recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recycler.setAdapter(adapter);
        filters.setTextSizeSp(12);
        filters.setItems(getString(R.string.news_all), getString(R.string.news_important));
        filters.setSelectedIndex(savedInstanceState == null ? 0 : savedInstanceState.getInt("news_filter", 0), false);
        filters.setOnSegmentSelectedListener(index -> {
            recycler.stopScroll();
            showItems();
            recycler.scrollToPosition(0);
        });
        refresh.setOnClickListener(v -> refreshNews(true));
        enableNotifications.setOnClickListener(v -> requestNotificationPermission());
        updateNotificationButton();
        NewsRepository.loadCached(this, (feed, error) -> {
            if (!remoteRendered) render(feed);
        });
        refreshNews(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (adapter != null) {
            updateNotificationButton();
            markRead();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putInt("news_filter", filters.getSelectedIndex());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        scrollToRequestedItem();
    }

    @Override
    protected void onNewsChanged() {
        if (adapter == null) return;
        NewsRepository.loadCached(this, (feed, error) -> render(feed));
    }

    private void refreshNews(boolean userRequested) {
        if (refreshing) return;
        refreshing = true;
        refresh.setEnabled(false);
        if (currentFeed == null) progress.setVisibility(View.VISIBLE);
        NewsRepository.Callback callback = (feed, error) -> {
            if (isFinishing() || isDestroyed()) return;
            refreshing = false;
            remoteRendered = true;
            refresh.setEnabled(true);
            progress.setVisibility(View.GONE);
            status.setText(error == null ? R.string.news_refreshed : R.string.news_refresh_failed);
            status.setVisibility(error == null ? View.GONE : View.VISIBLE);
            render(feed);
            if (userRequested && error == null) Toast.makeText(this, R.string.news_refreshed, Toast.LENGTH_SHORT).show();
        };
        if (userRequested) NewsRepository.refresh(this, callback);
        else NewsRepository.refreshIfStale(this, callback);
    }

    private void render(NewsFeed feed) {
        if (isFinishing() || isDestroyed()) return;
        currentFeed = feed == null ? new NewsFeed() : feed;
        sessionUnread.addAll(NewsState.getUnreadIds(this, currentFeed));
        progress.setVisibility(View.GONE);
        showItems();
        markRead();
        scrollToRequestedItem();
    }

    private void showItems() {
        if (currentFeed == null) return;
        List<NewsItem> shown = new ArrayList<>();
        for (NewsItem item : currentFeed.items) {
            if (filters.getSelectedIndex() == 0 || item.important) shown.add(item);
        }
        adapter.submit(shown, sessionUnread);
        ((TextView) findViewById(R.id.news_count)).setText(getString(R.string.news_count, shown.size()));
        ((TextView) findViewById(R.id.news_empty_text)).setText(filters.getSelectedIndex() == 0
                ? R.string.news_empty : R.string.news_no_important);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        if (shown.isEmpty()) {
            recycler.stopScroll();
            recycler.scrollToPosition(0);
        }
    }

    private void markRead() {
        if (resumed && currentFeed != null) NewsState.markAllRead(this, currentFeed);
    }

    private void scrollToRequestedItem() {
        if (adapter == null || getIntent() == null) return;
        String requested = getIntent().getStringExtra(EXTRA_NEWS_ID);
        if (requested == null) return;
        if (adapter.indexOf(requested) < 0 && currentFeed != null) {
            for (NewsItem item : currentFeed.items) {
                if (requested.equals(item.id)) {
                    filters.setSelectedIndex(0, false);
                    showItems();
                    break;
                }
            }
        }
        int position = adapter.indexOf(requested);
        if (position >= 0) {
            getIntent().removeExtra(EXTRA_NEWS_ID);
            getIntent().removeExtra(EXTRA_NEWS_URL);
            recycler.scrollToPosition(position);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notifications_requested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested", true).apply();
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void updateNotificationButton() {
        NewsNotificationHelper.initialize(this);
        enableNotifications.setVisibility(NotificationManagerCompat.from(this).areNotificationsEnabled() ? View.GONE : View.VISIBLE);
    }
}
