package org.levimc.launcher.ui.views;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.packages.InstalledPackages;
import org.levimc.launcher.core.versions.GameVersion;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class MainViewModel extends ViewModel {
    private final ModManager modManager;
    private final MutableLiveData<List<Mod>> modsLiveData = new MutableLiveData<>();
    /** The LeviLamina version the current instance has installed as a package; null when none. */
    private final MutableLiveData<String> loaderLiveData = new MutableLiveData<>();
    /** Keys of the mod folders of the loader, left out of {@link #modsLiveData} (shown as the loader instead). */
    private volatile Set<String> loaderFolders = Collections.emptySet();
    private final ExecutorService modsExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong refreshGeneration = new AtomicLong();
    private final Observer<Void> modsChangedObserver = trigger -> refreshMods();

    public MainViewModel(ModManager modManager) {
        this.modManager = modManager;
        modManager.getModsChangedLiveData().observeForever(modsChangedObserver);
        refreshMods();
    }

    public void refreshMods() {
        loadMods(false);
    }

    public void reloadMods() {
        loadMods(true);
    }

    private void loadMods(boolean invalidateCache) {
        long generation = refreshGeneration.incrementAndGet();
        modsExecutor.execute(() -> {
            List<Mod> mods = invalidateCache ? modManager.reloadMods() : modManager.getMods();
            InstalledPackages ws = readPackages(modManager.getCurrentVersion());
            InstalledPackages.Pkg ll = ws == null ? null : ws.leviLamina();
            Set<String> hidden = ws == null ? Collections.emptySet() : ws.loaderFolderKeys();
            List<Mod> shown = new ArrayList<>();
            for (Mod m : mods) {
                // A disabled loader folder stays listed, so it can be enabled again.
                if (!m.isEnabled() || !hidden.contains(key(m.getId()))) shown.add(m);
            }
            if (generation == refreshGeneration.get()) {
                loaderFolders = hidden;
                modsLiveData.postValue(shown);
                loaderLiveData.postValue(ll == null ? null : ll.version);
            }
        });
    }

    public void removeMod(Mod mod) {
        modManager.deleteMod(mod.getId());
        refreshMods();
    }

    public void setCurrentVersion(GameVersion version) {
        modManager.setCurrentVersion(version);
        refreshMods();
    }

    /** The installed packages of version; null when it has none or they cannot be read. */
    private static InstalledPackages readPackages(GameVersion version) {
        File dir = version == null || version.modsDir == null ? null : version.modsDir.getParentFile();
        if (dir == null || !(new File(dir, ".lip/state.json").isFile() || new File(dir, "tooth_lock.json").isFile())) return null;
        try {
            return InstalledPackages.read(dir);
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** The LeviLamina version of the current instance (installed as a package); null when none. */
    public LiveData<String> getLoaderLiveData() {
        return loaderLiveData;
    }

    public LiveData<List<Mod>> getModsLiveData() {
        return modsLiveData;
    }

    public void setModEnabled(String fileName, boolean enabled) {
        new Thread(() -> modManager.setModEnabled(fileName, enabled)).start();
    }

    public void reorderMods(List<Mod> reorderedMods) {
        new Thread(() -> {
            modManager.reorderMods(withUnlisted(reorderedMods));
            refreshMods();
        }).start();
    }

    /** The order of every mod after the listed ones were reordered: an unlisted mod (the loader) keeps its place. */
    private List<Mod> withUnlisted(List<Mod> listed) {
        if (loaderFolders.isEmpty()) return listed;
        Set<String> listedIds = new HashSet<>();
        for (Mod m : listed) listedIds.add(key(m.getId()));
        List<Mod> out = new ArrayList<>();
        Iterator<Mod> next = listed.iterator();
        for (Mod m : modManager.getMods()) {
            if (!listedIds.contains(key(m.getId()))) out.add(m);
            else if (next.hasNext()) out.add(next.next());
        }
        while (next.hasNext()) out.add(next.next());
        return out;
    }

    @Override
    protected void onCleared() {
        modManager.getModsChangedLiveData().removeObserver(modsChangedObserver);
        refreshGeneration.incrementAndGet();
        modsExecutor.shutdownNow();
        super.onCleared();
    }
}
