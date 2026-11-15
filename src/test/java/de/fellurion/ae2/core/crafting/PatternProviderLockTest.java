/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.LockCraftingMode;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.INGOT;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.ORE;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.SMELT;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.STICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pattern provider settings: the lock crafting modes of {@code appeng.helpers.patternprovider.PatternProviderLogic}
 * (with a stand-in for the redstone signal), visibility in pattern access terminals, and their persistence. Also the
 * upgrade cards of the molecular assembler surviving a save.
 */
class PatternProviderLockTest {
    private CraftingTestNetwork net;
    private PatternProviderLogic provider;
    private boolean signal;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        net = new CraftingTestNetwork(List.of(SMELT));
        net.machine.process = false; // the machine keeps the ore; results are returned by hand
        provider = net.provider;
        provider.setTargetSupplier(() -> List.of(net.machine));
        provider.setRedstoneSupplier(() -> signal);
    }

    private boolean push() {
        var inputs = new KeyCounter[] { new KeyCounter() };
        inputs[0].add(ORE, 1);
        return provider.pushPattern(SMELT, inputs);
    }

    private void setSignal(boolean on) {
        signal = on;
        provider.updateRedstoneState();
    }

    @Test
    void neverLockedByDefault() {
        assertEquals(LockCraftingMode.NONE, provider.getLockCraftingMode());
        assertTrue(push());
        assertTrue(push());
        assertFalse(provider.isCraftingLocked());
        assertTrue(provider.isShownInAccessTerminal());
    }

    @Test
    void lockUntilResultWaitsForThePrimaryOutput() {
        provider.setLockCraftingMode(LockCraftingMode.LOCK_UNTIL_RESULT);
        assertTrue(push());
        assertEquals(LockCraftingMode.LOCK_UNTIL_RESULT, provider.getCraftingLockedReason());
        assertEquals(new GenericStack(INGOT, 1), provider.getUnlockStack());
        assertFalse(push(), "locked until the ingot came back");

        // something else coming back does not unlock
        provider.getReturnInventory().insert(ORE, 1, Actionable.MODULATE);
        net.world.tick(20);
        assertTrue(provider.isCraftingLocked());
        provider.getReturnInventory().insert(INGOT, 1, Actionable.MODULATE);
        net.world.tick(20);
        assertFalse(provider.isCraftingLocked());
        assertNull(provider.getUnlockStack());
        assertEquals(1, net.stored(INGOT));
        assertTrue(push());

        // switching the mode releases a pending lock
        assertTrue(provider.isCraftingLocked());
        provider.setLockCraftingMode(LockCraftingMode.NONE);
        assertFalse(provider.isCraftingLocked());
        assertTrue(push());
    }

    @Test
    void lockWhileHighAndWhileLowFollowTheSignal() {
        provider.setLockCraftingMode(LockCraftingMode.LOCK_WHILE_HIGH);
        assertTrue(push(), "no signal: not locked");
        setSignal(true);
        assertEquals(LockCraftingMode.LOCK_WHILE_HIGH, provider.getCraftingLockedReason());
        assertFalse(push());
        setSignal(false);
        assertTrue(push());

        provider.setLockCraftingMode(LockCraftingMode.LOCK_WHILE_LOW);
        assertEquals(LockCraftingMode.LOCK_WHILE_LOW, provider.getCraftingLockedReason());
        assertFalse(push());
        setSignal(true);
        assertTrue(push());
        assertTrue(push(), "stays unlocked while the signal is on");
    }

    @Test
    void lockUntilPulseNeedsARisingSignalAfterEveryPush() {
        provider.setLockCraftingMode(LockCraftingMode.LOCK_UNTIL_PULSE);
        assertTrue(push());
        assertEquals(LockCraftingMode.LOCK_UNTIL_PULSE, provider.getCraftingLockedReason());
        assertFalse(push());
        setSignal(true);
        assertFalse(provider.isCraftingLocked(), "signal arrived");

        // pushed while the signal is still on: it has to go off and on again
        assertTrue(push());
        assertTrue(provider.isCraftingLocked());
        provider.updateRedstoneState();
        assertTrue(provider.isCraftingLocked(), "still the same signal");
        setSignal(false);
        assertTrue(provider.isCraftingLocked());
        setSignal(true);
        assertFalse(provider.isCraftingLocked());
        assertTrue(push());
    }

    @Test
    void withoutASignalSourceOnlyTheResultModeAndWhileLowLock() {
        var plain = new CraftingTestNetwork(List.of(SMELT)).provider;
        plain.setLockCraftingMode(LockCraftingMode.LOCK_WHILE_HIGH);
        assertFalse(plain.isCraftingLocked());
        plain.setLockCraftingMode(LockCraftingMode.LOCK_WHILE_LOW);
        assertTrue(plain.isCraftingLocked());
    }

    @Test
    void settingsAndPendingLockSurviveSaveAndLoad() {
        provider.setBlocking(true);
        provider.setShownInAccessTerminal(false);
        provider.setLockCraftingMode(LockCraftingMode.LOCK_UNTIL_RESULT);
        assertTrue(push());

        var copy = new PatternProviderLogic();
        copy.fromJson(provider.toJson());
        assertTrue(copy.isBlocking());
        assertFalse(copy.isShownInAccessTerminal());
        assertEquals(LockCraftingMode.LOCK_UNTIL_RESULT, copy.getLockCraftingMode());
        assertEquals(LockCraftingMode.LOCK_UNTIL_RESULT, copy.getCraftingLockedReason());
        assertEquals(new GenericStack(INGOT, 1), copy.getUnlockStack());

        provider.setLockCraftingMode(LockCraftingMode.LOCK_UNTIL_PULSE);
        assertTrue(push());
        copy.fromJson(provider.toJson());
        assertEquals(LockCraftingMode.LOCK_UNTIL_PULSE, copy.getCraftingLockedReason());
        assertNull(copy.getUnlockStack());

        var fresh = new PatternProviderLogic();
        fresh.fromJson(new PatternProviderLogic().toJson());
        assertTrue(fresh.isShownInAccessTerminal());
        assertEquals(LockCraftingMode.NONE, fresh.getLockCraftingMode());
        assertFalse(fresh.isCraftingLocked());

        // format written before all lock modes existed
        var old = new PatternProviderLogic();
        old.fromJson("{\"priority\":3,\"lockResult\":true,\"unlock\":" + unlockJson(provider) + "}");
        assertEquals(3, old.getPriority());
        assertEquals(LockCraftingMode.LOCK_UNTIL_RESULT, old.getLockCraftingMode());
        assertTrue(old.isCraftingLocked());
    }

    /** The "unlock" entry as the provider writes it while waiting for an ingot. */
    private String unlockJson(PatternProviderLogic p) {
        p.setLockCraftingMode(LockCraftingMode.LOCK_UNTIL_RESULT);
        assertTrue(push());
        String json = p.toJson();
        int at = json.indexOf("\"unlock\":") + "\"unlock\":".length();
        int depth = 0;
        for (int i = at; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return json.substring(at, i + 1);
            }
        }
        throw new IllegalStateException(json);
    }

    @Test
    void molecularAssemblerKeepsItsCardsAcrossSaveAndLoad() {
        var crafting = new CraftingTestNetwork(List.of(STICKS));
        assertTrue(crafting.assembler.getUpgrades().insert(UpgradeCard.SPEED));
        assertTrue(crafting.assembler.getUpgrades().insert(UpgradeCard.SPEED));
        var copy = new MolecularAssemblerLogic();
        copy.loadState(crafting.assembler.saveState());
        assertEquals(2, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.SPEED));
        // a state saved before cards existed leaves the cards alone
        copy.loadState("{\"pending\":[]}");
        assertEquals(2, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.SPEED));
    }
}
