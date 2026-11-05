package org.levimc.launcher.core.content;

import android.util.Log;

import org.levimc.launcher.core.content.nbt.BedrockNbtReader;
import org.levimc.launcher.core.content.nbt.NbtTag;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class WorldItem extends ContentItem {
    private static final String TAG = "WorldItem";

    private String worldName;
    private String gameMode;
    private long lastPlayed;
    private boolean isValid;

    public WorldItem(String name, File worldDir) {
        super(name, worldDir);
        this.worldName = name;
        loadWorldInfo();
    }

    WorldItem(WorldItem source) {
        super(source.name, source.file);
        identityPath = source.getStablePath();
        worldName = source.worldName;
        gameMode = source.gameMode;
        lastPlayed = source.lastPlayed;
        isValid = source.isValid;
    }

    @Override
    public String getType() {
        return "World";
    }

    @Override
    public String getDescription() {
        if (!isValid) return "Invalid world";
        return String.format("Game Mode: %s", gameMode != null ? gameMode : "Unknown");
    }

    @Override
    public boolean isValid() {
        return isValid;
    }

    public String getWorldName() {
        return worldName;
    }

    public String getGameMode() {
        return gameMode != null ? gameMode : "Unknown";
    }

    public File getIconFile() {
        if (file == null) return null;
        String[] names = {"world_icon.jpeg", "world_icon.jpg", "world_icon.png"};
        for (String name : names) {
            File icon = new File(file, name);
            if (icon.isFile()) return icon;
        }
        return null;
    }

    private void loadWorldInfo() {
        if (file == null || !file.exists() || !file.isDirectory()) {
            isValid = false;
            return;
        }

        File levelDat = new File(file, "level.dat");
        File levelNameFile = new File(file, "levelname.txt");

        if (!levelDat.exists()) {
            isValid = false;
            return;
        }

        isValid = true;

        if (levelNameFile.exists()) {
            try (FileInputStream fis = new FileInputStream(levelNameFile)) {
                byte[] data = new byte[(int) levelNameFile.length()];
                new java.io.DataInputStream(fis).readFully(data);
                worldName = new String(data, StandardCharsets.UTF_8).trim();
                if (!worldName.isEmpty()) {
                    this.name = worldName;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read levelname.txt for " + file.getName(), e);
            }
        }

        try {
            boolean needName = worldName == null || worldName.isEmpty() || worldName.equals(file.getName());
            WorldMetadataReader.Summary summary = WorldMetadataReader.read(levelDat, needName);
            if (summary.gameType != null) gameMode = getGameModeName(summary.gameType);
            if (needName && summary.levelName != null && !summary.levelName.isEmpty()) {
                worldName = summary.levelName;
                name = worldName;
            }
        } catch (IOException metadataError) {
            loadFullWorldInfo(levelDat);
        }

        if (gameMode == null) {
            gameMode = "Survival";
        }

        lastPlayed = file.lastModified();
    }

    private void loadFullWorldInfo(File levelDat) {
        try {
            NbtTag root = new BedrockNbtReader().readFile(levelDat);
            if (root == null || root.getType() != NbtTag.TAG_COMPOUND) return;
            Map<String, NbtTag> compound = root.getCompound();
            NbtTag mode = compound.get("GameType");
            if (mode != null) gameMode = getGameModeName(mode.getInt());
            if (worldName == null || worldName.isEmpty() || worldName.equals(file.getName())) {
                NbtTag levelName = compound.get("LevelName");
                if (levelName != null && levelName.getType() == NbtTag.TAG_STRING
                        && levelName.getString() != null && !levelName.getString().isEmpty()) {
                    worldName = levelName.getString();
                    name = worldName;
                }
            }
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "Failed to read level.dat for " + file.getName(), error);
        }
    }

    private String getGameModeName(int gameType) {
        return switch (gameType) {
            case 0 -> "Survival";
            case 1 -> "Creative";
            case 2 -> "Adventure";
            case 3 -> "Spectator";
            default -> "Unknown";
        };
    }
}