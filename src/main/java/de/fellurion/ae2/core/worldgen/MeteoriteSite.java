/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.worldgen;

/**
 * A fully planned meteorite. Port of {@code appeng.worldgen.meteorite.PlacedMeteoriteSettings}.
 *
 * @param x          centre
 * @param y          centre (the Mysterious Cube sits here)
 * @param z          centre
 * @param radius     meteorite radius, {@code [2, 8)}
 * @param crater     crater type
 * @param fallout    debris style
 * @param biomeBlock typical surface block for the "copy" debris styles
 * @param pure       "pure crater": the filler of the crater is left untouched by the decay
 * @param lake       the crater touches water and is flooded up to sea level
 * @param seed       seed of this meteorite (contents and debris are derived from it)
 */
public record MeteoriteSite(int x, int y, int z, float radius, CraterType crater, FalloutMode fallout,
        MeteoriteBlock biomeBlock, boolean pure, boolean lake, long seed) {

    /** Farthest horizontal distance from the centre at which the generator changes blocks. */
    public static final int REACH = 40;

    public MeteoriteSite withCenterY(int newY) {
        return new MeteoriteSite(x, newY, z, radius, crater, fallout, biomeBlock, pure, lake, seed);
    }

    /** The same meteorite with another crater and without a crater lake. */
    public MeteoriteSite withCrater(CraterType newCrater) {
        return new MeteoriteSite(x, y, z, radius, newCrater, fallout, biomeBlock, pure, false, seed);
    }
}
