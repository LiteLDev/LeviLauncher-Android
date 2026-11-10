package org.levimc.launcher.core.mods;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.views.MainViewModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileHandler {
    private static final String TAG = "FileHandler";
    private static final int BUFFER_SIZE = 8192;
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final String OVERWRITE_FILES_FIELD = "overwrite_files";
    private static final String OVERWRITE_FOLDERS_FIELD = "overwrite_folders";
    private static final String PRELOAD_NATIVE_TYPE = "preload-native";
    private static final String DEFAULT_MOD_AUTHOR = "Unknown";
    private static final String DEFAULT_MOD_ICON = "";
    private static final String DEFAULT_MOD_VERSION = "1.0.0";
    private static final String FALLBACK_ZIP_FILE_NAME = "imported_mod.zip";
    private static final String MCMOD_EXTENSION = ".mcmod";
    /** A mod folder name, as lip requires of the "name" in an mcmod asset's manifest.json. */
    private static final Pattern MOD_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Context context;
    private final MainViewModel modManager;
    private String targetPath;

    private static final class PreparedImport {
        final String targetId;
        final String entryPath;
        final Set<String> overwriteFiles;
        final Set<String> overwriteFolders;
        final File packageDir;
        final File cleanupRoot;

        PreparedImport(String targetId, String entryPath, Set<String> overwriteFiles, Set<String> overwriteFolders,
                       File packageDir, File cleanupRoot) {
            this.targetId = targetId;
            this.entryPath = entryPath;
            this.overwriteFiles = overwriteFiles;
            this.overwriteFolders = overwriteFolders;
            this.packageDir = packageDir;
            this.cleanupRoot = cleanupRoot;
        }
    }

    public interface FileOperationCallback {
        void onSuccess(int processedFiles);

        void onError(String errorMessage);

        void onProgressUpdate(int progress);
    }

    public FileHandler(Context context, MainViewModel modManager, VersionManager version) {
        this.context = context;
        this.modManager = modManager;

        GameVersion currentVersion = version.getSelectedVersion();
        if (currentVersion != null && currentVersion.modsDir != null) {
            this.targetPath = currentVersion.modsDir.getAbsolutePath();
        } else {
            this.targetPath = null;
        }
    }

    public void processIncomingFilesWithConfirmation(Intent intent, FileOperationCallback callback, boolean isButtonClick) {
        processIncomingFilesWithConfirmation(extractFileUris(intent), callback, isButtonClick);
    }

    public void processIncomingFilesWithConfirmation(List<Uri> fileUris, FileOperationCallback callback, boolean isButtonClick) {
        List<Uri> supportedUris = new ArrayList<>();
        for (Uri uri : fileUris) {
            String fileName = resolveImportFileName(uri);
            if (isSupportedImportFile(uri, fileName)) {
                supportedUris.add(uri);
            }
        }

        if (supportedUris.isEmpty()) {
            if (isButtonClick) {
                new CustomAlertDialog(context)
                        .setTitleText(context.getString(R.string.invalid_mod_file))
                        .setMessage(context.getString(R.string.invalid_mod_file_reason))
                        .setUseBorderedBackground(true)
                        .setBlurBackground(true)
                        .setPositiveButton(context.getString(R.string.confirm), d -> {})
                        .show();
            }
            return;
        }

        new Thread(() -> {
            List<Uri> packagedUris = new ArrayList<>();
            List<Uri> bareUris = new ArrayList<>();
            List<String> invalidZipNames = new ArrayList<>();
            List<String> invalidMcmods = new ArrayList<>();
            for (Uri uri : supportedUris) {
                String fileName = resolveImportFileName(uri);
                if (isMcmodFile(fileName)) {
                    McmodCheck check = checkMcmod(uri);
                    if (check.problem != null) {
                        invalidMcmods.add(fileName + ": " + check.problem);
                        continue;
                    }
                }
                if (isZipModPackageFile(uri, fileName) && !isValidZipModPackage(uri)) {
                    invalidZipNames.add(fileName);
                    continue;
                }

                if (needsMetadataInput(fileName)) {
                    bareUris.add(uri);
                } else {
                    packagedUris.add(uri);
                }
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                Runnable showImportDialogs = () -> {
                    if (!packagedUris.isEmpty()) {
                        new CustomAlertDialog(context)
                                .setTitleText(context.getString(R.string.import_confirmation_title))
                                .setMessage(context.getString(R.string.import_confirmation_message, packagedUris.size()))
                                .setPositiveButton(context.getString(R.string.confirm), d -> handleFilesWithOverwriteCheck(packagedUris, null, null, null, callback))
                                .setNegativeButton(context.getString(R.string.cancel), d -> {
                                    if (callback != null) {
                                        callback.onError(context.getString(R.string.user_cancelled));
                                    }
                                })
                                .show();
                    }

                    if (!bareUris.isEmpty()) {
                        showNextMetadataDialog(bareUris, 0, callback);
                    }
                };

                Runnable afterMcmods = invalidMcmods.isEmpty() ? showImportDialogs
                        : () -> showInvalidPackageDialog(R.string.invalid_mcmod_package_title,
                                R.string.invalid_mcmod_package_message, invalidMcmods, showImportDialogs);
                if (!invalidZipNames.isEmpty()) {
                    showInvalidPackageDialog(R.string.invalid_zip_mod_package_title,
                            R.string.invalid_zip_mod_package_message, invalidZipNames, afterMcmods);
                } else {
                    afterMcmods.run();
                }
            });
        }).start();
    }

    public void processCatalogModDirectly(Intent intent, String name, String author, String version,
                                          List<String> minecraftVersions, FileOperationCallback callback) {
        List<Uri> fileUris = extractFileUris(intent);
        List<Uri> supportedUris = new ArrayList<>();
        for (Uri uri : fileUris) {
            String fileName = resolveImportFileName(uri);
            if (isSupportedImportFile(uri, fileName)) {
                supportedUris.add(uri);
            }
        }

        if (supportedUris.isEmpty()) {
            postError(callback, context.getString(R.string.invalid_mod_file_reason));
            return;
        }

        new Thread(() -> {
            for (Uri uri : supportedUris) {
                String fileName = resolveImportFileName(uri);
                if (isMcmodFile(fileName)) {
                    McmodCheck check = checkMcmod(uri);
                    if (check.problem != null) {
                        postError(callback, fileName + ": " + check.problem);
                        return;
                    }
                }
                if (isZipModPackageFile(uri, fileName) && !isValidZipModPackage(uri)) {
                    postError(callback, context.getString(R.string.invalid_zip_mod_package_message));
                    return;
                }
            }
            handleFilesWithOverwriteCheck(supportedUris, name, PRELOAD_NATIVE_TYPE, version, author,
                    minecraftVersions, callback);
        }).start();
    }

    private boolean needsMetadataInput(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".so")) {
            return true;
        }
        return false;
    }

    private boolean isZipModPackageFile(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".zip") || lowerName.endsWith(".levipack");
    }

    private boolean isZipModPackageFile(Uri uri, String fileName) {
        // A .mcmod is a zip too, but it has a format of its own (see checkMcmod).
        if (isMcmodFile(fileName)) {
            return false;
        }
        return isZipModPackageFile(fileName) || isZipMimeType(uri) || hasZipHeader(uri);
    }

    static boolean isMcmodFile(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(MCMOD_EXTENSION);
    }

    /** What checking a .mcmod found: its mod folder name, or why it cannot be installed. */
    static final class McmodCheck {
        final String modName;
        final String entry;
        final JsonObject manifest;
        final String problem;

        private McmodCheck(String modName, String entry, JsonObject manifest, String problem) {
            this.modName = modName;
            this.entry = entry;
            this.manifest = manifest;
            this.problem = problem;
        }

        static McmodCheck invalid(String problem) {
            return new McmodCheck(null, null, null, problem);
        }
    }

    private McmodCheck checkMcmod(Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return McmodCheck.invalid("cannot be opened");
            }
            return checkMcmod(in);
        } catch (IOException e) {
            Log.w(TAG, "Cannot read .mcmod", e);
            return McmodCheck.invalid("cannot be read: " + e.getMessage());
        }
    }

    /**
     * Checks a .mcmod: a zip of exactly what goes into {@code mods/<id>/}, so its manifest.json is at the
     * root and {@code <id>} is the manifest's "name" (as lip installs an mcmod asset). Its files are
     * installed unchanged, so the manifest must already be one the launcher loads.
     */
    static McmodCheck checkMcmod(InputStream archive) throws IOException {
        Set<String> files = new HashSet<>();
        byte[] manifestBytes = null;
        try (ZipInputStream zis = new ZipInputStream(archive)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String raw = entry.getName().replace('\\', '/');
                String name = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
                if (name.equals("__MACOSX") || name.startsWith("__MACOSX/")) {
                    continue;
                }
                if (normalizeEntryPath(name) == null) {
                    return McmodCheck.invalid("it has an unsafe path '" + raw + "'");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                files.add(name);
                if (name.equals(MANIFEST_FILE_NAME)) {
                    manifestBytes = readAll(zis);
                }
            }
        } catch (IllegalArgumentException e) {
            return McmodCheck.invalid("it is not a valid zip archive");
        }
        if (files.isEmpty()) {
            return McmodCheck.invalid("it is not a zip archive or is empty");
        }
        if (manifestBytes == null) {
            return McmodCheck.invalid("there is no manifest.json at the root of the archive");
        }

        JsonObject manifest;
        try {
            JsonElement parsed = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8));
            manifest = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            manifest = null;
        }
        if (manifest == null) {
            return McmodCheck.invalid("its manifest.json is not a JSON object");
        }

        String modName = jsonString(manifest, "name");
        if (modName == null || !MOD_NAME.matcher(modName).matches()) {
            return McmodCheck.invalid("the \"name\" of its manifest.json must be a folder name "
                    + "(letters, digits, '.', '_' or '-', starting with a letter or digit)");
        }
        if (!PRELOAD_NATIVE_TYPE.equals(jsonString(manifest, "type"))) {
            return McmodCheck.invalid("the \"type\" of its manifest.json must be \"" + PRELOAD_NATIVE_TYPE + "\"");
        }
        String entry = normalizeEntryPath(jsonString(manifest, "entry"));
        if (entry == null || !entry.endsWith(".so") || !files.contains(entry)) {
            return McmodCheck.invalid("the \"entry\" of its manifest.json must name a .so file in the archive"
                    + " (an Android build of the mod)");
        }
        return new McmodCheck(modName, entry, manifest, null);
    }

    private static String jsonString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void showInvalidPackageDialog(int titleRes, int messageRes, List<String> invalidNames, Runnable afterDismiss) {
        StringBuilder message = new StringBuilder(context.getString(messageRes));
        if (!invalidNames.isEmpty()) {
            message.append("\n\n");
            for (String fileName : invalidNames) {
                message.append("- ").append(fileName).append('\n');
            }
        }

        new CustomAlertDialog(context)
                .setTitleText(context.getString(titleRes))
                .setMessage(message.toString().trim())
                .setUseBorderedBackground(true)
                .setBlurBackground(true)
                .setPositiveButton(context.getString(R.string.confirm), d -> {
                    if (afterDismiss != null) {
                        afterDismiss.run();
                    }
                })
                .show();
    }

    private boolean isValidZipModPackage(Uri uri) {
        Set<String> manifestRoots = new HashSet<>();
        List<String> soEntries = new ArrayList<>();

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                return false;
            }

            try (ZipInputStream zis = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = normalizeZipEntryName(entry.getName());
                    if (name.isEmpty() || isIgnoredZipEntry(name)) {
                        zis.closeEntry();
                        continue;
                    }

                    if (!entry.isDirectory()) {
                        if (name.equals(MANIFEST_FILE_NAME)) {
                            manifestRoots.add("");
                        } else if (name.endsWith("/" + MANIFEST_FILE_NAME)) {
                            manifestRoots.add(name.substring(0, name.length() - MANIFEST_FILE_NAME.length() - 1));
                        }

                        if (name.toLowerCase(Locale.ROOT).endsWith(".so")) {
                            soEntries.add(name);
                        }
                    }
                    zis.closeEntry();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Invalid zip mod package", e);
            return false;
        }

        if (manifestRoots.isEmpty() || soEntries.size() != 1) {
            return false;
        }
        if (manifestRoots.contains("") && isSoUnderRoot("", soEntries.get(0))) {
            return true;
        }

        int validRootCount = 0;
        for (String manifestRoot : manifestRoots) {
            if (isSoUnderRoot(manifestRoot, soEntries.get(0))) {
                validRootCount++;
            }
        }
        return validRootCount == 1;
    }

    private boolean isSoUnderRoot(String root, String soEntry) {
        if (root == null || root.isEmpty()) {
            return true;
        }

        return soEntry != null && soEntry.startsWith(root + "/");
    }

    private boolean isIgnoredZipEntry(String normalizedName) {
        return "__MACOSX".equals(normalizedName) || normalizedName.startsWith("__MACOSX/");
    }

    private void showNextMetadataDialog(List<Uri> bareUris, int index, FileOperationCallback callback) {
        if (index >= bareUris.size()) {
            return;
        }
        Uri uri = bareUris.get(index);
        String fileName = resolveFileName(uri);
        String defaultName = deriveDisplayNameFromLibrary(fileName);
        String defaultType = PRELOAD_NATIVE_TYPE;
        String defaultVersion = DEFAULT_MOD_VERSION;

        float density = context.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout container = new android.widget.LinearLayout(context);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        int padH = (int) (12 * density);
        container.setPadding(padH, 0, padH, padH);

        android.widget.LinearLayout.LayoutParams nameParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = (int) (4 * density);
        
        android.widget.EditText etName = buildLabeledEditText(container, context.getString(R.string.plugin_name_label), defaultName, density, nameParams);

        android.widget.LinearLayout rowLayout = new android.widget.LinearLayout(context);
        rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams rowParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = (int) (6 * density);
        rowLayout.setLayoutParams(rowParams);

        android.widget.LinearLayout.LayoutParams typeParams = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        typeParams.rightMargin = (int) (4 * density);

        android.widget.LinearLayout.LayoutParams versionParams = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        versionParams.leftMargin = (int) (4 * density);

        android.widget.EditText etType = buildLabeledEditText(rowLayout, context.getString(R.string.plugin_type_label), defaultType, density, typeParams);
        android.widget.EditText etVersion = buildLabeledEditText(rowLayout, context.getString(R.string.plugin_version_label), defaultVersion, density, versionParams);

        container.addView(rowLayout);

        new CustomAlertDialog(context)
                .setTitleText(context.getString(R.string.import_so_plugin_title))
                .setCustomView(container)
                .setPositiveButton(context.getString(R.string.import_button), v -> {
                    String name    = etName.getText().toString().trim();
                    String type    = etType.getText().toString().trim();
                    String version = etVersion.getText().toString().trim();
                    if (name.isEmpty()) name = defaultName;
                    if (type.isEmpty()) type = defaultType;
                    if (version.isEmpty()) version = defaultVersion;
                    handleFilesWithOverwriteCheck(
                            java.util.Collections.singletonList(uri),
                            name, type, version, callback);
                    showNextMetadataDialog(bareUris, index + 1, callback);
                })
                .setNegativeButton(context.getString(R.string.cancel), v -> {
                    if (callback != null) {
                        callback.onError(context.getString(R.string.user_cancelled));
                    }
                    showNextMetadataDialog(bareUris, index + 1, callback);
                })
                .show();
    }

    private android.widget.EditText buildLabeledEditText(
            android.view.ViewGroup container, String label, String defaultValue, float density, android.widget.LinearLayout.LayoutParams layoutParams) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(context);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.plugin_field_background);
        int cardPadH = (int) (12 * density);
        int cardPadTop = (int) (8 * density);
        int cardPadBottom = (int) (8 * density);
        card.setPadding(cardPadH, cardPadTop, cardPadH, cardPadBottom);
        
        if (layoutParams == null) {
            layoutParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            layoutParams.topMargin = (int) (8 * density);
        }
        card.setLayoutParams(layoutParams);

        android.widget.TextView tv = new android.widget.TextView(context);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        int secondaryColor = 0xFFAAAAAA;
        try { secondaryColor = context.getResources().getColor(R.color.text_secondary, context.getTheme()); } catch (Exception ignored) {}
        tv.setTextColor(secondaryColor);
        tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(tv);

        android.widget.EditText et = new android.widget.EditText(context);
        et.setText(defaultValue);
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        int primaryColor = 0xFFFFFFFF;
        try { primaryColor = context.getResources().getColor(R.color.text_primary, context.getTheme()); } catch (Exception ignored) {}
        et.setTextColor(primaryColor);
        et.setHintTextColor(secondaryColor);
        et.setBackground(null);
        et.setPadding(0, (int) (2 * density), 0, 0);
        et.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(et);

        container.addView(card);
        return et;
    }



    private void handleFilesWithOverwriteCheck(
            List<Uri> fileUris,
            String overrideName, String overrideType, String overrideVersion,
            FileOperationCallback callback) {
        handleFilesWithOverwriteCheck(
                fileUris, overrideName, overrideType, overrideVersion, null, null, callback);
    }

    private void handleFilesWithOverwriteCheck(
            List<Uri> fileUris,
            String overrideName, String overrideType, String overrideVersion,
            String overrideAuthor,
            List<String> overrideMinecraftVersions,
            FileOperationCallback callback) {
        new Thread(() -> {
            if (targetPath == null) {
                postError(callback, "No selected version mods directory");
                return;
            }

            File targetDir = new File(targetPath);
            if (!createDirectoryIfNeeded(targetDir)) {
                postError(callback, "Failed to create mods directory");
                return;
            }

            int completed = 0;
            int processed = 0;
            String lastError = null;

            for (Uri uri : fileUris) {
                PreparedImport preparedImport = null;
                try {
                    String fileName = resolveImportFileName(uri);
                    if (!isSupportedImportFile(uri, fileName)) {
                        throw new IOException("Unsupported mod import file: " + fileName);
                    }

                    preparedImport = prepareImport(
                            uri, fileName, overrideName, overrideType, overrideVersion,
                            overrideAuthor, overrideMinecraftVersions);
                    String refusal = ModManager.packageRefusal(targetDir, preparedImport.targetId);
                    if (refusal != null) {
                        throw new IOException(context.getString(
                                R.string.mod_import_refused_by_packages, preparedImport.targetId, refusal));
                    }
                    File destinationDir = new File(targetDir, preparedImport.targetId);
                    if (destinationDir.exists() && !confirmOverwrite(preparedImport.targetId)) {
                        lastError = context.getString(R.string.user_cancelled);
                        continue;
                    }

                    if (destinationDir.exists()) {
                        copyDirectoryPreservingExisting(
                                preparedImport.packageDir,
                                destinationDir,
                                preparedImport.entryPath,
                                preparedImport.overwriteFiles,
                                preparedImport.overwriteFolders);
                    } else {
                        copyDirectory(preparedImport.packageDir, destinationDir);
                    }
                    ++processed;
                } catch (Exception e) {
                    lastError = e.getMessage();
                    Log.e(TAG, "Failed to import mod", e);
                } finally {
                    if (preparedImport != null) {
                        deleteRecursively(preparedImport.cleanupRoot);
                    }
                    ++completed;
                    postProgress(callback, completed, fileUris.size());
                }
            }

            final int processedCount = processed;
            final String finalError = lastError;
            new Handler(Looper.getMainLooper()).post(() -> {
                modManager.reloadMods();
                if (processedCount > 0) {
                    if (callback != null) {
                        callback.onSuccess(processedCount);
                    }
                } else if (callback != null && finalError != null && !finalError.isEmpty()) {
                    callback.onError(finalError);
                }
            });
        }).start();
    }

    private PreparedImport prepareImport(
            Uri uri, String fileName,
            String overrideName, String overrideType, String overrideVersion,
            String overrideAuthor, List<String> overrideMinecraftVersions) throws IOException {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".so")) {
            return prepareSoImport(uri, fileName, overrideName, overrideType, overrideVersion,
                    overrideAuthor, overrideMinecraftVersions);
        }
        if (isMcmodFile(fileName)) {
            return prepareMcmodImport(uri);
        }
        if (isZipModPackageFile(uri, fileName)) {
            return prepareZipImport(
                    uri, fileName, overrideName, overrideType, overrideVersion,
                    overrideAuthor, overrideMinecraftVersions);
        }
        throw new IOException("Unsupported mod import file: " + fileName);
    }

    private PreparedImport prepareSoImport(
            Uri uri, String fileName,
            String overrideName, String overrideType, String overrideVersion,
            String overrideAuthor, List<String> overrideMinecraftVersions) throws IOException {
        File stagingRoot = createTempDirectory("mod_import_so");
        String displayName = (overrideName != null && !overrideName.isEmpty())
                ? overrideName
                : deriveDisplayNameFromLibrary(fileName);
        String targetId = buildTargetId(displayName, stripExtension(fileName));
        File packageDir = new File(stagingRoot, targetId);
        if (!packageDir.mkdirs()) {
            throw new IOException("Failed to prepare mod package directory");
        }

        File libraryFile = new File(packageDir, fileName);
        copyUriToFile(uri, libraryFile);
        writeManifest(packageDir, createNormalizedManifest(
                new JsonObject(), displayName, fileName, packageDir, overrideType, overrideVersion,
                overrideAuthor, overrideMinecraftVersions));
        return new PreparedImport(targetId, fileName, new HashSet<>(), new HashSet<>(), packageDir, stagingRoot);
    }

    private PreparedImport prepareZipImport(
            Uri uri, String fileName,
            String overrideName, String overrideType, String overrideVersion,
            String overrideAuthor, List<String> overrideMinecraftVersions) throws IOException {
        File tempZip = new File(context.getCacheDir(), "mod_zip_" + System.currentTimeMillis() + ".zip");
        File stagingRoot = createTempDirectory("mod_import_zip");
        try {
            copyUriToFile(uri, tempZip);
            extractZip(tempZip, stagingRoot);
        } finally {
            if (tempZip.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempZip.delete();
            }
        }

        File modRoot = findImportedModRoot(stagingRoot);
        if (modRoot == null) {
            throw new IOException("Invalid mod zip: no manifest.json or .so entry found");
        }

        List<String> soFiles = collectRelativeSoFiles(modRoot);
        if (soFiles.size() != 1) {
            throw new IOException("Invalid mod zip: exactly one .so entry is required");
        }

        File manifestFile = new File(modRoot, MANIFEST_FILE_NAME);
        if (!manifestFile.isFile()) {
            throw new IOException("Invalid mod zip: manifest.json is required");
        }

        JsonObject manifest = readManifest(modRoot);
        Set<String> overwriteFiles = parseOverwriteFiles(manifest);
        Set<String> overwriteFolders = parseOverwriteFolders(manifest);
        String entryPath = resolveEntryPath(manifest, modRoot, soFiles, fileName);
        if (entryPath == null) {
            throw new IOException("Invalid mod zip: manifest entry is missing or ambiguous");
        }

        String displayName = (overrideName != null && !overrideName.isEmpty())
                ? overrideName
                : resolveDisplayName(manifest, entryPath);
        writeManifest(modRoot, createNormalizedManifest(
                manifest, displayName, entryPath, modRoot, overrideType, overrideVersion,
                overrideAuthor, overrideMinecraftVersions));

        String rootName = modRoot.equals(stagingRoot) ? stripExtension(fileName) : modRoot.getName();
        String targetId = buildTargetId(displayName, rootName);
        return new PreparedImport(targetId, entryPath, overwriteFiles, overwriteFolders, modRoot, stagingRoot);
    }

    /** A .mcmod goes to {@code mods/<manifest name>/} exactly as archived (no metadata overrides). */
    private PreparedImport prepareMcmodImport(Uri uri) throws IOException {
        File tempZip = new File(context.getCacheDir(), "mod_mcmod_" + System.currentTimeMillis() + ".zip");
        File stagingRoot = createTempDirectory("mod_import_mcmod");
        try {
            copyUriToFile(uri, tempZip);
            McmodCheck check;
            try (InputStream in = new FileInputStream(tempZip)) {
                check = checkMcmod(in); // again: the source may have changed since the first check
            }
            if (check.problem != null) {
                throw new IOException("Invalid .mcmod: " + check.problem);
            }
            extractZip(tempZip, stagingRoot);
            deleteRecursively(new File(stagingRoot, "__MACOSX"));
            return new PreparedImport(check.modName, check.entry, parseOverwriteFiles(check.manifest),
                    parseOverwriteFolders(check.manifest), stagingRoot, stagingRoot);
        } catch (IOException | RuntimeException e) {
            deleteRecursively(stagingRoot);
            throw e;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempZip.delete();
        }
    }

    private JsonObject readManifest(File modRoot) throws IOException {
        File manifestFile = new File(modRoot, MANIFEST_FILE_NAME);
        if (!manifestFile.isFile()) {
            return new JsonObject();
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(manifestFile), StandardCharsets.UTF_8)) {
            JsonObject manifest = GSON.fromJson(reader, JsonObject.class);
            return manifest == null ? new JsonObject() : manifest;
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse manifest.json, regenerating it", e);
            return new JsonObject();
        }
    }

    private JsonObject createNormalizedManifest(
            JsonObject manifest, String displayName, String entryPath, File modRoot,
            String overrideType, String overrideVersion) {
        return createNormalizedManifest(
                manifest, displayName, entryPath, modRoot, overrideType, overrideVersion, null, null);
    }

    private JsonObject createNormalizedManifest(
            JsonObject manifest, String displayName, String entryPath, File modRoot,
            String overrideType, String overrideVersion, String overrideAuthor,
            List<String> overrideMinecraftVersions) {
        JsonObject normalized = manifest == null ? new JsonObject() : manifest.deepCopy();
        String type = (overrideType != null && !overrideType.isEmpty()) ? overrideType : PRELOAD_NATIVE_TYPE;
        String version = (overrideVersion != null && !overrideVersion.isEmpty()) ? overrideVersion : resolveVersion(manifest);
        normalized.addProperty("type", type);
        normalized.addProperty("name", displayName);
        normalized.addProperty("entry", entryPath.replace('\\', '/'));
        normalized.addProperty("author", overrideAuthor != null && !overrideAuthor.trim().isEmpty()
                ? overrideAuthor.trim() : resolveAuthor(manifest));
        normalized.addProperty("icon", resolveIconPath(manifest, modRoot));
        normalized.addProperty("version", version);
        if (overrideMinecraftVersions != null && !overrideMinecraftVersions.isEmpty()) {
            JsonArray versions = new JsonArray();
            for (String minecraftVersion : overrideMinecraftVersions) {
                if (minecraftVersion != null && !minecraftVersion.trim().isEmpty()) {
                    versions.add(minecraftVersion.trim());
                }
            }
            if (versions.size() > 0) normalized.add("minecraft_versions", versions);
        }
        return normalized;
    }

    // Overload without metadata overrides (for packaged zips that already have a manifest).
    private JsonObject createNormalizedManifest(
            JsonObject manifest, String displayName, String entryPath, File modRoot) {
        return createNormalizedManifest(manifest, displayName, entryPath, modRoot, null, null);
    }

    private String resolveAuthor(JsonObject manifest) {
        String author = getStringProperty(manifest, "author");
        if (author != null && !author.trim().isEmpty()) {
            return author.trim();
        }

        if (manifest != null && manifest.has("authors") && manifest.get("authors").isJsonArray()) {
            JsonArray authors = manifest.getAsJsonArray("authors");
            for (JsonElement authorElement : authors) {
                if (authorElement == null || authorElement.isJsonNull()) {
                    continue;
                }

                if (authorElement.isJsonPrimitive()) {
                    String authorValue = authorElement.getAsString();
                    if (authorValue != null && !authorValue.trim().isEmpty()) {
                        return authorValue.trim();
                    }
                    continue;
                }

                if (authorElement.isJsonObject()) {
                    String authorName = getStringProperty(authorElement.getAsJsonObject(), "name");
                    if (authorName != null && !authorName.trim().isEmpty()) {
                        return authorName.trim();
                    }
                }
            }
        }

        return DEFAULT_MOD_AUTHOR;
    }

    private String resolveIconPath(JsonObject manifest, File modRoot) {
        String iconPath = normalizeEntryPath(getStringProperty(manifest, "icon"));
        if (iconPath != null) {
            File iconFile = new File(modRoot, iconPath);
            if (iconFile.isFile()) {
                return iconPath;
            }
        }
        return DEFAULT_MOD_ICON;
    }

    private String resolveVersion(JsonObject manifest) {
        String version = getStringProperty(manifest, "version");
        if (version != null && !version.trim().isEmpty()) {
            return version.trim();
        }
        return DEFAULT_MOD_VERSION;
    }

    private void writeManifest(File modRoot, JsonObject manifest) throws IOException {
        File manifestFile = new File(modRoot, MANIFEST_FILE_NAME);
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(manifestFile), StandardCharsets.UTF_8)) {
            GSON.toJson(manifest, writer);
        }
    }

    private String resolveEntryPath(JsonObject manifest, File modRoot, List<String> soFiles, String sourceFileName) {
        String manifestEntry = getStringProperty(manifest, "entry");
        String normalizedEntry = normalizeEntryPath(manifestEntry);
        if (normalizedEntry != null && new File(modRoot, normalizedEntry).isFile()) {
            return normalizedEntry;
        }

        if (soFiles.isEmpty()) {
            return null;
        }

        if (soFiles.size() == 1) {
            return soFiles.get(0);
        }

        List<String> preferredTokens = new ArrayList<>();
        preferredTokens.add(stripExtension(sourceFileName));
        preferredTokens.add(modRoot.getName());
        if (normalizedEntry != null) {
            preferredTokens.add(new File(normalizedEntry).getName());
        }

        return findMatchingEntryByName(soFiles, preferredTokens);
    }

    private String findMatchingEntryByName(List<String> soFiles, List<String> preferredTokens) {
        List<String> normalizedTokens = new ArrayList<>();
        for (String preferredToken : preferredTokens) {
            String normalized = normalizeMatchToken(preferredToken);
            if (!normalized.isEmpty()) {
                normalizedTokens.add(normalized);
            }
        }

        String matchedEntry = null;
        for (String soFile : soFiles) {
            String fileName = new File(soFile).getName();
            String normalizedFileToken = normalizeMatchToken(fileName);
            for (String preferredToken : normalizedTokens) {
                if (!normalizedFileToken.equals(preferredToken)) {
                    continue;
                }
                if (matchedEntry != null && !matchedEntry.equals(soFile)) {
                    return null;
                }
                matchedEntry = soFile;
            }
        }
        return matchedEntry;
    }

    private String resolveDisplayName(JsonObject manifest, String entryPath) {
        String manifestName = getStringProperty(manifest, "name");
        if (manifestName != null && !manifestName.trim().isEmpty()) {
            return manifestName.trim();
        }
        return deriveDisplayNameFromLibrary(new File(entryPath).getName());
    }

    private String deriveDisplayNameFromLibrary(String fileName) {
        String baseName = stripExtension(fileName);
        if (baseName.startsWith("lib") && baseName.length() > 3) {
            baseName = baseName.substring(3);
        }
        if (baseName.isEmpty()) {
            return "mod";
        }
        return baseName;
    }

    private String buildTargetId(String preferredName, String fallbackName) {
        String candidate = sanitizeDirectoryName(preferredName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        candidate = sanitizeDirectoryName(fallbackName);
        if (!candidate.isEmpty()) {
            return candidate;
        }

        return "mod_" + System.currentTimeMillis();
    }

    private String sanitizeDirectoryName(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = value.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .replace(':', '_')
                .replace('*', '_')
                .replace('?', '_')
                .replace('"', '_')
                .replace('<', '_')
                .replace('>', '_')
                .replace('|', '_');

        while (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized.trim();
    }

    static String normalizeEntryPath(String entryPath) {
        if (entryPath == null) {
            return null;
        }

        String normalized = entryPath.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }

        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return null;
            }
        }
        return normalized;
    }

    static Set<String> parseOverwriteFiles(JsonObject manifest) {
        Set<String> files = new HashSet<>();
        JsonElement value = getOverwritePathValue(manifest, OVERWRITE_FILES_FIELD);
        if (value == null || value.isJsonNull()) {
            return files;
        }

        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement element : array) {
                addOverwriteFile(files, element);
            }
            return files;
        }

        addOverwriteFile(files, value);
        return files;
    }

    static Set<String> parseOverwriteFolders(JsonObject manifest) {
        Set<String> folders = new HashSet<>();
        JsonElement value = getOverwritePathValue(manifest, OVERWRITE_FOLDERS_FIELD);
        if (value == null || value.isJsonNull()) {
            return folders;
        }

        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement element : array) {
                addOverwriteFolder(folders, element);
            }
            return folders;
        }

        addOverwriteFolder(folders, value);
        return folders;
    }

    private static JsonElement getOverwritePathValue(JsonObject manifest, String fieldName) {
        if (manifest == null || !manifest.has(fieldName)) {
            return null;
        }
        return manifest.get(fieldName);
    }

    private static void addOverwriteFile(Set<String> files, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }

        try {
            String file = normalizeEntryPath(element.getAsString());
            if (file != null) {
                files.add(file);
            }
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
        }
    }

    private static void addOverwriteFolder(Set<String> folders, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }

        try {
            String folder = normalizeOverwriteFolder(element.getAsString());
            if (folder != null) {
                folders.add(folder);
            }
        } catch (UnsupportedOperationException | IllegalStateException ignored) {
        }
    }

    static String normalizeOverwriteFolder(String folderPath) {
        if (folderPath == null) {
            return null;
        }

        String normalized = folderPath.trim().replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        String safePath = normalizeEntryPath(normalized);
        return safePath == null || safePath.isEmpty() ? null : safePath;
    }

    static boolean canReplaceImportedPath(
            String relativePath, String entryPath, Set<String> overwriteFiles, Set<String> overwriteFolders) {
        String normalizedRelativePath = normalizeEntryPath(relativePath);
        if (normalizedRelativePath == null) {
            return false;
        }

        if (MANIFEST_FILE_NAME.equals(normalizedRelativePath)) {
            return true;
        }

        String normalizedEntryPath = normalizeEntryPath(entryPath);
        if (normalizedRelativePath.equals(normalizedEntryPath)) {
            return true;
        }

        if (overwriteFiles != null && overwriteFiles.contains(normalizedRelativePath)) {
            return true;
        }

        return isPathInOverwriteFolder(normalizedRelativePath, overwriteFolders);
    }

    static boolean isPathInOverwriteFolder(String relativePath, Set<String> overwriteFolders) {
        if (overwriteFolders == null || overwriteFolders.isEmpty()) {
            return false;
        }

        String normalizedRelativePath = normalizeEntryPath(relativePath);
        if (normalizedRelativePath == null) {
            return false;
        }

        for (String folder : overwriteFolders) {
            if (folder == null || folder.isEmpty()) {
                continue;
            }
            if (normalizedRelativePath.startsWith(folder + "/")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeMatchToken(String value) {
        if (value == null) {
            return "";
        }

        String token = stripExtension(value).trim();
        if (token.startsWith("lib") && token.length() > 3) {
            token = token.substring(3);
        }
        return token.replaceAll("[\\s_\\-.]+", "").toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private List<String> collectRelativeSoFiles(File rootDir) {
        List<String> soFiles = new ArrayList<>();
        collectRelativeSoFilesRecursive(rootDir, rootDir, soFiles);
        soFiles.sort(Comparator.naturalOrder());
        return soFiles;
    }

    private void collectRelativeSoFilesRecursive(File rootDir, File currentDir, List<String> soFiles) {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                collectRelativeSoFilesRecursive(rootDir, file, soFiles);
                continue;
            }

            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".so")) {
                String relativePath = rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                String normalizedPath = normalizeEntryPath(relativePath);
                if (normalizedPath != null) {
                    soFiles.add(normalizedPath);
                }
            }
        }
    }

    private File findImportedModRoot(File extractedRoot) {
        if (isValidZipModRoot(extractedRoot)) {
            return extractedRoot;
        }

        List<File> candidates = new ArrayList<>();
        collectCandidateModRoots(extractedRoot, candidates);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    private void collectCandidateModRoots(File currentDir, List<File> candidates) {
        File[] children = currentDir.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (!child.isDirectory() || "__MACOSX".equals(child.getName())) {
                continue;
            }

            if (isValidZipModRoot(child)) {
                candidates.add(child);
                continue;
            }
            collectCandidateModRoots(child, candidates);
        }
    }

    private boolean isValidZipModRoot(File directory) {
        if (!directory.isDirectory()) {
            return false;
        }

        File manifestFile = new File(directory, MANIFEST_FILE_NAME);
        if (!manifestFile.isFile()) {
            return false;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }

        return countSoFiles(directory) == 1;
    }

    private int countSoFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }

        int soCount = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".so")) {
                soCount++;
            }
            if (file.isDirectory()) {
                soCount += countSoFiles(file);
            }
        }
        return soCount;
    }

    private boolean confirmOverwrite(String modName) {
        final Object decisionLock = new Object();
        final boolean[] overwrite = new boolean[1];
        final boolean[] decisionMade = new boolean[1];

        new Handler(Looper.getMainLooper()).post(() -> new CustomAlertDialog(context)
                .setTitleText(context.getString(R.string.overwrite_file_title))
                .setMessage(context.getString(R.string.overwrite_file_message, modName))
                .setPositiveButton(context.getString(R.string.overwrite), dlg -> {
                    synchronized (decisionLock) {
                        overwrite[0] = true;
                        decisionMade[0] = true;
                        decisionLock.notifyAll();
                    }
                })
                .setNegativeButton(context.getString(R.string.skip), dlg -> {
                    synchronized (decisionLock) {
                        overwrite[0] = false;
                        decisionMade[0] = true;
                        decisionLock.notifyAll();
                    }
                })
                .show());

        synchronized (decisionLock) {
            while (!decisionMade[0]) {
                try {
                    decisionLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Overwrite confirmation interrupted", e);
                    return false;
                }
            }
        }

        return overwrite[0];
    }

    private void copyUriToFile(Uri source, File destination) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) {
                throw new IOException("Cannot open input file");
            }
            copyStreamToFile(in, destination);
        }
    }

    private void extractZip(File zipFile, File targetDir) throws IOException {
        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];

            while ((entry = zis.getNextEntry()) != null) {
                String normalizedName = normalizeZipEntryName(entry.getName());
                if (normalizedName.isEmpty()) {
                    continue;
                }

                File entryFile = new File(targetDir, normalizedName);
                String targetCanonicalPath = targetDir.getCanonicalPath();
                String entryCanonicalPath = entryFile.getCanonicalPath();
                if (!entryCanonicalPath.startsWith(targetCanonicalPath + File.separator)
                        && !entryCanonicalPath.equals(targetCanonicalPath)) {
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!entryFile.exists() && !entryFile.mkdirs()) {
                        throw new IOException("Failed to create directory: " + entryFile.getAbsolutePath());
                    }
                    continue;
                }

                File parent = entryFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
                }

                try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    private String normalizeZipEntryName(String name) {
        if (name == null) {
            return "";
        }

        String normalized = name.trim().replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private void copyStreamToFile(InputStream input, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (OutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void copyDirectory(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Failed to create directory: " + target.getAbsolutePath());
            }

            File[] files = source.listFiles();
            if (files == null) {
                return;
            }

            for (File file : files) {
                copyDirectory(file, new File(target, file.getName()));
            }
            return;
        }

        try (FileInputStream fis = new FileInputStream(source)) {
            copyStreamToFile(fis, target);
        }
    }

    private void copyDirectoryPreservingExisting(
            File source, File target, String entryPath, Set<String> overwriteFiles, Set<String> overwriteFolders) throws IOException {
        copyDirectoryPreservingExisting(
                source, source, target, normalizeEntryPath(entryPath), overwriteFiles, overwriteFolders);
    }

    private void copyDirectoryPreservingExisting(
            File root, File source, File target, String entryPath,
            Set<String> overwriteFiles, Set<String> overwriteFolders) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Failed to create directory: " + target.getAbsolutePath());
            }

            File[] files = source.listFiles();
            if (files == null) {
                return;
            }

            for (File file : files) {
                copyDirectoryPreservingExisting(
                        root, file, new File(target, file.getName()), entryPath, overwriteFiles, overwriteFolders);
            }
            return;
        }

        String relativePath = root.toPath().relativize(source.toPath()).toString().replace('\\', '/');
        boolean mayReplace = canReplaceImportedPath(relativePath, entryPath, overwriteFiles, overwriteFolders);
        if (target.exists() && !mayReplace) {
            return;
        }

        try (FileInputStream fis = new FileInputStream(source)) {
            copyStreamToFile(fis, target);
        }
    }

    private File createTempDirectory(String prefix) throws IOException {
        File cacheDir = context.getCacheDir();
        for (int attempt = 0; attempt < 10; attempt++) {
            File dir = new File(cacheDir, prefix + "_" + System.currentTimeMillis() + "_" + attempt);
            if (dir.mkdirs()) {
                return dir;
            }
        }
        throw new IOException("Failed to create temporary directory");
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private void postProgress(FileOperationCallback callback, int completed, int total) {
        if (callback == null || total <= 0) {
            return;
        }
        int progress = Math.min(100, (completed * 100) / total);
        new Handler(Looper.getMainLooper()).post(() -> callback.onProgressUpdate(progress));
    }

    private void postError(FileOperationCallback callback, String message) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(message));
    }

    private boolean isSupportedImportFile(Uri uri, String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".so") || isMcmodFile(fileName) || isZipModPackageFile(uri, fileName);
    }

    private String getStringProperty(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private String resolveImportFileName(Uri uri) {
        String fileName = resolveFileName(uri);
        if (isZipModPackageFile(fileName) || hasSupportedPlainExtension(fileName)) {
            return fileName;
        }
        if (isZipMimeType(uri) || hasZipHeader(uri)) {
            return FALLBACK_ZIP_FILE_NAME;
        }
        return fileName;
    }

    private boolean hasSupportedPlainExtension(String fileName) {
        if (fileName == null) {
            return false;
        }

        return fileName.toLowerCase(Locale.ROOT).endsWith(".so") || isMcmodFile(fileName);
    }

    private boolean isZipMimeType(Uri uri) {
        String mimeType;
        try {
            mimeType = context.getContentResolver().getType(uri);
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve MIME type", e);
            return false;
        }
        if (mimeType == null) {
            return false;
        }

        String lowerMimeType = mimeType.toLowerCase(Locale.ROOT);
        return "application/zip".equals(lowerMimeType)
                || "application/x-zip".equals(lowerMimeType)
                || "application/x-zip-compressed".equals(lowerMimeType);
    }

    private boolean hasZipHeader(Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return false;
            }

            byte[] header = new byte[4];
            int read = input.read(header);
            if (read < header.length) {
                return false;
            }

            return header[0] == 0x50
                    && header[1] == 0x4B
                    && (header[2] == 0x03 || header[2] == 0x05 || header[2] == 0x07)
                    && (header[3] == 0x04 || header[3] == 0x06 || header[3] == 0x08);
        } catch (Exception e) {
            Log.w(TAG, "Failed to inspect zip header", e);
            return false;
        }
    }

    private List<Uri> extractFileUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null) return uris;

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                uris.add(clipData.getItemAt(i).getUri());
            }
        } else if (intent.getData() != null) {
            uris.add(intent.getData());
        }
        return uris;
    }

    private String resolveFileName(Uri uri) {
        String defaultName = "unknown_" + System.currentTimeMillis();
        if (uri == null) {
            return defaultName;
        }

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                return new File(path).getName();
            }
        }

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve display name", e);
        }
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        String displayName = cursor.getString(nameIndex);
                        if (displayName != null && !displayName.isEmpty()) {
                            return displayName;
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        }

        String path = uri.getPath();
        if (path != null) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
        }

        String lastSegment = uri.getLastPathSegment();
        return lastSegment != null && !lastSegment.isEmpty() ? lastSegment : defaultName;
    }

    private boolean createDirectoryIfNeeded(File dir) {
        return dir.exists() || dir.mkdirs();
    }
}
