/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IEnergySource;

/**
 * The Spatial IO Port. Port of {@code appeng.blockentity.spatial.SpatialIOPortBlockEntity} and of the transfer in
 * {@code appeng.items.storage.SpatialStorageCellItem#doSpatialTransition}.
 * <p>
 * A transfer happens when the port is triggered (AE2: rising redstone edge, see {@link #updateRedstoneState}) and
 * <ol>
 * <li>slot 0 holds a spatial storage cell and slot 1 (output) is empty,</li>
 * <li>the port's node is active,</li>
 * <li>the network has a valid spatial region ({@link SpatialPylonService}),</li>
 * <li>the network can supply the required power (simulated extraction within 0.1 %),</li>
 * <li>the inner region fits into the cell tier and, for a cell that was used before, has exactly the size of the
 * cell's plot.</li>
 * </ol>
 * Then the region and the plot are swapped, the power is extracted and the cell moves to the output slot. A fresh cell
 * gets a newly allocated plot and is bound to the size of this first transfer.
 * <p>
 * Deviations:
 * <ul>
 * <li>The world is reached through {@link RegionSwapper}. Unlike AE2's swap it may refuse (region not loaded, a
 * player would be buried, protected area, I/O error); a refused transfer changes nothing: no power is taken, the cell
 * stays in the input slot and a plot allocated for this attempt is freed again (AE2 frees it in its
 * {@code finally}). AE2's {@code GridSpatialEvent.preventTransition} is covered by the same refusal.</li>
 * <li>AE2 writes the plot info to the cell before swapping; here it is written after a successful swap.</li>
 * <li>AE2 extracts the power after the swap. Here it is extracted before and returned if the swap is refused:
 * energy cells of the network may stand inside the region and would be out of reach afterwards (the transfer would
 * be free). The outcome is the same.</li>
 * <li>{@link #status} exposes the conditions without doing anything, for displays.</li>
 * </ul>
 */
public final class SpatialIOPortLogic {

    /** Outcome of {@link #status} / {@link #transition}. */
    public enum Result {
        /** Transfer done (or, for {@link #status}, possible as far as the network is concerned). */
        OK,
        /** Slot 0 has no spatial storage cell. */
        NO_CELL,
        /** The output slot is occupied. */
        OUTPUT_OCCUPIED,
        /** The port is not powered or has no channel. */
        NOT_ACTIVE,
        /** The network has no formed pylon line with a channel. */
        NO_REGION,
        /** The pylons do not frame a valid region. */
        INVALID_REGION,
        /** The network cannot supply the required power. */
        NOT_ENOUGH_POWER,
        /** The region is larger than the cell tier allows. */
        CELL_TOO_SMALL,
        /** The region does not have the size the cell is bound to. */
        SIZE_MISMATCH,
        /** The world refused the swap, see {@link SpatialIOPortLogic#lastRefusal()}. */
        REFUSED
    }

    /** The spatial storage cell lying in the input slot. */
    public interface Cell {
        String itemId();

        /** Plot info stored on the cell, or null for an unformatted cell. */
        SpatialPlotInfo plotInfo();

        /** Writes the plot info to the cell (AE2: {@code setStoredDimension}). */
        void setPlotInfo(SpatialPlotInfo info);
    }

    /** What the port sees of its block and network. */
    public interface Host {
        /** The cell in slot 0, or null if the slot is empty or holds something else. */
        Cell inputCell();

        boolean isOutputEmpty();

        /** Moves the cell from slot 0 to slot 1. */
        void moveCellToOutput();

        /** AE2: {@code getMainNode().isActive()}. */
        boolean isActive();

        /** Spatial region of the port's network, or null without a network. */
        SpatialPylonService spatial();

        /** Energy of the port's network, or null without a network. */
        IEnergySource energy();

        /** Gives power taken for a transfer that did not happen back to the network. */
        void returnPower(double ae);

        /** Owner of the network (stored in a newly allocated plot), may be null. */
        String owner();

        /** Name of the world the port is in. */
        String worldId();
    }

    /** Swaps the blocks of a region with the content of a plot. */
    @FunctionalInterface
    public interface RegionSwapper {
        /**
         * Exchanges the blocks in {@code x..x+sizeX-1} (and likewise y, z) with the stored content of {@code plot}.
         * Either the whole swap happens or nothing changes.
         *
         * @param fresh true if the plot was allocated for this transfer and has no content yet
         * @return null on success, otherwise the reason of the refusal (shown to the player)
         */
        String swap(SpatialStoragePlot plot, boolean fresh, int x, int y, int z, int sizeX, int sizeY, int sizeZ);
    }

    private final SpatialStoragePlotManager plots;
    private Boolean lastRedstoneState;
    private String lastRefusal;

