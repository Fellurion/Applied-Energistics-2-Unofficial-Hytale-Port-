/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.storage.MEStorage;

/**
 * An external (non-ME) inventory adjacent to an import/export/storage bus, seen as {@link MEStorage} with
 * slot-level read access. Port of the abstract part of {@code appeng.me.storage.ExternalStorageFacade}.
 * <p>
 * For item containers use {@link ExternalStorageFacade} over an {@code inv.SlotAccess}. For fluid tanks (or anything
 * else) the integration layer can implement this interface directly with {@code AEFluidKey}s (amounts in mB).
 */
public interface ExternalStorage extends MEStorage {
    /** Number of slots. */
    int getSlots();

    /** Content of a slot, or null if empty. */
    GenericStack getStackInSlot(int slot);

    @Override
    default int getEstimatedStackCount() {
        return getSlots();
    }
}
