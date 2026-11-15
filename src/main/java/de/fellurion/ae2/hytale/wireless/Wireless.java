/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.items.ItemTooltips;
import de.fellurion.ae2.hytale.items.ItemUse;
import de.fellurion.ae2.hytale.ui.CellWorkbenchPage;

/** Wireless Access Point, Wireless Terminals und tragbare Zellen. */
public final class Wireless {

    private Wireless() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        // ME Wireless Access Point: Booster, Reichweite, Verbrauch; benutzen = Booster-Slot bzw. Terminal verknüpfen
        Devices.register(WirelessAccessPointDevice.KIND, (nets, node) -> new WirelessAccessPointDevice());
        UseHandlers.register(WirelessAccessPointDevice.KIND, WirelessTerminals::useAccessPoint);
        InfoProviders.register(WirelessAccessPointDevice::describe, WirelessAccessPointDevice.KIND);

        // Wireless Terminals: Rechtsklick öffnet bzw. verknüpft; Tooltip „Verknüpft“ / „Nicht verknüpft“
        ItemUse.register("AE2_Wireless_Terminal", ctx -> WirelessTerminals.use(ctx, false));
        ItemUse.register("AE2_Wireless_Crafting_Terminal", ctx -> WirelessTerminals.use(ctx, true));
        ItemTooltips.addSection(WirelessLink::tooltip);

        // Tragbare Zellen: Rechtsklick öffnet den eigenen Inhalt
        for (String id : PortableCells.openableCells()) {
            ItemUse.register(id, PortableCells::use);
        }

        // Zellen-Werkbank: nimmt neben Zellen auch die Items mit Kartenplätzen auf (Karten für Terminals und Werkzeuge)
        Devices.register("cell_workbench", (nets, node) -> new WorkbenchSlot());

        HeldSessions.setup(plugin);
        WirelessCommands.all().forEach(AE2Command::registerSub);
    }

    /** Slot der Zellen-Werkbank: Speicherzellen und Items mit Kartenplätzen ({@link CellWorkbenchPage#accepts}). */
    private static final class WorkbenchSlot implements Device {
        @Override
        public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
            ItemContainer c = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
            if (c == null) {
                return;
            }
            for (short s = 0; s < c.getCapacity(); s++) {
                c.setSlotFilter(FilterActionType.ADD, s, (action, container, slot, stack) ->
                        ItemStack.isEmpty(stack) || CellWorkbenchPage.accepts(stack.getItemId()));
            }
        }
    }
}
