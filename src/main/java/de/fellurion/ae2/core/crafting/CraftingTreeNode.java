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

import java.util.ArrayList;
import java.util.List;

/**
 * A requested stack in the crafting tree: either the top-level request (slot −1, no parent) or an input of a pattern.
 * Port of {@code appeng.crafting.CraftingTreeNode}.
 */
final class CraftingTreeNode {
    /** What input this node is for. Null for the top-level node. */
    final IPatternDetails.IInput parentInput;
    private final CraftingCalculation job;
    private final CraftingTreeProcess parent;
    private final CraftingService craftingService;
    /** "Template" of the key this node is making (count: 1 for top level, the input template amount otherwise). */
    private final AEKey what;
    private final long amount;
    /** The patterns that can make this node, null until computed. */
    private ArrayList<CraftingTreeProcess> nodes = null;
    private final boolean canEmit;

    CraftingTreeNode(CraftingService cc, CraftingCalculation job, AEKey what, long amount, CraftingTreeProcess par,
            int slot) {
        this.parent = par;
        this.parentInput = slot == -1 ? null : par.details.getInputs()[slot];
        this.job = job;
        this.craftingService = cc;
        this.what = findCraftedStack(cc, what);
        this.amount = amount;
        this.canEmit = cc.canEmitFor(what);
    }

    private AEKey findCraftedStack(CraftingService cc, AEKey wat) {
        if (cc.canEmitFor(wat)) {
            return wat;
        }
        var patterns = cc.getCraftingFor(wat);
        if (patterns.isEmpty() && parentInput != null) {
            // No pattern for the exact encoded input. Try to find a pattern for a substitute ingredient.
            long acceptableAmount = parentInput.getPossibleInputs()[0].amount();
            for (var possibleInput : parentInput.getPossibleInputs()) {
                if (possibleInput.amount() != acceptableAmount) {
                    continue;
                }
                var fuzzy = cc.getFuzzyCraftable(possibleInput.what(), parentInput::isValid);
                if (fuzzy != null) {
                    return fuzzy;
                }
            }
        }
        return wat;
    }

    private void buildChildPatterns() {
        if (canEmit) {
            throw new IllegalStateException("Internal AE2 error: this node is emitable, it shouldn't use patterns!");
        }
        if (nodes == null) {
            nodes = new ArrayList<>();
            for (var details : craftingService.getCraftingFor(what)) {
                if (parent == null || parent.notRecursive(details)) {
                    nodes.add(new CraftingTreeProcess(craftingService, job, details, this));
                }
            }
        }
    }

    /** Return true if adding this pattern as a child would not cause recursion. */
    boolean notRecursive(IPatternDetails details) {
        for (var output : details.getOutputs()) {
            if (what.matches(output)) {
                return false;
            }
        }
        for (var input : details.getInputs()) {
            if (what.matches(input.getPossibleInputs()[0])) {
                return false;
            }
        }
        if (parent == null) {
            return true;
        }
        return parent.notRecursive(details);
    }

    /**
     * Requests items; succeeds or throws.
     *
     * @param requestedAmount raw amount for top-level requests, number of inputs otherwise
     * @param containerItems  where produced container items are recorded (may be null)
     */
    void request(CraftingSimulationState inv, long requestedAmount, KeyCounter containerItems)
            throws CraftBranchFailure {
        job.handlePausing();
        inv.addStackBytes(what, amount, requestedAmount);

        // 1) Collect items from the inventory
        for (var template : getValidItemTemplates(inv)) {
            long extracted = CraftingCpuHelper.extractTemplates(inv, template, requestedAmount);
            if (extracted > 0) {
                requestedAmount -= extracted;
                addContainerItems(template.key(), extracted, containerItems);
                if (requestedAmount == 0) {
                    return;
                }
            }
        }

        // Already add the container items: if we fail, they will be discarded anyway.
        addContainerItems(what, requestedAmount, containerItems);

        // 2) Emitable items
        if (canEmit) {
            inv.emitItems(what, amount * requestedAmount);
            return;
        }

        // 3) Use patterns
        buildChildPatterns();
        long totalRequestedItems = requestedAmount * amount;
        if (nodes.size() == 1) {
            // Single branch: query as much as we can and let it throw if that's not possible.
            var pro = nodes.getFirst();
            var craftedPerPattern = pro.getOutputCount(what);
            while (pro.possible && totalRequestedItems > 0) {
                long times;
                if (pro.limitsQuantity()) {
                    times = 1;
                } else {
                    times = (totalRequestedItems + craftedPerPattern - 1) / craftedPerPattern;
                }
                pro.request(inv, times);
                var available = inv.extract(what, totalRequestedItems, Actionable.MODULATE);
                if (available != 0) {
                    totalRequestedItems -= available;
                    if (totalRequestedItems <= 0) {
                        return;
                    }
                } else {
                    throw new UnsupportedOperationException(
                            "Unexpected error in the crafting calculation: can't find created items " + what);
                }
            }
        } else if (nodes.size() > 1) {
            // Multiple branches: try as much as possible of one branch before moving to the next one.
            for (var pro : nodes) {
                try {
                    while (pro.possible && totalRequestedItems > 0) {
                        var child = new CraftingSimulationState.Child(inv);
                        pro.request(child, 1);
                        var available = child.extract(what, totalRequestedItems, Actionable.MODULATE);
                        if (available != 0) {
                            child.applyDiff(inv);
                            totalRequestedItems -= available;
                            if (totalRequestedItems <= 0) {
                                return;
                            }
                        } else {
                            pro.possible = false;
                        }
                    }
                } catch (CraftBranchFailure fail) {
                    pro.possible = true;
                }
            }
        }

        if (job.isSimulation()) {
            job.addMissing(what, totalRequestedItems);
        } else {
            throw new CraftBranchFailure(what, totalRequestedItems);
        }
    }

    private void addContainerItems(AEKey template, long multiplier, KeyCounter outputList) {
        if (outputList != null) {
            var containerItem = parentInput.getRemainingKey(template);
            if (containerItem != null) {
                outputList.add(containerItem, multiplier);
            }
        }
    }

    private List<InputTemplate> getValidItemTemplates(ICraftingInventory inv) {
        if (parentInput == null) {
            return List.of(new InputTemplate(what, 1));
        }
        return CraftingCpuHelper.getValidItemTemplates(inv, parentInput);
    }

    long getNodeCount() {
        long tot = 1;
        if (nodes != null) {
            for (var pro : nodes) {
                tot += pro.getNodeCount();
            }
        }
        return tot;
    }

    boolean hasMultiplePaths() {
        if (nodes == null) {
            return false;
        }
        if (nodes.size() > 1) {
            return true;
        }
        for (var pro : nodes) {
            if (pro.hasMultiplePaths()) {
                return true;
            }
        }
        return false;
    }
}
