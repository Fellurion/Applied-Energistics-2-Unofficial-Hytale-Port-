/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.worldgen;

import java.util.Random;

/**
 * Builds a meteorite: crater, sky stone body with the certus quartz room and the Mysterious Cube, decay of the
 * crater and the debris around it, and the crater lake. Port of {@code appeng.worldgen.meteorite.MeteoritePlacer}.
 *
 * <p>Differences to the original, all of them to make the result independent of the order in which parts of the
 * world are processed: the random decisions for decay and debris are derived from the block position instead of a
 * running random; the cap on the number of debris blocks ({@code 100 * crater}, never reached in practice) is
 * dropped; and nothing is changed farther than {@link MeteoriteSite#REACH} blocks from the centre. A meteorite can
 * therefore be placed in one go or piece by piece (see the bounds of {@link #place}) with the same result.
 */
public final class MeteoritePlacer {

    /** Access to the blocks of the world. Coordinates are absolute. */
    public interface Terrain {
        /** Highest Y that may be changed. */
        int maxY();

        int seaLevel();

        /** Y of the highest block of this column that is not air (below the lowest Y if the column is empty). */
        int columnTop(int x, int z);

        boolean isAir(int x, int y, int z);

        /** A solid block: not air, not a fluid, not a plant. */
        boolean isSolid(int x, int y, int z);

        /** Something that may simply be overwritten: air, fluids, plants. */
        boolean isReplaceable(int x, int y, int z);

        boolean hasFluid(int x, int y, int z);

        /** Whether the block here is the given material. */
        boolean is(int x, int y, int z, MeteoriteBlock block);

        /** Places a block. The implementation must never replace indestructible blocks. */
        void set(int x, int y, int z, MeteoriteBlock block);

        /** Makes the block here a copy of the block above it. */
        void copyFromAbove(int x, int y, int z);
    }

    private static final MeteoriteBlock[] QUARTZ_BLOCKS = {MeteoriteBlock.QUARTZ_BLOCK, MeteoriteBlock.DAMAGED_BUDDING,
            MeteoriteBlock.CHIPPED_BUDDING, MeteoriteBlock.FLAWED_BUDDING, MeteoriteBlock.FLAWLESS_BUDDING};
    private static final MeteoriteBlock[] QUARTZ_BUDS = {MeteoriteBlock.SMALL_BUD, MeteoriteBlock.MEDIUM_BUD,
            MeteoriteBlock.LARGE_BUD};

    private final Terrain terrain;
    private final MeteoriteSite site;
    private final int x, y, z;
    private final double radius;
    private final double squaredRadius;
    /** Squared(!) distance used by the decay, {@code (2r + 5)^2}. */
    private final double crater;
    private final double craterBottom;
    private final int minX, maxX, minZ, maxZ;

    private MeteoritePlacer(Terrain terrain, MeteoriteSite site, int minX, int minZ, int maxX, int maxZ) {
        this.terrain = terrain;
        this.site = site;
        this.x = site.x();
        this.y = site.y();
        this.z = site.z();
        this.radius = site.radius();
        this.squaredRadius = radius * radius;
        double r = radius * 2 + 5;
        this.crater = r * r;
        this.craterBottom = y - radius + 1 + site.fallout().craterAdjust();
        this.minX = Math.max(minX, x - MeteoriteSite.REACH);
        this.maxX = Math.min(maxX, x + MeteoriteSite.REACH);
        this.minZ = Math.max(minZ, z - MeteoriteSite.REACH);
        this.maxZ = Math.min(maxZ, z + MeteoriteSite.REACH);
    }

