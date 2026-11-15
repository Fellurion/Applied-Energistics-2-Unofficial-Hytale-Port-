/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.p2p;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.p2p.MEP2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PFrequency;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PTunnelType;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.items.MemoryCardTarget;
import de.fellurion.ae2.hytale.signal.Signals;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /ae2 p2p …}: Admin-/Testbefehle (Berechtigung {@code ae2.admin}) für P2P-Tunnel, Toggle Bus und das
 * AE2-Signal – damit lässt sich alles auch ohne Memory Card und ohne Spieler prüfen.
 * <ul>
 *   <li>{@code link <x y z Eingang> <x y z Ausgang>} – verknüpft zwei Tunnel wie mit der Memory Card: am ersten
 *       speichern, auf den zweiten übertragen</li>
 *   <li>{@code info x y z} – Art, Rolle, Frequenz und Zustand eines Tunnels bzw. Toggle Bus</li>
 *   <li>{@code attune x y z <Art|ItemId>} – stimmt einen Tunnel ab (Art: me, item, fluid, fe, light, redstone)</li>
 *   <li>{@code toggle x y z} – legt den eingebauten Schalter eines Toggle Bus um</li>
 *   <li>{@code signal x y z on|off} – lässt die Position das AE2-Signal senden (Test-Signalquelle)</li>
 *   <li>{@code place x y z <BlockId> <Richtung>} – setzt einen Block mit der Vorderseite in eine Richtung (up, down,
 *       north, south, east, west); {@code block set} kann nicht drehen</li>
 *   <li>{@code contents x y z} – Inhalt des Containers an der Position</li>
 * </ul>
 */
final class P2PCommands extends AbstractCommandCollection {

    P2PCommands() {
        super("p2p", "ae2p2p.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new Link());
        addSubCommand(new Info());
        addSubCommand(new Attune());
        addSubCommand(new Toggle());
        addSubCommand(new Signal());
        addSubCommand(new Place());
        addSubCommand(new Contents());
    }

    private abstract static class Sub extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        Sub(String name, String description, String posName, String posDescription) {
            super(name, description);
            requirePermission("ae2.admin");
            pos = withRequiredArg(posName, posDescription, ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, store, WorldNetworks.of(world), p.x, p.y, p.z));
        }

