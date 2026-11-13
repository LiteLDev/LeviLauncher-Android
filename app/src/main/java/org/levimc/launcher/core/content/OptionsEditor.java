package org.levimc.launcher.core.content;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OptionsEditor {
    private static final String TAG = "OptionsEditor";

    private final File optionsFile;
    private final Map<String, String> options = new LinkedHashMap<>();
    private boolean loaded = false;

    public OptionsEditor(File optionsFile) {
        this.optionsFile = optionsFile;
    }

    public void load() throws IOException {
        options.clear();
        
        if (!optionsFile.exists()) {
            loaded = true;
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(optionsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = colonIndex < line.length() - 1 
                            ? line.substring(colonIndex + 1).trim() 
                            : "";
                    options.put(key, value);
                }
            }
        }
        
        loaded = true;
        Log.d(TAG, "Loaded " + options.size() + " options from " + optionsFile.getAbsolutePath());
    }

    public void save() throws IOException {
        save(getOptions());
    }

    public void save(Map<String, String> values) throws IOException {
        File parentDir = optionsFile.getAbsoluteFile().getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        File backup = new File(optionsFile.getParentFile(), "options.txt.backup");
        if (optionsFile.exists()) {
            copyFile(optionsFile, backup);
        }

        File pending = File.createTempFile(".options-", ".tmp", parentDir);
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(pending), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : values.entrySet()) writer.write(entry.getKey() + ":" + entry.getValue() + "\n");
            }
            try {
                Files.move(pending.toPath(), optionsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending.toPath(), optionsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (pending.exists()) pending.delete();
        }
        
        Log.d(TAG, "Saved " + options.size() + " options to " + optionsFile.getAbsolutePath());
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean hasOptionsFile() {
        return optionsFile.exists();
    }

    public Map<String, String> getOptions() {
        return new LinkedHashMap<>(options);
    }

    public String getValue(String key) {
        return options.get(key);
    }

    public void setValue(String key, String value) {
        options.put(key, value);
    }

    public List<OptionProperty> getOptionProperties() {
        List<OptionProperty> properties = new ArrayList<>();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            properties.add(new OptionProperty(entry.getKey(), entry.getValue()));
        }
        return properties;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    public static class OptionProperty {
        private final String key;
        private String value;
        private final String category;

        public OptionProperty(String key, String value) {
            this.key = key;
            this.value = value;
            this.category = categorize(key);
        }

        public String getKey() { return key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getCategory() { return category; }

        public String getDisplayName() {
            return formatName(key);
        }

        private String formatName(String name) {
            StringBuilder result = new StringBuilder();
            boolean capitalizeNext = true;
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '_') {
                    result.append(' ');
                    capitalizeNext = true;
                } else if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }

        private String categorize(String key) {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("gfx") || lower.contains("render") || lower.contains("graphics") 
                    || lower.contains("texture") || lower.contains("particle") || lower.contains("fancy")) {
                return "Graphics";
            }
            if (lower.contains("audio") || lower.contains("sound") || lower.contains("music") 
                    || lower.contains("volume")) {
                return "Audio";
            }
            if (lower.contains("control") || lower.contains("sensitivity") || lower.contains("invert") 
                    || lower.contains("button") || lower.contains("key")) {
                return "Controls";
            }
            if (lower.contains("gui") || lower.contains("ui") || lower.contains("hud") 
                    || lower.contains("screen") || lower.contains("safe")) {
                return "Interface";
            }
            if (lower.contains("game") || lower.contains("difficulty") || lower.contains("view") 
                    || lower.contains("fov") || lower.contains("distance")) {
                return "Game";
            }
            if (lower.contains("dev") || lower.contains("debug") || lower.contains("log")) {
                return "Developer";
            }
            if (lower.contains("server") || lower.contains("multiplayer") || lower.contains("realms") 
                    || lower.contains("online")) {
                return "Multiplayer";
            }
            if (lower.contains("vr") || lower.contains("3d")) {
                return "VR";
            }
            return "Other";
        }
    }
}
