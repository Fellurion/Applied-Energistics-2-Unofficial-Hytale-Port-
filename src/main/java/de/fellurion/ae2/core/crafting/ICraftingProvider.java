/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.List;
import java.util.Set;

/**
 * Node service that provides crafting patterns (and emitable items) to the network. Port of
 * {@code appeng.api.networking.crafting.ICraftingProvider}. A grid node whose owner implements this interface is
 * registered automatically by {@link CraftingService}.
 */
public interface ICraftingProvider {
    /** Patterns offered by this provider; {@link #pushPattern} is called when they need to be crafted. */
    List<IPatternDetails> getAvailablePatterns();

    /** Patterns of higher priority are preferred by the crafting calculation. */
    default int getPatternPriority() {
        return 0;
    }

    /**
     * Instructs the provider to craft one of its patterns.
     *
     * @param inputHolder the extracted inputs, one counter per pattern input
     * @return true if the pattern was pushed (the provider took ownership of the inputs)
     */
    boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder);

    /** If true, the CPU will not push patterns to this provider. */
    boolean isBusy();

    /** Items this provider can "emit" on request (level emitters with crafting card). */
    default Set<AEKey> getEmitableItems() {
        return Set.of();
    }

    /** Asks the grid's crafting service to re-read the provider's patterns. */
    static void requestUpdate(GridNode node) {
        if (node != null && node.getGrid() != null) {
            node.getGrid().getCraftingService().refreshNodeCraftingProvider(node);
        }
    }
}
