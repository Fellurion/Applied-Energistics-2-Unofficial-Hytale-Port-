/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.core.machines.InscriberRecipes;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import java.util.logging.Level;

/**
 * Werkzeuge: Schraubenschlüssel, Network Tool, Memory Card, Schneidmesser und Namenspresse.
 *
 * <ul>
 *   <li>{@link ToolItems} – was der Rechtsklick mit dem jeweiligen Werkzeug tut</li>
 *   <li>{@link Wrenches} – drehen und abbauen, {@link MemoryCards} – Einstellungen speichern und übertragen</li>
 *   <li>{@link NetworkStatusPage}, {@link ToolboxPage}, {@link KnifePage} – die Seiten</li>
 *   <li>{@link NamePresses} – Namen für den Inscriber</li>
 *   <li>{@link ToolCommands} – {@code /ae2 tool …}, Testbefehle für Admins</li>
 * </ul>
 */
public final class Tools {

    private Tools() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        ToolItems.register();
        ItemTooltips.addSection(ToolItems::cardTooltip);
        ItemTooltips.addSection(Toolbox::tooltip);
        ItemTooltips.addSection(NamePresses::tooltip);
        // Der Inscriber benennt mit einer Namenspresse um; wie Namen gelesen und geschrieben werden, steht hier
        InscriberRecipes.setNameHandler(NamePresses.INSTANCE);
        AE2Command.registerSub(new ToolCommands());
        try {
            plugin.getChunkStoreRegistry().registerSystem(new ToolPages.Refresher());
        } catch (RuntimeException | LinkageError e) {
            // Ohne den Takt bleibt der Netzwerkstatus auf dem Stand vom Öffnen
            plugin.getLogger().at(Level.WARNING).withCause(e).log("AE2: the network status page will not refresh");
        }
    }
}
