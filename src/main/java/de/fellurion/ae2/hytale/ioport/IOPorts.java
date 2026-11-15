/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ioport;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.ui.DeviceConfigPage;

/** ME IO Port. */
public final class IOPorts {

    /** Block-Art ({@code Kind}) des IO-Ports. */
    public static final String KIND = "io_port";

    private IOPorts() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        Devices.register(KIND, (nets, node) -> new IOPortDevice());
        // Benutzen öffnet die zwölf Zellenplätze; geduckt die Einstellungen und Karten (AE2: beides in einem Fenster)
        UseHandlers.register(KIND, ctx -> {
            if (UseHandlers.isCrouching(ctx) && DeviceConfigPage.open(ctx)) {
                return;
            }
            AE2UseBlockInteraction.openContainerWindow(ctx);
        });
        InfoProviders.register(IOPortHud::describe, KIND);
        AE2Command.registerSub(new IOPortCommands());
    }
}
