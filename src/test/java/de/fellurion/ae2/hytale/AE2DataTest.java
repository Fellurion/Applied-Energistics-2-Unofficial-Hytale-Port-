/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale;

import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.machines.ChargerRecipes;
import de.fellurion.ae2.core.machines.InscriberRecipes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passen die generierten ae2data-Dateien zu Kern und Hytale-Anbindung? */
class AE2DataTest {

    @BeforeAll
    static void load() {
        ItemCatalog.load();
    }

    @Test
    void itemCatalogHasAllItems() {
        assertTrue(ItemCatalog.size() >= 350, "items.json zu klein: " + ItemCatalog.size());
        ItemCatalog.Info drive = ItemCatalog.get("AE2_Drive");
        assertNotNull(drive);
        assertEquals("drive", drive.kind());
        assertEquals(BlockAccess.Dir.SOUTH, drive.front());
        ItemCatalog.Info terminal = ItemCatalog.get("AE2_Terminal");
        assertNotNull(terminal);
        assertTrue(terminal.part());
        assertEquals(BlockAccess.Dir.UP, terminal.front());
    }

    @Test
    void everyNetworkKindIsKnownToTheCore() {
        for (String id : new String[]{"AE2_Controller", "AE2_Drive", "AE2_ME_Chest", "AE2_Terminal", "AE2_Import_Bus",
                "AE2_Export_Bus", "AE2_Storage_Bus", "AE2_Energy_Cell", "AE2_Inscriber", "AE2_Charger",
                "AE2_Cable_Interface", "AE2_16k_Crafting_Storage", "AE2_Wireless_Access_Point"}) {
            ItemCatalog.Info info = ItemCatalog.get(id);
            assertNotNull(info, id);
            assertTrue(NodeType.isNetworked(info.kind()), id + " / " + info.kind());
            assertNotNull(NodeType.forBlock(info.kind()), id);
        }
        assertFalse(NodeType.isNetworked("condenser"));
    }

    @Test
    void cellItemsAreKnownToTheCore() {
        for (String id : new String[]{"AE2_Item_Storage_Cell_1k", "AE2_Item_Storage_Cell_256k", "AE2_Creative_Storage_Cell"}) {
            assertNotNull(ItemCatalog.get(id), id);
            assertTrue(CellDefinition.isCell(id), id);
        }
    }

    @Test
    void machineRecipesParse() {
        InscriberRecipes inscriber = InscriberRecipes.fromJson(ItemCatalog.resource("inscriber.json"));
        assertTrue(inscriber.getRecipes().size() >= 10, "Inscriber-Rezepte: " + inscriber.getRecipes().size());
        ChargerRecipes charger = ChargerRecipes.fromJson(ItemCatalog.resource("charger.json"));
        assertFalse(charger.getRecipes().isEmpty());
    }

    @Test
    void creativeCellProvidesConfiguredItemsEndlessly() {
        var data = new de.fellurion.ae2.core.cells.CellItemData()
                .setConfig(java.util.List.of(de.fellurion.ae2.core.stacks.AEItemKey.of("Ingredient_Bar_Iron")));
        // Round-Trip über das JSON in den Item-Metadaten (so liegt die Zelle im Drive)
        var restored = de.fellurion.ae2.core.cells.CellItemData.fromJson(data.toJson());
        var cell = de.fellurion.ae2.core.cells.StorageCells.getCellInventory(
                de.fellurion.ae2.core.cells.CellDefinition.CREATIVE_CELL_ID, restored, () -> { });
        assertNotNull(cell);
        var iron = de.fellurion.ae2.core.stacks.AEItemKey.of("Ingredient_Bar_Iron");
        var src = de.fellurion.ae2.core.storage.IActionSource.empty();
        assertEquals(1_000_000, cell.extract(iron, 1_000_000, de.fellurion.ae2.core.config.Actionable.MODULATE, src));
        assertEquals(1_000_000, cell.extract(iron, 1_000_000, de.fellurion.ae2.core.config.Actionable.MODULATE, src));
        assertEquals(0, cell.extract(de.fellurion.ae2.core.stacks.AEItemKey.of("Ingredient_Bar_Gold"), 5,
                de.fellurion.ae2.core.config.Actionable.MODULATE, src));
        assertTrue(cell.getAvailableStacks().get(iron) > 0);
    }

    @Test
    void smartCableChannelLevelsMatchAssetContract() {
        // Smart (8 Kanäle): 0→0, 1–2→1, 3–4→2, 5–7→3, 8→4
        int[] smart = {0, 1, 1, 2, 2, 3, 3, 3, 4};
        for (int used = 0; used <= 8; used++) {
            assertEquals(smart[used], WorldNetworks.channelLevel(used, 8), "smart " + used);
        }
        // Dense (32): 1–8→1, 9–16→2, 17–31→3, 32→4
        assertEquals(1, WorldNetworks.channelLevel(8, 32));
        assertEquals(2, WorldNetworks.channelLevel(9, 32));
        assertEquals(3, WorldNetworks.channelLevel(31, 32));
        assertEquals(4, WorldNetworks.channelLevel(32, 32));
        assertEquals(4, WorldNetworks.channelLevel(3, 0)); // unbegrenzter Kanalmodus: an/aus
    }

    @Test
    void cablePositionPacking() {
        long p = BlockAccess.pack(-123, 77, 456);
        assertEquals(-123, BlockAccess.unpackX(p));
        assertEquals(77, BlockAccess.unpackY(p));
        assertEquals(456, BlockAccess.unpackZ(p));
    }
}
