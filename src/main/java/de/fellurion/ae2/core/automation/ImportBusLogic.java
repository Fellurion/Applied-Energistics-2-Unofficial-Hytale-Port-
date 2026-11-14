/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEKeyType;

/**
 * Import bus: pulls stacks from the inventory in front into the network. Port of
 * {@code appeng.parts.automation.ImportBusPart} with {@code StorageImportStrategy}.
 * <p>
 * Per operation round: for each slot of the target (in order) whose key passes the filter (inverted with an inverter
 * card), simulate how much the network accepts (up to {@code operations · amountPerOperation}), extract that from the
 * target and insert it into the network (overflow is given back). Each transferred resource uses
 * {@code max(1, inserted / amountPerOperation)} operations. Without a filter everything is imported.
 * <p>
 * Note (faithful to this AE2 version): the import bus inserts into the network storage directly, without charging
 * the AE cost of {@code StorageHelper.poweredInsert}. The bus only works while its node is active (powered).
 */
public class ImportBusLogic extends IOBusLogic {
    public static final String ITEM_ID = "AE2_Import_Bus";

    public ImportBusLogic() {
        super(TickRates.ImportBus, ITEM_ID);
    }

    @Override
    protected boolean doBusWork(Grid grid) {
        var target = getTarget();
        if (target == null) {
            return false;
        }
        var context = new StackTransferContext(grid.getStorageService(), grid.getEnergyService(), source,
                getOperationsPerTick(), getFilter());
        context.setInverted(isUpgradedWith(UpgradeCard.INVERTER));
        transfer(context, target);
        return context.hasDoneWork();
    }

    /**
     * Port of {@code StackImportFacade} + {@code StorageImportStrategy.transfer}: one strategy per key type (items,
     * then fluids), each with its own transfer budget of {@code operations · amountPerOperation}.
     */
    private static void transfer(StackTransferContext context, ExternalStorage adjacentStorage) {
        for (var keyType : AEKeyType.values()) {
            transfer(context, adjacentStorage, keyType);
        }
    }

    private static void transfer(StackTransferContext context, ExternalStorage adjacentStorage, AEKeyType keyType) {
        if (!context.isKeyTypeEnabled(keyType)) {
            return;
        }
        long remainingTransferAmount = context.getOperationsRemaining() * (long) keyType.getAmountPerOperation();
        var inv = context.getInternalStorage().getInventory();

        // Try to find an extractable resource that fits our filter
        for (int i = 0; i < adjacentStorage.getSlots() && remainingTransferAmount > 0; i++) {
            var resource = adjacentStorage.getStackInSlot(i);
            if (resource == null || resource.what().getType() != keyType
                    // Regard a filter that is set on the bus
                    || context.isInFilter(resource.what()) == context.isInverted()) {
                continue;
            }

            // Check how much of *this* resource we can actually insert into the network, it might be 0
            // if the cells are partitioned or there's not enough types left, etc.
            var amountForThisResource = inv.insert(resource.what(), remainingTransferAmount, Actionable.SIMULATE,
                    context.getActionSource());

            var amount = adjacentStorage.extract(resource.what(), amountForThisResource, Actionable.MODULATE,
                    context.getActionSource());
            if (amount > 0) {
                var inserted = inv.insert(resource.what(), amount, Actionable.MODULATE, context.getActionSource());
                if (inserted < amount) {
                    // Be nice and try to give the overflow back
                    long leftover = amount - inserted;
                    adjacentStorage.insert(resource.what(), leftover, Actionable.MODULATE,
                            context.getActionSource());
                }
                var opsUsed = Math.max(1, inserted / keyType.getAmountPerOperation());
                context.reduceOperationsRemaining(opsUsed);
                remainingTransferAmount -= inserted;
            }
        }
    }
}
