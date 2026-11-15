/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Places items in front of a formation plane, as a block or as an item entity. Port of
 * {@code appeng.parts.automation.ItemPlacementStrategy}.
 * <ul>
 * <li>Nothing happens while the position in front cannot be replaced (Minecraft: {@code canBeReplaced()} – air,
 * plants ...). Once that was seen the strategy is "blocked" and does not look again until
 * {@link #clearBlocked()}.</li>
 * <li>As a block: one item per call, and only what was really placed is reported.</li>
 * <li>As an entity: up to one stack per call, unless there are already {@link #getEntityLimit() 128} or more entities
 * around (AE2 config {@code formationPlaneEntityLimit}).</li>
 * <li>No energy is charged (AE2: none either; whoever inserts into the network pays the insertion).</li>
 * </ul>
 * The world is behind {@link Target}.
 * <p>
 * Deviations:
 * <ul>
 * <li>AE2 "uses" the item on the position with a fake player ({@code Item.useOn}), which also covers items that
 * are no blocks (seeds, buckets ...). Here {@link Target#placeBlock} places the block of a block item; other items
 * are not consumed in block mode.</li>
 * <li>Simulation: AE2 answers 1 for every item whenever the position is replaceable and only finds out when really
 * placing. Here {@link Target#canPlaceBlock} is asked, so a simulation does not promise what cannot be placed.</li>
 * </ul>
 */
public class ItemPlacementStrategy implements PlacementStrategy {

    /** AE2 config {@code formationPlaneEntityLimit}. */
    public static final int DEFAULT_ENTITY_LIMIT = 128;

    private static int entityLimit = DEFAULT_ENTITY_LIMIT;

    /** The block position in front of the plane. */
    public interface Target {
        /**
         * Can the position take a block or an item entity (Minecraft: {@code getBlockState(pos).canBeReplaced()})?
         * False while a solid block is there, the position is not loaded or the plane must not modify it.
         */
        boolean canBeReplaced();

        /** Maximum stack size of the item. */
        int getMaxStackSize(AEItemKey what);

        /** Number of entities around the position (AE2: within 8 blocks). */
        int countEntitiesAround();

        /** Drops {@code amount} items in front of the plane as an item entity. */
        void spawnItemEntity(AEItemKey what, int amount);

        /** Whether {@link #placeBlock} would currently place the item as a block. */
        boolean canPlaceBlock(AEItemKey what);

        /**
         * Places the item as a block at the position.
         *
         * @return the number of items consumed: 1 if the block was placed, 0 if not
         */
        int placeBlock(AEItemKey what);
    }

    private final Target target;
    private boolean blocked = false;

    public ItemPlacementStrategy(Target target) {
        this.target = target;
    }

    /** Disables dropping items once there are this many entities around the position. */
    public static int getEntityLimit() {
        return entityLimit;
    }

    public static void setEntityLimit(int limit) {
        entityLimit = Math.max(0, limit);
    }

    @Override
    public void clearBlocked() {
        this.blocked = !target.canBeReplaced();
    }

    /** Whether the last look at the position found it occupied. */
    public boolean isBlocked() {
        return blocked;
    }

    @Override
    public final long placeInWorld(AEKey what, long amount, Actionable type, boolean placeAsEntity) {
        if (this.blocked || !(what instanceof AEItemKey itemKey) || amount <= 0) {
            return 0;
        }

        var maxStorage = (int) Math.min(amount, target.getMaxStackSize(itemKey));
        var worked = false;

        var replaceable = target.canBeReplaced();
        if (replaceable) {
            if (placeAsEntity) {
                final var sum = target.countEntitiesAround();

                // Disable spawning once there is a certain amount of entities in an area.
                if (sum < entityLimit) {
                    worked = true;

                    if (type == Actionable.MODULATE) {
                        target.spawnItemEntity(itemKey, maxStorage);
                    }
                }
            } else if (target.canPlaceBlock(itemKey)) {
                worked = true;
                if (type == Actionable.MODULATE) {
                    maxStorage = Math.max(0, target.placeBlock(itemKey));
                    // The placed block now occupies the position
                    replaceable = target.canBeReplaced();
                } else {
                    maxStorage = 1;
                }
            }
        }

        this.blocked = !replaceable;

        if (worked) {
            return maxStorage;
        }

        return 0;
    }
}
