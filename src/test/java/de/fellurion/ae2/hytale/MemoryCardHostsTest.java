/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.CondenserOutput;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.LockCraftingMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.config.FullnessMode;
import de.fellurion.ae2.core.config.OperationMode;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.core.tools.MemoryCardSettings.CardStock;
import de.fellurion.ae2.core.tools.MemoryCardSettings.Part;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.device.CraftingDevices;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.device.MachineDevice;
import de.fellurion.ae2.hytale.emitter.LevelEmitterDevice;
import de.fellurion.ae2.hytale.ioport.IOPortDevice;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.plane.FormationPlaneDevice;
import de.fellurion.ae2.hytale.tools.MemoryCards;
import de.fellurion.ae2.hytale.tools.MemoryCards.Outcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.fellurion.ae2.hytale.MemoryCardNodesTest.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory Card an den übrigen einstellbaren Geräten: Pattern Provider, IO-Port, Level Emitter, Inscriber, Condenser,
 * Vibration Chamber, Formation Plane. Geprüft wird je Gerät, was auf die Karte kommt (AE2s Namen), dass ein gleiches Gerät alles
 * übernimmt, was ein anderes Gerät davon versteht, und dass Fremdes und Ungültiges nichts verstellt.
 */
class MemoryCardHostsTest {

    private static final String LANG = "en-US";

    @BeforeAll
    static void recipes() {
        Devices.init(); // Rezepte des Inscribers (aus ae2data)
    }

    /** Karten eines Spielers. */
    private static final class Stock implements CardStock {
        final Map<UpgradeCard, Integer> cards = new EnumMap<>(UpgradeCard.class);

        Stock with(UpgradeCard card, int n) {
            cards.put(card, n);
            return this;
        }

        int of(UpgradeCard card) {
            return cards.getOrDefault(card, 0);
        }

        @Override
        public int take(UpgradeCard card, int amount) {
            int n = Math.min(amount, of(card));
            cards.put(card, of(card) - n);
            return n;
        }

        @Override
        public void give(UpgradeCard card, int amount) {
            cards.merge(card, amount, Integer::sum);
        }
    }

    private static MemoryCardSettings stored(MemoryCardData card) {
        return MemoryCardSettings.fromJson(card.payload());
    }

