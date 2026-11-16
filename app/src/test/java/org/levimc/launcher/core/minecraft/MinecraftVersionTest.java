package org.levimc.launcher.core.minecraft;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MinecraftVersionTest {
    @Test
    public void isAtLeastAcceptsSupportedVersionsAndRejectsOlderOrUnknownVersions() {
        assertTrue(MinecraftVersion.isAtLeast("1.21.80", "1.21.80"));
        assertTrue(MinecraftVersion.isAtLeast("1.21.80.3", "1.21.80"));
        assertTrue(MinecraftVersion.isAtLeast("1.26.0", "1.21.80"));
        assertFalse(MinecraftVersion.isAtLeast("1.21.79", "1.21.80"));
        assertFalse(MinecraftVersion.isAtLeast("unknown", "1.21.80"));
        assertFalse(MinecraftVersion.isAtLeast("", "1.21.80"));
    }
}
