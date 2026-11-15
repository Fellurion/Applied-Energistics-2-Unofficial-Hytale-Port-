/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.core.automation.FluidPickupStrategy;
import de.fellurion.ae2.core.automation.FluidPlacementStrategy;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.plane.Planes;

import java.util.logging.Level;

/**
 * Flüssigkeiten im ME-Netz.
 *
 * <ul>
 *   <li>{@link FluidTypes}: welche Flüssigkeiten des Spiels lagerbar sind und womit sie angezeigt werden;
 *   {@link FluidContainers}: Behälter-Items (der Eimer für Wasser); {@link FluidText}: Mengen in Eimern.</li>
 *   <li>Terminal ({@code hytale.ui.TerminalView}): Flüssigkeiten im Gitter, Eimer füllen und leeren
 *   ({@link FluidTransfers}).</li>
 *   <li>{@link TankDevice}: der Sky Stone Tank, auch als Ziel für Busse und den Fluid-P2P-Tunnel
 *   ({@code hytale.device.StorageTarget}, {@code hytale.p2p.FluidTunnel}).</li>
 *   <li>{@link PlaneFluids}: Flüssigkeiten vor Annihilation und Formation Plane (Quelle aufnehmen bzw. setzen).</li>
 *   <li>{@link FluidCommands}: {@code /ae2 fluid …} zum Prüfen ohne Spieler.</li>
 * </ul>
 * Fluid-Zellen brauchen nichts Eigenes: Der Kern lagert darin, sobald die Schlüssel von hier kommen.
 */
public final class Fluids {

    private Fluids() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        HytaleLogger log = plugin.getLogger();
        FluidConfig.load(plugin.getDataDirectory(), (text, error) -> log.at(Level.WARNING).withCause(error).log(text));

        // Sky Stone Tank: 16 Eimer einer Flüssigkeit; benutzen = Behälter in der Hand leeren bzw. füllen
        Devices.register(TankDevice.KIND, (nets, node) -> new TankDevice());
        UseHandlers.register(TankDevice.KIND, TankDevice::use);
        InfoProviders.register(TankDevice::describe, TankDevice.KIND);

        // Planes: Annihilation Plane nimmt die Quelle vor sich auf, Formation Plane setzt eine (AE2: je Schlüsselart
        // eine Strategie; die für Items meldet der Bereich der Planes an)
        Planes.registerPickupStrategy(AEKeyType.FLUIDS, target -> new FluidPickupStrategy(new PlaneFluids(target)));
        Planes.registerPlacementStrategy(AEKeyType.FLUIDS, target -> new FluidPlacementStrategy(new PlaneFluids(target)));

        AE2Command.registerSub(new FluidCommands());
    }
}
