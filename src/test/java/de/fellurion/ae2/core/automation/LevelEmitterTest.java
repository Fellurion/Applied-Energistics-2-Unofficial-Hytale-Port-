/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Level emitter and energy level emitter (ports of AE2 StorageLevelEmitterPart, EnergyLevelEmitterPart,
 * AbstractLevelEmitterPart) plus the import bus that listens to the emitter through its redstone card.
 */
class LevelEmitterTest extends AutomationTestBase {

    private LevelEmitterLogic emitter;
    private GridNode emitterNode;
    private final List<Boolean> outputs = new ArrayList<>();

    /** Powered network with a drive (4k cell) and a level emitter watching stone with threshold 100. */
    private void setUpEmitter() {
        network(0, 2, 0);
        drive(0, 0);
        emitter = new LevelEmitterLogic();
        emitter.setOutputListener(outputs::add);
        emitter.getConfig().setKey(0, STONE);
        emitter.setReportingValue(100);
        emitterNode = partAbove(1, 0, NodeType.LEVEL_EMITTER, emitter);
        world.tick();
        assertTrue(emitterNode.isActive());
    }

    @Test
    void levelEmittersNeedNoChannel() {
        assertFalse(NodeType.LEVEL_EMITTER.requiresChannel());
        assertFalse(NodeType.ENERGY_LEVEL_EMITTER.requiresChannel());
        assertEquals(1.0, NodeType.LEVEL_EMITTER.idlePower());
    }

    @Test
    void highSignalEmitsAtOrAboveThresholdAndFollowsTheNetwork() {
        setUpEmitter();
        assertEquals(RedstoneMode.HIGH_SIGNAL, emitter.getRedstoneMode(), "AE2 default");
        assertFalse(emitter.isEmitting(), "0 < 100");

        insertIntoNetwork(emitterNode, STONE, 99);
        world.tick();
        assertFalse(emitter.isEmitting(), "99 < 100");
        assertEquals(99, emitter.getLastReportedValue());

        insertIntoNetwork(emitterNode, STONE, 1);
        world.tick();
        assertTrue(emitter.isEmitting(), "100 >= 100");

        insertIntoNetwork(emitterNode, STONE, 400);
        insertIntoNetwork(emitterNode, DIRT, 1000);
        world.tick();
        assertTrue(emitter.isEmitting());
        assertEquals(500, emitter.getLastReportedValue(), "other items do not count");

        extractFromNetwork(emitterNode, STONE, 401);
        world.tick();
        assertFalse(emitter.isEmitting(), "99 < 100 after extraction");

        extractFromNetwork(emitterNode, STONE, 99);
        world.tick();
        assertFalse(emitter.isEmitting(), "item is gone");
        assertEquals(0, emitter.getLastReportedValue());

        assertEquals(List.of(true, false), outputs, "the listener only hears changes");
    }

    @Test
    void lowSignalIsInverted() {
        setUpEmitter();
        emitter.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertTrue(emitter.isEmitting(), "0 < 100");

        insertIntoNetwork(emitterNode, STONE, 100);
        world.tick();
        assertFalse(emitter.isEmitting(), "100 is not below 100");

        extractFromNetwork(emitterNode, STONE, 1);
        world.tick();
        assertTrue(emitter.isEmitting(), "99 < 100");

        emitter.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        assertFalse(emitter.isEmitting(), "switching the mode takes effect at once");
        assertThrows(IllegalArgumentException.class, () -> emitter.setRedstoneMode(RedstoneMode.SIGNAL_PULSE));
        assertThrows(IllegalArgumentException.class, () -> emitter.setRedstoneMode(RedstoneMode.IGNORE));
    }

    @Test
    void changingTheThresholdTakesEffectAtOnce() {
        setUpEmitter();
        insertIntoNetwork(emitterNode, STONE, 50);
        world.tick();
        assertFalse(emitter.isEmitting());
        emitter.setReportingValue(50);
        assertTrue(emitter.isEmitting());
        emitter.setReportingValue(51);
        assertFalse(emitter.isEmitting());
        emitter.setReportingValue(0);
        assertTrue(emitter.isEmitting(), "threshold 0 is always reached");
        emitter.setReportingValue(-5);
        assertEquals(0, emitter.getReportingValue(), "negative thresholds are clamped");
    }

