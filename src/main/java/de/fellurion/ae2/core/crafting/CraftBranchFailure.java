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
 * Thrown when a branch of the crafting tree cannot be satisfied. Port of {@code appeng.crafting.CraftBranchFailure}.
 */
public final class CraftBranchFailure extends Exception {
    private final AEKey missing;
    private final long amount;

    public CraftBranchFailure(AEKey what, long howMany) {
        super("Failed: " + what + " x " + howMany, null, false, false);
        this.missing = what;
        this.amount = howMany;
    }

    public AEKey getMissing() {
        return missing;
    }

    public long getAmount() {
        return amount;
    }
}
