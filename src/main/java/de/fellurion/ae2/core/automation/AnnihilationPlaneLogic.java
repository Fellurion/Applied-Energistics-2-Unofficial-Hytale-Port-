/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.StorageHelper;

import java.util.List;
import java.util.function.Supplier;

/**
 * Annihilation plane: breaks the block in front of it and collects item entities touching its front, storing
 * everything in the network. Port of {@code appeng.parts.automation.AnnihilationPlanePart}.
 * <ul>
 * <li>What is picked up and how is decided by its {@link PickupStrategy pickup strategies}, one per key type
 * ({@link ItemPickupStrategy} for blocks and items; a fluid strategy plugs in the same way). They are asked in
 * order; the first one that picks something up or cannot store what it found ends the tick.</li>
 * <li>Adaptive ticking with {@link TickRates#AnnihilationPlane} (2..120 ticks): URGENT after a pickup, IDLE (slowest
 * rate) while there is something that cannot be stored, SLEEP when there is nothing to pick up. A sleeping plane is
 * woken by {@link #onNeighborChanged()} and when its grid has booted.</li>
 * <li>Everything goes through {@code StorageHelper.poweredInsert} (1 AE per item) in addition to the strategy's own
 * energy cost.</li>
 * <li>Continuous generation: AE2 lets a plane at the build limit facing up produce 1 sky stone dust every 200 ticks
 * instead of picking anything up; the integration enables this with {@link #setContinuousGeneration}.</li>
 * </ul>
 * Usage: pass the logic as owner to {@code MEWorld.addNode(pos, NodeType.ANNIHILATION_PLANE, facing, color, logic)},
 * give it its strategies and report changes of the block in front with {@link #onNeighborChanged()}.
 * <p>
 * Deviations:
 * <ul>
 * <li>No enchantments (AE2 keeps those of the plane item for silk touch / fortune; Hytale has no counterpart).</li>
 * <li>AE2 gets entities from the block's collision callback, at any time. Without such a callback the integration
 * reports that entities are touching the front with {@link #onEntitiesDetected()}; the plane is alerted and fetches
 * them from its {@linkplain #setEntitySource entity source} in its next tick, where each one runs through
 * {@link #onEntityCollision(Object)} exactly like in AE2. This keeps all world changes inside the grid tick.</li>
 * <li>The connected-plane model ({@code PlaneConnections}) is purely visual and not part of the logic.</li>
 * </ul>
 */
public class AnnihilationPlaneLogic implements IGridTickable, IGridNodeListener, IActionHost {
    public static final String ITEM_ID = "AE2_Annihilation_Plane";

    private final IActionSource actionSource = IActionSource.ofMachine(this);

    private List<PickupStrategy> pickupStrategies = List.of();

    // Allows annihilation planes to stop pickup and instead go into a continuous generation mode
    private ContinuousGeneration continuousGeneration;
    private int continuousGenerationTicks;

    private Supplier<? extends Iterable<?>> entitySource;
    private boolean entitiesDetected;

    private GridNode node;

    /** The strategies, in the order they are asked (AE2: {@code StackWorldBehaviors.createPickupStrategies}). */
    public void setPickupStrategies(List<PickupStrategy> strategies) {
        this.pickupStrategies = List.copyOf(strategies);
    }

    public List<PickupStrategy> getPickupStrategies() {
        return pickupStrategies;
    }

    /**
     * Switches the plane from picking up to generating {@code amount} of {@code what} every {@code ticks} game ticks
     * (AE2: sky stone dust at the build height), or back with {@code what == null}.
     */
    public void setContinuousGeneration(AEKey what, long amount, int ticks) {
        this.continuousGenerationTicks = 0;
        this.continuousGeneration = what == null ? null : new ContinuousGeneration(what, amount, ticks);
    }

    public boolean isContinuouslyGenerating() {
        return continuousGeneration != null;
    }

