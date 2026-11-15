/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Formation plane (port of AE2 FormationPlanePart + ItemPlacementStrategy) against a fake world: filter, priority,
 * block / entity mode, blocked position, partial placement, nothing voided.
 */
class FormationPlaneTest extends AutomationTestBase {

    /** One block position that holds at most one block, and the items dropped there. */
    static final class FakeTarget implements ItemPlacementStrategy.Target {
        /** The block at the position (item id), or null for air. */
        String block;
        /** Items that are blocks. */
        final Set<String> blockItems = Set.of(STONE.itemId(), DIRT.itemId(), COBBLE.itemId());
        final List<String> dropped = new ArrayList<>();
        int entitiesAround;
        int replaceableQueries;
        boolean placingFails;

        @Override
        public boolean canBeReplaced() {
            replaceableQueries++;
            return block == null;
        }

        @Override
        public int getMaxStackSize(AEItemKey what) {
            return 64;
        }

        @Override
        public int countEntitiesAround() {
            return entitiesAround;
        }

        @Override
        public void spawnItemEntity(AEItemKey what, int amount) {
            dropped.add(amount + " " + what.itemId());
            entitiesAround++;
        }

        @Override
        public boolean canPlaceBlock(AEItemKey what) {
            return block == null && blockItems.contains(what.itemId());
        }

        @Override
        public int placeBlock(AEItemKey what) {
            if (placingFails || !canPlaceBlock(what)) {
                return 0;
            }
            block = what.itemId();
            return 1;
        }
    }

    private FormationPlaneLogic plane;
    private GridNode planeNode;
    private FakeTarget target;
    private ItemPlacementStrategy strategy;
    private CellHostLogic drive;

    private void setUp(boolean withDrive) {
        network(0, 2, 0);
        drive = withDrive ? drive(0, 0) : null;
        plane = new FormationPlaneLogic();
        target = new FakeTarget();
        strategy = new ItemPlacementStrategy(target);
        plane.setPlacementStrategy(new PlacementStrategyFacade(Map.of(AEKeyType.ITEMS, strategy)));
        planeNode = partAbove(1, 0, NodeType.FORMATION_PLANE, plane);
        world.tick();
        assertTrue(planeNode.isOnline());
    }

    @AfterEach
    void resetLimit() {
        ItemPlacementStrategy.setEntityLimit(ItemPlacementStrategy.DEFAULT_ENTITY_LIMIT);
    }

    private long inDrive(AEKey key) {
        return drive.getCell(0).getAvailableStacks().get(key);
    }

    /** The block is broken by something else (the plane is told, like a neighbour update). */
    private void clearBlock() {
        target.block = null;
        plane.onNeighborChanged();
    }

    @Test
    void placesAMatchingBlockAndReportsOnlyThat() {
        setUp(false);
        plane.getConfig().setKey(0, STONE);

        assertEquals(1, insertIntoNetwork(planeNode, STONE, 10), "one block per insert, the rest is not taken");
        assertEquals(STONE.itemId(), target.block);
        assertTrue(target.dropped.isEmpty());

        // the position is occupied now: nothing is accepted, nothing vanishes
        assertEquals(0, insertIntoNetwork(planeNode, STONE, 10));
        clearBlock();
        assertEquals(1, insertIntoNetwork(planeNode, STONE, 10));
    }

    @Test
    void theRestGoesToOtherStorage() {
        setUp(true);
        plane.setPriority(1);
        plane.getConfig().setKey(0, STONE);

        assertEquals(10, insertIntoNetwork(planeNode, STONE, 10));
        assertEquals(STONE.itemId(), target.block);
        assertEquals(9, inDrive(STONE), "1 placed + 9 stored = 10, nothing lost");
        assertEquals(9, networkAmount(planeNode, STONE), "the placed block is not listed as stored");
    }

    @Test
    void filterDecidesWhatIsPlaced() {
        setUp(true);
        plane.setPriority(1);
        plane.getConfig().setKey(0, STONE);

        assertEquals(5, insertIntoNetwork(planeNode, DIRT, 5));
        assertNull(target.block, "dirt is not in the filter");
        assertEquals(5, inDrive(DIRT));

        // empty filter: everything
        plane.getConfig().setKey(0, null);
        assertEquals(5, insertIntoNetwork(planeNode, DIRT, 5));
        assertEquals(DIRT.itemId(), target.block);
        assertEquals(9, inDrive(DIRT));
    }

    @Test
    void inverterCardTurnsTheFilterIntoABlacklist() {
        setUp(true);
        plane.setPriority(1);
        plane.getConfig().setKey(0, STONE);
        assertTrue(plane.getUpgrades().insert(UpgradeCard.INVERTER));
        assertTrue(plane.isInverted());

        insertIntoNetwork(planeNode, STONE, 3);
        assertNull(target.block);
        assertEquals(3, inDrive(STONE));
        insertIntoNetwork(planeNode, DIRT, 3);
        assertEquals(DIRT.itemId(), target.block);
        assertEquals(2, inDrive(DIRT));
    }

