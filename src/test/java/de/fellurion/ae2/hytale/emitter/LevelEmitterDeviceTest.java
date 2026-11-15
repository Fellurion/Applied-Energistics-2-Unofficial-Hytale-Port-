/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.emitter;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.ConfigHost.Context;
import de.fellurion.ae2.hytale.ui.ConfigHost.NumberField;
import de.fellurion.ae2.hytale.ui.ConfigHost.Setting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was die Einstellungsseite an einem Level Emitter zeigt (AE2: StorageLevelEmitterScreen / EnergyLevelEmitterScreen):
 * welche Knöpfe mit welcher Karte sichtbar sind und dass sie die Kernlogik umstellen.
 */
class LevelEmitterDeviceTest {

    /** Ohne Server gibt es keine Übersetzung: Der Schlüssel steht für den Text. */
    private static final Context CTX = new Context(null, null, "en-US") {
        @Override
        public String tr(String key, Object... params) {
            return key;
        }
    };

    private static List<String> texts(ConfigHost host) {
        return host.settings(CTX).stream().map(Setting::text).toList();
    }

    @Test
    void itemEmitterOhneKarte() {
        LevelEmitterDevice device = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        assertNotNull(device.storage());
        assertFalse(device.isEnergy());
        ConfigHost.GhostSlots slots = device.ghostSlots(CTX);
        assertNotNull(slots);
        assertEquals(1, slots.usable());
        assertFalse(slots.amounts());
        assertEquals(1, slots.inventory().size());
        assertEquals(1, device.upgradeCards().size(), "AE2: ein Kartenplatz");
        assertEquals(List.of("ae2emitter.setting.level.high_signal"), texts(device));
        NumberField number = device.numberField(CTX);
        assertNotNull(number);
        assertEquals(0, number.value());
        assertEquals(0, number.min());
        assertFalse(device.hasPriority());
        assertNotNull(device.watchers());
        assertEquals("ae2emitter.note.all", device.note(CTX), "ohne Item zählt alles");

        // Knöpfe und Zahlenfeld stellen die Kernlogik um
        device.settings(CTX).getFirst().next().run();
        assertEquals(RedstoneMode.LOW_SIGNAL, device.logic().getRedstoneMode());
        assertEquals(List.of("ae2emitter.setting.level.low_signal"), texts(device));
        device.settings(CTX).getFirst().next().run();
        assertEquals(RedstoneMode.HIGH_SIGNAL, device.logic().getRedstoneMode(), "nur die zwei Modi der Emitter");
        number.setter().accept(250);
        assertEquals(250, device.logic().getReportingValue());
        assertEquals(250, device.numberField(CTX).value());

        slots.inventory().setKey(0, AEItemKey.of("Rock_Stone"));
        assertEquals(AEItemKey.of("Rock_Stone"), device.storage().getConfiguredKey());
        assertEquals("ae2emitter.note.item", device.note(CTX));
    }

    @Test
    void fuzzyKarteZeigtDenFuzzyModus() {
        LevelEmitterDevice device = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        assertTrue(device.upgradeCards().insert(UpgradeCard.FUZZY));
        assertEquals(List.of("ae2emitter.setting.level.high_signal", "ae2ui.workbench.fuzzy.ignore_all"),
                texts(device));
        device.settings(CTX).get(1).next().run();
        assertEquals(FuzzyMode.PERCENT_99, device.storage().getFuzzyMode());
        assertNotNull(device.numberField(CTX));
        assertFalse(device.upgradeCards().canInsert(UpgradeCard.CRAFTING), "nur ein Kartenplatz");
    }

    @Test
    void craftingKarteErsetztSchwelleUndSignalmodus() {
        LevelEmitterDevice device = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        assertTrue(device.upgradeCards().insert(UpgradeCard.CRAFTING));
        assertEquals(List.of("ae2emitter.setting.craft.no"), texts(device));
        assertNull(device.numberField(CTX), "AE2: Schwelle mit Crafting-Karte nicht einstellbar");
        assertEquals("ae2emitter.note.whileCrafting", device.note(CTX));
        device.settings(CTX).getFirst().next().run();
        assertTrue(device.storage().isCraftViaRedstone());
        assertEquals(List.of("ae2emitter.setting.craft.yes"), texts(device));
        assertEquals("ae2emitter.note.craftVia", device.note(CTX));
        // Karte heraus: wieder Schwelle und Signalmodus
        device.upgradeCards().remove(0);
        assertEquals(List.of("ae2emitter.setting.level.high_signal"), texts(device));
        assertNotNull(device.numberField(CTX));
    }

    @Test
    void energieEmitterHatNurSchwelleUndSignalmodus() {
        LevelEmitterDevice device = new LevelEmitterDevice(null, 0, 0, 0, null, true);
        assertTrue(device.isEnergy());
        assertNull(device.storage());
        assertNull(device.ghostSlots(CTX));
        assertNull(device.upgradeCards(), "AE2: keine Kartenplätze");
        assertEquals(List.of("ae2emitter.setting.energy.high_signal"), texts(device));
        NumberField number = device.numberField(CTX);
        assertNotNull(number);
        assertEquals("ae2emitter.threshold.energy", number.labelKey());
        assertEquals(3, number.steps().length);
        assertEquals("ae2emitter.note.energy", device.note(CTX));
        assertEquals("ae2bus.info.offline", device.info(CTX), "ohne Netz offline");
    }

    @Test
    void gespeicherterZustandWirdGeladen() {
        LevelEmitterDevice first = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        first.storage().getConfig().setKey(0, AEItemKey.of("Rock_Stone"));
        first.logic().setReportingValue(100);
        first.logic().setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        first.upgradeCards().insert(UpgradeCard.FUZZY);
        String data = first.logic().saveState();

        LevelEmitterDevice loaded = new LevelEmitterDevice(null, 0, 0, 0, data, false);
        assertEquals(AEItemKey.of("Rock_Stone"), loaded.storage().getConfiguredKey());
        assertEquals(100, loaded.logic().getReportingValue());
        assertEquals(RedstoneMode.LOW_SIGNAL, loaded.logic().getRedstoneMode());
        assertTrue(loaded.storage().isUpgradedWith(UpgradeCard.FUZZY));

        // kaputte Daten: Standardwerte statt Absturz
        LevelEmitterDevice broken = new LevelEmitterDevice(null, 0, 0, 0, "{nicht json", false);
        assertEquals(0, broken.logic().getReportingValue());
        assertNull(broken.storage().getConfiguredKey());
    }
}
