/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkyStoneTankLogicTest {
    private static final AEFluidKey WATER = AEFluidKey.of("Water_Source");
    private static final AEFluidKey LAVA = AEFluidKey.of("Lava_Source");

    private SkyStoneTankLogic tank;
    private int changes;

    @BeforeEach
    void setUp() {
        tank = new SkyStoneTankLogic();
        changes = 0;
        tank.setChangeListener(() -> changes++);
    }

    @Test
    void holdsSixteenBucketsOfOneFluid() {
        assertEquals(16_000, tank.getCapacity());
        assertTrue(tank.isEmpty());
        assertNull(tank.getFluid());

        assertEquals(1000, tank.insert(WATER, 1000, Actionable.SIMULATE));
        assertTrue(tank.isEmpty(), "simulating changes nothing");
        assertEquals(0, changes);

        assertEquals(1000, tank.insert(WATER, 1000, Actionable.MODULATE));
        assertEquals(WATER, tank.getFluid());
        assertEquals(1000, tank.getAmount());
        assertEquals(1, changes);

        // a second fluid is refused while the tank holds water
        assertEquals(0, tank.insert(LAVA, 1000, Actionable.MODULATE));
        // at most the capacity
        assertEquals(15_000, tank.insert(WATER, 99_000, Actionable.MODULATE));
        assertEquals(16_000, tank.getAmount());
        assertEquals(0, tank.insert(WATER, 1, Actionable.MODULATE));
        assertEquals(1.0, tank.getFillFraction(), 1e-9);
    }

    @Test
    void drainedTankForgetsItsFluid() {
        tank.insert(WATER, 2500, Actionable.MODULATE);
        assertEquals(0, tank.extract(LAVA, 1000, Actionable.MODULATE));
        assertEquals(1000, tank.extract(WATER, 1000, Actionable.SIMULATE));
        assertEquals(2500, tank.getAmount());
        assertEquals(2500, tank.extract(WATER, 9000, Actionable.MODULATE));
        assertTrue(tank.isEmpty());
        assertNull(tank.getFluid());
        // now lava fits
        assertEquals(500, tank.insert(LAVA, 500, Actionable.MODULATE));
        assertEquals(LAVA, tank.getFluid());
    }

    @Test
    void containersAreMovedWholeOrNotAtAll() {
        tank.insert(WATER, 15_500, Actionable.MODULATE);
        assertFalse(tank.fillFromContainer(WATER, 1000), "only 500 mB of room");
        assertEquals(15_500, tank.getAmount());
        assertFalse(tank.fillFromContainer(LAVA, 500));
        assertTrue(tank.fillFromContainer(WATER, 500));
        assertEquals(16_000, tank.getAmount());

        assertEquals(WATER, tank.drainIntoContainer(1000));
        assertEquals(15_000, tank.getAmount());
        tank.extract(WATER, 14_500, Actionable.MODULATE);
        assertNull(tank.drainIntoContainer(1000), "500 mB are not a full bucket");
        assertEquals(500, tank.getAmount());

        var empty = new SkyStoneTankLogic();
        assertNull(empty.drainIntoContainer(1000));
        assertTrue(empty.fillFromContainer(LAVA, 1000));
    }

    @Test
    void busesSeeOneSlotOfFluidStorage() {
        var storage = tank.getStorage();
        assertSame(storage, tank.getStorage(), "stable object so a bus recognises its target");
        var src = IActionSource.empty();
        assertEquals(1, storage.getSlots());
        assertNull(storage.getStackInSlot(0));
        assertTrue(storage.getAvailableStacks().isEmpty());

        // items are not stored
        assertEquals(0, storage.insert(AEItemKey.of("Rock_Stone"), 5, Actionable.MODULATE, src));
        assertEquals(125, storage.insert(WATER, 125, Actionable.MODULATE, src));
        assertEquals(new GenericStack(WATER, 125), storage.getStackInSlot(0));
        assertNull(storage.getStackInSlot(1));
        assertEquals(125, storage.getAvailableStacks().get(WATER));
        assertEquals(0, storage.extract(LAVA, 125, Actionable.MODULATE, src));
        assertEquals(100, storage.extract(WATER, 100, Actionable.MODULATE, src));
        assertEquals(25, tank.getAmount());
    }

    @Test
    void stateRoundTripsThroughJson() {
        assertEquals("{}", tank.saveState());
        tank.insert(LAVA, 3125, Actionable.MODULATE);
        var copy = new SkyStoneTankLogic();
        copy.loadState(tank.saveState());
        assertEquals(LAVA, copy.getFluid());
        assertEquals(3125, copy.getAmount());

        copy.loadState(null);
        assertTrue(copy.isEmpty());
        copy.loadState("{\"fluid\":\"Water_Source\",\"amount\":999999}");
        assertEquals(16_000, copy.getAmount(), "clamped to the capacity");
        copy.loadState("{\"fluid\":\"Water_Source\",\"amount\":0}");
        assertNull(copy.getFluid());

        copy.set(WATER, 4000);
        assertEquals(4000, copy.getAmount());
        copy.set(null, 4000);
        assertTrue(copy.isEmpty());
    }
}
