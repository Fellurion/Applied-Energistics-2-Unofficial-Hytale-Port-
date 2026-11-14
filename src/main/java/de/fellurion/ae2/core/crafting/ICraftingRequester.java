/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.stacks.AEKey;

import java.util.Set;

/**
 * A machine that requests crafting jobs and receives their final output (export bus / interface with crafting card).
 * Port of {@code appeng.api.networking.crafting.ICraftingRequester}.
 */
public interface ICraftingRequester extends IActionHost {
    /** Links of jobs this requester is waiting for (used to re-attach after reloads). */
    Set<ICraftingLink> getRequestedJobs();

    /**
     * Receives the crafted final output.
     *
     * @return the amount accepted
     */
    long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode);

    /** Called when the job finished or was cancelled. */
    void jobStateChange(ICraftingLink link);
}
