package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityOptionsCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.levimc.launcher.R;
import org.levimc.launcher.core.content.ContentManager;
import org.levimc.launcher.core.content.ResourcePackItem;
import org.levimc.launcher.core.content.ResourcePackManager;
import org.levimc.launcher.core.content.ServerItem;
import org.levimc.launcher.core.content.StructureExtractor;
import org.levimc.launcher.core.content.WorldItem;
import org.levimc.launcher.core.content.WorldManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityContentListBinding;
import org.levimc.launcher.settings.FeatureSettings;
import org.levimc.launcher.ui.adapter.ResourcePacksAdapter;
import org.levimc.launcher.ui.adapter.StructuresAdapter;
import org.levimc.launcher.ui.adapter.WorldsAdapter;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.LauncherStorage;

import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.OutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ContentListActivity extends BaseActivity {

    public static final String EXTRA_CONTENT_TYPE = "content_type";
    public static final String EXTRA_WORLDS_DIRECTORY = "worlds_directory";
    public static final String EXTRA_CURRENT_STORAGE_TYPE = "current_storage_type";
    public static final int TYPE_WORLDS = 0;
    public static final int TYPE_SKIN_PACKS = 1;
    public static final int TYPE_RESOURCE_PACKS = 2;
    public static final int TYPE_BEHAVIOR_PACKS = 3;
    public static final int TYPE_SCREENSHOTS = 4;
    public static final int TYPE_SERVERS = 5;
    private static final String STATE_SELECTION_MODE = "selection_mode";
    private static final String STATE_SELECTED_PATHS = "selected_paths";

    private ActivityContentListBinding binding;
    private ContentManager contentManager;
    private VersionManager versionManager;
    private int contentType;
    private File worldsDirectory;
    private FeatureSettings.StorageType currentStorageType;

    private WorldsAdapter worldsAdapter;
    private ResourcePacksAdapter packsAdapter;
    private org.levimc.launcher.ui.adapter.ScreenshotsAdapter screenshotsAdapter;
    private org.levimc.launcher.ui.adapter.ServersAdapter serversAdapter;

    private ActivityResultLauncher<Intent> screenshotSaveLauncher;
    private String pendingScreenshotPath;
    private ActivityResultLauncher<Intent> exportLauncher;
    private ActivityResultLauncher<Intent> exportPackLauncher;
    private ActivityResultLauncher<Intent> customFlatWorldLauncher;
    private ActivityResultLauncher<Intent> structureExportLauncher;
    private ActivityResultLauncher<Intent> batchExportFolderLauncher;
    private WorldItem pendingExportWorld;
    private ResourcePackItem pendingExportPack;
    private WorldItem pendingStructureExportWorld;
    private StructureExtractor.StructureInfo pendingStructureInfo;
    private StructureExtractor structureExtractor;
    private List<WorldItem> pendingBatchWorlds = new ArrayList<>();
    private List<ResourcePackItem> pendingBatchPacks = new ArrayList<>();
    private boolean skipInitialResumeRefresh = true;

    private List<WorldItem> allWorlds = new ArrayList<>();
    private List<ResourcePackItem> allPacks = new ArrayList<>();
    private List<ServerItem> allServers = new ArrayList<>();
    
    private org.levimc.launcher.ui.dialogs.LoadingDialog progressDialog;

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
        binding = ActivityContentListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DynamicAnim.applyPressScaleRecursively(binding.getRoot());

        contentType = getIntent().getIntExtra(EXTRA_CONTENT_TYPE, TYPE_WORLDS);
        contentManager = ContentManager.getInstance(this);
        versionManager = VersionManager.get(this);
        
        String storageTypeStr = getIntent().getStringExtra(EXTRA_CURRENT_STORAGE_TYPE);
        if (storageTypeStr != null) {
            currentStorageType = parseStorageType(storageTypeStr);
        } else {
            SharedPreferences prefs = getSharedPreferences("content_management", MODE_PRIVATE);
            String savedType = prefs.getString("storage_type", "INTERNAL");
            currentStorageType = parseStorageType(savedType);
        }

        if (savedInstanceState != null) pendingScreenshotPath = savedInstanceState.getString("pending_screenshot_path");
        setupActivityResultLaunchers();
        setupUI();
        restoreSelectionState(savedInstanceState);
        setupObservers();
        loadContent();
    }

    private void setupActivityResultLaunchers() {
        screenshotSaveLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    String path = pendingScreenshotPath;
                    pendingScreenshotPath = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && path != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) saveScreenshotImage(new File(path), uri, false);
                    }
                });
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingExportWorld != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportWorld(pendingExportWorld, uri);
                    }
                }
                pendingExportWorld = null;
            }
        );

        exportPackLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingExportPack != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportPack(pendingExportPack, uri);
                    }
                }
                pendingExportPack = null;
            }
        );

        customFlatWorldLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
            }
        );

        structureExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingStructureExportWorld != null && pendingStructureInfo != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportStructureToFile(pendingStructureExportWorld, pendingStructureInfo, uri);
                    }
                }
                pendingStructureExportWorld = null;
                pendingStructureInfo = null;
            }
        );

        batchExportFolderLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        int flags = result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        try {
                            getContentResolver().takePersistableUriPermission(treeUri, flags);
                        } catch (Exception ignored) {
                        }
                        if (!pendingBatchWorlds.isEmpty()) {
                            List<WorldItem> items = new ArrayList<>(pendingBatchWorlds);
                            pendingBatchWorlds.clear();
                            new Thread(() -> exportWorldBatch(treeUri, items, 0, 0, 0, new HashSet<>())).start();
                        } else if (!pendingBatchPacks.isEmpty()) {
                            List<ResourcePackItem> items = new ArrayList<>(pendingBatchPacks);
                            pendingBatchPacks.clear();
                            new Thread(() -> exportPackBatch(treeUri, items, 0, 0, 0, new HashSet<>())).start();
                        }
                    }
                } else {
                    pendingBatchWorlds.clear();
                    pendingBatchPacks.clear();
                }
            }
        );

        structureExtractor = new StructureExtractor(this);
    }

    private void setupUI() {
        String worldsPath = getIntent().getStringExtra(EXTRA_WORLDS_DIRECTORY);
        if (worldsPath != null) {
            worldsDirectory = new File(worldsPath);
        } else {
            worldsDirectory = getWorldsDirectoryForType(currentStorageType);
        }

        configureContentDirectories();

        switch (contentType) {
            case TYPE_WORLDS:
                binding.titleText.setText(getString(R.string.worlds_title));
                binding.customFlatButton.setVisibility(View.VISIBLE);
                binding.selectButton.setVisibility(View.VISIBLE);
                setupWorldsRecyclerView();
                break;
            case TYPE_SKIN_PACKS:
                binding.titleText.setText(getString(R.string.skin_packs_title));
                binding.selectButton.setVisibility(View.VISIBLE);
                setupPacksRecyclerView();
                break;
            case TYPE_RESOURCE_PACKS:
                binding.titleText.setText(getString(R.string.resource_packs_title));
                binding.selectButton.setVisibility(View.VISIBLE);
                setupPacksRecyclerView();
                break;
            case TYPE_BEHAVIOR_PACKS:
                binding.titleText.setText(getString(R.string.behavior_packs_title));
                binding.selectButton.setVisibility(View.VISIBLE);
                setupPacksRecyclerView();
                break;
            case TYPE_SCREENSHOTS:
                binding.titleText.setText(getString(R.string.screenshots_category));
                binding.searchEditText.setVisibility(View.GONE);
                setupScreenshotsRecyclerView();
                break;
            case TYPE_SERVERS:
                binding.titleText.setText(getString(R.string.servers_category));
                binding.customFlatButton.setText(getString(R.string.quick_launch_add_server));
                binding.customFlatButton.setVisibility(View.VISIBLE);
                setupServersRecyclerView();
                break;
        }

        binding.customFlatButton.setOnClickListener(v -> {
            if (contentType == TYPE_SERVERS) showAddServerDialog();
            else openCustomFlatWorld();
        });
        binding.selectButton.setOnClickListener(v -> enterSelectionMode());
        binding.selectAllButton.setOnClickListener(v -> selectAllVisible());
        binding.batchExportButton.setOnClickListener(v -> startBatchExport());
        binding.batchTransferButton.setOnClickListener(v -> showBatchTransferDialog());
        binding.batchDeleteButton.setOnClickListener(v -> showBatchDeleteDialog());

        setupSearchFilter();
        updateSelectionToolbar();
    }

    private void setupSearchFilter() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterContent(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void filterContent(String query) {
        String rawQuery = query == null ? "" : query.trim();
        String lowerQuery = rawQuery.toLowerCase();

        if (contentType == TYPE_WORLDS && worldsAdapter != null) {
            List<WorldItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allWorlds) : allWorlds.stream()
                    .filter(world -> world.getWorldName() != null && world.getWorldName().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
            worldsAdapter.updateWorlds(filtered);
            updateListState(filtered.size(), allWorlds.size(), rawQuery);
        } else if (contentType == TYPE_SERVERS && serversAdapter != null) {
            List<ServerItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allServers) : allServers.stream()
                    .filter(server -> (server.name != null && server.name.toLowerCase().contains(lowerQuery)) ||
                            (server.ip != null && server.ip.toLowerCase().contains(lowerQuery)))
                    .collect(Collectors.toList());
            serversAdapter.updateData(filtered);
            updateListState(filtered.size(), allServers.size(), rawQuery);
        } else if (isPackType() && packsAdapter != null) {
            List<ResourcePackItem> filtered = lowerQuery.isEmpty() ? new ArrayList<>(allPacks) : allPacks.stream()
                    .filter(pack -> pack.getPackName() != null && pack.getPackName().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
            packsAdapter.updateResourcePacks(filtered);
            updateListState(filtered.size(), allPacks.size(), rawQuery);
        }
    }

    private void setupWorldsRecyclerView() {
        worldsAdapter = new WorldsAdapter();
        worldsAdapter.setOnWorldActionListener(new WorldsAdapter.OnWorldActionListener() {
            @Override
            public void onWorldExport(WorldItem world) {
                startWorldExport(world);
            }

            @Override
            public void onWorldDelete(WorldItem world) {
                showDeleteWorldDialog(world);
            }

            @Override
            public void onWorldBackup(WorldItem world) {
                backupWorld(world);
            }

            @Override
            public void onWorldEdit(WorldItem world) {
                openWorldEditor(world);
            }

            @Override
            public void onWorldExtractStructures(WorldItem world) {
                showExtractStructuresDialog(world);
            }

            @Override
            public void onWorldTransfer(WorldItem world) {
                showTransferWorldDialog(world);
            }
        });
        worldsAdapter.setOnSelectionChangedListener(count -> updateSelectionToolbar());

        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(worldsAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void openWorldEditor(WorldItem world) {
        File worldFile = world.getFile();
        if (worldFile == null || !worldFile.exists()) {
            Toast.makeText(this, R.string.world_directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        
        Intent intent = new Intent(this, WorldEditorActivity.class);
        intent.putExtra(WorldEditorActivity.EXTRA_WORLD_PATH, worldFile.getAbsolutePath());
        intent.putExtra(WorldEditorActivity.EXTRA_WORLD_NAME, world.getWorldName());
        startActivity(intent);
    }

    private void setupPacksRecyclerView() {
        packsAdapter = new ResourcePacksAdapter();
        packsAdapter.setOnResourcePackActionListener(new ResourcePacksAdapter.OnResourcePackActionListener() {
            @Override
            public void onResourcePackDelete(ResourcePackItem pack) {
                showDeletePackDialog(pack);
            }

            @Override
            public void onResourcePackTransfer(ResourcePackItem pack) {
                showTransferPackDialog(pack);
            }

            @Override
            public void onResourcePackExport(ResourcePackItem pack) {
                startPackExport(pack);
            }
        });
        packsAdapter.setOnSelectionChangedListener(count -> updateSelectionToolbar());

        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(packsAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupScreenshotsRecyclerView() {
        screenshotsAdapter = new org.levimc.launcher.ui.adapter.ScreenshotsAdapter(new ArrayList(), new org.levimc.launcher.ui.adapter.ScreenshotsAdapter.OnScreenshotClickListener() {
            @Override
            public void onDeleteClick(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
                showDeleteScreenshotDialog(screenshot);
            }

            @Override
            public void onSaveClick(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
                saveScreenshotToGallery(screenshot);
            }
        });
        binding.contentRecyclerView.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
        binding.contentRecyclerView.setAdapter(screenshotsAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupServersRecyclerView() {
        serversAdapter = new org.levimc.launcher.ui.adapter.ServersAdapter(new ArrayList<>(), server -> showDeleteServerDialog(server));
        binding.contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.contentRecyclerView.setAdapter(serversAdapter);
        binding.contentRecyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(binding.contentRecyclerView));
    }

    private void setupObservers() {
        switch (contentType) {
            case TYPE_WORLDS:
                contentManager.getWorldsLiveData().observe(this, worlds -> {
                    allWorlds = worlds != null ? worlds : new ArrayList<>();
                    if (worldsAdapter != null) {
                        worldsAdapter.retainSelections(allWorlds);
                        filterContent(binding.searchEditText.getText().toString());
                    }
                    showLoading(false);
                });
                break;
            case TYPE_SKIN_PACKS:
                contentManager.getSkinPacksLiveData().observe(this, packs -> updatePacksFromObserver(packs));
                break;
            case TYPE_RESOURCE_PACKS:
                contentManager.getResourcePacksLiveData().observe(this, packs -> updatePacksFromObserver(packs));
                break;
            case TYPE_BEHAVIOR_PACKS:
                contentManager.getBehaviorPacksLiveData().observe(this, packs -> updatePacksFromObserver(packs));
                break;
            case TYPE_SCREENSHOTS:
                contentManager.getScreenshotsLiveData().observe(this, screenshots -> {
                    List<org.levimc.launcher.core.content.ScreenshotItem> items = screenshots != null ? screenshots : new ArrayList<>();
                    if (screenshotsAdapter != null) screenshotsAdapter.updateData(items);
                    updateListState(items.size(), items.size(), "");
                    showLoading(false);
                });
                break;
            case TYPE_SERVERS:
                contentManager.getServersLiveData().observe(this, servers -> {
                    allServers = servers != null ? servers : new ArrayList<>();
                    if (serversAdapter != null) filterContent(binding.searchEditText.getText().toString());
                    showLoading(false);
                });
                break;
        }
    }

    private void updatePacksFromObserver(List<ResourcePackItem> packs) {
        allPacks = packs != null ? packs : new ArrayList<>();
        if (packsAdapter != null) {
            packsAdapter.retainSelections(allPacks);
            filterContent(binding.searchEditText.getText().toString());
        }
        showLoading(false);
    }

    private void loadContent() {
        showLoading(true);
        switch (contentType) {
            case TYPE_WORLDS:
                contentManager.refreshWorlds();
                break;
            case TYPE_SKIN_PACKS:
                contentManager.refreshSkinPacks();
                break;
            case TYPE_RESOURCE_PACKS:
                contentManager.refreshResourcePacks();
                break;
            case TYPE_BEHAVIOR_PACKS:
                contentManager.refreshBehaviorPacks();
                break;
            case TYPE_SCREENSHOTS:
                contentManager.refreshScreenshots();
                break;
            case TYPE_SERVERS:
                contentManager.refreshServers();
                break;
        }
    }

    private void showLoading(boolean show) {
        binding.loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startWorldExport(WorldItem world) {
        pendingExportWorld = world;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, world.getName() + ".mcworld");
        exportLauncher.launch(intent);
    }

    private void exportWorld(WorldItem world, Uri uri) {
        showProgressDialog(getString(R.string.exporting_world));
        contentManager.exportWorld(world, uri, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void startPackExport(ResourcePackItem pack) {
        pendingExportPack = pack;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, pack.getPackName() + ".mcpack");
        exportPackLauncher.launch(intent);
    }

    private void exportPack(ResourcePackItem pack, Uri uri) {
        showProgressDialog(getString(R.string.exporting_pack));
        contentManager.exportResourcePack(pack, uri, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void backupWorld(WorldItem world) {
        showProgressDialog(getString(R.string.backing_up_world));
        contentManager.backupWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void showDeleteWorldDialog(WorldItem world) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete_world))
            .setMessage(getString(R.string.confirm_delete_world))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteWorld(world))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void deleteWorld(WorldItem world) {
        showProgressDialog(getString(R.string.deleting_world));
        contentManager.deleteWorld(world, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void showDeletePackDialog(ResourcePackItem pack) {
        int titleResId;
        int messageResId;

        if (contentType == TYPE_BEHAVIOR_PACKS) {
            titleResId = R.string.delete_behavior_pack;
            messageResId = R.string.confirm_delete_behavior_pack;
        } else if (contentType == TYPE_SKIN_PACKS) {
            titleResId = R.string.delete_skin_pack;
            messageResId = R.string.confirm_delete_skin_pack;
        } else {
            titleResId = R.string.delete_resource_pack;
            messageResId = R.string.confirm_delete_resource_pack;
        }

        new CustomAlertDialog(this)
            .setTitleText(getString(titleResId))
            .setMessage(getString(messageResId))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deletePack(pack))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void deletePack(ResourcePackItem pack) {
        showProgressDialog(getString(R.string.deleting_pack));
        contentManager.deleteResourcePack(pack, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void saveScreenshotToGallery(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pendingScreenshotPath = screenshot.file.getAbsolutePath();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/jpeg");
            intent.putExtra(Intent.EXTRA_TITLE, screenshot.name + ".jpg");
            screenshotSaveLauncher.launch(intent);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, screenshot.name + "_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        try {
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("Cannot save screenshot");
            saveScreenshotImage(screenshot.file, uri, true);
        } catch (Exception error) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveScreenshotImage(File source, Uri uri, boolean gallery) {
        showLoading(true);
        new Thread(() -> {
            boolean saved = false;
            Bitmap bitmap = null;
            try {
                bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
                if (bitmap == null) throw new java.io.IOException("Cannot read screenshot");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                        throw new java.io.IOException("Cannot write screenshot");
                    }
                }
                if (gallery) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                }
                saved = true;
            } catch (Exception error) {
                if (gallery) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                if (bitmap != null) bitmap.recycle();
            }
            boolean success = saved;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showLoading(false);
                Toast.makeText(this, success ? (gallery ? R.string.saved_to_gallery : R.string.screenshot_saved)
                        : R.string.save_failed, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void showDeleteScreenshotDialog(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete))
            .setMessage(getString(R.string.delete_screenshot_confirm))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteScreenshot(screenshot))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void showAddServerDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_server, null);
        EditText serverNameEdit = dialogView.findViewById(R.id.server_name_edit);
        EditText serverIpEdit = dialogView.findViewById(R.id.server_ip_edit);
        EditText serverPortEdit = dialogView.findViewById(R.id.server_port_edit);
        serverPortEdit.setText("19132");

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.quick_launch_add_server))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.add), v -> {
                    String name = serverNameEdit.getText().toString().trim();
                    String ip = serverIpEdit.getText().toString().trim();
                    String portStr = serverPortEdit.getText().toString().trim();

                    if (TextUtils.isEmpty(name) || TextUtils.isEmpty(ip)) {
                        Toast.makeText(this, R.string.server_details_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int port = 19132;
                    if (!TextUtils.isEmpty(portStr)) {
                        try {
                            port = Integer.parseInt(portStr);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    addServer(new org.levimc.launcher.core.content.ServerItem(name, ip, port));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void addServer(org.levimc.launcher.core.content.ServerItem server) {
        showProgressDialog(getString(R.string.adding_server));
        contentManager.addServer(server, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void deleteScreenshot(org.levimc.launcher.core.content.ScreenshotItem screenshot) {
        showProgressDialog(getString(R.string.deleting_screenshot));
        contentManager.deleteScreenshot(screenshot, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showDeleteServerDialog(org.levimc.launcher.core.content.ServerItem server) {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.delete))
            .setMessage(getString(R.string.delete_server_confirm))
            .setPositiveButton(getString(R.string.dialog_positive_delete), v -> deleteServer(server))
            .setNegativeButton(getString(R.string.cancel), null)
            .show();
    }

    private void deleteServer(org.levimc.launcher.core.content.ServerItem server) {
        showProgressDialog(getString(R.string.deleting_server));
        contentManager.deleteServer(server, new ContentManager.ContentOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openCustomFlatWorld() {
        if (worldsDirectory == null || !worldsDirectory.exists()) {
            Toast.makeText(this, R.string.worlds_directory_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        
        Intent intent = new Intent(this, CustomFlatWorldActivity.class);
        intent.putExtra(CustomFlatWorldActivity.EXTRA_WORLDS_DIRECTORY, worldsDirectory.getAbsolutePath());
        customFlatWorldLauncher.launch(intent, ActivityOptionsCompat.makeCustomAnimation(
                this, R.anim.fade_in, R.anim.fade_out));
    }

    private void showExtractStructuresDialog(WorldItem world) {
        File worldFile = world.getFile();
        if (worldFile == null || !worldFile.exists()) {
            Toast.makeText(this, R.string.world_directory_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.loadingOverlay.setVisibility(View.VISIBLE);

        structureExtractor.loadStructures(worldFile, new StructureExtractor.StructureListCallback() {
            @Override
            public void onComplete(List<StructureExtractor.StructureInfo> structures) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    if (structures.isEmpty()) {
                        showNoStructuresFoundDialog();
                    } else {
                        showStructureSelectionDialog(world, structures);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showNoStructuresFoundDialog() {
        new CustomAlertDialog(this)
            .setTitleText(getString(R.string.no_structures_found_title))
            .setMessage(getString(R.string.no_structures_found_message))
            .setPositiveButton(getString(R.string.dialog_positive_ok), null)
            .show();
    }

    private void showStructureSelectionDialog(WorldItem world, List<StructureExtractor.StructureInfo> structures) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_structure_list, null);
        
        TextView structureCount = dialogView.findViewById(R.id.structure_count);
        RecyclerView recyclerView = dialogView.findViewById(R.id.structures_recycler_view);
        
        structureCount.setText(getString(R.string.structures_found_count, structures.size()));
        
        StructuresAdapter adapter = new StructuresAdapter();
        adapter.setStructures(structures);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.structures_found_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .create();
        
        adapter.setOnStructureExportListener(structure -> {
            dialog.dismiss();
            startStructureExport(world, structure);
        });
        
        dialog.show();

        org.levimc.launcher.util.PersonalizationManager structPm = new org.levimc.launcher.util.PersonalizationManager(this);
        int structAccent = structPm.getAccentColor();
        if (structAccent != 0) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(structAccent);
        }
    }

    private void startStructureExport(WorldItem world, StructureExtractor.StructureInfo structure) {
        pendingStructureExportWorld = world;
        pendingStructureInfo = structure;
        
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, structure.getFileName());
        structureExportLauncher.launch(intent);
    }

    private void exportStructureToFile(WorldItem world, StructureExtractor.StructureInfo structure, Uri uri) {
        binding.loadingOverlay.setVisibility(View.VISIBLE);

        structureExtractor.exportSingleStructure(structure, uri, new StructureExtractor.ExtractionCallback() {

            @Override
            public void onComplete(int extractedCount, String outputPath) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(ContentListActivity.this,
                            getString(R.string.structure_exported, structure.getName()),
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    binding.loadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showTransferWorldDialog(WorldItem world) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_transfer_content, null);
        RadioGroup radioGroup = dialogView.findViewById(R.id.storage_radio_group);
        RadioButton radioInternal = dialogView.findViewById(R.id.radio_internal);
        RadioButton radioExternal = dialogView.findViewById(R.id.radio_external);
        RadioButton radioVersionIsolationInternal = dialogView.findViewById(R.id.radio_version_isolation_internal);
        RadioButton radioVersionIsolation = dialogView.findViewById(R.id.radio_version_isolation);
        radioVersionIsolationInternal.setText(getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_internal) + ")");
        radioVersionIsolation.setText(getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_external) + ")");

        switch (currentStorageType) {
            case INTERNAL -> radioInternal.setEnabled(false);
            case EXTERNAL -> radioExternal.setEnabled(false);
            case VERSION_ISOLATION_INTERNAL -> radioVersionIsolationInternal.setEnabled(false);
            case VERSION_ISOLATION, VERSION_ISOLATION_EXTERNAL -> radioVersionIsolation.setEnabled(false);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transfer_content)
            .setView(dialogView)
            .setPositiveButton(R.string.transfer, (d, which) -> {
                int selectedId = radioGroup.getCheckedRadioButtonId();
                FeatureSettings.StorageType targetType = null;
                
                if (selectedId == R.id.radio_internal) {
                    targetType = FeatureSettings.StorageType.INTERNAL;
                } else if (selectedId == R.id.radio_external) {
                    targetType = FeatureSettings.StorageType.EXTERNAL;
                } else if (selectedId == R.id.radio_version_isolation_internal) {
                    targetType = FeatureSettings.StorageType.VERSION_ISOLATION_INTERNAL;
                } else if (selectedId == R.id.radio_version_isolation) {
                    targetType = FeatureSettings.StorageType.VERSION_ISOLATION_EXTERNAL;
                }

                if (targetType != null && targetType != currentStorageType) {
                    transferWorld(world, targetType);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
        
        org.levimc.launcher.util.PersonalizationManager twPm = new org.levimc.launcher.util.PersonalizationManager(this);
        int twAccent = twPm.getAccentColor();
        if (twAccent != 0) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(twAccent);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(twAccent);
        } else {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.accent_text, getTheme()));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.accent_text, getTheme()));
        }
    }

    private void showTransferPackDialog(ResourcePackItem pack) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_transfer_content, null);
        RadioGroup radioGroup = dialogView.findViewById(R.id.storage_radio_group);
        RadioButton radioInternal = dialogView.findViewById(R.id.radio_internal);
        RadioButton radioExternal = dialogView.findViewById(R.id.radio_external);
        RadioButton radioVersionIsolationInternal = dialogView.findViewById(R.id.radio_version_isolation_internal);
        RadioButton radioVersionIsolation = dialogView.findViewById(R.id.radio_version_isolation);
        radioVersionIsolationInternal.setText(getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_internal) + ")");
        radioVersionIsolation.setText(getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_external) + ")");

        switch (currentStorageType) {
            case INTERNAL -> radioInternal.setEnabled(false);
            case EXTERNAL -> radioExternal.setEnabled(false);
            case VERSION_ISOLATION_INTERNAL -> radioVersionIsolationInternal.setEnabled(false);
            case VERSION_ISOLATION, VERSION_ISOLATION_EXTERNAL -> radioVersionIsolation.setEnabled(false);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transfer_content)
            .setView(dialogView)
            .setPositiveButton(R.string.transfer, (d, which) -> {
                int selectedId = radioGroup.getCheckedRadioButtonId();
                FeatureSettings.StorageType targetType = null;
                
                if (selectedId == R.id.radio_internal) {
                    targetType = FeatureSettings.StorageType.INTERNAL;
                } else if (selectedId == R.id.radio_external) {
                    targetType = FeatureSettings.StorageType.EXTERNAL;
                } else if (selectedId == R.id.radio_version_isolation_internal) {
                    targetType = FeatureSettings.StorageType.VERSION_ISOLATION_INTERNAL;
                } else if (selectedId == R.id.radio_version_isolation) {
                    targetType = FeatureSettings.StorageType.VERSION_ISOLATION_EXTERNAL;
                }

                if (targetType != null && targetType != currentStorageType) {
                    transferPack(pack, targetType);
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
        
        org.levimc.launcher.util.PersonalizationManager tpPm = new org.levimc.launcher.util.PersonalizationManager(this);
        int tpAccent = tpPm.getAccentColor();
        if (tpAccent != 0) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(tpAccent);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(tpAccent);
        } else {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.accent_text, getTheme()));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.accent_text, getTheme()));
        }
    }

    private void transferWorld(WorldItem world, FeatureSettings.StorageType targetType) {
        File targetDir = getWorldsDirectoryForType(targetType);
        if (targetDir == null) {
            Toast.makeText(this, getString(R.string.transfer_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);
        contentManager.transferWorld(world, targetDir, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, getString(R.string.transfer_success), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void transferPack(ResourcePackItem pack, FeatureSettings.StorageType targetType) {
        File targetDir = getPackDirectoryForType(targetType, pack.isBehaviorPack() ? "behavior_packs" : 
                (contentType == TYPE_SKIN_PACKS ? "skin_packs" : "resource_packs"));
        if (targetDir == null) {
            Toast.makeText(this, getString(R.string.transfer_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);
        contentManager.transferResourcePack(pack, targetDir, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, getString(R.string.transfer_success), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(ContentListActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int progress) {}
        });
    }

    private void restoreSelectionState(Bundle state) {
        if (state == null || !state.getBoolean(STATE_SELECTION_MODE, false)) return;
        ArrayList<String> paths = state.getStringArrayList(STATE_SELECTED_PATHS);
        if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.restoreSelection(paths, true);
        else if (isPackType() && packsAdapter != null) packsAdapter.restoreSelection(paths, true);
        updateSelectionToolbar();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pending_screenshot_path", pendingScreenshotPath);
        outState.putBoolean(STATE_SELECTION_MODE, isSelectionMode());
        if (contentType == TYPE_WORLDS && worldsAdapter != null) outState.putStringArrayList(STATE_SELECTED_PATHS, worldsAdapter.getSelectedPaths());
        else if (isPackType() && packsAdapter != null) outState.putStringArrayList(STATE_SELECTED_PATHS, packsAdapter.getSelectedPaths());
    }

    private void configureContentDirectories() {
        File gameDataDir = getGameDataDirForType(currentStorageType);
        File configuredWorlds = worldsDirectory;
        File resourcePacks = null;
        File behaviorPacks = null;
        File skinPacks = null;
        File screenshots = null;
        File minecraftPe = null;
        if (gameDataDir != null) {
            if (configuredWorlds == null) configuredWorlds = new File(gameDataDir, "minecraftWorlds");
            resourcePacks = new File(gameDataDir, "resource_packs");
            behaviorPacks = new File(gameDataDir, "behavior_packs");
            skinPacks = new File(gameDataDir, "skin_packs");
            screenshots = new File(gameDataDir, "Screenshots");
            minecraftPe = new File(gameDataDir, "minecraftpe");
        }
        contentManager.configureStorageDirectories(configuredWorlds, resourcePacks, behaviorPacks, skinPacks, screenshots, minecraftPe);
    }

    private boolean isPackType() {
        return contentType == TYPE_SKIN_PACKS || contentType == TYPE_RESOURCE_PACKS || contentType == TYPE_BEHAVIOR_PACKS;
    }

    private void enterSelectionMode() {
        if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.setSelectionMode(true);
        else if (isPackType() && packsAdapter != null) packsAdapter.setSelectionMode(true);
        updateSelectionToolbar();
    }

    private void exitSelectionMode() {
        if (worldsAdapter != null) worldsAdapter.setSelectionMode(false);
        if (packsAdapter != null) packsAdapter.setSelectionMode(false);
        updateSelectionToolbar();
    }

    private boolean isSelectionMode() {
        if (contentType == TYPE_WORLDS) return worldsAdapter != null && worldsAdapter.isSelectionMode();
        if (isPackType()) return packsAdapter != null && packsAdapter.isSelectionMode();
        return false;
    }

    private int getSelectedCount() {
        if (contentType == TYPE_WORLDS) return worldsAdapter != null ? worldsAdapter.getSelectedCount() : 0;
        if (isPackType()) return packsAdapter != null ? packsAdapter.getSelectedCount() : 0;
        return 0;
    }

    private void updateSelectionToolbar() {
        boolean active = isSelectionMode();
        int count = getSelectedCount();
        binding.normalToolbar.setVisibility(active ? View.GONE : View.VISIBLE);
        binding.selectionToolbar.setVisibility(active ? View.VISIBLE : View.GONE);
        binding.selectionCountText.setText(getString(R.string.selected_count, count));
        binding.batchExportButton.setEnabled(count > 0);
        binding.batchTransferButton.setEnabled(count > 0);
        binding.batchDeleteButton.setEnabled(count > 0);
    }

    private void selectAllVisible() {
        if (contentType == TYPE_WORLDS && worldsAdapter != null) worldsAdapter.selectAllVisible();
        else if (isPackType() && packsAdapter != null) packsAdapter.selectAllVisible();
        updateSelectionToolbar();
    }

    private void handleBackNavigation() {
        if (isSelectionMode()) exitSelectionMode();
        else finish();
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void updateListState(int visibleCount, int totalCount, String query) {
        binding.itemCountText.setText(String.valueOf(totalCount));
        boolean empty = visibleCount == 0;
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.contentRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) return;

        if (query != null && !query.isEmpty()) {
            binding.emptyStateTitle.setText(getString(R.string.no_search_results, query));
            binding.emptyStateMessage.setText("");
        } else if (contentType == TYPE_WORLDS) {
            binding.emptyStateTitle.setText(R.string.no_worlds_found);
            binding.emptyStateMessage.setText(R.string.no_worlds_message);
        } else if (isPackType()) {
            binding.emptyStateTitle.setText(R.string.no_packs_found);
            binding.emptyStateMessage.setText(R.string.no_packs_message);
        } else if (contentType == TYPE_SERVERS) {
            binding.emptyStateTitle.setText(R.string.no_servers_found);
            binding.emptyStateMessage.setText(R.string.no_servers_message);
        } else {
            binding.emptyStateTitle.setText(R.string.no_screenshots_found);
            binding.emptyStateMessage.setText(R.string.no_screenshots_message);
        }

        if (contentType == TYPE_WORLDS) binding.emptyStateIcon.setImageResource(R.drawable.ic_world);
        else if (contentType == TYPE_BEHAVIOR_PACKS) binding.emptyStateIcon.setImageResource(R.drawable.ic_behavior);
        else if (contentType == TYPE_SKIN_PACKS) binding.emptyStateIcon.setImageResource(R.drawable.ic_tshirt);
        else binding.emptyStateIcon.setImageResource(R.drawable.ic_photo);
    }

    private List<WorldItem> getSelectedWorlds() {
        return worldsAdapter != null ? worldsAdapter.getSelectedItems(allWorlds) : new ArrayList<>();
    }

    private List<ResourcePackItem> getSelectedPacks() {
        return packsAdapter != null ? packsAdapter.getSelectedItems(allPacks) : new ArrayList<>();
    }

    private void startBatchExport() {
        if (contentType == TYPE_WORLDS) {
            pendingBatchWorlds = getSelectedWorlds();
            pendingBatchPacks.clear();
            if (pendingBatchWorlds.isEmpty()) return;
        } else if (isPackType()) {
            pendingBatchPacks = getSelectedPacks();
            pendingBatchWorlds.clear();
            if (pendingBatchPacks.isEmpty()) return;
        } else {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        batchExportFolderLauncher.launch(intent);
    }

    private Uri createExportDocument(Uri treeUri, String displayName) {
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            return DocumentsContract.createDocument(getContentResolver(), parent, "application/zip", displayName);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String uniqueExportName(String name, String extension, Set<String> usedNames) {
        String base = name == null ? "content" : name.trim();
        base = base.replace('\\', '_').replaceAll("[/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) base = "content";
        if (base.length() > 96) base = base.substring(0, 96).trim();
        String candidate = base + extension;
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase())) {
            candidate = base + " (" + suffix++ + ")" + extension;
        }
        return candidate;
    }

    private void exportWorldBatch(Uri treeUri, List<WorldItem> items, int index, int success, int failed, Set<String> usedNames) {
        if (index >= items.size()) {
            finishBatch(success, failed, false);
            return;
        }
        runOnUiThread(() -> showProgressDialog(getString(R.string.batch_exporting, index + 1, items.size())));
        WorldItem world = items.get(index);
        Uri output = createExportDocument(treeUri, uniqueExportName(world.getWorldName(), ".mcworld", usedNames));
        if (output == null) {
            exportWorldBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            return;
        }
        contentManager.exportWorld(world, output, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                exportWorldBatch(treeUri, items, index + 1, success + 1, failed, usedNames);
            }

            @Override
            public void onError(String error) {
                exportWorldBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void exportPackBatch(Uri treeUri, List<ResourcePackItem> items, int index, int success, int failed, Set<String> usedNames) {
        if (index >= items.size()) {
            finishBatch(success, failed, false);
            return;
        }
        runOnUiThread(() -> showProgressDialog(getString(R.string.batch_exporting, index + 1, items.size())));
        ResourcePackItem pack = items.get(index);
        Uri output = createExportDocument(treeUri, uniqueExportName(pack.getPackName(), ".mcpack", usedNames));
        if (output == null) {
            exportPackBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            return;
        }
        contentManager.exportResourcePack(pack, output, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                exportPackBatch(treeUri, items, index + 1, success + 1, failed, usedNames);
            }

            @Override
            public void onError(String error) {
                exportPackBatch(treeUri, items, index + 1, success, failed + 1, usedNames);
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void showBatchDeleteDialog() {
        int count = getSelectedCount();
        if (count == 0) return;
        boolean worlds = contentType == TYPE_WORLDS;
        new CustomAlertDialog(this)
                .setTitleText(getString(worlds ? R.string.batch_delete_worlds_title : R.string.batch_delete_packs_title))
                .setMessage(getString(worlds ? R.string.batch_delete_worlds_message : R.string.batch_delete_packs_message, count))
                .setPositiveButton(getString(R.string.dialog_positive_delete), v -> {
                    if (worlds) deleteWorldBatch(getSelectedWorlds(), 0, 0, 0);
                    else deletePackBatch(getSelectedPacks(), 0, 0, 0);
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void deleteWorldBatch(List<WorldItem> items, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        showProgressDialog(getString(R.string.batch_deleting, index + 1, items.size()));
        contentManager.deleteWorld(items.get(index), false, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> deleteWorldBatch(items, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> deleteWorldBatch(items, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void deletePackBatch(List<ResourcePackItem> items, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        showProgressDialog(getString(R.string.batch_deleting, index + 1, items.size()));
        contentManager.deleteResourcePack(items.get(index), false, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> deletePackBatch(items, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> deletePackBatch(items, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void showBatchTransferDialog() {
        if (getSelectedCount() == 0) return;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_transfer_content, null);
        RadioGroup radioGroup = dialogView.findViewById(R.id.storage_radio_group);
        RadioButton radioInternal = dialogView.findViewById(R.id.radio_internal);
        RadioButton radioExternal = dialogView.findViewById(R.id.radio_external);
        RadioButton radioVersionIsolationInternal = dialogView.findViewById(R.id.radio_version_isolation_internal);
        RadioButton radioVersionIsolation = dialogView.findViewById(R.id.radio_version_isolation);
        radioVersionIsolationInternal.setText(getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_internal) + ")");
        radioVersionIsolation.setText(getString(R.string.storage_version_isolation) + " (" + getString(R.string.storage_external) + ")");
        disableCurrentStorageOption(radioInternal, radioExternal, radioVersionIsolationInternal, radioVersionIsolation);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.transfer_content)
                .setView(dialogView)
                .setPositiveButton(R.string.transfer, (d, which) -> {
                    FeatureSettings.StorageType targetType = storageTypeFromRadio(radioGroup.getCheckedRadioButtonId());
                    if (targetType == null || targetType == currentStorageType) return;
                    if (contentType == TYPE_WORLDS) transferWorldBatch(getSelectedWorlds(), targetType, 0, 0, 0);
                    else transferPackBatch(getSelectedPacks(), targetType, 0, 0, 0);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        applyDialogAccent(dialog);
    }

    private void disableCurrentStorageOption(RadioButton internal, RadioButton external, RadioButton isolationInternal, RadioButton isolationExternal) {
        switch (currentStorageType) {
            case INTERNAL -> internal.setEnabled(false);
            case EXTERNAL -> external.setEnabled(false);
            case VERSION_ISOLATION_INTERNAL -> isolationInternal.setEnabled(false);
            case VERSION_ISOLATION, VERSION_ISOLATION_EXTERNAL -> isolationExternal.setEnabled(false);
        }
    }

    private FeatureSettings.StorageType storageTypeFromRadio(int selectedId) {
        if (selectedId == R.id.radio_internal) return FeatureSettings.StorageType.INTERNAL;
        if (selectedId == R.id.radio_external) return FeatureSettings.StorageType.EXTERNAL;
        if (selectedId == R.id.radio_version_isolation_internal) return FeatureSettings.StorageType.VERSION_ISOLATION_INTERNAL;
        if (selectedId == R.id.radio_version_isolation) return FeatureSettings.StorageType.VERSION_ISOLATION_EXTERNAL;
        return null;
    }

    private void applyDialogAccent(AlertDialog dialog) {
        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();
        int color = accent != 0 ? accent : getResources().getColor(R.color.accent_text, getTheme());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
    }

    private void transferWorldBatch(List<WorldItem> items, FeatureSettings.StorageType targetType, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        File targetDir = getWorldsDirectoryForType(targetType);
        if (targetDir == null) {
            finishBatch(success, failed + items.size() - index, false);
            return;
        }
        showProgressDialog(getString(R.string.batch_transferring, index + 1, items.size()));
        contentManager.transferWorld(items.get(index), targetDir, false, new WorldManager.WorldOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> transferWorldBatch(items, targetType, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> transferWorldBatch(items, targetType, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private void transferPackBatch(List<ResourcePackItem> items, FeatureSettings.StorageType targetType, int index, int success, int failed) {
        if (index >= items.size()) {
            finishBatch(success, failed, true);
            return;
        }
        File targetDir = getPackDirectoryForType(targetType, getPackDirectoryName());
        if (targetDir == null) {
            finishBatch(success, failed + items.size() - index, false);
            return;
        }
        showProgressDialog(getString(R.string.batch_transferring, index + 1, items.size()));
        contentManager.transferResourcePack(items.get(index), targetDir, false, new ResourcePackManager.PackOperationCallback() {
            @Override
            public void onSuccess(String message) {
                runOnUiThread(() -> transferPackBatch(items, targetType, index + 1, success + 1, failed));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> transferPackBatch(items, targetType, index + 1, success, failed + 1));
            }

            @Override
            public void onProgress(int progress) {
            }
        });
    }

    private String getPackDirectoryName() {
        if (contentType == TYPE_BEHAVIOR_PACKS) return "behavior_packs";
        if (contentType == TYPE_SKIN_PACKS) return "skin_packs";
        return "resource_packs";
    }

    private void finishBatch(int success, int failed, boolean refresh) {
        runOnUiThread(() -> {
            hideProgressDialog();
            Toast.makeText(this, getString(R.string.batch_result, success, failed), failed > 0 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            exitSelectionMode();
            if (refresh) loadContent();
        });
    }

    private File getWorldsDirectoryForType(FeatureSettings.StorageType storageType) {
        File gameDataDir = getGameDataDirForType(storageType);
        return gameDataDir == null ? null : new File(gameDataDir, "minecraftWorlds");
    }

    private File getPackDirectoryForType(FeatureSettings.StorageType storageType, String packType) {
        File gameDataDir = getGameDataDirForType(storageType);
        return gameDataDir == null ? null : new File(gameDataDir, packType);
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
        if (skipInitialResumeRefresh) {
            skipInitialResumeRefresh = false;
        } else {
            loadContent();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (structureExtractor != null) {
            structureExtractor.shutdown();
        }
        if (screenshotsAdapter != null) {
            screenshotsAdapter.shutdown();
        }
    }
}
