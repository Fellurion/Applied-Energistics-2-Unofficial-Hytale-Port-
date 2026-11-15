/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.worldgen;

/**
 * Abstract materials the meteorite generator places. The platform maps them to real block ids (and may map several
 * of them to the same block if it has no closer equivalent).
 */
public enum MeteoriteBlock {
    AIR,
    SKY_STONE,
    MYSTERIOUS_CUBE,
    QUARTZ_BLOCK,
    DAMAGED_BUDDING,
    CHIPPED_BUDDING,
    FLAWED_BUDDING,
    FLAWLESS_BUDDING,
    /** Quartz buds are always placed facing up. */
    SMALL_BUD,
    MEDIUM_BUD,
    LARGE_BUD,
    LAVA,
    OBSIDIAN,
    WATER,
    SNOW_BLOCK,
    SNOW_LAYER,
    ICE,
    MAGMA,
    GLASS,
    TERRACOTTA,
    SAND,
    DIRT,
    GRASS,
    COBBLESTONE,
    STONE,
    GRAVEL
}
