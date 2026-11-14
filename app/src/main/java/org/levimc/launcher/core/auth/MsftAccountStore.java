package org.levimc.launcher.core.auth;

import android.content.Context;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class MsftAccountStore {

    public static class MsftAccount {
        public String id;
        public String msUserId;
        public String xboxGamertag;
        public String minecraftUsername;
        public String xuid;
        public String xboxAvatarUrl;
        public long lastUpdated;
        public boolean active;
        public String serializedAuthManager;

        public MsftAccount() {}

        public MsftAccount(String id, String msUserId, String xboxGamertag, String minecraftUsername, String xuid, String xboxAvatarUrl, long lastUpdated, boolean active, String serializedAuthManager) {
            this.id = id;
            this.msUserId = msUserId;
            this.xboxGamertag = xboxGamertag;
            this.minecraftUsername = minecraftUsername;
            this.xuid = xuid;
            this.xboxAvatarUrl = xboxAvatarUrl;
            this.lastUpdated = lastUpdated;
            this.active = active;
            this.serializedAuthManager = serializedAuthManager;
        }
    }

    private static final String FILENAME = "Xal.Accounts.json";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<MsftAccount>>(){}.getType();

    private static File getFile(Context ctx) {
        File dir = new File(ctx.getApplicationContext().getFilesDir(), "xal");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILENAME);
    }

    public static synchronized List<MsftAccount> list(Context ctx) {
        File f = getFile(ctx);
        if (!f.exists() && !new File(f.getPath() + ".bak").exists()) return new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(new AtomicFile(f).openRead(), StandardCharsets.UTF_8)) {
            List<MsftAccount> list = GSON.fromJson(reader, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Exception ex) {
            Log.w("XALExport", "Failed to read " + f.getAbsolutePath(), ex);
            return new ArrayList<>();
        }
    }

    private static synchronized void save(Context ctx, List<MsftAccount> list) {
        AtomicFile file = new AtomicFile(getFile(ctx));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(GSON.toJson(list).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception ex) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("The Microsoft account could not be saved.", ex);
        }
    }

    public static synchronized MsftAccount getActive(Context ctx) {
        for (MsftAccount account : list(ctx)) {
            if (account.active) return account;
        }
        return null;
    }

    public static synchronized MsftAccount updateAuth(Context ctx, String id, String serializedAuthManager) {
        List<MsftAccount> accounts = list(ctx);
        for (MsftAccount account : accounts) {
            if (id.equals(account.id)) {
                account.serializedAuthManager = serializedAuthManager;
                account.lastUpdated = System.currentTimeMillis();
                save(ctx, accounts);
                return account;
            }
        }
        throw new IllegalStateException("The Microsoft account was removed. Select an account and try again.");
    }

    public static synchronized void exportActiveAccount(Context ctx, String id) {
        MsftAccount active = getActive(ctx);
        if (active == null || !id.equals(active.id)) {
            throw new IllegalStateException("The active Microsoft account changed. Launch Minecraft again.");
        }
        org.levimc.launcher.core.auth.storage.XalExporter.exportActiveAccountOrThrow(ctx);
    }

    public static synchronized MsftAccount addOrUpdate(Context ctx, String msUserId, String gamertag, String minecraftUsername, String xuid, String avatarUrl, String serializedAuthManager) {
        List<MsftAccount> list = list(ctx);
        MsftAccount target = null;
        for (MsftAccount a : list) {
            if (msUserId != null && msUserId.equals(a.msUserId)) {
                target = a; break;
            }
        }
        if (target == null) {
            target = new MsftAccount(UUID.randomUUID().toString(), msUserId, gamertag, minecraftUsername, xuid, avatarUrl, System.currentTimeMillis(), list.isEmpty(), serializedAuthManager);
            list.add(target);
        } else {
            if (!TextUtils.isEmpty(gamertag)) target.xboxGamertag = gamertag;
            if (!TextUtils.isEmpty(minecraftUsername)) target.minecraftUsername = minecraftUsername;
            if (!TextUtils.isEmpty(xuid)) target.xuid = xuid;
            if (!TextUtils.isEmpty(avatarUrl)) target.xboxAvatarUrl = avatarUrl;
            if (serializedAuthManager != null) target.serializedAuthManager = serializedAuthManager;
            target.lastUpdated = System.currentTimeMillis();
        }
        save(ctx, list);
        return target;
    }

    public static synchronized void remove(Context ctx, String id) {
        List<MsftAccount> list = list(ctx);
        String removedUserId = null;
        Iterator<MsftAccount> it = list.iterator();
        while (it.hasNext()) {
            MsftAccount a = it.next();
            if (a.id.equals(id)) {
                it.remove();
                removedUserId = a.msUserId;
            }
        }

        boolean hasActive = false;
        for (MsftAccount a : list) if (a.active) { hasActive = true; break; }
        if (!hasActive && !list.isEmpty()) list.get(0).active = true;
        save(ctx, list);
        org.levimc.launcher.core.auth.storage.XalExporter.removeAccountData(ctx, removedUserId);
        if (list.isEmpty()) org.levimc.launcher.core.auth.storage.XalExporter.exportActiveAccount(ctx);
        else org.levimc.launcher.core.auth.storage.XalExporter.prepareCachedAccount(ctx, getActive(ctx));
    }

    public static synchronized void selectSavedAccount(Context ctx, String id) {
        List<MsftAccount> accounts = list(ctx);
        MsftAccount selected = null;
        for (MsftAccount account : accounts) {
            account.active = id.equals(account.id);
            if (account.active) selected = account;
        }
        if (selected == null) throw new IllegalStateException("The Microsoft account is no longer available.");
        org.levimc.launcher.core.auth.storage.XalExporter.prepareCachedAccount(ctx, selected);
        save(ctx, accounts);
    }

    public static synchronized void setActive(Context ctx, String id) {
        List<MsftAccount> list = list(ctx);
        boolean found = false;
        for (MsftAccount a : list) {
            if (id.equals(a.id)) {
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalStateException("The Microsoft account is no longer available.");
        for (MsftAccount a : list) {
            a.active = id.equals(a.id);
        }
        save(ctx, list);
        org.levimc.launcher.core.auth.storage.XalExporter.exportActiveAccountOrThrow(ctx);
    }

    public static synchronized MsftAccount find(Context ctx, String id) {
        for (MsftAccount a : list(ctx)) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }
}
