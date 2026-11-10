package org.levimc.launcher.core.packages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tags and licenses as the levimc.org listing shows and filters them (its
 * mods/src/data/featured-tags.ts and licenses.ts, which launchers mirror).
 */
public final class CatalogFacets {
    private CatalogFacets() {
    }

    /** A featured tag: the filter value of its group, the tags that count as it, and its names (en, zh-CN, zh-Hant). */
    public static final class Featured {
        public final String id;
        final List<String> aliases;
        final String[] labels;

        Featured(String id, String en, String zhCn, String zhHant, String... aliases) {
            this.id = id;
            this.aliases = Arrays.asList(aliases);
            this.labels = new String[]{en, zhCn, zhHant};
        }

        /** The name in locale: zh-Hant for Traditional Chinese, zh-CN for other Chinese, else English. */
        public String label(Locale locale) {
            if (!"zh".equals(locale.getLanguage())) return labels[0];
            String c = locale.getCountry();
            boolean hant = "Hant".equals(locale.getScript()) || c.equals("TW") || c.equals("HK") || c.equals("MO");
            return labels[hant ? 2 : 1];
        }
    }

    /** In the order filters and cards list them. */
    public static final List<Featured> FEATURED = Collections.unmodifiableList(Arrays.asList(
            new Featured("library", "Library / API", "前置库 / API", "前置庫 / API",
                    "api", "modapi", "nativeapi", "event", "export", "form", "pland:library"),
            new Featured("utility", "Utility", "实用工具", "實用工具",
                    "tools", "essential", "inventory", "waila", "info-display", "tooltip", "recipe-viewer", "item-list"),
            new Featured("management", "Server management", "服务器管理", "伺服器管理",
                    "backup", "ban", "whitelist", "login", "command", "land", "checkbag"),
            new Featured("game-mechanics", "Game mechanics", "游戏机制", "遊戲機制", "gameplay", "vanilla-plus", "achievement"),
            new Featured("adventure", "Content & adventure", "内容与冒险", "內容與冒險", "worldgen", "terrain", "biome"),
            new Featured("visual", "Visual & HUD", "视觉与界面", "視覺與介面", "hud", "sidebar", "shader", "replay", "recording"),
            new Featured("economy", "Economy", "经济", "經濟", "money"),
            new Featured("teleport", "Teleport", "传送", "傳送", "tp", "waystones"),
            new Featured("anticheat", "Anti-cheat", "反作弊", "反作弊", "antixray"),
            new Featured("optimization", "Optimization", "性能优化", "效能優化", "performance", "cleaner"),
            new Featured("bugfix", "Bug fixes", "错误修复", "錯誤修復", "fix"),
            new Featured("technology", "Technical & redstone", "技术与红石", "技術與紅石", "redstone", "piston", "automation"),
            new Featured("protocol", "Protocol & multi-version", "协议与多版本", "協議與多版本", "multiversion")));

    private static final Map<String, Featured> GROUP = new HashMap<>();

