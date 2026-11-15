/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.energy.IEnergySource;

/**
 * How an annihilation plane picks up one kind of resource (items, fluids) from the position in front of it. Port of
 * {@code appeng.api.behaviors.PickupStrategy}.
 * <p>
 * A plane owns one strategy per key type (see {@link AnnihilationPlaneLogic#setPickupStrategies}); each strategy
 * already knows the world position it works on.
 * <p>
 * Deviations: AE2 creates the strategies through {@code PickupStrategy.Factory} with the Minecraft level, position,
 * side, host and enchantments; here the integration layer builds them and hands them to the plane. Entities are
 * opaque objects of the integration layer.
 */
public interface PickupStrategy {
    /**
     * Resets any lock-out caused by throttling of the pickup strategy. It is called at least once per tick of the
     * annihilation plane to allow the strategy to reset its lockout.
     */
    void reset();

    /** Tests if this strategy can pick up the given entity. */
    boolean canPickUpEntity(Object entity);

    /**
     * Pick up a given entity and place the result into the given pickup sink. Returns true if the entity was picked up
     * successfully. The strategy has to handle removal or modification of the entity itself.
     */
    boolean pickUpEntity(IEnergySource energySource, PickupSink sink, Object entity);

    Result tryPickup(IEnergySource energySource, PickupSink sink);

    enum Result {
        /** There is nothing this strategy can pick up. */
        CANT_PICKUP,
        /** There is something the strategy could pick up, but the storage is full. */
        CANT_STORE,
        /** The strategy picked something up successfully. */
        PICKED_UP
    }
}