    public SpatialIOPortLogic(SpatialStoragePlotManager plots) {
        this.plots = plots;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Redstone

    /**
     * AE2 {@code updateRedstoneState}: remembers the signal and reports a rising edge.
     *
     * @return true if a transfer must be triggered now
     */
    public boolean updateRedstoneState(boolean powered) {
        if (this.lastRedstoneState == null || this.lastRedstoneState != powered) {
            this.lastRedstoneState = powered;
            return powered;
        }
        return false;
    }

    /** Last seen signal; null = undecided (AE2: {@code YesNo.UNDECIDED}). Persist it with the block. */
    public Boolean getLastRedstoneState() {
        return lastRedstoneState;
    }

    public void setLastRedstoneState(Boolean state) {
        this.lastRedstoneState = state;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Transfer

    /** The reason given by the {@link RegionSwapper} for the last {@link Result#REFUSED}. */
    public String lastRefusal() {
        return lastRefusal;
    }

    /**
     * Tells whether a transfer could start right now, without changing anything. Checks everything except the world
     * itself, in the order AE2 checks it.
     */
    public Result status(Host host) {
        Cell cell = host.inputCell();
        if (cell == null || !SpatialStorageCell.isSpatialCell(cell.itemId())) {
            return Result.NO_CELL;
        }
        if (!host.isOutputEmpty()) {
            return Result.OUTPUT_OCCUPIED;
        }
        if (!host.isActive()) {
            return Result.NOT_ACTIVE;
        }
        SpatialPylonService spc = host.spatial();
        IEnergySource energy = host.energy();
        if (spc == null || energy == null || !spc.hasRegion()) {
            return Result.NO_REGION;
        }
        if (!spc.isValidRegion()) {
            return Result.INVALID_REGION;
        }
        if (!hasPower(energy, spc.requiredPower())) {
            return Result.NOT_ENOUGH_POWER;
        }
        return checkSize(cell, spc);
    }

    /** AE2: the simulated extraction must deliver the required power within 0.1 %. */
    private static boolean hasPower(IEnergySource energy, double req) {
        final double pr = energy.extractAEPower(req, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        return Math.abs(pr - req) < req * 0.001;
    }

    /** The size rules of {@code doSpatialTransition}. */
    private Result checkSize(Cell cell, SpatialPylonService spc) {
        final int targetX = spc.sizeX();
        final int targetY = spc.sizeY();
        final int targetZ = spc.sizeZ();
        final int maxSize = SpatialStorageCell.getMaxStoredDim(cell.itemId());
        if (targetX > maxSize || targetY > maxSize || targetZ > maxSize) {
            return Result.CELL_TOO_SMALL;
        }
        SpatialStoragePlot plot = plots.getPlot(cell.plotInfo());
        if (plot != null && !plot.hasSize(targetX, targetY, targetZ)) {
            // Check that the existing plot has the right size
            return Result.SIZE_MISMATCH;
        }
        return Result.OK;
    }

    /**
     * Performs the transfer if all conditions hold (AE2: {@code transition()}).
     *
     * @param now current time in milliseconds (stored as the plot's last transition)
     */
    public Result transition(Host host, RegionSwapper swapper, long now) {
        this.lastRefusal = null;
        Result status = status(host);
        if (status != Result.OK) {
            return status;
        }
        Cell cell = host.inputCell();
        SpatialPylonService spc = host.spatial();
        IEnergySource energy = host.energy();
        final double req = spc.requiredPower();
        // the region is read now: the swap may remove pylons' network parts and thereby change the service
        final int x = spc.innerMinX();
        final int y = spc.innerMinY();
        final int z = spc.innerMinZ();
        final int sizeX = spc.sizeX();
        final int sizeY = spc.sizeY();
        final int sizeZ = spc.sizeZ();
        final TransitionInfo info = new TransitionInfo(host.worldId(), spc.getMinX(), spc.getMinY(), spc.getMinZ(),
                spc.getMaxX(), spc.getMaxY(), spc.getMaxZ(), now);

        final double taken = energy.extractAEPower(req, Actionable.MODULATE, PowerMultiplier.CONFIG);
        if (!(Math.abs(taken - req) < req * 0.001)) {
            host.returnPower(taken);
            return Result.NOT_ENOUGH_POWER;
        }

        SpatialStoragePlot plot = plots.getPlot(cell.plotInfo());
        boolean fresh = plot == null;
        if (fresh) {
            // Otherwise allocate a new one
            plot = plots.allocatePlot(sizeX, sizeY, sizeZ, host.owner());
        }

        String refusal;
        try {
            refusal = swapper.swap(plot, fresh, x, y, z, sizeX, sizeY, sizeZ);
        } catch (RuntimeException e) {
            // clean up newly created plots that failed transfer
            if (fresh) {
                plots.freePlot(plot.getId());
            }
            host.returnPower(taken);
            throw e;
        }
        if (refusal != null) {
            if (fresh) {
                plots.freePlot(plot.getId());
            }
            host.returnPower(taken);
            this.lastRefusal = refusal;
            return Result.REFUSED;
        }

        // Store some information about this transition in the plot
        plots.setLastTransition(plot.getId(), info);
        cell.setPlotInfo(plot.toPlotInfo());
        host.moveCellToOutput();
        return Result.OK;
    }
}
