package org.levimc.launcher.core.content;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContentImporter {
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface ImportCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(int progress);
    }

    public static class ImportResult {
        public int resourcePacksImported;
        public int behaviorPacksImported;
        public int skinPacksImported;
        public int worldsImported;
    }

    public ContentImporter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void importContent(List<Uri> uris, File resources, File behaviors,
                               File skins, File worlds, ImportCallback callback) {
        if (executor.isShutdown()) {
            callback.onError("Content importer is closed");
            return;
        }
        List<Uri> sources = new java.util.ArrayList<>(uris);
        executor.execute(() -> {
            ContentArchive.Result total = new ContentArchive.Result();
            int count = Math.max(1, sources.size());
            for (int i = 0; i < sources.size(); i++) {
                Uri uri = sources.get(i);
                final int index = i;
                try {
                    total.add(ContentArchive.importUri(context, uri, worlds, resources, behaviors, skins,
                            false, value -> callback.onProgress((index * 100 + value) / count)));
                } catch (Exception error) {
                    total.warnings.add(uri.getLastPathSegment() + ": " + error.getMessage());
                }
            }
            if (total.total() == 0) {
                callback.onError(total.warnings.isEmpty() ? "No content was imported" : String.join("\n", total.warnings));
                return;
            }
            StringBuilder message = new StringBuilder("Imported:");
            if (total.worlds > 0) message.append(" Worlds: ").append(total.worlds);
            if (total.resources > 0) message.append(" Resource Packs: ").append(total.resources);
            if (total.behaviors > 0) message.append(" Behavior Packs: ").append(total.behaviors);
            if (total.skins > 0) message.append(" Skin Packs: ").append(total.skins);
            if (!total.warnings.isEmpty()) message.append("\nErrors:\n").append(String.join("\n", total.warnings));
            callback.onProgress(100);
            callback.onSuccess(message.toString());
        });
    }

    public void shutdown() { executor.shutdown(); }
}
