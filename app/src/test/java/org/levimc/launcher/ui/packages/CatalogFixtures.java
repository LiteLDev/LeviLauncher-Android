// Catalogue fixtures for the package screen tests (the documents under
// src/test/resources/catalog shared with the core.packages tests).
package org.levimc.launcher.ui.packages;

import org.levimc.launcher.core.packages.Catalog;
import org.levimc.launcher.core.packages.CatalogModels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class CatalogFixtures {
    private CatalogFixtures() {
    }

    static String text(String name) {
        try (InputStream in = CatalogFixtures.class.getClassLoader().getResourceAsStream("catalog/" + name)) {
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

    static CatalogModels.Pkg pkg(String name) {
        CatalogModels.Pkg p = Catalog.Index.parsePackage(CatalogModels.Detail.parse(text(name)), VersionGroupingTest.RID);
        if (p == null) {
            throw new IllegalStateException(name + " has no client variant");
        }
        return p;
    }

    /** android-platforms.json: its client variant (2.0.0 and 1.5.0 for android-arm64; 3.0.0 and 1.0.0 for other platforms). */
    static CatalogModels.Variant androidVariant() {
        return pkg("android-platforms.json").defaultVariant();
    }
}
