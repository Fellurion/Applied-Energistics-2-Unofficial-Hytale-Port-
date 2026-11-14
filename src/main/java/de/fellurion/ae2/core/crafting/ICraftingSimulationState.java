/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Simulation inventory of a crafting calculation. Port of {@code appeng.crafting.inv.ICraftingSimulationState}.
 */
public interface ICraftingSimulationState extends ICraftingInventory {
    void emitItems(AEKey what, long amount);

    void addBytes(double bytes);

    /**
     * Adds the bytes needed to store the given amount: {@code amount * multiplier / amountPerByte * 8}. Crafting
     * storage is 8 times bigger than normal storage, this is intentional (AE2).
     */
    default void addStackBytes(AEKey key, long amount, long multiplier) {
        addBytes((double) amount * multiplier / key.getType().getAmountPerByte() * 8);
    }

    void addCrafting(IPatternDetails details, long crafts);
}
