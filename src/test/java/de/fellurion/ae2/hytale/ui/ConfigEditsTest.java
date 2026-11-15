/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.automation.ExportBusLogic;
import de.fellurion.ae2.core.automation.InterfaceLogic;
import de.fellurion.ae2.core.automation.StorageBusLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.ui.ConfigEdits.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regeln der Geräte-Konfiguration: Filterplätze, Kapazitätskarten, Vorratsmengen, Priorität. */
class ConfigEditsTest {

    private static final AEItemKey STONE = AEItemKey.of("Rock_Stone");
    private static final AEItemKey DIRT = AEItemKey.of("Soil_Dirt");
    private static final AEItemKey GOLD = AEItemKey.of("Ingredient_Bar_Gold");

    @Test
    void filterNimmtJedesItemNurEinmalUndFuelltVonVorn() {
        ConfigInventory filter = ConfigInventory.configTypes(63);
        assertEquals(Result.DONE, ConfigEdits.put(filter, 18, -1, STONE, 64, true));
        assertEquals(Result.DONE, ConfigEdits.put(filter, 18, -1, DIRT, 1, true));
        assertEquals(Result.DUPLICATE, ConfigEdits.put(filter, 18, -1, STONE, 1, true));
        assertEquals(Result.DUPLICATE, ConfigEdits.put(filter, 18, 5, STONE, 1, true));
        assertEquals(STONE, filter.getKey(0));
        assertEquals(DIRT, filter.getKey(1));
        assertEquals(0, filter.getAmount(0), "Filter kennen keine Mengen");
        assertEquals(2, ConfigEdits.used(filter, 18));
        assertEquals(2, ConfigEdits.firstFree(filter, 18));
        assertEquals(1, ConfigEdits.indexOf(filter, 18, DIRT));
        assertEquals(Result.NOTHING, ConfigEdits.put(filter, 18, -1, null, 1, true));
    }

    @Test
    void gezielterPlatzWirdErsetzt() {
        ConfigInventory filter = ConfigInventory.configTypes(63);
        ConfigEdits.put(filter, 18, 4, STONE, 1, true);
        assertEquals(Result.DONE, ConfigEdits.put(filter, 18, 4, DIRT, 1, true));
        assertEquals(DIRT, filter.getKey(4));
        assertEquals(1, ConfigEdits.used(filter, 18));
        assertTrue(ConfigEdits.clear(filter, 18, 4));
        assertFalse(ConfigEdits.clear(filter, 18, 4), "schon leer");
        assertFalse(ConfigEdits.clear(filter, 18, 40), "nicht freigeschaltet");
        assertFalse(ConfigEdits.clear(filter, 18, -1));
    }

    @Test
    void nurFreigeschaltetePlaetzeSindNutzbar() {
        ConfigInventory filter = ConfigInventory.configTypes(63);
        assertEquals(Result.OUT_OF_RANGE, ConfigEdits.put(filter, 18, 18, STONE, 1, true));
        assertEquals(Result.OUT_OF_RANGE, ConfigEdits.put(filter, 18, 99, STONE, 1, true));
        for (int i = 0; i < 18; i++) {
            assertEquals(Result.DONE, ConfigEdits.put(filter, 18, -1, AEItemKey.of("Item_" + i), 1, true));
        }
        assertEquals(Result.FULL, ConfigEdits.put(filter, 18, -1, STONE, 1, true));
        assertEquals(Result.DONE, ConfigEdits.put(filter, 27, -1, STONE, 1, true), "mit einer Kapazitätskarte passt es");
        assertEquals(STONE, filter.getKey(18));
    }

    @Test
    void sichtbarePlaetzeWieInAE2() {
        assertEquals(18, BusDevice.visibleSlots(0, 63));
        assertEquals(27, BusDevice.visibleSlots(1, 63));
        assertEquals(63, BusDevice.visibleSlots(5, 63));
        assertEquals(63, BusDevice.visibleSlots(9, 63), "nie mehr als es Plätze gibt");
        assertEquals(18, BusDevice.visibleSlots(-2, 63));
    }

