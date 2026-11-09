package org.levimc.launcher.core.mods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileHandlerTest {
    @Test
    public void parseOverwriteFilesNormalizesSafeRelativeFiles() {
        JsonObject manifest = new JsonObject();
        JsonArray files = new JsonArray();
        files.add("assets/icon.png");
        files.add("config\\schema.json");
        files.add("../escape.json");
        files.add("/absolute.json");
        files.add(".");
        files.add("");
        manifest.add("overwrite_files", files);

        Set<String> expected = new HashSet<>(Arrays.asList(
                "assets/icon.png",
                "config/schema.json"));
        assertEquals(expected, FileHandler.parseOverwriteFiles(manifest));
    }

    @Test
    public void parseOverwriteFoldersNormalizesSafeRelativeDirectories() {
        JsonObject manifest = new JsonObject();
        JsonArray folders = new JsonArray();
        folders.add("assets");
        folders.add("config/schema/");
        folders.add("nested\\folder");
        folders.add("../escape");
        folders.add("/absolute");
        folders.add(".");
        folders.add("");
        manifest.add("overwrite_folders", folders);

        Set<String> expected = new HashSet<>(Arrays.asList(
                "assets",
                "config/schema",
                "nested/folder"));
        assertEquals(expected, FileHandler.parseOverwriteFolders(manifest));
    }

    @Test
    public void parseOverwriteFoldersAcceptsSingleString() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("overwrite_folders", "assets");

        assertEquals(
                new HashSet<>(Arrays.asList("assets")),
                FileHandler.parseOverwriteFolders(manifest));
    }

    @Test
    public void canReplaceImportedPathAllowsManifestEntryAndWhitelistedFolderContents() {
        Set<String> overwriteFiles = new HashSet<>(Arrays.asList("assets/icon.png", "config/config.json"));
        Set<String> overwriteFolders = new HashSet<>(Arrays.asList("assets", "config/schema"));

        assertTrue(FileHandler.canReplaceImportedPath("manifest.json", "libmod.so", overwriteFiles, overwriteFolders));
        assertTrue(FileHandler.canReplaceImportedPath("libmod.so", "libmod.so", overwriteFiles, overwriteFolders));
        assertTrue(FileHandler.canReplaceImportedPath("assets/icon.png", "libmod.so", overwriteFiles, overwriteFolders));
        assertTrue(FileHandler.canReplaceImportedPath("config/config.json", "libmod.so", overwriteFiles, overwriteFolders));
        assertTrue(FileHandler.canReplaceImportedPath("assets/textures/icon.png", "libmod.so", overwriteFiles, overwriteFolders));
        assertTrue(FileHandler.canReplaceImportedPath("config/schema/options.json", "libmod.so", overwriteFiles, overwriteFolders));

        assertFalse(FileHandler.canReplaceImportedPath("assets", "libmod.so", overwriteFiles, overwriteFolders));
        assertFalse(FileHandler.canReplaceImportedPath("assets2/textures/icon.png", "libmod.so", overwriteFiles, overwriteFolders));
        assertFalse(FileHandler.canReplaceImportedPath("config/other.json", "libmod.so", overwriteFiles, overwriteFolders));
        assertFalse(FileHandler.canReplaceImportedPath("../outside", "libmod.so", overwriteFiles, overwriteFolders));
    }

    private static final String MCMOD_MANIFEST =
            "{\"name\":\"ExampleMod\",\"type\":\"preload-native\",\"entry\":\"libexample.so\",\"overwrite_files\":[\"data/a.json\"]}";

    /** A zip of name/content pairs; a name ending in '/' is a directory entry. */
    private static ByteArrayInputStream zip(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private static String mcmodProblem(String... entries) throws IOException {
        return FileHandler.checkMcmod(zip(entries)).problem;
    }

    @Test
    public void mcmodIsTheModFolderNamedByItsManifest() throws IOException {
        FileHandler.McmodCheck check = FileHandler.checkMcmod(zip(
                "manifest.json", MCMOD_MANIFEST,
                "libexample.so", "elf",
                "data/", "",
                "data/a.json", "{}"));
        assertNull(check.problem);
        assertEquals("ExampleMod", check.modName);
        assertEquals("libexample.so", check.entry);
        assertEquals(Collections.singleton("data/a.json"), FileHandler.parseOverwriteFiles(check.manifest));
    }

    @Test
    public void mcmodRecognizedByExtensionOnly() {
        assertTrue(FileHandler.isMcmodFile("Example.MCMOD"));
        assertFalse(FileHandler.isMcmodFile("example.mcmod.zip"));
        assertFalse(FileHandler.isMcmodFile(null));
    }

    @Test
    public void mcmodNeedsItsManifestAtTheRoot() throws IOException {
        assertNotNull(mcmodProblem("ExampleMod/manifest.json", MCMOD_MANIFEST, "ExampleMod/libexample.so", "elf"));
        assertNotNull(mcmodProblem("libexample.so", "elf"));
        assertNotNull(mcmodProblem("manifest.json", "[1]", "libexample.so", "elf"));
    }

    @Test
    public void mcmodManifestMustBeLoadable() throws IOException {
        assertNotNull(mcmodProblem("manifest.json", MCMOD_MANIFEST.replace("ExampleMod", "../x"), "libexample.so", "elf"));
        assertNotNull(mcmodProblem("manifest.json", MCMOD_MANIFEST.replace("ExampleMod", ".hidden"), "libexample.so", "elf"));
        assertNotNull(mcmodProblem("manifest.json", MCMOD_MANIFEST.replace("preload-native", "native"), "libexample.so", "elf"));
        // A Windows-only build: the entry is not a .so, or not in the archive.
        assertNotNull(mcmodProblem("manifest.json", MCMOD_MANIFEST.replace("libexample.so", "Example.dll"), "Example.dll", "pe"));
        assertNotNull(mcmodProblem("manifest.json", MCMOD_MANIFEST, "libother.so", "elf"));
    }

    @Test
    public void mcmodRefusesUnsafePathsAndNonZips() throws IOException {
        assertNotNull(mcmodProblem("manifest.json", MCMOD_MANIFEST, "libexample.so", "elf", "../evil.so", "x"));
        assertNotNull(FileHandler.checkMcmod(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8))).problem);
    }
}
