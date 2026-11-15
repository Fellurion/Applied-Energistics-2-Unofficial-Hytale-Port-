/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fluids at the planes: the annihilation plane picks up a source block as 1000 mB if the network can store it, the
 * formation plane places a source block when a full block is offered and the position is free.
 */
class FluidPlaneTest extends AutomationTestBase {
    private static final AEFluidKey WATER = AEFluidKey.of("Water_Source");
    private static final AEFluidKey LAVA = AEFluidKey.of("Lava_Source");

    /** One block position: at most one fluid, as a source or flowing. */
    static final class FakeWorld implements FluidPickupStrategy.Target, FluidPlacementStrategy.Target {
        AEFluidKey fluid;
        boolean source;
        boolean solidBlock;
        boolean mayModify = true;
        /** What the world hands out when the source is picked up (null: what was there). */
        GenericStack released;
        int pickups, placements, pickupEffects, placeEffects, canPlaceChecks;

        void source(AEFluidKey what) {
            fluid = what;
            source = what != null;
        }

        void flowing(AEFluidKey what) {
            fluid = what;
            source = false;
        }

        @Override
        public AEFluidKey getSourceFluid() {
            return mayModify && source ? fluid : null;
        }

        @Override
        public GenericStack pickUpSource(AEFluidKey expected) {
            if (!mayModify || !source || !expected.equals(fluid)) {
                return null;
            }
            pickups++;
            fluid = null;
            source = false;
            return released != null ? released : new GenericStack(expected, AEFluidKey.AMOUNT_BLOCK);
        }

        @Override
        public void playPickupEffect(AEFluidKey what) {
            pickupEffects++;
        }

        @Override
        public boolean canPlace(AEFluidKey what) {
            canPlaceChecks++;
            return mayModify && !solidBlock && !(source && what.equals(fluid));
        }

        @Override
        public boolean placeSource(AEFluidKey what) {
            if (!canPlace(what)) {
                return false;
            }
            placements++;
            source(what);
            return true;
        }

        @Override
        public void playPlaceEffect(AEFluidKey what) {
            placeEffects++;
        }
    }

    private final FakeWorld front = new FakeWorld();
    private CellHostLogic drive;
    private GridNode planeNode;

