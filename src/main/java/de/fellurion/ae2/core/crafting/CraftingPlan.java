/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.Map;

/**
 * Result of a crafting calculation. Port of {@code appeng.crafting.CraftingPlan} ({@code ICraftingPlan}).
 *
 * @param finalOutput   what will be crafted (amount may be lower than requested with CRAFT_LESS)
 * @param bytes         crafting storage bytes required on the CPU
 * @param simulation    true if the plan is incomplete (missing items); it cannot be submitted
 * @param multiplePaths whether alternative patterns existed
 * @param usedItems     items that will be extracted from the network when the job starts
 * @param emittedItems  items that will be requested from emitters
 * @param missingItems  items that are missing (only in simulation plans)
 * @param patternTimes  how often each pattern is executed
 */
public record CraftingPlan(GenericStack finalOutput,
        long bytes,
        boolean simulation,
        boolean multiplePaths,
        KeyCounter usedItems,
        KeyCounter emittedItems,
        KeyCounter missingItems,
        Map<IPatternDetails, Long> patternTimes) {
}
