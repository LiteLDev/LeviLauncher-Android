package org.levimc.launcher.ui.activities;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.ViewGroup;

import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentImporter;
import org.levimc.launcher.core.curseforge.CurseForgeClient;
import org.levimc.launcher.core.curseforge.models.Content;
import org.levimc.launcher.core.curseforge.models.ContentFile;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.util.LauncherStorage;



import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ContentDetailsActivity extends BaseActivity {

    public static final String EXTRA_CONTENT = "extra_content";

    private Content content;
    private ImageView icon;
    private TextView title;
    private TextView author;
    private TextView metadata;

    private WebView summary;
    
    private MaterialButton btnInstall;
    private MaterialButton btnBrowser;
    private ProgressBar progressBar;

    private ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private ContentImporter contentImporter;
    private VersionManager versionManager;
    private CurseForgeClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_content_details);

        content = (Content) getIntent().getSerializableExtra(EXTRA_CONTENT);
        if (content == null) {
            finish();
            return;
        }

        contentImporter = new ContentImporter(this);
        versionManager = VersionManager.get(this);
        client = CurseForgeClient.getInstance();

        initViews();
        bindData();
        loadDescription();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (summary != null) {
            summary.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (summary != null) {
            summary.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        if (summary != null) {
            ViewGroup parent = (ViewGroup) summary.getParent();
            if (parent != null) parent.removeView(summary);
            summary.destroy();
            summary = null;
        }
        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void initWebView() {
        summary.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        WebSettings settings = summary.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        summary.setVerticalScrollBarEnabled(false);
        summary.setHorizontalScrollBarEnabled(false);
        summary.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            private boolean handleUrl(Uri uri) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(ContentDetailsActivity.this,
                            getString(R.string.cannot_open_link, uri.toString()), Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {
                }
                return true;
            }
        });
    }

    private void initViews() {

        icon = findViewById(R.id.detail_icon);
        title = findViewById(R.id.detail_title);
        author = findViewById(R.id.detail_author);
        metadata = findViewById(R.id.detail_metadata);
        summary = findViewById(R.id.detail_summary);
        btnInstall = findViewById(R.id.btn_install_header);
        btnBrowser = findViewById(R.id.btn_browser);
        progressBar = findViewById(R.id.download_progress);
        
        initWebView();
        
        btnInstall.setOnClickListener(v -> onInstallClick());
        btnBrowser.setOnClickListener(v -> onBrowserClick());
    }

    private void bindData() {
        title.setText(content.name != null ? content.name : "");
        if (content.authors != null && !content.authors.isEmpty() && content.authors.get(0) != null) {
            author.setText(getString(R.string.mod_author_byline, content.authors.get(0).name));
            author.setVisibility(View.VISIBLE);
        } else {
            author.setVisibility(View.GONE);
        }

        StringBuilder info = new StringBuilder();
        double count = content.downloadCount;
        if (count >= 1000000) {
            info.append(String.format(java.util.Locale.getDefault(), "%.1fM", count / 1000000.0));
        } else if (count >= 1000) {
            info.append(String.format(java.util.Locale.getDefault(), "%.1fK", count / 1000.0));
        } else {
            info.append((long) count);
        }
        info.append(" downloads");
        if (content.categories != null && !content.categories.isEmpty() && content.categories.get(0) != null) {
            info.append("  ·  ").append(content.categories.get(0).name);
        }
        if (content.dateModified != null && content.dateModified.length() >= 10) {
            info.append("  ·  ").append(content.dateModified, 0, 10);
        }
        metadata.setText(info.toString());
        org.levimc.launcher.util.PersonalizationManager personalization =
                new org.levimc.launcher.util.PersonalizationManager(this);
        if (personalization.hasCustomAccent()) {
            btnInstall.setBackgroundTintList(android.content.res.ColorStateList.valueOf(personalization.getAccentColor()));
            int onAccent = android.graphics.Color.luminance(personalization.getAccentColor()) > 0.42f
                    ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
            btnInstall.setTextColor(onAccent);
            btnInstall.setIconTint(android.content.res.ColorStateList.valueOf(onAccent));
        }
        if (content.logo != null && content.logo.thumbnailUrl != null) {
            Glide.with(this)
                    .load(content.logo.thumbnailUrl)
                    .centerCrop()
                    .placeholder(R.drawable.ic_minecraft_cube)
                    .error(R.drawable.ic_minecraft_cube)
                    .into(icon);
        }
    }

    private void loadDescription() {
        client.getContentDescription(content.id, new CurseForgeClient.CurseForgeCallback<String>() {
            @Override
            public void onSuccess(String result) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || summary == null || result == null || result.isEmpty()) return;
                    String html = "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            + "<style>body{color:" + getHexColor(R.color.on_surface)
                            + ";font-family:sans-serif;font-size:13px;line-height:1.5;overflow-wrap:anywhere;"
                            + "background:transparent;margin:0;padding:0}"
                            + "a{color:" + getHexColor(R.color.primary) + ";text-decoration:none}"
                            + "img{display:block;max-width:100%;height:auto;margin:8px auto;border-radius:7px}"
                            + "iframe{max-width:100%;width:100%;border:0;aspect-ratio:16/9}"
                            + "p{margin:7px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}"
                            + "table{max-width:100%;display:block;overflow-x:auto}</style></head><body>"
                            + result + "</body></html>";
                    summary.loadDataWithBaseURL("https://www.curseforge.com", html, "text/html", "utf-8", null);
                });
            }

            @Override
            public void onError(Throwable error) {
            }
        });
    }

    private void onInstallClick() {
        loadAllFiles();
    }

    private void loadAllFiles() {
        progressBar.setVisibility(View.VISIBLE);
        btnInstall.setEnabled(false);

        client.getModFiles(content.id, 0, 50, new CurseForgeClient.CurseForgeCallback<>() {
            @Override
            public void onSuccess(org.levimc.launcher.core.curseforge.models.ModFilesResponse result) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                    if (result != null && result.data != null && !result.data.isEmpty()) {
                        showFilesDialog(result.data);
                    } else {
                        Toast.makeText(ContentDetailsActivity.this, R.string.no_files_found, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                    Toast.makeText(ContentDetailsActivity.this, getString(R.string.failed_to_load_files, t.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showFilesDialog(java.util.List<ContentFile> files) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        dialog.setContentView(R.layout.dialog_file_list);

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.hasCustomAccent() ? pm.getAccentColor() : androidx.core.content.ContextCompat.getColor(this, R.color.primary);

        RecyclerView recycler = dialog.findViewById(R.id.recycler_files);

        org.levimc.launcher.ui.adapter.FileListAdapter adapter = new org.levimc.launcher.ui.adapter.FileListAdapter(file -> {
            dialog.dismiss();
            downloadAndImport(file);
        }, accent);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        adapter.setFiles(files);

        int maxListHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        int estimatedHeight = Math.round(getResources().getDisplayMetrics().density * 62f * files.size());
        ViewGroup.LayoutParams listParams = recycler.getLayoutParams();
        listParams.height = Math.min(maxListHeight, Math.max(1, estimatedHeight));
        recycler.setLayoutParams(listParams);
        recycler.setItemAnimator(null);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        dialog.show();
        
        if (dialog.getWindow() != null) {
            android.view.View decorView = dialog.getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                dialog.getWindow().setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = decorView.getWindowInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                }
            }
            dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }

        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private void onBrowserClick() {
        if (content.links != null && content.links.websiteUrl != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(content.links.websiteUrl));
            startActivity(intent);
        } else {
            Toast.makeText(this, R.string.no_website_link_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadAndImport(ContentFile file) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            btnInstall.setEnabled(false);
            Toast.makeText(this, getString(R.string.curseforge_downloading), Toast.LENGTH_SHORT).show();
        });
        
        downloadExecutor.execute(() -> {
            try {
                File cacheDir = getCacheDir();
                File outputFile = new File(cacheDir, file.fileName);
                
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder().url(file.downloadUrl).build();
                
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) throw new IOException("Failed to download");
                    
                    try (InputStream is = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.curseforge_importing), Toast.LENGTH_SHORT).show();
                    importFile(outputFile);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnInstall.setEnabled(true);
                    Toast.makeText(this, getString(R.string.curseforge_download_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void importFile(File file) {
        Uri uri = Uri.fromFile(file);
        
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) {
            progressBar.setVisibility(View.GONE);
            btnInstall.setEnabled(true);
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }
        
        File worldsDir = getWorldsDirectory();
        File resourcePacksDir = getPackDirectory("resource_packs");
        File behaviorPacksDir = getPackDirectory("behavior_packs");
        File skinPacksDir = getPackDirectory("skin_packs");

        contentImporter.importContent(java.util.Collections.singletonList(uri), resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir,
            new ContentImporter.ImportCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnInstall.setEnabled(true);
                        Toast.makeText(ContentDetailsActivity.this, message, Toast.LENGTH_SHORT).show();
                        if (file.exists()) file.delete();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnInstall.setEnabled(true);
                        Toast.makeText(ContentDetailsActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onProgress(int progress) {
                }
            });
    }
    private File getPackDirectory(String packType) {
        File gameDataDir = getGameDataDirForSavedStorageType();
        return gameDataDir == null ? null : new File(gameDataDir, packType);
    }

    private File getWorldsDirectory() {
        File gameDataDir = getGameDataDirForSavedStorageType();
        return gameDataDir == null ? null : new File(gameDataDir, "minecraftWorlds");
    }

    private File getGameDataDirForSavedStorageType() {
        android.content.SharedPreferences prefs = getSharedPreferences("content_management", MODE_PRIVATE);
        String savedType = prefs.getString("storage_type", "INTERNAL");
        FeatureSettings.StorageType currentStorageType = parseStorageType(savedType);
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return null;
        currentStorageType = LauncherStorage.normalizeContentStorageType(
                currentStorageType,
                currentVersion.versionIsolation
        );
        return LauncherStorage.getContentGameDataDir(this, currentVersion.getStorageProfileId(), currentStorageType);
    }

    private FeatureSettings.StorageType parseStorageType(String value) {
        try {
            return FeatureSettings.StorageType.valueOf(value);
        } catch (Exception ignored) {
            return FeatureSettings.StorageType.INTERNAL;
        }
    }

    private String getHexColor(int colorResId) {
        int color = getResources().getColor(colorResId, getTheme());
        return String.format("#%06X", (0xFFFFFF & color));
    }
}
