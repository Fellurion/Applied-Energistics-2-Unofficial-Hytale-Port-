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
        de.fellurion.ae2.hytale.lang.AE2Language.setup(plugin);
        de.fellurion.ae2.hytale.advancements.AE2Advancements.setup(plugin);
        Devices.init();
        de.fellurion.ae2.hytale.transform.InWorldTransforms.setup(plugin);
        de.fellurion.ae2.hytale.ui.TerminalOpener.setup(plugin);
        de.fellurion.ae2.hytale.hud.AE2Hud.setup(plugin);
        de.fellurion.ae2.hytale.security.AE2Security.setup(plugin);
        de.fellurion.ae2.hytale.meteorite.Meteorites.setup(plugin);
        // Bereiche, die ihre Geräte und Aktionen selbst anmelden (Devices.register, UseHandlers.register, ItemUse.register)
        de.fellurion.ae2.hytale.tools.Tools.setup(plugin);
        de.fellurion.ae2.hytale.gadgets.Gadgets.setup(plugin);
        de.fellurion.ae2.hytale.emitter.Emitters.setup(plugin);
        de.fellurion.ae2.hytale.ioport.IOPorts.setup(plugin);
        de.fellurion.ae2.hytale.plane.Planes.setup(plugin);
        de.fellurion.ae2.hytale.p2p.P2P.setup(plugin);
        de.fellurion.ae2.hytale.wireless.Wireless.setup(plugin);
        de.fellurion.ae2.hytale.spatial.Spatial.setup(plugin);
        de.fellurion.ae2.hytale.quantum.Quantum.setup(plugin);
        de.fellurion.ae2.hytale.fluid.Fluids.setup(plugin);
        de.fellurion.ae2.hytale.guide.Guide.setup(plugin);
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
        plugin.getLogger().at(Level.INFO).log("AE2: loaded %d items/blocks from ae2data", ItemCatalog.size());
    }
}
