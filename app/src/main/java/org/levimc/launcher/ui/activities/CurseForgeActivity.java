package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.curseforge.CurseForgeClient;
import org.levimc.launcher.core.curseforge.models.Content;
import org.levimc.launcher.core.curseforge.models.ContentSearchResponse;
import org.levimc.launcher.ui.adapter.CurseForgeContentAdapter;
import org.levimc.launcher.ui.widget.CatalogSelect;
import org.levimc.launcher.util.UIHelper;
import org.levimc.launcher.util.UiSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CurseForgeActivity extends BaseActivity {

    private static final int PAGE_SIZE = 20;

    private EditText searchBox;
    private CatalogSelect classSelect;
    private CatalogSelect versionSelect;
    private CatalogSelect sortSelect;
    private RecyclerView recyclerView;
    private ProgressBar loadingProgress;
    private View emptyState;
    private TextView emptyMessage;
    private View retryButton;
    private CurseForgeContentAdapter adapter;
    private CurseForgeClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentPage = 1;
    private int totalPages = 1;
    private int requestSequence;
    private Category currentCategory;
    private SortOption currentSort;
    /** Empty means all versions. */
    private String currentVersion = "";
    private final List<String> gameVersions = new ArrayList<>();

    private static class Category {
        final String name;
        final int id;

        Category(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    private static class SortOption {
        final String name;
        final String field;
        final String order;

        SortOption(String name, String field, String order) {
            this.name = name;
            this.field = field;
            this.order = order;
        }
    }

    private final List<Category> categories = new ArrayList<>();
    private final List<SortOption> sortOptions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(UiSettings.useTwoPane(this)
                ? R.layout.activity_curseforge : R.layout.activity_curseforge_stacked);
        client = CurseForgeClient.getInstance();
        setupData();
        initViews();
        loadContent();
        loadGameVersions();
    }

    private void setupData() {
        categories.add(new Category(getString(R.string.catalog_cf_all_classes), 0));
        categories.add(new Category("Addons", 4984));
        categories.add(new Category("Maps", 6913));
        categories.add(new Category("Skins", 6925));
        categories.add(new Category("Textures", 6929));
        categories.add(new Category("Scripts", 6940));

        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_popular), CurseForgeClient.SORT_POPULARITY, "desc"));
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_downloads), CurseForgeClient.SORT_TOTAL_DOWNLOADS, "desc"));
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_updated), CurseForgeClient.SORT_LAST_UPDATED, "desc"));
        sortOptions.add(new SortOption(getString(R.string.catalog_cf_sort_name), CurseForgeClient.SORT_NAME, "asc"));
    }

    private void initViews() {
        searchBox = findViewById(R.id.search_box);
        recyclerView = findViewById(R.id.mods_recycler);
        loadingProgress = findViewById(R.id.loading_progress);
        emptyState = findViewById(R.id.empty_state);
        emptyMessage = findViewById(R.id.empty_message);
        retryButton = findViewById(R.id.cf_retry);

        adapter = new CurseForgeContentAdapter(this::onContentClick, new CurseForgeContentAdapter.OnPageChangeListener() {
            @Override
            public void onNextPage() {
                if (currentPage < totalPages) {
                    currentPage++;
                    loadContent();
                }
            }

            @Override
            public void onPrevPage() {
                if (currentPage > 1) {
                    currentPage--;
                    loadContent();
                }
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null);
        recyclerView.setAdapter(adapter);

        searchBox.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
        findViewById(R.id.btn_search).setOnClickListener(v -> runSearch());
        retryButton.setOnClickListener(v -> {
            loadContent();
            if (gameVersions.isEmpty()) loadGameVersions();
        });

        List<String> classNames = new ArrayList<>();
        for (Category category : categories) classNames.add(category.name);
        currentCategory = categories.get(0);
        classSelect = new CatalogSelect(findViewById(R.id.cf_select_class), getString(R.string.catalog_cf_class));
        classSelect.setItems(classNames, 0);
        classSelect.setOnPick(index -> {
            currentCategory = categories.get(index);
            currentPage = 1;
            loadContent();
        });

        List<String> sortNames = new ArrayList<>();
        for (SortOption option : sortOptions) sortNames.add(option.name);
        currentSort = sortOptions.get(0);
        sortSelect = new CatalogSelect(findViewById(R.id.btn_sort), getString(R.string.catalog_cf_sort));
        sortSelect.setItems(sortNames, 0);
        sortSelect.setOnPick(index -> {
            currentSort = sortOptions.get(index);
            currentPage = 1;
            loadContent();
        });

        versionSelect = new CatalogSelect(findViewById(R.id.cf_select_version), getString(R.string.catalog_cf_game_version));
        bindVersions();
        versionSelect.setOnPick(index -> {
            currentVersion = index == 0 ? "" : gameVersions.get(index - 1);
            currentPage = 1;
            loadContent();
        });
    }

    private void runSearch() {
        currentPage = 1;
        loadContent();
        UIHelper.hideKeyboard(this);
        searchBox.clearFocus();
    }

    private void bindVersions() {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.catalog_cf_all_versions));
        items.addAll(gameVersions);
        int index = currentVersion.isEmpty() ? 0 : gameVersions.indexOf(currentVersion) + 1;
        versionSelect.setItems(items, Math.max(0, index));
        versionSelect.setEnabled(!gameVersions.isEmpty());
    }

    private void loadGameVersions() {
        client.getGameVersions(new CurseForgeClient.CurseForgeCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> result) {
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    gameVersions.clear();
                    if (result != null) gameVersions.addAll(result);
                    bindVersions();
                });
            }

            @Override
            public void onError(Throwable error) {
                // The version filter stays at all versions (disabled); searching still works.
            }
        });
    }

    private void showEmpty(CharSequence message, boolean retry) {
        emptyMessage.setText(message);
        retryButton.setVisibility(retry ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(View.VISIBLE);
    }

    private void loadContent() {
        String query = searchBox.getText().toString().trim();
        Category category = currentCategory;
        String version = currentVersion;

        SortOption sort = currentSort;
        int request = ++requestSequence;
        int requestedPage = currentPage;
        loadingProgress.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        recyclerView.setVisibility(adapter.getItemCount() > 0 ? View.VISIBLE : View.INVISIBLE);

        client.searchContent(query, category != null ? category.id : 0, version,
                (requestedPage - 1) * PAGE_SIZE, PAGE_SIZE,
                sort != null ? sort.field : CurseForgeClient.SORT_POPULARITY,
                sort != null ? sort.order : "desc",
                new CurseForgeClient.CurseForgeCallback<ContentSearchResponse>() {
                    @Override
                    public void onSuccess(ContentSearchResponse result) {
                        handler.post(() -> {
                            if (isFinishing() || isDestroyed() || request != requestSequence) return;
                            loadingProgress.setVisibility(View.GONE);
                            if (result != null && result.data != null) {
                                if (result.pagination != null && result.pagination.totalCount > 0) {
                                    totalPages = Math.max(1, (int) Math.ceil((double) result.pagination.totalCount / PAGE_SIZE));
                                } else {
                                    totalPages = result.data.size() < PAGE_SIZE ? requestedPage : requestedPage + 1;
                                }
                                adapter.setContents(result.data, requestedPage, totalPages);
                                boolean empty = result.data.isEmpty();
                                if (empty) showEmpty(getString(R.string.catalog_cf_no_results), false);
                                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                                if (!empty) recyclerView.scrollToPosition(0);
                            } else {
                                adapter.setContents(Collections.emptyList(), 1, 1);
                                recyclerView.setVisibility(View.GONE);
                                showEmpty(getString(R.string.catalog_cf_no_results), false);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        handler.post(() -> {
                            if (isFinishing() || isDestroyed() || request != requestSequence) return;
                            loadingProgress.setVisibility(View.GONE);
                            if (adapter.getItemCount() == 0) {
                                recyclerView.setVisibility(View.GONE);
                                showEmpty(getString(R.string.error_message_format, error.getMessage()), true);
                            } else {
                                recyclerView.setVisibility(View.VISIBLE);
                                android.widget.Toast.makeText(CurseForgeActivity.this,
                                        getString(R.string.error_message_format, error.getMessage()),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
    }

    private void onContentClick(Content content) {
        Intent intent = new Intent(this, ContentDetailsActivity.class);
        intent.putExtra(ContentDetailsActivity.EXTRA_CONTENT, content);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        requestSequence++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
