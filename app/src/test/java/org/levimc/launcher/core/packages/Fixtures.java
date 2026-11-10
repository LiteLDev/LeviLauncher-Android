// Catalogue fixtures (port of upstream fixtures_test.go): documents
// shaped like levimc.org/api/v4/mods/<id>/index.json under src/test/resources/catalog.
package org.levimc.launcher.core.packages;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Fixtures {
    static final String HOLO = "holo.json";
    static final String SERVER_ONLY = "serveronly.json";
    static final String LSE = "lse.json";
    static final String MONEY = "money.json";
    static final String CRASH = "crash.json";
    static final String ANDROID = "android-platforms.json";

    private Fixtures() {
    }

    static String text(String name) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("catalog/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static CatalogModels.Detail detail(String name) {
        return CatalogModels.Detail.parse(text(name));
    }

    static final String RID = "android-arm64";
    /** The platform every mod of the shared fixture has. */
    static final String WIN = "win-x64";

    static CatalogModels.Pkg pkg(String name) {
        return Catalog.Index.parsePackage(detail(name), RID);
    }

    /** fixtureIndex: LegacyMoney, LHolo, BackupHelper (server only), LSE and CrashLogger, for android-arm64. */
    static Catalog.Index index() {
        return Catalog.Index.build(new ArrayList<>(Arrays.asList(detail(MONEY), detail(HOLO), detail(SERVER_ONLY),
                detail(LSE), detail(CRASH))), RID);
    }

    /** fixtureDB: a LeviLamina database for the fixtures. */
    static LeviLamina.Db db() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("1.26.51.01", Arrays.asList("26.51.0", "26.51.1"));
        raw.put("1.26.60.05", Arrays.asList("26.60.0", "26.60.4", "26.60.5-rc.1"));
        return LeviLamina.Db.normalize(raw);
    }

    // ---- the shared fixture (levimc.org's own test data) ------------------------

    /** A file of the shared fixture ("index.json", "github.com/LiteLDev/Arke/index.json"). */
    static String siteText(String path) {
        return text("site/" + path);
    }

    /** The detail of a mod of the shared fixture ("LiteLDev/Arke"). */
    static CatalogModels.Detail siteDetail(String ownerRepo) {
        return CatalogModels.Detail.parse(siteText("github.com/" + ownerRepo + "/index.json"));
    }

    /** The mods of the shared fixture's index.json, in its order. */
    static List<JsonObject> siteMods() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement m : JsonParser.parseString(siteText("index.json")).getAsJsonObject().getAsJsonArray("mods")) {
            out.add(m.getAsJsonObject());
        }
        return out;
    }

    /**
     * The shared fixture's catalogue as packages, to run the query on what the site's catalog-query.ts and
     * search.ts run on: each index entry becomes a detail with one version whose default variant has a client
     * entry per platform, depending on the entry's deps; parsed for win-x64 (every mod of the fixture has it).
     */
    static List<CatalogModels.Pkg> siteCatalog() {
        List<CatalogModels.Pkg> out = new ArrayList<>();
        for (JsonObject m : siteMods()) {
            JsonObject d = new JsonObject();
            for (String k : new String[]{"id", "name", "description", "owner", "logo", "tags", "gameVersions", "stars",
                    "license", "updated", "created"}) {
                if (m.has(k)) d.add(k, m.get(k));
            }
            JsonArray entries = new JsonArray();
            for (JsonElement p : m.getAsJsonArray("platform")) {
                JsonObject e = new JsonObject();
                e.addProperty("side", "client");
                e.add("platform", p);
                e.add("deps", m.get("deps"));
                entries.add(e);
            }
            JsonObject variants = new JsonObject();
            variants.add("", entries);
            JsonObject version = new JsonObject();
            version.addProperty("version", "1.0.0");
            version.add("variants", variants);
            JsonArray versions = new JsonArray();
            versions.add(version);
            d.add("versions", versions);
            CatalogModels.Pkg p = Catalog.Index.parsePackage(CatalogModels.Detail.parse(d.toString()), WIN);
            if (p == null) throw new IllegalStateException(m.get("id") + " has no " + WIN + " entry");
            out.add(p);
        }
        return out;
    }
}
