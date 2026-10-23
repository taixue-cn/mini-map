package io.github.minimap.protocol;

public record TilePos(int x, int z) {
    public static final int SIDE = 64;
    public static final int PIXELS = SIDE * SIDE;
    public TilePos {
        if (Math.abs((long) x * SIDE) > 30_000_064L || Math.abs((long) z * SIDE) > 30_000_064L)
            throw new IllegalArgumentException("Tile outside supported world bounds");
    }
}
