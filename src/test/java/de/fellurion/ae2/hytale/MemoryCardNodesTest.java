/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.automation.ExportBusLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.Enums.SchedulingMode;
import de.fellurion.ae2.core.config.Enums.StorageFilter;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.core.tools.MemoryCardSettings.CardStock;
import de.fellurion.ae2.core.tools.MemoryCardSettings.Part;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.InterfaceDevice;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.items.MemoryCardTarget;
import de.fellurion.ae2.hytale.tools.MemoryCards;
import de.fellurion.ae2.hytale.tools.MemoryCards.Outcome;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory Card gegen echte Geräte: speichern und übertragen von Einstellungen, Filter, Priorität und Karten zwischen
 * Bussen, Interface und Laufwerk – gleiche Art, andere Art, Geräte mit eigenem Umgang ({@link MemoryCardTarget}).
 * Steht in diesem Paket, weil nur hier ein {@link WorldNetworks.Node} ohne Welt angelegt werden kann.
 */
class MemoryCardNodesTest {

    private static final String LANG = "en-US";

    static WorldNetworks.Node node(String kind, @Nullable Device device) {
        AE2BlockComponent comp = new AE2BlockComponent();
        try {
            Field f = AE2BlockComponent.class.getDeclaredField("kind");
            f.setAccessible(true);
            f.set(comp, kind);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        // eine ungültige Referenz: markDirty() tut dann nichts
        Ref<ChunkStore> nowhere = new Ref<>((Store<ChunkStore>) null);
        WorldNetworks.Node n = new WorldNetworks.Node(BlockAccess.pack(1, 2, 3), nowhere, comp,
                new BlockModule.BlockStateInfo(0, nowhere));
        n.device = device;
        return n;
    }

    /** Karten eines Spielers; zählt mit, damit nichts unbemerkt entsteht oder verschwindet. */
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

    /**
     * Ein Gerät mit Vorrat (Plätze mit Mengen), Priorität und einer Einstellung – wie das ME-Interface, aber ohne
     * dessen Bindung an Hytales Item-Daten, die es ohne Server nicht gibt.
     */
    private static final class Stocker implements Device, ConfigHost {
        final ConfigInventory stock = ConfigInventory.configStacks(9);
        final UpgradeInventory cards = new UpgradeInventory("AE2_Interface", 1);
        FuzzyMode fuzzy = FuzzyMode.IGNORE_ALL;
        int priority;
        int changed;

        @Override
        public GhostSlots ghostSlots(Context ctx) {
            return new GhostSlots(stock, 9, true, "ae2bus.filter.interface", "ae2bus.tip.stock", "ae2bus.hint.interface");
        }

        @Override
        public UpgradeInventory upgradeCards() {
            return cards;
        }

        @Override
        public Map<String, String> exportSettings() {
            return Map.of(BusDevice.SETTING_FUZZY_MODE, fuzzy.name());
        }

        @Override
        public boolean importSettings(Map<String, String> in) {
            return MemoryCardSettings.importEnum(in, BusDevice.SETTING_FUZZY_MODE, FuzzyMode.class, m -> fuzzy = m);
        }

        @Override
        public boolean hasPriority() {
            return true;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void setPriority(WorldNetworks.Node node, int value) {
            priority = value;
            node.comp.setPriority(value);
            node.markDirty();
        }

        @Override
        public void configChanged(WorldNetworks.Node node) {
            changed++;
        }
    }

    private static int installed(UpgradeInventory inv, UpgradeCard card) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            n += inv.get(i) == card ? 1 : 0;
        }
        return n;
    }