    @Test
    void entfernteKapazitaetskarteLeertDiePlaetzeDahinter() {
        ExportBusLogic bus = new ExportBusLogic();
        assertTrue(bus.getUpgrades().insert(UpgradeCard.CAPACITY));
        int visible = BusDevice.visibleSlots(bus.getInstalledUpgrades(UpgradeCard.CAPACITY), bus.getConfig().size());
        assertEquals(27, visible);
        ConfigEdits.put(bus.getConfig(), visible, 3, STONE, 1, true);
        ConfigEdits.put(bus.getConfig(), visible, 20, DIRT, 1, true);
        ConfigEdits.put(bus.getConfig(), visible, 26, GOLD, 1, true);

        bus.getUpgrades().remove(0);
        visible = BusDevice.visibleSlots(bus.getInstalledUpgrades(UpgradeCard.CAPACITY), bus.getConfig().size());
        assertEquals(18, visible);
        assertEquals(2, ConfigEdits.clearHidden(bus.getConfig(), visible));
        assertEquals(STONE, bus.getConfig().getKey(3));
        assertNull(bus.getConfig().getKey(20));
        assertNull(bus.getConfig().getKey(26));
        assertEquals(0, ConfigEdits.clearHidden(bus.getConfig(), visible));
    }

    @Test
    void nurPassendeKartenInDerErlaubtenAnzahl() {
        ExportBusLogic export = new ExportBusLogic();
        assertFalse(export.getUpgrades().canInsert(UpgradeCard.INVERTER), "Export-Bus kennt keine Inverter-Karte");
        assertFalse(export.getUpgrades().canInsert(UpgradeCard.VOID));
        assertTrue(export.getUpgrades().insert(UpgradeCard.CRAFTING));
        assertFalse(export.getUpgrades().canInsert(UpgradeCard.CRAFTING), "höchstens eine");
        for (int i = 0; i < 4; i++) {
            assertTrue(export.getUpgrades().insert(UpgradeCard.SPEED), "Karte " + i);
        }
        assertFalse(export.getUpgrades().canInsert(UpgradeCard.FUZZY), "alle fünf Plätze belegt");

        StorageBusLogic storage = new StorageBusLogic();
        assertTrue(storage.getUpgrades().canInsert(UpgradeCard.INVERTER));
        assertTrue(storage.getUpgrades().canInsert(UpgradeCard.VOID));
        assertFalse(storage.getUpgrades().canInsert(UpgradeCard.SPEED));

        InterfaceLogic iface = new InterfaceLogic(InterfaceLogic.BLOCK_ITEM_ID);
        assertTrue(iface.getUpgrades().canInsert(UpgradeCard.CRAFTING));
        assertTrue(iface.getUpgrades().insert(UpgradeCard.FUZZY));
        assertFalse(iface.getUpgrades().canInsert(UpgradeCard.CRAFTING), "nur ein Kartenplatz");
    }

