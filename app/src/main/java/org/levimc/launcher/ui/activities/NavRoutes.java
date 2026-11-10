package org.levimc.launcher.ui.activities;

import android.app.Activity;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.levimc.launcher.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Page hierarchy for the breadcrumbs in the top bar (Windows: 首页 › 内容管理 › 世界).
 * Each page names its parent; {@code standalone} pages can be opened without extras, so their
 * crumbs are tappable even when they are not in the back stack.
 */
final class NavRoutes {
    private NavRoutes() {
    }

    static final class Route {
        @StringRes
        final int title;
        @Nullable
        final Class<? extends Activity> parent;
        final boolean standalone;

        Route(int title, @Nullable Class<? extends Activity> parent, boolean standalone) {
            this.title = title;
            this.parent = parent;
            this.standalone = standalone;
        }
    }

    private static final Map<Class<?>, Route> ROUTES = new HashMap<>();

    static {
        put(MainActivity.class, R.string.crumb_home, null, true);
        put(GameDownloadActivity.class, R.string.crumb_download, MainActivity.class, true);
        put(InstancesActivity.class, R.string.crumb_instances, MainActivity.class, true);
        put(InstanceSettingsActivity.class, R.string.crumb_instance_settings, InstancesActivity.class, false);
        put(ContentManagementActivity.class, R.string.crumb_content, MainActivity.class, true);
        put(ContentListActivity.class, R.string.crumb_content, ContentManagementActivity.class, false);
        put(OptionsEditorActivity.class, R.string.crumb_options_editor, ContentManagementActivity.class, false);
        put(CustomFlatWorldActivity.class, R.string.crumb_custom_flat_world, ContentListActivity.class, false);
        put(WorldEditorActivity.class, R.string.crumb_details, ContentListActivity.class, false);
        put(ModsFullscreenActivity.class, R.string.crumb_mods, MainActivity.class, true);
        put(ModDetailActivity.class, R.string.crumb_details, ModsFullscreenActivity.class, false);
        put(ModConfigActivity.class, R.string.crumb_mod_config, ModDetailActivity.class, false);
        put(ExternalModsActivity.class, R.string.crumb_external_mods, MainActivity.class, true);
        put(CurseForgeActivity.class, R.string.crumb_curseforge, MainActivity.class, true);
        put(ContentDetailsActivity.class, R.string.crumb_details, CurseForgeActivity.class, false);
        put(org.levimc.launcher.ui.packages.PackagesActivity.class, R.string.crumb_levimc_mods, MainActivity.class, true);
        put(org.levimc.launcher.ui.packages.PackageActivity.class, R.string.crumb_details,
                org.levimc.launcher.ui.packages.PackagesActivity.class, false);
        put(SettingsActivity.class, R.string.crumb_settings, MainActivity.class, true);
        put(AboutActivity.class, R.string.crumb_about, MainActivity.class, true);
        put(AccountsActivity.class, R.string.crumb_accounts, MainActivity.class, true);
        put(NewsActivity.class, R.string.crumb_news, MainActivity.class, true);
        put(QuickLaunchActivity.class, R.string.crumb_quick_launch, MainActivity.class, true);
    }

    private static void put(Class<?> page, int title, @Nullable Class<? extends Activity> parent, boolean standalone) {
        ROUTES.put(page, new Route(title, parent, standalone));
    }

    @Nullable
    static Route get(Class<?> page) {
        Class<?> c = page;
        while (c != null && c != BaseActivity.class) {
            Route r = ROUTES.get(c);
            if (r != null) return r;
            c = c.getSuperclass();
        }
        return null;
    }
}
