/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridMultiblock;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.stacks.GenericStack;

import java.util.Collections;
import java.util.Iterator;

/**
 * Owner logic of a crafting CPU block (unit, accelerator, storage, monitor). Port of
 * {@code appeng.blockentity.crafting.CraftingBlockEntity} / {@code CraftingMonitorBlockEntity} and
 * {@code appeng.block.crafting.CraftingUnitType}.
 * <p>
 * Pass an instance as node owner with node type {@code NodeType.CRAFTING_*}. Adjacent crafting blocks of one grid form
 * a {@link CraftingCpuCluster} when they fill a cuboid of at most 17×17×17 and contain at least one storage block
 * (see {@link CraftingService}). Persist {@link #getClusterStateToSave()} on the block, and give it back with
 * {@link #setSavedClusterState(String)} when the block is loaded so a running job survives reloads. When a player
 * breaks the block, call {@link #onBlockBroken()} before removing the node.
 */
public final class CraftingUnitLogic implements IGridMultiblock, IGridNodeListener, IActionHost {
    /** Port of {@code CraftingUnitType}. */
    public enum UnitType {
        UNIT(0, 0),
        ACCELERATOR(0, 1),
        STORAGE_1K(1, 0),
        STORAGE_4K(4, 0),
        STORAGE_16K(16, 0),
        STORAGE_64K(64, 0),
        STORAGE_256K(256, 0),
        MONITOR(0, 0);

        public final int storageKb;
        public final int acceleratorThreads;

        UnitType(int storageKb, int acceleratorThreads) {
            this.storageKb = storageKb;
            this.acceleratorThreads = acceleratorThreads;
        }

        public long storageBytes() {
            return storageKb * 1024L;
        }

        public static UnitType storage(long bytes) {
            for (var t : values()) {
                if (t.storageKb > 0 && t.storageBytes() == bytes) {
                    return t;
                }
            }
            throw new IllegalArgumentException("No crafting storage with " + bytes + " bytes");
        }
    }

    private final UnitType type;
    private GridNode node;
    CraftingCpuCluster cluster;
    private String savedClusterState;
    private GenericStack displayedJob;
    private Runnable changeListener;

    public CraftingUnitLogic(UnitType type) {
        this.type = type;
    }

    /**
     * Creates the logic for a block kind: {@code crafting_unit}, {@code crafting_accelerator},
     * {@code crafting_monitor} or {@code crafting_storage} (with {@code bytes} = N*1024).
     */
    public static CraftingUnitLogic forKind(String kind, long bytes) {
        return new CraftingUnitLogic(switch (kind) {
            case "crafting_unit" -> UnitType.UNIT;
            case "crafting_accelerator" -> UnitType.ACCELERATOR;
            case "crafting_monitor" -> UnitType.MONITOR;
            case "crafting_storage" -> UnitType.storage(bytes);
            default -> throw new IllegalArgumentException("Not a crafting block kind: " + kind);
        });
    }

    public UnitType getType() {
        return type;
    }

    public long getStorageBytes() {
        return type.storageBytes();
    }

    public int getAcceleratorThreads() {
        return type.acceleratorThreads;
    }

    /** The cluster this block belongs to, or null if unformed. */
    public CraftingCpuCluster getCluster() {
        return cluster;
    }

    /** Block state "formed" (AE2 {@code FORMED}). */
    public boolean isFormed() {
        return cluster != null && !cluster.isDestroyed();
    }

    /** Called when the cluster changed (formed/unformed, job output for monitors, dirty state). */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /** For monitors: the job output currently displayed (null if idle). */
    public GenericStack getDisplayedJob() {
        return displayedJob;
    }

    void setDisplayedJob(GenericStack job) {
        this.displayedJob = job;
        changed();
    }

    /** Saved CPU state (JSON) to restore when the cluster forms; set by the integration when loading the block. */
    public void setSavedClusterState(String json) {
        this.savedClusterState = json;
    }

    String takeSavedClusterState() {
        var s = savedClusterState;
        savedClusterState = null;
        return s;
    }

    /**
     * The CPU state (JSON) to persist on this block: non-null only for the cluster's core block (AE2 stores the CPU
     * state in the core block entity).
     */
    public String getClusterStateToSave() {
        if (cluster != null && !cluster.isDestroyed() && cluster.getCore() == this) {
            return cluster.toJson();
        }
        return savedClusterState;
    }

    public GridNode getNode() {
        return node;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    @Override
    public Iterator<GridNode> getMultiblockNodes() {
        if (cluster == null || cluster.isDestroyed()) {
            return Collections.emptyIterator();
        }
        return cluster.getNodes().iterator();
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    /**
     * Call when the player breaks this block (before {@code MEWorld.removeNode}). Port of
     * {@code CraftingBlockEntity.breakCluster}: the job is cancelled, the CPU's items go back into the network and
     * leftovers are handed to {@link CraftingCpuCluster#setDropHandler}.
     */
    public void onBlockBroken() {
        if (cluster != null && !cluster.isDestroyed()) {
            var grid = cluster.getGrid();
            cluster.breakCluster();
            if (grid != null) {
                grid.getCraftingService().clustersChanged();
            }
        }
    }

    /**
     * Node removed (block broken after {@link #onBlockBroken()}, or chunk unloaded): the cluster is dissolved without
     * cancelling its job. Like AE2, a running job only survives an unload if the integration saved
     * {@link #getClusterStateToSave()} of the core block before.
     */
    @Override
    public void onNodeRemoved(GridNode node) {
        if (cluster != null && !cluster.isDestroyed()) {
            var grid = cluster.getGrid();
            cluster.destroy();
            if (grid != null) {
                grid.getCraftingService().clustersChanged();
            }
        }
        this.node = null;
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state == NodeState.POWER || state == NodeState.CHANNEL) {
            changed();
        }
    }
}
