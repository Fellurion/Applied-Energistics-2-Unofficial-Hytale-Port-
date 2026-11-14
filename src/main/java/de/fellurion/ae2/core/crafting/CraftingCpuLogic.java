/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.EnergyService;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The crafting logic of a CPU. Port of {@code appeng.crafting.execution.CraftingCpuLogic}.
 * <ul>
 * <li>Per tick, the CPU may push {@code coProcessors + 1} patterns minus the patterns it pushed in the previous 3
 * ticks (sliding window), so a CPU without co-processors pushes 1 pattern every 4 ticks (AE2 behaviour).</li>
 * <li>Pushing a pattern costs {@code sum(amount / amountPerOperation)} AE of its inputs.</li>
 * <li>Awaited items are inserted back through the crafting service's storage (highest priority).</li>
 * </ul>
 * Deviation: no player job status packets; job owners are not tracked.
 */
public final class CraftingCpuLogic {
    final CraftingCpuCluster cluster;
    private ExecutingCraftingJob job = null;
    private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
    /** Used crafting operations over the last 3 ticks. */
    private final int[] usedOps = new int[3];
    private final Set<Consumer<AEKey>> listeners = new LinkedHashSet<>();
    private boolean cantStoreItems = false;
    private long lastModifiedOnTick = 0;

    CraftingCpuLogic(CraftingCpuCluster cluster) {
        this.cluster = cluster;
    }

    public CraftingSubmitResult trySubmitJob(Grid grid, CraftingPlan plan, IActionSource src,
            ICraftingRequester requester) {
        if (job != null) {
            return CraftingSubmitResult.CPU_BUSY;
        }
        if (!cluster.isActive()) {
            return CraftingSubmitResult.CPU_OFFLINE;
        }
        if (cluster.getAvailableStorage() < plan.bytes()) {
            return CraftingSubmitResult.CPU_TOO_SMALL;
        }
        var missingIngredient = CraftingCpuHelper.tryExtractInitialItems(plan, grid, inventory, src);
        if (missingIngredient != null) {
            return CraftingSubmitResult.missingIngredient(missingIngredient);
        }
        var craftId = UUID.randomUUID();
        var linkCpu = new CraftingLink(craftId, requester == null, cluster);
        this.job = new ExecutingCraftingJob(plan, this::postChange, linkCpu);
        cluster.updateOutput(plan.finalOutput());
        cluster.markDirty();

        if (requester != null) {
            var linkReq = new CraftingLink(craftId, false, requester);
            var craftingService = grid.getCraftingService();
            craftingService.addLink(linkCpu);
            craftingService.addLink(linkReq);
            return CraftingSubmitResult.successful(linkReq);
        } else {
            return CraftingSubmitResult.successful(null);
        }
    }

    public void tickCraftingLogic(EnergyService eg, CraftingService cc) {
        if (!cluster.isActive()) {
            return;
        }
        cantStoreItems = false;
        if (job == null) {
            storeItems();
            if (!inventory.list.isEmpty()) {
                cantStoreItems = true;
            }
            return;
        }
        if (job.link.isCanceled()) {
            cancel();
            return;
        }
        if (job.suspended) {
            return;
        }
        var remainingOperations = cluster.getCoProcessors() + 1 - (usedOps[0] + usedOps[1] + usedOps[2]);
        final var started = remainingOperations;
        if (remainingOperations > 0) {
            do {
                var pushedPatterns = executeCrafting(remainingOperations, cc, eg);
                if (pushedPatterns > 0) {
                    remainingOperations -= pushedPatterns;
                } else {
                    break;
                }
            } while (remainingOperations > 0);
        }
        usedOps[2] = usedOps[1];
        usedOps[1] = usedOps[0];
        usedOps[0] = started - remainingOperations;
    }

