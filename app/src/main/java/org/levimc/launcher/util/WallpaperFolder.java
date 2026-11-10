package org.levimc.launcher.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Wallpaper folder, as on LeviLauncher for Windows: the wallpaper is picked from a folder, a new one
 * on every launch (random or sequential), and "Next image" switches right away.
 */
public final class WallpaperFolder {
    private WallpaperFolder() {
    }

    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"};
    private static boolean sRotatedThisLaunch;

    public static final class Image {
        public final String documentId;
        public final String name;
        public final Uri uri;

        Image(String documentId, String name, Uri uri) {
            this.documentId = documentId;
            this.name = name;
            this.uri = uri;
        }
    }

    /** Images directly in the folder, sorted naturally by name. Runs a content query; call off the UI thread. */
    public static List<Image> list(Context context, String treeUri) {
        List<Image> images = new ArrayList<>();
        if (treeUri == null) return images;
        try {
            Uri tree = Uri.parse(treeUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = context.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                    if (!isImage(name)) continue;
                    images.add(new Image(id, name, DocumentsContract.buildDocumentUriUsingTree(tree, id)));
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(images, (a, b) -> naturalCompare(a.name, b.name));
        return images;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) if (lower.endsWith(ext)) return true;
        return false;
    }

    /** Picks the next image for the play order; random avoids repeating the current one. */
    public static Image pick(List<Image> images, String currentId, String order) {
        if (images.isEmpty()) return null;
        int current = -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).documentId.equals(currentId)) current = i;
        }
        if (PersonalizationManager.ORDER_SEQUENTIAL.equals(order)) {
            return images.get((current + 1) % images.size());
        }
        if (images.size() == 1) return images.get(0);
        int next;
        Random random = new Random();
        do {
            next = random.nextInt(images.size());
        } while (next == current);
        return images.get(next);
    }

    /** Switches to the next folder image now. The callback runs on the main thread. */
    public static void next(Context context, Consumer<Boolean> callback) {
        Context app = context.getApplicationContext();
        PersonalizationManager pm = new PersonalizationManager(app);
        String folder = pm.getBackgroundFolder();
        if (folder == null) {
            callback.accept(false);
            return;
        }
        new Thread(() -> {
            List<Image> images = list(app, folder);
            Image image = pick(images, pm.getBackgroundFolderCurrent(), pm.getBackgroundPlayOrder());
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            if (image == null) {
                main.post(() -> callback.accept(false));
                return;
            }
            main.post(() -> LauncherBackgroundController.importBackground(context, image.uri, ok -> {
                if (ok) pm.setBackgroundFolderCurrent(image.documentId);
                callback.accept(ok);
            }));
        }, "WallpaperFolder").start();
    }

    /** Once per process: a new wallpaper from the folder, like the Windows launcher on every start. */
    public static void rotateOnLaunch(Context context) {
        if (sRotatedThisLaunch) return;
        sRotatedThisLaunch = true;
        if (new PersonalizationManager(context).getBackgroundFolder() == null) return;
        next(context, ok -> { });
    }

    public static int count(Context context, String treeUri) {
        return list(context, treeUri).size();
    }

    private static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