    @Test
    void withoutKeyTheTotalAmountCounts() {
        setUpEmitter();
        emitter.getConfig().setKey(0, null);
        assertNull(emitter.getConfiguredKey());
        insertIntoNetwork(emitterNode, STONE, 60);
        world.tick();
        assertFalse(emitter.isEmitting());
        insertIntoNetwork(emitterNode, DIRT, 40);
        world.tick();
        assertTrue(emitter.isEmitting(), "60 stone + 40 dirt >= 100");
        extractFromNetwork(emitterNode, DIRT, 1);
        world.tick();
        assertFalse(emitter.isEmitting());

        // setting the key again: only that item counts
        emitter.getConfig().setKey(0, DIRT);
        assertEquals(39, emitter.getLastReportedValue());
        emitter.setReportingValue(39);
        assertTrue(emitter.isEmitting());
    }

    @Test
    void blankEmitterStopsCountingOnceAboveTheThreshold() {
        setUpEmitter();
        emitter.getConfig().setKey(0, null);
        emitter.setReportingValue(10);
        insertIntoNetwork(emitterNode, STONE, 500);
        insertIntoNetwork(emitterNode, DIRT, 500);
        insertIntoNetwork(emitterNode, GOLD, 500);
        world.tick();
        assertTrue(emitter.isEmitting());
        assertEquals(500, emitter.getLastReportedValue(), "the scan stopped after the first stack");
        // raising the threshold rescans
        emitter.setReportingValue(1200);
        assertTrue(emitter.isEmitting());
        assertEquals(1500, emitter.getLastReportedValue());
        emitter.setReportingValue(1501);
        assertFalse(emitter.isEmitting());
    }

    @Test
    void fuzzyCardSumsAllVariantsOfTheItem() {
        setUpEmitter();
        var worn = AEItemKey.of("Rock_Stone", "{\"d\":5.0}");
        var named = AEItemKey.of("Rock_Stone", "{\"m\":{\"n\":1}}");
        insertIntoNetwork(emitterNode, STONE, 40);
        insertIntoNetwork(emitterNode, worn, 30);
        insertIntoNetwork(emitterNode, named, 30);
        insertIntoNetwork(emitterNode, DIRT, 500);
        world.tick();
        assertFalse(emitter.isEmitting(), "exact match: only 40");
        assertEquals(40, emitter.getLastReportedValue());

        assertTrue(emitter.getUpgrades().insert(UpgradeCard.FUZZY));
        assertEquals(FuzzyMode.IGNORE_ALL, emitter.getFuzzyMode());
        assertTrue(emitter.isEmitting(), "fuzzy: 40 + 30 + 30");
        assertEquals(100, emitter.getLastReportedValue());

        extractFromNetwork(emitterNode, worn, 1);
        world.tick();
        assertFalse(emitter.isEmitting(), "a variant changed: 99");

        // the second card does not fit: one slot
        assertFalse(emitter.getUpgrades().canInsert(UpgradeCard.CRAFTING));
        assertFalse(emitter.getUpgrades().canInsert(UpgradeCard.REDSTONE));
        emitter.getUpgrades().remove(0);
        assertEquals(40, emitter.getLastReportedValue(), "without the card: exact again");
    }

    @Test
    void severalChangesBetweenTwoTicksAreNotMissed() {
        setUpEmitter();
        emitter.getConfig().setKey(0, null);
        var storage = emitterNode.getGrid().getStorageService();
        insertIntoNetwork(emitterNode, STONE, 100);
        world.tick();
        assertTrue(emitter.isEmitting());
        // a terminal reads the snapshot between two ticks after a player took items out
        extractFromNetwork(emitterNode, STONE, 50);
        storage.invalidateCache();
        storage.getCachedInventory();
        assertFalse(emitter.isEmitting(), "rescanned with the first rebuild");
        insertIntoNetwork(emitterNode, DIRT, 70);
        storage.invalidateCache();
        storage.getCachedInventory();
        assertTrue(emitter.isEmitting(), "and with the second rebuild in the same tick");
    }

