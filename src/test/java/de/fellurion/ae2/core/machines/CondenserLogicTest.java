/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.cells.StorageCellTier;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.CondenserOutput;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CondenserLogicTest {
    private SimpleSlotAccess inv;
    private CondenserLogic condenser;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        inv = new SimpleSlotAccess(3);
        condenser = new CondenserLogic(inv);
    }

    private void component(StorageCellTier tier) {
        inv.set(CondenserLogic.SLOT_COMPONENT, SimpleStack.of(tier.componentItemId(), 1));
    }

    @Test
    void storageIsComponentBytesTimesEight() {
        assertEquals(0, condenser.getStorage());
        component(StorageCellTier.SIZE_1K);
        assertEquals(8192, condenser.getStorage());
        component(StorageCellTier.SIZE_256K);
        assertEquals(262144 * 8, condenser.getStorage());
    }

    @Test
    void matterBallsCost256Items() {
        component(StorageCellTier.SIZE_1K);
        condenser.setMode(CondenserOutput.MATTER_BALLS);
        assertEquals(256, condenser.getRequiredPower());

        assertEquals(255, condenser.insertItems(SimpleStack.of("Rock_Stone", 255), false));
        assertNull(inv.get(CondenserLogic.SLOT_OUTPUT));
        assertEquals(1, condenser.insertItems(SimpleStack.of("Rock_Stone", 1), false));
        assertEquals(SimpleStack.of(CondenserLogic.MATTER_BALL_ID, 1), inv.get(CondenserLogic.SLOT_OUTPUT));
        assertEquals(0, condenser.getStoredPower(), 1e-9);

        // via the input slot
        inv.set(CondenserLogic.SLOT_INPUT, SimpleStack.of("Soil_Dirt", 64));
        condenser.onInventoryChanged();
        assertNull(inv.get(CondenserLogic.SLOT_INPUT));
        assertEquals(64, condenser.getStoredPower(), 1e-9);

        // fluids: 1 power per 125 mB, max one bucket per operation
        assertEquals(1000, condenser.insertFluid(AEFluidKey.of("Water"), 5000, false));
        assertEquals(72, condenser.getStoredPower(), 1e-9);
    }

    @Test
    void powerIsCappedByComponentAndTrashVoids() {
        // TRASH without component: everything is voided, nothing stored
        assertEquals(100, condenser.insertItems(SimpleStack.of("Rock_Stone", 100), false));
        assertEquals(0, condenser.getStoredPower());

        component(StorageCellTier.SIZE_1K);
        condenser.insertItems(SimpleStack.of("Rock_Stone", 10_000), false);
        assertEquals(8192, condenser.getStoredPower(), 1e-9);

        // Switching to matter balls converts the stored power: 8192 / 256 = 32 balls
        condenser.setMode(CondenserOutput.MATTER_BALLS);
        assertEquals(SimpleStack.of(CondenserLogic.MATTER_BALL_ID, 32), inv.get(CondenserLogic.SLOT_OUTPUT));
        assertEquals(0, condenser.getStoredPower(), 1e-9);
    }

    @Test
    void singularityNeeds64kComponent() {
        condenser.setMode(CondenserOutput.SINGULARITY);
        component(StorageCellTier.SIZE_16K); // 131072 < 256000
        condenser.insertItems(SimpleStack.of("Rock_Stone", 300_000), false);
        assertNull(inv.get(CondenserLogic.SLOT_OUTPUT));
        assertEquals(131072, condenser.getStoredPower(), 1e-9);

        component(StorageCellTier.SIZE_64K);
        condenser.insertItems(SimpleStack.of("Rock_Stone", 124_928), false);
        assertEquals(SimpleStack.of(CondenserLogic.SINGULARITY_ID, 1), inv.get(CondenserLogic.SLOT_OUTPUT));
        assertEquals(0, condenser.getStoredPower(), 1e-9);
    }

    @Test
    void fullOutputBlocksInputAndMeFacadeExtracts() {
        component(StorageCellTier.SIZE_4K);
        condenser.setMode(CondenserOutput.MATTER_BALLS);
        inv.set(CondenserLogic.SLOT_OUTPUT, SimpleStack.of(CondenserLogic.MATTER_BALL_ID, 64));
        assertFalse(condenser.canAddOutput());
        assertEquals(0, condenser.insertItems(SimpleStack.of("Rock_Stone", 10), false));
        var me = condenser.getMEStorage();
        assertEquals(0, me.insert(AEItemKey.of("Rock_Stone"), 10, Actionable.MODULATE, IActionSource.empty()));

        var ball = AEItemKey.of(CondenserLogic.MATTER_BALL_ID);
        assertEquals(64, me.getAvailableStacks().get(ball));
        assertEquals(10, me.extract(ball, 10, Actionable.SIMULATE, IActionSource.empty()));
        assertEquals(64, inv.get(CondenserLogic.SLOT_OUTPUT).count());
        assertEquals(10, me.extract(ball, 10, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(54, inv.get(CondenserLogic.SLOT_OUTPUT).count());
        // ME insert of 1000 mB water = 1000 / 125 = 8 power
        assertEquals(1000, me.insert(AEFluidKey.of("Water"), 1000, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(8, condenser.getStoredPower(), 1e-9);

        var copy = new CondenserLogic(new SimpleSlotAccess(3));
        copy.loadState(condenser.saveState());
        assertEquals(CondenserOutput.MATTER_BALLS, copy.getMode());
        assertEquals(8, copy.getStoredPower(), 1e-9);
    }
}
