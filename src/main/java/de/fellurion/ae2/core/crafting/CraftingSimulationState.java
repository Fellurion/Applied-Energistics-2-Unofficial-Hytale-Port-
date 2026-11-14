/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulated inventory of a crafting calculation, lazily caching the parent's amounts. Port of
 * {@code appeng.crafting.inv.CraftingSimulationState}.
 */
public abstract class CraftingSimulationState implements ICraftingSimulationState {
    /** Partial cache of the parent's items, never modified. */
    private final KeyCounter unmodifiedCache = new KeyCounter();
    /** Partial cache of the parent's items, modifiable. */
    private final KeyCounter modifiableCache = new KeyCounter();
    private final KeyCounter emittedItems = new KeyCounter();
    private double bytes = 0;
    private final Map<IPatternDetails, Long> crafts = new LinkedHashMap<>();
    /** Minimum amount of each item that needs to be extracted from the network (max of unmodified − modifiable). */
    private final KeyCounter requiredExtract = new KeyCounter();

    protected abstract long simulateExtractParent(AEKey what, long amount);

    protected abstract Iterable<AEKey> findFuzzyParent(AEKey input);

    private void cacheFuzzy(AEKey what) {
        if (unmodifiedCache.findFuzzy(what, FuzzyMode.IGNORE_ALL).isEmpty()) {
            boolean insertedAny = false;
            for (var keyToCache : findFuzzyParent(what)) {
                var extracted = simulateExtractParent(keyToCache, Long.MAX_VALUE);
                if (extracted != 0) {
                    insertedAny = true;
                }
                modifiableCache.add(keyToCache, extracted);
                unmodifiedCache.add(keyToCache, extracted);
            }
            if (!insertedAny) {
                unmodifiedCache.add(what, 0);
            }
        }
    }

    @Override
    public void insert(AEKey what, long amount, Actionable mode) {
        cacheFuzzy(what);
        if (mode == Actionable.MODULATE) {
            modifiableCache.add(what, amount);
        }
    }

    private void updateRequiredExtract(AEKey key, long delta) {
        if (delta > 0) {
            long max = Math.max(delta, requiredExtract.get(key));
            requiredExtract.set(key, max);
        }
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode) {
        cacheFuzzy(what);
        var cachedAmount = modifiableCache.get(what);
        if (cachedAmount == 0) {
            return 0;
        }
        long extracted = Math.min(cachedAmount, amount);
        if (mode == Actionable.MODULATE) {
            modifiableCache.remove(what, extracted);
        }
        updateRequiredExtract(what, unmodifiedCache.get(what) - modifiableCache.get(what));
        return extracted;
    }

    @Override
    public Iterable<AEKey> findFuzzyTemplates(AEKey input) {
        if (input == null) {
            return List.of();
        }
        cacheFuzzy(input);
        var result = new ArrayList<AEKey>();
        for (var e : modifiableCache.findFuzzy(input, FuzzyMode.IGNORE_ALL)) {
            result.add(e.getKey());
        }
        return result;
    }

    @Override
    public void emitItems(AEKey what, long amount) {
        emittedItems.add(what, amount);
    }

    @Override
    public void addBytes(double bytes) {
        this.bytes += bytes;
    }

    @Override
    public void addCrafting(IPatternDetails details, long crafts) {
        this.crafts.merge(details, crafts, Long::sum);
    }

    /** Hides a key from the simulation (the requested output must not be taken from the network). */
    public void ignore(AEKey stack) {
        cacheFuzzy(stack);
        unmodifiedCache.set(stack, 0);
        modifiableCache.set(stack, 0);
    }

    /** Applies the changes of this (child) state to the parent. */
    public void applyDiff(CraftingSimulationState parent) {
        for (var entry : requiredExtract) {
            var key = entry.getKey();
            long delta = parent.unmodifiedCache.get(key) - parent.modifiableCache.get(key) + entry.getLongValue();
            parent.updateRequiredExtract(key, delta);
        }
        for (var entry : modifiableCache) {
            var unmodified = unmodifiedCache.get(entry.getKey());
            long sizeDelta = entry.getLongValue() - unmodified;
            if (sizeDelta > 0) {
                parent.insert(entry.getKey(), sizeDelta, Actionable.MODULATE);
            } else if (sizeDelta < 0) {
                var reallyExtracted = parent.extract(entry.getKey(), -sizeDelta, Actionable.MODULATE);
                if (reallyExtracted != -sizeDelta) {
                    throw new IllegalStateException("Failed to extract from parent. This is a bug!");
                }
            }
        }
        for (var toEmit : emittedItems) {
            parent.emitItems(toEmit.getKey(), toEmit.getLongValue());
        }
        parent.addBytes(bytes);
        for (var entry : crafts.entrySet()) {
            parent.addCrafting(entry.getKey(), entry.getValue());
        }
    }

    public static CraftingPlan buildCraftingPlan(CraftingSimulationState state, CraftingCalculation calculation,
            long calculatedAmount) {
        return new CraftingPlan(
                new de.fellurion.ae2.core.stacks.GenericStack(calculation.getOutput(), calculatedAmount),
                (long) Math.ceil(state.bytes),
                calculation.isSimulation(),
                calculation.hasMultiplePaths(),
                state.requiredExtract,
                state.emittedItems,
                calculation.getMissingItems().copy(),
                state.crafts);
    }

    /** Child state on top of another simulation inventory. Port of {@code ChildCraftingSimulationState}. */
    public static final class Child extends CraftingSimulationState {
        private final ICraftingInventory parent;

        public Child(ICraftingInventory parent) {
            this.parent = parent;
        }

        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            return parent.extract(what, amount, Actionable.SIMULATE);
        }

        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            return parent.findFuzzyTemplates(input);
        }
    }

    /**
     * Snapshot of the network storage at the start of the calculation. Port of
     * {@code NetworkCraftingSimulationState}: player requests re-query the live storage, machine requests use the
     * cached inventory.
     */
    public static final class Network extends CraftingSimulationState {
        private final KeyCounter list;

        public Network(de.fellurion.ae2.core.storage.StorageService storage,
                de.fellurion.ae2.core.storage.IActionSource src) {
            if (src != null && src.player().isPresent()) {
                this.list = storage.getInventory().getAvailableStacks();
            } else {
                this.list = new KeyCounter();
                for (var stack : storage.getCachedInventory()) {
                    if (stack.getLongValue() > 0) {
                        list.add(stack.getKey(), stack.getLongValue());
                    }
                }
            }
        }

        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            return Math.min(list.get(what), amount);
        }

        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            var result = new ArrayList<AEKey>();
            for (var e : list.findFuzzy(input, FuzzyMode.IGNORE_ALL)) {
                result.add(e.getKey());
            }
            return result;
        }
    }
}
