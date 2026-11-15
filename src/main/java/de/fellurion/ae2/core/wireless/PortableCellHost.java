/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.NullStorage;
import de.fellurion.ae2.core.storage.StorageHelper;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The terminal of a portable cell held in the hand. Port of {@code appeng.items.contents.PortableCellMenuHost}.
 * <ul>
 * <li>Storage: the cell inventory of the item itself.</li>
 * <li>Idle power while open: 0.5 AE/t from the item's battery; moving items costs the usual 1 AE per item (times the
 * config multiplier) from the same battery.</li>
 * <li>Link status: connected unless the battery cannot pay the idle power.</li>
 * </ul>
 */
public final class PortableCellHost extends PortableTerminalHost {
    private final Supplier<? extends MEStorage> cellStorage;
    private LinkStatus linkStatus = LinkStatus.OUT_OF_POWER;

    /**
     * @param battery     battery of the cell item
     * @param cellStorage the cell inventory of the item as it is now (may return null if the item is gone)
     */
    public PortableCellHost(Battery battery, Supplier<? extends MEStorage> cellStorage) {
        super(battery);
        this.cellStorage = Objects.requireNonNull(cellStorage, "cellStorage");
        updateLinkStatus();
    }

    @Override
    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            consumeIdlePower(Actionable.MODULATE);
        }
        updateLinkStatus();
    }

    private void updateLinkStatus() {
        if (!consumeIdlePower(Actionable.SIMULATE)) {
            this.linkStatus = LinkStatus.OUT_OF_POWER;
        } else {
            this.linkStatus = LinkStatus.CONNECTED;
        }
    }

    @Override
    public LinkStatus getLinkStatus() {
        return linkStatus;
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
        amt = usePowerMultiplier.multiply(amt);

        if (mode == Actionable.SIMULATE) {
            return usePowerMultiplier.divide(Math.min(amt, battery().getAECurrentPower()));
        }

        return usePowerMultiplier.divide(battery().extractAEPower(amt, Actionable.MODULATE));
    }

    @Override
    public MEStorage getInventory() {
        MEStorage storage = cellStorage.get();
        return storage == null ? NullStorage.of() : storage;
    }

    /**
     * Inserts into the cell, paying from the battery.
     *
     * @return the amount inserted (0 while not connected)
     */
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!linkStatus.connected()) {
            return 0;
        }
        return StorageHelper.poweredInsert(this, getInventory(), what, amount, source, mode);
    }

    /**
     * Extracts from the cell, paying from the battery.
     *
     * @return the amount extracted (0 while not connected)
     */
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!linkStatus.connected()) {
            return 0;
        }
        return StorageHelper.poweredExtraction(this, getInventory(), what, amount, source, mode);
    }
}
