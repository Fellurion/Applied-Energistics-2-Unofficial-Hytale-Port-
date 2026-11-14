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
        super("ae2", "ae2cmd.root.desc");
        requireNoPermission(); // Unterbefehle regeln ihre Rechte selbst (Admin: ae2.admin)
        addSubCommand(new Info());
        addSubCommand(new LanguageCommand());
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
            super("perf", "ae2cmd.perf.desc");
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            WorldNetworks nets = WorldNetworks.of(world);
            for (Message line : nets.perf().report(nets)) {
                context.sendMessage(line);
            }
            nets.perf().startDetail(AE2Perf.WINDOW);
            context.sendMessage(Message.translation("ae2cmd.perf.detailStarted"));
        }
    }

    /** {@code /ae2 language en|de}: AE2-Sprache des Spielers (Standard Englisch). */
    private static final class LanguageCommand extends com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand {
        private final RequiredArg<String> language;

        LanguageCommand() {
            super("language", "ae2cmd.language.desc");
            addAliases("sprache");
            requireNoPermission();
            language = withRequiredArg("en|de", "ae2cmd.arg.language", ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull com.hypixel.hytale.component.Ref<EntityStore> ref,
                               @Nonnull com.hypixel.hytale.server.core.universe.PlayerRef playerRef, @Nonnull World world) {
            String lang = de.fellurion.ae2.hytale.lang.AE2Language.parse(context.get(language));
            if (lang == null) {
                context.sendMessage(Message.translation("ae2cmd.language.unknown"));
                return;
            }
            de.fellurion.ae2.hytale.lang.AE2Language.set(playerRef, lang);
            // kommt nach den neuen Texten beim Client an und erscheint daher schon in der neuen Sprache
            context.sendMessage(Message.translation("ae2cmd.language.set"));
        }
    }

    private static final class Info extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> pos;

        Info() {
            super("info", "ae2cmd.info.desc");
            requireNoPermission();
            this.pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
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

    /** Netz-Zusammenfassung für die Position (Chat/Konsole), übersetzt ({@code ae2cmd.info.*}). */
    public static Message describe(WorldNetworks nets, int x, int y, int z) {
        WorldNetworks.Node node = nets.node(x, y, z);
        if (node == null) {
            return Message.translation("ae2cmd.info.none").param("x", x).param("y", y).param("z", z)
                    .param("count", nets.size());
        }
        Message block = node.item() == null ? Message.raw(node.kind())
                : Message.translation("ae2.items." + node.item().id() + ".name");
        GridNode g = node.gridNode();
        if (g == null || g.getGrid() == null) {
            return Message.translation("ae2cmd.info.notInNetwork").param("block", block);
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
        return Message.translation("ae2cmd.info.network")
                .param("block", block)
                .param("online", Message.translation(g.isOnline() ? "ae2cmd.info.online" : "ae2cmd.info.offline"))
                .param("blocks", grid.size())
                .param("channels", channels)
                .param("devices", devices)
                .param("stored", TerminalPage.formatAmount((long) energy.getStoredPower()))
                .param("max", TerminalPage.formatAmount((long) energy.getMaxStoredPower()))
                .param("usage", String.format(Locale.ROOT, "%.1f", energy.getAvgPowerUsage()))
                .param("power", Message.translation(energy.isNetworkPowered() ? "ae2cmd.info.powered" : "ae2cmd.info.unpowered"))
                .param("types", grid.getStorageService().getCachedInventory().size());
    }
}