    private static Map<String, String> map(String... keysAndValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ Pattern Provider

    @Test
    void patternProviderKopiertEinstellungenUndPrioritaet() {
        CraftingDevices.PatternProvider from = new CraftingDevices.PatternProvider();
        WorldNetworks.Node source = node("pattern_provider", from);
        from.logic().setBlocking(true);
        from.logic().setLockCraftingMode(LockCraftingMode.LOCK_UNTIL_RESULT);
        from.logic().setShownInAccessTerminal(false);
        from.setPriority(source, 5);

        MemoryCardData card = MemoryCards.save(null, source, LANG);
        assertEquals("pattern_provider", card.type());
        MemoryCardSettings s = stored(card);
        assertEquals(map("blocking_mode", "YES", "pattern_access_terminal", "NO", "lock_crafting_mode",
                "LOCK_UNTIL_RESULT"), s.settings());
        assertEquals(5, s.priority());
        assertNull(s.upgrades(), "AE2: der Pattern Provider nimmt keine Karten");
        assertNull(s.config(), "kein Filter");

        CraftingDevices.PatternProvider to = new CraftingDevices.PatternProvider();
        WorldNetworks.Node target = node("pattern_provider", to);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, card, new Stock(), LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS, Part.PRIORITY), loaded.parts());
        assertTrue(to.logic().isBlocking());
        assertEquals(LockCraftingMode.LOCK_UNTIL_RESULT, to.logic().getLockCraftingMode());
        assertFalse(to.logic().isShownInAccessTerminal());
        assertEquals(5, to.priority());
        assertEquals(5, target.comp.priority(), "die Priorität steht auch in der Block-Komponente");
        assertEquals(from.exportSettings(), to.exportSettings());
    }

    @Test
    void patternProviderUebergehtFremdesUndUngueltiges() {
        CraftingDevices.PatternProvider provider = new CraftingDevices.PatternProvider();
        assertFalse(provider.importSettings(Map.of()));
        assertFalse(provider.importSettings(null));
        assertFalse(provider.importSettings(Map.of("fuzzy_mode", "PERCENT_25", "redstone_controlled", "LOW_SIGNAL")));
        assertFalse(provider.importSettings(Map.of("blocking_mode", "maybe", "lock_crafting_mode", "LOCK_FOREVER")));
        assertFalse(provider.logic().isBlocking());
        assertEquals(LockCraftingMode.NONE, provider.logic().getLockCraftingMode());
        assertTrue(provider.importSettings(Map.of("lock_crafting_mode", "LOCK_WHILE_HIGH", "unknown", "x")));
        assertEquals(LockCraftingMode.LOCK_WHILE_HIGH, provider.logic().getLockCraftingMode());
        assertTrue(provider.logic().isShownInAccessTerminal(), "nicht Genanntes bleibt");

        // Karte eines Export-Bus: nichts, was ein Pattern Provider versteht
        MemoryCardData busCard = MemoryCards.save(null, node("export_bus", new BusDevice("export_bus")), LANG);
        assertEquals(Outcome.INVALID,
                MemoryCards.load(null, node("pattern_provider", provider), busCard, null, LANG).outcome());
        // Karte eines Storage-Bus: nur die Priorität
        BusDevice storage = new BusDevice("storage_bus");
        WorldNetworks.Node storageNode = node("storage_bus", storage);
        storage.setPriority(storageNode, 11);
        MemoryCards.Loaded partial = MemoryCards.load(null, node("pattern_provider", provider),
                MemoryCards.save(null, storageNode, LANG), null, LANG);
        assertEquals(Outcome.PARTIAL, partial.outcome());
        assertEquals(EnumSet.of(Part.PRIORITY), partial.parts());
        assertEquals(11, provider.priority());
    }

    // ------------------------------------------------------------------ IO-Port

    @Test
    void ioPortKopiertRichtungAuswurfSignalUndKarten() {
        IOPortDevice from = new IOPortDevice();
        from.logic().setOperationMode(OperationMode.FILL);
        from.logic().setFullnessMode(FullnessMode.FULL);
        from.logic().setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        assertTrue(from.upgradeCards().insert(UpgradeCard.SPEED));
        assertTrue(from.upgradeCards().insert(UpgradeCard.SPEED));
        assertTrue(from.upgradeCards().insert(UpgradeCard.REDSTONE));

        MemoryCardData card = MemoryCards.save(null, node("io_port", from), LANG);
        assertEquals("io_port", card.type());
        MemoryCardSettings s = stored(card);
        assertEquals(map("redstone_controlled", "HIGH_SIGNAL", "fullness_mode", "FULL", "operation_mode", "FILL"),
                s.settings());
        assertEquals(Map.of("AE2_Speed_Card", 2, "AE2_Redstone_Card", 1), s.upgrades());
        assertNull(s.priority());
        assertNull(s.config());

        IOPortDevice to = new IOPortDevice();
        WorldNetworks.Node target = node("io_port", to);
        Stock stock = new Stock().with(UpgradeCard.SPEED, 3);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, card, stock, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.UPGRADES, Part.SETTINGS), loaded.parts());
        assertEquals(Map.of(UpgradeCard.REDSTONE, 1), loaded.missing(), "der Spieler hatte keine Redstone-Karte");
        assertEquals(1, stock.of(UpgradeCard.SPEED));
        assertEquals(OperationMode.FILL, to.logic().getOperationMode());
        assertEquals(FullnessMode.FULL, to.logic().getFullnessMode());
        assertEquals(RedstoneMode.HIGH_SIGNAL, to.logic().getRedstoneMode(), "auch ohne Karte kopiert");
        assertEquals(from.exportSettings(), to.exportSettings());
        assertEquals(to.logic().saveState(), target.comp.data(), "der Port hat seinen Zustand gespeichert");
    }

    @Test
    void ioPortUndBusTeilenSichDenSignalmodus() {
        IOPortDevice port = new IOPortDevice();
        assertFalse(port.importSettings(Map.of()));
        assertFalse(port.importSettings(Map.of("operation_mode", "SIDEWAYS", "fullness_mode", "", "access", "READ")));
        assertEquals(OperationMode.EMPTY, port.logic().getOperationMode());
        assertEquals(Set.of("redstone_controlled", "fullness_mode", "operation_mode"), port.exportSettings().keySet());

        port.logic().setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        port.logic().setOperationMode(OperationMode.FILL);
        BusDevice bus = new BusDevice("import_bus");
        MemoryCards.Loaded partial = MemoryCards.load(null, node("import_bus", bus),
                MemoryCards.save(null, node("io_port", port), LANG), null, LANG);
        assertEquals(Outcome.PARTIAL, partial.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS), partial.parts());
        assertEquals(RedstoneMode.LOW_SIGNAL, bus.ioBus().getRedstoneMode(), "redstone_controlled gilt für beide");

        // und umgekehrt
        bus.ioBus().setRedstoneMode(RedstoneMode.SIGNAL_PULSE);
        IOPortDevice other = new IOPortDevice();
        MemoryCards.Loaded back = MemoryCards.load(null, node("io_port", other),
                MemoryCards.save(null, node("import_bus", bus), LANG), null, LANG);
        assertEquals(Outcome.PARTIAL, back.outcome());
        assertEquals(RedstoneMode.SIGNAL_PULSE, other.logic().getRedstoneMode());
        assertEquals(OperationMode.EMPTY, other.logic().getOperationMode(), "davon stand nichts auf der Karte");
    }

    // ------------------------------------------------------------------ Level Emitter

    @Test
    void levelEmitterKopiertItemSchwelleModiUndKarte() {
        LevelEmitterDevice from = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        from.storage().getConfig().setKey(0, AEItemKey.of("Rock_Stone"));
        from.logic().setReportingValue(250);
        from.logic().setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertTrue(from.upgradeCards().insert(UpgradeCard.FUZZY));
        from.storage().setFuzzyMode(FuzzyMode.PERCENT_75);
        from.storage().setCraftViaRedstone(true);

        MemoryCardData card = MemoryCards.save(null, node("level_emitter", from), LANG);
        assertEquals("level_emitter", card.type());
        MemoryCardSettings s = stored(card);
        assertEquals(map("redstone_emitter", "LOW_SIGNAL", "craft_via_redstone", "YES", "fuzzy_mode", "PERCENT_75",
                "level_emitter_value", "250"), s.settings());
        assertEquals(Map.of("AE2_Fuzzy_Card", 1), s.upgrades());
        assertEquals(1, s.config().size());
        assertNull(s.priority());

        LevelEmitterDevice to = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        to.storage().getConfig().setKey(0, AEItemKey.of("Soil_Dirt"));
        WorldNetworks.Node target = node("level_emitter", to);
        Stock stock = new Stock().with(UpgradeCard.FUZZY, 1);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, card, stock, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.UPGRADES, Part.SETTINGS, Part.CONFIG_INV), loaded.parts());
        assertTrue(loaded.missing().isEmpty());
        assertEquals(0, stock.of(UpgradeCard.FUZZY));
        assertEquals(AEItemKey.of("Rock_Stone"), to.storage().getConfiguredKey());
        assertEquals(250, to.logic().getReportingValue());
        assertEquals(RedstoneMode.LOW_SIGNAL, to.logic().getRedstoneMode());
        assertEquals(FuzzyMode.PERCENT_75, to.storage().getFuzzyMode());
        assertTrue(to.storage().isCraftViaRedstone());
        assertTrue(to.storage().isUpgradedWith(UpgradeCard.FUZZY));
        assertEquals(to.logic().saveState(), target.comp.data(), "der Emitter hat seinen Zustand gespeichert");
        assertEquals(s.settings(), stored(MemoryCards.save(null, target, LANG)).settings());
    }

    @Test
    void energieEmitterUndItemEmitterTeilenSchwelleUndSignalmodus() {
        LevelEmitterDevice energy = new LevelEmitterDevice(null, 0, 0, 0, null, true);
        energy.logic().setReportingValue(120_000);
        energy.logic().setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        MemoryCardData card = MemoryCards.save(null, node("energy_level_emitter", energy), LANG);
        MemoryCardSettings s = stored(card);
        assertEquals(map("redstone_emitter", "LOW_SIGNAL", "level_emitter_value", "120000"), s.settings());
        assertNull(s.upgrades(), "kein Kartenfach");
        assertNull(s.config(), "kein Filter");

        LevelEmitterDevice sameKind = new LevelEmitterDevice(null, 0, 0, 0, null, true);
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("energy_level_emitter", sameKind), card, null, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS), loaded.parts());
        assertEquals(120_000, sameKind.logic().getReportingValue());
        assertEquals(RedstoneMode.LOW_SIGNAL, sameKind.logic().getRedstoneMode());

        LevelEmitterDevice items = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        items.storage().setFuzzyMode(FuzzyMode.PERCENT_25);
        MemoryCards.Loaded partial = MemoryCards.load(null, node("level_emitter", items), card, null, LANG);
        assertEquals(Outcome.PARTIAL, partial.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS), partial.parts());
        assertEquals(120_000, items.logic().getReportingValue());
        assertEquals(RedstoneMode.LOW_SIGNAL, items.logic().getRedstoneMode());
        assertEquals(FuzzyMode.PERCENT_25, items.storage().getFuzzyMode(), "davon stand nichts auf der Karte");
    }

    @Test
    void levelEmitterUebergehtUngueltigeWerte() {
        LevelEmitterDevice device = new LevelEmitterDevice(null, 0, 0, 0, null, false);
        device.logic().setReportingValue(7);
        assertFalse(device.importSettings(null));
        assertFalse(device.importSettings(Map.of("redstone_emitter", "IGNORE")), "Emitter kennen nur an über/unter");
        assertFalse(device.importSettings(Map.of("redstone_emitter", "SIGNAL_PULSE", "redstone_controlled", "LOW_SIGNAL")));
        assertFalse(device.importSettings(Map.of("level_emitter_value", "viel")));
        assertFalse(device.importSettings(Map.of("level_emitter_value", "-5")));
        assertEquals(7, device.logic().getReportingValue());
        assertEquals(RedstoneMode.HIGH_SIGNAL, device.logic().getRedstoneMode());
        assertTrue(device.importSettings(Map.of("level_emitter_value", "0")));
        assertEquals(0, device.logic().getReportingValue());
        // der Fuzzy-Modus eines Bus passt auch hier
        BusDevice bus = new BusDevice("import_bus");
        bus.ioBus().setFuzzyMode(FuzzyMode.PERCENT_99);
        assertTrue(device.importSettings(bus.exportSettings()));
        assertEquals(FuzzyMode.PERCENT_99, device.storage().getFuzzyMode());
        assertEquals(RedstoneMode.HIGH_SIGNAL, device.logic().getRedstoneMode());
    }

    // ------------------------------------------------------------------ Formation Plane

    @Test
    void formationPlaneKopiertSetzenFuzzyFilterPrioritaetUndKarten() {
        FormationPlaneDevice from = new FormationPlaneDevice();
        WorldNetworks.Node source = node("formation_plane", from);
        assertEquals(map("place_block", "YES", "fuzzy_mode", "IGNORE_ALL"), from.exportSettings(), "AE2s Standardwerte");
        from.logic().setPlaceBlock(YesNo.NO);
        assertTrue(from.upgradeCards().insert(UpgradeCard.FUZZY));
        from.logic().setFuzzyMode(FuzzyMode.PERCENT_25);
        from.logic().getConfig().setKey(2, AEItemKey.of("Rock_Stone"));
        from.setPriority(source, 3);

        MemoryCardData card = MemoryCards.save(null, source, LANG);
        assertEquals("formation_plane", card.type());
        MemoryCardSettings s = stored(card);
        assertEquals(map("place_block", "NO", "fuzzy_mode", "PERCENT_25"), s.settings());
        assertEquals(3, s.priority());
        assertEquals(Map.of("AE2_Fuzzy_Card", 1), s.upgrades());

        FormationPlaneDevice to = new FormationPlaneDevice();
        WorldNetworks.Node target = node("formation_plane", to);
        Stock stock = new Stock().with(UpgradeCard.FUZZY, 1);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, card, stock, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.allOf(Part.class), loaded.parts());
        assertEquals(YesNo.NO, to.logic().getPlaceBlock());
        assertEquals(FuzzyMode.PERCENT_25, to.logic().getFuzzyMode());
        assertEquals(AEItemKey.of("Rock_Stone"), to.logic().getConfig().getKey(2));
        assertEquals(3, to.priority());
        assertEquals(3, target.comp.priority());
        assertTrue(to.upgradeCards().isInstalled(UpgradeCard.FUZZY));
        assertEquals(s.settings(), to.exportSettings());

        assertFalse(to.importSettings(Map.of("place_block", "MAYBE", "fuzzy_mode", "ROUGHLY", "access", "READ")));
        assertEquals(s.settings(), to.exportSettings());
        // Storage-Bus und Formation Plane teilen sich Fuzzy-Modus, Filter und Priorität
        BusDevice bus = new BusDevice("storage_bus");
        MemoryCards.Loaded partial = MemoryCards.load(null, node("storage_bus", bus), card, null, LANG);
        assertEquals(Outcome.PARTIAL, partial.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS, Part.PRIORITY, Part.CONFIG_INV), partial.parts());
        assertEquals(FuzzyMode.PERCENT_25, bus.storageBus().getFuzzyMode());
        assertEquals(3, bus.priority());
    }

    // ------------------------------------------------------------------ Maschinen

    private static MachineDevice machine(String kind, int slots) {
        return new MachineDevice(kind).withSlots(new SimpleSlotAccess(slots));
    }

    @Test
    void inscriberKopiertSeitenAutoExportPlatzgroesseUndKarten() {
        MachineDevice from = machine("inscriber", 4);
        assertEquals(map("inscriber_separate_sides", "NO", "auto_export", "NO", "inscriber_input_capacity",
                "SIXTY_FOUR"), from.exportSettings(), "AE2s Standardwerte");
        assertTrue(from.importSettings(map("inscriber_separate_sides", "YES", "auto_export", "YES",
                "inscriber_input_capacity", "FOUR")));
        assertTrue(from.upgradeCards().insert(UpgradeCard.SPEED));

        MemoryCardData card = MemoryCards.save(null, node("inscriber", from), LANG);
        MemoryCardSettings s = stored(card);
        assertEquals(map("inscriber_separate_sides", "YES", "auto_export", "YES", "inscriber_input_capacity", "FOUR"),
                s.settings());
        assertEquals(Map.of("AE2_Speed_Card", 1), s.upgrades());

        MachineDevice to = machine("inscriber", 4);
        WorldNetworks.Node target = node("inscriber", to);
        Stock stock = new Stock().with(UpgradeCard.SPEED, 1);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, card, stock, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.UPGRADES, Part.SETTINGS), loaded.parts());
        assertEquals(s.settings(), to.exportSettings());
        assertEquals(1, to.upgradeCards().getInstalledUpgrades(UpgradeCard.SPEED));
        assertEquals(0, stock.of(UpgradeCard.SPEED));
        assertTrue(target.comp.data() != null && target.comp.data().contains("\"separateSides\""),
                String.valueOf(target.comp.data()));

        assertFalse(to.importSettings(Map.of("inscriber_input_capacity", "SEVEN", "auto_export", "sometimes")));
        assertEquals(s.settings(), to.exportSettings());
        assertTrue(to.importSettings(Map.of("inscriber_input_capacity", "ONE")));
        assertEquals("ONE", to.exportSettings().get("inscriber_input_capacity"));
    }

    @Test
    void condenserKopiertSeineAusgabe() {
        MachineDevice from = machine("condenser", 3);
        assertEquals(Map.of("condenser_output", "TRASH"), from.exportSettings());
        assertTrue(from.importSettings(Map.of("condenser_output", CondenserOutput.SINGULARITY.name())));
        MemoryCardData card = MemoryCards.save(null, node("condenser", from), LANG);
        assertEquals("{\"settings\":{\"condenser_output\":\"SINGULARITY\"}}", card.payload());

        MachineDevice to = machine("condenser", 3);
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("condenser", to), card, null, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS), loaded.parts());
        assertEquals(Map.of("condenser_output", "SINGULARITY"), to.exportSettings());
        assertFalse(to.importSettings(Map.of("condenser_output", "GOLD")));

        // einem Inscriber sagt die Karte des Condensers nichts
        assertEquals(Outcome.INVALID, MemoryCards.load(null, node("inscriber", machine("inscriber", 4)), card, null,
                LANG).outcome());
    }

    @Test
    void vibrationChamberUndChargerHabenKeineEinstellungen() {
        MachineDevice chamber = machine("vibration_chamber", 1);
        assertNull(chamber.exportSettings(), "AE2: nur Karten");
        assertFalse(chamber.importSettings(Map.of("auto_export", "YES")));
        assertTrue(chamber.upgradeCards().insert(UpgradeCard.ENERGY));
        MemoryCardData card = MemoryCards.save(null, node("vibration_chamber", chamber), LANG);
        assertEquals("{\"upgrades\":{\"AE2_Energy_Card\":1}}", card.payload());
        MachineDevice other = machine("vibration_chamber", 1);
        Stock stock = new Stock().with(UpgradeCard.ENERGY, 1);
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("vibration_chamber", other), card, stock, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.UPGRADES), loaded.parts());
        assertEquals(1, other.upgradeCards().getInstalledUpgrades(UpgradeCard.ENERGY));

        MachineDevice charger = new MachineDevice("charger"); // ohne Logik: der Charger hat nichts zu kopieren
        assertNull(charger.exportSettings());
        assertEquals("{}", MemoryCards.save(null, node("charger", charger), LANG).payload());
        assertEquals(List.of(), List.copyOf(MemoryCards.load(null, node("charger", charger), card, null, LANG).parts()));
    }
}
