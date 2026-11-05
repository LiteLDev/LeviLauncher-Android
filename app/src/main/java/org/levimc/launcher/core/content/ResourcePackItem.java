package org.levimc.launcher.core.content;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ResourcePackItem extends ContentItem {
    private static final String TAG = "ResourcePackItem";
    
    public enum PackType {
        RESOURCE_PACK,
        BEHAVIOR_PACK,
        SKIN_PACK,
        ADDON
    }
    
    private String rawPackName;
    private String rawDescription;
    private String version;
    private PackType packType;
    private boolean isValid;
    private String uuid;
    private String localizationLocale;
    private File localizationFile;
    private long localizationModified;
    private long localizationLength;
    private long localizationDirectoryModified;
    private Map<String, String> localizationStrings;

    public ResourcePackItem(String name, File packFile, PackType packType) {
        super(name, packFile);
        this.packType = packType;
        this.rawPackName = name;
        loadPackInfo();
    }

    ResourcePackItem(ResourcePackItem source) {
        super(source.name, source.file);
        identityPath = source.getStablePath();
        rawPackName = source.rawPackName;
        rawDescription = source.rawDescription;
        version = source.version;
        packType = source.packType;
        isValid = source.isValid;
        uuid = source.uuid;
        localizationLocale = source.localizationLocale;
        localizationFile = source.localizationFile;
        localizationModified = source.localizationModified;
        localizationLength = source.localizationLength;
        localizationDirectoryModified = source.localizationDirectoryModified;
        localizationStrings = source.localizationStrings;
    }

    void prepareDisplayStrings() {
        if (isLocalizationKey(rawPackName) || isLocalizationKey(rawDescription)) loadLangStrings();
    }

    @Override
    public String getType() {
        switch (packType) {
            case RESOURCE_PACK:
                return "Resource Pack";
            case BEHAVIOR_PACK:
                return "Behavior Pack";
            case SKIN_PACK:
                return "Skin Pack";
            case ADDON:
                return "Add-On";
            default:
                return "Pack";
        }
    }

    @Override
    public String getDescription() {
        if (!isValid) return "Invalid pack";
        String resolved = resolveLocalizedString(rawDescription);
        if (resolved != null && !resolved.isEmpty()) {
            return resolved;
        }
        return String.format("Version: %s", version != null ? version : "Unknown");
    }

    @Override
    public boolean isValid() {
        return isValid;
    }

    public String getPackName() {
        String resolved = resolveLocalizedString(rawPackName);
        if (resolved != null && !resolved.isEmpty() && !resolved.equals(rawPackName)) {
            return resolved;
        }
        if (isLocalizationKey(rawPackName)) {
            return file.getName();
        }
        return rawPackName;
    }

    @Override
    public String getName() {
        return getPackName();
    }

    public String getVersion() {
        return version;
    }

    public File getIconFile() {
        if (file == null) return null;
        File icon = new File(file, "pack_icon.png");
        return icon.isFile() ? icon : null;
    }

    private void loadPackInfo() {
        if (file == null || !file.exists()) {
            isValid = false;
            return;
        }

        if (file.isDirectory()) {
            loadPackInfoFromDirectory();
        } else {
            isValid = false;
        }
    }

    private void loadPackInfoFromDirectory() {
        File manifest = new File(file, "manifest.json");
        if (!manifest.exists()) {
            isValid = false;
            return;
        }

        try (FileInputStream fis = new FileInputStream(manifest)) {
            byte[] data = new byte[(int) manifest.length()];
            new java.io.DataInputStream(fis).readFully(data);
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1);
            parseManifest(ContentFiles.withoutComments(jsonStr));
        } catch (IOException e) {
            Log.e(TAG, "Failed to read manifest.json from directory", e);
            isValid = false;
        }
    }

    private synchronized Map<String, String> loadLangStrings() {
        String localeKey = Locale.getDefault().toLanguageTag();
        long directoryModified = new File(file, "texts").lastModified();
        if (directoryModified == localizationDirectoryModified && localeKey.equals(localizationLocale) && localizationStrings != null
                && (localizationFile == null || (localizationFile.lastModified() == localizationModified
                    && localizationFile.length() == localizationLength))) return localizationStrings;
        localizationLocale = localeKey;
        localizationDirectoryModified = directoryModified;
        localizationFile = null;
        localizationStrings = new HashMap<>();
        Map<String, String> langStrings = localizationStrings;
        
        File textsDir = new File(file, "texts");
        if (!textsDir.exists() || !textsDir.isDirectory()) {
            return langStrings;
        }

        Locale locale = Locale.getDefault();
        String systemLang = locale.getLanguage().toLowerCase();
        String systemCountry = locale.getCountry();
        
        if (systemCountry == null || systemCountry.isEmpty()) {
            systemCountry = systemLang.toUpperCase();
        } else {
            systemCountry = systemCountry.toUpperCase();
        }
        
        String fullLocale = systemLang + "_" + systemCountry;
        
        File[] allLangFiles = textsDir.listFiles((dir, name) -> name.endsWith(".lang"));

        String[] langPriority = {
            fullLocale,
            systemLang + "_" + systemCountry.toUpperCase(),
            systemLang.toUpperCase() + "_" + systemCountry,
            systemLang,
            "en_US",
            "en_GB",
            "en"
        };

        for (String langCode : langPriority) {
            File langFile = new File(textsDir, langCode + ".lang");
            if (langFile.exists()) {
                parseLangFile(langFile, langStrings);
                if (!langStrings.isEmpty()) {
                    return langStrings;
                }
            }
        }
        
        if (allLangFiles != null) {
            for (String langCode : langPriority) {
                for (File f : allLangFiles) {
                    String fileName = f.getName().replace(".lang", "");
                    if (fileName.equalsIgnoreCase(langCode)) {
                        parseLangFile(f, langStrings);
                        if (!langStrings.isEmpty()) {
                            return langStrings;
                        }
                    }
                }
            }

            for (File f : allLangFiles) {
                String fileName = f.getName().replace(".lang", "").toLowerCase();
                if (fileName.startsWith(systemLang + "_") || fileName.equals(systemLang)) {
                    parseLangFile(f, langStrings);
                    if (!langStrings.isEmpty()) {
                        return langStrings;
                    }
                }
            }

            for (File f : allLangFiles) {
                String fileName = f.getName().toLowerCase();
                if (fileName.startsWith("en")) {
                    parseLangFile(f, langStrings);
                    if (!langStrings.isEmpty()) {
                        return langStrings;
                    }
                }
            }
            
            if (allLangFiles.length > 0) {
                parseLangFile(allLangFiles[0], langStrings);
            }
        }
        
        return langStrings;
    }

    private void parseLangFile(File langFile, Map<String, String> langStrings) {
        localizationFile = langFile;
        localizationModified = langFile.lastModified();
        localizationLength = langFile.length();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(langFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex > 0) {
                    String key = line.substring(0, equalsIndex).trim();
                    String value = equalsIndex < line.length() - 1 
                            ? line.substring(equalsIndex + 1).trim() 
                            : "";
                    langStrings.put(key, value);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to parse lang file: " + langFile.getName(), e);
        }
    }

    private String resolveLocalizedString(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (!isLocalizationKey(value)) {
            return value;
        }
        Map<String, String> langStrings = localizationStrings;
        if (langStrings == null || !Locale.getDefault().toLanguageTag().equals(localizationLocale)) {
            langStrings = loadLangStrings();
        }
        if (langStrings.containsKey(value)) {
            return langStrings.get(value);
        }
        return value;
    }

    private boolean isLocalizationKey(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return value.startsWith("pack.") || 
               (value.contains(".") && !value.contains(" ") && value.matches("^[a-zA-Z0-9_.]+$"));
    }

    private void parseManifest(String jsonStr) {
        try {
            JSONObject manifest = new JSONObject(jsonStr);

            if (manifest.has("header")) {
                JSONObject header = manifest.getJSONObject("header");
                
                if (header.has("name")) {
                    rawPackName = header.getString("name");
                }
                
                if (header.has("description")) {
                    rawDescription = header.getString("description");
                }
                
                if (header.has("version")) {
                    Object versionObj = header.get("version");
                    if (versionObj instanceof String) {
                        version = (String) versionObj;
                    } else if (versionObj instanceof org.json.JSONArray) {
                        org.json.JSONArray versionArray = (org.json.JSONArray) versionObj;
                        StringBuilder versionBuilder = new StringBuilder();
                        for (int i = 0; i < versionArray.length(); i++) {
                            if (i > 0) versionBuilder.append(".");
                            versionBuilder.append(versionArray.getInt(i));
                        }
                        version = versionBuilder.toString();
                    }
                }
                
                if (header.has("uuid")) {
                    uuid = header.getString("uuid");
                }
            }

            if (manifest.has("modules")) {
                org.json.JSONArray modules = manifest.getJSONArray("modules");
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject module = modules.getJSONObject(i);
                    if (module.has("type")) {
                        String moduleType = module.getString("type");
                        if ("resources".equals(moduleType)) {
                            packType = PackType.RESOURCE_PACK;
                        } else if ("data".equals(moduleType) || "script".equals(moduleType)) {
                            packType = PackType.BEHAVIOR_PACK;
                        } else if ("skin_pack".equals(moduleType)) {
                            packType = PackType.SKIN_PACK;
                        }
                    }
                }
            }
            
            isValid = true;
            
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse manifest.json", e);
            isValid = false;
        }
    }

    public boolean isResourcePack() {
        return packType == PackType.RESOURCE_PACK;
    }

    public boolean isBehaviorPack() {
        return packType == PackType.BEHAVIOR_PACK;
    }

    public boolean isSkinPack() {
        return packType == PackType.SKIN_PACK;
    }
}
