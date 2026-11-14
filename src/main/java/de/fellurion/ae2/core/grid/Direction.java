/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

/**
 * The six block faces. The ordinal is the bit index of the cable connection mask:
 * bit0 = +X, bit1 = −X, bit2 = +Y, bit3 = −Y, bit4 = +Z, bit5 = −Z.
 * <p>
 * Replaces Minecraft's {@code Direction} enum (names follow the Minecraft convention: +Z = south).
 */
public enum Direction {
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0),
    SOUTH(0, 0, 1),
    NORTH(0, 0, -1);

    private static final Direction[] VALUES = values();

    public final int dx;
    public final int dy;
    public final int dz;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public Direction getOpposite() {
        return VALUES[ordinal() ^ 1];
    }

    /** Bit of this side in the 6-bit connection mask. */
    public int bit() {
        return 1 << ordinal();
    }

    public boolean isHorizontal() {
        return dy == 0;
    }

    public static Direction fromDelta(int dx, int dy, int dz) {
        for (var d : VALUES) {
            if (d.dx == dx && d.dy == dy && d.dz == dz) {
                return d;
            }
        }
        return null;
    }

    /** Builds the 6-bit cable mask of the given sides. */
    public static int mask(Iterable<Direction> sides) {
        int m = 0;
        for (var d : sides) {
            m |= d.bit();
        }
        return m;
    }
}
