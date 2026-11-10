package org.levimc.launcher.core.packages.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;

/**
 * Userspace (userspace 1-3): the config directory with {@code config.toml} (its one key
 * {@code github_proxy}; compatibility 4 migrates a {@code liprc.json}) and the cache directory.
 * On Android both are directories of the app given by it.
 */
public final class Config {
    static final String FILE = "config.toml";
    static final String LEGACY_FILE = "liprc.json";

    private final File configDir, cacheDir;

    public Config(File configDir, File cacheDir) {
        this.configDir = configDir;
        this.cacheDir = cacheDir;
    }

    public File cacheDir() {
        return cacheDir;
    }

    /** The configured GitHub proxy URI prefix, or null when none (also when the file is unusable). */
    public synchronized String githubProxy() {
        File file = new File(configDir, FILE);
        try {
            if (!file.exists()) migrateLegacy();
            JsonObject o = TextFormats.parseToml(Files.readAllBytes(file.toPath()));
            JsonElement proxy = o.get("github_proxy");
            return Manifest.isNumber(o.get("format"), 4) && proxy != null && proxy.isJsonPrimitive()
                    && proxy.getAsJsonPrimitive().isString() && isUri(proxy.getAsString()) ? proxy.getAsString() : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Sets {@code github_proxy}; it must be an absolute https URI. */
    public synchronized void setGithubProxy(String uri) throws PackageException {
        if (uri == null || !uri.startsWith("https://") || !isUri(uri)) {
            throw new PackageException(PackageException.Kind.INVALID_ARGUMENT, uri, "'" + uri + "' is not an absolute https:// URI");
        }
        write(uri);
    }

    /** Resets {@code github_proxy} to its default (none). */
    public synchronized void deleteGithubProxy() throws PackageException {
        write(null);
    }

    private void write(String proxy) throws PackageException {
        JsonObject o = new JsonObject();
        o.addProperty("format", 4);
        if (proxy != null) o.addProperty("github_proxy", proxy);
        try {
            if (!configDir.isDirectory() && !configDir.mkdirs()) throw new IOException("cannot create " + configDir);
            TextFormats.writeAtomically(new File(configDir, FILE), TextFormats.writeToml(o));
            Files.deleteIfExists(new File(configDir, LEGACY_FILE).toPath());
        } catch (IOException e) {
            throw new PackageException(PackageException.Kind.IO, null, "Cannot write " + FILE + ": " + e.getMessage(), e);
        }
    }

    /** compatibility 4: a usable liprc.json becomes config.toml and is deleted. */
    private void migrateLegacy() throws IOException {
        File legacy = new File(configDir, LEGACY_FILE);
        if (!legacy.isFile()) return;
        JsonObject o;
        try {
            JsonElement v = TextFormats.parseJson(Files.readAllBytes(legacy.toPath()));
            if (!v.isJsonObject()) return;
            o = v.getAsJsonObject();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (o.has("format_version") && !Manifest.isNumber(o.get("format_version"), 3)) return;
        JsonElement proxy = o.get("github_proxy");
        JsonObject config = new JsonObject();
        config.addProperty("format", 4);
        if (proxy != null && proxy.isJsonPrimitive() && proxy.getAsJsonPrimitive().isString()) config.add("github_proxy", proxy);
        TextFormats.writeAtomically(new File(configDir, FILE), TextFormats.writeToml(config));
        Files.deleteIfExists(legacy.toPath());
    }

    private static boolean isUri(String s) {
        try {
            return new URI(s).isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Deletes the whole cache directory (cli 5.9). */
    public void cacheClean() throws PackageException {
        if (!delete(cacheDir)) {
            throw new PackageException(PackageException.Kind.IO, null, "Cannot delete the cache directory " + cacheDir);
        }
    }

    /** Bytes of the regular files in the cache directory. */
    public long cacheSize() {
        return size(cacheDir);
    }

    /** Deletes f and everything below it, not following symbolic links; true when nothing is left. */
    public static boolean delete(File f) {
        File[] children = Files.isSymbolicLink(f.toPath()) ? null : f.listFiles();
        boolean ok = true;
        if (children != null) for (File c : children) ok &= delete(c);
        return ok & (f.delete() || !f.exists());
    }

    private static long size(File f) {
        File[] children = f.listFiles();
        if (children == null) return f.isFile() ? f.length() : 0;
        long n = 0;
        for (File c : children) n += size(c);
        return n;
    }
}
