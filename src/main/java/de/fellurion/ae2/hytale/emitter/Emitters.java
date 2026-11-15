/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.emitter;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.ui.DeviceConfigPage;

import java.util.logging.Level;

/** Level Emitter, Energy Level Emitter, Light Detector, Storage Monitor und Conversion Monitor. */
public final class Emitters {

    private Emitters() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        EmitterSettings.load(plugin.getDataDirectory(),
                (message, cause) -> plugin.getLogger().at(Level.WARNING).withCause(cause).log("%s", message));
        MonitorDisplay.setup(plugin);

        Devices.register("level_emitter", (nets, node) -> new LevelEmitterDevice(nets, node, false));
        Devices.register("energy_level_emitter", (nets, node) -> new LevelEmitterDevice(nets, node, true));
        Devices.register("light_detector", LightDetectorDevice::new);
        Devices.register("storage_monitor", (nets, node) -> new MonitorDevice(nets, node, false));
        Devices.register("conversion_monitor", (nets, node) -> new MonitorDevice(nets, node, true));

        // Emitter: Einstellungsseite (AE2: StorageLevelEmitterMenu / EnergyLevelEmitterMenu); Monitore: Gesten
        UseHandlers.register("level_emitter", DeviceConfigPage::open);
        UseHandlers.register("energy_level_emitter", DeviceConfigPage::open);
        UseHandlers.register("storage_monitor", MonitorUse::use);
        UseHandlers.register("conversion_monitor", MonitorUse::use);
        UseHandlers.register("light_detector", ctx -> {
            // nichts zu bedienen; die Blickanzeige zeigt Helligkeit und Signal
        });

        EmitterHud.register();
        EmitterCommands.register();
    }
}
