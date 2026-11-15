/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.worldgen;

/**
 * What the crater of a meteorite is filled with. Port of {@code appeng.worldgen.meteorite.CraterType}; the order is
 * kept because the ordinal is persisted.
 */
public enum CraterType {
    /** No crater at all (ocean): the meteorite is buried. */
    NONE(null),
    /** A plain, empty crater. */
    NORMAL(MeteoriteBlock.AIR),
    LAVA(MeteoriteBlock.LAVA),
    OBSIDIAN(MeteoriteBlock.OBSIDIAN),
    WATER(MeteoriteBlock.WATER),
    SNOW(MeteoriteBlock.SNOW_BLOCK),
    ICE(MeteoriteBlock.ICE);

    private final MeteoriteBlock filler;

    CraterType(MeteoriteBlock filler) {
        this.filler = filler;
    }

    /** The block the bottom of the crater is filled with ({@code null} for {@link #NONE}). */
    public MeteoriteBlock filler() {
        return filler;
    }
}
