package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.PopupMenu;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentImporter;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.core.content.ResourcePackManager;
import org.levimc.launcher.core.content.ScreenshotManager;
import org.levimc.launcher.core.content.ServerManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityContentManagementBinding;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ContentManagementActivity extends BaseActivity {
    
    private static final String PREFS_NAME = "content_management";
    private static final String KEY_STORAGE_TYPE = "storage_type";
    
    private ActivityContentManagementBinding binding;
    private ContentManager contentManager;
    private ContentImporter contentImporter;
    private VersionManager versionManager;
    private FeatureSettings.StorageType currentStorageType = FeatureSettings.StorageType.INTERNAL;
    private SharedPreferences prefs;
    private ActivityResultLauncher<Intent> importLauncher;
    private org.levimc.launcher.ui.dialogs.LoadingDialog progressDialog;
    private final ExecutorService countExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong countGeneration = new AtomicLong();

    private void showProgressDialog(String message) {
        if (progressDialog == null) {
            progressDialog = new org.levimc.launcher.ui.dialogs.LoadingDialog(this);
        }
        progressDialog.show();
        progressDialog.setMessage(message);
    }

    private void hideProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityContentManagementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DynamicAnim.applyPressScaleRecursively(binding.getRoot());

        setupActivityResultLaunchers();
        initializeManagers();
        setupUI();
        loadCurrentVersion();
    }

    private void setupActivityResultLaunchers() {
        importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    List<Uri> uris = new ArrayList<>();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            uris.add(data.getClipData().getItemAt(i).getUri());
                        }
                    } else if (data.getData() != null) {
                        uris.add(data.getData());
                    }
                    if (!uris.isEmpty()) {
                        handleImport(uris);
                    }
                }
            }
        );
    }

    private void initializeManagers() {
        contentManager = ContentManager.getInstance(this);
        contentImporter = new ContentImporter(this);
        versionManager = VersionManager.get(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadStorageType();
    }

    private void loadStorageType() {
        String savedType = prefs.getString(KEY_STORAGE_TYPE, "INTERNAL");
        currentStorageType = parseStorageType(savedType);
        normalizeCurrentStorageType();
    }

    private void saveStorageType() {
        prefs.edit().putString(KEY_STORAGE_TYPE, currentStorageType.name()).apply();
    }

    private void setupUI() {
        binding.editOptionsButton.setOnClickListener(v -> openOptionsEditor());
        binding.importContentButton.setOnClickListener(v -> startImport());
        binding.refreshButton.setOnClickListener(v -> {
            updateStorageDirectories();
            contentManager.refreshContent();
        });

        setupStorageSelector();
        setupCategoryButtons();
        contentManager.getCountsLiveData().observe(this, counts -> {
            if (counts == null) return;
            binding.worldsCount.setText(String.valueOf(counts.worlds));
            binding.resourcePacksCount.setText(String.valueOf(counts.resourcePacks));
            binding.skinPacksCount.setText(String.valueOf(counts.skinPacks));
        });
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        importLauncher.launch(Intent.createChooser(intent, getString(R.string.import_content_title)));
    }

    private void handleImport(List<Uri> uris) {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) {
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }

        File worldsDir = getWorldsDirectory();
        File resourcePacksDir = getPackDirectory("resource_packs");
        File behaviorPacksDir = getPackDirectory("behavior_packs");
        File skinPacksDir = getPackDirectory("skin_packs");

        showProgressDialog(getString(R.string.importing_content));

        contentImporter.importContent(uris, resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir,
            new ContentImporter.ImportCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        hideProgressDialog();
                        Toast.makeText(ContentManagementActivity.this, message, Toast.LENGTH_SHORT).show();
                        contentManager.refreshContent();
                        updateStorageDirectories();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        hideProgressDialog();
                        Toast.makeText(ContentManagementActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onProgress(int progress) {
                }
            });
    }

    private File getPackDirectory(String packType) {
        File gameDataDir = getGameDataDirForType(currentStorageType);
        return gameDataDir == null ? null : new File(gameDataDir, packType);
    }

    private static final FeatureSettings.StorageType[] STORAGE_CHOICES = {
            FeatureSettings.StorageType.INTERNAL,
            FeatureSettings.StorageType.EXTERNAL,
            FeatureSettings.StorageType.VERSION_ISOLATION_INTERNAL,
            FeatureSettings.StorageType.VERSION_ISOLATION_EXTERNAL
    };

    private void setupStorageSelector() {
        updateStorageLabel();
        binding.storageValue.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, binding.storageValue);
            for (int i = 0; i < STORAGE_CHOICES.length; i++) {
                menu.getMenu().add(Menu.NONE, i, i, storageLabel(STORAGE_CHOICES[i]));
            }
            menu.setOnMenuItemClickListener(item -> {
                FeatureSettings.StorageType newType = STORAGE_CHOICES[item.getItemId()];
                if (newType != currentStorageType) {
                    currentStorageType = newType;
                    saveStorageType();
                    updateStorageLabel();
                    updateStorageDirectories();
                }
                return true;
            });
            menu.show();
        });
    }

    private void updateStorageLabel() {
        binding.storageValue.setText(storageLabel(currentStorageType));
    }

    private String storageLabel(FeatureSettings.StorageType type) {
        return switch (type) {
            case INTERNAL -> getString(R.string.storage_internal);
            case EXTERNAL -> getString(R.string.storage_external);
            case VERSION_ISOLATION_INTERNAL -> getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_internal) + ")";
            case VERSION_ISOLATION, VERSION_ISOLATION_EXTERNAL -> getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_external) + ")";
        };
    }

    private void setupCategoryButtons() {
        UiKit.tintIconChip(binding.worldsChip, binding.worldsIcon, UiKit.BLUE);
        UiKit.tintIconChip(binding.resourcePacksChip, binding.resourcePacksIcon, UiKit.PURPLE);
        UiKit.tintIconChip(binding.behaviorPacksChip, binding.behaviorPacksIcon, UiKit.ORANGE);
        UiKit.tintIconChip(binding.skinPacksChip, binding.skinPacksIcon, UiKit.PINK);
        UiKit.tintIconChip(binding.serversChip, binding.serversIcon, UiKit.INDIGO);
        UiKit.tintIconChip(binding.screenshotsChip, binding.screenshotsIcon, UiKit.TEAL);
        binding.worldsButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_WORLDS));
        binding.skinPacksButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_SKIN_PACKS));
        binding.resourcePacksButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_RESOURCE_PACKS));
        binding.behaviorPacksButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_BEHAVIOR_PACKS));
        binding.screenshotsButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_SCREENSHOTS));
        binding.serversButton.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_SERVERS));
    }

    private void openContentList(int contentType) {
        Intent intent = new Intent(this, ContentListActivity.class);
        intent.putExtra(ContentListActivity.EXTRA_CONTENT_TYPE, contentType);
        intent.putExtra(ContentListActivity.EXTRA_CURRENT_STORAGE_TYPE, currentStorageType.name());
        
        if (contentType == ContentListActivity.TYPE_WORLDS) {
            File worldsDir = getWorldsDirectory();
            if (worldsDir != null) {
                intent.putExtra(ContentListActivity.EXTRA_WORLDS_DIRECTORY, worldsDir.getAbsolutePath());
            }
        }
        
        startActivity(intent);
    }

    private File getWorldsDirectory() {
        File gameDataDir = getGameDataDirForType(currentStorageType);
        return gameDataDir == null ? null : new File(gameDataDir, "minecraftWorlds");
    }

    private void loadCurrentVersion() {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion != null) {
            binding.instanceText.setText(instanceName(currentVersion));
            binding.versionText.setText(currentVersion.versionCode != null && !currentVersion.versionCode.isEmpty()
                    ? currentVersion.versionCode : "\u2014");
            updateStorageDirectories();
        } else {
            binding.instanceText.setText(getString(R.string.not_found_version));
            binding.versionText.setText("\u2014");
            Toast.makeText(this, R.string.dialog_title_no_version, Toast.LENGTH_SHORT).show();
        }
    }

    /** Instance name without the " (version)" suffix that display names carry. */
    static String instanceName(GameVersion version) {
        String name = version.displayName != null && !version.displayName.isEmpty()
                ? version.displayName : version.directoryName;
        if (name == null) return "";
        String suffix = version.versionCode != null ? " (" + version.versionCode + ")" : null;
        if (suffix != null && name.endsWith(suffix) && name.length() > suffix.length()) {
            name = name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    private void updateStorageDirectories() {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return;

        File worldsDir;
        File resourcePacksDir;
        File behaviorPacksDir;
        File skinPacksDir;
        File screenshotsDir;
        File minecraftPeDir;

        File gameDataDir = getGameDataDirForType(currentStorageType);
        if (gameDataDir == null) {
                worldsDir = null;
                resourcePacksDir = null;
                behaviorPacksDir = null;
                skinPacksDir = null;
                screenshotsDir = null;
                minecraftPeDir = null;
        } else {
                worldsDir = new File(gameDataDir, "minecraftWorlds");
                resourcePacksDir = new File(gameDataDir, "resource_packs");
                behaviorPacksDir = new File(gameDataDir, "behavior_packs");
                skinPacksDir = new File(gameDataDir, "skin_packs");
                screenshotsDir = new File(gameDataDir, "Screenshots");
                minecraftPeDir = new File(gameDataDir, "minecraftpe");
        }

        contentManager.configureStorageDirectories(worldsDir, resourcePacksDir, behaviorPacksDir, skinPacksDir, screenshotsDir, minecraftPeDir);
        contentManager.refreshCounts();
        refreshExtraCounts(behaviorPacksDir, screenshotsDir, minecraftPeDir);
    }

    /** Behavior packs, screenshots and servers are not in ContentManager's counts; count them off the UI thread. */
    private void refreshExtraCounts(File behaviorPacksDir, File screenshotsDir, File minecraftPeDir) {
        long generation = countGeneration.incrementAndGet();
        if (countExecutor.isShutdown()) return;
        android.content.Context appContext = getApplicationContext();
        countExecutor.execute(() -> {
            int behaviors = 0;
            int screenshots = 0;
            int servers = 0;
            try {
                ResourcePackManager packs = new ResourcePackManager(appContext);
                packs.setPackDirectories(null, behaviorPacksDir, null);
                behaviors = packs.getBehaviorPacks().size();
            } catch (Exception ignored) {
            }
            try {
                ScreenshotManager shots = new ScreenshotManager();
                shots.setScreenshotsDirectory(screenshotsDir);
                screenshots = shots.getScreenshots().size();
            } catch (Exception ignored) {
            }
            try {
                ServerManager serverManager = new ServerManager();
                serverManager.setMinecraftPeDirectory(minecraftPeDir);
                servers = serverManager.getServers().size();
            } catch (Exception ignored) {
            }
            int behaviorCount = behaviors;
            int screenshotCount = screenshots;
            int serverCount = servers;
            mainHandler.post(() -> {
                if (generation != countGeneration.get() || isFinishing() || isDestroyed()) return;
                binding.behaviorPacksCount.setText(String.valueOf(behaviorCount));
                binding.screenshotsCount.setText(String.valueOf(screenshotCount));
                binding.serversCount.setText(String.valueOf(serverCount));
            });
        });
    }

    private void openOptionsEditor() {
        File optionsFile = getOptionsFile();
        if (optionsFile == null) {
            Toast.makeText(this, R.string.options_file_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        String storageLabel = storageLabel(currentStorageType);

        Intent intent = new Intent(this, OptionsEditorActivity.class);
        intent.putExtra(OptionsEditorActivity.EXTRA_OPTIONS_PATH, optionsFile.getAbsolutePath());
        intent.putExtra(OptionsEditorActivity.EXTRA_STORAGE_TYPE, storageLabel);
        startActivity(intent);
    }

    private File getOptionsFile() {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return null;

        String profileId = currentVersion.getStorageProfileId();
        boolean versionIsolation = currentVersion.versionIsolation;
        File internalGameDataDir = LauncherStorage.getStorageGameDataDir(
                this, profileId, versionIsolation, false);
        File internalOptionsFile = new File(internalGameDataDir, "minecraftpe/options.txt");
        if (internalOptionsFile.isFile()) return internalOptionsFile;

        File externalGameDataDir = LauncherStorage.getStorageGameDataDir(
                this, profileId, versionIsolation, true);
        File externalOptionsFile = new File(externalGameDataDir, "minecraftpe/options.txt");
        return externalOptionsFile.isFile() ? externalOptionsFile : internalOptionsFile;
    }

    private File getGameDataDirForType(FeatureSettings.StorageType storageType) {
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return null;
        FeatureSettings.StorageType resolvedType = LauncherStorage.normalizeContentStorageType(
                storageType,
                currentVersion.versionIsolation
        );
        return LauncherStorage.getContentGameDataDir(this, currentVersion.getStorageProfileId(), resolvedType);
    }

    private void normalizeCurrentStorageType() {
        GameVersion currentVersion = versionManager != null ? versionManager.getSelectedVersion() : null;
        if (currentVersion == null) return;
        currentStorageType = LauncherStorage.normalizeContentStorageType(
                currentStorageType,
                currentVersion.versionIsolation
        );
    }

    private FeatureSettings.StorageType parseStorageType(String value) {
        try {
            return FeatureSettings.StorageType.valueOf(value);
        } catch (Exception ignored) {
            return FeatureSettings.StorageType.INTERNAL;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStorageDirectories();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        countExecutor.shutdownNow();
        if (contentImporter != null) {
            contentImporter.shutdown();
        }
    }
}
