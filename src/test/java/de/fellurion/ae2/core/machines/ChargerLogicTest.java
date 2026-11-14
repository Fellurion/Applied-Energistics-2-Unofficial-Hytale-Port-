/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChargerLogicTest {
    private static final String CHARGER_JSON = """
            [{"id": "charged_certus",
              "input": {"ItemId": "AE2_Certus_Quartz_Crystal", "Quantity": 1},
              "output": {"ItemId": "AE2_Charged_Certus_Quartz_Crystal", "Quantity": 1}}]
            """;

    /** Test item storing its energy in the metadata string ("e=123.0"). */
    static final class Battery implements IChargeableItem {
        static final String ID = "AE2_Wireless_Terminal";

        @Override
        public boolean isChargeable(SimpleStack stack) {
            return stack.is(ID);
        }

        @Override
        public double getAECurrentPower(SimpleStack stack) {
            return stack.nbt() == null ? 0 : Double.parseDouble(stack.nbt().substring(2));
        }

        @Override
        public double getAEMaxPower(SimpleStack stack) {
            return 20_000;
        }

        @Override
        public double getChargeRate(SimpleStack stack) {
            return 80;
        }

        @Override
        public Charged injectAEPower(SimpleStack stack, double amount) {
            double cur = getAECurrentPower(stack);
            double accepted = Math.min(amount, getAEMaxPower(stack) - cur);
            return new Charged(new SimpleStack(ID, "e=" + (cur + accepted), 1), amount - accepted);
        }
    }

    private MEWorld world;
    private EnergyCellLogic cell;
    private ChargerRecipes recipes;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        cell = EnergyCellLogic.denseEnergyCell();
        cell.setAECurrentPower(1_000_000);
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null, cell);
        recipes = ChargerRecipes.fromJson(CHARGER_JSON);
    }

    private ChargerLogic placeCharger(SimpleSlotAccess inv, long seed) {
        var logic = new ChargerLogic(inv, recipes, new Battery(), new Random(seed));
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.CHARGER, Direction.EAST, null, logic);
        return logic;
    }

    @Test
    void chargesCertusWithTwentyPercentChancePerChargerTick() {
        var inv = new SimpleSlotAccess(1, id -> 1, 1);
        var logic = placeCharger(inv, 42);
        inv.set(0, SimpleStack.of("AE2_Certus_Quartz_Crystal", 1));

        // buffer fills with 800 AE per charger tick from the network
        logic.doWork(10);
        assertEquals(800, logic.getBuffer().getAECurrentPower(), 1e-9);
        assertFalse(logic.isWorking());
        logic.doWork(10);
        assertEquals(1600, logic.getBuffer().getAECurrentPower(), 1e-9);

        int attempts = 0;
        while (inv.get(0).is("AE2_Certus_Quartz_Crystal") && attempts < 1000) {
            logic.doWork(10);
            attempts++;
            assertTrue(logic.isWorking());
        }
        assertEquals(SimpleStack.of("AE2_Charged_Certus_Quartz_Crystal", 1), inv.get(0));
        // conversion consumed the full buffer, then 800 were pulled again in the same step
        assertEquals(800, logic.getBuffer().getAECurrentPower(), 1e-9);
        assertTrue(logic.canExtract());
        assertFalse(logic.isItemValid(SimpleStack.of("Rock_Stone", 1)));
    }

    @Test
    void averageAttemptsMatchTwentyPercentChance() {
        var inv = new SimpleSlotAccess(1, id -> 1, 1);
        var logic = placeCharger(inv, 1234);
        long totalAttempts = 0;
        int conversions = 2000;
        for (int i = 0; i < conversions; i++) {
            inv.set(0, SimpleStack.of("AE2_Certus_Quartz_Crystal", 1));
            logic.getBuffer().setAECurrentPower(1600);
            int attempts = 0;
            while (inv.get(0).is("AE2_Certus_Quartz_Crystal")) {
                logic.getBuffer().setAECurrentPower(1600);
                logic.doWork(10);
                attempts++;
            }
            totalAttempts += attempts;
        }
        double mean = (double) totalAttempts / conversions;
        assertEquals(5.0, mean, 0.35, "geometric distribution with p=0.2 has mean 5");
    }

    @Test
    void chargesPoweredItemsWithChargeRateTimesTicks() {
        var inv = new SimpleSlotAccess(1, id -> 1, 1);
        var logic = placeCharger(inv, 7);
        inv.set(0, new SimpleStack(Battery.ID, "e=0.0", 1));
        double before = cell.getAECurrentPower();

        logic.doWork(10); // 80 AE/t * 10 ticks = 800 AE, buffer empty -> taken from the grid
        assertEquals(800, new Battery().getAECurrentPower(inv.get(0)), 1e-9);
        assertTrue(logic.isWorking());

        int steps = 1;
        while (new Battery().getAECurrentPower(inv.get(0)) < 20_000 && steps < 100) {
            logic.doWork(10);
            steps++;
        }
        assertEquals(25, steps, "20000 AE / 800 AE per charger tick");
        assertTrue(logic.canExtract());
        // energy came from the network (item 20000 + buffer refill)
        assertTrue(before - cell.getAECurrentPower() >= 20_000);
        logic.doWork(10);
        assertFalse(logic.isWorking(), "full item: nothing to do");
    }

    @Test
    void worksWithTickManagerEveryTenTicks() {
        var inv = new SimpleSlotAccess(1, id -> 1, 1);
        placeCharger(inv, 99);
        inv.set(0, SimpleStack.of("AE2_Certus_Quartz_Crystal", 1));
        world.tick(400);
        assertEquals(SimpleStack.of("AE2_Charged_Certus_Quartz_Crystal", 1), inv.get(0));
    }
}
