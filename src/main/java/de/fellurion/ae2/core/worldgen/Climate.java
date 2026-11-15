/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.worldgen;

/**
 * What the generator needs to know about the place a meteorite lands in. Replaces Minecraft's biome queries
 * (temperature, precipitation, biome tags) used by {@code MeteoriteStructure#determineCraterType} and
 * {@code FalloutMode#fromBiome}.
 *
 * @param ocean         open water: the meteorite is buried in the sea floor, without a crater
 * @param temperature   temperature class
 * @param precipitation whether it rains or snows here at all
 * @param canSnow       whether it is cold enough to snow at the landing position
 * @param fallout       debris style
 * @param biomeBlock    the typical surface block, used by the "copy" fallout styles
 */
public record Climate(boolean ocean, Temperature temperature, boolean precipitation, boolean canSnow,
        FalloutMode fallout, MeteoriteBlock biomeBlock) {

    /** Temperature classes: Minecraft base temperature {@code >= 1}, {@code [0.2, 1)}, {@code < 0.2}. */
    public enum Temperature {
        HOT, TEMPERATE, COLD
    }

    /** A temperate place with rain and default debris. */
    public static Climate temperate() {
        return new Climate(false, Temperature.TEMPERATE, true, false, FalloutMode.DEFAULT, MeteoriteBlock.COBBLESTONE);
    }
}