        abstract Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z);
    }

    private static Message noTunnel(int x, int y, int z) {
        return Message.translation("ae2p2p.cmd.noTunnel").param("x", x).param("y", y).param("z", z);
    }

    private static final class Link extends Sub {
        private final RequiredArg<RelativeIntPosition> output;

        Link() {
            super("link", "ae2p2p.cmd.link.desc", "input", "ae2p2p.cmd.arg.input");
            output = withRequiredArg("output", "ae2p2p.cmd.arg.output", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            Vector3i o = ctx.get(output).getBlockPosition(ctx, store);
            WorldNetworks.Node in = nets.node(x, y, z);
            WorldNetworks.Node out = nets.node(o.x, o.y, o.z);
            if (in == null || !(in.device() instanceof TunnelDevice inDevice)) {
                return noTunnel(x, y, z);
            }
            if (out == null || out == in || !(out.device() instanceof TunnelDevice outDevice)) {
                return noTunnel(o.x, o.y, o.z);
            }
            // Genau der Weg der Memory Card: speichern am Eingang, übertragen auf den Ausgang
            MemoryCardData card = inDevice.saveToCard(nets, in);
            MemoryCardTarget.Result result = outDevice.loadFromCard(nets, out, card);
            return Message.translation(result == MemoryCardTarget.Result.LOADED ? "ae2p2p.cmd.link.done"
                            : "ae2p2p.cmd.link.failed")
                    .param("frequency", P2PFrequency.toHexString(inDevice.logic().getFrequency()))
                    .param("type", inDevice.type().id());
        }
    }

    private static final class Info extends Sub {
        Info() {
            super("info", "ae2p2p.cmd.info.desc", "x y z", "ae2cmd.arg.position");
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node != null && node.device() instanceof ToggleBusDevice bus) {
                return Message.translation("ae2p2p.cmd.info.toggle")
                        .param("state", bus.logic().isEnabled() ? "connected" : "separated")
                        .param("signal", String.valueOf(bus.signalled()))
                        .param("manual", String.valueOf(bus.manual()));
            }
            if (node == null || !(node.device() instanceof TunnelDevice device)) {
                return noTunnel(x, y, z);
            }
            P2PTunnelLogic logic = device.logic();
            GridNode g = logic.getMainNode();
            Grid grid = g == null ? null : g.getGrid();
            int outputs = grid == null ? 0
                    : grid.getP2PService().getOutputs(logic.getFrequency(), logic.getType()).size();
            String extra = device.behaviour().debugState();
            if (logic instanceof MEP2PTunnelLogic me) {
                GridNode outer = me.getOuterNode();
                extra = "connections=" + me.getConnectionCount() + " frontNetwork="
                        + (outer == null || outer.getGrid() == null ? 0 : outer.getGrid().size());
            }
            return Message.translation("ae2p2p.cmd.info.tunnel")
                    .param("type", device.type().id())
                    .param("role", logic.isOutput() ? "output" : "input")
                    .param("frequency", P2PFrequency.toHexString(logic.getFrequency()))
                    .param("state", g != null && g.isOnline() ? "online" : "offline")
                    .param("outputs", outputs)
                    .param("input", String.valueOf(logic.getInput() != null))
                    .param("extra", extra);
        }
    }

    private static final class Attune extends Sub {
        private final RequiredArg<String> what;

        Attune() {
            super("attune", "ae2p2p.cmd.attune.desc", "x y z", "ae2cmd.arg.position");
            what = withRequiredArg("type|itemId", "ae2p2p.cmd.arg.type", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null || !(node.device() instanceof TunnelDevice device)) {
                return noTunnel(x, y, z);
            }
            String arg = ctx.get(what);
            P2PTunnelType type = P2PTunnelType.fromId(arg);
            if (type == null) {
                type = TunnelAttunement.byTriggerItem(arg);
            }
            if (type == null) {
                return Message.translation("ae2p2p.cmd.attune.unknown").param("what", arg);
            }
            boolean changed = device.attune(nets, node, type);
            return Message.translation(changed ? "ae2p2p.cmd.attune.done" : "ae2p2p.cmd.attune.same")
                    .param("type", type.id());
        }
    }

    private static final class Toggle extends Sub {
        Toggle() {
            super("toggle", "ae2p2p.cmd.toggle.desc", "x y z", "ae2cmd.arg.position");
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null || !(node.device() instanceof ToggleBusDevice bus)) {
                return Message.translation("ae2p2p.cmd.noToggle").param("x", x).param("y", y).param("z", z);
            }
            boolean manual = bus.toggleManual(node);
            return Message.translation("ae2p2p.cmd.toggle.done").param("manual", String.valueOf(manual))
                    .param("state", bus.logic().isEnabled() ? "connected" : "separated");
        }
    }

    private static final class Place extends Sub {
        private final RequiredArg<String> block;
        private final RequiredArg<String> facing;

        Place() {
            super("place", "ae2p2p.cmd.place.desc", "x y z", "ae2cmd.arg.position");
            block = withRequiredArg("blockId", "ae2p2p.cmd.arg.block", ArgTypes.STRING);
            facing = withRequiredArg("facing", "ae2p2p.cmd.arg.facing", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            String id = ctx.get(block);
            Dir dir;
            try {
                dir = Dir.valueOf(ctx.get(facing).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Message.translation("ae2p2p.cmd.place.failed").param("block", id);
            }
            ItemCatalog.Info info = ItemCatalog.get(id);
            Dir front = info == null || info.front() == null ? Dir.UP : info.front();
            boolean ok = BlockAccess.setBlock(nets.world(), x, y, z, id, BlockAccess.rotationFacing(dir, front), 0);
            return Message.translation(ok ? "ae2p2p.cmd.place.done" : "ae2p2p.cmd.place.failed").param("block", id)
                    .param("facing", dir.name().toLowerCase(Locale.ROOT)).param("x", x).param("y", y).param("z", z);
        }
    }

    private static final class Contents extends Sub {
        Contents() {
            super("contents", "ae2p2p.cmd.contents.desc", "x y z", "ae2cmd.arg.position");
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(nets.world(), x, y, z);
            if (c == null) {
                return Message.translation("ae2p2p.cmd.contents.none").param("x", x).param("y", y).param("z", z);
            }
            Map<String, Integer> totals = new TreeMap<>();
            for (short slot = 0; slot < c.getCapacity(); slot++) {
                ItemStack stack = c.getItemStack(slot);
                if (!ItemStack.isEmpty(stack)) {
                    totals.merge(stack.getItemId(), stack.getQuantity(), Integer::sum);
                }
            }
            StringBuilder sb = new StringBuilder();
            totals.forEach((id, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(' ').append(id));
            return Message.translation("ae2p2p.cmd.contents.list").param("slots", (int) c.getCapacity())
                    .param("items", sb.isEmpty() ? "-" : sb.toString());
        }
    }

    private static final class Signal extends Sub {
        private final RequiredArg<String> state;

        Signal() {
            super("signal", "ae2p2p.cmd.signal.desc", "x y z", "ae2cmd.arg.position");
            state = withRequiredArg("on|off", "ae2p2p.cmd.arg.onoff", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            boolean on = "on".equalsIgnoreCase(ctx.get(state));
            Signals.of(nets.world()).setEmitting(x, y, z, on);
            return Message.translation(on ? "ae2p2p.cmd.signal.on" : "ae2p2p.cmd.signal.off")
                    .param("x", x).param("y", y).param("z", z);
        }
    }
}
