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
 * Where meteorites are and what they look like. Port of the structure set
 * ({@code spacing 32, separation 8, salt 124895654}) and of {@code appeng.worldgen.meteorite.MeteoriteStructure}.
 *
 * <p>The world is divided into regions of {@value #REGION}×{@value #REGION} blocks. Every region has exactly one
 * candidate position, half of the candidates become a meteorite: about one meteorite per 724×724 blocks, never
 * closer than {@value #SEPARATION} blocks to the candidate of a neighbouring region. Everything is derived from the
 * world seed, so the same world always has the same meteorites.
 */
public final class MeteoriteLocator {

    /** Edge length of a region in blocks (Minecraft: spacing 32 chunks of 16 blocks). */
    public static final int REGION = 512;
    /** Minimum distance between candidates of neighbouring regions (Minecraft: separation 8 chunks). */
    public static final int SEPARATION = 128;
    private static final int SALT = 124895654;
    private static final int CELL = 16;

    private MeteoriteLocator() {
    }

    /**
     * A meteorite position before the terrain is known.
     *
     * @param x      centre
     * @param z      centre
     * @param radius meteorite radius {@code [2, 8)}
     * @param seed   seed for everything else about this meteorite
     */
    public record Candidate(int regionX, int regionZ, int x, int z, float radius, long seed) {
    }

    /** Heights of the terrain, as far as the planner needs them. */
    public interface Surface {
        /** Y of the highest block that is not air (water counts). */
        int surfaceHeight(int x, int z);

        /** Y of the highest solid block (the sea floor under water). */
        int groundHeight(int x, int z);

        int seaLevel();

        int minY();
    }

    public static int region(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, REGION);
    }

    /** The meteorite of a region, or null if this region has none. */
    public static Candidate candidate(long worldSeed, int regionX, int regionZ) {
        Random rnd = new Random(regionX * 341873128712L + regionZ * 132897987541L + worldSeed + SALT);
        int cellX = rnd.nextInt((REGION - SEPARATION) / CELL);
        int cellZ = rnd.nextInt((REGION - SEPARATION) / CELL);
        if (!rnd.nextBoolean()) {
            return null; // MeteoriteStructure#findGenerationPoint: 50 % of the candidates are dropped
        }
        int x = regionX * REGION + cellX * CELL + rnd.nextInt(CELL);
        int z = regionZ * REGION + cellZ * CELL + rnd.nextInt(CELL);
        float radius = rnd.nextFloat() * 6.0f + 2;
        return new Candidate(regionX, regionZ, x, z, radius, rnd.nextLong());
    }

    /**
     * Plans the meteorite: height, crater type, lake. Port of {@code MeteoriteStructure#generatePieces}.
     */
    public static MeteoriteSite plan(Candidate c, Climate climate, Surface surface) {
        Random rnd = new Random(c.seed());
        int y = centerY(c, climate.ocean(), surface);
        boolean lake = waterAroundCrater(c.x(), y, c.z(), c.radius(), surface);
        CraterType crater = craterType(climate, rnd);
        boolean pure = rnd.nextFloat() > 0.9f;
        return new MeteoriteSite(c.x(), y, c.z(), c.radius(), crater, climate.fallout(), climate.biomeBlock(), pure, lake,
                rnd.nextLong());
    }

    /**
     * The centre sits {@code ceil(radius) + 1} below the mean surface around it; on rough terrain it sinks further.
     */
    static int centerY(Candidate c, boolean ocean, Surface surface) {
        int yOffset = (int) Math.ceil(c.radius()) + 1;
        int scanRadius = Math.max(1, (int) (c.radius() * 2));
        // AE2 reads every column of the square; a grid of at most 9x9 samples gives the same statistics and keeps
        // the number of (expensive) height queries small.
        int step = Math.max(1, scanRadius / 4);
        long sum = 0;
        long sumSquares = 0;
        int min = Integer.MAX_VALUE;
        int n = 0;
        for (int dx = -scanRadius; dx <= scanRadius; dx += step) {
            for (int dz = -scanRadius; dz <= scanRadius; dz += step) {
                int h = ocean ? surface.groundHeight(c.x() + dx, c.z() + dz) : surface.surfaceHeight(c.x() + dx, c.z() + dz);
                sum += h;
                sumSquares += (long) h * h;
                min = Math.min(min, h);
                n++;
            }
        }
        double mean = sum / (double) n;
        double variance = sumSquares / (double) n - mean * mean;
        int y = (int) mean;
        if (variance > 5) {
            y = (int) (y - (mean - min) * 0.75);
        }
        y -= yOffset;
        return Math.max(surface.minY() + yOffset, y);
    }

    /** Port of {@code MeteoriteStructure#locateWaterAroundTheCrater}: is the ground at the rim below sea level? */
    static boolean waterAroundCrater(int x, int y, int z, float radius, Surface surface) {
        int seaLevel = surface.seaLevel();
        int maxY = seaLevel - 1;
        double h = y - radius + 1;
        // every second column is enough to find water at the rim
        for (int i = x - 32; i <= x + 32; i += 2) {
            for (int k = z - 32; k <= z + 32; k += 2) {
                double d2 = (double) (i - x) * (i - x) + (double) (k - z) * (k - z);
                if (maxY > h + d2 * 0.0175 && maxY < h + d2 * 0.02 && surface.groundHeight(i, k) < seaLevel) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Port of {@code MeteoriteStructure#determineCraterType}. */
    static CraterType craterType(Climate climate, Random rnd) {
        if (climate.ocean()) {
            return CraterType.NONE;
        }
        if (!(rnd.nextFloat() > 0.5f)) {
            return CraterType.NORMAL;
        }
        boolean canSnow = climate.canSnow();
        boolean precipitation = climate.precipitation();
        switch (climate.temperature()) {
            case HOT -> {
                boolean lava = rnd.nextFloat() > 0.5f;
                if (!precipitation) {
                    return lava ? CraterType.LAVA : CraterType.NORMAL;
                } else if (!canSnow) {
                    boolean obsidian = rnd.nextFloat() > 0.75f;
                    return lava ? (obsidian ? CraterType.OBSIDIAN : CraterType.LAVA) : CraterType.NORMAL;
                }
                return CraterType.NORMAL;
            }
            case TEMPERATE -> {
                boolean lake = rnd.nextFloat() > 0.25f;
                boolean lava = rnd.nextFloat() > 0.8f;
                if (!precipitation) {
                    return lava ? CraterType.LAVA : CraterType.NORMAL;
                } else if (!canSnow) {
                    boolean obsidian = rnd.nextFloat() > 0.75f;
                    return lava ? (obsidian ? CraterType.OBSIDIAN : CraterType.LAVA)
                            : (lake ? CraterType.WATER : CraterType.NORMAL);
                } else {
                    boolean snow = rnd.nextFloat() > 0.75f;
                    return snow ? CraterType.SNOW : (lake ? CraterType.WATER : CraterType.NORMAL);
                }
            }
            default -> {
                boolean lake = rnd.nextFloat() > 0.25f;
                boolean lava = rnd.nextFloat() > 0.95f;
                boolean frozen = rnd.nextFloat() > 0.25f;
                if (!precipitation) {
                    return lava ? CraterType.LAVA : CraterType.NORMAL;
                } else if (!canSnow) {
                    return lava ? CraterType.LAVA : (lake ? (frozen ? CraterType.ICE : CraterType.WATER) : CraterType.NORMAL);
                } else {
                    return lava ? CraterType.LAVA : (lake ? CraterType.SNOW : CraterType.NORMAL);
                }
            }
        }
    }
}
