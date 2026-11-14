/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.StorageService;
import de.fellurion.ae2.core.storage.partition.IPartitionList;

import java.util.EnumSet;
import java.util.Set;

/**
 * State of one bus operation. Port of {@code appeng.parts.automation.StackTransferContextImpl} (and the
 * {@code StackTransferContext} API).
 */
final class StackTransferContext {
    private final StorageService internalStorage;
    private final IEnergySource energySource;
    private final IActionSource actionSource;
    private final IPartitionList filter;
    private final Set<AEKeyType> keyTypes = EnumSet.noneOf(AEKeyType.class);
    private final int initialOperations;
    private int operationsRemaining;
    private boolean isInverted;

    StackTransferContext(StorageService internalStorage, IEnergySource energySource, IActionSource actionSource,
            int operationsRemaining, IPartitionList filter) {
        this.internalStorage = internalStorage;
        this.energySource = energySource;
        this.actionSource = actionSource;
        this.filter = filter;
        this.initialOperations = operationsRemaining;
        this.operationsRemaining = operationsRemaining;
        for (var item : filter.getItems()) {
            keyTypes.add(item.getType());
        }
    }

    StorageService getInternalStorage() {
        return internalStorage;
    }

    IEnergySource getEnergySource() {
        return energySource;
    }

    IActionSource getActionSource() {
        return actionSource;
    }

    int getOperationsRemaining() {
        return operationsRemaining;
    }

    boolean hasOperationsLeft() {
        return operationsRemaining > 0;
    }

    boolean hasDoneWork() {
        return initialOperations > operationsRemaining;
    }

    /** Uses the raw inverted flag like AE2. */
    boolean isKeyTypeEnabled(AEKeyType space) {
        return keyTypes.isEmpty() || isInverted || keyTypes.contains(space);
    }

    boolean isInFilter(AEKey key) {
        return filter.isEmpty() || filter.isListed(key);
    }

    void setInverted(boolean inverted) {
        isInverted = inverted;
    }

    /** Inverted only matters with a non-empty filter. */
    boolean isInverted() {
        return !filter.isEmpty() && isInverted;
    }

    void reduceOperationsRemaining(long ops) {
        operationsRemaining -= (int) ops;
    }
}