    @Test
    void exportBusAufExportBusKopiertAlles() {
        BusDevice from = new BusDevice("export_bus");
        ExportBusLogic a = (ExportBusLogic) from.ioBus();
        assertTrue(from.upgrades().insert(UpgradeCard.CAPACITY));
        assertTrue(from.upgrades().insert(UpgradeCard.SPEED));
        assertTrue(from.upgrades().insert(UpgradeCard.SPEED));
        assertTrue(from.upgrades().insert(UpgradeCard.FUZZY));
        a.getConfig().setKey(0, AEItemKey.of("Rock_Stone"));
        a.getConfig().setKey(20, AEItemKey.of("Soil_Dirt")); // nur mit Kapazitätskarte nutzbar
        a.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        a.setFuzzyMode(FuzzyMode.PERCENT_25);
        a.setCraftOnly(true);
        a.setSchedulingMode(SchedulingMode.RANDOM);

        MemoryCardData card = MemoryCards.save(null, node("export_bus", from), LANG);
        assertNotNull(card);
        assertEquals("export_bus", card.type());
        assertNull(card.nameKey(), "ohne Katalogeintrag kein Name");
        MemoryCardSettings stored = MemoryCardSettings.fromJson(card.payload());
        assertEquals(Map.of("AE2_Capacity_Card", 1, "AE2_Speed_Card", 2, "AE2_Fuzzy_Card", 1), stored.upgrades());
        assertEquals("LOW_SIGNAL", stored.settings().get(BusDevice.SETTING_REDSTONE_CONTROLLED));
        assertEquals("YES", stored.settings().get(BusDevice.SETTING_CRAFT_ONLY));
        assertNull(stored.priority(), "ein Export-Bus hat keine Priorität");

        BusDevice to = new BusDevice("export_bus");
        ExportBusLogic b = (ExportBusLogic) to.ioBus();
        assertTrue(to.upgrades().insert(UpgradeCard.REDSTONE));
        b.getConfig().setKey(5, AEItemKey.of("Wood_Oak"));
        Stock stock = new Stock().with(UpgradeCard.CAPACITY, 1).with(UpgradeCard.SPEED, 5);
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("export_bus", to), card, stock, LANG);

        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.UPGRADES, Part.SETTINGS, Part.CONFIG_INV), loaded.parts());
        assertEquals(Map.of(UpgradeCard.FUZZY, 1), loaded.missing(), "der Spieler hatte keine Fuzzy-Karte");
        assertEquals(1, installed(to.upgrades(), UpgradeCard.CAPACITY));
        assertEquals(2, installed(to.upgrades(), UpgradeCard.SPEED));
        assertEquals(0, installed(to.upgrades(), UpgradeCard.REDSTONE), "überzählige Karte ging zurück");
        assertEquals(1, stock.of(UpgradeCard.REDSTONE));
        assertEquals(3, stock.of(UpgradeCard.SPEED));
        assertEquals(0, stock.of(UpgradeCard.CAPACITY));
        assertEquals(AEItemKey.of("Rock_Stone"), b.getConfig().getKey(0));
        assertEquals(AEItemKey.of("Soil_Dirt"), b.getConfig().getKey(20));
        assertNull(b.getConfig().getKey(5), "der alte Filter ist ersetzt");
        assertEquals(RedstoneMode.LOW_SIGNAL, b.getRedstoneMode());
        assertEquals(FuzzyMode.PERCENT_25, b.getFuzzyMode());
        assertTrue(b.isCraftOnly());
        assertEquals(SchedulingMode.RANDOM, b.getSchedulingMode());
        // bis auf die fehlende Karte gleicht das Ziel jetzt der Quelle
        MemoryCardSettings now = MemoryCardSettings.fromJson(MemoryCards.save(null, node("export_bus", to), LANG).payload());
        assertEquals(stored.settings(), now.settings());
        assertEquals(stored.config(), now.config());
        assertEquals(Map.of("AE2_Capacity_Card", 1, "AE2_Speed_Card", 2), now.upgrades());
    }

    @Test
    void ohneKapazitaetskarteBleibenGesperrtePlaetzeLeer() {
        BusDevice from = new BusDevice("import_bus");
        assertTrue(from.upgrades().insert(UpgradeCard.CAPACITY));
        from.config().setKey(3, AEItemKey.of("Rock_Stone"));
        from.config().setKey(25, AEItemKey.of("Soil_Dirt"));
        MemoryCardData card = MemoryCards.save(null, node("import_bus", from), LANG);

        BusDevice to = new BusDevice("import_bus");
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("import_bus", to), card, new Stock(), LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(Map.of(UpgradeCard.CAPACITY, 1), loaded.missing());
        assertEquals(AEItemKey.of("Rock_Stone"), to.config().getKey(3));
        assertNull(to.config().getKey(25), "ohne die Karte ist Platz 25 gesperrt und bleibt leer");
    }

    @Test
    void ohneSpielerBleibenDieKartenWieSieSind() {
        BusDevice from = new BusDevice("export_bus");
        assertTrue(from.upgrades().insert(UpgradeCard.SPEED));
        from.config().setKey(0, AEItemKey.of("Rock_Stone"));
        BusDevice to = new BusDevice("export_bus");
        assertTrue(to.upgrades().insert(UpgradeCard.CRAFTING));
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("export_bus", to),
                MemoryCards.save(null, node("export_bus", from), LANG), null, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS, Part.CONFIG_INV), loaded.parts());
        assertEquals(1, installed(to.upgrades(), UpgradeCard.CRAFTING));
        assertEquals(0, installed(to.upgrades(), UpgradeCard.SPEED));
        assertEquals(AEItemKey.of("Rock_Stone"), to.config().getKey(0));
    }

    @Test
    void storageBusKopiertZugriffAnzeigeUndPrioritaet() {
        BusDevice from = new BusDevice("storage_bus");
        WorldNetworks.Node source = node("storage_bus", from);
        from.setPriority(source, 42);
        from.storageBus().setAccess(AccessRestriction.READ);
        from.storageBus().setStorageFilter(StorageFilter.NONE);
        from.storageBus().setFilterOnExtract(false);
        from.storageBus().setFuzzyMode(FuzzyMode.PERCENT_75);
        from.config().setKey(1, AEItemKey.of("Rock_Stone"));

        BusDevice to = new BusDevice("storage_bus");
        WorldNetworks.Node target = node("storage_bus", to);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, MemoryCards.save(null, source, LANG), null, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS, Part.PRIORITY, Part.CONFIG_INV), loaded.parts());
        assertEquals(42, to.priority());
        assertEquals(42, target.comp.priority(), "die Priorität steht auch in der Block-Komponente");
        assertEquals(AccessRestriction.READ, to.storageBus().getAccess());
        assertEquals(StorageFilter.NONE, to.storageBus().getStorageFilter());
        assertFalse(to.storageBus().isFilterOnExtract());
        assertEquals(FuzzyMode.PERCENT_75, to.storageBus().getFuzzyMode());
        assertEquals(AEItemKey.of("Rock_Stone"), to.config().getKey(1));
    }

    @Test
    void busEinstellungenUebergehenFremdesUndUngueltiges() {
        BusDevice storage = new BusDevice("storage_bus");
        assertFalse(storage.importSettings(Map.of()));
        assertFalse(storage.importSettings(Map.of("redstone_controlled", "LOW_SIGNAL")), "kennt der Storage-Bus nicht");
        assertFalse(storage.importSettings(Map.of("access", "NO_ACCESS")), "für den Storage-Bus kein gültiger Wert");
        assertFalse(storage.importSettings(Map.of("access", "banana")));
        assertEquals(AccessRestriction.READ_WRITE, storage.storageBus().getAccess());
        assertTrue(storage.importSettings(Map.of("access", "WRITE", "unknown", "x")));
        assertEquals(AccessRestriction.WRITE, storage.storageBus().getAccess());
        assertEquals(Set.of("access", "fuzzy_mode", "storage_filter", "filter_on_extract"),
                storage.exportSettings().keySet());

        BusDevice importBus = new BusDevice("import_bus");
        assertEquals(Set.of("redstone_controlled", "fuzzy_mode"), importBus.exportSettings().keySet());
        assertFalse(importBus.importSettings(Map.of("craft_only", "YES", "scheduling_mode", "RANDOM")),
                "das sind Einstellungen des Export-Bus");
        assertEquals(Set.of("redstone_controlled", "fuzzy_mode", "craft_only", "scheduling_mode"),
                new BusDevice("export_bus").exportSettings().keySet());
        // jeder Bus liest seinen eigenen Export unverändert wieder ein
        for (String kind : new String[]{"import_bus", "export_bus", "storage_bus"}) {
            BusDevice a = new BusDevice(kind);
            BusDevice b = new BusDevice(kind);
            assertTrue(b.importSettings(a.exportSettings()), kind);
            assertEquals(a.exportSettings(), b.exportSettings(), kind);
        }
    }

    @Test
    void vorratMitMengenPrioritaetUndEinstellungWerdenKopiert() {
        Stocker from = new Stocker();
        WorldNetworks.Node source = node("interface", from);
        from.stock.setStack(0, new GenericStack(AEItemKey.of("Rock_Stone"), 32));
        from.stock.setStack(4, new GenericStack(AEItemKey.of("Rock_Stone"), 7));
        from.fuzzy = FuzzyMode.PERCENT_99;
        from.setPriority(source, -3);
        assertTrue(from.cards.insert(UpgradeCard.FUZZY));

        Stocker to = new Stocker();
        to.stock.setStack(8, new GenericStack(AEItemKey.of("Wood_Oak"), 1));
        WorldNetworks.Node target = node("interface", to);
        Stock stock = new Stock().with(UpgradeCard.FUZZY, 2);
        MemoryCards.Loaded loaded = MemoryCards.load(null, target, MemoryCards.save(null, source, LANG), stock, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(EnumSet.allOf(Part.class), loaded.parts());
        assertTrue(loaded.missing().isEmpty());
        assertEquals(new GenericStack(AEItemKey.of("Rock_Stone"), 32), to.stock.getStack(0));
        assertEquals(new GenericStack(AEItemKey.of("Rock_Stone"), 7), to.stock.getStack(4), "dasselbe Item zweimal");
        assertNull(to.stock.getStack(8));
        assertEquals(FuzzyMode.PERCENT_99, to.fuzzy);
        assertEquals(-3, to.priority);
        assertEquals(-3, target.comp.priority());
        assertEquals(1, installed(to.cards, UpgradeCard.FUZZY));
        assertEquals(1, stock.of(UpgradeCard.FUZZY));
        assertEquals(1, to.changed, "das Gerät speichert genau einmal");
        assertEquals(0, from.changed, "Speichern auf die Karte ändert die Quelle nicht");
    }

    /** Block und Kabel-Part des Interface teilen sich wie in AE2 Art und Einstellung („share the same soul“). */
    @Test
    void interfaceBlockUndPartHabenDieselbeEinstellung() {
        InterfaceDevice block = new InterfaceDevice(false);
        InterfaceDevice part = new InterfaceDevice(true);
        block.logic().setFuzzyMode(FuzzyMode.PERCENT_75);
        assertEquals(Map.of("fuzzy_mode", "PERCENT_75"), block.exportSettings());
        assertTrue(part.importSettings(block.exportSettings()));
        assertEquals(FuzzyMode.PERCENT_75, part.logic().getFuzzyMode());
        assertFalse(part.importSettings(Map.of("fuzzy_mode", "nonsense", "access", "READ")));
        MemoryCardData card = MemoryCards.save(null, node("interface", block), LANG);
        assertEquals("interface", card.type());
        MemoryCardSettings stored = MemoryCardSettings.fromJson(card.payload());
        assertEquals(0, stored.priority());
        assertTrue(stored.upgrades().isEmpty());
        assertTrue(stored.config().isEmpty(), "leerer Vorrat");
    }

    @Test
    void andereGeraeteartUebernimmtNurWasSieVersteht() {
        Stocker from = new Stocker();
        WorldNetworks.Node source = node("interface", from);
        from.stock.setStack(0, new GenericStack(AEItemKey.of("Rock_Stone"), 32));
        from.fuzzy = FuzzyMode.PERCENT_25;
        from.setPriority(source, 9);
        MemoryCardData card = MemoryCards.save(null, source, LANG);

        BusDevice bus = new BusDevice("export_bus");
        MemoryCards.Loaded partial = MemoryCards.load(null, node("export_bus", bus), card, null, LANG);
        assertEquals(Outcome.PARTIAL, partial.outcome());
        assertEquals(EnumSet.of(Part.SETTINGS, Part.CONFIG_INV), partial.parts(), "keine Priorität am Export-Bus");
        assertEquals(FuzzyMode.PERCENT_25, bus.ioBus().getFuzzyMode());
        assertEquals(AEItemKey.of("Rock_Stone"), bus.config().getKey(0));
        assertEquals(0, bus.config().getAmount(0), "ein Filter kennt keine Mengen");

        // umgekehrt bekommt ein Vorrat aus einem Filter die Menge 1
        Stocker stocker = new Stocker();
        MemoryCards.Loaded reverse = MemoryCards.load(null, node("interface", stocker),
                MemoryCards.save(null, node("export_bus", bus), LANG), null, LANG);
        assertEquals(Outcome.PARTIAL, reverse.outcome());
        assertEquals(new GenericStack(AEItemKey.of("Rock_Stone"), 1), stocker.stock.getStack(0));

        // ein Laufwerk versteht davon nur die Priorität
        CellHostDevice drive = new CellHostDevice(CellHostLogic.drive());
        WorldNetworks.Node driveNode = node("drive", drive);
        MemoryCards.Loaded priority = MemoryCards.load(null, driveNode, card, null, LANG);
        assertEquals(Outcome.PARTIAL, priority.outcome());
        assertEquals(EnumSet.of(Part.PRIORITY), priority.parts());
        assertEquals(9, drive.logic().getPriority());
        assertEquals(9, driveNode.comp.priority());

        // ein Block ohne alles (Controller, Kabel …) versteht nichts
        assertEquals(Outcome.INVALID, MemoryCards.load(null, node("controller", null), card, null, LANG).outcome());
    }

    @Test
    void laufwerkKopiertSeinePrioritaet() {
        CellHostDevice from = new CellHostDevice(CellHostLogic.drive());
        from.logic().setPriority(5);
        MemoryCardData card = MemoryCards.save(null, node("drive", from), LANG);
        assertEquals("{\"priority\":5}", card.payload());
        CellHostDevice to = new CellHostDevice(CellHostLogic.drive());
        MemoryCards.Loaded loaded = MemoryCards.load(null, node("drive", to), card, null, LANG);
        assertEquals(Outcome.LOADED, loaded.outcome());
        assertEquals(5, to.logic().getPriority());
    }

    @Test
    void geraeteOhneEinstellungenSpeichernNurIhreArt() {
        MemoryCardData card = MemoryCards.save(null, node("controller", null), LANG);
        assertNotNull(card, "wie in AE2 lässt sich jedes Gerät speichern");
        assertEquals("controller", card.type());
        assertEquals("{}", card.payload());
        assertEquals(Outcome.LOADED, MemoryCards.load(null, node("controller", null), card, null, LANG).outcome());
        assertEquals(Outcome.INVALID, MemoryCards.load(null, node("energy_cell", null), card, null, LANG).outcome());
        assertEquals(Outcome.INVALID, MemoryCards.load(null, node("export_bus", new BusDevice("export_bus")), card,
                new Stock(), LANG).outcome(), "nichts auf der Karte, was ein Bus übernehmen könnte");
        assertFalse(MemoryCards.isMachine(null));
        assertFalse(MemoryCards.isMachine(node("controller", null)), "noch nicht im Kern angemeldet");
        assertTrue(MemoryCards.isMachine(node("export_bus", new BusDevice("export_bus"))));
    }

    @Test
    void kaputteKartenRichtenNichtsAn() {
        BusDevice bus = new BusDevice("export_bus");
        bus.config().setKey(0, AEItemKey.of("Rock_Stone"));
        assertTrue(bus.upgrades().insert(UpgradeCard.SPEED));
        WorldNetworks.Node n = node("export_bus", bus);
        for (String payload : new String[]{"", "not json", "[1,2,3]", "{\"freq\":7}", "{\"config\":5,\"settings\":[]}"}) {
            Stock stock = new Stock();
            MemoryCards.Loaded same = MemoryCards.load(null, n, new MemoryCardData("export_bus", payload, null), stock, LANG);
            assertEquals(Outcome.LOADED, same.outcome(), payload);
            assertTrue(same.parts().isEmpty(), payload);
            assertEquals(Outcome.INVALID,
                    MemoryCards.load(null, n, new MemoryCardData("p2p", payload, null), stock, LANG).outcome(), payload);
            assertEquals(AEItemKey.of("Rock_Stone"), bus.config().getKey(0), payload);
            assertEquals(1, installed(bus.upgrades(), UpgradeCard.SPEED), payload);
            assertEquals(0, stock.of(UpgradeCard.SPEED), payload);
        }
    }

    /** Ein Gerät, das selbst mit der Karte umgeht (wie ein P2P-Tunnel). */
    private static final class Tunnel implements Device, MemoryCardTarget {
        String frequency = "none";

        @Override
        public MemoryCardData saveToCard(WorldNetworks nets, WorldNetworks.Node node) {
            frequency = "new";
            return new MemoryCardData("p2p", "{\"freq\":\"new\"}", "ae2.items.AE2_ME_P2P_Tunnel.name");
        }

        @Override
        public Result loadFromCard(WorldNetworks nets, WorldNetworks.Node node, MemoryCardData data) {
            if (!"p2p".equals(data.type())) {
                return Result.WRONG_TYPE;
            }
            if (!data.payload().contains("freq")) {
                return Result.FAILED;
            }
            frequency = "linked";
            return Result.LOADED;
        }
    }

    @Test
    void geraeteMitEigenemUmgangEntscheidenSelbst() {
        Tunnel a = new Tunnel();
        MemoryCardData card = MemoryCards.save(null, node("p2p_tunnel", a), LANG);
        assertEquals("p2p", card.type());
        assertEquals("new", a.frequency, "Speichern darf das Gerät verändern");
        Tunnel b = new Tunnel();
        assertEquals(Outcome.LOADED, MemoryCards.load(null, node("p2p_tunnel", b), card, new Stock(), LANG).outcome());
        assertEquals("linked", b.frequency);
        assertEquals(Outcome.FAILED, MemoryCards.load(null, node("p2p_tunnel", b),
                new MemoryCardData("p2p", "{}", null), null, LANG).outcome());
        MemoryCardData busCard = MemoryCards.save(null, node("export_bus", new BusDevice("export_bus")), LANG);
        assertEquals(Outcome.INVALID, MemoryCards.load(null, node("p2p_tunnel", b), busCard, null, LANG).outcome());
        // und umgekehrt: die Tunnel-Karte sagt einem Bus nichts
        assertEquals(Outcome.INVALID, MemoryCards.load(null, node("export_bus", new BusDevice("export_bus")), card,
                new Stock(), LANG).outcome());
    }
}
