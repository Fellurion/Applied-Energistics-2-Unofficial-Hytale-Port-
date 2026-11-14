/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

/**
 * Packs block coordinates into a {@code long} key, used to identify grid nodes in {@link MEWorld}.
 * <p>
 * Layout (like Minecraft's {@code BlockPos.asLong}): x and z use 26 signed bits (±33,554,431), y uses 12 signed bits
 * (−2048..2047). The integration layer must use {@link #asLong(int, int, int)} to build keys so that neighbour lookups
 * ({@link #offset(long, Direction)}) work.
 */
public final class BlockPos {
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;

    private BlockPos() {
    }

    public static long asLong(int x, int y, int z) {
        return ((long) x & XZ_MASK) << X_SHIFT | ((long) z & XZ_MASK) << Z_SHIFT | ((long) y & Y_MASK);
    }

    public static int getX(long pos) {
        return (int) (pos << (64 - X_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int getY(long pos) {
        return (int) (pos << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int getZ(long pos) {
        return (int) (pos << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static long offset(long pos, Direction d) {
        return asLong(getX(pos) + d.dx, getY(pos) + d.dy, getZ(pos) + d.dz);
    }

    public static long offset(long pos, int dx, int dy, int dz) {
        return asLong(getX(pos) + dx, getY(pos) + dy, getZ(pos) + dz);
    }

    public static String toString(long pos) {
        return getX(pos) + "," + getY(pos) + "," + getZ(pos);
    }
}
