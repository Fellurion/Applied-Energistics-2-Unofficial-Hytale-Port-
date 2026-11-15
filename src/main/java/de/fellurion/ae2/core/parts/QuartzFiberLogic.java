/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.parts;

import de.fellurion.ae2.core.energy.EnergyService;
import de.fellurion.ae2.core.energy.IEnergyOverlayGridConnection;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Quartz fiber: lets two networks use each other's energy without joining them. Port of
 * {@code appeng.parts.networking.QuartzFiberPart}.
 * <p>
 * A quartz fiber consists of two grid nodes which are not connected: the main node on the back of the block
 * ({@link NodeType#QUARTZ_FIBER_MAIN}, owned by this object) and the outer node on the front
 * ({@link NodeType#QUARTZ_FIBER_OUTER}, created by {@link #createOuterNode}). Each node exposes the energy service
 * of the other node's grid through {@link IEnergyOverlayGridConnection}, so both grids - and every grid reached
 * through further fibers - draw from and charge the same storages. Neither node carries channels
 * ({@code CANNOT_CARRY}), neither uses power.
 * <p>
 * As a service of the main node this object answers with the energy service on the outer side.
 */
public final class QuartzFiberLogic implements IEnergyOverlayGridConnection {
    private GridNode mainNode;
    private GridNode outerNode;

    /**
     * Creates the outer node (front side of the block) once the main node exists. It is removed together with the
     * main node.
     *
     * @param mainNode the node this object is the owner of
     */
    public GridNode createOuterNode(GridNode mainNode) {
        this.mainNode = mainNode;
        this.outerNode = mainNode.getWorld().addSecondaryNode(mainNode.getPos(), NodeType.QUARTZ_FIBER_OUTER,
                mainNode.getFacing(), mainNode.getGridColor(), this,
                // Expose the energy service of the main node on the outer node
                Map.of(IEnergyOverlayGridConnection.class, (IEnergyOverlayGridConnection) this::getOurEnergyServices));
        // The main grid may have looked for its overlay grid while the outer node did not exist yet
        var grid = mainNode.getGrid();
        if (grid != null) {
            grid.getEnergyService().invalidateOverlayEnergyGrid();
        }
        return outerNode;
    }

    public GridNode getMainNode() {
        return mainNode;
    }

    /** The node on the front side, or null before {@link #createOuterNode}. */
    public GridNode getOuterNode() {
        return outerNode;
    }

    /** True if both sides are attached to a network. */
    public boolean isConnected() {
        return mainNode != null && outerNode != null && mainNode.getGrid() != null && outerNode.getGrid() != null;
    }

    /** Service of the main node: the energy service of the outer node's grid ("theirs"). */
    @Override
    public Collection<EnergyService> connectedEnergyServices() {
        return energyServiceOf(outerNode);
    }

    private Collection<EnergyService> getOurEnergyServices() {
        return energyServiceOf(mainNode);
    }

    private static Collection<EnergyService> energyServiceOf(GridNode node) {
        var grid = node == null ? null : node.getGrid();
        return grid == null ? List.of() : List.of(grid.getEnergyService());
    }
}
