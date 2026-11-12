package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentImporter;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.core.content.ScreenshotManager;
import org.levimc.launcher.core.content.ServerManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityContentManagementBinding;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.ui.widget.LauncherPopup;
import org.levimc.launcher.ui.widget.ContentStorageSelector;
import org.levimc.launcher.core.minecraft.MinecraftImportIntents;
import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.util.StorageAccess;
import org.levimc.launcher.util.LauncherStorage;

import java.io.File;
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
    private GameVersion importTarget;
    private FeatureSettings.StorageType importStorageType;
    private boolean importWithMinecraft;
    private org.levimc.launcher.ui.dialogs.LoadingDialog progressDialog;
    private final ExecutorService minecraftImportExecutor = Executors.newSingleThreadExecutor();
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



        setupActivityResultLaunchers();
        initializeManagers();
        setupUI();
        if (savedInstanceState != null) {
            importTarget = savedInstanceState.getParcelable("content_import_target");
            importStorageType = parseStorageType(savedInstanceState.getString("content_import_storage", "INTERNAL"));
            importWithMinecraft = savedInstanceState.getBoolean("content_import_minecraft", false);
        }
    }

    private void setupActivityResultLaunchers() {
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    GameVersion target = importTarget;
                    FeatureSettings.StorageType storage = importStorageType;
                    boolean useMinecraft = importWithMinecraft;
                    importTarget = null;
                    if (result.getResultCode() != RESULT_OK || result.getData() == null || target == null) return;
                    Intent data = result.getData();
                    List<Uri> uris = StorageAccess.selectedUris(data);
                    for (Uri uri : uris) StorageAccess.retainReadPermission(this, data, uri);
                    if (uris.isEmpty()) return;
                    if (useMinecraft) {
                        if (uris.size() != 1) {
                            Toast.makeText(this, R.string.content_import_single_file, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        launchMinecraftImport(uris.get(0), target);
                    } else {
                        handleImport(uris, target, storage);
                    }
                });
    }

    private void launchMinecraftImport(Uri source, GameVersion target) {
        showProgressDialog(getString(R.string.content_import_preparing));
        minecraftImportExecutor.execute(() -> {
            try {
                Intent gameIntent = MinecraftImportIntents.prepareContentImport(this, source);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    hideProgressDialog();
                    if (!MinecraftImportIntents.forwardToRunningMinecraft(this, gameIntent)) {
                        new MinecraftLauncher(this).launch(gameIntent, target);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    hideProgressDialog();
                    Toast.makeText(this, getString(R.string.content_import_failed, error.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
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
        UiKit.applyPrimary(binding.importContentButton);
        binding.importContentButton.setOnClickListener(v -> showImportMethods());

        setupStorageSelector();
        setupCategoryButtons();
        contentManager.getCountsLiveData().observe(this, counts -> {
            if (counts == null) return;
            binding.worldsCount.setText(getString(R.string.content_category_count, getString(R.string.worlds_category), counts.worlds));
            binding.resourcePacksCount.setText(getString(R.string.content_category_count, getString(R.string.resource_packs_category), counts.resourcePacks));
            binding.skinPacksCount.setText(getString(R.string.content_category_count, getString(R.string.skin_packs_category), counts.skinPacks));
            binding.behaviorPacksCount.setText(getString(R.string.content_category_count, getString(R.string.behavior_packs_category), counts.behaviorPacks));
        });
    }

    private void showImportMethods() {
        new LauncherPopup(binding.importContentButton).width(360)
                .section(getString(R.string.import_content))
                .action(1, getString(R.string.content_import_here), getString(R.string.content_import_here_hint), R.drawable.ic_qa_import)
                .action(2, getString(R.string.content_import_minecraft), getString(R.string.content_import_minecraft_hint), R.drawable.ic_nav_launch)
                .onSelect(id -> startImport(id == 2)).show();
    }

    private void startImport(boolean useMinecraft) {
        importTarget = versionManager.getSelectedVersion();
        if (importTarget == null) {
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }
        importStorageType = LauncherStorage.normalizeContentStorageType(currentStorageType, importTarget.versionIsolation);
        importWithMinecraft = useMinecraft;
        importLauncher.launch(StorageAccess.downloadsPicker(!useMinecraft));
    }

    private void handleImport(List<Uri> uris, GameVersion currentVersion, FeatureSettings.StorageType storageType) {
        if (currentVersion == null) {
            Toast.makeText(this, R.string.not_found_version, Toast.LENGTH_SHORT).show();
            return;
        }

        FeatureSettings.StorageType resolved = LauncherStorage.normalizeContentStorageType(storageType, currentVersion.versionIsolation);
        File root = LauncherStorage.getContentGameDataDir(this, currentVersion.getStorageProfileId(), resolved);
        File worldsDir = new File(root, "minecraftWorlds");
        File resourcePacksDir = new File(root, "resource_packs");
        File behaviorPacksDir = new File(root, "behavior_packs");
        File skinPacksDir = new File(root, "skin_packs");

        showProgressDialog(getString(R.string.importing_content));

        contentImporter.importContent(uris, resourcePacksDir, behaviorPacksDir, skinPacksDir, worldsDir,
            new ContentImporter.ImportCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            contentManager.refreshContent();
                            return;
                        }
                        hideProgressDialog();
                        Toast.makeText(ContentManagementActivity.this, message, Toast.LENGTH_SHORT).show();
                        updateStorageDirectories();
                        contentManager.refreshContent();
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        hideProgressDialog();
                        Toast.makeText(ContentManagementActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onProgress(int progress) {
                }
            });
    }

    private void setupStorageSelector() {
        updateStorageLabel();
        binding.storageValue.setOnClickListener(v -> ContentStorageSelector.show(
                binding.storageValue, versionManager.getSelectedVersion(), currentStorageType, type -> {
                    currentStorageType = type;
                    saveStorageType();
                    updateStorageLabel();
                    updateStorageDirectories();
                }));
    }

    private void updateStorageLabel() {
        String label = storageLabel(currentStorageType);
        binding.storageValue.setText(label);
        binding.storageValue.setContentDescription(getString(R.string.content_ui_storage_location) + ": " + label);
    }

    private String storageLabel(FeatureSettings.StorageType type) {
        return ContentStorageSelector.label(this, type);
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
            updateStorageDirectories();
        } else {
            binding.instanceText.setText(getString(R.string.not_found_version));
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
        refreshExtraCounts(screenshotsDir, minecraftPeDir);
    }

    private void refreshExtraCounts(File screenshotsDir, File minecraftPeDir) {
        long generation = countGeneration.incrementAndGet();
        if (countExecutor.isShutdown()) return;
        countExecutor.execute(() -> {
            int screenshots = 0;
            int servers = 0;
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
            int screenshotCount = screenshots;
            int serverCount = servers;
            mainHandler.post(() -> {
                if (generation != countGeneration.get() || isFinishing() || isDestroyed()) return;
                binding.screenshotsCount.setText(getString(R.string.content_category_count, getString(R.string.content_ui_screenshots), screenshotCount));
                binding.serversCount.setText(getString(R.string.content_category_count, getString(R.string.servers_category), serverCount));
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
        loadStorageType();
        updateStorageLabel();
        loadCurrentVersion();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("content_import_target", importTarget);
        if (importStorageType != null) outState.putString("content_import_storage", importStorageType.name());
        outState.putBoolean("content_import_minecraft", importWithMinecraft);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        countGeneration.incrementAndGet();
        countExecutor.shutdownNow();
        minecraftImportExecutor.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        hideProgressDialog();
        if (contentImporter != null) {
            contentImporter.shutdown();
        }
    }
}
