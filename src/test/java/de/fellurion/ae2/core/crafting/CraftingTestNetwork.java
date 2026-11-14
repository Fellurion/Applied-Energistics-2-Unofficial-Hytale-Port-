/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCellTier;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Small test network: cables along x at y=0, devices on top (y=1): creative energy cell, drive (one 4k item cell),
 * pattern provider, molecular assembler, crafting CPU blocks.
 */
final class CraftingTestNetwork {
    static final AEItemKey PLANK = AEItemKey.of("Wood_Plank");
    static final AEItemKey STICK = AEItemKey.of("Stick");
    static final AEItemKey FENCE = AEItemKey.of("Fence");
    static final AEItemKey ORE = AEItemKey.of("Ore_Iron");
    static final AEItemKey INGOT = AEItemKey.of("Ingot_Iron");

    final MEWorld world = new MEWorld();
    final CellHostLogic drive = CellHostLogic.drive();
    final PatternProviderLogic provider = new PatternProviderLogic();
    final MolecularAssemblerLogic assembler = new MolecularAssemblerLogic();
    final InstantMachine machine;
    final List<CraftingUnitLogic> units = new ArrayList<>();
    final IActionSource src = IActionSource.ofPlayer("tester");

    /** 1 plank → 4 sticks (crafting pattern). */
    static final CraftingPattern STICKS = CraftingPattern.of(
            java.util.Arrays.asList(PLANK, null, null, null, null, null, null, null, null),
            new GenericStack(STICK, 4));
    /** 3 sticks + 1 plank → 1 fence (processing pattern, sparse inputs). */
    static final ProcessingPattern FENCES = ProcessingPattern.of(
            java.util.Arrays.asList(new GenericStack(STICK, 2), new GenericStack(PLANK, 1), null,
                    new GenericStack(STICK, 1)),
            List.of(new GenericStack(FENCE, 1)));
    /** 1 ore → 1 ingot. */
    static final ProcessingPattern SMELT = ProcessingPattern.of(List.of(new GenericStack(ORE, 1)),
            List.of(new GenericStack(INGOT, 1)));

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /**
     * @param cpuBlocks crafting unit types placed along x starting at x=5 (all adjacent → one cuboid row)
     */
    CraftingTestNetwork(List<? extends IPatternDetails> patterns, CraftingUnitLogic.UnitType... cpuBlocks) {
        machine = new InstantMachine(provider);
        for (int x = 0; x < 5 + Math.max(1, cpuBlocks.length); x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
        }
        world.addNode(p(0, 1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        world.addNode(p(1, 1, 0), NodeType.DRIVE, null, null, drive);
        drive.setCell(0, StorageCells.getCellInventory(CellDefinition.itemCell(StorageCellTier.SIZE_4K).itemId(),
                new CellItemData(), null));
        world.addNode(p(2, 1, 0), NodeType.PATTERN_PROVIDER, null, null, provider);
        world.addNode(p(3, 1, 0), NodeType.MOLECULAR_ASSEMBLER, null, null, assembler);
        provider.setTargetSupplier(() -> List.of(assembler, machine));
        provider.setPatternDetails(patterns);
        for (int i = 0; i < cpuBlocks.length; i++) {
            var u = new CraftingUnitLogic(cpuBlocks[i]);
            units.add(u);
            world.addNode(p(5 + i, 1, 0), nodeType(cpuBlocks[i]), null, null, u);
        }
        world.tick(2);
    }

    static NodeType nodeType(CraftingUnitLogic.UnitType t) {
        return switch (t) {
            case UNIT -> NodeType.CRAFTING_UNIT;
            case ACCELERATOR -> NodeType.CRAFTING_ACCELERATOR;
            case MONITOR -> NodeType.CRAFTING_MONITOR;
            default -> NodeType.CRAFTING_STORAGE;
        };
    }

    Grid grid() {
        return world.getNode(p(0, 0, 0)).getGrid();
    }

    CraftingService crafting() {
        return grid().getCraftingService();
    }

    void insert(AEKey key, long amount) {
        var inserted = grid().getStorageService().getInventory().insert(key, amount, Actionable.MODULATE, src);
        if (inserted != amount) {
            throw new IllegalStateException("could not insert " + amount + " " + key);
        }
    }

    long stored(AEKey key) {
        return grid().getStorageService().getInventory().getAvailableStacks().get(key);
    }

    /**
     * Test machine: accepts all items, "processes" processing patterns instantly by returning the outputs of complete
     * input sets into the provider's return inventory. Records the world tick of every insert batch.
     */
    final class InstantMachine implements PatternProviderTarget {
        private final PatternProviderLogic provider;
        final KeyCounter received = new KeyCounter();
        final List<Long> pushTicks = new ArrayList<>();
        boolean process = true;

        InstantMachine(PatternProviderLogic provider) {
            this.provider = provider;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable type) {
            if (type == Actionable.MODULATE) {
                received.add(what, amount);
                if (process) {
                    processAll();
                }
            }
            return amount;
        }

        void markPush() {
            pushTicks.add(world.getCurrentTick());
        }

        private void processAll() {
            // 3 sticks + 1 plank -> fence
            while (received.get(STICK) >= 3 && received.get(PLANK) >= 1) {
                received.remove(STICK, 3);
                received.remove(PLANK, 1);
                markPush();
                provider.getReturnInventory().insert(FENCE, 1, Actionable.MODULATE);
            }
            while (received.get(ORE) >= 1) {
                received.remove(ORE, 1);
                markPush();
                provider.getReturnInventory().insert(INGOT, 1, Actionable.MODULATE);
            }
        }

        @Override
        public boolean containsPatternInput(Set<AEKey> patternInputs) {
            return false;
        }
    }
}