    static {
        for (Featured f : FEATURED) {
            GROUP.put(f.id, f);
            for (String a : f.aliases) GROUP.put(a, f);
        }
    }

    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "levilamina", "ll", "mod", "plugin", "native", "client", "server", "minecraft-bedrock"));

    /** Lower case, without a "category:" prefix. */
    public static String normalizeTag(String tag) {
        String lower = PackageService.key(tag);
        return lower.startsWith("category:") ? lower.substring("category:".length()) : lower;
    }

    static boolean hiddenTag(String normalized) {
        return normalized.isEmpty() || HIDDEN.contains(normalized) || normalized.startsWith("platform:") || normalized.startsWith("type:");
    }

    /** The tags shown: normalised, hidden ones dropped, each once, in the manifest's order. */
    public static List<String> visibleTags(List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tags) {
            String n = normalizeTag(t);
            if (!hiddenTag(n)) out.add(n);
        }
        return new ArrayList<>(out);
    }

    /** The featured tag with this id, or that tag is an alias of; null when none. */
    public static Featured featured(String tag) {
        return GROUP.get(normalizeTag(tag));
    }

    /** The value a tag filters by: its featured group's id, else the normalised tag; null for a hidden one. */
    public static String tagKey(String tag) {
        String n = normalizeTag(tag);
        if (hiddenTag(n)) return null;
        Featured f = GROUP.get(n);
        return f != null ? f.id : n;
    }

    public static List<String> tagKeys(List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : tags) {
            String k = tagKey(t);
            if (k != null) out.add(k);
        }
        return new ArrayList<>(out);
    }

    /** A package's featured tags, in the table's order. */
    public static List<Featured> featuredTagsOf(List<String> tags) {
        Set<Featured> have = new HashSet<>();
        for (String t : visibleTags(tags)) {
            Featured f = GROUP.get(t);
            if (f != null) have.add(f);
        }
        List<Featured> out = new ArrayList<>();
        for (Featured f : FEATURED) if (have.contains(f)) out.add(f);
        return out;
    }

    /** The visible tags that put a package under f (shown in grey after its name: "Teleport  tp waystones"). */
    public static List<String> rawTagsOf(Featured f, List<String> tags) {
        List<String> out = visibleTags(tags);
        out.removeIf(t -> GROUP.get(t) != f);
        return out;
    }

    /** A package's visible tags in no featured group, in the manifest's order. */
    public static List<String> plainTagsOf(List<String> tags) {
        List<String> out = visibleTags(tags);
        out.removeIf(GROUP::containsKey);
        return out;
    }

    // ---- licenses ----------------------------------------------------------

    public static final String PERMISSIVE = "permissive";
    public static final String WEAK_COPYLEFT = "weak-copyleft";
    public static final String COPYLEFT = "copyleft";
    public static final String OTHER = "other";
    public static final String NONE = "none";
    /** In the order filters list them. */
    public static final List<String> FAMILIES = Collections.unmodifiableList(Arrays.asList(PERMISSIVE, WEAK_COPYLEFT, COPYLEFT, OTHER, NONE));

    private static final Map<String, Pattern> FAMILY_IDS = new LinkedHashMap<>();

    static {
        FAMILY_IDS.put(PERMISSIVE, Pattern.compile("^(?:MIT|APACHE-2\\.0|BSD-.+|ISC|ZLIB|CC0-1\\.0|UNLICENSE|0BSD)$"));
        FAMILY_IDS.put(WEAK_COPYLEFT, Pattern.compile("^(?:LGPL-.+|MPL-2\\.0|EPL-2\\.0)$"));
        FAMILY_IDS.put(COPYLEFT, Pattern.compile("^(?:A?GPL-.+)$"));
    }

    /** Licenses GitHub cannot tell (NOASSERTION), by lower-case owner/repo. */
    private static final Map<String, String> LICENSE_OVERRIDES = new HashMap<>();

    static {
        LICENSE_OVERRIDES.put("copper-lamp/bschat", "AGPL-3.0");
        LICENSE_OVERRIDES.put("ieckseyr/hologramlib", "MIT");
    }

    /** The family of an SPDX id (any case); "none" without one. */
    public static String licenseFamily(String spdx) {
        if (spdx == null || spdx.isEmpty()) return NONE;
        String id = spdx.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Pattern> e : FAMILY_IDS.entrySet()) {
            if (e.getValue().matcher(id).matches()) return e.getKey();
        }
        return OTHER;
    }

    /** A package's license as the listing shows it: the override for its repository, else the SPDX id given. */
    public static String licenseOf(String id, String license) {
        String[] parts = PackageService.key(id).split("/");
        String repo = parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : "";
        String o = LICENSE_OVERRIDES.get(repo);
        return o != null ? o : PackageService.trim(license);
    }
}
