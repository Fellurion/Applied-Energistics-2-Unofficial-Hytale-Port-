/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Hook for crafting-card behaviour of export buses and interfaces. In AE2 this is
 * {@code appeng.helpers.MultiCraftingTracker} talking to the grid's crafting service; autocrafting is implemented in
 * the {@code crafting} package, so the automation logic only calls this hook (if set and a crafting card is installed).
 */
public interface CraftingHook {
    /**
     * Requests crafting of {@code amount} of {@code what} for the given config slot.
     *
     * @return true if a job was started or is still running for that slot (counts as work done)
     */
    boolean requestCrafting(int slot, AEKey what, long amount);

    /** True while a crafting job for the slot is still running. */
    default boolean isBusy(int slot) {
        return false;
    }

    /** Cancels all requested jobs (crafting card removed). */
    default void cancelAll() {
    }
}
