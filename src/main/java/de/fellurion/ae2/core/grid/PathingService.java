/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Enums.ChannelMode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Channel assignment and controller state of a grid. Port of {@code appeng.me.service.PathingService} (+
 * {@code AdHocChannelUpdater}, {@code ChannelFinalizer}).
 * <p>
 * Topology changes call {@link #repath()}; the actual calculation happens at the end of the next tick. As in AE2 the
 * grid is "booting" only during that recalculation, i.e. channels are available again in the same tick.
 * Channel power usage: {@code channelsByBlocks / 128} AE/t (added to the grid's idle drain).
 * <p>
 * Deviations: achievements and per-node saved channel modes are not ported; the channel mode can be forced per grid
 * with {@link #setForcedChannelMode}.
 */
public final class PathingService implements IGridService {
    private final Grid grid;
    private final Set<GridNode> controllers = new LinkedHashSet<>();
    private final Set<GridNode> nodesNeedingChannels = new LinkedHashSet<>();
    private final Set<GridNode> cannotCarryCompressedNodes = new HashSet<>();
    private int channelsInUse = 0;
    private int channelsByBlocks = 0;
    private double channelPowerUsage = 0.0;
    private boolean recalculateControllerNextTick = true;
    private boolean reboot = true;
    private boolean booting = false;
    private AdHocNetworkError adHocNetworkError;
    private ControllerState controllerState = ControllerState.NO_CONTROLLER;
    private boolean channelModeLocked;
    private ChannelMode channelMode = AEConfig.get().channelMode();

    PathingService(Grid grid) {
        this.grid = grid;
    }

    @Override
    public void onServerEndTick() {
        if (recalculateControllerNextTick) {
            updateControllerState();
        }

        if (reboot) {
            reboot = false;
            booting = true;
            channelsInUse = 0;
            adHocNetworkError = null;

            if (grid.isEmpty()) {
                booting = false;
                return;
            }

            for (var n : grid.getNodes()) {
                n.resetPathingState();
                for (var c : n.connections) {
                    c.usedChannels = 0;
                }
            }

            if (controllerState == ControllerState.NO_CONTROLLER) {
                channelsInUse = calculateAdHocChannels();
                channelsByBlocks = grid.size() * channelsInUse;
                setAdHocChannels(channelsInUse);
            } else if (controllerState == ControllerState.CONTROLLER_CONFLICT) {
                setAdHocChannels(0);
                channelsInUse = 0;
                channelsByBlocks = 0;
            } else {
                var calculation = new PathingCalculation(grid);
                calculation.compute();
                channelsInUse = calculation.getChannelsInUse();
                channelsByBlocks = calculation.getChannelsByBlocks();
            }

            booting = false;
            channelPowerUsage = channelsByBlocks / 128.0;
            // Notify of channel changes AFTER booting is false, so that activeness checks return true.
            for (var n : grid.getNodes()) {
                n.finalizeChannels();
                for (var c : n.connections) {
                    if (c.a() == n) {
                        c.finalizeChannels();
                    }
                }
            }
            grid.notifyAllNodes(NodeState.GRID_BOOT);
        }
    }

    private void setAdHocChannels(int channels) {
        for (var n : grid.getNodes()) {
            n.setAdHocChannels(channels);
            for (var c : n.connections) {
                c.setAdHocChannels(channels);
            }
        }
    }

    @Override
    public void addNode(GridNode node) {
        if (node.isController()) {
            controllers.add(node);
            recalculateControllerNextTick = true;
        }
        if (node.hasFlag(GridFlag.REQUIRE_CHANNEL)) {
            nodesNeedingChannels.add(node);
        }
        if (node.hasFlag(GridFlag.CANNOT_CARRY_COMPRESSED)) {
            cannotCarryCompressedNodes.add(node);
        }
        repath();
    }

    @Override
    public void removeNode(GridNode node) {
        if (node.isController()) {
            controllers.remove(node);
            recalculateControllerNextTick = true;
        }
        nodesNeedingChannels.remove(node);
        cannotCarryCompressedNodes.remove(node);
        repath();
    }

    private void updateControllerState() {
        recalculateControllerNextTick = false;
        controllerState = ControllerValidator.calculateState(controllers);
    }

    private int calculateAdHocChannels() {
        var ignore = new HashSet<GridNode>();
        adHocNetworkError = null;
        int channels = 0;
        for (var node : nodesNeedingChannels) {
            if (!ignore.contains(node)) {
                if (node.hasFlag(GridFlag.COMPRESSED_CHANNEL) && !cannotCarryCompressedNodes.isEmpty()) {
                    adHocNetworkError = AdHocNetworkError.NESTED_P2P_TUNNEL;
                    return 0;
                }
                channels++;
                // Multiblocks only require a single channel.
                if (node.hasFlag(GridFlag.MULTIBLOCK)) {
                    var multiblock = node.getService(IGridMultiblock.class);
                    if (multiblock != null) {
                        var it = multiblock.getMultiblockNodes();
                        while (it.hasNext()) {
                            ignore.add(it.next());
                        }
                    }
                }
            }
        }
        if (channels > channelMode.getAdHocNetworkChannels()) {
            adHocNetworkError = AdHocNetworkError.TOO_MANY_CHANNELS;
            return 0;
        }
        return channels;
    }

    /** Schedules a channel recalculation at the end of the current/next tick. */
    public void repath() {
        if (!channelModeLocked) {
            channelMode = AEConfig.get().channelMode();
        }
        channelsByBlocks = 0;
        reboot = true;
    }

    /** Also triggers re-validation of the controller structure. */
    void controllersChanged() {
        recalculateControllerNextTick = true;
        repath();
    }

    public boolean isNetworkBooting() {
        return booting;
    }

    public ControllerState getControllerState() {
        return controllerState;
    }

    public AdHocNetworkError getAdHocNetworkError() {
        return adHocNetworkError;
    }

    /** Number of devices that received a channel. */
    public int getUsedChannels() {
        return channelsInUse;
    }

    /** AE/t used for channels (channelsByBlocks / 128). */
    public double getChannelPowerUsage() {
        return channelPowerUsage;
    }

    public ChannelMode getChannelMode() {
        return channelMode;
    }

    public void setForcedChannelMode(ChannelMode forced) {
        if (forced == null) {
            if (channelModeLocked) {
                channelModeLocked = false;
                repath();
            }
        } else {
            channelModeLocked = true;
            if (channelMode != forced) {
                channelMode = forced;
                repath();
            }
        }
    }
}
