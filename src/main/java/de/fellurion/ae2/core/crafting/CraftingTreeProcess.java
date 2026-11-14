/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pattern in the crafting tree, with child nodes for its inputs. Port of {@code appeng.crafting.CraftingTreeProcess}.
 */
final class CraftingTreeProcess {
    private final CraftingTreeNode parent;
    final IPatternDetails details;
    private final CraftingCalculation job;
    /** Linked map for deterministic ordering of subcrafts: node → multiplier. */
    private final Map<CraftingTreeNode, Long> nodes = new LinkedHashMap<>();
    boolean possible = true;
    private boolean containerItems;
    /** If true, this pattern is simulated one craft at a time (outputs used as inputs / container items). */
    private boolean limitQty;

    CraftingTreeProcess(CraftingService cc, CraftingCalculation job, IPatternDetails details,
            CraftingTreeNode craftingTreeNode) {
        this.parent = craftingTreeNode;
        this.details = details;
        this.job = job;
        updateLimitQty();
        var inputs = details.getInputs();
        for (int x = 0; x < inputs.length; ++x) {
            var input = inputs[x];
            var firstInput = input.getPossibleInputs()[0];
            nodes.put(new CraftingTreeNode(cc, job, firstInput.what(), firstInput.amount(), this, x),
                    input.getMultiplier());
        }
    }

    boolean notRecursive(IPatternDetails details) {
        return parent == null || parent.notRecursive(details);
    }

    private void updateLimitQty() {
        for (var input : details.getInputs()) {
            var primaryInput = input.getPossibleInputs()[0];
            boolean isAnInput = false;
            for (var output : details.getOutputs()) {
                if (output.what().matches(primaryInput)) {
                    isAnInput = true;
                    break;
                }
            }
            if (isAnInput) {
                limitQty = true;
            }
            if (input.getRemainingKey(primaryInput.what()) != null) {
                limitQty = containerItems = true;
            }
        }
    }

    boolean limitsQuantity() {
        return limitQty;
    }

    void request(CraftingSimulationState inv, long times) throws CraftBranchFailure {
        job.handlePausing();
        var containerItemsCounter = this.containerItems ? new KeyCounter() : null;
        // request and remove inputs...
        for (var entry : nodes.entrySet()) {
            entry.getKey().request(inv, entry.getValue() * times, containerItemsCounter);
        }
        // add container items
        if (containerItemsCounter != null) {
            for (var stack : containerItemsCounter) {
                inv.insert(stack.getKey(), stack.getLongValue(), Actionable.MODULATE);
                inv.addStackBytes(stack.getKey(), stack.getLongValue(), 1);
            }
        }
        // add crafting results..
        for (var out : details.getOutputs()) {
            inv.insert(out.what(), out.amount() * times, Actionable.MODULATE);
        }
        inv.addCrafting(details, times);
        inv.addBytes(times);
    }

    long getNodeCount() {
        long tot = 0;
        for (var node : nodes.keySet()) {
            tot += node.getNodeCount();
        }
        return tot;
    }

    long getOutputCount(AEKey what) {
        long tot = 0;
        for (var is : details.getOutputs()) {
            if (what.matches(is)) {
                tot += is.amount();
            }
        }
        return tot;
    }

    boolean hasMultiplePaths() {
        for (var node : nodes.keySet()) {
            if (node.hasMultiplePaths()) {
                return true;
            }
        }
        return false;
    }
}
