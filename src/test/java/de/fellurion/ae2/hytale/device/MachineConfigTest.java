/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.LockCraftingMode;
import de.fellurion.ae2.core.crafting.MolecularAssemblerLogic;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.ConfigHost.Context;
import de.fellurion.ae2.hytale.ui.ConfigHost.Setting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was Pattern Provider, Molecular Assembler und die Maschinen der Konfigurationsseite anbieten ({@link ConfigHost}),
 * soweit es ohne Welt prüfbar ist: Einstellungen in der Reihenfolge von AE2, was ein Klick weiterschaltet, und wie
 * die Karten des Assemblers in der Block-Komponente stehen.
 */
class MachineConfigTest {

    /** Ohne Server gibt es keine Übersetzung: Der Schlüssel steht für den Text. */
    private static final Context CTX = new Context(null, null, "en-US") {
        @Override
        public String tr(String key, Object... params) {
            return key;
        }
    };

    private static List<String> texts(ConfigHost host) {
        List<String> out = new ArrayList<>();
        for (Setting s : host.settings(CTX)) {
            out.add(s.text());
        }
        return out;
    }

    @Test
    void patternProviderHatBlockierenSperreAnzeigeUndPrioritaet() {
        CraftingDevices.PatternProvider provider = new CraftingDevices.PatternProvider();
        assertTrue(provider.opensOnCrouch(), "Benutzen öffnet die Pattern-Plätze");
        assertNull(provider.ghostSlots(CTX));
        assertNull(provider.upgradeCards(), "AE2: der Pattern Provider nimmt keine Karten");
        assertTrue(provider.hasPriority());
        assertNotNull(provider.watchers());
        assertEquals(List.of("ae2bus.setting.blocking.no", "ae2bus.setting.lock.none",
                "ae2bus.setting.accessTerminal.yes"), texts(provider));
        assertNull(provider.note(CTX));

        provider.settings(CTX).get(0).next().run();
        assertTrue(provider.logic().isBlocking());
        provider.settings(CTX).get(2).next().run();
        assertFalse(provider.logic().isShownInAccessTerminal());

        // die Sperre schaltet durch alle fünf Modi von AE2 und wieder auf „nie“
        List<LockCraftingMode> seen = new ArrayList<>();
        for (int i = 0; i < LockCraftingMode.values().length; i++) {
            provider.settings(CTX).get(1).next().run();
            seen.add(provider.logic().getLockCraftingMode());
            boolean needsSignal = provider.logic().getLockCraftingMode() != LockCraftingMode.NONE
                    && provider.logic().getLockCraftingMode() != LockCraftingMode.LOCK_UNTIL_RESULT;
            assertEquals(needsSignal ? "ae2bus.hint.lockSignal" : null, provider.note(CTX),
                    String.valueOf(provider.logic().getLockCraftingMode()));
        }
        assertEquals(List.of(LockCraftingMode.LOCK_UNTIL_PULSE, LockCraftingMode.LOCK_WHILE_HIGH,
                LockCraftingMode.LOCK_WHILE_LOW, LockCraftingMode.LOCK_UNTIL_RESULT, LockCraftingMode.NONE), seen);
        assertEquals(List.of("ae2bus.setting.blocking.yes", "ae2bus.setting.lock.none",
                "ae2bus.setting.accessTerminal.no"), texts(provider));
    }

    @Test
    void assemblerNimmtBeschleunigungskartenUndSchreibtSieJePlatz() {
        CraftingDevices.MolecularAssembler assembler = new CraftingDevices.MolecularAssembler();
        assertTrue(assembler.opensOnCrouch());
        assertEquals(assembler.logic().getUpgrades(), assembler.upgradeCards());
        assertTrue(assembler.settings(CTX).isEmpty());
        assertFalse(assembler.hasPriority());
        assertEquals("", CraftingDevices.MolecularAssembler.cards(assembler.logic().getUpgrades()), "ohne Karten kein Eintrag");

        assembler.logic().getUpgrades().set(0, UpgradeCard.SPEED);
        assembler.logic().getUpgrades().set(3, UpgradeCard.SPEED);
        String saved = CraftingDevices.MolecularAssembler.cards(assembler.logic().getUpgrades());
        assertEquals("AE2_Speed_Card,,,AE2_Speed_Card,", saved);

        // so liest onAttached sie wieder ein: jede Karte an ihrem Platz
        MolecularAssemblerLogic copy = new MolecularAssemblerLogic();
        copy.getUpgrades().fromItemIds(Arrays.asList(saved.split(",", -1)));
        assertEquals(UpgradeCard.SPEED, copy.getUpgrades().get(0));
        assertNull(copy.getUpgrades().get(1));
        assertEquals(UpgradeCard.SPEED, copy.getUpgrades().get(3));
        assertEquals(2, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.SPEED));
    }

    @Test
    void eingabeplaetzeDesInscribersSchaltenWieInAE2() {
        assertEquals(1, MachineDevice.nextCapacity(64));
        assertEquals(4, MachineDevice.nextCapacity(1));
        assertEquals(64, MachineDevice.nextCapacity(4));
        assertEquals(64, MachineDevice.nextCapacity(7), "unbekannter Wert: zurück auf den Standard");
    }

    @Test
    void maschineOhneContainerBietetNichtsAn() {
        // ohne prepare() (kein Container gefunden) hat die Maschine keine Logik: nichts zu konfigurieren
        for (String kind : List.of("inscriber", "charger", "vibration_chamber", "condenser")) {
            MachineDevice machine = new MachineDevice(kind);
            assertFalse(machine.opensOnCrouch(), kind);
            assertNull(machine.upgradeCards(), kind);
            assertTrue(machine.settings(CTX).isEmpty(), kind);
            assertNull(machine.info(CTX), kind);
            assertNull(machine.automationSlots(de.fellurion.ae2.hytale.BlockAccess.Dir.UP), kind);
        }
    }
}
