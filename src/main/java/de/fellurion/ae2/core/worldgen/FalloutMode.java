/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.worldgen;

/**
 * Which debris a meteorite scatters, depending on the surroundings. Port of
 * {@code appeng.worldgen.meteorite.fallout.FalloutMode} and the {@code Fallout*} classes.
 */
public enum FalloutMode {
    /** Stone, cobblestone, dirt and gravel. */
    DEFAULT(0),
    /** Sandy surroundings: sand and glass. */
    SAND(2),
    /** Copies the typical block of the surroundings (terracotta in badlands). */
    TERRACOTTA(0),
    /** Cold surroundings: snow and ice. */
    ICE_SNOW(2);

    private final int craterAdjust;

    FalloutMode(int craterAdjust) {
        this.craterAdjust = craterAdjust;
    }

    /** Raises the bottom of the crater by this many blocks ({@code Fallout#adjustCrater}). */
    public int craterAdjust() {
        return craterAdjust;
    }
}