    /**
     * Pushes patterns into available providers.
     *
     * @return how many patterns were pushed
     */
    public int executeCrafting(int maxPatterns, CraftingService craftingService, EnergyService energyService) {
        var job = this.job;
        if (job == null) {
            return 0;
        }
        var pushedPatterns = 0;
        var it = job.tasks.entrySet().iterator();
        taskLoop:
        while (it.hasNext()) {
            var task = it.next();
            if (task.getValue()[0] <= 0) {
                it.remove();
                continue;
            }
            var details = task.getKey();
            var expectedOutputs = new KeyCounter();
            var expectedContainerItems = new KeyCounter();
            var craftingContainer = CraftingCpuHelper.extractPatternInputs(details, inventory, expectedOutputs,
                    expectedContainerItems);

            for (var provider : craftingService.getProviders(details)) {
                if (craftingContainer == null) {
                    break;
                }
                if (provider.isBusy()) {
                    continue;
                }
                var patternPower = CraftingCpuHelper.calculatePatternPower(craftingContainer);
                if (energyService.extractAEPower(patternPower, Actionable.SIMULATE,
                        PowerMultiplier.CONFIG) < patternPower - 0.01) {
                    break;
                }
                if (provider.pushPattern(details, craftingContainer)) {
                    energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                    pushedPatterns++;
                    for (var expectedOutput : expectedOutputs) {
                        job.waitingFor.insert(expectedOutput.getKey(), expectedOutput.getLongValue(),
                                Actionable.MODULATE);
                    }
                    for (var expectedContainerItem : expectedContainerItems) {
                        job.waitingFor.insert(expectedContainerItem.getKey(), expectedContainerItem.getLongValue(),
                                Actionable.MODULATE);
                        job.timeTracker.addMaxItems(expectedContainerItem.getLongValue(),
                                expectedContainerItem.getKey().getType());
                    }
                    cluster.markDirty();
                    task.getValue()[0]--;
                    if (task.getValue()[0] <= 0) {
                        it.remove();
                        if (pushedPatterns == maxPatterns) {
                            break taskLoop;
                        }
                        continue taskLoop;
                    }
                    if (pushedPatterns == maxPatterns) {
                        break taskLoop;
                    }
                    // Prepare next inputs.
                    expectedOutputs.reset();
                    expectedContainerItems.reset();
                    craftingContainer = CraftingCpuHelper.extractPatternInputs(details, inventory, expectedOutputs,
                            expectedContainerItems);
                }
            }
            // Failed to push this pattern, reinject the inputs.
            if (craftingContainer != null) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);
            }
        }
        return pushedPatterns;
    }

    /**
     * Called by the crafting service (storage priority {@link Integer#MAX_VALUE}) to inject awaited items.
     *
     * @return consumed amount
     */
    public long insert(AEKey what, long amount, Actionable type) {
        if (what == null || job == null) {
            return 0;
        }
        var waitingFor = job.waitingFor.extract(what, amount, Actionable.SIMULATE);
        if (waitingFor <= 0) {
            return 0;
        }
        if (amount > waitingFor) {
            amount = waitingFor;
        }
        if (type == Actionable.MODULATE) {
            job.timeTracker.decrementItems(amount, what.getType());
            job.waitingFor.extract(what, amount, Actionable.MODULATE);
            cluster.markDirty();
        }
        long inserted = amount;
        if (what.matches(job.finalOutput)) {
            // Final output is special: it goes directly into the requester. Any remainder is ignored (AE2), i.e.
            // for standalone jobs the output continues into the network storage.
            inserted = job.link.insert(what, amount, type);
            if (type == Actionable.MODULATE) {
                postChange(what);
                job.remainingAmount = Math.max(0, job.remainingAmount - amount);
                if (job.remainingAmount <= 0) {
                    finishJob(true);
                    cluster.updateOutput(null);
                } else {
                    cluster.updateOutput(new GenericStack(job.finalOutput.what(), job.remainingAmount));
                }
            }
        } else {
            if (type == Actionable.MODULATE) {
                inventory.insert(what, amount, Actionable.MODULATE);
            }
        }
        return inserted;
    }

    private void finishJob(boolean success) {
        if (success) {
            job.link.markDone();
        } else {
            job.link.cancel();
        }
        job.waitingFor.clear();
        for (var entry : job.tasks.entrySet()) {
            for (var output : entry.getKey().getOutputs()) {
                postChange(output.what());
            }
        }
        this.job = null;
        storeItems();
    }

    /** Cancels the current job; its items are returned to the network. */
    public void cancel() {
        if (job == null) {
            return;
        }
        cluster.updateOutput(null);
        finishJob(false);
    }

    /** Tries to dump all locally stored items back into the network. */
    public void storeItems() {
        if (job != null) {
            throw new IllegalStateException("CPU should not have a job to prevent re-insertion when dumping items");
        }
        if (inventory.list.isEmpty()) {
            return;
        }
        var g = cluster.getGrid();
        if (g == null) {
            return;
        }
        var storage = g.getStorageService().getInventory();
        for (var entry : inventory.list) {
            postChange(entry.getKey());
            var inserted = storage.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE, cluster.getSrc());
            entry.setValue(entry.getLongValue() - inserted);
        }
        inventory.list.removeZeros();
        cluster.markDirty();
    }

    private void postChange(AEKey what) {
        lastModifiedOnTick = cluster.currentTick();
        for (var listener : List.copyOf(listeners)) {
            listener.accept(what);
        }
    }

    public long getLastModifiedOnTick() {
        return lastModifiedOnTick;
    }

    public boolean hasJob() {
        return job != null;
    }

    public GenericStack getFinalJobOutput() {
        return job != null ? job.finalOutput : null;
    }

    public ElapsedTimeTracker getElapsedTimeTracker() {
        return job != null ? job.timeTracker : new ElapsedTimeTracker();
    }

    public ICraftingLink getLastLink() {
        return job != null ? job.link : null;
    }

    public ListCraftingInventory getInventory() {
        return inventory;
    }

    /** Listener receiving keys whose stored/awaited/pending amount changed (CPU status UI). */
    public void addListener(Consumer<AEKey> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<AEKey> listener) {
        listeners.remove(listener);
    }

    public long getStored(AEKey template) {
        return inventory.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    public long getWaitingFor(AEKey template) {
        return job != null ? job.waitingFor.extract(template, Long.MAX_VALUE, Actionable.SIMULATE) : 0;
    }

    public void getAllWaitingFor(Set<AEKey> waitingFor) {
        if (job != null) {
            for (var entry : job.waitingFor.list) {
                waitingFor.add(entry.getKey());
            }
        }
    }

    public long getPendingOutputs(AEKey template) {
        long count = 0;
        if (job != null) {
            for (var t : job.tasks.entrySet()) {
                for (var output : t.getKey().getOutputs()) {
                    if (template.matches(output)) {
                        count += output.amount() * t.getValue()[0];
                    }
                }
            }
        }
        return count;
    }

    /** All stored, awaited and pending keys (for the CPU status UI). */
    public void getAllItems(KeyCounter out) {
        out.addAll(inventory.list);
        if (job != null) {
            out.addAll(job.waitingFor.list);
            for (var t : job.tasks.entrySet()) {
                for (var output : t.getKey().getOutputs()) {
                    out.add(output.what(), output.amount() * t.getValue()[0]);
                }
            }
        }
    }

    /** Number of pattern executions that are still to be pushed. */
    public long getRemainingTaskCount() {
        long n = 0;
        if (job != null) {
            for (var v : job.tasks.values()) {
                n += v[0];
            }
        }
        return n;
    }

    public boolean isCantStoreItems() {
        return cantStoreItems;
    }

    public boolean isJobSuspended() {
        return job != null && job.suspended;
    }

    public void setJobSuspended(boolean suspended) {
        if (job != null) {
            job.suspended = suspended;
        }
    }

    Map<String, Object> toJson() {
        var m = new LinkedHashMap<String, Object>();
        m.put("inv", inventory.toJson());
        if (job != null) {
            m.put("job", job.toJson());
        }
        return m;
    }

    void fromJson(Map<?, ?> m) {
        inventory.fromJson(MiniJson.getList(m, "inv"));
        var jobData = MiniJson.getMap(m, "job");
        if (!jobData.isEmpty()) {
            job = ExecutingCraftingJob.fromJson(jobData, this::postChange, cluster);
            var grid = cluster.getGrid();
            if (grid != null) {
                grid.getCraftingService().addLink(job.link);
            }
            if (job.finalOutput == null) {
                finishJob(false);
            } else {
                cluster.updateOutput(new GenericStack(job.finalOutput.what(), job.remainingAmount));
            }
        } else {
            cluster.updateOutput(null);
        }
    }
}
