/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;

/**
 * How a formation plane puts one kind of resource (items, fluids) into the position in front of it. Port of
 * {@code appeng.api.behaviors.PlacementStrategy} (with {@code NoopPlacementStrategy}).
 * <p>
 * A plane owns one strategy per key type, combined by {@link PlacementStrategyFacade}; each strategy already knows
 * the world position it works on.
 * <p>
 * Deviation: AE2 creates the strategies through {@code PlacementStrategy.Factory} with the Minecraft level, position,
 * side and host; here the integration layer builds them and hands them to the plane.
 */
public interface PlacementStrategy {
    /** A placement strategy that simply does nothing. */
    PlacementStrategy NOOP = new PlacementStrategy() {
        @Override
        public void clearBlocked() {
        }

        @Override
        public long placeInWorld(AEKey what, long amount, Actionable type, boolean placeAsEntity) {
            return 0;
        }
    };

    static PlacementStrategy noop() {
        return NOOP;
    }

    /** The position in front may have changed: forget that it was found blocked. */
    void clearBlocked();

    /**
     * @param placeAsEntity drop the resource as an entity instead of placing it as a block
     * @return the amount actually placed (with {@link Actionable#SIMULATE}: that could be placed)
     */
    long placeInWorld(AEKey what, long amount, Actionable type, boolean placeAsEntity);
}
