/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;

/**
 * Computes a crafting plan for a requested stack. Port of {@code appeng.crafting.CraftingCalculation}.
 * <p>
 * Deviation: AE2 runs the calculation on a background thread that is paused/resumed from the server tick
 * ({@code craftingCalculationTimePerTick}); this port runs synchronously in {@link #run()} (the network contents are
 * snapshot at construction, exactly like AE2's {@code NetworkCraftingSimulationState}). An optional node budget
 * ({@link #setMaxTreeOperations}) aborts pathological calculations.
 */
public final class CraftingCalculation {
    private final CraftingSimulationState.Network networkInv;
    private final KeyCounter missing = new KeyCounter();
    private final CraftingTreeNode tree;
    private final AEKey output;
    private final long requestedAmount;
    private final CalculationStrategy strategy;
    private final IActionSource source;
    private boolean simulate = false;
    private long operations;
    private long maxTreeOperations = 10_000_000L;

    /**
     * @param grid     the grid (its storage is snapshot now)
     * @param source   who requests (player requests re-query live storage)
     * @param output   what and how much to craft
     * @param strategy what to do if the full amount cannot be crafted
     */
    public CraftingCalculation(Grid grid, IActionSource source, GenericStack output, CalculationStrategy strategy) {
        this.output = output.what();
        this.requestedAmount = output.amount();
        this.strategy = strategy;
        this.source = source;
        this.networkInv = new CraftingSimulationState.Network(grid.getStorageService(), source);
        this.tree = new CraftingTreeNode(grid.getCraftingService(), this, this.output, 1, null, -1);
    }

    /** Aborts (IllegalStateException) after this many tree operations (default 10M). */
    public CraftingCalculation setMaxTreeOperations(long max) {
        this.maxTreeOperations = max;
        return this;
    }

    void addMissing(AEKey what, long amount) {
        missing.add(what, amount);
    }

    /** Runs the calculation and returns the plan (a simulation plan if items are missing). */
    public CraftingPlan run() {
        var fullAmountPlan = runCraftAttempt(false, requestedAmount);
        if (fullAmountPlan != null) {
            return fullAmountPlan;
        }
        if (strategy == CalculationStrategy.CRAFT_LESS) {
            // Try crafting less if possible using binary search.
            long successfulAmount = 0;
            CraftingPlan successfulPlan = null;
            for (long increment = Long.highestOneBit(requestedAmount); increment > 0; increment /= 2) {
                long testAmount = successfulAmount + increment;
                if (testAmount < requestedAmount) {
                    var plan = runCraftAttempt(false, testAmount);
                    if (plan != null) {
                        successfulAmount = testAmount;
                        successfulPlan = plan;
                    }
                }
            }
            if (successfulPlan != null) {
                return successfulPlan;
            }
        }
        // Couldn't find a successful plan -> simulate.
        return runCraftAttempt(true, requestedAmount);
    }

    /** @return null on failure */
    private CraftingPlan runCraftAttempt(boolean simulate, long amount) {
        this.simulate = simulate;
        var craftingInventory = new CraftingSimulationState.Child(networkInv);
        craftingInventory.ignore(this.output);
        try {
            this.tree.request(craftingInventory, amount, null);
        } catch (CraftBranchFailure failure) {
            return null;
        }
        // Add bytes for the tree size.
        craftingInventory.addBytes(this.tree.getNodeCount() * 8);
        return CraftingSimulationState.buildCraftingPlan(craftingInventory, this, amount);
    }

    void handlePausing() {
        if (++operations > maxTreeOperations) {
            throw new IllegalStateException("Crafting calculation for " + output + " exceeded " + maxTreeOperations
                    + " operations");
        }
    }

    public boolean isSimulation() {
        return simulate;
    }

    public AEKey getOutput() {
        return output;
    }

    public KeyCounter getMissingItems() {
        return missing;
    }

    public IActionSource getSource() {
        return source;
    }

    public boolean hasMultiplePaths() {
        return tree.hasMultiplePaths();
    }
}
