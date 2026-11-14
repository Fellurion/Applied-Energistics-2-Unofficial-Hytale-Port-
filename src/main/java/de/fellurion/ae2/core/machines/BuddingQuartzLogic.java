/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.grid.Direction;

import java.util.random.RandomGenerator;

/**
 * Certus quartz growth on budding quartz blocks. Port of {@code appeng.decorative.solid.BuddingCertusQuartzBlock}.
 * <p>
 * On each random tick: with chance 1/{@link #GROWTH_CHANCE} (1/5) a random direction is picked. If the neighbour is
 * air or a full water source, a small bud grows there; if it is a bud facing away from this block, it grows to the
 * next stage (small → medium → large → cluster). After a successful growth, non-flawless budding blocks decay with
 * chance 1/{@link #DECAY_CHANCE} (1/12): flawed → chipped → damaged → plain certus quartz block.
 * <p>
 * Scheduling: Minecraft random-ticks each block on average every 4096/3 ≈ 1365 game ticks (randomTickSpeed 3 per
 * 16³ section); the integration should call {@link #randomTick} at a comparable rate (and additionally from growth
 * accelerators, see {@link GrowthAcceleratorLogic}).
 */
public final class BuddingQuartzLogic {
    public static final int GROWTH_CHANCE = 5;
    public static final int DECAY_CHANCE = 12;
    /** Average game ticks between two Minecraft random ticks of one block (4096 / 3). */
    public static final double AVERAGE_RANDOM_TICK_INTERVAL = 4096.0 / 3.0;

    private static final Direction[] DIRECTIONS = Direction.values();

    private BuddingQuartzLogic() {
    }

    /** Budding block grades. */
    public enum Grade {
        FLAWLESS("flawless"),
        FLAWED("flawed"),
        CHIPPED("chipped"),
        DAMAGED("damaged");

        public final String id;

        Grade(String id) {
            this.id = id;
        }

        /** Next decay stage, or null if the block becomes a plain quartz block (damaged) or never decays. */
        public Grade decayed() {
            return switch (this) {
                case FLAWLESS -> FLAWLESS;
                case FLAWED -> CHIPPED;
                case CHIPPED -> DAMAGED;
                case DAMAGED -> null;
            };
        }

        public static Grade fromId(String id) {
            for (var g : values()) {
                if (g.id.equalsIgnoreCase(id) || g.name().equalsIgnoreCase(id)) {
                    return g;
                }
            }
            throw new IllegalArgumentException("Unknown budding quartz grade: " + id);
        }
    }

    /** Bud stages; ids are the AE2 block ids. */
    public enum BudStage {
        SMALL("small_quartz_bud"),
        MEDIUM("medium_quartz_bud"),
        LARGE("large_quartz_bud"),
        CLUSTER("quartz_cluster");

        public final String ae2Id;

        BudStage(String ae2Id) {
            this.ae2Id = ae2Id;
        }

        /** Next stage, or null for a cluster. */
        public BudStage next() {
            return switch (this) {
                case SMALL -> MEDIUM;
                case MEDIUM -> LARGE;
                case LARGE -> CLUSTER;
                case CLUSTER -> null;
            };
        }
    }

    /** What is next to the budding block. */
    public sealed interface Neighbour permits Empty, Bud, Other {
    }

    /**
     * Air, or a full water source block ({@code canClusterGrowAtState}).
     *
     * @param water true for a water source (the bud becomes waterlogged)
     */
    public record Empty(boolean water) implements Neighbour {
    }

    /** A quartz bud/cluster with its facing (the direction it points to). */
    public record Bud(BudStage stage, Direction facing) implements Neighbour {
    }

    /** Anything else. */
    public record Other() implements Neighbour {
    }

    /** Looks up the neighbour in a direction. */
    @FunctionalInterface
    public interface NeighbourLookup {
        Neighbour get(Direction direction);
    }

    /**
     * Result of a successful growth.
     *
     * @param direction    where the bud grew (place {@code newStage} facing {@code direction})
     * @param newStage     the new bud stage at that position
     * @param waterlogged  whether the new bud is waterlogged
     * @param decayed      true if the budding block decayed
     * @param newGrade     the new grade if decayed (null = became a plain certus quartz block)
     */
    public record GrowthResult(Direction direction, BudStage newStage, boolean waterlogged, boolean decayed,
            Grade newGrade) {
    }

    /**
     * Performs a random tick.
     *
     * @return the growth result, or null if nothing happened
     */
    public static GrowthResult randomTick(Grade grade, RandomGenerator random, NeighbourLookup lookup) {
        if (random.nextInt(GROWTH_CHANCE) != 0) {
            return null;
        }
        // Try to grow cluster
        var direction = DIRECTIONS[random.nextInt(DIRECTIONS.length)];
        var target = lookup.get(direction);
        BudStage newCluster = null;
        boolean water = false;
        if (target instanceof Empty empty) {
            newCluster = BudStage.SMALL;
            water = empty.water();
        } else if (target instanceof Bud bud && bud.facing() == direction) {
            newCluster = bud.stage().next();
        }
        if (newCluster == null) {
            return null;
        }
        // Damage the budding certus block after a successful growth
        if (grade == Grade.FLAWLESS || random.nextInt(DECAY_CHANCE) != 0) {
            return new GrowthResult(direction, newCluster, water, false, grade);
        }
        return new GrowthResult(direction, newCluster, water, true, grade.decayed());
    }
}
