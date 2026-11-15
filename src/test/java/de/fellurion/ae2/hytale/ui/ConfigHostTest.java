/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

import de.fellurion.ae2.core.automation.ExportBusLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.Enums.SchedulingMode;
import de.fellurion.ae2.core.config.Enums.StorageFilter;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.device.InterfaceDevice;
import de.fellurion.ae2.hytale.ui.ConfigHost.Context;
import de.fellurion.ae2.hytale.ui.ConfigHost.GhostSlots;
import de.fellurion.ae2.hytale.ui.ConfigHost.Setting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was Busse und das Interface der Konfigurationsseite als {@link ConfigHost} anbieten: dieselben Abschnitte,
 * Einstellungen (in derselben Reihenfolge) und Texte, die die Seite früher selbst je Geräteart zusammenstellte.
 */
class ConfigHostTest {

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
    void einGeraetOhneAllesHatKeineAbschnitte() {
        ConfigHost plain = new ConfigHost() {
        };
        assertNull(plain.titleKey());
        assertNull(plain.info(CTX));
        assertNull(plain.ghostSlots(CTX));
        assertNull(plain.upgradeCards());
        assertTrue(plain.settings(CTX).isEmpty());
        assertNull(plain.numberField(CTX));
        assertFalse(plain.hasPriority());
        assertNull(plain.itemSection());
        assertNull(plain.action(CTX));
        assertNull(plain.note(CTX));
        assertNull(plain.watchers());
        assertFalse(plain.opensOnCrouch());
    }

    @Test
    void exportBusZeigtEinstellungenJeNachKarten() {
        BusDevice bus = new BusDevice("export_bus");
        ExportBusLogic logic = (ExportBusLogic) bus.ioBus();
        assertEquals(List.of("ae2bus.setting.scheduling.default"), texts(bus));
        bus.settings(CTX).getFirst().next().run();
        assertEquals(SchedulingMode.ROUNDROBIN, logic.getSchedulingMode());

        assertTrue(bus.upgrades().insert(UpgradeCard.CRAFTING));
        assertTrue(bus.upgrades().insert(UpgradeCard.REDSTONE));
        assertTrue(bus.upgrades().insert(UpgradeCard.FUZZY));
        assertEquals(List.of("ae2bus.setting.scheduling.roundrobin", "ae2ui.workbench.fuzzy.ignore_all",
                "ae2bus.setting.redstone.ignore", "ae2bus.setting.craftOnly.no"), texts(bus));
        List<Setting> settings = bus.settings(CTX);
        settings.get(1).next().run();
        settings.get(2).next().run();
        settings.get(3).next().run();
        assertEquals(FuzzyMode.PERCENT_99, logic.getFuzzyMode());
        assertEquals(RedstoneMode.LOW_SIGNAL, logic.getRedstoneMode());
        assertTrue(logic.isCraftOnly());
        assertEquals("ae2emitter.bus.signalOff", bus.note(CTX), "mit Redstone-Karte: ob ein Signal anliegt");

        assertFalse(bus.hasPriority());
        assertNull(bus.action(CTX), "nur der Storage-Bus füllt den Filter aus dem Inhalt");
        assertNull(bus.itemSection());
        assertNull(bus.numberField(CTX));
        assertEquals(bus.upgrades(), bus.upgradeCards());
    }

    @Test
    void importBusOhneKartenHatKeineEinstellungen() {
        BusDevice bus = new BusDevice("import_bus");
        assertTrue(bus.settings(CTX).isEmpty());
        assertNull(bus.note(CTX));
        GhostSlots slots = bus.ghostSlots(CTX);
        assertNotNull(slots);
        assertEquals(18, slots.usable());
        assertFalse(slots.amounts());
        assertEquals("ae2bus.filter.import", slots.headerKey());
        assertEquals("ae2bus.tip.filter", slots.tipKey());
        assertEquals("ae2bus.hint.bus", slots.hintKey());
        assertTrue(bus.upgrades().insert(UpgradeCard.INVERTER));
        assertTrue(bus.upgrades().insert(UpgradeCard.CAPACITY));
        slots = bus.ghostSlots(CTX);
        assertEquals("ae2bus.filter.importInverted", slots.headerKey());
        assertEquals(27, slots.usable());
        assertEquals("ae2bus.filter.export", new BusDevice("export_bus").ghostSlots(CTX).headerKey());
    }