    @Test
    void emitsOnlyWhileTheNodeIsActive() {
        // an energy cell that can be emptied instead of the creative cell
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(10_000);
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        drive(0, 0);
        emitter = new LevelEmitterLogic();
        emitter.setOutputListener(outputs::add);
        emitter.getConfig().setKey(0, STONE);
        emitterNode = partAbove(0, 0, NodeType.LEVEL_EMITTER, emitter);
        world.tick();
        assertTrue(emitterNode.isActive());
        assertTrue(emitter.isEmitting(), "threshold 0");

        cell.setAECurrentPower(0);
        // the grid's own buffer (25 AE per node) keeps it alive for a few ticks
        for (int i = 0; i < 200 && emitterNode.isPowered(); i++) {
            world.tick();
        }
        assertFalse(emitterNode.isPowered());
        assertFalse(emitter.isEmitting(), "no power: no signal");

        cell.setAECurrentPower(10_000);
        for (int i = 0; i < 100 && !emitterNode.isPowered(); i++) {
            world.tick();
        }
        assertTrue(emitter.isEmitting(), "power is back");
        assertEquals(List.of(true, false, true), outputs);
    }

    @Test
    void emitterWithoutNetworkStaysOff() {
        emitter = new LevelEmitterLogic();
        emitterNode = partAbove(0, 0, NodeType.LEVEL_EMITTER, emitter);
        world.tick(40);
        assertFalse(emitter.isEmitting(), "alone: never powered");
        emitter.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertFalse(emitter.isEmitting(), "not even inverted");
    }

    @Test
    void joiningAndLeavingANetwork() {
        network(0, 1, 0);
        drive(0, 0);
        world.tick();
        insertIntoNetwork(world.getNode(p(0, 0, 0)), STONE, 200);
        world.tick();

        emitter = new LevelEmitterLogic();
        emitter.setOutputListener(outputs::add);
        emitter.getConfig().setKey(0, STONE);
        emitter.setReportingValue(100);
        // placed next to the running network
        emitterNode = partAbove(1, 0, NodeType.LEVEL_EMITTER, emitter);
        world.tick();
        assertTrue(emitter.isEmitting(), "reads the amount when it joins");
        assertEquals(200, emitter.getLastReportedValue());

        // the cable below is removed: the emitter is alone and unpowered
        world.removeNode(p(1, 0, 0));
        for (int i = 0; i < 100 && emitterNode.isPowered(); i++) {
            world.tick();
        }
        assertFalse(emitter.isEmitting());

        world.removeNode(p(1, 1, 0));
        assertNull(emitter.getActionableNode());
    }

    @Test
    void saveAndLoad() {
        setUpEmitter();
        emitter.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        emitter.setReportingValue(1234);
        emitter.getUpgrades().insert(UpgradeCard.FUZZY);
        emitter.setFuzzyMode(FuzzyMode.PERCENT_75);
        assertTrue(emitter.isEmitting());
        var json = emitter.saveState();

        var copy = new LevelEmitterLogic();
        copy.loadState(json);
        assertEquals(RedstoneMode.LOW_SIGNAL, copy.getRedstoneMode());
        assertEquals(1234, copy.getReportingValue());
        assertEquals(STONE, copy.getConfiguredKey());
        assertTrue(copy.isUpgradedWith(UpgradeCard.FUZZY));
        assertEquals(FuzzyMode.PERCENT_75, copy.getFuzzyMode());
        assertFalse(copy.isCraftViaRedstone());
        assertTrue(copy.isEmitting(), "the last output is kept until the grid says otherwise (AE2 prevState)");
        assertEquals(json, copy.saveState());

        // crafting card and its mode
        var crafting = new LevelEmitterLogic();
        crafting.getUpgrades().insert(UpgradeCard.CRAFTING);
        crafting.setCraftViaRedstone(true);
        var copy2 = new LevelEmitterLogic();
        copy2.loadState(crafting.saveState());
        assertTrue(copy2.isUpgradedWith(UpgradeCard.CRAFTING));
        assertTrue(copy2.isCraftViaRedstone());

        // empty / missing state: defaults
        var fresh = new LevelEmitterLogic();
        fresh.loadState(null);
        fresh.loadState("");
        assertEquals(RedstoneMode.HIGH_SIGNAL, fresh.getRedstoneMode());
        assertEquals(0, fresh.getReportingValue());
    }

