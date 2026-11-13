package com.mojang.minecraftpe;

final class GameSurfaceSize {
    private int width = -1;
    private int height = -1;
    private int keyboard = Integer.MIN_VALUE;

    boolean changed(int nextWidth, int nextHeight, int nextKeyboard) {
        if (nextWidth <= 0 || nextHeight <= 0) return false;
        if (width == nextWidth && height == nextHeight && keyboard == nextKeyboard) return false;
        width = nextWidth;
        height = nextHeight;
        keyboard = nextKeyboard;
        return true;
    }

    void reset() {
        width = -1;
        height = -1;
        keyboard = Integer.MIN_VALUE;
    }
}
