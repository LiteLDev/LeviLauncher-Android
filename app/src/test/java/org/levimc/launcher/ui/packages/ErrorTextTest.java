// Tests of PkgUi.ErrorText: every code the package manager returns has a text,
// "ERR_X: detail" messages and the hook-failure marker.
package org.levimc.launcher.ui.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.levimc.launcher.R;
import org.levimc.launcher.core.packages.InstallPlanner;
import org.levimc.launcher.core.packages.PackageService;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ErrorTextTest {
    private static List<String> codeConstants(Class<?> c) throws IllegalAccessException {
        List<String> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())
                    && f.getType() == String.class) {
                Object v = f.get(null);
                if (v instanceof String && ((String) v).startsWith("ERR_")) {
                    out.add((String) v);
                }
            }
        }
        return out;
    }

    @Test
    public void testEveryCodeHasAText() throws Exception {
        List<String> codes = new ArrayList<>();
        codes.addAll(codeConstants(PackageService.class));
        codes.addAll(codeConstants(InstallPlanner.class));
        codes.add("ERR_LL_MANAGED_IN_VERSION_SETTINGS");
        codes.add("ERR_NO_UPDATE_SOURCE");
        codes.add("ERR_ALREADY_LATEST");
        assertTrue(codes.size() > 30);
        for (String code : codes) {
            assertNotEquals("no text for " + code, 0, PkgUi.ErrorText.resId(code));
        }
        assertEquals(R.string.pkg_err_workspace_busy, PkgUi.ErrorText.resId(PackageService.ERR_PKG_WORKSPACE_BUSY));
    }

    @Test
    public void testCodeAndDetailOfMessages() {
        assertEquals(R.string.pkg_err_cache_clean_failed,
                PkgUi.ErrorText.resId("ERR_PKG_CACHE_CLEAN_FAILED: permission denied"));
        assertEquals("ERR_FETCH_LL_DB", PkgUi.ErrorText.codeOf(" ERR_FETCH_LL_DB: HTTP 503 "));
        assertEquals("HTTP 503", PkgUi.ErrorText.detailOf(" ERR_FETCH_LL_DB: HTTP 503 "));
        assertEquals("plain: text", PkgUi.ErrorText.codeOf("plain: text"));
        assertEquals("", PkgUi.ErrorText.detailOf("plain: text"));
        assertEquals(0, PkgUi.ErrorText.resId("ERR_SOMETHING_NEW"));
        assertEquals(0, PkgUi.ErrorText.resId(null));
    }

    @Test
    public void testPlainLanguageCauses() {
        assertEquals(Arrays.asList(R.string.pkg_err_cause_dns), PkgUi.ErrorText.causes(
                "Cannot get https://github.com/a/b.git: Unable to resolve host \"github.com\": No address associated with hostname"));
        assertEquals(Arrays.asList(R.string.pkg_err_cause_http_404), PkgUi.ErrorText.causes("HTTP 404 from https://x.test/a.zip"));
        assertEquals(Arrays.asList(R.string.pkg_err_cause_timeout, R.string.pkg_err_cause_conflict), PkgUi.ErrorText.causes(
                "Cannot satisfy example.com/a (needed by example.com/b): Cannot get https://x.test: timeout"));
        assertEquals(Arrays.asList(R.string.pkg_err_cause_corrupt), PkgUi.ErrorText.causes("the pack's checksum does not match"));
        assertEquals(Collections.emptyList(), PkgUi.ErrorText.causes("No package of example.com/a has an entry"));
        assertEquals(Collections.emptyList(), PkgUi.ErrorText.causes(null));
    }
}
