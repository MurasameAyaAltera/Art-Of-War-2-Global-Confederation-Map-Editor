package com.aowmaps;

import android.graphics.Color;

final class Palette {
    private Palette() {}

    /** Stable colour for a tile / object id (the game's tileset art is not decoded yet). */
    static int color(int id) {
        float[] hsv = new float[]{(id * 137.5f) % 360f, 0.45f + ((id * 7) % 3) * 0.17f, 0.50f + ((id * 13) % 3) * 0.20f};
        return Color.HSVToColor(hsv);
    }
}
