/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.Devices;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.logging.Level;

/** Verdrahtet Plugin, Kernlogik und Hytale-Systeme. */
public final class HytaleBridge {

    private HytaleBridge() {
    }

    public static void registerComponentType(ComponentType<ChunkStore, AE2BlockComponent> type) {
        AE2BlockComponent.setComponentType(type);
    }

    public static void setup(JavaPlugin plugin) {
        ItemCatalog.load();
        de.fellurion.ae2.hytale.advancements.AE2Advancements.setup(plugin);
        Devices.init();
        de.fellurion.ae2.hytale.ui.TerminalOpener.setup(plugin);
        de.fellurion.ae2.hytale.hud.AE2Hud.setup(plugin);
        de.fellurion.ae2.hytale.security.AE2Security.setup(plugin);
        ItemKeys.installDurabilityResolver();
        // Verschachtelte Zellen: der Zelleninhalt steckt in den Item-Metadaten unter "AE2Cell" (siehe ItemKeys)
        StorageCells.setCellDataResolver(key -> {
            if (key.nbt() == null) {
                return new CellItemData();
            }
            try {
                BsonDocument doc = BsonDocument.parse(key.nbt());
                if (doc.containsKey("m")) {
                    BsonValue v = doc.getDocument("m").get(CellHostDevice.CELL_KEY);
                    if (v != null && v.isString()) {
                        return CellItemData.fromJson(v.asString().getValue());
                    }
                }
            } catch (RuntimeException ignored) {
                // keine Zellendaten
            }
            return new CellItemData();
        });
        plugin.getCommandRegistry().registerCommand(new AE2Command());
        plugin.getLogger().at(Level.INFO).log("AE2: %d Items/Blöcke aus ae2data geladen", ItemCatalog.size());
    }
}
