/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A formed crafting CPU. Port of {@code appeng.me.cluster.implementations.CraftingCPUCluster}.
 * <p>
 * Storage = sum of the storage blocks' bytes (1k = 1024 bytes ... 256k), co-processors = number of accelerators. The
 * CPU is active iff its core block's node is active. Deviation: clusters are formed by {@link CraftingService} from the
 * grid topology (see there), not by a block-update driven multiblock calculator; CPU naming is not supported.
 */
public final class CraftingCpuCluster {
    private static volatile BiConsumer<CraftingCpuCluster, List<GenericStack>> dropHandler = (c, s) -> {
    };

    private final List<CraftingUnitLogic> units;
    private final List<GridNode> nodes;
    private final long storage;
    private final int accelerator;
    private final long boundsMin;
    private final long boundsMax;
    private boolean destroyed;
    private CpuSelectionMode selectionMode = CpuSelectionMode.ANY;
    public final CraftingCpuLogic craftingLogic = new CraftingCpuLogic(this);
    private IActionSource src;

    CraftingCpuCluster(List<CraftingUnitLogic> units, long boundsMin, long boundsMax) {
        this.units = List.copyOf(units);
        var n = new ArrayList<GridNode>();
        long st = 0;
        int acc = 0;
        for (var u : units) {
            n.add(u.getNode());
            st += u.getStorageBytes();
            if (u.getAcceleratorThreads() > 16) {
                throw new IllegalArgumentException("Co-processor threads may not exceed 16 per single unit block.");
            }
            acc += u.getAcceleratorThreads();
        }
        this.nodes = Collections.unmodifiableList(n);
        this.storage = st;
        this.accelerator = acc;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
    }

    /**
     * Handler for items that remain in a broken CPU and could not be returned to the network (AE2 drops them in the
     * world). Default: discard.
     */
    public static void setDropHandler(BiConsumer<CraftingCpuCluster, List<GenericStack>> handler) {
        dropHandler = handler == null ? (c, s) -> {
        } : handler;
    }

    public List<CraftingUnitLogic> getUnits() {
        return units;
    }

    List<GridNode> getNodes() {
        return nodes;
    }

    /** The core block (stores the CPU state). */
    public CraftingUnitLogic getCore() {
        return units.getFirst();
    }

    public long getBoundsMin() {
        return boundsMin;
    }

    public long getBoundsMax() {
        return boundsMax;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public long getAvailableStorage() {
        return storage;
    }

    public int getCoProcessors() {
        return accelerator;
    }

    public GridNode getNode() {
        for (var u : units) {
            if (u.getNode() != null && !u.getNode().isRemoved()) {
                return u.getNode();
            }
        }
        return null;
    }

    public Grid getGrid() {
        var n = getNode();
        return n != null ? n.getGrid() : null;
    }

    public boolean isActive() {
        var core = getCore().getNode();
        return core != null && !core.isRemoved() && core.isActive();
    }

    public boolean isBusy() {
        return craftingLogic.hasJob();
    }

    long currentTick() {
        var n = getNode();
        return n == null ? 0 : n.getWorld().getCurrentTick();
    }

    IActionSource getSrc() {
        if (src == null) {
            src = IActionSource.ofMachine(getCore());
        }
        return src;
    }

    public CraftingSubmitResult submitJob(Grid g, CraftingPlan plan, IActionSource src,
            ICraftingRequester requestingMachine) {
        return craftingLogic.trySubmitJob(g, plan, src, requestingMachine);
    }

    public void cancelJob() {
        craftingLogic.cancel();
    }

    /** Job status for UIs, or null if idle. */
    public CraftingJobStatus getJobStatus() {
        var finalOutput = craftingLogic.getFinalJobOutput();
        if (finalOutput == null) {
            return null;
        }
        var tracker = craftingLogic.getElapsedTimeTracker();
        long total = Integer.MAX_VALUE;
        long progress = (long) (tracker.getProgress() * (double) Integer.MAX_VALUE);
        return new CraftingJobStatus(finalOutput, total, progress, tracker.getElapsedTime(),
                craftingLogic.isJobSuspended());
    }

    void updateOutput(GenericStack finalOutput) {
        var send = finalOutput != null && finalOutput.amount() <= 0 ? null : finalOutput;
        for (var u : units) {
            if (u.getType() == CraftingUnitLogic.UnitType.MONITOR) {
                u.setDisplayedJob(send);
            }
        }
    }

    void markDirty() {
        getCore().changed();
    }

    public CpuSelectionMode getSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(CpuSelectionMode mode) {
        this.selectionMode = mode;
        markDirty();
    }

    public boolean canBeAutoSelectedFor(IActionSource source) {
        return switch (selectionMode) {
            case ANY -> true;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    public boolean isPreferredFor(IActionSource source) {
        return switch (selectionMode) {
            case ANY -> false;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    /**
     * Breaks the cluster (block removed / structure changed): the job is cancelled, the CPU inventory is returned to
     * the network, and what does not fit is handed to the drop handler. Port of {@code CraftingBlockEntity.breakCluster}.
     */
    void breakCluster() {
        if (destroyed) {
            return;
        }
        craftingLogic.cancel();
        // cancel() already tries storeItems(); whatever is left is dropped
        var leftovers = new ArrayList<GenericStack>();
        var inv = craftingLogic.getInventory();
        for (var e : inv.list) {
            if (e.getLongValue() > 0) {
                leftovers.add(new GenericStack(e.getKey(), e.getLongValue()));
            }
        }
        inv.clear();
        if (!leftovers.isEmpty()) {
            dropHandler.accept(this, leftovers);
        }
        destroy();
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        updateOutput(null);
        for (var u : units) {
            if (u.cluster == this) {
                u.cluster = null;
                u.changed();
            }
        }
    }

    /** Serializes the CPU state (inventory, running job, selection mode). */
    public String toJson() {
        var m = new LinkedHashMap<String, Object>(craftingLogic.toJson());
        if (selectionMode != CpuSelectionMode.ANY) {
            m.put("mode", selectionMode.name());
        }
        return MiniJson.write(m);
    }

    void loadFromJson(String json) {
        var m = MiniJson.parseObject(json);
        selectionMode = MiniJson.getEnum(m, "mode", CpuSelectionMode.class, CpuSelectionMode.ANY);
        craftingLogic.fromJson(m);
    }

    @Override
    public String toString() {
        return "CraftingCpuCluster[" + BlockPos.toString(boundsMin) + " .. " + BlockPos.toString(boundsMax)
                + ", " + storage + " bytes, " + accelerator + " co-processors]";
    }
}
