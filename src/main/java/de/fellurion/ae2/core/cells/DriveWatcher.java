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
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEInventoryHandler;

/**
 * Wraps a cell mounted in a drive / ME chest and reports status changes (for LED updates). Port of
 * {@code appeng.me.storage.DriveWatcher}.
 */
public final class DriveWatcher extends MEInventoryHandler {
    private CellState oldStatus;
    private final Runnable activityCallback;

    public DriveWatcher(StorageCell cell, Runnable activityCallback) {
        super(cell);
        this.activityCallback = activityCallback;
        this.oldStatus = getStatus();
    }

    public CellState getStatus() {
        return getCell().getStatus();
    }

    public StorageCell getCell() {
        return (StorageCell) getDelegate();
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        var inserted = super.insert(what, amount, mode, source);
        if (mode == Actionable.MODULATE && inserted > 0) {
            checkStatus();
        }
        return inserted;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        var extracted = super.extract(what, amount, mode, source);
        if (mode == Actionable.MODULATE && extracted > 0) {
            checkStatus();
        }
        return extracted;
    }

    private void checkStatus() {
        var newStatus = getStatus();
        if (newStatus != oldStatus) {
            oldStatus = newStatus;
            if (activityCallback != null) {
                activityCallback.run();
            }
        }
    }
}
