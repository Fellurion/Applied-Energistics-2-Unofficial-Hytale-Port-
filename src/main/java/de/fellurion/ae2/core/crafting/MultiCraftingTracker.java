/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.automation.CraftingHook;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Connects the crafting card of export buses and interfaces ({@link CraftingHook}) to the grid's
 * {@link CraftingService}. Port of {@code appeng.helpers.MultiCraftingTracker}.
 * <p>
 * Usage: {@code bus.setCraftingHook(new MultiCraftingTracker(bus, 63, (slot, what, amount, mode) -> ...))} where the
 * sink delivers the crafted final output (export bus: into the target inventory; interface: into its storage slot).
 * <p>
 * Deviation: AE2 calculates the plan asynchronously and submits it on a later tick; here the calculation is synchronous
 * ({@link CalculationStrategy#CRAFT_LESS}) and the job is submitted immediately. Links are not persisted: after a reload
 * a running job delivers its output into the network instead.
 */
public final class MultiCraftingTracker implements CraftingHook, ICraftingRequester {
    /** Receives the crafted final output for a config slot. */
    @FunctionalInterface
    public interface CraftedItemSink {
        /** @return the amount accepted */
        long insert(int slot, AEKey what, long amount, Actionable mode);
    }

    private final IActionHost host;
    private final ICraftingLink[] links;
    private final CraftedItemSink sink;

    public MultiCraftingTracker(IActionHost host, int slots, CraftedItemSink sink) {
        this.host = host;
        this.links = new ICraftingLink[slots];
        this.sink = sink;
    }

    @Override
    public boolean requestCrafting(int slot, AEKey what, long amount) {
        if (slot < 0 || slot >= links.length || amount <= 0 || isBusy(slot)) {
            return false;
        }
        GridNode node = host.getActionableNode();
        if (node == null || node.getGrid() == null || !node.isActive()) {
            return false;
        }
        var craftingService = node.getGrid().getCraftingService();
        if (!craftingService.isCraftable(what)) {
            return false;
        }
        var src = IActionSource.ofMachine(host);
        var plan = craftingService.calculate(src, what, amount, CalculationStrategy.CRAFT_LESS);
        var result = craftingService.submitJob(plan, this, null, false, src);
        if (result.successful() && result.link() != null) {
            links[slot] = result.link();
            return true;
        }
        return false;
    }

    @Override
    public boolean isBusy(int slot) {
        var link = links[slot];
        if (link != null && (link.isCanceled() || link.isDone())) {
            links[slot] = null;
            link = null;
        }
        return link != null;
    }

    @Override
    public void cancelAll() {
        for (int i = 0; i < links.length; i++) {
            if (links[i] != null) {
                links[i].cancel();
                links[i] = null;
            }
        }
    }

    @Override
    public Set<ICraftingLink> getRequestedJobs() {
        var set = new LinkedHashSet<ICraftingLink>();
        for (var l : links) {
            if (l != null) {
                set.add(l);
            }
        }
        return set;
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        int slot = getSlot(link);
        if (slot < 0 || sink == null) {
            return 0;
        }
        return sink.insert(slot, what, amount, mode);
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        int slot = getSlot(link);
        if (slot >= 0) {
            links[slot] = null;
        }
    }

    @Override
    public GridNode getActionableNode() {
        return host.getActionableNode();
    }

    private int getSlot(ICraftingLink link) {
        for (int i = 0; i < links.length; i++) {
            if (links[i] == link) {
                return i;
            }
        }
        return -1;
    }
}