    @Test
    void saveListenerHearsEveryChange() {
        setUpEmitter();
        int[] saves = {0};
        emitter.setSaveListener(() -> saves[0]++);
        emitter.setReportingValue(5);
        assertEquals(1, saves[0]);
        emitter.setReportingValue(5);
        assertEquals(1, saves[0], "unchanged value");
        emitter.getConfig().setKey(0, DIRT);
        emitter.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertTrue(saves[0] >= 3);
        int before = saves[0];
        insertIntoNetwork(emitterNode, DIRT, 5);
        world.tick();
        assertTrue(saves[0] > before, "the output changed and is persisted");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Energy level emitter

    @Test
    void energyLevelEmitterFollowsStoredEnergy() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(5_000);
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        var energyEmitter = new EnergyLevelEmitterLogic();
        energyEmitter.setOutputListener(outputs::add);
        energyEmitter.setReportingValue(10_000);
        var node = partAbove(0, 0, NodeType.ENERGY_LEVEL_EMITTER, energyEmitter);
        world.tick();
        assertTrue(node.isActive());
        assertFalse(energyEmitter.isEmitting(), "about 5k AE < 10k");

        cell.setAECurrentPower(20_000);
        world.tick();
        assertTrue(energyEmitter.isEmitting(), "the stored energy passed the threshold");

        energyEmitter.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertFalse(energyEmitter.isEmitting());
        cell.setAECurrentPower(9_000);
        world.tick();
        assertTrue(energyEmitter.isEmitting(), "below the threshold, inverted");

        // a new threshold is watched at once
        energyEmitter.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        energyEmitter.setReportingValue(8_000);
        assertTrue(energyEmitter.isEmitting(), "9k >= 8k");
        cell.setAECurrentPower(7_000);
        world.tick();
        assertFalse(energyEmitter.isEmitting(), "passed the new threshold");
        assertEquals(List.of(true, false, true, false, true, false), outputs);

        var copy = new EnergyLevelEmitterLogic();
        copy.loadState(energyEmitter.saveState());
        assertEquals(8_000, copy.getReportingValue());
        assertEquals(RedstoneMode.HIGH_SIGNAL, copy.getRedstoneMode());
    }

    @Test
    void energyLevelEmitterSeesTheIdleDrain() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(1_000);
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        var energyEmitter = new EnergyLevelEmitterLogic();
        var node = partAbove(0, 0, NodeType.ENERGY_LEVEL_EMITTER, energyEmitter);
        world.tick();
        double stored = node.getGrid().getEnergyService().getStoredPower();
        energyEmitter.setReportingValue((long) stored - 20);
        assertTrue(energyEmitter.isEmitting());
        // the emitter itself draws 1 AE/t: after some ticks the level falls below the threshold
        int ticks = 0;
        while (energyEmitter.isEmitting() && ticks < 100) {
            world.tick();
            ticks++;
        }
        assertFalse(energyEmitter.isEmitting());
        assertTrue(ticks >= 19 && ticks <= 23, "about 20 ticks at 1 AE/t, was " + ticks);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Emitter -> bus with redstone card

    @Test
    void importBusWithRedstoneCardFollowsTheEmitter() {
        setUpEmitter();
        var bus = new ImportBusLogic();
        var busNode = partAbove(2, 0, NodeType.IMPORT_BUS, bus);
        var chest = chest(9);
        chest.set(0, de.fellurion.ae2.core.inv.SimpleStack.of("Soil_Dirt", 64));
        bus.setTarget(ExternalStorageFacade.of(chest));
        bus.getUpgrades().insert(UpgradeCard.REDSTONE);
        bus.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        // the integration forwards the emitter's output to the bus next to it
        emitter.setOutputListener(bus::setRedstonePowered);
        world.tick(40);
        assertTrue(busNode.isActive());
        assertEquals(64, chest.get(0).count(), "no signal: the bus sleeps");

        insertIntoNetwork(emitterNode, STONE, 100);
        world.tick(40);
        assertTrue(emitter.isEmitting());
        assertTrue(chest.get(0) == null || chest.get(0).count() < 64, "signal: the bus works");
        long moved = networkAmount(busNode, DIRT);
        assertTrue(moved > 0);

        extractFromNetwork(emitterNode, STONE, 1);
        world.tick(2);
        assertFalse(emitter.isEmitting());
        long afterStop = networkAmount(busNode, DIRT);
        world.tick(40);
        assertEquals(afterStop, networkAmount(busNode, DIRT), "signal gone: the bus sleeps again");
    }
}
