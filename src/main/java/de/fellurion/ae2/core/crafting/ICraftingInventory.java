/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Inventory used by the crafting simulation and CPUs. Port of {@code appeng.crafting.inv.ICraftingInventory}.
 */
public interface ICraftingInventory {
    void insert(AEKey what, long amount, Actionable mode);

    long extract(AEKey what, long amount, Actionable mode);

    /** All stored keys in the same fuzzy (IGNORE_ALL) partition as {@code input}. */
    Iterable<AEKey> findFuzzyTemplates(AEKey input);
}
