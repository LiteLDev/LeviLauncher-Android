package org.levimc.launcher.ui.activities;

import android.content.Intent;
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

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.InstallProgressDialog;
import org.levimc.launcher.util.InstanceBackupManager;
import org.levimc.launcher.util.InstanceShortcutManager;

public class InstanceSettingsActivity extends BaseActivity {

    private GameVersion version;
    private VersionManager versionManager;
    private InstanceBackupManager backupManager;
    private ActivityResultLauncher<Intent> backupDownloadsFolderLauncher;
    private InstallProgressDialog backupProgressDialog;
    private Button backupButton;

    private TextView tabGeneral, tabLaunchOptions, tabManagement;
    private View sectionGeneral, sectionLaunchOptions, sectionManagement;

    private EditText editName;
    private SwitchMaterial switchIsolation;
    private SwitchMaterial switchLaunchVertically;
    private EditText editShortcutName;
    private ImageView shortcutIconPreview;
    private String shortcutIconUri;
    private ActivityResultLauncher<String[]> shortcutIconPicker;
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingNameSave;
    private boolean populatingData;
    private String lastSavedInstanceName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instance_settings);

        shortcutIconPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            shortcutIconUri = uri.toString();
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
        selectTab(tabGeneral);
    }

    private void initViews() {
        tabGeneral = findViewById(R.id.tab_general);
        tabLaunchOptions = findViewById(R.id.tab_launch_options);
        tabManagement = findViewById(R.id.tab_management);

        sectionGeneral = findViewById(R.id.section_general);
        sectionLaunchOptions = findViewById(R.id.section_launch_options);
        sectionManagement = findViewById(R.id.section_management);

        editName = findViewById(R.id.edit_instance_name);
        switchIsolation = findViewById(R.id.switch_version_isolation);
        switchLaunchVertically = findViewById(R.id.switch_launch_vertically);
        editShortcutName = findViewById(R.id.edit_shortcut_name);
        shortcutIconPreview = findViewById(R.id.shortcut_icon_preview);

        tabGeneral.setOnClickListener(v -> selectTab(tabGeneral));
        tabLaunchOptions.setOnClickListener(v -> selectTab(tabLaunchOptions));
        tabManagement.setOnClickListener(v -> selectTab(tabManagement));

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
            shortcutIconUri = null;
            updateShortcutIconPreview();
            saveShortcutSettings();
        });
        findViewById(R.id.btn_add_instance_shortcut).setOnClickListener(v -> addInstanceShortcut());

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
        TextView instanceInfo = findViewById(R.id.instance_info);
        String type = version.isInstalled ? getString(R.string.tag_installed) : getString(R.string.tag_custom);
        String info = "Game Version: " + (version.versionCode != null ? version.versionCode : "—")
                + " · Name: " + (version.directoryName != null ? version.directoryName : "—")
                + " · " + type;
        instanceInfo.setText(info);

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
        populatingData = true;
        lastSavedInstanceName = currentName.trim();
        editName.setText(currentName);
        switchIsolation.setChecked(version.versionIsolation);
        switchLaunchVertically.setChecked(version.launchVertically);
        editShortcutName.setText(InstanceShortcutManager.getSavedName(this, version));
        shortcutIconUri = InstanceShortcutManager.getSavedIconUri(this, version);
        updateShortcutIconPreview();
        populatingData = false;
    }

    private void selectTab(TextView selectedTab) {
        TextView[] tabs = {tabGeneral, tabLaunchOptions, tabManagement};
        View[] sections = {sectionGeneral, sectionLaunchOptions, sectionManagement};

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();

        for (int i = 0; i < tabs.length; i++) {
            boolean isSelected = tabs[i] == selectedTab;

            if (isSelected) {
                if (accent != 0) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    gd.setColor(accent);
                    gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
                    tabs[i].setBackground(gd);
                } else {
                    tabs[i].setBackgroundResource(R.drawable.bg_tab_selected);
                }
                tabs[i].setTextColor(android.graphics.Color.WHITE);
            } else {
                tabs[i].setBackgroundResource(R.drawable.bg_tab_unselected);
                tabs[i].setTextColor(getColor(R.color.text_secondary));
            }

            if (isSelected) {
                sections[i].setVisibility(View.VISIBLE);
                sections[i].setAlpha(0f);
                sections[i].animate().alpha(1f).setDuration(200).start();
            } else {
                sections[i].setVisibility(View.GONE);
            }
        }
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
        android.graphics.Bitmap bitmap = InstanceShortcutManager.loadIconBitmap(this, shortcutIconUri);
        if (bitmap != null) {
            shortcutIconPreview.setImageBitmap(bitmap);
        } else {
            shortcutIconPreview.setImageResource(R.mipmap.ic_launcher);
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
                if (isFinishing() || isDestroyed()) return;
                new CustomAlertDialog(InstanceSettingsActivity.this)
                        .setTitleText(getString(R.string.instance_backup_success_title))
                        .setMessage(getString(R.string.instance_backup_success_message, displayPath))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                finishBackupProgress();
                if (isFinishing() || isDestroyed()) return;
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
        if (backupManager != null) {
            backupManager.shutdown();
        }
        super.onDestroy();
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_instances);
    }
}
