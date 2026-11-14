/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;

import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Exposes an external item container ({@link SlotAccess}, implemented by the Hytale layer on top of a block's item
 * container) as ME storage. Port of {@code appeng.me.storage.ExternalStorageFacade} ({@code ItemHandlerFacade}).
 * <p>
 * Insert/extract use item-handler semantics ({@link SlotInventories}): inserts stack onto existing stacks first, then
 * fill empty slots; extracts take from all matching slots. Reported amounts are clamped to 2^42 like AE2.
 * <p>
 * Deviation: AE2 determines "extractable" slots for {@code extractableOnly} by simulating a 1-item extraction through
 * the capability; {@link SlotAccess} has no extraction filter, so an optional {@link #setExtractFilter slot predicate}
 * is used instead (default: every slot is extractable).
 */
public class ExternalStorageFacade implements ExternalStorage {
    /** Clamp reported values to avoid overflows when amounts get too close to Long.MAX_VALUE. */
    private static final long MAX_REPORTED_AMOUNT = 1L << 42;

    private final SlotAccess inv;
    private Runnable changeListener;
    private boolean extractableOnly;
    private IntPredicate extractFilter = slot -> true;
    private String description = "ae2.external_storage";

    public ExternalStorageFacade(SlotAccess inv) {
        this.inv = Objects.requireNonNull(inv);
    }

    public static ExternalStorageFacade of(SlotAccess inv) {
        return new ExternalStorageFacade(inv);
    }

    public SlotAccess getSlotAccess() {
        return inv;
    }

    /** Called after every successful modulating insert/extract. */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** Only report stacks that can be extracted ({@code StorageFilter.EXTRACTABLE_ONLY} of storage buses). */
    public void setExtractableOnly(boolean extractableOnly) {
        this.extractableOnly = extractableOnly;
    }

    public boolean isExtractableOnly() {
        return extractableOnly;
    }

    /** Which slots allow extraction (e.g. a furnace's output-only rules). */
    public void setExtractFilter(IntPredicate filter) {
        this.extractFilter = filter == null ? slot -> true : filter;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public int getSlots() {
        return inv.size();
    }

    @Override
    public GenericStack getStackInSlot(int slot) {
        var s = inv.get(slot);
        return s == null ? null : new GenericStack(s.key(), s.count());
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey) || amount <= 0) {
            return 0;
        }
        long inserted = SlotInventories.insert(inv, itemKey, Math.min(amount, Integer.MAX_VALUE),
                mode.isSimulate());
        if (inserted > 0 && mode == Actionable.MODULATE && changeListener != null) {
            changeListener.run();
        }
        return inserted;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey) || amount <= 0) {
            return 0;
        }
        long remaining = Math.min(amount, Integer.MAX_VALUE);
        long extracted = 0;
        for (int slot = 0; slot < inv.size() && remaining > 0; slot++) {
            var s = inv.get(slot);
            if (s != null && s.matches(itemKey) && extractFilter.test(slot)) {
                int got = SlotInventories.extractFromSlot(inv, slot, (int) remaining, mode.isSimulate());
                extracted += got;
                remaining -= got;
            }
        }
        if (extracted > 0 && mode == Actionable.MODULATE && changeListener != null) {
            changeListener.run();
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (int slot = 0; slot < inv.size(); slot++) {
            var s = inv.get(slot);
            if (s == null) {
                continue;
            }
            if (extractableOnly && !extractFilter.test(slot)) {
                continue; // Skip unextractable slots
            }
            out.add(s.key(), Math.min(s.count(), MAX_REPORTED_AMOUNT));
        }
    }

    /** True if any slot contains one of the given keys (ignoring metadata). */
    public boolean containsAnyFuzzy(Set<AEKey> keys) {
        for (int slot = 0; slot < inv.size(); slot++) {
            var s = inv.get(slot);
            if (s != null && keys.contains(s.key().dropSecondary())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
