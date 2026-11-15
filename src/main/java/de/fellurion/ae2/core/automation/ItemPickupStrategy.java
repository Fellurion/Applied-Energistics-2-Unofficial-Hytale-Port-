/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Breaks the block in front of an annihilation plane and collects item entities. Port of
 * {@code appeng.parts.automation.ItemPickupStrategy}.
 * <ul>
 * <li>A block is only broken if the network has the energy for it and can store its drops (simulated first).</li>
 * <li>Energy: {@code 1 + hardness + number of dropped items} AE, on top of the normal insertion cost of the
 * sink.</li>
 * <li>Whatever does not fit after all (two simulations may each succeed where both together do not) is dropped in
 * the world, where the plane collects it later as an item entity.</li>
 * <li>After anything could not be stored the strategy stops accepting until the next {@link #reset()}.</li>
 * </ul>
 * The world is behind {@link Target}; item entities are {@link ItemEntity} objects of the integration layer.
 * <p>
 * Deviations:
 * <ul>
 * <li>No enchantments (silk touch, fortune, efficiency, unbreaking): Hytale has no counterpart, so there is no fake
 * harvest tool and no energy factor; the drops are those of a normal tool.</li>
 * <li>AE2's {@code canStoreItemStacks} lets the pickup happen as soon as <em>one</em> of the drops fits (its Javadoc
 * says "all drops"); here <em>every</em> drop has to fit, and equal drops are added up before the check.</li>
 * <li>{@link Target#destroyBlock} returns what breaking really released; that is stored instead of the simulated
 * list, so nothing is duplicated or lost should the two ever differ.</li>
 * <li>AE2 also stores the item entities that appear around the block while it is being destroyed; such entities
 * (contents and parts of the block) are collected by the plane's normal entity pickup.</li>
 * <li>The visual transition effects are left to the integration layer.</li>
 * </ul>
 */
public class ItemPickupStrategy implements PickupStrategy {

    /** The block position in front of the plane. */
    public interface Target {
        /**
         * Port of {@code canHandleBlock}: is there a block the plane may break? False for air and fluids, for
         * unbreakable blocks (Minecraft: hardness &lt; 0), blacklisted blocks (AE2 tag
         * {@code ae2:blacklisted/annihilation_plane}), positions that are not loaded and positions the plane must not
         * modify.
         */
        boolean canHandleBlock();

        /** Hardness of the block (Minecraft: {@code getDestroySpeed}), {@code >= 0}. */
        float getDestroySpeed();

        /**
         * A potential outcome of breaking the block: what it drops for a normal tool, and what it contains if it is a
         * container. Never contains empty stacks.
         */
        List<GenericStack> obtainBlockDrops();

        /**
         * Removes the block without the world dropping any of {@code drops} (Minecraft:
         * {@code level.destroyBlock(pos, false)}).
         *
         * @param drops the result of the preceding {@link #obtainBlockDrops()}
         * @return what breaking released (normally {@code drops}), or null if the block was no longer there and
         *         nothing changed
         */
        List<GenericStack> destroyBlock(List<GenericStack> drops);

        /** Drops stacks as item entities at the position (Minecraft: {@code Platform.spawnDrops}). */
        void spawnDrops(List<GenericStack> drops);

        /** AE2 item tag {@code ae2:blacklisted/annihilation_plane}: items the plane never collects. */
        boolean isItemBlacklisted(AEItemKey item);
    }

    /** An item lying in the world (Minecraft: {@code ItemEntity}). */
    public interface ItemEntity {
        boolean isAlive();

        AEItemKey getItem();

        int getCount();

        /** Removes the entity from the world. */
        void discard();

        /** Shrinks the entity's stack to {@code count} (&gt; 0). */
        void setCount(int count);
    }

    private final Target target;

    private boolean isAccepting = true;

    public ItemPickupStrategy(Target target) {
        this.target = target;
    }

    @Override
    public void reset() {
        this.isAccepting = true;
    }

    @Override
    public boolean canPickUpEntity(Object entity) {
        return entity instanceof ItemEntity;
    }

    @Override
    public boolean pickUpEntity(IEnergySource energySource, PickupSink sink, Object entity) {
        if (!this.isAccepting || !(entity instanceof ItemEntity itemEntity)) {
            return false;
        }

        if (target.isItemBlacklisted(itemEntity.getItem())) {
            return false;
        }

        this.storeEntityItem(sink, itemEntity);

        return true;
    }

    @Override
    public Result tryPickup(IEnergySource energySource, PickupSink sink) {
        if (this.isAccepting) {
            if (target.canHandleBlock()) {
                // Query a potential outcome of breaking the block
                var items = merge(target.obtainBlockDrops());
                var requiredPower = this.calculateEnergyUsage(items);

                var hasPower = energySource.extractAEPower(requiredPower, Actionable.SIMULATE,
                        PowerMultiplier.CONFIG) > requiredPower - 0.1;
                var canStore = this.canStoreItemStacks(sink, items);

                if (hasPower && canStore) {
                    this.completePickup(energySource, sink, items, requiredPower);
                    return Result.PICKED_UP;
                } else {
                    return Result.CANT_STORE;
                }
            }
        }

        return Result.CANT_PICKUP;
    }

    private void completePickup(IEnergySource energySource, PickupSink sink, List<GenericStack> items,
            float requiredPower) {
        var released = target.destroyBlock(items);
        if (released == null) {
            // We failed to actually replace the block with air, or it already was the case
            return;
        }

        List<GenericStack> leftover = null;
        for (var item : released) {
            var inserted = storeItemStack(sink, item.what(), item.amount());
            // If inserting the item fully was not possible, drop it as an item entity instead. If the storage clears
            // up, we'll pick it up that way. This will mainly be the case with storages where two inserts can
            // simulate correctly but only one will succeed.
            if (inserted < item.amount()) {
                if (leftover == null) {
                    leftover = new ArrayList<>();
                }
                leftover.add(new GenericStack(item.what(), item.amount() - inserted));
            }
        }
        if (leftover != null) {
            target.spawnDrops(leftover);
        }

        energySource.extractAEPower(requiredPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
    }

    /**
     * Stores an {@link ItemEntity} inside the network and either removes it or sets it to the leftover stack size.
     */
    private boolean storeEntityItem(PickupSink sink, ItemEntity entityItem) {
        if (entityItem.isAlive()) {
            var inserted = this.storeItemStack(sink, entityItem.getItem(), entityItem.getCount());

            return this.handleOverflow(entityItem, inserted);
        }

        return false;
    }

    /**
     * Stores a stack inside the network.
     *
     * @return count inserted
     */
    private long storeItemStack(PickupSink sink, AEKey what, long amount) {
        if (amount <= 0) {
            return 0;
        }

        var inserted = sink.insert(what, amount, Actionable.MODULATE);

        this.isAccepting = inserted >= amount;

        return inserted;
    }

    /**
     * Handles a possible overflow or none at all. It will update the entity to match the leftover stack size as well
     * as remove it without any leftover amount.
     *
     * @return true, if the entity was changed otherwise false.
     */
    private boolean handleOverflow(ItemEntity entityItem, long inserted) {
        int entityItemCount = entityItem.getCount();
        if (inserted >= entityItemCount) {
            entityItem.discard();
            return true;
        }

        var newStackSize = (int) (entityItemCount - inserted);
        var changed = entityItemCount != newStackSize;

        if (changed) {
            entityItem.setCount(newStackSize);
        }

        return changed;
    }

    /** Port of {@code calculateEnergyUsage} without the enchantment factors. */
    protected float calculateEnergyUsage(List<GenericStack> items) {
        var hardness = target.getDestroySpeed();

        var requiredEnergy = 1 + hardness;
        for (var is : items) {
            requiredEnergy += is.amount();
        }

        return requiredEnergy;
    }

    /**
     * Checks if the network can store the drops.
     * <p>
     * It also sets isAccepting to false, if the items can not be stored.
     *
     * @return true, if the network can store all drops or no drops are reported
     */
    private boolean canStoreItemStacks(PickupSink sink, List<GenericStack> itemStacks) {
        var canStore = true;

        for (var itemStack : itemStacks) {
            var inserted = sink.insert(itemStack.what(), itemStack.amount(), Actionable.SIMULATE);
            if (inserted != itemStack.amount()) {
                canStore = false;
                break;
            }
        }

        this.isAccepting = canStore;
        return canStore;
    }

    /** Adds up equal drops, so the simulation asks for the whole amount of each key at once. */
    private static List<GenericStack> merge(List<GenericStack> drops) {
        if (drops.size() < 2) {
            return drops;
        }
        var sums = new LinkedHashMap<AEKey, Long>();
        for (var drop : drops) {
            sums.merge(drop.what(), drop.amount(), Long::sum);
        }
        if (sums.size() == drops.size()) {
            return drops;
        }
        var merged = new ArrayList<GenericStack>(sums.size());
        for (var e : sums.entrySet()) {
            merged.add(new GenericStack(e.getKey(), e.getValue()));
        }
        return merged;
    }
}
