package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.InstallProgressDialog;
import org.levimc.launcher.ui.packages.PackagesActivity;
import org.levimc.launcher.ui.packages.LoaderCard;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.InstanceBackupManager;
import org.levimc.launcher.util.InstanceShortcutManager;
import org.levimc.launcher.util.InstanceIcons;

import java.io.File;
import java.io.FileOutputStream;

public class InstanceSettingsActivity extends BaseActivity {

    private GameVersion version;
    private VersionManager versionManager;
    private InstanceBackupManager backupManager;
    private ActivityResultLauncher<Intent> backupDownloadsFolderLauncher;
    private InstallProgressDialog backupProgressDialog;
    private Button backupButton;

    private static final int TAB_GENERAL = 0;
    private static final int TAB_LAUNCH_OPTIONS = 1;
    private static final int TAB_LOADER = 2;
    private static final int TAB_MANAGEMENT = 3;
    private static final String ICON_DIR = "instance_icons";

    private SegmentedControl tabs;
    private View sectionGeneral, sectionLaunchOptions, sectionLoader, sectionManagement;

    private EditText editName;
    private SwitchMaterial switchIsolation;
    private SwitchMaterial switchLaunchVertically;
    private SwitchMaterial switchModMenu;
    private EditText editShortcutName;
    private ImageView shortcutIconPreview;
    private View shortcutIconPlaceholder;
    private String shortcutIconUri;
    private ActivityResultLauncher<String[]> shortcutIconPicker;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingNameSave;
    private boolean populatingData;
    private String lastSavedInstanceName = "";
    private LoaderCard loaderSection;
    private int currentTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instance_settings);

        shortcutIconPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            String stored = importIcon(uri);
            if (stored == null) {
                Toast.makeText(this, R.string.instances_ui_icon_import_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            shortcutIconUri = stored;
            updateShortcutIconPreview();
            saveShortcutSettings();
        });

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));

        setupNavBar();

        versionManager = VersionManager.get(this);
        backupManager = new InstanceBackupManager(this);
        backupDownloadsFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    try {
                        backupManager.setDownloadsFolder(result.getData());
                        startBackup();
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });

        version = getIntent().getParcelableExtra("version");
        if (version == null) {
            finish();
            return;
        }

        initViews();
        populateData();
        loaderSection = new LoaderCard(this, version, findViewById(R.id.pkg_loader_card));
        // The "Go to Loader" links open the Loader tab (PackagesActivity.Links.EXTRA_TAB).
        String tab = getIntent().getStringExtra(PackagesActivity.Links.EXTRA_TAB);
        int initialTab = PackagesActivity.Links.TAB_LOADER.equals(tab) ? TAB_LOADER
                : PackagesActivity.Links.TAB_MANAGEMENT.equals(tab) ? TAB_MANAGEMENT : TAB_GENERAL;
        if (savedInstanceState != null) {
            initialTab = Math.max(TAB_GENERAL, Math.min(TAB_MANAGEMENT, savedInstanceState.getInt("instance_settings_tab", initialTab)));
        }
        tabs.setSelectedIndex(initialTab, false);
        selectTab(initialTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loaderSection != null) {
            loaderSection.onResume();
        }
    }

    private void initViews() {
        tabs = findViewById(R.id.instance_tabs);
        tabs.setEqualWidth(false);
        tabs.setItems(getString(R.string.instance_tab_general),
                getString(R.string.instance_tab_launch_options),
                getString(R.string.instance_tab_loader),
                getString(R.string.instance_tab_management));
        tabs.setOnSegmentSelectedListener(this::selectTab);

        sectionGeneral = findViewById(R.id.section_general);
        sectionLaunchOptions = findViewById(R.id.section_launch_options);
        sectionLoader = findViewById(R.id.section_loader);
        sectionManagement = findViewById(R.id.section_management);

        editName = findViewById(R.id.edit_instance_name);
        switchIsolation = findViewById(R.id.switch_version_isolation);
        switchLaunchVertically = findViewById(R.id.switch_launch_vertically);
        switchModMenu = findViewById(R.id.switch_instance_mod_menu);
        editShortcutName = findViewById(R.id.edit_shortcut_name);
        shortcutIconPreview = findViewById(R.id.shortcut_icon_preview);
        shortcutIconPlaceholder = findViewById(R.id.instance_icon_placeholder);
        findViewById(R.id.btn_choose_shortcut_icon).setClipToOutline(true);

        switchIsolation.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (populatingData) return;
            versionManager.setInstanceVersionIsolation(version, isChecked);
            setResult(RESULT_OK);
        });
        switchLaunchVertically.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (populatingData) return;
            versionManager.setInstanceLaunchVertically(version, isChecked);
            setResult(RESULT_OK);
        });

        switchModMenu.setOnCheckedChangeListener((button, checked) -> {
            if (populatingData) return;
            if (versionManager.setInstanceModMenuEnabled(version, checked)) {
                setResult(RESULT_OK);
            } else {
                populatingData = true;
                switchModMenu.setChecked(versionManager.isInstanceModMenuEnabled(version));
                populatingData = false;
                Toast.makeText(this, R.string.instance_settings_auto_save_failed, Toast.LENGTH_SHORT).show();
            }
        });

        editName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!populatingData) scheduleInstanceNameSave();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        editShortcutName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!populatingData) saveShortcutSettings();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.btn_choose_shortcut_icon).setOnClickListener(v ->
                shortcutIconPicker.launch(new String[]{"image/*"}));
        findViewById(R.id.btn_reset_shortcut_icon).setOnClickListener(v -> {
            deleteStoredIcon();
            shortcutIconUri = null;
            updateShortcutIconPreview();
            saveShortcutSettings();
        });
        MaterialButton addShortcut = findViewById(R.id.btn_add_instance_shortcut);
        UiKit.applyPrimary(addShortcut);
        addShortcut.setOnClickListener(v -> addInstanceShortcut());

        Button btnDelete = findViewById(R.id.btn_delete_instance);
        backupButton = findViewById(R.id.btn_backup_instance);
        if (backupButton != null) {
            backupButton.setOnClickListener(v -> confirmBackup());
        }
        if (version.isInstalled) {
            btnDelete.setEnabled(false);
            btnDelete.setAlpha(0.4f);
        } else {
            btnDelete.setOnClickListener(v -> confirmDelete());
        }
    }

    private void populateData() {
        TextView versionTag = findViewById(R.id.instance_version_tag);
        versionTag.setText(version.versionCode != null ? version.versionCode : "—");
        String currentName = version.versionCode != null ? version.versionCode : "";
        if (version.displayName != null && !version.displayName.isEmpty()) {
            String dn = version.displayName;
            int parenIdx = dn.lastIndexOf(" (");
            if (parenIdx > 0) {
                currentName = dn.substring(0, parenIdx);
            } else {
                currentName = dn;
            }
        }
        updateInstanceInfo(currentName);
        populatingData = true;
        lastSavedInstanceName = currentName.trim();
        editName.setText(currentName);
        switchIsolation.setChecked(version.versionIsolation);
        switchLaunchVertically.setChecked(version.launchVertically);
        switchModMenu.setChecked(versionManager.isInstanceModMenuEnabled(version));
        editShortcutName.setText(InstanceShortcutManager.getSavedName(this, version));
        shortcutIconUri = InstanceShortcutManager.getSavedIconUri(this, version);
        updateShortcutIconPreview();
        populatingData = false;
    }

    private void selectTab(int index) {
        boolean changed = currentTab >= 0 && currentTab != index;
        currentTab = index;
        View[] sections = {sectionGeneral, sectionLaunchOptions, sectionLoader, sectionManagement};
        for (int i = 0; i < sections.length; i++) {
            if (i == index) {
                sections[i].setVisibility(View.VISIBLE);
                sections[i].setAlpha(0f);
                sections[i].animate().alpha(1f).setDuration(200).start();
            } else {
                sections[i].setVisibility(View.GONE);
            }
        }
        if (loaderSection != null) {
            loaderSection.setVisible(index == TAB_LOADER);
        }
        if (changed) findViewById(R.id.instance_settings_scroll).post(() ->
                ((android.widget.ScrollView) findViewById(R.id.instance_settings_scroll)).scrollTo(0, 0));
    }

    private void updateInstanceInfo(String name) {
        TextView info = findViewById(R.id.instance_info);
        String type = getString(version.isInstalled ? R.string.tag_installed : R.string.tag_custom);
        info.setText(name + " · " + type);
        info.setContentDescription(info.getText());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("instance_settings_tab", currentTab);
    }

    private void scheduleInstanceNameSave() {
        if (pendingNameSave != null) {
            autoSaveHandler.removeCallbacks(pendingNameSave);
        }
        pendingNameSave = this::saveInstanceNameNow;
        autoSaveHandler.postDelayed(pendingNameSave, 250);
    }

    private void saveInstanceNameNow() {
        if (pendingNameSave != null) {
            autoSaveHandler.removeCallbacks(pendingNameSave);
            pendingNameSave = null;
        }
        if (editName == null || version == null) return;
        String newName = editName.getText().toString().trim();
        if (newName.isEmpty() || newName.equals(lastSavedInstanceName)) return;
        if (versionManager.setInstanceDisplayName(version, newName)) {
            lastSavedInstanceName = newName;
            updateInstanceInfo(newName);
            setResult(RESULT_OK);
        } else {
            Toast.makeText(this, R.string.instance_settings_auto_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveShortcutSettings() {
        if (populatingData || version == null || editShortcutName == null) return;
        String shortcutName = editShortcutName.getText().toString().trim();
        InstanceShortcutManager.saveSettings(this, version, shortcutName, shortcutIconUri);
        if (InstanceShortcutManager.isPinned(this, version)) {
            InstanceShortcutManager.createOrUpdate(this, version, shortcutName, shortcutIconUri);
        }
        setResult(RESULT_OK);
    }

    private void addInstanceShortcut() {
        saveShortcutSettings();
        String shortcutName = editShortcutName == null ? "" : editShortcutName.getText().toString().trim();
        boolean accepted = InstanceShortcutManager.createOrUpdate(this, version, shortcutName, shortcutIconUri);
        if (!accepted) {
            Toast.makeText(this, R.string.instance_shortcut_not_supported, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateShortcutIconPreview() {
        if (shortcutIconPreview == null) return;
        InstanceIcons.bindUri(shortcutIconPreview, shortcutIconUri);
        shortcutIconPreview.setVisibility(View.VISIBLE);
        if (shortcutIconPlaceholder != null) shortcutIconPlaceholder.setVisibility(View.GONE);
    }

    /**
     * Copies the picked image (cropped to a square) into app-private storage, so the icon keeps working
     * after the picker's URI permission is gone. Returns the stored file URI, or null on failure.
     */
    private String importIcon(Uri source) {
        Bitmap bitmap = InstanceShortcutManager.loadIconBitmap(this, source.toString());
        if (bitmap == null) return null;
        File dir = new File(getFilesDir(), ICON_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        // A new name per pick, so a cached bitmap of the previous icon is never reused.
        File target = new File(dir, InstanceShortcutManager.getShortcutId(version)
                + "_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                target.delete();
                return null;
            }
        } catch (Exception e) {
            target.delete();
            return null;
        }
        deleteStoredIcon();
        return Uri.fromFile(target).toString();
    }

    /** Deletes the current icon file when it is one this screen stored in app-private storage. */
    private void deleteStoredIcon() {
        if (shortcutIconUri == null) return;
        try {
            Uri uri = Uri.parse(shortcutIconUri);
            if (!"file".equals(uri.getScheme()) || uri.getPath() == null) return;
            File file = new File(uri.getPath());
            File parent = file.getParentFile();
            File dir = new File(getFilesDir(), ICON_DIR);
            if (parent != null && dir.getCanonicalPath().equals(parent.getCanonicalPath())) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void confirmDelete() {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.instance_delete_confirm_title))
                .setMessage(getString(R.string.instance_delete_confirm_msg))
                .setPositiveButton(getString(R.string.delete), v -> {
                    versionManager.deleteCustomVersion(version, new VersionManager.OnDeleteVersionCallback() {
                        @Override
                        public void onDeleteCompleted(boolean success) {
                            runOnUiThread(() -> {
                                setResult(RESULT_OK);
                                finish();
                            });
                        }

                        @Override
                        public void onDeleteFailed(Exception e) {
                            runOnUiThread(() -> Toast.makeText(InstanceSettingsActivity.this,
                                    getString(R.string.toast_delete_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void confirmBackup() {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.instance_backup_title))
                .setMessage(getString(R.string.instance_backup_confirm_message))
                .setPositiveButton(getString(R.string.backup), v -> startBackupWithPermissionCheck())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void startBackupWithPermissionCheck() {
        if (backupManager.needsDownloadsFolderSelection()) {
            backupDownloadsFolderLauncher.launch(backupManager.downloadsFolderPicker());
            return;
        }
        startBackup();
    }

    private void startBackup() {
        if (backupButton != null) {
            backupButton.setEnabled(false);
            backupButton.setAlpha(0.55f);
        }
        backupProgressDialog = new InstallProgressDialog(this);
        backupProgressDialog.setTitleText(getString(R.string.instance_backup_title));
        backupProgressDialog.setStatusText(getString(R.string.instance_backup_in_progress));
        backupProgressDialog.setProgress(0);
        backupProgressDialog.show();

        backupManager.backup(version, new InstanceBackupManager.BackupCallback() {
            @Override
            public void onStarted() {
                if (backupProgressDialog != null) {
                    backupProgressDialog.setProgress(0);
                    backupProgressDialog.setStatusText(getString(R.string.instance_backup_in_progress));
                }
            }

            @Override
            public void onProgress(int progress) {
                if (backupProgressDialog != null) {
                    backupProgressDialog.setProgress(progress);
                }
            }

            @Override
            public void onSuccess(String displayPath) {
                finishBackupProgress();
                new CustomAlertDialog(InstanceSettingsActivity.this)
                        .setTitleText(getString(R.string.instance_backup_success_title))
                        .setMessage(getString(R.string.instance_backup_success_message, displayPath))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                finishBackupProgress();
                new CustomAlertDialog(InstanceSettingsActivity.this)
                        .setTitleText(getString(R.string.instance_backup_failed_title))
                        .setMessage(getString(R.string.instance_backup_failed_message, message))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }
        });
    }

    private void finishBackupProgress() {
        if (backupProgressDialog != null && backupProgressDialog.isShowing()) {
            backupProgressDialog.dismiss();
        }
        backupProgressDialog = null;
        if (backupButton != null) {
            backupButton.setEnabled(true);
            backupButton.setAlpha(1f);
        }
    }

    @Override
    protected void onPause() {
        saveInstanceNameNow();
        saveShortcutSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (pendingNameSave != null) {
            autoSaveHandler.removeCallbacks(pendingNameSave);
            pendingNameSave = null;
        }
        if (loaderSection != null) {
            loaderSection.onDestroy();
            loaderSection = null;
        }
        super.onDestroy();
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_instances);
    }
}
