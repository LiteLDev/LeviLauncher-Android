package org.levimc.launcher.ui.packages;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.util.PersonalizationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PackagesActivity extends BaseActivity {
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_TAB = "tab";
    public static final String TAB_BROWSE = "browse";
    public static final String TAB_INSTALLED = "installed";

    private static final String STATE_TAB = "pkg_tab";
    private static final String STATE_TARGET = "pkg_target";

    private TextView tabBrowse;
    private TextView tabInstalled;
    private TextView targetText;
    private String currentTab = TAB_BROWSE;
    @Nullable
    private GameVersion target;
    private final List<TargetListener> listeners = new ArrayList<>();
    private int accent;

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

        PersonalizationManager pm = new PersonalizationManager(this);
        accent = pm.getAccentColor();
        if (accent == 0) {
            accent = getColor(R.color.primary);
        }

        tabBrowse = findViewById(R.id.pkg_tab_browse);
        tabInstalled = findViewById(R.id.pkg_tab_installed);
        targetText = findViewById(R.id.pkg_target_text);
        View targetChip = findViewById(R.id.pkg_target_chip);

        if (savedInstanceState != null) {
            target = savedInstanceState.getParcelable(STATE_TARGET);
            currentTab = savedInstanceState.getString(STATE_TAB, TAB_BROWSE);
        } else {
            target = getIntent().getParcelableExtra(EXTRA_VERSION);
            if (target == null) {
                target = PackagesActivity.Instances.selected(this);
            }
            String tab = getIntent().getStringExtra(EXTRA_TAB);
            currentTab = TAB_INSTALLED.equals(tab) ? TAB_INSTALLED : TAB_BROWSE;
        }

        tabBrowse.setOnClickListener(v -> selectTab(TAB_BROWSE));
        tabInstalled.setOnClickListener(v -> selectTab(TAB_INSTALLED));
        targetChip.setOnClickListener(v -> pickTarget());
        DynamicAnim.applyPressScale(targetChip);
        DynamicAnim.applyPressScale(tabBrowse);
        DynamicAnim.applyPressScale(tabInstalled);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            pm.applyAccentToView(root, this);
        }
        renderTarget();
        selectTab(currentTab);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TAB, currentTab);
        outState.putParcelable(STATE_TARGET, target);
    }

    // #region Tabs

    private void selectTab(String tab) {
        currentTab = tab;
        styleTab(tabBrowse, TAB_BROWSE.equals(tab));
        styleTab(tabInstalled, TAB_INSTALLED.equals(tab));

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
    }

    private void styleTab(TextView tab, boolean selected) {
        if (selected) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor(accent);
            gd.setCornerRadius(16 * getResources().getDisplayMetrics().density);
            tab.setBackground(gd);
            tab.setTextColor(Color.luminance(accent) > 0.42f ? Color.BLACK : Color.WHITE);
            tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            tab.setBackgroundResource(R.drawable.bg_tab_unselected);
            tab.setTextColor(getColor(R.color.text_secondary));
            tab.setTypeface(android.graphics.Typeface.create(tab.getTypeface(), android.graphics.Typeface.NORMAL));
        }
    }

    // #endregion

    // #region Target

    private void renderTarget() {
        if (target == null) {
            targetText.setText(R.string.pkg_target_none);
            return;
        }
        String name = PackagesActivity.Instances.displayName(target);
        String game = PackagesActivity.Instances.gameVersion(target);
        targetText.setText(game.isEmpty() || game.equals(name)
                ? name : getString(R.string.pkg_target_value, name, game));
    }

    private void pickTarget() {
        List<GameVersion> all = PackagesActivity.Instances.all(this);
        if (all.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.not_found_version, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            GameVersion v = all.get(i);
            String game = PackagesActivity.Instances.gameVersion(v);
            String name = PackagesActivity.Instances.displayName(v);
            names[i] = game.isEmpty() || game.equals(name) ? name : getString(R.string.pkg_target_value, name, game);
            if (PackagesActivity.Instances.same(v, target)) {
                names[i] = "✓ " + names[i];
            }
        }
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.pkg_target_select_title))
                .setItems(names, (dialog, which) -> setTarget(all.get(which)))
                .setNegativeButton(getString(R.string.pkg_task_cancel), null)
                .show();
    }

    private void setTarget(GameVersion v) {
        if (PackagesActivity.Instances.same(v, target)) {
            return;
        }
        target = v;
        renderTarget();
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
