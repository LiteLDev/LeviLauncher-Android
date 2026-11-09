package org.levimc.launcher.core.packages;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.levimc.launcher.core.packages.engine.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import okio.ByteString;

public final class LocalSources {
    public static final String DIR_NAME = "local";
    static final int HASH_DIGITS = 12;
    static final int MAX_DEPTH = 32;
    private static final int MAX_NAME_LENGTH = 80;

    private LocalSources() {
    }

    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    public interface Node {
        String name();

        boolean isDirectory();

        List<Node> children() throws IOException;

        InputStream open() throws IOException;
    }

    public static File localDir(File root) {
        return new File(root, DIR_NAME);
    }

    public static File importArchive(File root, String displayName, Opener opener) throws IOException {
        File base = localDir(root);
        File tmpDir = new File(base, ".tmp-" + UUID.randomUUID());
        if (!tmpDir.mkdirs()) {
            throw new IOException("cannot create " + tmpDir);
        }
        try {
            String name = archiveName(displayName);
            File tmp = new File(tmpDir, name);
            MessageDigest digest = sha256();
            try (InputStream in = opener.open(); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    throw new IOException("cannot open " + displayName);
                }
                copy(in, out, digest);
            }
            File dir = new File(base, hex(digest.digest()).substring(0, HASH_DIGITS));
            File dest = new File(dir, name);
            if (dest.isFile() && dest.length() == tmp.length()) {
                return dest;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } finally {
            deleteTree(tmpDir);
        }
    }

    public static File importDirectory(File root, Node folder) throws IOException {
        if (!folder.isDirectory()) {
            throw new IOException(folder.name() + " is not a folder");
        }
        File base = localDir(root);
        File tmpDir = new File(base, ".tmp-" + UUID.randomUUID());
        String name = folderName(folder.name());
        File tmp = new File(tmpDir, name);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create " + tmp);
        }
        try {
            MessageDigest digest = sha256();
            copyTree(folder, tmp, "", digest, 0);
            File dir = new File(base, hex(digest.digest()).substring(0, HASH_DIGITS));
            File dest = new File(dir, name);
            if (dest.isDirectory()) {
                // The same content was imported before under this name.
                return dest;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            Files.move(tmp.toPath(), dest.toPath());
            return dest;
        } finally {
            deleteTree(tmpDir);
        }
    }

    private static void copyTree(Node node, File dir, String rel, MessageDigest digest, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("the folder is nested too deeply: " + rel);
        }
        List<Node> children = new ArrayList<>(node.children());
        children.sort(Comparator.comparing(Node::name));
        for (Node child : children) {
            String childName = child.name();
            if (!isSafeEntryName(childName)) {
                throw new IOException("invalid file name in the folder: " + childName);
            }
            String childRel = rel.isEmpty() ? childName : rel + "/" + childName;
            File target = new File(dir, childName);
            if (child.isDirectory()) {
                digest.update(("D " + childRel + "\n").getBytes(StandardCharsets.UTF_8));
                if (!target.mkdirs()) {
                    throw new IOException("cannot create " + target);
                }
                copyTree(child, target, childRel, digest, depth + 1);
            } else {
                digest.update(("F " + childRel + "\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream in = child.open(); OutputStream out = new FileOutputStream(target)) {
                    if (in == null) {
                        throw new IOException("cannot open " + childRel);
                    }
                    copy(in, out, digest);
                }
                digest.update((byte) 0);
            }
        }
    }

    static boolean isSafeEntryName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    static String archiveName(String displayName) {
        String n = displayName == null ? "" : displayName.trim();
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            n = n.substring(0, n.length() - 4);
        }
        n = sanitize(n);
        if (n.isEmpty()) {
            n = "package";
        }
        return n + ".zip";
    }

    static String folderName(String displayName) {
        String n = sanitize(displayName);
        return n.isEmpty() ? "package" : n;
    }

    static String sanitize(String name) {
        String s = name == null ? "" : name.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            b.append(ok ? c : '_');
        }
        String out = b.toString();
        while (out.startsWith(".")) {
            out = out.substring(1);
        }
        if (out.length() > MAX_NAME_LENGTH) {
            out = out.substring(out.length() - MAX_NAME_LENGTH);
        }
        return out;
    }

    private static void copy(InputStream in, OutputStream out, MessageDigest digest) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            digest.update(buf, 0, n);
            out.write(buf, 0, n);
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return ByteString.of(bytes).hex();
    }

    private static void deleteTree(File f) {
        if (!Config.delete(f)) {
            PackageService.warn("cannot delete " + f);
        }
    }

    public static final class Saf {
        private Saf() {
        }

        public static File importDocument(Context context, Uri uri) throws IOException {
            ContentResolver resolver = context.getContentResolver();
            String name = displayName(resolver, uri, "package.zip");
            return LocalSources.importArchive(PackageService.get().dataDir(), name, () -> resolver.openInputStream(uri));
        }

        public static File importTree(Context context, Uri treeUri) throws IOException {
            ContentResolver resolver = context.getContentResolver();
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            String name = displayName(resolver, rootUri, "package");
            return LocalSources.importDirectory(PackageService.get().dataDir(),
                    new TreeNode(resolver, treeUri, rootId, name, true));
        }

        public static String displayName(Context context, Uri uri, String fallback) {
            return displayName(context.getContentResolver(), uri, fallback);
        }

        private static String displayName(ContentResolver resolver, Uri uri, String fallback) {
            try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) {
                    String n = c.getString(0);
                    if (n != null && !n.trim().isEmpty()) {
                        return n.trim();
                    }
                }
            } catch (RuntimeException ignored) {
                // A provider without the column: use the fallback.
            }
            String last = uri.getLastPathSegment();
            if (last != null) {
                int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf(':'));
                String n = last.substring(slash + 1).trim();
                if (!n.isEmpty()) {
                    return n;
                }
            }
            return fallback;
        }

        private static final class TreeNode implements LocalSources.Node {
            private final ContentResolver resolver;
            private final Uri treeUri;
            private final String documentId;
            private final String name;
            private final boolean directory;

            TreeNode(ContentResolver resolver, Uri treeUri, String documentId, String name, boolean directory) {
                this.resolver = resolver;
                this.treeUri = treeUri;
                this.documentId = documentId;
                this.name = name;
                this.directory = directory;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isDirectory() {
                return directory;
            }

            @Override
            public List<LocalSources.Node> children() throws IOException {
                List<LocalSources.Node> out = new ArrayList<>();
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
                String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
                try (Cursor c = resolver.query(children, columns, null, null, null)) {
                    if (c == null) {
                        throw new IOException("cannot list " + name);
                    }
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        String childName = c.getString(1);
                        String mime = c.getString(2);
                        if (id == null || childName == null) {
                            continue;
                        }
                        out.add(new TreeNode(resolver, treeUri, id, childName,
                                DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)));
                    }
                } catch (SecurityException | IllegalArgumentException e) {
                    throw new IOException("cannot list " + name + ": " + e.getMessage(), e);
                }
                return out;
            }

            @Override
            public InputStream open() throws IOException {
                try {
                    return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
                } catch (SecurityException | IllegalArgumentException e) {
                    throw new IOException("cannot read " + name + ": " + e.getMessage(), e);
                }
            }
        }
    }
}
