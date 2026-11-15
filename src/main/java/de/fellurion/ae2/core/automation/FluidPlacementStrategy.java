/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEKey;

import java.util.HashSet;
import java.util.Set;

/**
 * Places a fluid source block in front of a formation plane. Port of
 * {@code appeng.parts.automation.FluidPlacementStrategy}.
 * <ul>
 * <li>A full block is needed: with less than {@link AEFluidKey#AMOUNT_BLOCK} (1000 mB) offered nothing is placed, and
 * never more than one block per call.</li>
 * <li>Fluids are placed as blocks only; with the plane set to drop items nothing happens.</li>
 * <li>A fluid that could not be placed is remembered as blocked and not tried again until
 * {@link #clearBlocked()} (the position in front changed).</li>
 * <li>No energy is charged (AE2: none either; whoever inserts into the network pays the insertion).</li>
 * </ul>
 * The world is behind {@link Target}.
 * <p>
 * Deviations: Minecraft's special cases are not ported – water evaporating in hot dimensions and waterloggable
 * blocks ({@code LiquidBlockContainer}); whether a position can take the fluid is {@link Target#canPlace}. AE2
 * reports the block as placed if the position already holds a source after a failed placement; here only a
 * successful {@link Target#placeSource} counts.
 */
public class FluidPlacementStrategy implements PlacementStrategy {

    /** The block position in front of the plane. */
    public interface Target {
        /**
         * Port of {@code canPlace}: can a source block of the fluid be put at the position? False if that source is
         * already there (AE2: "don't place it again"), a solid block is in the way, the fluid does not exist in this
         * world, the position is not loaded or the plane must not modify it.
         */
        boolean canPlace(AEFluidKey what);

        /**
         * Puts a source block of the fluid at the position.
         *
         * @return true if it was placed
         */
        boolean placeSource(AEFluidKey what);

        /** Sound / visual effect of a placement (throttled by the strategy). */
        void playPlaceEffect(AEFluidKey what);
    }

    private final Target target;

    /** The fluids that we tried to place unsuccessfully. */
    private final Set<AEFluidKey> blocked = new HashSet<>();

    /** {@link System#currentTimeMillis()} of when the last sound/visual effect was played by this plane. */
    private long lastEffect;

    public FluidPlacementStrategy(Target target) {
        this.target = target;
    }

    @Override
    public void clearBlocked() {
        blocked.clear();
    }

    /** Whether the fluid was found unplaceable since the last {@link #clearBlocked()}. */
    public boolean isBlocked(AEFluidKey what) {
        return blocked.contains(what);
    }

    @Override
    public long placeInWorld(AEKey what, long amount, Actionable type, boolean placeAsEntity) {
        if (placeAsEntity || !(what instanceof AEFluidKey fluidKey)) {
            return 0; // Not supported
        }

        if (amount < AEFluidKey.AMOUNT_BLOCK) {
            // need a full bucket
            return 0;
        }

        // We previously tried placing this fluid unsuccessfully, so don't check it again.
        if (blocked.contains(fluidKey)) {
            return 0;
        }

        if (!target.canPlace(fluidKey)) {
            // Remember that this fluid cannot be placed right now.
            blocked.add(fluidKey);
            return 0;
        }

        if (type == Actionable.MODULATE) {
            if (!target.placeSource(fluidKey)) {
                return 0;
            }
            if (!throttleEffect()) {
                target.playPlaceEffect(fluidKey);
            }
        }

        return AEFluidKey.AMOUNT_BLOCK;
    }

    /** Only play the effect every 250ms. */
    private boolean throttleEffect() {
        var now = System.currentTimeMillis();
        if (now < lastEffect + 250) {
            return true;
        }
        lastEffect = now;
        return false;
    }
}
