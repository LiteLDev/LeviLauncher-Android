package org.levimc.launcher.core.packages.engine;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import okio.Okio;

/**
 * An archive (package 1): a tree of regular files named by '/'-separated paths from its root. A ZIP
 * archive ({@link #zip}), a directory archive ({@link #directory}) or a git archive ({@link Git}).
 */
abstract class Archive implements Closeable {
    private Set<String> fileSet;

    /** The regular files, in archive order. */
    abstract List<String> files();

    /** Opens the file {@code path}; it must be one of {@link #files()}. */
    abstract InputStream open(String path) throws IOException, PackageException;

    /** Makes the files available before they are opened (one request for a git archive). */
    void prefetch(Collection<String> paths) throws PackageException {
    }

    @Override
    public void close() {
    }

    final boolean isFile(String path) {
        if (fileSet == null) fileSet = new HashSet<>(files());
        return fileSet.contains(path);
    }

    /** The content of the file {@code path}, or null when the archive has no such file. */
    final byte[] read(String path) throws IOException, PackageException {
        if (!isFile(path)) return null;
        try (InputStream in = open(path)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        return Okio.buffer(Okio.source(in)).readByteArray();
    }

    static PackageException invalid(String what, String why) {
        return new PackageException(PackageException.Kind.INVALID_PACKAGE, null, what + " is not a valid archive: " + why);
    }

    /** The archive a local path names: a directory archive, or else a ZIP archive. */
    static Archive local(File f) throws PackageException {
        return f.isDirectory() ? directory(f) : zip(f);
    }

    /** A directory archive; links are not part of it. */
    static Archive directory(File root) throws PackageException {
        List<String> files = new ArrayList<>();
        walk(root, "", files);
        return new Archive() {
            @Override
            List<String> files() {
                return files;
            }

            @Override
            InputStream open(String path) throws IOException {
                return new FileInputStream(new File(root, path));
            }
        };
    }

    private static void walk(File dir, String prefix, List<String> out) throws PackageException {
        String[] names = dir.list();
        if (names == null) throw invalid(dir.getPath(), "the directory cannot be read");
        Arrays.sort(names);
        for (String name : names) {
            File f = new File(dir, name);
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (name.indexOf('\\') >= 0) throw invalid(dir.getPath(), "'" + prefix + name + "' contains '\\'");
            if (f.isDirectory()) walk(f, prefix + name + "/", out);
            else if (f.isFile()) out.add(prefix + name);
        }
    }

    /**
     * A ZIP archive, recognised by content. Its central directory is checked first: every entry a
     * regular file or directory, with a valid UTF-8 '/'-separated path (not empty, no leading '/',
     * no '..' segment, no '\').
     */
    static Archive zip(File file) throws PackageException {
        List<String> files;
        ZipFile zip = null;
        try {
            files = scan(file);
            zip = new ZipFile(file);
            for (String name : files) if (zip.getEntry(name) == null) throw new IOException("entry " + name + " not found");
        } catch (IOException | RuntimeException e) {
            if (zip != null) try {
                zip.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw invalid(file.getPath(), String.valueOf(e.getMessage()));
        }
        ZipFile z = zip;
        List<String> list = Collections.unmodifiableList(files);
        return new Archive() {
            @Override
            List<String> files() {
                return list;
            }

            @Override
            InputStream open(String path) throws IOException {
                ZipEntry e = z.getEntry(path);
                if (e == null) throw new IOException(path + " is not in " + file);
                return z.getInputStream(e);
            }

            @Override
            public void close() {
                try {
                    z.close();
                } catch (IOException ignored) {
                    // nothing was written
                }
            }
        };
    }

    /** Reads the central directory: the regular files, or IOException when the archive is invalid. */
    private static List<String> scan(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long len = f.length();
            byte[] head = new byte[4];
            if (len < 22) throw new IOException("not a ZIP file");
            f.readFully(head);
            if (le(head, 0, 4) != 0x04034b50 && le(head, 0, 4) != 0x06054b50) throw new IOException("not a ZIP file");
            byte[] tail = new byte[(int) Math.min(len, 65557)];
            f.seek(len - tail.length);
            f.readFully(tail);
            int e = tail.length - 22;
            while (e >= 0 && le(tail, e, 4) != 0x06054b50) e--;
            if (e < 0) throw new IOException("no end of central directory");
            long count = le(tail, e + 10, 2), size = le(tail, e + 12, 4), offset = le(tail, e + 16, 4);
            if ((count == 0xffff || size == 0xffffffffL || offset == 0xffffffffL) && e >= 20 && le(tail, e - 20, 4) == 0x07064b50) {
                byte[] z64 = new byte[56];
                f.seek(le(tail, e - 12, 8));
                f.readFully(z64);
                if (le(z64, 0, 4) != 0x06064b50) throw new IOException("bad ZIP64 end of central directory");
                count = le(z64, 32, 8);
                size = le(z64, 40, 8);
                offset = le(z64, 48, 8);
            }
            if (size > len || offset > len - size || size > Integer.MAX_VALUE) throw new IOException("bad central directory");
            byte[] cd = new byte[(int) size];
            f.seek(offset);
            f.readFully(cd);
            List<String> files = new ArrayList<>();
            int p = 0;
            for (long i = 0; i < count; i++) {
                if (p + 46 > cd.length || le(cd, p, 4) != 0x02014b50) throw new IOException("bad central directory entry");
                int nameLen = (int) le(cd, p + 28, 2), skip = (int) (le(cd, p + 30, 2) + le(cd, p + 32, 2));
                if (p + 46 + nameLen > cd.length) throw new IOException("bad central directory entry");
                String name = TextFormats.utf8(Arrays.copyOfRange(cd, p + 46, p + 46 + nameLen));
                long mode = le(cd, p + 5, 1) == 3 ? le(cd, p + 38, 4) >>> 16 & 0170000 : 0;
                if (mode != 0 && mode != 0100000 && mode != 0040000) throw new IOException("'" + name + "' is not a regular file or directory");
                if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || ("/" + name + "/").contains("/../")) {
                    throw new IOException("invalid entry path '" + name + "'");
                }
                if (!name.endsWith("/") && mode != 0040000) files.add(name);
                p += 46 + nameLen + skip;
            }
            return files;
        } catch (IllegalArgumentException e) {
            throw new IOException("an entry path is not UTF-8");
        }
    }

    /** The little-endian unsigned integer of {@code n} bytes at {@code at}. */
    private static long le(byte[] b, int at, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = v << 8 | (b[at + i] & 0xff);
        return v;
    }
}