    @Test
    void filterSlotsBeyondTheCapacityAreIgnored() {
        setUp(true);
        plane.setPriority(1);
        assertEquals(18, plane.availableSlots());
        plane.getConfig().setKey(0, STONE);
        plane.getConfig().setKey(20, DIRT); // needs a capacity card

        insertIntoNetwork(planeNode, DIRT, 1);
        assertNull(target.block);

        assertTrue(plane.getUpgrades().insert(UpgradeCard.CAPACITY));
        assertEquals(27, plane.availableSlots());
        insertIntoNetwork(planeNode, DIRT, 1);
        assertEquals(DIRT.itemId(), target.block);

        for (int i = 0; i < 4; i++) {
            assertTrue(plane.getUpgrades().insert(UpgradeCard.CAPACITY));
        }
        assertEquals(63, plane.availableSlots(), "18 + 5 · 9");
        assertFalse(plane.getUpgrades().canInsert(UpgradeCard.CAPACITY), "5 capacity cards at most");
        assertFalse(plane.getUpgrades().canInsert(UpgradeCard.SPEED));
    }

    @Test
    void fuzzyCardMatchesOtherVariants() {
        setUp(true);
        plane.setPriority(1);
        var damaged = AEItemKey.of(STONE.itemId(), "{\"d\":5.0}");
        plane.getConfig().setKey(0, STONE);

        insertIntoNetwork(planeNode, damaged, 1);
        assertEquals(0, target.replaceableQueries, "exact filter: the variant never reaches the world");
        assertEquals(1, inDrive(damaged));

        assertTrue(plane.getUpgrades().insert(UpgradeCard.FUZZY));
        plane.setFuzzyMode(FuzzyMode.IGNORE_ALL);
        insertIntoNetwork(planeNode, damaged, 1);
        assertTrue(target.replaceableQueries > 0, "fuzzy filter lets the variant through");
    }

    @Test
    void priorityDecidesBetweenPlaneAndDrive() {
        setUp(true);
        plane.setPriority(-1);
        assertEquals(4, insertIntoNetwork(planeNode, STONE, 4));
        assertNull(target.block, "the drive (priority 0) takes everything first");
        assertEquals(4, inDrive(STONE));

        plane.setPriority(5);
        assertEquals(4, insertIntoNetwork(planeNode, STONE, 4));
        assertEquals(STONE.itemId(), target.block, "now the plane is asked first");
        assertEquals(7, inDrive(STONE));
    }

    @Test
    void blockedPositionIsRememberedUntilTheNeighbourChanges() {
        setUp(true);
        plane.setPriority(1);
        target.block = COBBLE.itemId();

        assertEquals(3, insertIntoNetwork(planeNode, STONE, 3));
        assertEquals(COBBLE.itemId(), target.block, "never replaces a solid block");
        assertEquals(3, inDrive(STONE), "the items stay in the network");
        assertTrue(strategy.isBlocked());

        // while blocked the world is not asked again
        int queries = target.replaceableQueries;
        insertIntoNetwork(planeNode, STONE, 3);
        assertEquals(queries, target.replaceableQueries);

        // the block disappears, but nobody tells the plane yet
        target.block = null;
        insertIntoNetwork(planeNode, STONE, 1);
        assertNull(target.block);

        plane.onNeighborChanged();
        assertFalse(strategy.isBlocked());
        insertIntoNetwork(planeNode, STONE, 1);
        assertEquals(STONE.itemId(), target.block);
    }

    @Test
    void dropsItemsAsEntitiesWhenPlacingIsOff() {
        setUp(true);
        plane.setPriority(1);
        plane.setPlaceBlock(YesNo.NO);

        assertEquals(100, insertIntoNetwork(planeNode, STONE, 100));
        assertNull(target.block);
        assertEquals(List.of("64 " + STONE.itemId()), target.dropped, "one stack per insert");
        assertEquals(36, inDrive(STONE));

        // items that are no blocks are dropped as well
        assertEquals(2, insertIntoNetwork(planeNode, GOLD, 2));
        assertEquals("2 " + GOLD.itemId(), target.dropped.get(1));
        assertEquals(0, inDrive(GOLD));
    }

    @Test
    void entityLimitStopsDropping() {
        setUp(true);
        plane.setPriority(1);
        plane.setPlaceBlock(YesNo.NO);
        target.entitiesAround = 128;

        assertEquals(5, insertIntoNetwork(planeNode, STONE, 5));
        assertTrue(target.dropped.isEmpty(), "AE2: no more drops with 128 entities around");
        assertEquals(5, inDrive(STONE));

        ItemPlacementStrategy.setEntityLimit(200);
        insertIntoNetwork(planeNode, STONE, 5);
        assertEquals(1, target.dropped.size());
    }

    @Test
    void itemsThatAreNoBlocksStayInTheNetworkInBlockMode() {
        setUp(true);
        plane.setPriority(1);

        assertEquals(3, insertIntoNetwork(planeNode, GOLD, 3));
        assertNull(target.block);
        assertTrue(target.dropped.isEmpty());
        assertEquals(3, inDrive(GOLD));
    }

