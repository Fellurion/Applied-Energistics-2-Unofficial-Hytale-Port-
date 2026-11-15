/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * The Spatial Anchor: keeps every chunk loaded that its ME network has a block in. Port of
 * {@code appeng.blockentity.spatial.SpatialAnchorBlockEntity}.
 * <p>
 * Rules as in AE2:
 * <ul>
 * <li>ticks every 20 game ticks while awake; waits for the network to finish booting before its first clean-up;</li>
 * <li>a clean-up releases chunks the network no longer has a block in and forces the ones that are missing;</li>
 * <li>idle power = 80 + n * (n + 1) / 2 AE/t for n held chunks;</li>
 * <li>after more than 200 ticks without power or channel all chunks are released and the anchor sleeps until its
 * state changes ({@link #wakeUp(GridNode)});</li>
 * <li>removing the anchor releases everything ({@link #releaseAll()}).</li>
 * </ul>
 * Deviations: AE2 learns about chunks from {@code GridChunkAdded}/{@code GridChunkRemoved} events of its statistics
 * service; here the integration layer provides the current chunk set of the network ({@link Network#chunks()}) and
 * the anchor is woken by the reboot of the network that every added or removed block causes, so a new chunk is
 * forced by the clean-up one tick later rather than in the same tick. The chunk overlay (a client-side render setting) and the temporary 9x9 chunk area AE2 loads
 * after an anchor was moved by a spatial transfer ({@code doneMoving}) are not ported.
 */
public final class SpatialAnchorLogic implements IGridTickable, IGridNodeListener {

    /** AE2: "be a bit lenient to not unload all chunks immediately upon power loss". */
    public static final int POWERLESS_TICKS = 200;

    /** Keeps chunks loaded on behalf of this anchor. Port of {@code ChunkLoadingService.forceChunk/releaseChunk}. */
    public interface ChunkLoader {
        /** @return true if the chunk is now kept loaded for this anchor */
        boolean forceChunk(long chunk);

        /** @return true if the chunk was held for this anchor and is released now */
        boolean releaseChunk(long chunk);
    }

    /** The anchor's view of its node and network. */
    public interface Network {
        /** AE2: {@code getMainNode().hasGridBooted()}. */
        boolean hasGridBooted();

        /** AE2: {@code getMainNode().isOnline()}. */
        boolean isOnline();

        /**
         * The chunks the network currently has blocks in (AE2: {@code StatisticsService.getChunks()}), or null
         * without a network.
         */
        Set<Long> chunks();

        /** AE2: {@code getMainNode().setIdlePowerUsage}. */
        void setIdlePowerUsage(double usage);
    }

    private final ChunkLoader loader;
    private final Network network;
    private final Set<Long> chunks = new HashSet<>();
    private int powerlessTicks = 0;
    private boolean initialized = false;
    private boolean removed = false;

    public SpatialAnchorLogic(ChunkLoader loader, Network network) {
        this.loader = loader;
        this.network = network;
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(20, 20, false);
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        // Initialize once the network is ready and there are no entries marked as loaded.
        if (!this.initialized) {
            if (!network.hasGridBooted()) {
                // Wait for the end of pathing to give time for the network to load the first time...
                return TickRateModulation.SAME;
            } else {
                this.initialized = true;
            }
        }

        this.cleanUp();

        // Be a bit lenient to not unload all chunks immediately upon power loss
        if (this.powerlessTicks > POWERLESS_TICKS) {
            if (!network.isOnline()) {
                this.releaseAll();
            }
            this.powerlessTicks = 0;

            // Put anchor to sleep until another power change.
            return TickRateModulation.SLEEP;
        }

        // Count ticks without power
        if (!network.isOnline()) {
            this.powerlessTicks += ticksSinceLastCall;
            return TickRateModulation.SAME;
        }

        // Default to sleep
        return TickRateModulation.SLEEP;
    }

    /**
     * AE2 {@code onMainNodeStateChanged}: every power, channel or boot change wakes the anchor. A network reboots
     * whenever a block joins or leaves it, so this also covers AE2's chunk added/removed events.
     */
    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state != NodeState.CONNECTIONS) {
            wakeUp(node);
        }
    }

    /** Wakes the anchor so that it performs another clean-up (AE2: {@code wakeUp()}). */
    public void wakeUp(GridNode node) {
        if (node != null && !this.removed) {
            node.alert();
        }
    }

    /** The chunks currently held by this anchor. */
    public Set<Long> getLoadedChunks() {
        return Collections.unmodifiableSet(this.chunks);
    }

    public int countLoadedChunks() {
        return this.chunks.size();
    }

    /** AE2: {@code 80 + n * (n + 1) / 2}. */
    public static int powerFor(int chunkCount) {
        return 80 + chunkCount * (chunkCount + 1) / 2;
    }

    /** Idle power for the chunks held right now. */
    public int currentPower() {
        return powerFor(this.chunks.size());
    }

    /** Used to restore chunks that are still held for this anchor from before (AE2: {@code registerChunk}). */
    public void registerChunk(long chunk) {
        this.chunks.add(chunk);
        this.updatePowerConsumption();
    }

    private void updatePowerConsumption() {
        if (this.removed) {
            // Don't try to update the power usage if the node was already removed.
            return;
        }
        network.setIdlePowerUsage(powerFor(this.chunks.size()));
    }

    /**
     * Performs a cleanup of the loaded chunks and adds missing ones as well as removes any chunk no longer part of the
     * network.
     */
    private void cleanUp() {
        Set<Long> requiredChunks = network.chunks();
        if (requiredChunks == null) {
            return;
        }

        // Release all chunks, which are no longer part of the network.
        boolean changed = false;
        for (Iterator<Long> iterator = chunks.iterator(); iterator.hasNext();) {
            long chunk = iterator.next();
            if (!requiredChunks.contains(chunk)) {
                loader.releaseChunk(chunk);
                iterator.remove();
                changed = true;
            }
        }

        // Force missing chunks
        for (long chunk : requiredChunks) {
            if (!this.chunks.contains(chunk)) {
                changed |= this.force(chunk);
            }
        }
        if (changed) {
            this.updatePowerConsumption();
        }
    }

    /** Adds the chunk to the current loaded list. */
    private boolean force(long chunk) {
        // Avoid loading chunks after the anchor is destroyed
        if (this.removed) {
            return false;
        }
        boolean forced = loader.forceChunk(chunk);
        return forced && this.chunks.add(chunk);
    }

    /**
     * Releases every chunk (anchor unpowered for too long). As in AE2 the idle power is left at its last value: the
     * network has to afford the chunks it held before it comes back online.
     */
    public void releaseAll() {
        for (long chunk : this.chunks) {
            loader.releaseChunk(chunk);
        }
        this.chunks.clear();
    }

    /** The anchor block is gone: release everything and never force again (AE2: {@code setRemoved}). */
    public void onRemoved() {
        this.removed = true;
        this.releaseAll();
    }
}
