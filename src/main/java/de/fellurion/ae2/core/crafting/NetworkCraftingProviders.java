/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Index of all patterns and emitable items of a grid. Port of {@code appeng.me.service.helpers.NetworkCraftingProviders}.
 * Providers of the same pattern are used round-robin.
 */
final class NetworkCraftingProviders {
    private final Map<GridNode, ProviderState> craftingProviders = new IdentityHashMap<>();
    private final List<ProviderState> globalProviders = new ArrayList<>();
    private final Map<AEKey, PatternsForKey> craftableItems = new HashMap<>();
    private final KeyCounter craftableItemsList = new KeyCounter();
    private final Map<IPatternDetails, CraftingProviderList> craftingMethods = new HashMap<>();
    private final Map<AEKey, Integer> emitableItems = new HashMap<>();
    private long modificationCount;

    void addProvider(GridNode node) {
        var provider = node.getService(ICraftingProvider.class);
        if (provider != null) {
            if (craftingProviders.containsKey(node)) {
                throw new IllegalArgumentException("Duplicate crafting provider registration for node " + node);
            }
            var state = new ProviderState(provider);
            state.mount(this);
            craftingProviders.put(node, state);
            modificationCount++;
        }
    }

    void addProvider(ICraftingProvider provider) {
        for (var state : globalProviders) {
            if (state.provider == provider) {
                throw new IllegalArgumentException("Duplicate crafting provider registration for " + provider);
            }
        }
        var state = new ProviderState(provider);
        state.mount(this);
        globalProviders.add(state);
        modificationCount++;
    }

    void removeProvider(GridNode node) {
        var state = craftingProviders.remove(node);
        if (state != null) {
            state.unmount(this);
            modificationCount++;
        }
    }

    void removeProvider(ICraftingProvider provider) {
        var it = globalProviders.iterator();
        while (it.hasNext()) {
            var state = it.next();
            if (state.provider == provider) {
                it.remove();
                state.unmount(this);
                modificationCount++;
            }
        }
    }

    long getModificationCount() {
        return modificationCount;
    }

    Set<AEKey> getCraftables(Predicate<AEKey> filter) {
        var result = new LinkedHashSet<AEKey>();
        for (var k : craftableItems.keySet()) {
            if (filter.test(k)) {
                result.add(k);
            }
        }
        for (var k : emitableItems.keySet()) {
            if (filter.test(k)) {
                result.add(k);
            }
        }
        return result;
    }

    Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
        var patterns = craftableItems.get(whatToCraft);
        return patterns != null ? patterns.getSortedPatterns() : Collections.emptyList();
    }

    AEKey getFuzzyCraftable(AEKey whatToCraft, Predicate<AEKey> filter) {
        for (var fuzzy : craftableItemsList.findFuzzy(whatToCraft, FuzzyMode.IGNORE_ALL)) {
            if (filter.test(fuzzy.getKey())) {
                return fuzzy.getKey();
            }
        }
        return null;
    }

    boolean canEmitFor(AEKey someItem) {
        return emitableItems.containsKey(someItem);
    }

    Iterable<ICraftingProvider> getMediums(IPatternDetails key) {
        var list = craftingMethods.get(key);
        return list == null ? List.of() : list;
    }

    /** Providers in round-robin order: each iteration continues where the previous one stopped. */
    private static final class CraftingProviderList implements Iterable<ICraftingProvider> {
        private final List<ICraftingProvider> providers = new ArrayList<>();
        private int next;

        void add(ICraftingProvider p) {
            providers.add(p);
            next = 0;
        }

        void remove(ICraftingProvider p) {
            providers.remove(p);
            next = 0;
        }

        @Override
        public Iterator<ICraftingProvider> iterator() {
            final int size = providers.size();
            return new Iterator<>() {
                int returned;

                @Override
                public boolean hasNext() {
                    return returned < size && !providers.isEmpty();
                }

                @Override
                public ICraftingProvider next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    returned++;
                    var p = providers.get(next % providers.size());
                    next = (next + 1) % providers.size();
                    return p;
                }
            };
        }
    }

    private static final class ProviderState {
        private final ICraftingProvider provider;
        private final Set<AEKey> emitable;
        private final List<IPatternDetails> patterns;
        private final int priority;

        ProviderState(ICraftingProvider provider) {
            this.provider = provider;
            this.emitable = new LinkedHashSet<>(provider.getEmitableItems());
            this.patterns = new ArrayList<>(provider.getAvailablePatterns());
            this.priority = provider.getPatternPriority();
        }

        void mount(NetworkCraftingProviders methods) {
            for (var e : emitable) {
                methods.emitableItems.merge(e, 1, Integer::sum);
            }
            for (var pattern : patterns) {
                var primaryOutput = pattern.getPrimaryOutput();
                methods.craftableItemsList.add(primaryOutput.what(), 1);
                var pfk = methods.craftableItems.computeIfAbsent(primaryOutput.what(), k -> new PatternsForKey());
                pfk.patterns.add(new PatternInfo(pattern, this));
                pfk.needsSorting = true;
                methods.craftingMethods.computeIfAbsent(pattern, d -> new CraftingProviderList()).add(provider);
            }
        }

        void unmount(NetworkCraftingProviders methods) {
            for (var e : emitable) {
                methods.emitableItems.compute(e, (key, cnt) -> cnt == null || cnt == 1 ? null : cnt - 1);
            }
            for (var pattern : patterns) {
                var primaryOutput = pattern.getPrimaryOutput();
                methods.craftableItemsList.remove(primaryOutput.what(), 1);
                if (methods.craftableItemsList.get(primaryOutput.what()) <= 0) {
                    methods.craftableItemsList.remove(primaryOutput.what());
                }
                methods.craftableItems.computeIfPresent(primaryOutput.what(), (key, pfk) -> {
                    pfk.patterns.remove(new PatternInfo(pattern, this));
                    pfk.needsSorting = true;
                    return pfk.patterns.isEmpty() ? null : pfk;
                });
                methods.craftingMethods.computeIfPresent(pattern, (pat, list) -> {
                    list.remove(provider);
                    return list.providers.isEmpty() ? null : list;
                });
            }
        }
    }

    private static final class PatternsForKey {
        private final Set<PatternInfo> patterns = new LinkedHashSet<>();
        private List<IPatternDetails> sortedPatterns = List.of();
        private boolean needsSorting = false;

        List<IPatternDetails> getSortedPatterns() {
            if (needsSorting) {
                sortedPatterns = patterns.stream()
                        .sorted(Comparator.comparingInt((PatternInfo pi) -> pi.state.priority).reversed())
                        .map(PatternInfo::pattern)
                        .distinct()
                        .toList();
                needsSorting = false;
            }
            return sortedPatterns;
        }
    }

    private record PatternInfo(IPatternDetails pattern, ProviderState state) {
    }
}
