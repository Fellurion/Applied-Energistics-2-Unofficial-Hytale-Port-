/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.p2p;

import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridFlag;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridService;
import de.fellurion.ae2.core.grid.NodeState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The P2P tunnels of a grid by frequency: one input and any number of outputs each. Port of
 * {@code appeng.me.service.P2PService}; get it with {@link Grid#getP2PService()}.
 * <p>
 * Tunnels register themselves by being the owner of a grid node (only the main node of an ME tunnel counts, not its
 * outer node). After every channel recalculation and every power change of the grid the ME input tunnels are woken
 * so that they re-check their connections.
 * <p>
 * Deviations: AE2 unregisters an input by frequency alone; here the entry is only removed if it is that tunnel, so
 * two inputs that ended up on one frequency (e.g. by joining two networks) do not unregister each other. AE2
 * reacts to the grid events {@code GridBootingStatusChange} / {@code GridPowerStatusChange}; here the per-node status
 * notifications {@link NodeState#GRID_BOOT} / {@link NodeState#POWER} are used.
 */
public final class P2PService implements IGridService {
    private final Map<Short, P2PTunnelLogic> inputs = new HashMap<>();
    private final Map<Short, Set<P2PTunnelLogic>> outputs = new HashMap<>();
    private final Random frequencyGenerator = new Random();

    public P2PService(Grid grid) {
    }

    private static P2PTunnelLogic tunnelOf(GridNode node) {
        // The outer node of an ME tunnel has the same owner but is not the tunnel's place in this network
        if (node.getOwner() instanceof P2PTunnelLogic tunnel && !node.isSecondary()
                && node.hasFlag(GridFlag.REQUIRE_CHANNEL)) {
            return tunnel;
        }
        return null;
    }

    @Override
    public void onNodeStateChanged(GridNode node, NodeState state) {
        if (state == NodeState.GRID_BOOT || state == NodeState.POWER) {
            if (tunnelOf(node) instanceof MEP2PTunnelLogic tunnel && !tunnel.isOutput()) {
                tunnel.wake();
            }
        }
    }

    @Override
    public void removeNode(GridNode node) {
        var tunnel = tunnelOf(node);
        if (tunnel == null) {
            return;
        }
        if (tunnel.isOutput()) {
            removeOutput(tunnel.getFrequency(), tunnel);
        } else {
            inputs.remove(tunnel.getFrequency(), tunnel);
        }
        updateTunnel(tunnel.getFrequency(), !tunnel.isOutput(), false);
    }

    @Override
    public void addNode(GridNode node) {
        var tunnel = tunnelOf(node);
        if (tunnel == null) {
            return;
        }
        tunnel.setMainNode(node);
        if (tunnel.isOutput()) {
            outputs.computeIfAbsent(tunnel.getFrequency(), f -> new LinkedHashSet<>()).add(tunnel);
        } else {
            inputs.put(tunnel.getFrequency(), tunnel);
        }
        updateTunnel(tunnel.getFrequency(), !tunnel.isOutput(), false);
    }

    private void removeOutput(short freq, P2PTunnelLogic tunnel) {
        var set = outputs.get(freq);
        if (set != null) {
            set.remove(tunnel);
            if (set.isEmpty()) {
                outputs.remove(freq);
            }
        }
    }

    private void updateTunnel(short freq, boolean updateOutputs, boolean configChange) {
        if (updateOutputs) {
            var set = outputs.get(freq);
            if (set != null) {
                for (var p : List.copyOf(set)) {
                    if (configChange) {
                        p.onTunnelConfigChange();
                    }
                    p.onTunnelNetworkChange();
                }
            }
        } else {
            var in = inputs.get(freq);
            if (in != null) {
                if (configChange) {
                    in.onTunnelConfigChange();
                }
                in.onTunnelNetworkChange();
            }
        }
    }

    /**
     * Moves a tunnel of this grid to another frequency, in the role it has now ({@link P2PTunnelLogic#isOutput()}),
     * and notifies the tunnels of the old and the new frequency.
     */
    public void updateFreq(P2PTunnelLogic t, short newFrequency) {
        removeOutput(t.getFrequency(), t);
        inputs.remove(t.getFrequency(), t);

        short oldFrequency = t.getFrequency();
        t.setFrequency(newFrequency);

        if (t.isOutput()) {
            outputs.computeIfAbsent(t.getFrequency(), f -> new LinkedHashSet<>()).add(t);
        } else {
            inputs.put(t.getFrequency(), t);
        }

        if (oldFrequency != newFrequency) {
            updateTunnel(oldFrequency, true, true);
            updateTunnel(oldFrequency, false, true);
        }
        updateTunnel(newFrequency, true, true);
        updateTunnel(newFrequency, false, true);
    }

    /** A random frequency that is not 0 and has no input on this grid yet. */
    public short newFrequency() {
        short newFrequency;
        do {
            newFrequency = (short) frequencyGenerator.nextInt(1 << 16);
        } while (newFrequency == 0 || inputs.containsKey(newFrequency));
        return newFrequency;
    }

    /**
     * The outputs of a frequency that have the given type. Empty unless the frequency's input has that type too.
     */
    public List<P2PTunnelLogic> getOutputs(short freq, P2PTunnelType type) {
        // Check that a matching input exists for the requested type
        var input = inputs.get(freq);
        if (input == null || input.getType() != type) {
            return List.of();
        }
        var set = outputs.get(freq);
        if (set == null) {
            return List.of();
        }
        var list = new ArrayList<P2PTunnelLogic>(set.size());
        for (var t : set) {
            if (t.getType() == type) {
                list.add(t);
            }
        }
        return list;
    }

    /** The input of a frequency (any type), or null. */
    public P2PTunnelLogic getInput(short freq) {
        return inputs.get(freq);
    }
}
