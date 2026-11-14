/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.FENCE;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.FENCES;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.PLANK;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.STICK;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.STICKS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Crafting calculation on a 2-level recipe (planks → sticks via crafting pattern, sticks + plank → fence via
 * processing pattern) against a real grid with a drive.
 */
class CraftingCalculationTest {
    private CraftingTestNetwork net;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        net = new CraftingTestNetwork(List.of(STICKS, FENCES), CraftingUnitLogic.UnitType.STORAGE_1K);
        net.insert(PLANK, 10);
        net.world.tick();
    }

    @Test
    void patternsAreRegisteredInTheNetwork() {
        var cs = net.crafting();
        assertEquals(List.of(STICKS), List.copyOf(cs.getCraftingFor(STICK)));
        assertEquals(List.of(FENCES), List.copyOf(cs.getCraftingFor(FENCE)));
        assertTrue(cs.isCraftable(FENCE));
        assertFalse(cs.isCraftable(PLANK));
        // processing pattern condenses its sparse inputs: 3 sticks + 1 plank
        assertEquals(2, FENCES.getInputs().length);
        assertEquals(3, FENCES.getInputs()[0].getMultiplier());
        assertEquals(STICK, FENCES.getInputs()[0].getPossibleInputs()[0].what());
        assertEquals(1, FENCES.getInputs()[1].getMultiplier());
        assertFalse(STICKS.supportsPushInputsToExternalInventory());
    }

    @Test
    void twoLevelPlan() {
        var plan = net.crafting().calculate(net.src, FENCE, 4, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(plan.simulation());
        assertEquals(new GenericStack(FENCE, 4), plan.finalOutput());
        // 12 sticks = 3 crafts (3 planks) + 4 planks for the fences
        assertEquals(7, plan.usedItems().get(PLANK));
        assertEquals(0, plan.usedItems().get(STICK));
        assertEquals(3L, plan.patternTimes().get(STICKS));
        assertEquals(4L, plan.patternTimes().get(FENCES));
        assertTrue(plan.missingItems().isEmpty());
        assertFalse(plan.multiplePaths());
        // AE2 byte formula: per node request amount*multiplier/amountPerByte*8 (= item count for items)
        // fence 4 + sticks 12 + planks for sticks 3 + crafts(sticks) 3 + planks for fences 4 + crafts(fences) 4
        // + 4 tree nodes * 8
        assertEquals(4 + 12 + 3 + 3 + 4 + 4 + 4 * 8, plan.bytes());
    }

    @Test
    void missingItemsInSimulation() {
        var plan = net.crafting().calculate(net.src, FENCE, 10, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(plan.simulation());
        // 30 sticks need 8 crafts = 8 planks, plus 10 planks for the fences: 18 needed, 10 available
        assertEquals(8, plan.missingItems().get(PLANK));
        assertEquals(1, plan.missingItems().size());
        assertEquals(10, plan.finalOutput().amount());
        // A simulated plan cannot be submitted
        var result = net.crafting().submitJob(plan, null, null, true, net.src);
        assertEquals(CraftingSubmitErrorCode.INCOMPLETE_PLAN, result.errorCode());
    }

    @Test
    void craftLessFindsMaximumAmount() {
        // n fences need n + ceil(3n/4) planks: 5 → 9 planks, 6 → 11 planks
        var plan = net.crafting().calculate(net.src, FENCE, 10, CalculationStrategy.CRAFT_LESS);
        assertFalse(plan.simulation());
        assertEquals(5, plan.finalOutput().amount());
        assertEquals(9, plan.usedItems().get(PLANK));
    }

    @Test
    void usesStoredIntermediatesFirst() {
        net.insert(STICK, 6);
        var plan = net.crafting().calculate(net.src, FENCE, 4, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(plan.simulation());
        assertEquals(6, plan.usedItems().get(STICK));
        // 6 more sticks → 2 crafts
        assertEquals(2L, plan.patternTimes().get(STICKS));
        assertEquals(2 + 4, plan.usedItems().get(PLANK));
    }

    @Test
    void requestedItemIsNotTakenFromStorage() {
        net.insert(FENCE, 100);
        var plan = net.crafting().calculate(net.src, FENCE, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(1L, plan.patternTimes().get(FENCES));
        assertEquals(0, plan.usedItems().get(FENCE));
    }

    @Test
    void notCraftableAndNotStoredIsMissing() {
        var unknown = AEItemKey.of("Unobtainium");
        var plan = net.crafting().calculate(net.src, unknown, 3, CalculationStrategy.CRAFT_LESS);
        assertTrue(plan.simulation());
        assertEquals(3, plan.missingItems().get(unknown));
    }

    @Test
    void fluidBytesUseAmountPerByte() {
        var water = AEFluidKey.of("Water");
        var bytes = new double[1];
        ICraftingSimulationState state = new ICraftingSimulationState() {
            @Override
            public void emitItems(de.fellurion.ae2.core.stacks.AEKey what, long amount) {
            }

            @Override
            public void addBytes(double b) {
                bytes[0] += b;
            }

            @Override
            public void addCrafting(IPatternDetails details, long crafts) {
            }

            @Override
            public void insert(de.fellurion.ae2.core.stacks.AEKey what, long amount,
                    de.fellurion.ae2.core.config.Actionable mode) {
            }

            @Override
            public long extract(de.fellurion.ae2.core.stacks.AEKey what, long amount,
                    de.fellurion.ae2.core.config.Actionable mode) {
                return 0;
            }

            @Override
            public Iterable<de.fellurion.ae2.core.stacks.AEKey> findFuzzyTemplates(
                    de.fellurion.ae2.core.stacks.AEKey input) {
                return List.of();
            }
        };
        // crafting storage counts 8x: 8000 mB = 1 byte -> 8, 8 items = 1 byte -> 8
        state.addStackBytes(water, 8000, 1);
        state.addStackBytes(STICK, 8, 1);
        assertEquals(16.0, bytes[0], 1e-9);
    }

    @Test
    void patternEncodingRoundTrip() {
        var decoded = PatternDetailsHelper.decodePattern(FENCES.getDefinition());
        assertEquals(FENCES, decoded);
        assertEquals(FENCES.getSparseInputs(), ((ProcessingPattern) decoded).getSparseInputs());
        var craft = PatternDetailsHelper.decodePattern(STICKS.getDefinition());
        assertInstanceOf(CraftingPattern.class, craft);
        assertEquals(new GenericStack(STICK, 4), craft.getPrimaryOutput());
        assertNull(PatternDetailsHelper.decodePattern(AEItemKey.of("Rock_Stone")));
        assertNull(PatternDetailsHelper.decodePattern(new AEItemKey(ProcessingPattern.ITEM_ID, "{broken")));
    }

    @Test
    void craftingPatternSubstitutes() {
        var oak = AEItemKey.of("Plank_Oak");
        var birch = AEItemKey.of("Plank_Birch");
        var def = CraftingPattern.encode(Arrays.asList(oak, oak), new GenericStack(STICK, 4), true,
                List.of(List.of(birch), List.of(birch)));
        var pattern = new CraftingPattern(def);
        assertEquals(1, pattern.getInputs().length);
        var input = pattern.getInputs()[0];
        assertEquals(2, input.getMultiplier());
        assertTrue(input.isValid(oak));
        assertTrue(input.isValid(birch));
        assertFalse(input.isValid(PLANK));
        assertEquals(2, input.getPossibleInputs().length);
    }
}
