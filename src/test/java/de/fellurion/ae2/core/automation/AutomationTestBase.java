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
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.BeforeEach;

/**
 * Builds small real networks: a creative energy cell, a line of cables along +X at y=0 and parts above the cables
 * (y=1, facing up so their back connects downwards).
 */
abstract class AutomationTestBase {
    static final AEItemKey STONE = AEItemKey.of("Rock_Stone");
    static final AEItemKey DIRT = AEItemKey.of("Soil_Dirt");
    static final AEItemKey GOLD = AEItemKey.of("Ingredient_Bar_Gold");
    static final AEItemKey IRON = AEItemKey.of("Ingredient_Bar_Iron");
    static final AEItemKey COBBLE = AEItemKey.of("Rock_Stone_Cobble");

    MEWorld world;

    @BeforeEach
    void setUpWorld() {
        AEConfig.reset();
        world = new MEWorld();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** Creative energy cell at (x0-1, 0, z) and cables from x0..x1 at y=0. */
    void network(int x0, int x1, int z) {
        world.addNode(p(x0 - 1, 0, z), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        for (int x = x0; x <= x1; x++) {
            world.addNode(p(x, 0, z), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
        }
    }

    /** A drive below the cable at (x, -1, z) with a fresh 4k item cell in slot 0. */
    CellHostLogic drive(int x, int z) {
        var drive = CellHostLogic.drive();
        world.addNode(p(x, -1, z), NodeType.DRIVE, null, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_4k", new CellItemData(), null));
        return drive;
    }

    /** Places a part-block above the cable at (x, 0, z), facing up. */
    GridNode partAbove(int x, int z, NodeType type, Object logic) {
        return world.addNode(p(x, 1, z), type, Direction.UP, null, logic);
    }

    static SimpleSlotAccess chest(int slots) {
        return new SimpleSlotAccess(slots);
    }

    static Grid grid(GridNode node) {
        return node.getGrid();
    }

    static long networkAmount(GridNode node, AEKey key) {
        return node.getGrid().getStorageService().getInventory().getAvailableStacks().get(key);
    }

    static long insertIntoNetwork(GridNode node, AEKey key, long amount) {
        return node.getGrid().getStorageService().getInventory().insert(key, amount, Actionable.MODULATE,
                IActionSource.empty());
    }

    static long extractFromNetwork(GridNode node, AEKey key, long amount) {
        return node.getGrid().getStorageService().getInventory().extract(key, amount, Actionable.MODULATE,
                IActionSource.empty());
    }
}