    @Test
    void storageBusHatZugriffAnzeigeFilterUndPrioritaet() {
        BusDevice bus = new BusDevice("storage_bus");
        assertEquals(List.of("ae2bus.setting.access.read_write", "ae2bus.setting.storageFilter.extractable_only",
                "ae2bus.setting.filterOnExtract.yes"), texts(bus));
        // Zugriff: beides -> nur entnehmen -> nur einlagern -> beides
        bus.settings(CTX).getFirst().next().run();
        assertEquals(AccessRestriction.READ, bus.storageBus().getAccess());
        bus.settings(CTX).getFirst().next().run();
        assertEquals(AccessRestriction.WRITE, bus.storageBus().getAccess());
        bus.settings(CTX).getFirst().next().run();
        assertEquals(AccessRestriction.READ_WRITE, bus.storageBus().getAccess());
        bus.settings(CTX).get(1).next().run();
        assertEquals(StorageFilter.NONE, bus.storageBus().getStorageFilter());
        bus.settings(CTX).get(1).next().run();
        assertEquals(StorageFilter.EXTRACTABLE_ONLY, bus.storageBus().getStorageFilter());
        bus.settings(CTX).get(2).next().run();
        assertFalse(bus.storageBus().isFilterOnExtract());
        assertTrue(bus.upgrades().insert(UpgradeCard.FUZZY));
        assertEquals(4, bus.settings(CTX).size());

        assertTrue(bus.hasPriority());
        assertEquals(0, bus.priority());
        assertEquals("ae2bus.filter.storage", bus.ghostSlots(CTX).headerKey());
        assertTrue(bus.upgrades().insert(UpgradeCard.INVERTER));
        assertEquals("ae2bus.filter.storageInverted", bus.ghostSlots(CTX).headerKey());

        ConfigHost.Action partition = bus.action(CTX);
        assertNotNull(partition);
        assertEquals("ae2bus.filter.partition", partition.text());
        ConfigHost.Feedback feedback = partition.run().get();
        assertTrue(feedback.error(), "ohne angeschlossenes Inventar");
        assertEquals("ae2bus.msg.noTarget", feedback.text());
    }

    @Test
    void interfaceHatVorratMitMengenEchtePlaetzeUndPrioritaet() {
        InterfaceDevice iface = new InterfaceDevice(false);
        GhostSlots slots = iface.ghostSlots(CTX);
        assertNotNull(slots);
        assertTrue(slots.amounts());
        assertEquals(InterfaceDevice.SLOTS, slots.usable());
        assertEquals("ae2bus.filter.interface", slots.headerKey());
        assertEquals("ae2bus.tip.stock", slots.tipKey());
        assertEquals("ae2bus.hint.interface", slots.hintKey());
        assertEquals(iface.logic().getUpgrades(), iface.upgradeCards());
        assertTrue(iface.settings(CTX).isEmpty(), "Fuzzy-Modus erst mit Karte");
        assertTrue(iface.logic().getUpgrades().insert(UpgradeCard.FUZZY));
        assertEquals(List.of("ae2ui.workbench.fuzzy.ignore_all"), texts(iface));
        assertTrue(iface.hasPriority());
        ConfigHost.ItemSection section = iface.itemSection();
        assertNotNull(section);
        assertEquals(InterfaceDevice.SLOTS, section.slots());
        assertEquals("ae2bus.storage.header", section.headerKey());
        assertEquals("ae2bus.tip.storage", section.tipKey());
        assertNull(iface.action(CTX));
        assertNull(iface.note(CTX));
    }

    @Test
    void einstellungenSchaltenReihum() {
        RedstoneMode[] seen = {null};
        Setting.cycle(CTX, "ae2bus.setting.redstone.", RedstoneMode.SIGNAL_PULSE, RedstoneMode.values(), m -> seen[0] = m)
                .next().run();
        assertEquals(RedstoneMode.IGNORE, seen[0], "nach dem letzten Wert wieder der erste");
        AccessRestriction[] access = {null};
        Setting.cycle(CTX, "ae2bus.setting.access.", AccessRestriction.NO_ACCESS, new AccessRestriction[]{
                AccessRestriction.READ_WRITE, AccessRestriction.READ}, a -> access[0] = a).next().run();
        assertEquals(AccessRestriction.READ_WRITE, access[0], "ein Wert außerhalb der Liste springt auf den ersten");
        boolean[] flag = {false};
        Setting toggle = Setting.toggle(CTX, "ae2bus.setting.craftOnly.", false, v -> flag[0] = v);
        assertEquals("ae2bus.setting.craftOnly.no", toggle.text());
        toggle.next().run();
        assertTrue(flag[0]);
        assertEquals(3, ConfigHost.NumberField.of("ae2bus.priority.value", 5, 0, 10, v -> {
        }).steps().length);
    }
}