    @Test
    void aFailedPlacementConsumesNothing() {
        setUp(false);
        target.placingFails = true; // e.g. refused at the last moment

        assertEquals(0, insertIntoNetwork(planeNode, STONE, 5), "reports only what really was placed");
        assertNull(target.block);
    }

    @Test
    void simulationDoesNotTouchTheWorld() {
        setUp(false);
        var storage = grid(planeNode).getStorageService().getInventory();

        assertEquals(1, storage.insert(STONE, 5, Actionable.SIMULATE, IActionSource.empty()));
        assertNull(target.block);
        assertEquals(0, storage.insert(GOLD, 5, Actionable.SIMULATE, IActionSource.empty()),
                "a simulation does not promise what cannot be placed");

        plane.setPlaceBlock(YesNo.NO);
        assertEquals(5, storage.insert(GOLD, 5, Actionable.SIMULATE, IActionSource.empty()));
        assertTrue(target.dropped.isEmpty());
    }

    @Test
    void nothingCanBeExtractedOrListed() {
        setUp(false);
        insertIntoNetwork(planeNode, STONE, 1);
        assertEquals(STONE.itemId(), target.block);
        assertEquals(0, networkAmount(planeNode, STONE));
        assertEquals(0, extractFromNetwork(planeNode, STONE, 1));
    }

    @Test
    void fluidsNeedTheirOwnStrategy() {
        setUp(false);
        var water = AEFluidKey.of("Water");
        assertEquals(0, insertIntoNetwork(planeNode, water, 1000), "no strategy for fluids: not accepted");

        // the seam for fluids: one more strategy in the facade
        var placed = new ArrayList<String>();
        PlacementStrategy fluids = new PlacementStrategy() {
            @Override
            public void clearBlocked() {
            }

            @Override
            public long placeInWorld(AEKey what, long amount, Actionable type, boolean placeAsEntity) {
                if (placeAsEntity || amount < 1000) {
                    return 0;
                }
                if (type == Actionable.MODULATE) {
                    placed.add(what.getId());
                }
                return 1000;
            }
        };
        plane.setPlacementStrategy(new PlacementStrategyFacade(Map.of(AEKeyType.ITEMS, strategy, AEKeyType.FLUIDS,
                fluids)));
        assertEquals(1000, insertIntoNetwork(planeNode, water, 2500));
        assertEquals(List.of("Water"), placed);
        assertEquals(1, insertIntoNetwork(planeNode, STONE, 2), "items still go to the item strategy");
    }

    @Test
    void offlinePlaneIsNotPartOfTheStorage() {
        network(0, 2, 0);
        plane = new FormationPlaneLogic();
        target = new FakeTarget();
        plane.setPlacementStrategy(new PlacementStrategyFacade(Map.of(AEKeyType.ITEMS,
                new ItemPlacementStrategy(target))));
        planeNode = partAbove(1, 0, NodeType.FORMATION_PLANE, plane);
        world.tick();
        assertTrue(planeNode.isOnline());
        assertEquals(1, insertIntoNetwork(planeNode, STONE, 1));
        target.block = null;
        plane.onNeighborChanged();

        var lonely = new FormationPlaneLogic();
        var lonelyTarget = new FakeTarget();
        lonely.setPlacementStrategy(new PlacementStrategyFacade(Map.of(AEKeyType.ITEMS,
                new ItemPlacementStrategy(lonelyTarget))));
        var lonelyNode = world.addNode(p(1, 9, 0), NodeType.FORMATION_PLANE, Direction.UP, null, lonely);
        world.tick(40);
        assertFalse(lonelyNode.isOnline(), "no power");
        assertEquals(0, insertIntoNetwork(lonelyNode, STONE, 1));
        assertNull(lonelyTarget.block);
    }

    @Test
    void stateSurvivesSavingAndLoading() {
        setUp(false);
        plane.getConfig().setKey(0, STONE);
        plane.getConfig().setKey(3, DIRT);
        plane.getUpgrades().insert(UpgradeCard.FUZZY);
        plane.getUpgrades().insert(UpgradeCard.CAPACITY);
        plane.setFuzzyMode(FuzzyMode.PERCENT_25);
        plane.setPlaceBlock(YesNo.NO);
        plane.setPriority(7);

        var copy = new FormationPlaneLogic();
        int[] saves = {0};
        copy.fromJson(plane.toJson());
        copy.setSaveListener(() -> saves[0]++);
        assertEquals(STONE, copy.getConfig().getKey(0));
        assertEquals(DIRT, copy.getConfig().getKey(3));
        assertTrue(copy.getUpgrades().isInstalled(UpgradeCard.FUZZY));
        assertEquals(1, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.CAPACITY));
        assertEquals(FuzzyMode.PERCENT_25, copy.getFuzzyMode());
        assertEquals(YesNo.NO, copy.getPlaceBlock());
        assertEquals(7, copy.getPriority());
        assertEquals(plane.toJson(), copy.toJson());

        copy.setPlaceBlock(YesNo.YES);
        copy.getConfig().setKey(1, GOLD);
        copy.setPriority(2);
        assertEquals(3, saves[0], "every change asks to be saved");
        new FormationPlaneLogic().fromJson(null);
        new FormationPlaneLogic().fromJson("");
    }
}
