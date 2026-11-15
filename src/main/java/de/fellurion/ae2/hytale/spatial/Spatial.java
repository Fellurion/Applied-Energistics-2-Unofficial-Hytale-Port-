/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Spatial IO: Pylone, Spatial IO Port, Spatial Anchor und Spatial-Zellen.
 *
 * <p>AE2 lagert ein Gebiet in eine eigene Dimension aus. Dieser Port erzeugt keine zweite Welt: der Inhalt einer
 * Zelle (ihr „Plot“) ist eine Datei im Datenordner des Plugins ({@link SpatialStore}), ein Transfer tauscht die
 * Blöcke des Gebiets mit dem Inhalt der Datei ({@link RegionSwap}). Regeln und Zahlen (Pylon-Linien, Gültigkeit,
 * Effizienz, Energiebedarf, Zellengrößen, Anker-Verbrauch) stehen im Kern unter {@code core.spatial}.
 */
public final class Spatial {

    private static volatile SpatialStore store;
    private static volatile HytaleLogger logger;
    private static MoveRules rules;
    private static int rulesVersion = -1;

    private Spatial() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        HytaleLogger log = plugin.getLogger();
        logger = log;
        BiConsumer<String, Throwable> warn = (message, cause) -> {
            if (cause == null) {
                log.at(Level.WARNING).log("%s", message);
            } else {
                log.at(Level.WARNING).withCause(cause).log("%s", message);
            }
        };
        SpatialStore s = new SpatialStore(plugin.getDataDirectory().resolve("spatial"), warn);
        s.load();
        store = s;

        Devices.register("spatial_pylon", PylonDevice::new);
        Devices.register("spatial_io_port", (nets, node) -> new IOPortDevice(s));
        Devices.register("spatial_anchor", (nets, node) -> new AnchorDevice(nets));

        // Geduckt benutzen startet den Transfer, sonst öffnet sich das Fenster mit den zwei Slots
        UseHandlers.register("spatial_io_port", ctx -> {
            if (UseHandlers.isCrouching(ctx)) {
                var player = ctx.playerRef();
                SpatialTransfers.request(WorldNetworks.of(ctx.world()), ctx.pos().x, ctx.pos().y, ctx.pos().z,
                        outcome -> player.sendMessage(outcome.message()));
            } else {
                AE2UseBlockInteraction.openContainerWindow(ctx);
            }
        });

        InfoProviders.register(SpatialHud::pylon, "spatial_pylon");
        InfoProviders.register(SpatialHud::ioPort, "spatial_io_port");
        InfoProviders.register(SpatialHud::anchor, "spatial_anchor");
        ItemTooltips.addSection(SpatialCells::tooltip);
        AE2Command.registerSub(new SpatialCommands());
        log.at(Level.INFO).log("AE2: spatial storage in %s (%d plots)", s.directory(), s.plots().getPlots().size());
    }

    static SpatialStore store() {
        return store;
    }

    static HytaleLogger logger() {
        return logger;
    }

    /** Die Sperrregeln zum aktuellen Stand der Einstellungen. */
    static synchronized MoveRules rules() {
        SpatialStore s = store;
        if (rules == null || rulesVersion != s.configVersion()) {
            rules = new MoveRules(s.config());
            rulesVersion = s.configVersion();
        }
        return rules;
    }
}
