/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;

import java.util.Set;

/**
 * Where a pattern provider pushes pattern inputs: the adjacent machine/container. Implemented by the integration layer
 * (or via {@link #of(SlotAccess)} / {@link #of(MEStorage, IActionSource)}). Port of
 * {@code appeng.helpers.patternprovider.PatternProviderTarget} merged with {@code ICraftingMachine} (crafting machines
 * that accept whole plans, like the {@link MolecularAssemblerLogic molecular assembler}).
 */
public interface PatternProviderTarget {
    /** Inserts an input; @return the amount accepted. */
    long insert(AEKey what, long amount, Actionable type);

    /** For blocking mode: true if the target still contains any of the given (secondary-dropped) input keys. */
    boolean containsPatternInput(Set<AEKey> patternInputs);

    /** True if this is a crafting machine that currently accepts a whole plan ({@code ICraftingMachine}). */
    default boolean acceptsPlans() {
        return false;
    }

    /**
     * Crafting machines: accept a pattern with its inputs. Outputs should later be returned via
     * {@code provider.getReturnInventory().insert(...)}.
     */
    default boolean pushPattern(IPatternDetails details, KeyCounter[] inputs, PatternProviderLogic provider) {
        return false;
    }

    /** Adapter for a slot container (chest, furnace input, ...). Only items are accepted. */
    static PatternProviderTarget of(SlotAccess inv) {
        return new PatternProviderTarget() {
            @Override
            public long insert(AEKey what, long amount, Actionable type) {
                if (what instanceof AEItemKey item) {
                    return SlotInventories.insert(inv, item, amount, type.isSimulate());
                }
                return 0;
            }

            @Override
            public boolean containsPatternInput(Set<AEKey> patternInputs) {
                for (int i = 0; i < inv.size(); i++) {
                    var s = inv.get(i);
                    if (s != null && patternInputs.contains(s.key().dropSecondary())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** Adapter for an ME storage (e.g. an interface of a subnetwork). */
    static PatternProviderTarget of(MEStorage storage, IActionSource src) {
        return new PatternProviderTarget() {
            @Override
            public long insert(AEKey what, long amount, Actionable type) {
                return storage.insert(what, amount, type, src);
            }

            @Override
            public boolean containsPatternInput(Set<AEKey> patternInputs) {
                for (var stack : storage.getAvailableStacks()) {
                    if (patternInputs.contains(stack.getKey().dropSecondary())) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
