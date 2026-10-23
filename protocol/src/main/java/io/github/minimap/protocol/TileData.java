package io.github.minimap.protocol;

import java.util.Arrays;

/** Immutable, row-major surface samples. Alpha zero represents an absent pixel. */
public final class TileData {
    private final int[] colors, heights;
    private final byte[] lights;
    public TileData(int[] colors, int[] heights, byte[] lights) {
        if (colors.length != TilePos.PIXELS || heights.length != TilePos.PIXELS || lights.length != TilePos.PIXELS)
            throw new IllegalArgumentException("Expected a 64 by 64 tile");
        this.colors = colors.clone(); this.heights = heights.clone(); this.lights = lights.clone();
        for (int i = 0; i < colors.length; i++) {
            if ((colors[i] >>> 24) == 0) { this.colors[i] = 0; this.heights[i] = 0; this.lights[i] = 0; }
            if (this.lights[i] < 0 || this.lights[i] > 15 || this.heights[i] < -32768 || this.heights[i] > 32767)
                throw new IllegalArgumentException("Invalid surface sample");
        }
    }
    public int color(int i) { return colors[i]; }
    public int height(int i) { return heights[i]; }
    public byte light(int i) { return lights[i]; }
    public boolean empty() { return Arrays.stream(colors).noneMatch(c -> (c >>> 24) != 0); }
}