    /** Where {@link #onEntitiesDetected()} gets the entities touching the front from. */
    public void setEntitySource(Supplier<? extends Iterable<?>> source) {
        this.entitySource = source;
    }

    private boolean isActive() {
        return node != null && node.isActive();
    }

    /** Port of {@code onUpdateShape} for the plane's own side: a block might have been placed in front of us. */
    public void onNeighborChanged() {
        this.refresh();
    }

    /**
     * Entities are touching the front of the plane: fetch them from the entity source in the next tick (see the
     * class description).
     */
    public void onEntitiesDetected() {
        if (!this.entitiesDetected && this.isActive()) {
            this.entitiesDetected = true;
            node.alert();
        }
    }

    /**
     * Port of {@code onEntityCollision}: an entity touches the front of the plane. Whether it does is decided by the
     * caller (AE2 compares the entity's position with the plane's face).
     */
    public void onEntityCollision(Object entity) {
        if (!this.isActive()) {
            return;
        }

        var grid = node.getGrid();
        if (grid == null) {
            return;
        }

        PickupStrategy strategy = null;
        for (PickupStrategy pickupStrategy : pickupStrategies) {
            if (pickupStrategy.canPickUpEntity(entity)) {
                strategy = pickupStrategy;
                break;
            }
        }
        if (strategy == null) {
            return;
        }

        if (!strategy.pickUpEntity(grid.getEnergyService(), this::insertIntoGrid, entity)) {
            // we need to wake up the plane in case an entity pickup fails
            // to reset the "blocked" flags internal to the pickup strategy.
            node.alert();
        }
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    /** Port of {@code onMainNodeStateChanged}. */
    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state != NodeState.CONNECTIONS && node.hasGridBooted()) {
            this.refresh();
        }
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.AnnihilationPlane, false);
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (!isActive()) {
            return TickRateModulation.SLEEP;
        }

        var grid = node.getGrid();

        if (continuousGeneration != null) {
            continuousGenerationTicks += ticksSinceLastCall;
            if (continuousGenerationTicks >= continuousGeneration.ticks) {
                long amount = continuousGenerationTicks / continuousGeneration.ticks;
                insertIntoGrid(continuousGeneration.what, amount * continuousGeneration.amount, Actionable.MODULATE);
                continuousGenerationTicks -= amount * continuousGeneration.ticks;
            }
            return TickRateModulation.IDLE;
        }

        if (entitiesDetected) {
            entitiesDetected = false;
            collectEntities();
        }

        // Reset to allow more entity pickups
        for (var pickupStrategy : pickupStrategies) {
            pickupStrategy.reset();
        }

        for (PickupStrategy pickupStrategy : pickupStrategies) {
            var pickupResult = pickupStrategy.tryPickup(grid.getEnergyService(), this::insertIntoGrid);

            if (pickupResult == PickupStrategy.Result.PICKED_UP) {
                return TickRateModulation.URGENT;
            } else if (pickupResult == PickupStrategy.Result.CANT_STORE) {
                // If there's a compatible block, but we can't store it, wait longer
                return TickRateModulation.IDLE;
            }
        }

        return TickRateModulation.SLEEP;
    }

    /** The collisions AE2 would have delivered since the last tick. */
    private void collectEntities() {
        if (entitySource == null) {
            return;
        }
        for (var pickupStrategy : pickupStrategies) {
            pickupStrategy.reset();
        }
        for (var entity : entitySource.get()) {
            onEntityCollision(entity);
        }
    }

    private void refresh() {
        for (var pickupStrategy : pickupStrategies) {
            pickupStrategy.reset();
        }

        if (node != null) {
            node.alert();
        }
    }

    private long insertIntoGrid(AEKey what, long amount, Actionable mode) {
        var grid = node == null ? null : node.getGrid();
        if (grid == null) {
            return 0;
        }
        return StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(),
                what, amount, this.actionSource, mode);
    }

    private record ContinuousGeneration(AEKey what, long amount, int ticks) {
    }
}
