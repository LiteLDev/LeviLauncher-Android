package org.levimc.launcher.ui.packages;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.content.res.AppCompatResources;

import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogFacets;
import org.levimc.launcher.core.packages.CatalogModels;
import org.levimc.launcher.core.packages.Readme;
import org.levimc.launcher.util.PersonalizationManager;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class PkgUi {
    private PkgUi() {
    }

    static String availabilityLabel(Context ctx, CatalogModels.Availability a) {
        if (a == null) {
            return "";
        }
        switch (a) {
            case AVAILABLE:
                return ctx.getString(R.string.pkg_badge_android);
            case UNAVAILABLE:
                return ctx.getString(R.string.pkg_badge_no_android);
            default:
                return ctx.getString(R.string.pkg_badge_unspecified);
        }
    }

    static int availabilityColor(Context ctx, CatalogModels.Availability a) {
        if (a == CatalogModels.Availability.AVAILABLE) {
            return ctx.getColor(R.color.pkg_status_success);
        }
        if (a == CatalogModels.Availability.UNKNOWN) {
            return ctx.getColor(R.color.pkg_log_warning);
        }
        return ctx.getColor(R.color.pkg_status_neutral);
    }

    static void bindBadge(TextView chip, @Nullable CatalogModels.Availability a) {
        if (a == null) {
            chip.setVisibility(View.GONE);
            return;
        }
        Context ctx = chip.getContext();
        int color = availabilityColor(ctx, a);
        chip.setVisibility(View.VISIBLE);
        chip.setText(availabilityLabel(ctx, a));
        chip.setTextColor(color);
        chip.setBackgroundTintList(ColorStateList.valueOf(withAlpha(color, 38)));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int accent(Context ctx) {
        int accent = new PersonalizationManager(ctx).getAccentColor();
        return accent != 0 ? accent : ctx.getColor(R.color.primary);
    }

    private static Locale locale(Context ctx) {
        return ctx.getResources().getConfiguration().getLocales().get(0);
    }

    /**
     * The language of the screen's strings (R.string.pkg_content_locale), not the device's: on a zh-TW device the
     * strings are English, so featured-tag and license names are English too.
     */
    static Locale contentLocale(Context ctx) {
        return Locale.forLanguageTag(ctx.getString(R.string.pkg_content_locale));
    }

    /** A tag filter value: a featured tag's name, else "#tag". */
    static String tagLabel(Context ctx, String key) {
        CatalogFacets.Featured f = CatalogFacets.featured(key);
        return f != null ? f.label(contentLocale(ctx)) : "#" + key;
    }

    /** text followed by raw in small grey text (raw may be empty). */
    static CharSequence withRaw(Context ctx, String text, String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        if (raw != null && !raw.isEmpty()) {
            sb.append("  ");
            int start = sb.length();
            sb.append(raw);
            sb.setSpan(new ForegroundColorSpan(ctx.getColor(R.color.text_secondary)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** The sprite of a featured tag (res/drawable/ic_tag_*.xml, from the levimc-kit sprites), or null. */
    @Nullable
    static Drawable tagIcon(Context ctx, String featuredId) {
        int res;
        switch (featuredId) {
            case "library": res = R.drawable.ic_tag_library; break;
            case "utility": res = R.drawable.ic_tag_utility; break;
            case "management": res = R.drawable.ic_tag_management; break;
            case "game-mechanics": res = R.drawable.ic_tag_game_mechanics; break;
            case "adventure": res = R.drawable.ic_tag_adventure; break;
            case "visual": res = R.drawable.ic_tag_visual; break;
            case "economy": res = R.drawable.ic_tag_economy; break;
            case "teleport": res = R.drawable.ic_tag_teleport; break;
            case "anticheat": res = R.drawable.ic_tag_anticheat; break;
            case "optimization": res = R.drawable.ic_tag_optimization; break;
            case "bugfix": res = R.drawable.ic_tag_bugfix; break;
            case "technology": res = R.drawable.ic_tag_technology; break;
            case "protocol": res = R.drawable.ic_tag_protocol; break;
            default: return null;
        }
        return AppCompatResources.getDrawable(ctx, res);
    }

    /**
     * Fills group with a package's tag chips (item_pkg_tag): its featured tags (sprite, name, the raw tags in grey),
     * then its other visible tags as "#tag", at most max of them (0 for all) and a "+N" chip for the rest.
     */
    static void bindTags(Context ctx, ViewGroup group, List<String> tags, int max) {
        group.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        List<CatalogFacets.Featured> featured = CatalogFacets.featuredTagsOf(tags);
        List<String> plain = CatalogFacets.plainTagsOf(tags);
        int total = featured.size() + plain.size();
        int limit = max <= 0 ? total : Math.min(max, total);
        List<String> rest = new ArrayList<>();
        for (CatalogFacets.Featured f : featured) {
            String name = f.label(contentLocale(ctx));
            String raw = String.join(" ", CatalogFacets.rawTagsOf(f, tags));
            if (group.getChildCount() >= limit) {
                rest.add(raw.isEmpty() ? name : name + " (" + raw + ")");
                continue;
            }
            TextView chip = (TextView) inflater.inflate(R.layout.item_pkg_tag, group, false);
            chip.setText(withRaw(ctx, name, raw));
            chip.setTextColor(ctx.getColor(R.color.on_surface));
            Drawable icon = tagIcon(ctx, f.id);
            if (icon != null) {
                int px = Math.round(12 * ctx.getResources().getDisplayMetrics().density);
                icon.setBounds(0, 0, px, px);
                chip.setCompoundDrawablesRelative(icon, null, null, null);
                chip.setCompoundDrawablePadding(Math.round(4 * ctx.getResources().getDisplayMetrics().density));
            }
            group.addView(chip);
        }
        for (String t : plain) {
            if (group.getChildCount() >= limit) {
                rest.add("#" + t);
                continue;
            }
            TextView chip = (TextView) inflater.inflate(R.layout.item_pkg_tag, group, false);
            chip.setText("#" + t);
            group.addView(chip);
        }
        if (!rest.isEmpty()) {
            TextView more = (TextView) inflater.inflate(R.layout.item_pkg_tag, group, false);
            more.setText(ctx.getString(R.string.pkg_more_tags, rest.size()));
            more.setContentDescription(ctx.getString(R.string.pkg_more_tags_description, rest.size(), String.join(", ", rest)));
            group.addView(more);
        }
        group.setVisibility(group.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** A license family's name. */
    static String familyName(Context ctx, String family) {
        switch (family) {
            case CatalogFacets.PERMISSIVE:
                return ctx.getString(R.string.pkg_license_permissive);
            case CatalogFacets.WEAK_COPYLEFT:
                return ctx.getString(R.string.pkg_license_weak_copyleft);
            case CatalogFacets.COPYLEFT:
                return ctx.getString(R.string.pkg_license_copyleft);
            case CatalogFacets.OTHER:
                return ctx.getString(R.string.pkg_license_other);
            case CatalogFacets.NONE:
                return ctx.getString(R.string.pkg_license_none);
            default:
                return family;
        }
    }

    /** A package's license as the catalogue shows it: "Copyleft (GPL-3.0)", the family alone for NOASSERTION, "" without one. */
    static String license(Context ctx, CatalogModels.Pkg p) {
        if (p.license.isEmpty()) {
            return "";
        }
        String family = familyName(ctx, p.licenseFamily);
        return "NOASSERTION".equals(p.license) ? family : ctx.getString(R.string.pkg_license_value, family, p.license);
    }

    /** The non-empty parts joined with " · " (a meta line). */
    static String metaLine(String... parts) {
        List<String> out = new ArrayList<>();
        for (String part : parts) if (part != null && !part.isEmpty()) out.add(part);
        return String.join(" · ", out);
    }

    static String formatDate(Context ctx, String updated) {
        long ms = Catalog.Search.updatedTime(updated);
        if (ms <= 0) {
            return "-";
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale(ctx))
                .format(new Date(ms));
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    static void openUrl(Context ctx, String url) {
        if (!Readme.Markdown.isOpenableExternal(url)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, ctx.getString(R.string.cannot_open_link, url), Toast.LENGTH_SHORT).show();
        }
    }

    static int noticeColor(Context ctx, InstallDialog.Model.Level level) {
        switch (level) {
            case ERROR:
                return ctx.getColor(R.color.error);
            case WARNING:
                return ctx.getColor(R.color.pkg_log_warning);
            default:
                return ctx.getColor(R.color.text_secondary);
        }
    }

    public final static class ErrorText {
        private static final Map<String, Integer> CODES;

        static {
            Map<String, Integer> m = new HashMap<>();
            m.put("ERR_TARGET_NOT_FOUND", R.string.pkg_err_target_not_found);
            m.put("ERR_ACCESS_VERSIONS_DIR", R.string.pkg_err_access_versions_dir);
            m.put("ERR_PKG_WORKSPACE_ENV_MISMATCH", R.string.pkg_err_workspace_env_mismatch);
            m.put("ERR_PKG_WORKSPACE_INVALID", R.string.pkg_err_workspace_invalid);
            m.put("ERR_PKG_WORKSPACE_BUSY", R.string.pkg_err_workspace_busy);
            m.put("ERR_PACKAGE_INVALID_IDENTIFIER", R.string.pkg_err_package_invalid_identifier);
            m.put("ERR_PACKAGE_VERSION_REQUIRED", R.string.pkg_err_package_version_required);
            m.put("ERR_PACKAGE_INSTALL_FAILED", R.string.pkg_err_package_install_failed);
            m.put("ERR_PACKAGE_UNINSTALL_FAILED", R.string.pkg_err_package_uninstall_failed);
            m.put("ERR_PACKAGE_REQUIRED_BY_DEPENDENTS", R.string.pkg_err_package_required_by_dependents);
            m.put("ERR_PACKAGE_DEMOTED_TO_DEPENDENCY", R.string.pkg_err_package_demoted_to_dependency);
            m.put("ERR_PACKAGE_QUERY_FAILED", R.string.pkg_err_package_query_failed);
            m.put("ERR_PKG_INSTALL_FAILED", R.string.pkg_err_install_failed);
            m.put("ERR_PKG_CACHE_CLEAN_FAILED", R.string.pkg_err_cache_clean_failed);
            m.put("ERR_PKG_INVALID_GITHUB_PROXY", R.string.pkg_err_invalid_github_proxy);
            m.put("ERR_WRITE_FILE", R.string.pkg_err_write_file);
            m.put("ERR_PKG_INDEX_TIMEOUT", R.string.pkg_err_index_timeout);
            m.put("ERR_PKG_INDEX_UNAVAILABLE", R.string.pkg_err_index_unavailable);
            m.put("ERR_PACKAGE_NOT_FOUND", R.string.pkg_err_package_not_found);
            m.put("ERR_FETCH_LL_DB", R.string.pkg_err_fetch_ll_db);
            m.put("ERR_INVALID_VERSION_FORMAT", R.string.pkg_err_invalid_version_format);
            m.put("ERR_LL_NOT_SUPPORTED", R.string.pkg_err_ll_not_supported);
            m.put("ERR_LL_VERSION_UNSUPPORTED", R.string.pkg_err_ll_version_unsupported);
            m.put("ERR_LL_NO_COMPATIBLE_VERSION", R.string.pkg_err_ll_no_compatible_version);
            m.put("ERR_PKG_GAME_VERSION_UNSUPPORTED", R.string.pkg_err_game_version_unsupported);
            m.put("ERR_PACKAGE_VERSION_NOT_FOUND", R.string.pkg_err_package_version_not_found);
            m.put("ERR_LL_MANAGED_IN_VERSION_SETTINGS", R.string.pkg_err_ll_managed_in_version_settings);
            m.put("ERR_NO_UPDATE_SOURCE", R.string.pkg_err_no_update_source);
            m.put("ERR_ALREADY_LATEST", R.string.pkg_err_already_latest);
            m.put("ERR_PKG_NO_ANDROID_BUILD", R.string.pkg_err_no_android_build);
            m.put("ERR_PKG_TASK_CANCELLED", R.string.pkg_err_task_cancelled);
            m.put("ERR_MOD_DELETE_FAILED", R.string.pkg_err_mod_delete_failed);
            m.put("ERR_PKG_LOCAL_SOURCE_FAILED", R.string.pkg_err_local_source_failed);
            m.put("ERR_PKG_HOOK_FAILED", R.string.pkg_err_hook_failed);
            CODES = Collections.unmodifiableMap(m);
        }

        /**
         * Plain-language causes of a failure's detail (LeviLauncher's errorDetail.ts, network and
         * package groups; patterns also match Android's and OkHttp's wording): the first hit of each group.
         */
        private static final Object[][][] CAUSES = {
                {
                        {"no such host|unable to resolve host|server misbehaving|name or service not known|could not resolve|\\bdns\\b", R.string.pkg_err_cause_dns},
                        {"\\btls\\b|x509|certificat|\\bssl\\b|handshake", R.string.pkg_err_cause_tls},
                        {"timed out|timeout|deadline exceeded", R.string.pkg_err_cause_timeout},
                        {"connection (refused|reset|abort)|failed to connect|network is unreachable|no route to host|unexpected end of stream|unexpected eof", R.string.pkg_err_cause_connection},
                        {"\\b403\\b|forbidden", R.string.pkg_err_cause_http_403},
                        {"\\b404\\b", R.string.pkg_err_cause_http_404},
                        {"\\b429\\b|too many requests|rate limit", R.string.pkg_err_cause_http_429},
                        {"\\b50[0-4]\\b|bad gateway|service unavailable|gateway timeout", R.string.pkg_err_cause_http_5xx},
                },
                {
                        {"conflict|cannot satisfy|unsatisfiable|no version|incompatible|not compatible", R.string.pkg_err_cause_conflict},
                        {"checksum|hash mismatch|sha-?\\d+ mismatch|corrupt|damaged|not a valid (zip|archive)|zip: |invalid (archive|header)", R.string.pkg_err_cause_corrupt},
                },
        };

        /** The string resources of the plain-language causes of {@code detail}, most specific group first. */
        static List<Integer> causes(String detail) {
            List<Integer> out = new ArrayList<>();
            if (detail == null || detail.isEmpty()) return out;
            for (Object[][] group : CAUSES) {
                for (Object[] rule : group) {
                    if (Pattern.compile((String) rule[0], Pattern.CASE_INSENSITIVE).matcher(detail).find()) {
                        out.add((Integer) rule[1]);
                        break;
                    }
                }
            }
            return out;
        }

        private ErrorText() {
        }

        @StringRes
        public static int resId(String code) {
            Integer id = CODES.get(codeOf(code));
            return id == null ? 0 : id;
        }

        static String codeOf(String text) {
            String t = text == null ? "" : text.trim();
            int idx = t.indexOf(':');
            if (idx > 0 && t.startsWith("ERR_")) {
                return t.substring(0, idx).trim();
            }
            return t;
        }

        static String detailOf(String text) {
            String t = text == null ? "" : text.trim();
            int idx = t.indexOf(':');
            if (idx > 0 && t.startsWith("ERR_")) {
                return t.substring(idx + 1).trim();
            }
            return "";
        }

        public static String describe(Context context, String code) {
            return describe(context, code, "");
        }

        public static String describe(Context context, String code, String detail) {
            String c = codeOf(code);
            String d = detail == null || detail.trim().isEmpty() ? detailOf(code) : detail.trim();
            int id = resId(c);
            if (id != 0) {
                return context.getString(id);
            }
            if (c.isEmpty()) {
                return d.isEmpty() ? context.getString(R.string.pkg_task_error_generic) : d;
            }
            if (d.isEmpty() || d.equals(c)) {
                return c;
            }
            return context.getString(R.string.pkg_err_unknown_with_detail, c, d);
        }

        public static String describe(Context context, TaskSession.Result result) {
            return describe(context, result.code, result.detail);
        }

        /** The headline, the plain-language causes and the technical detail, one per line. */
        public static String full(Context context, String code, String detail) {
            String headline = describe(context, code, detail);
            String d = detail == null || detail.trim().isEmpty() ? detailOf(code) : detail.trim();
            StringBuilder b = new StringBuilder(headline);
            for (int id : causes(d)) b.append('\n').append(context.getString(id));
            if (!d.isEmpty() && !headline.contains(d) && !d.equals(codeOf(code))) b.append('\n').append(d);
            return b.toString();
        }
    }
}