    @Test
    void filterAusInhaltUebernehmen() {
        ConfigInventory filter = ConfigInventory.configTypes(63);
        ConfigEdits.put(filter, 18, 0, GOLD, 1, true);
        ConfigEdits.put(filter, 18, 17, AEItemKey.of("Ingredient_Bar_Gold", "{\"d\":1.0}"), 1, true);
        List<AEKey> contents = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            contents.add(AEItemKey.of("Item_" + i));
        }
        assertEquals(18, ConfigEdits.partition(filter, 18, contents), "nur die freigeschalteten Plätze");
        assertEquals(AEItemKey.of("Item_0"), filter.getKey(0));
        assertEquals(AEItemKey.of("Item_17"), filter.getKey(17));
        assertNull(filter.getKey(18));
        assertEquals(2, ConfigEdits.partition(filter, 18, List.of(STONE, DIRT)));
        assertEquals(STONE, filter.getKey(0));
        assertNull(filter.getKey(2), "alte Einträge verschwinden");
        assertEquals(0, ConfigEdits.partition(filter, 18, List.of()));
        assertTrue(filter.isEmpty());
    }

    @Test
    void vorratsplaetzeMitMengen() {
        ConfigInventory stock = ConfigInventory.configStacks(9).setItemMaxStackSize(id -> id.equals("Tool_Pickaxe") ? 1 : 100)
                .setCapacity(AEKeyType.ITEMS, Long.MAX_VALUE);
        assertEquals(Result.DONE, ConfigEdits.put(stock, 9, 2, STONE, 32, false));
        assertEquals(new GenericStack(STONE, 32), stock.getStack(2));
        assertEquals(Result.DONE, ConfigEdits.put(stock, 9, -1, STONE, 500, false), "dasselbe Item in zwei Plätzen");
        assertEquals(100, stock.getAmount(0), "höchstens ein Stapel");
        assertEquals(Result.DONE, ConfigEdits.put(stock, 9, 2, DIRT, 0, false));
        assertEquals(new GenericStack(DIRT, 1), stock.getStack(2), "ersetzt, Menge mindestens 1");

        assertEquals(11, ConfigEdits.changeAmount(stock, 2, 10));
        assertEquals(1, ConfigEdits.changeAmount(stock, 2, -64), "nie unter 1");
        assertEquals(100, ConfigEdits.changeAmount(stock, 2, 5000), "nie über der Stapelgröße");
        assertEquals(0, ConfigEdits.changeAmount(stock, 5, 10), "leerer Platz");
        assertEquals(0, ConfigEdits.changeAmount(stock, 42, 10));
        ConfigEdits.put(stock, 9, 3, AEItemKey.of("Tool_Pickaxe"), 64, false);
        assertEquals(1, stock.getAmount(3));
        assertEquals(1, ConfigEdits.changeAmount(stock, 3, 10), "nicht stapelbar");
    }

    @Test
    void prioritaet() {
        assertEquals(15, ConfigEdits.addPriority(5, 10));
        assertEquals(-95, ConfigEdits.addPriority(5, -100));
        assertEquals(Integer.MAX_VALUE, ConfigEdits.addPriority(Integer.MAX_VALUE - 3, 100));
        assertEquals(Integer.MIN_VALUE, ConfigEdits.addPriority(Integer.MIN_VALUE + 3, -100));
        assertEquals(42, ConfigEdits.toPriority(42.0, 7));
        assertEquals(-3, ConfigEdits.toPriority(-2.6, 7));
        assertEquals(7, ConfigEdits.toPriority(null, 7));
        assertEquals(7, ConfigEdits.toPriority(Double.NaN, 7));
        assertEquals(Integer.MAX_VALUE, ConfigEdits.toPriority(1e18, 7));
    }

    @Test
    void zahlenfeldBleibtInSeinenGrenzen() {
        assertEquals(110, ConfigEdits.addNumber(10, 100, 0, 1000));
        assertEquals(0, ConfigEdits.addNumber(10, -100, 0, 1000), "nie unter dem Minimum");
        assertEquals(1000, ConfigEdits.addNumber(990, 100, 0, 1000), "nie über dem Maximum");
        assertEquals(Long.MAX_VALUE, ConfigEdits.addNumber(Long.MAX_VALUE - 3, 100, 0, Long.MAX_VALUE), "kein Überlauf");
        assertEquals(Long.MIN_VALUE, ConfigEdits.addNumber(Long.MIN_VALUE + 3, -100, Long.MIN_VALUE, 0));
        assertEquals(42, ConfigEdits.toNumber(41.6, 7, 0, 1000));
        assertEquals(1000, ConfigEdits.toNumber(1e30, 7, 0, 1000));
        assertEquals(0, ConfigEdits.toNumber(-5.0, 7, 0, 1000));
        assertEquals(7, ConfigEdits.toNumber(null, 7, 0, 1000));
        assertEquals(7, ConfigEdits.toNumber(Double.NaN, 7, 0, 1000));
        assertEquals(5, ConfigEdits.addNumber(3, 1, 5, 2), "verdrehte Grenzen: das Minimum gilt");
    }
}
