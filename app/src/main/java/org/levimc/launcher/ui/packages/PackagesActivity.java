package org.levimc.launcher.ui.packages;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.PackageService;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.activities.BaseActivity;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "LeviMC Mods" (Windows LIP page): the Browse and Installed tabs. Packages go into the global current instance
 * (the one selected on the home page), as on Windows; each tab's header shows it as a read-only tag.
 */
public class PackagesActivity extends BaseActivity {
    /** Kept for callers; the page always follows the global current instance. */
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_TAB = "tab";
    public static final String TAB_BROWSE = "browse";
    public static final String TAB_INSTALLED = "installed";

    private static final String STATE_TAB = "pkg_tab";

    private String currentTab = TAB_BROWSE;
    @Nullable
    private GameVersion target;
    private final List<TargetListener> listeners = new ArrayList<>();

    public static Intent intent(Context context, @Nullable GameVersion version, @Nullable String tab) {
        Intent intent = new Intent(context, PackagesActivity.class);
        if (version != null) {
            intent.putExtra(EXTRA_VERSION, version);
        }
        if (tab != null) {
            intent.putExtra(EXTRA_TAB, tab);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_packages);

        target = PackagesActivity.Instances.selected(this);
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getString(STATE_TAB, TAB_BROWSE);
        } else {
            String tab = getIntent().getStringExtra(EXTRA_TAB);
            currentTab = TAB_INSTALLED.equals(tab) ? TAB_INSTALLED : TAB_BROWSE;
        }
        selectTab(currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The current instance may have been switched on another page meanwhile.
        setTarget(PackagesActivity.Instances.selected(this));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TAB, currentTab);
    }

    // #region Tabs

    private void selectTab(String tab) {
        currentTab = tab;

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        Fragment browse = fm.findFragmentByTag(TAB_BROWSE);
        Fragment installed = fm.findFragmentByTag(TAB_INSTALLED);
        if (TAB_BROWSE.equals(tab)) {
            if (browse == null) {
                tx.add(R.id.pkg_fragment_container, new BrowseFragment(), TAB_BROWSE);
            } else {
                tx.show(browse);
            }
            if (installed != null) {
                tx.hide(installed);
            }
        } else {
            if (installed == null) {
                tx.add(R.id.pkg_fragment_container, new InstalledFragment(), TAB_INSTALLED);
            } else {
                tx.show(installed);
            }
            if (browse != null) {
                tx.hide(browse);
            }
        }
        tx.commitNow();
        syncHeaderTabs(browse);
        syncHeaderTabs(installed);
    }

    /** Each tab draws its own copy of the header; keep the hidden one's segmented control in step. */
    private void syncHeaderTabs(@Nullable Fragment fragment) {
        View root = fragment == null ? null : fragment.getView();
        SegmentedControl tabs = root == null ? null : root.findViewById(R.id.pkg_header_tabs);
        if (tabs != null) {
            tabs.setSelectedIndex(TAB_INSTALLED.equals(currentTab) ? 1 : 0, false);
        }
    }

    /**
     * Wires a tab's header (catalog_pkg_header_lead): the Browse / Installed segmented control and the current
     * instance tag. {@code index} is the tab that owns the header (0 Browse, 1 Installed).
     */
    void bindHeader(View root, int index) {
        SegmentedControl tabs = root.findViewById(R.id.pkg_header_tabs);
        if (tabs != null) {
            tabs.setEqualWidth(false);
            tabs.setItems(getString(R.string.pkg_tab_browse), getString(R.string.pkg_tab_installed));
            tabs.setSelectedIndex(index, false);
            tabs.setOnSegmentSelectedListener(i -> selectTab(i == 1 ? TAB_INSTALLED : TAB_BROWSE));
        }
        renderTargetTag(root.findViewById(R.id.pkg_header_target));
        // Tab fragments are added after setContentView's personalization pass: re-tint their accent views.
        new PersonalizationManager(this).applyAccentToView(root, this);
        // Fragment views are inflated after the page's glass pass; give their cards the wallpaper material too.
        new PersonalizationManager(this).applyGlassToTree(root, this);
    }

    // #endregion

    // #region Target

    void renderTargetTag(@Nullable TextView tag) {
        if (tag == null) {
            return;
        }
        String value;
        if (target == null) {
            value = getString(R.string.pkg_target_none);
        } else {
            String name = PackagesActivity.Instances.displayName(target);
            String game = PackagesActivity.Instances.gameVersion(target);
            value = game.isEmpty() || game.equals(name) ? name : getString(R.string.pkg_target_value, name, game);
        }
        tag.setText(getString(R.string.catalog_instance_tag, value));
    }

    private void setTarget(@Nullable GameVersion v) {
        if (PackagesActivity.Instances.same(v, target)) {
            return;
        }
        target = v;
        for (TargetListener l : new ArrayList<>(listeners)) {
            l.onPkgTargetChanged(target);
        }
    }

    @Nullable
    public GameVersion getPkgTarget() {
        return target;
    }

    public void addPkgTargetListener(TargetListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removePkgTargetListener(TargetListener listener) {
        listeners.remove(listener);
    }

    // #endregion

    /** Told when the screen's target instance changes. */
    public interface TargetListener {
        void onPkgTargetChanged(@Nullable GameVersion target);
    }

    public final static class Instances {
        private Instances() {
        }

        public static List<GameVersion> all(Context context) {
            VersionManager vm = VersionManager.get(context);
            List<GameVersion> out = new ArrayList<>();
            if (vm.getInstalledVersions() != null) {
                out.addAll(vm.getInstalledVersions());
            }
            if (vm.getCustomVersions() != null) {
                out.addAll(vm.getCustomVersions());
            }
            return out;
        }

        public static GameVersion selected(Context context) {
            return VersionManager.get(context).getSelectedVersion();
        }

        public static boolean same(GameVersion a, GameVersion b) {
            if (a == null || b == null) {
                return a == b;
            }
            if (a.versionDir != null && b.versionDir != null) {
                return a.versionDir.getAbsolutePath().equals(b.versionDir.getAbsolutePath());
            }
            return a.isInstalled == b.isInstalled && Objects.equals(a.directoryName, b.directoryName);
        }

        public static int indexOf(List<GameVersion> list, GameVersion v) {
            for (int i = 0; i < list.size(); i++) {
                if (same(list.get(i), v)) {
                    return i;
                }
            }
            return -1;
        }

        public static String displayName(GameVersion version) {
            if (version == null) {
                return "";
            }
            String name = version.displayName == null ? "" : version.displayName.trim();
            String code = version.versionCode;
            if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(code)) {
                String suffix = " (" + code + ")";
                if (name.endsWith(suffix)) {
                    name = name.substring(0, name.length() - suffix.length()).trim();
                }
            }
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
            if (!TextUtils.isEmpty(version.directoryName)) {
                return version.directoryName;
            }
            return code == null ? "" : code;
        }

        public static String gameVersion(GameVersion version) {
            return version == null || version.versionCode == null ? "" : version.versionCode.trim();
        }

        public static String targetName(Context context, GameVersion version) {
            if (version == null) {
                return "";
            }
            File dir = PackageService.get().workspaceDir(version);
            return dir.getName();
        }
    }

    public final static class Links {
        /** The guide on creating packages (lip.toml's side and platform, the recommended tags). */
        public static final String DEVELOPER_GUIDE_URL = "https://docs.levimc.org/lip/guides/creating_packages.html";

        public static final String EXTRA_TAB = "tab";
        public static final String TAB_MANAGEMENT = "management";
        public static final String TAB_LOADER = "loader";

        private Links() {
        }

        public static String pageUrl(String identifier) {
            // The page of a package: https://levimc.org/mods/<id>/ (its JSON is under /api/v4/mods/).
            String json = Catalog.Index.detailUrl("https://levimc.org/mods/", identifier);
            if (json.isEmpty()) {
                return "";
            }
            return json.substring(0, json.length() - "index.json".length());
        }

        public static String routeVariant(String identifier) {
            String raw = identifier == null ? "" : identifier.trim();
            int hash = raw.indexOf('#');
            if (hash < 0) {
                return "";
            }
            String rest = raw.substring(hash + 1);
            int at = rest.indexOf('@');
            return (at >= 0 ? rest.substring(0, at) : rest).trim();
        }
    }
}
