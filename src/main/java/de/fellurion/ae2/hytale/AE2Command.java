/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.hytale.ui.TerminalPage;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.Locale;

/** {@code /ae2 info x y z} – Netzwerk-Infos zu einem AE2-Block (wie AE2s Network Tool). */
public final class AE2Command extends AbstractCommandCollection {

    public AE2Command() {
        super("ae2", "Applied Energistics 2");
        addSubCommand(new Info());
        addSubCommand(de.fellurion.ae2.hytale.advancements.AE2Advancements.createCommand());
        // Admin-/Testbefehle (Berechtigung ae2.admin)
        addSubCommand(new AE2DebugCommands.SetSlot());
        addSubCommand(new AE2DebugCommands.Pattern());
        addSubCommand(new AE2DebugCommands.Insert());
        addSubCommand(new AE2DebugCommands.Craft());
        addSubCommand(new AE2DebugCommands.Cpus());
        addSubCommand(new AE2DebugCommands.Count());
        addSubCommand(new AE2DebugCommands.KeepLoaded());
        addSubCommand(new Perf());
        addSubCommand(new AE2DebugCommands.Stress());
    }

    /** {@code /ae2 perf}: Tick-Zeiten des AE2-Takts dieser Welt; startet zugleich 10 s Messung je Block-Art. */
    private static final class Perf extends AbstractWorldCommand {
        Perf() {
            super("perf", "AE2-Leistung dieser Welt (Tick-Zeiten)");
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            WorldNetworks nets = WorldNetworks.of(world);
            for (String line : nets.perf().report(nets)) {
                context.sendMessage(Message.raw("[AE2] " + line));
            }
            nets.perf().startDetail(AE2Perf.WINDOW);
            context.sendMessage(Message.raw("[AE2] Messung je Block-Art läuft 10 s – danach /ae2 perf erneut."));
        }
    }

    private static final class Info extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> pos;

        Info() {
            super("info", "Zeigt das ME-Netz eines AE2-Blocks");
            this.pos = withRequiredArg("x y z", "Position", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = context.get(pos).getBlockPosition(context, store);
            WorldNetworks nets = WorldNetworks.of(world);
            WorldNetworks.Node node = nets.node(p.x, p.y, p.z);
            // Fremde Netze: nur der Besitzer (bzw. eingetragene Spieler) sieht die Netz-Infos
            if (context.isPlayer() && node != null
                    && !de.fellurion.ae2.hytale.security.AE2Security.canUse(nets, node, context.sender().getUuid())) {
                context.sendMessage(de.fellurion.ae2.hytale.security.AE2Security.denyMessage(nets, node));
                return;
            }
            context.sendMessage(describe(nets, p.x, p.y, p.z));
        }
    }

    /** Netz-Zusammenfassung für die Position (Chat/Konsole). */
    public static Message describe(WorldNetworks nets, int x, int y, int z) {
        WorldNetworks.Node node = nets.node(x, y, z);
        if (node == null) {
            return Message.raw(String.format(Locale.ROOT, "[AE2] Kein AE2-Block bei %d %d %d (%d AE2-Blöcke in der Welt)",
                    x, y, z, nets.size()));
        }
        GridNode g = node.gridNode();
        String state = BlockAccess.currentState(nets.world(), x, y, z);
        if (g == null || g.getGrid() == null) {
            return Message.raw(String.format(Locale.ROOT, "[AE2] %s (%s) – nicht im ME-Netz, State %s", node.kind(),
                    node.item() == null ? "?" : node.item().id(), state));
        }
        Grid grid = g.getGrid();
        var energy = grid.getEnergyService();
        int channels = 0, devices = 0;
        for (GridNode n : grid.getNodes()) {
            if (n.getType().requiresChannel()) {
                devices++;
                if (n.meetsChannelRequirements()) {
                    channels++;
                }
            }
        }
        return Message.raw(String.format(Locale.ROOT,
                "[AE2] %s · State %s · online=%s · Netz: %d Blöcke · Kanäle %d/%d · Energie %s/%s AE · Verbrauch %.1f AE/t · %s · %s · Items %d Typen",
                node.kind(), state, g.isOnline(), grid.size(), channels, devices,
                TerminalPage.formatAmount((long) energy.getStoredPower()),
                TerminalPage.formatAmount((long) energy.getMaxStoredPower()), energy.getAvgPowerUsage(),
                energy.isNetworkPowered() ? "versorgt" : "ohne Strom", grid.getPathingService().getControllerState(),
                grid.getStorageService().getCachedInventory().size()));
    }
}
