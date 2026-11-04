package org.levimc.launcher.util;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.ApkVersionConfirmDialog;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.dialogs.InstallProgressDialog;
import org.levimc.launcher.ui.views.MainViewModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApkImportManager {
    private final Activity activity;
    private final MainViewModel viewModel;
    private final InstallProgressDialog progressDialog;
    private OnImportCompleteListener importCompleteListener;

    public interface OnImportCompleteListener {
        void onImportComplete();
    }

    public ApkImportManager(Activity activity, MainViewModel viewModel) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.progressDialog = new InstallProgressDialog(activity);
    }

    public void setOnImportCompleteListener(OnImportCompleteListener listener) {
        this.importCompleteListener = listener;
    }

    public void handleApkImportResult(Intent data) {
        Uri apkUri = data.getData();
        if (apkUri == null) return;
        StorageAccess.retainReadPermission(activity, data, apkUri);
        
        String fileName = getFileName(apkUri);
        boolean isApks = fileName != null && fileName.toLowerCase().endsWith(".apks");
        
        if (!isApks && fileName != null && !fileName.toLowerCase().endsWith(".apk")) {
            new CustomAlertDialog(activity)
                    .setTitleText(activity.getString(R.string.illegal_apk_title))
                    .setMessage(activity.getString(R.string.not_apk_or_apks))
                    .setPositiveButton(activity.getString(R.string.exit), v -> {})
                    .show();
            return;
        }
        
        String initialVersionName = isApks 
                ? ApkUtils.extractMinecraftVersionNameFromApksUri(activity, apkUri)
                : ApkUtils.extractMinecraftVersionNameFromUri(activity, apkUri);
                
        if ("Error Apk".equals(initialVersionName)) {
            new CustomAlertDialog(activity)
                    .setTitleText(activity.getString(R.string.illegal_apk_title))
                    .setMessage(activity.getString(R.string.not_mc_apk))
                    .setPositiveButton(activity.getString(R.string.exit), v -> {})
                    .show();
            return;
        }
        ApkVersionConfirmDialog dialog = new ApkVersionConfirmDialog()
                .setInitialVersionName(initialVersionName)
                .setCallback(new ApkVersionConfirmDialog.Callback() {
                    @Override
                    public void onInstallClicked(String versionName) {
                        showProgress();
                        ExecutorService installExecutor = Executors.newSingleThreadExecutor();
                        ApkInstaller installer = new ApkInstaller(activity, installExecutor, new ApkInstaller.InstallCallback() {
                            @Override
                            public void onProgress(int progress) {
                                activity.runOnUiThread(() -> progressDialog.setProgress(progress));
                            }

                            @Override
                            public void onSuccess(String versionName) {
                                installExecutor.shutdown();
                                activity.runOnUiThread(() -> {
                                    dismissProgress();
                                    Toast.makeText(
                                            activity,
                                            activity.getString(R.string.install_done, versionName),
                                            Toast.LENGTH_LONG
                                    ).show();
                                    VersionManager.get(activity).loadAllVersions();
                                    if (importCompleteListener != null) {
                                        importCompleteListener.onImportComplete();
                                    }
                                });
                            }

                            @Override
                            public void onError(String errorMsg) {
                                installExecutor.shutdown();
                                activity.runOnUiThread(() -> {
                                    dismissProgress();
                                    Toast.makeText(activity, errorMsg, Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                        installer.install(apkUri, versionName);
                    }

                    @Override
                    public void onCancelled() {
                    }
                });
        dialog.show(((AppCompatActivity) activity).getSupportFragmentManager(), "ApkVersionConfirmDialog");
    }

    void showProgress() {
        progressDialog.setProgress(0);
        if (!progressDialog.isShowing()) progressDialog.show();
    }

    void dismissProgress() {
        if (progressDialog.isShowing()) progressDialog.dismiss();
    }

    public void handleActivityResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            handleApkImportResult(data);
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }
}