    /** A drive below the cable with a 1k fluid cell (1024 bytes, 8000 mB per byte) in slot 0. */
    private void fluidDrive() {
        drive = CellHostLogic.drive();
        world.addNode(p(0, -1, 0), NodeType.DRIVE, null, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Fluid_Storage_Cell_1k", new CellItemData(), null));
    }

    private long inCell(AEFluidKey key) {
        return drive.getCell(0).getAvailableStacks().get(key);
    }

    // ------------------------------------------------------------------ annihilation plane

    private AnnihilationPlaneLogic annihilation(boolean withCell) {
        network(0, 2, 0);
        if (withCell) {
            fluidDrive();
        }
        var plane = new AnnihilationPlaneLogic();
        plane.setPickupStrategies(List.of(new FluidPickupStrategy(front)));
        planeNode = partAbove(1, 0, NodeType.ANNIHILATION_PLANE, plane);
        world.tick();
        assertTrue(planeNode.isOnline());
        return plane;
    }

    /** Lets the plane look again, like after a change of the block in front. */
    private void lookAgain(AnnihilationPlaneLogic plane) {
        plane.onNeighborChanged();
        world.tick(TickRates.AnnihilationPlane.getMin() + 1);
    }

    @Test
    void picksUpASourceBlockAsOneBucket() {
        var plane = annihilation(true);
        front.source(WATER);
        lookAgain(plane);

        assertEquals(1, front.pickups);
        assertNull(front.fluid, "the source is gone from the world");
        assertEquals(1000, inCell(WATER));
        assertEquals(1, front.pickupEffects);

        // source by source, a second fluid too
        front.source(WATER);
        lookAgain(plane);
        front.source(LAVA);
        lookAgain(plane);
        assertEquals(3, front.pickups);
        assertEquals(2000, inCell(WATER));
        assertEquals(1000, inCell(LAVA));
    }

    @Test
    void flowingFluidStaysWhereItIs() {
        var plane = annihilation(true);
        front.flowing(WATER);
        lookAgain(plane);
        world.tick(300);

        assertEquals(0, front.pickups);
        assertEquals(WATER, front.fluid);
        assertEquals(0, inCell(WATER));
        assertEquals(PickupStrategy.Result.CANT_PICKUP,
                new FluidPickupStrategy(front).tryPickup(planeNode.getGrid().getEnergyService(), (w, a, m) -> a));
    }

    @Test
    void nothingIsTakenThatCannotBeStored() {
        var plane = annihilation(false);
        front.source(WATER);
        lookAgain(plane);
        world.tick(300);
        assertEquals(0, front.pickups, "no fluid cell: the source stays");
        assertEquals(WATER, front.fluid);

        // a cell that has room for less than a block
        fluidDrive();
        world.tick();
        long room = insertIntoNetwork(planeNode, WATER, Long.MAX_VALUE / 4);
        assertTrue(room > 0);
        extractFromNetwork(planeNode, WATER, 999);
        lookAgain(plane);
        world.tick(300);
        assertEquals(0, front.pickups, "999 mB of room are not enough for a source block");
        extractFromNetwork(planeNode, WATER, 1);
        lookAgain(plane);
        world.tick(TickRates.AnnihilationPlane.getMax() + 1);
        assertEquals(1, front.pickups);
        assertEquals(room, inCell(WATER));
    }

    @Test
    void protectedPositionsAreLeftAlone() {
        var plane = annihilation(true);
        front.mayModify = false;
        front.source(WATER);
        lookAgain(plane);
        world.tick(300);
        assertEquals(0, front.pickups);
        assertEquals(WATER, front.fluid);

        front.mayModify = true;
        lookAgain(plane);
        assertEquals(1, front.pickups);
    }

    @Test
    void whatTheWorldReleasesIsWhatGetsStored() {
        annihilation(true);
        var strategy = new FluidPickupStrategy(front);
        var energy = planeNode.getGrid().getEnergyService();
        long[] stored = new long[2];
        PickupSink sink = (what, amount, mode) -> {
            if (mode == Actionable.MODULATE) {
                stored[what.equals(WATER) ? 0 : 1] += amount;
            }
            return amount;
        };

        front.source(WATER);
        front.released = new GenericStack(LAVA, 500); // the world hands out something else
        assertEquals(PickupStrategy.Result.PICKED_UP, strategy.tryPickup(energy, sink));
        assertEquals(0, stored[0]);
        assertEquals(500, stored[1]);

        // the network refuses: the source is not touched
        front.released = null;
        front.source(WATER);
        assertEquals(PickupStrategy.Result.CANT_STORE, strategy.tryPickup(energy, (what, amount, mode) -> amount - 1));
        assertEquals(WATER, front.fluid);

        // entities are none of its business
        assertFalse(strategy.canPickUpEntity(new Object()));
        assertFalse(strategy.pickUpEntity(energy, sink, new Object()));
    }

    // ------------------------------------------------------------------ formation plane

    private FluidPlacementStrategy placement;

    private FormationPlaneLogic formation(boolean withCell) {
        network(0, 2, 0);
        if (withCell) {
            fluidDrive();
        }
        var plane = new FormationPlaneLogic();
        placement = new FluidPlacementStrategy(front);
        Map<AEKeyType, PlacementStrategy> strategies = new EnumMap<>(AEKeyType.class);
        strategies.put(AEKeyType.FLUIDS, placement);
        plane.setPlacementStrategy(new PlacementStrategyFacade(strategies));
        planeNode = partAbove(1, 0, NodeType.FORMATION_PLANE, plane);
        world.tick();
        assertTrue(planeNode.isOnline());
        return plane;
    }

    @Test
    void placesASourceBlockForAFullBucket() {
        var plane = formation(false);
        plane.getConfig().setKey(0, WATER);

        assertEquals(0, insertIntoNetwork(planeNode, WATER, 999), "need a full bucket");
        assertNull(front.fluid);
        assertEquals(1000, insertIntoNetwork(planeNode, WATER, 5000), "one block per insert, the rest is not taken");
        assertEquals(WATER, front.fluid);
        assertTrue(front.source);
        assertEquals(1, front.placements);
        assertEquals(1, front.placeEffects);

        // the source is in place: nothing is accepted and the world is not asked again and again
        assertEquals(0, insertIntoNetwork(planeNode, WATER, 1000));
        assertTrue(placement.isBlocked(WATER));
        int checks = front.canPlaceChecks;
        assertEquals(0, insertIntoNetwork(planeNode, WATER, 1000));
        assertEquals(checks, front.canPlaceChecks);

        // something took the source away (the plane is told, like a neighbour update)
        front.source(null);
        plane.onNeighborChanged();
        assertEquals(1000, insertIntoNetwork(planeNode, WATER, 1000));
        assertEquals(2, front.placements);
    }

    @Test
    void theFilterDecidesWhichFluidIsPlaced() {
        var plane = formation(true);
        plane.setPriority(1);
        plane.getConfig().setKey(0, LAVA);

        assertEquals(3000, insertIntoNetwork(planeNode, WATER, 3000));
        assertNull(front.fluid, "water is not in the filter");
        assertEquals(3000, inCell(WATER));

        // one block goes into the world, the rest into the cell; less than a block goes to the cell entirely
        assertEquals(3000, insertIntoNetwork(planeNode, LAVA, 3000));
        assertEquals(LAVA, front.fluid);
        assertEquals(2000, inCell(LAVA));
        front.source(null);
        plane.onNeighborChanged();
        assertEquals(500, insertIntoNetwork(planeNode, LAVA, 500));
        assertNull(front.fluid);
        assertEquals(2500, inCell(LAVA));
    }

    @Test
    void flowingFluidAndOtherFluidsAreReplacedSolidBlocksAreNot() {
        var plane = formation(false);
        front.flowing(WATER);
        assertEquals(1000, insertIntoNetwork(planeNode, WATER, 1000));
        assertTrue(front.source);

        // a different fluid in front is overwritten, like a bucket does
        plane.onNeighborChanged();
        assertEquals(1000, insertIntoNetwork(planeNode, LAVA, 1000));
        assertEquals(LAVA, front.fluid);

        front.source(null);
        front.solidBlock = true;
        plane.onNeighborChanged();
        assertEquals(0, insertIntoNetwork(planeNode, WATER, 1000));
        assertNull(front.fluid);
        front.solidBlock = false;
        front.mayModify = false;
        plane.onNeighborChanged();
        assertEquals(0, insertIntoNetwork(planeNode, WATER, 1000));
        assertNull(front.fluid);
    }

    @Test
    void droppingItemsInsteadOfBlocksPlacesNoFluid() {
        var plane = formation(false);
        plane.setPlaceBlock(YesNo.NO);
        assertEquals(0, insertIntoNetwork(planeNode, WATER, 1000));
        assertNull(front.fluid);
        assertEquals(0, placement.placeInWorld(STONE, 64, Actionable.MODULATE, false), "items are not its business");

        // simulating does not touch the world
        plane.setPlaceBlock(YesNo.YES);
        assertEquals(1000, placement.placeInWorld(WATER, 4000, Actionable.SIMULATE, false));
        assertNull(front.fluid);
        assertEquals(0, front.placements);
    }
}
