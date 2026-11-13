package org.levimc.launcher.core.mods.inbuilt.overlay;

final class ModMenuGeometry {
    final int width;
    final int height;
    final int x;
    final int y;

    private ModMenuGeometry(int width, int height, int x, int y) {
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
    }

    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    static ModMenuGeometry fit(int availableWidth, int availableHeight, float density,
                               int widthPercent, int heightPercent, int positionX, int positionY) {
        int aw = Math.max(1, availableWidth);
        int ah = Math.max(1, availableHeight);
        int width = clamp(Math.round(aw * clamp(widthPercent, 35, 100) / 100f), Math.min(aw, Math.round(320 * density)), aw);
        int height = clamp(Math.round(ah * clamp(heightPercent, 55, 100) / 100f), Math.min(ah, Math.round(210 * density)), ah);
        return new ModMenuGeometry(width, height,
                Math.round((aw - width) * clamp(positionX, 0, 1000) / 1000f),
                Math.round((ah - height) * clamp(positionY, 0, 1000) / 1000f));
    }

    static int fraction(int position, int remaining) {
        return remaining <= 0 ? 500 : clamp(Math.round(position * 1000f / remaining), 0, 1000);
    }

    static int columns(int contentWidth, float density, boolean list) {
        return list ? 1 : clamp((int) (contentWidth / Math.max(1f, density * 254f)), 1, 4);
    }
}
