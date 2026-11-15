/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.GenericStack;

/**
 * Picks up the fluid source block in front of an annihilation plane. Port of
 * {@code appeng.parts.automation.FluidPickupStrategy}.
 * <ul>
 * <li>Only a <b>source</b> block is picked up, as one block = {@link AEFluidKey#AMOUNT_BLOCK} (1000 mB); flowing fluid
 * is left alone.</li>
 * <li>The source is only removed if the network would take the whole block (simulated first). What the world really
 * released is then stored; like in AE2, if that turned out to be something else that does not fit, it is lost.</li>
 * <li>No energy of its own (AE2: none either); the plane pays the normal insertion cost, 1 AE per 125 mB.</li>
 * <li>Entities are never picked up by this strategy.</li>
 * </ul>
 * The world is behind {@link Target}.
 * <p>
 * Deviations:
 * <ul>
 * <li>AE2 picks the fluid up "as if using a bucket" with a fake player ({@code BucketPickup.pickupBlock});
 * {@link Target#pickUpSource} removes the source directly.</li>
 * <li>AE2's fluid blacklist tag ({@code ae2:blacklisted/annihilation_plane} for fluids) and every other reason not to
 * touch the position (not loaded, protected) are part of {@link Target#getSourceFluid()}.</li>
 * <li>If the source vanished between the check and the pickup, AE2 still reports a pickup; here that is
 * {@link Result#CANT_PICKUP}, so the plane does not keep ticking at full speed for nothing.</li>
 * </ul>
 */
public class FluidPickupStrategy implements PickupStrategy {

    /** The block position in front of the plane. */
    public interface Target {
        /**
         * The fluid whose source block is at the position and may be picked up, or null: no fluid, flowing fluid, a
         * fluid the network does not store, the position is not loaded or the plane must not modify it.
         */
        AEFluidKey getSourceFluid();

        /**
         * Removes the source block from the world.
         *
         * @param expected the result of the preceding {@link #getSourceFluid()}
         * @return what was picked up (normally {@code expected} with {@link AEFluidKey#AMOUNT_BLOCK}), or null if the
         *         source was no longer there and nothing changed
         */
        GenericStack pickUpSource(AEFluidKey expected);

        /** Sound / visual effect of a pickup (throttled by the strategy). */
        void playPickupEffect(AEFluidKey what);
    }

    private final Target target;

    /** {@link System#currentTimeMillis()} of when the last sound/visual effect was played by this plane. */
    private long lastEffect;

    public FluidPickupStrategy(Target target) {
        this.target = target;
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean canPickUpEntity(Object entity) {
        return false;
    }

    @Override
    public boolean pickUpEntity(IEnergySource energySource, PickupSink sink, Object entity) {
        return false;
    }

    @Override
    public Result tryPickup(IEnergySource energySource, PickupSink sink) {
        var what = target.getSourceFluid();
        if (what == null) {
            // nothing to do here :)
            return Result.CANT_PICKUP;
        }

        // Attempt to store the fluid in the network
        if (!this.storeFluid(sink, what, AEFluidKey.AMOUNT_BLOCK, false)) {
            return Result.CANT_STORE;
        }

        // If that would succeed, actually slurp up the liquid as if we were using a bucket.
        // This _MIGHT_ change the liquid, and if it does, and we dont have enough space, tough luck. you loose the
        // source block.
        var pickedUp = target.pickUpSource(what);
        if (pickedUp == null) {
            return Result.CANT_PICKUP;
        }
        if (pickedUp.what() instanceof AEFluidKey fluidKey && pickedUp.amount() > 0) {
            this.storeFluid(sink, fluidKey, pickedUp.amount(), true);
        }

        if (!throttleEffect()) {
            target.playPickupEffect(what);
        }

        return Result.PICKED_UP;
    }

    private boolean storeFluid(PickupSink sink, AEFluidKey what, long amount, boolean modulate) {
        return sink.insert(what, amount, modulate ? Actionable.MODULATE : Actionable.SIMULATE) >= amount;
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
