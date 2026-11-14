/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Creative storage cell: provides {@link Integer#MAX_VALUE} of every configured key and voids insertions of them.
 * Port of {@code appeng.me.cells.CreativeCellInventory}.
 */
public final class CreativeCellInventory implements StorageCell {
    private final Set<AEKey> configured;

    public CreativeCellInventory(CellItemData data) {
        this.configured = new LinkedHashSet<>(data.getConfigKeys());
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return configured.contains(what) ? amount : 0;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        return configured.contains(what) ? amount : 0;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (var key : configured) {
            out.add(key, Integer.MAX_VALUE);
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey input, IActionSource source) {
        return configured.contains(input);
    }

    @Override
    public CellState getStatus() {
        return CellState.TYPES_FULL;
    }

    @Override
    public double getIdleDrain() {
        return 0;
    }

    @Override
    public boolean canFitInsideCell() {
        return configured.isEmpty();
    }

    @Override
    public String getDescription() {
        return CellDefinition.CREATIVE_CELL_ID;
    }

    @Override
    public void persist() {
    }
}
