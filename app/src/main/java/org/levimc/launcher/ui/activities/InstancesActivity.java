package org.levimc.launcher.ui.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityOptionsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.InstallProgressDialog;
import org.levimc.launcher.util.ApkImportManager;
import org.levimc.launcher.util.InstanceBackupManager;
import org.levimc.launcher.util.StorageAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class InstancesActivity extends BaseActivity {
    public static final String EXTRA_RESTORE_BACKUP_ON_OPEN = "restore_backup_on_open";

    private static final int FILTER_ALL = 0;
    private static final int FILTER_CUSTOM = 1;
    private static final int CARD_GLASS_ALPHA_LIGHT = 48;
    private static final int CARD_GLASS_ALPHA_DARK = 58;
    private static final int CARD_OUTLINE_ALPHA_LIGHT = 90;
    private static final int CARD_OUTLINE_ALPHA_DARK = 86;
    private static final int CARD_TEXT_PRIMARY_LIGHT = Color.rgb(27, 31, 35);
    private static final int CARD_TEXT_SECONDARY_LIGHT = Color.rgb(68, 75, 82);
    private static final int CARD_TEXT_SECONDARY_DARK = Color.rgb(224, 228, 232);

    private VersionManager versionManager;
    private RecyclerView recyclerView;
    private InstanceCardAdapter adapter;
    private TextView filterAll, filterCustom;
    private TextView instanceCountBadge;
    private EditText searchInput;
    private int currentFilter = FILTER_ALL;
    private List<GameVersion> allVersions = new ArrayList<>();

    private ApkImportManager apkImportManager;
    private InstanceBackupManager backupManager;
    private ActivityResultLauncher<Intent> backupDownloadsFolderLauncher;
    private InstallProgressDialog restoreProgressDialog;
    private InstallProgressDialog batchBackupProgressDialog;
    private TextView batchBackupButton;
    private List<GameVersion> pendingBatchBackupVersions = new ArrayList<>();
    private List<String> batchBackupPaths = new ArrayList<>();
    private List<String> batchBackupFailures = new ArrayList<>();
    private int batchBackupIndex;
    private ActivityResultLauncher<Intent> apkImportResultLauncher;
    private ActivityResultLauncher<Intent> backupImportResultLauncher;
    private ActivityResultLauncher<Intent> instanceSettingsLauncher;
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instances);
        setupNavBar();

        versionManager = VersionManager.get(this);

        apkImportManager = new ApkImportManager(this, null);
        backupManager = new InstanceBackupManager(this);
        backupDownloadsFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    try {
                        backupManager.setDownloadsFolder(result.getData());
                        startBatchBackup();
                    } catch (Exception error) {
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });

        apkImportManager.setOnImportCompleteListener(() -> {
            versionManager.loadAllVersions();
            loadVersions();
            applyFilters();
        });
        apkImportResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (apkImportManager != null)
                        apkImportManager.handleActivityResult(result.getResultCode(), result.getData());
                }
        );

        backupImportResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK
                            && result.getData() != null
                            && result.getData().getData() != null) {
                        restoreBackup(result.getData().getData());
                    }
                }
        );

        instanceSettingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        versionManager.loadAllVersions();
                        loadVersions();
                        applyFilters();
                    }
                }
        );

        recyclerView = findViewById(R.id.instances_recycler);
        filterAll = findViewById(R.id.filter_all);
        filterCustom = findViewById(R.id.filter_custom);
        instanceCountBadge = findViewById(R.id.instance_count_badge);
        searchInput = findViewById(R.id.search_input);

        int spanCount = calculateSpanCount();
        GridLayoutManager layoutManager = new GridLayoutManager(this, spanCount);
        recyclerView.setLayoutManager(layoutManager);

        int spacing = (int) (10 * getResources().getDisplayMetrics().density);
        recyclerView.addItemDecoration(new GridSpacingDecoration(spanCount, spacing));

        loadVersions();

        GameVersion selectedVersion = versionManager.getSelectedVersion();
        adapter = new InstanceCardAdapter(allVersions, selectedVersion);
        recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(version -> {
            versionManager.selectVersion(version);
            adapter.setSelectedVersion(version);
            adapter.notifyDataSetChanged();
        });

        adapter.setOnSettingsClickListener(version -> {
            Intent intent = new Intent(this, InstanceSettingsActivity.class);
            intent.putExtra("version", version);
            instanceSettingsLauncher.launch(intent, ActivityOptionsCompat.makeCustomAnimation(
                    this,
                    R.anim.fade_in,
                    R.anim.fade_out
            ));
        });

        setupFilterTabs();
        setupSearch();
        setupImportButton();
        setupBackupImportButton();
        setupBatchBackupButton();
        updateCount();
        handleBackupOpenIntent(getIntent());

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
        recyclerView.post(() -> DynamicAnim.staggerRecyclerChildren(recyclerView));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleBackupOpenIntent(intent);
    }

    private int calculateSpanCount() {
        float displayWidth = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float cardMinWidth = 240f;
        float padding = 40f;
        int count = (int) ((displayWidth - padding) / cardMinWidth);
        return Math.max(2, count);
    }

    private void loadVersions() {
        allVersions.clear();
        List<GameVersion> installed = versionManager.getInstalledVersions();
        List<GameVersion> custom = versionManager.getCustomVersions();
        if (installed != null) allVersions.addAll(installed);
        if (custom != null) allVersions.addAll(custom);

        Collections.sort(allVersions, (a, b) -> {
            String va = a.versionCode != null ? a.versionCode : "";
            String vb = b.versionCode != null ? b.versionCode : "";
            return vb.compareTo(va);
        });
    }

    private void setupFilterTabs() {
        updateFilterUI();

        filterAll.setOnClickListener(v -> {
            currentFilter = FILTER_ALL;
            updateFilterUI();
            applyFilters();
        });
        filterCustom.setOnClickListener(v -> {
            currentFilter = FILTER_CUSTOM;
            updateFilterUI();
            applyFilters();
        });
    }

    private void updateFilterUI() {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        TextView[] tabs = {filterAll, filterCustom};
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = (i == currentFilter);
            tabs[i].setSelected(selected);
            if (selected) {
                tabs[i].setTextColor(android.graphics.Color.WHITE);
                tabs[i].setTypeface(tabs[i].getTypeface(), android.graphics.Typeface.BOLD);
                if (accent != 0) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    gd.setColor(accent);
                    gd.setCornerRadius(20 * getResources().getDisplayMetrics().density);
                    tabs[i].setBackground(gd);
                }
            } else {
                tabs[i].setTextColor(getResources().getColor(R.color.on_surface, getTheme()));
                tabs[i].setTypeface(tabs[i].getTypeface(), android.graphics.Typeface.NORMAL);
                tabs[i].setBackgroundResource(R.drawable.bg_filter_chip);
                tabs[i].setSelected(false);
            }
        }
    }

    private void setupSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilters();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupImportButton() {
        TextView btnImport = findViewById(R.id.btn_import_apk);
        if (btnImport == null) return;
        btnImport.setVisibility(View.VISIBLE);
        btnImport.setSelected(true);
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent != 0) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(accent);
            gd.setCornerRadius(20 * getResources().getDisplayMetrics().density);
            btnImport.setBackground(gd);
            btnImport.setTextColor(android.graphics.Color.WHITE);
        }
        btnImport.setOnClickListener(v -> startApkFilePicker());
    }

    private void setupBackupImportButton() {
        TextView btnImportBackup = findViewById(R.id.btn_import_backup);
        if (btnImportBackup == null) return;
        btnImportBackup.setVisibility(View.VISIBLE);
        btnImportBackup.setSelected(true);
        applyAccentButtonStyle(btnImportBackup);
        btnImportBackup.setOnClickListener(v -> startBackupImportPicker());
    }

    private void setupBatchBackupButton() {
        batchBackupButton = findViewById(R.id.btn_batch_backup);
        if (batchBackupButton == null) return;
        batchBackupButton.setVisibility(View.VISIBLE);
        batchBackupButton.setSelected(true);
        applyAccentButtonStyle(batchBackupButton);
        batchBackupButton.setOnClickListener(v -> showBatchBackupSelection());
    }

    private void applyAccentButtonStyle(TextView button) {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent != 0) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(accent);
            gd.setCornerRadius(20 * getResources().getDisplayMetrics().density);
            button.setBackground(gd);
            button.setTextColor(android.graphics.Color.WHITE);
        }
    }

    private void startApkFilePicker() {
        apkImportResultLauncher.launch(StorageAccess.downloadsPicker(false));
    }

    private void startBackupImportPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {
                "application/zip",
                "application/x-zip",
                "application/x-zip-compressed",
                "application/octet-stream"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        backupImportResultLauncher.launch(intent);
    }

    private void restoreBackup(android.net.Uri backupUri) {
        restoreProgressDialog = new InstallProgressDialog(this);
        restoreProgressDialog.setTitleText(getString(R.string.instance_restore_title));
        restoreProgressDialog.setStatusText(getString(R.string.instance_restore_in_progress));
        restoreProgressDialog.setProgress(0);
        restoreProgressDialog.show();

        backupManager.restore(backupUri, new InstanceBackupManager.RestoreCallback() {
            @Override
            public void onStarted() {
                if (restoreProgressDialog != null) {
                    restoreProgressDialog.setProgress(0);
                    restoreProgressDialog.setStatusText(getString(R.string.instance_restore_in_progress));
                }
            }

            @Override
            public void onProgress(int progress) {
                if (restoreProgressDialog != null) {
                    restoreProgressDialog.setProgress(progress);
                }
            }

            @Override
            public void onSuccess(String restoredName) {
                finishRestoreProgress();
                versionManager.loadAllVersions();
                loadVersions();
                if (adapter != null) {
                    adapter.setSelectedVersion(versionManager.getSelectedVersion());
                    applyFilters();
                }
                new CustomAlertDialog(InstancesActivity.this)
                        .setTitleText(getString(R.string.instance_restore_success_title))
                        .setMessage(getString(R.string.instance_restore_success_message, restoredName))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                finishRestoreProgress();
                new CustomAlertDialog(InstancesActivity.this)
                        .setTitleText(getString(R.string.instance_restore_failed_title))
                        .setMessage(getString(R.string.instance_restore_failed_message, message))
                        .setPositiveButton(getString(R.string.confirm), null)
                        .show();
            }
        });
    }

    private void showBatchBackupSelection() {
        versionManager.loadAllVersions();
        loadVersions();
        if (allVersions.isEmpty()) {
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.instance_batch_backup_title))
                    .setMessage(getString(R.string.instance_batch_backup_no_instances))
                    .setPositiveButton(getString(R.string.confirm), null)
                    .show();
            return;
        }

        List<GameVersion> selectableVersions = new ArrayList<>(allVersions);
        boolean[] selected = new boolean[selectableVersions.size()];
        for (int i = 0; i < selected.length; i++) {
            selected[i] = true;
        }

        CustomAlertDialog[] dialogRef = new CustomAlertDialog[1];
        Runnable updateBackupButton = () -> updateBatchBackupDialogButton(dialogRef[0], selected);
        View content = createBatchBackupSelectionView(selectableVersions, selected, updateBackupButton);
        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.instance_batch_backup_title))
                .setCustomView(content)
                .setPositiveButton(getString(R.string.backup), v -> {
                    List<GameVersion> versionsToBackup = collectSelectedVersions(selectableVersions, selected);
                    if (versionsToBackup.isEmpty()) {
                        Toast.makeText(this, R.string.instance_batch_backup_no_selection, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startBatchBackupWithPermissionCheck(versionsToBackup);
                })
                .setNegativeButton(getString(R.string.cancel), null);
        dialogRef[0] = dialog;
        dialog.show();
        updateBatchBackupDialogButton(dialog, selected);
    }

    private View createBatchBackupSelectionView(List<GameVersion> versions, boolean[] selected, Runnable onSelectionChanged) {
        float density = getResources().getDisplayMetrics().density;
        int horizontalPadding = (int) (2 * density);
        int rowVerticalPadding = (int) (10 * density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(horizontalPadding, 0, horizontalPadding, 0);

        TextView message = new TextView(this);
        message.setText(R.string.instance_batch_backup_select_message);
        message.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        message.setTextSize(13);
        message.setFontFeatureSettings("kern");
        container.addView(message, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        CheckBox selectAll = new CheckBox(this);
        selectAll.setText(R.string.instance_batch_backup_select_all);
        selectAll.setTextColor(getResources().getColor(R.color.on_surface, getTheme()));
        selectAll.setTextSize(14);
        selectAll.setChecked(true);
        selectAll.setPadding(0, rowVerticalPadding, 0, rowVerticalPadding);
        applyCheckBoxTheme(selectAll);
        container.addView(selectAll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.divider, getTheme()));
        divider.setAlpha(0.35f);
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) density)
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        CheckBox[] boxes = new CheckBox[versions.size()];
        boolean[] updatingAll = {false};
        for (int i = 0; i < versions.size(); i++) {
            int index = i;
            CheckBox item = new CheckBox(this);
            item.setText(getVersionLabel(versions.get(i)));
            item.setTextColor(getResources().getColor(R.color.on_surface, getTheme()));
            item.setTextSize(13);
            item.setSingleLine(false);
            item.setChecked(true);
            item.setPadding(0, rowVerticalPadding, 0, rowVerticalPadding);
            applyCheckBoxTheme(item);
            item.setOnCheckedChangeListener((buttonView, isChecked) -> {
                selected[index] = isChecked;
                if (updatingAll[0]) return;
                updatingAll[0] = true;
                selectAll.setChecked(areAllSelected(selected));
                updatingAll[0] = false;
                if (onSelectionChanged != null) {
                    onSelectionChanged.run();
                }
            });
            boxes[i] = item;
            list.addView(item, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }

        selectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAll[0]) return;
            updatingAll[0] = true;
            for (int i = 0; i < boxes.length; i++) {
                selected[i] = isChecked;
                boxes[i].setChecked(isChecked);
            }
            updatingAll[0] = false;
            if (onSelectionChanged != null) {
                onSelectionChanged.run();
            }
        });

        int rowHeight = (int) (48 * density);
        int maxHeight = (int) (260 * density);
        int minHeight = (int) (96 * density);
        int listHeight = Math.min(maxHeight, Math.max(minHeight, versions.size() * rowHeight));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                listHeight
        );
        scrollParams.topMargin = (int) (6 * density);
        container.addView(scrollView, scrollParams);
        return container;
    }

    private void applyCheckBoxTheme(CheckBox checkBox) {
        PersonalizationManager pm = new PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent == 0) {
            accent = getResources().getColor(R.color.primary, getTheme());
        }
        int unchecked = getResources().getColor(R.color.text_secondary, getTheme());
        int disabled = Color.argb(88, Color.red(unchecked), Color.green(unchecked), Color.blue(unchecked));
        ColorStateList tint = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{disabled, accent, unchecked}
        );
        checkBox.setButtonTintList(tint);
    }

    private void updateBatchBackupDialogButton(CustomAlertDialog dialog, boolean[] selected) {
        if (dialog == null || dialog.getPositiveButton() == null) return;
        boolean hasSelection = false;
        if (selected != null) {
            for (boolean item : selected) {
                if (item) {
                    hasSelection = true;
                    break;
                }
            }
        }
        dialog.getPositiveButton().setEnabled(hasSelection);
        dialog.getPositiveButton().setAlpha(hasSelection ? 1f : 0.55f);
    }

    private List<GameVersion> collectSelectedVersions(List<GameVersion> versions, boolean[] selected) {
        List<GameVersion> result = new ArrayList<>();
        for (int i = 0; i < versions.size() && i < selected.length; i++) {
            if (selected[i]) {
                result.add(versions.get(i));
            }
        }
        return result;
    }

    private boolean areAllSelected(boolean[] selected) {
        if (selected == null || selected.length == 0) return false;
        for (boolean item : selected) {
            if (!item) return false;
        }
        return true;
    }

    private void startBatchBackupWithPermissionCheck(List<GameVersion> versionsToBackup) {
        pendingBatchBackupVersions = new ArrayList<>(versionsToBackup);
        if (backupManager.needsDownloadsFolderSelection()) {
            backupDownloadsFolderLauncher.launch(backupManager.downloadsFolderPicker());
            return;
        }
        startBatchBackup();
    }

    private void startBatchBackup() {
        if (pendingBatchBackupVersions == null || pendingBatchBackupVersions.isEmpty()) {
            return;
        }
        batchBackupIndex = 0;
        batchBackupPaths = new ArrayList<>();
        batchBackupFailures = new ArrayList<>();
        setBatchBackupButtonEnabled(false);

        batchBackupProgressDialog = new InstallProgressDialog(this);
        batchBackupProgressDialog.setTitleText(getString(R.string.instance_batch_backup_title));
        batchBackupProgressDialog.setStatusText(getString(
                R.string.instance_batch_backup_in_progress,
                getVersionLabel(pendingBatchBackupVersions.get(0)),
                1,
                pendingBatchBackupVersions.size()
        ));
        batchBackupProgressDialog.setProgress(0);
        batchBackupProgressDialog.show();

        runNextBatchBackup();
    }

    private void runNextBatchBackup() {
        if (isFinishing() || isDestroyed()) return;

        if (batchBackupIndex >= pendingBatchBackupVersions.size()) {
            finishBatchBackup();
            return;
        }

        GameVersion current = pendingBatchBackupVersions.get(batchBackupIndex);
        String currentLabel = getVersionLabel(current);
        int currentNumber = batchBackupIndex + 1;
        int total = pendingBatchBackupVersions.size();

        backupManager.backup(current, new InstanceBackupManager.BackupCallback() {
            @Override
            public void onStarted() {
                updateBatchBackupProgress(0, currentLabel, currentNumber, total);
            }

            @Override
            public void onProgress(int progress) {
                updateBatchBackupProgress(progress, currentLabel, currentNumber, total);
            }

            @Override
            public void onSuccess(String displayPath) {
                batchBackupPaths.add(displayPath);
                batchBackupIndex++;
                runNextBatchBackup();
            }

            @Override
            public void onError(String message) {
                batchBackupFailures.add(currentLabel + ": " + firstNonEmpty(message, getString(R.string.instance_backup_failed_title)));
                batchBackupIndex++;
                runNextBatchBackup();
            }
        });
    }

    private void updateBatchBackupProgress(int instanceProgress, String instanceName, int currentNumber, int total) {
        if (batchBackupProgressDialog == null) return;
        int clampedProgress = Math.max(0, Math.min(100, instanceProgress));
        int overallProgress = ((currentNumber - 1) * 100 + clampedProgress) / Math.max(1, total);
        batchBackupProgressDialog.setProgress(overallProgress);
        batchBackupProgressDialog.setStatusText(getString(
                R.string.instance_batch_backup_in_progress,
                instanceName,
                currentNumber,
                total
        ));
    }

    private void finishBatchBackup() {
        finishBatchBackupProgress();

        if (isFinishing() || isDestroyed()) {
            pendingBatchBackupVersions = new ArrayList<>();
            batchBackupIndex = 0;
            return;
        }

        String savedLines = joinLines(batchBackupPaths);
        String failedLines = joinLines(batchBackupFailures);
        CustomAlertDialog resultDialog = new CustomAlertDialog(this);
        if (batchBackupPaths.isEmpty()) {
            resultDialog
                    .setTitleText(getString(R.string.instance_batch_backup_failed_title))
                    .setMessage(getString(R.string.instance_batch_backup_failed_message, failedLines))
                    .setPositiveButton(getString(R.string.confirm), null)
                    .show();
        } else if (batchBackupFailures.isEmpty()) {
            resultDialog
                    .setTitleText(getString(R.string.instance_batch_backup_success_title))
                    .setMessage(getString(R.string.instance_batch_backup_success_message,
                            batchBackupPaths.size(), savedLines))
                    .setPositiveButton(getString(R.string.confirm), null)
                    .show();
        } else {
            resultDialog
                    .setTitleText(getString(R.string.instance_batch_backup_partial_title))
                    .setMessage(getString(R.string.instance_batch_backup_partial_message,
                            batchBackupPaths.size(), batchBackupFailures.size(), savedLines, failedLines))
                    .setPositiveButton(getString(R.string.confirm), null)
                    .show();
        }

        pendingBatchBackupVersions = new ArrayList<>();
        batchBackupIndex = 0;
    }

    private void finishBatchBackupProgress() {
        if (batchBackupProgressDialog != null && batchBackupProgressDialog.isShowing()) {
            batchBackupProgressDialog.dismiss();
        }
        batchBackupProgressDialog = null;
        setBatchBackupButtonEnabled(true);
    }

    private void setBatchBackupButtonEnabled(boolean enabled) {
        if (batchBackupButton == null) return;
        batchBackupButton.setEnabled(enabled);
        batchBackupButton.setAlpha(enabled ? 1f : 0.55f);
    }

    private String getVersionLabel(GameVersion version) {
        if (version == null) {
            return getString(R.string.minecraft);
        }
        String primary = firstNonEmpty(version.displayName, version.directoryName, version.versionCode, getString(R.string.minecraft));
        String secondary = firstNonEmpty(version.versionCode, version.directoryName);
        if (!secondary.isEmpty() && !primary.contains(secondary)) {
            return primary + " (" + secondary + ")";
        }
        return primary;
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ").append(line.trim());
        }
        return builder.toString();
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private void handleBackupOpenIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RESTORE_BACKUP_ON_OPEN, false)) {
            return;
        }
        android.net.Uri uri = intent.getData();
        intent.removeExtra(EXTRA_RESTORE_BACKUP_ON_OPEN);
        setIntent(intent);
        if (uri != null) {
            restoreBackup(uri);
        }
    }

    private void finishRestoreProgress() {
        if (restoreProgressDialog != null && restoreProgressDialog.isShowing()) {
            restoreProgressDialog.dismiss();
        }
        restoreProgressDialog = null;
    }

    private void applyFilters() {
        String query = searchInput.getText().toString().trim().toLowerCase();
        List<GameVersion> filtered = new ArrayList<>();

        for (GameVersion v : allVersions) {
            if (currentFilter == FILTER_CUSTOM && v.isInstalled) continue;

            if (!query.isEmpty()) {
                String name = v.displayName != null ? v.displayName.toLowerCase() : "";
                String code = v.versionCode != null ? v.versionCode.toLowerCase() : "";
                String dir = v.directoryName != null ? v.directoryName.toLowerCase() : "";
                if (!name.contains(query) && !code.contains(query) && !dir.contains(query)) {
                    continue;
                }
            }

            filtered.add(v);
        }

        adapter.updateData(filtered);
        updateCount();
    }

    private void updateCount() {
        if (instanceCountBadge != null && adapter != null) {
            instanceCountBadge.setText(String.valueOf(adapter.getItemCount()));
        }
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_instances);
        findViewById(R.id.nav_tab_instances).setOnClickListener(v -> {});
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
        } else {
            versionManager.loadAllVersions();
            loadVersions();
        }
        setupImportButton();
        setupBackupImportButton();
        setupBatchBackupButton();
        applyFilters();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backupManager != null) {
            backupManager.shutdown();
        }
    }

    private static class GridSpacingDecoration extends RecyclerView.ItemDecoration {
        private final int spanCount;
        private final int spacing;

        GridSpacingDecoration(int spanCount, int spacing) {
            this.spanCount = spanCount;
            this.spacing = spacing;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            int column = position % spanCount;
            outRect.left = spacing - column * spacing / spanCount;
            outRect.right = (column + 1) * spacing / spanCount;
            if (position >= spanCount) {
                outRect.top = spacing;
            }
        }
    }

    private static class InstanceCardAdapter extends RecyclerView.Adapter<InstanceCardAdapter.VH> {
        private List<GameVersion> versions;
        private GameVersion selectedVersion;
        private OnItemClickListener listener;
        private OnSettingsClickListener settingsListener;

        interface OnItemClickListener {
            void onClick(GameVersion version);
        }

        interface OnSettingsClickListener {
            void onClick(GameVersion version);
        }

        void setOnItemClickListener(OnItemClickListener l) {
            this.listener = l;
        }

        void setOnSettingsClickListener(OnSettingsClickListener l) {
            this.settingsListener = l;
        }

        InstanceCardAdapter(List<GameVersion> versions, GameVersion selected) {
            this.versions = new ArrayList<>(versions);
            this.selectedVersion = selected;
        }

        void setSelectedVersion(GameVersion v) {
            this.selectedVersion = v;
        }

        void updateData(List<GameVersion> newVersions) {
            this.versions = new ArrayList<>(newVersions);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_instance_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GameVersion v = versions.get(position);
            boolean isSelected = selectedVersion != null
                    && selectedVersion.directoryName != null
                    && selectedVersion.directoryName.equals(v.directoryName);

            holder.itemView.setActivated(isSelected);

            PersonalizationManager pm = new PersonalizationManager(holder.itemView.getContext());
            int accent = pm.getAccentColor();
            boolean hasBackgroundImage = pm.hasBackgroundImage();
            boolean isDark = isDarkMode(holder.itemView.getContext());
            
            int bgColor;
            int primaryTextColor;
            int secondaryTextColor;
            int outlineColor;
            if (hasBackgroundImage) {
                bgColor = isDark
                        ? Color.argb(CARD_GLASS_ALPHA_DARK, 18, 20, 24)
                        : Color.argb(CARD_GLASS_ALPHA_LIGHT, 255, 255, 255);
                primaryTextColor = isDark ? Color.WHITE : CARD_TEXT_PRIMARY_LIGHT;
                secondaryTextColor = isDark ? CARD_TEXT_SECONDARY_DARK : CARD_TEXT_SECONDARY_LIGHT;
                outlineColor = Color.argb(isDark ? CARD_OUTLINE_ALPHA_DARK : CARD_OUTLINE_ALPHA_LIGHT,
                        255, 255, 255);
            } else {
                bgColor = androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.surface);
                primaryTextColor = androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.on_surface);
                secondaryTextColor = androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary);
                outlineColor = androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.outline);
            }
            
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(12 * holder.itemView.getContext().getResources().getDisplayMetrics().density);
            gd.setColor(bgColor);
            
            if (isSelected && accent != 0) {
                gd.setStroke((int)(2 * holder.itemView.getContext().getResources().getDisplayMetrics().density), accent);
            } else {
                gd.setStroke((int)(1 * holder.itemView.getContext().getResources().getDisplayMetrics().density), outlineColor);
            }
            
            holder.itemView.setBackground(gd);

            holder.versionCode.setText(v.versionCode != null ? v.versionCode : v.directoryName);
            holder.versionCode.setTextColor(primaryTextColor);
            holder.displayName.setTextColor(secondaryTextColor);
            holder.settingsIcon.setImageTintList(ColorStateList.valueOf(secondaryTextColor));

            if (v.isInstalled) {
                holder.typeTag.setText(R.string.tag_installed);
                int tagColor = accent != 0 ? accent : holder.itemView.getContext().getResources().getColor(R.color.primary, holder.itemView.getContext().getTheme());
                holder.typeTag.setTextColor(tagColor);
                if (hasBackgroundImage) {
                    holder.typeTag.setBackground(makeTagBackground(holder.itemView.getContext(), tagColor));
                } else {
                    holder.typeTag.setBackgroundResource(R.drawable.bg_release_tag);
                }
            } else {
                holder.typeTag.setText(R.string.tag_custom);
                int tagColor = hasBackgroundImage
                        ? secondaryTextColor
                        : holder.itemView.getContext().getResources().getColor(R.color.text_secondary, holder.itemView.getContext().getTheme());
                holder.typeTag.setTextColor(tagColor);
                if (hasBackgroundImage) {
                    holder.typeTag.setBackground(makeTagBackground(holder.itemView.getContext(), tagColor));
                } else {
                    holder.typeTag.setBackgroundResource(R.drawable.bg_preview_tag);
                }
            }
            holder.typeTag.setVisibility(View.VISIBLE);

            String displayLabel;
            if (v.displayName != null && !v.displayName.isEmpty()) {
                displayLabel = v.displayName;
            } else {
                displayLabel = holder.itemView.getContext().getString(R.string.vanilla_prefix, v.versionCode != null ? v.versionCode : "");
            }
            holder.displayName.setText(displayLabel);

            holder.settingsIcon.setOnClickListener(iv -> {
                if (settingsListener != null) settingsListener.onClick(v);
            });

            holder.itemView.setOnClickListener(iv -> {
                if (listener != null) listener.onClick(v);
            });

            DynamicAnim.applyPressScale(holder.itemView);
        }

        private static boolean isDarkMode(Context context) {
            int nightModeFlags = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
        }

        private static GradientDrawable makeTagBackground(Context context, int color) {
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setShape(GradientDrawable.RECTANGLE);
            tagBg.setColor(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)));
            tagBg.setCornerRadius(4 * context.getResources().getDisplayMetrics().density);
            return tagBg;
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView versionCode, typeTag, displayName;
            ImageView settingsIcon;

            VH(View v) {
                super(v);
                versionCode = v.findViewById(R.id.card_version_code);
                typeTag = v.findViewById(R.id.card_type_tag);
                displayName = v.findViewById(R.id.card_display_name);
                settingsIcon = v.findViewById(R.id.card_settings_icon);
            }
        }
    }
}
