package org.levimc.launcher.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.news.NewsFeed;
import org.levimc.launcher.core.news.NewsNotificationHelper;
import org.levimc.launcher.core.news.NewsRepository;
import org.levimc.launcher.core.news.NewsState;
import org.levimc.launcher.ui.animation.DynamicAnim;

public final class NewsActivity extends BaseActivity {
    public static final String EXTRA_NEWS_ID = "news_id";
    public static final String EXTRA_NEWS_URL = "news_url";
    private RecyclerView recycler;
    private NewsAdapter adapter;
    private ProgressBar progress;
    private View empty;
    private Button enableNotifications;
    private View refresh;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean rendered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> updateNotificationButton());
        setContentView(R.layout.activity_news);
        setActiveNavTab(0);

        recycler = findViewById(R.id.news_recycler);
        progress = findViewById(R.id.news_progress);
        empty = findViewById(R.id.news_empty);
        enableNotifications = findViewById(R.id.news_enable_notifications);
        refresh = findViewById(R.id.news_refresh);

        adapter = new NewsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(false);

        refresh.setOnClickListener(v -> refreshNews(true));
        enableNotifications.setOnClickListener(v -> requestNotificationPermission());
        DynamicAnim.applyPressScale(refresh);
        DynamicAnim.applyPressScale(enableNotifications);

        updateNotificationButton();
        NewsRepository.loadCached(this, (feed, error) -> render(feed));
        refreshNews(false);
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
        refresh.setEnabled(false);
        if (!rendered) progress.setVisibility(View.VISIBLE);
        NewsRepository.Callback callback = (feed, error) -> {
            refresh.setEnabled(true);
            progress.setVisibility(View.GONE);
            render(feed);
            if (userRequested) {
                Toast.makeText(this,
                        error == null ? R.string.news_refreshed : R.string.news_refresh_failed,
                        Toast.LENGTH_SHORT).show();
            }
        };
        if (userRequested) NewsRepository.refresh(this, callback);
        else NewsRepository.refreshIfStale(this, callback);
    }

    private void render(NewsFeed feed) {
        if (feed == null) feed = new NewsFeed();
        java.util.Set<String> unreadIds = NewsState.getUnreadIds(this, feed);
        adapter.submit(feed.items, unreadIds);
        rendered = true;
        progress.setVisibility(View.GONE);
        empty.setVisibility(feed.items.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(feed.items.isEmpty() ? View.GONE : View.VISIBLE);
        NewsState.markAllRead(this, feed);
        scrollToRequestedItem();
    }

    private void scrollToRequestedItem() {
        if (adapter == null || recycler == null || getIntent() == null) return;
        String requestedId = getIntent().getStringExtra(EXTRA_NEWS_ID);
        int position = adapter.indexOf(requestedId);
        if (position >= 0) recycler.scrollToPosition(position);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void updateNotificationButton() {
        NewsNotificationHelper.initialize(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            enableNotifications.setVisibility(View.GONE);
            return;
        }
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        enableNotifications.setVisibility(granted ? View.GONE : View.VISIBLE);
    }
}