    /** Places the whole meteorite. */
    public static void place(Terrain terrain, MeteoriteSite site) {
        place(terrain, site, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
    }

    /** Places the part of the meteorite inside the given columns (inclusive bounds). */
    public static void place(Terrain terrain, MeteoriteSite site, int minX, int minZ, int maxX, int maxZ) {
        MeteoritePlacer p = new MeteoritePlacer(terrain, site, minX, minZ, maxX, maxZ);
        if (p.minX > p.maxX || p.minZ > p.maxZ) {
            return;
        }
        if (site.crater() != CraterType.NONE) {
            p.placeCrater();
        }
        p.placeMeteorite();
        if (site.crater() != CraterType.NONE) {
            p.decay();
        }
        if (site.lake()) {
            p.placeCraterLake();
        }
    }

    private boolean inBounds(int i, int k) {
        return i >= minX && i <= maxX && k >= minZ && k <= maxZ;
    }

    // ------------------------------------------------------------------ crater

    private void placeCrater() {
        MeteoriteBlock filler = site.crater().filler();
        boolean filled = site.crater() != CraterType.NORMAL;
        for (int i = minX; i <= maxX; i++) {
            for (int k = minZ; k <= maxZ; k++) {
                double d2 = (double) (i - x) * (i - x) + (double) (k - z) * (k - z);
                double surface = craterBottom + d2 * 0.02;
                int top = Math.min(terrain.maxY(), terrain.columnTop(i, k));
                for (int j = Math.max(y - 5, (int) Math.floor(surface) + 1); j <= top; j++) {
                    if (j > surface) {
                        if (filled && j < y && terrain.isSolid(i, j, k)) {
                            terrain.set(i, j, k, filler);
                        } else if (!terrain.isAir(i, j, k)) {
                            terrain.set(i, j, k, MeteoriteBlock.AIR);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ meteorite

    private void placeMeteorite() {
        // The contents are always computed in the same order, whatever the bounds are.
        Random rnd = new Random(site.seed() ^ 0x6D65_7465_6F72L);
        for (int i = x - 8; i <= x + 8; i++) {
            for (int j = y - 8; j < y + 8; j++) {
                for (int k = z - 8; k <= z + 8; k++) {
                    int dx = i - x;
                    int dy = j - y;
                    int dz = k - z;
                    if (dx * dx * 0.7 + dy * dy * (j > y ? 1.4 : 0.8) + dz * dz * 0.7 >= squaredRadius) {
                        continue;
                    }
                    if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1) {
                        // the 3x3x3 room in the centre: only its floor is built
                        if (dy == -1) {
                            int index = rnd.nextInt(QUARTZ_BLOCKS.length);
                            boolean bud = index != 0 && (dx != 0 || dz != 0) && rnd.nextFloat() <= 0.7f;
                            MeteoriteBlock budBlock = bud ? QUARTZ_BUDS[rnd.nextInt(QUARTZ_BUDS.length)] : null;
                            if (inBounds(i, k)) {
                                terrain.set(i, j, k, QUARTZ_BLOCKS[index]);
                                if (budBlock != null) {
                                    terrain.set(i, j + 1, k, budBlock);
                                }
                            }
                        }
                    } else if (inBounds(i, k)) {
                        terrain.set(i, j, k, MeteoriteBlock.SKY_STONE);
                    }
                }
            }
        }
        if (inBounds(x, z)) {
            terrain.set(x, y, z, MeteoriteBlock.MYSTERIOUS_CUBE);
        }
    }

    // ------------------------------------------------------------------ decay

    private void decay() {
        MeteoriteBlock filler = site.crater().filler();
        int fromX = Math.max(minX, x - 30), toX = Math.min(maxX, x + 30);
        int fromZ = Math.max(minZ, z - 30), toZ = Math.min(maxZ, z + 30);
        int toY = Math.min(terrain.maxY() - 1, y + 29);
        for (int i = fromX; i <= toX; i++) {
            for (int k = fromZ; k <= toZ; k++) {
                for (int j = y - 9; j <= toY; j++) {
                    if (site.pure() && terrain.is(i, j, k, filler)) {
                        continue;
                    }
                    if (terrain.isReplaceable(i, j, k)) {
                        if (!terrain.isAir(i, j + 1, k)) {
                            terrain.copyFromAbove(i, j, k);
                        } else if (!terrain.isReplaceable(i, j - 1, k)) {
                            double dist2 = dist2(i, j, k);
                            double extraRange = random(i, j, k, 1) * 0.6;
                            double height = crater * (extraRange + 0.2) - Math.abs(dist2 - crater * 1.7);
                            if (!terrain.isAir(i, j - 1, k) && height > 0 && random(i, j, k, 2) > 0.6) {
                                randomFall(i, j, k);
                            }
                        }
                    } else if (terrain.isAir(i, j + 1, k) && random(i, j, k, 3) > 0.4) {
                        boolean room = Math.abs(i - x) <= 1 && Math.abs(j - y) <= 1 && Math.abs(k - z) <= 1;
                        if (!room && dist2(i, j, k) < crater * 1.6) {
                            randomInset(i, j, k);
                        }
                    }
                }
            }
        }
    }

    private double dist2(int i, int j, int k) {
        double dx = i - x, dy = j - y, dz = k - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------ crater lake

    private void placeCraterLake() {
        int maxY = terrain.seaLevel() - 1;
        for (int i = minX; i <= maxX; i++) {
            for (int k = minZ; k <= maxZ; k++) {
                double d2 = (double) (i - x) * (i - x) + (double) (k - z) * (k - z);
                double surface = craterBottom + d2 * 0.02;
                for (int j = y - 5; j <= maxY; j++) {
                    if (j > surface) {
                        if (terrain.isAir(i, j, k)) {
                            terrain.set(i, j, k, MeteoriteBlock.WATER);
                        }
                    } else if (maxY + (maxY - j) * 2 + 2 > surface) {
                        // seal the slope so the lake cannot run out through caves
                        for (int n = 0; n < 20; n++) {
                            int p = j - n;
                            if (terrain.isAir(i, p, k) || (!terrain.hasFluid(i, p, k) && terrain.isReplaceable(i, p, k))) {
                                if (site.crater() == CraterType.LAVA && random(i, p, k, 4) < 0.075) {
                                    terrain.set(i, p, k, MeteoriteBlock.MAGMA);
                                } else {
                                    randomFall(i, p, k);
                                }
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ fallout

    /** Debris dropped on the ground around the crater ({@code Fallout#getRandomFall} and subclasses). */
    private void randomFall(int i, int j, int k) {
        double a = random(i, j, k, 5);
        switch (site.fallout()) {
            case DEFAULT -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, MeteoriteBlock.STONE);
                } else if (a > 0.8) {
                    terrain.set(i, j, k, MeteoriteBlock.COBBLESTONE);
                } else if (a > 0.7) {
                    terrain.set(i, j, k, MeteoriteBlock.DIRT);
                } else {
                    terrain.set(i, j, k, MeteoriteBlock.GRAVEL);
                }
            }
            case TERRACOTTA -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, site.biomeBlock());
                }
            }
            case SAND -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, site.biomeBlock());
                } else if (a > 0.66) {
                    terrain.set(i, j, k, MeteoriteBlock.GLASS);
                }
            }
            case ICE_SNOW -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, site.biomeBlock());
                } else if (a > 0.7) {
                    terrain.set(i, j, k, MeteoriteBlock.SNOW_LAYER);
                } else if (a > 0.5) {
                    terrain.set(i, j, k, MeteoriteBlock.ICE);
                }
            }
        }
    }

    /** Roughens an exposed block inside the crater ({@code Fallout#getRandomInset} and subclasses). */
    private void randomInset(int i, int j, int k) {
        double a = random(i, j, k, 6);
        switch (site.fallout()) {
            case DEFAULT -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, MeteoriteBlock.COBBLESTONE);
                } else if (a > 0.8) {
                    terrain.set(i, j, k, MeteoriteBlock.STONE);
                } else if (a > 0.7) {
                    terrain.set(i, j, k, MeteoriteBlock.GRASS);
                } else if (a > 0.6) {
                    terrain.set(i, j, k, MeteoriteBlock.SKY_STONE);
                } else if (a > 0.5) {
                    terrain.set(i, j, k, MeteoriteBlock.GRAVEL);
                } else {
                    terrain.set(i, j, k, MeteoriteBlock.AIR);
                }
            }
            case TERRACOTTA -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, site.biomeBlock());
                } else if (a > 0.8) {
                    terrain.set(i, j, k, MeteoriteBlock.AIR);
                }
            }
            case SAND -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, site.biomeBlock());
                } else if (a > 0.8) {
                    terrain.set(i, j, k, MeteoriteBlock.AIR);
                } else if (a > 0.76) {
                    terrain.set(i, j, k, MeteoriteBlock.GLASS);
                }
            }
            case ICE_SNOW -> {
                if (a > 0.9) {
                    terrain.set(i, j, k, site.biomeBlock());
                } else if (a > 0.8) {
                    terrain.set(i, j, k, MeteoriteBlock.AIR);
                } else if (a > 0.6) {
                    terrain.set(i, j, k, MeteoriteBlock.ICE);
                }
            }
        }
    }

    /** A random number in {@code [0, 1)} that only depends on the meteorite, the position and the purpose. */
    private double random(int i, int j, int k, int salt) {
        long h = site.seed();
        h ^= i * 0x9E3779B97F4A7C15L;
        h = mix(h);
        h ^= j * 0xC2B2AE3D27D4EB4FL;
        h = mix(h);
        h ^= k * 0x165667B19E3779F9L;
        h = mix(h);
        h ^= salt * 0x27D4EB2F165667C5L;
        h = mix(h);
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
