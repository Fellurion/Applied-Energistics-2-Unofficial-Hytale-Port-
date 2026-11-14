/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.IncludeExclude;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.partition.IPartitionList;

/**
 * Wraps a storage and applies partitioning, access restriction and overflow voiding.
 * <p>
 * Port of {@code appeng.me.storage.MEInventoryHandler}. Used for storage buses and as base of {@code DriveWatcher}
 * ({@link CellStorageWatcher}).
 */
public class MEInventoryHandler extends DelegatingMEStorage {
    private IPartitionList partitionList = IPartitionList.EMPTY;
    private IncludeExclude partitionListMode = IncludeExclude.WHITELIST;
    private boolean filterOnExtraction;
    private boolean filterAvailableContents;
    private boolean allowExtraction = true;
    private boolean allowInsertion = true;
    private boolean voidOverflow;
    private boolean gettingAvailableContent = false;

    public MEInventoryHandler(MEStorage inventory) {
        super(inventory);
    }

    public void setAllowExtraction(boolean allowExtraction) {
        this.allowExtraction = allowExtraction;
    }

    public void setAllowInsertion(boolean allowInsertion) {
        this.allowInsertion = allowInsertion;
    }

    public IncludeExclude getWhitelist() {
        return partitionListMode;
    }

    public void setWhitelist(IncludeExclude mode) {
        this.partitionListMode = mode;
    }

    public IPartitionList getPartitionList() {
        return partitionList;
    }

    public void setPartitionList(IPartitionList list) {
        this.partitionList = list;
    }

    public void setExtractFiltering(boolean filterOnExtraction, boolean filterAvailableContents) {
        this.filterOnExtraction = filterOnExtraction;
        this.filterAvailableContents = filterAvailableContents;
    }

    public void setVoidOverflow(boolean voidOverflow) {
        this.voidOverflow = voidOverflow;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!this.allowInsertion || !passesBlackOrWhitelist(what)) {
            return 0;
        }
        var inserted = super.insert(what, amount, mode, source);
        return this.voidOverflow ? amount : inserted;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (this.filterOnExtraction && !canExtract(what)) {
            return 0;
        }
        return super.extract(what, amount, mode, source);
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (gettingAvailableContent) {
            return; // prevent recursion
        }
        gettingAvailableContent = true;
        try {
            if (!filterAvailableContents) {
                super.getAvailableStacks(out);
            } else {
                if (!allowExtraction) {
                    return;
                }
                for (var entry : getDelegate().getAvailableStacks()) {
                    if (canExtract(entry.getKey())) {
                        out.add(entry.getKey(), entry.getLongValue());
                    }
                }
            }
        } finally {
            gettingAvailableContent = false;
        }
    }

    @Override
    public int getEstimatedStackCount() {
        if (gettingAvailableContent) {
            return 0;
        }
        gettingAvailableContent = true;
        try {
            if (filterAvailableContents && !allowExtraction) {
                return 0;
            }
            return super.getEstimatedStackCount();
        } finally {
            gettingAvailableContent = false;
        }
    }

    @Override
    public boolean isPreferredStorageFor(AEKey input, IActionSource source) {
        if (partitionListMode == IncludeExclude.WHITELIST && partitionList.isListed(input)) {
            return true;
        }
        // Inventories that already contain some equal stack are also preferred
        if (super.extract(input, 1, Actionable.SIMULATE, source) > 0) {
            return true;
        }
        return super.isPreferredStorageFor(input, source);
    }

    protected boolean canExtract(AEKey request) {
        return allowExtraction && passesBlackOrWhitelist(request);
    }

    private boolean passesBlackOrWhitelist(AEKey input) {
        return partitionList.matchesFilter(input, partitionListMode);
    }
}
