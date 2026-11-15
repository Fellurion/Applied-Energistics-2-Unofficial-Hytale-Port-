/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.cells.BasicCellInventory;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.machines.SkyStoneTankLogic;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.plane.Planes;
import de.fellurion.ae2.hytale.ui.ConfigEdits;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.NetworkTerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalView;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * {@code /ae2 fluid …}: Admin-/Testbefehle für Flüssigkeiten (Berechtigung {@code ae2.admin}), damit sich alles ohne
 * Spieler über die Konsole prüfen lässt. Mengen in mB (1000 mB = ein Eimer = ein Quell-Block).
 * <ul>
 *   <li>{@code list} – die lagerbaren Flüssigkeiten mit Anzeige-Item und Behälter</li>
 *   <li>{@code insert x y z flüssigkeit mB} / {@code extract …} – ins Netz des Blocks einlagern bzw. entnehmen
 *   (kreativ, ohne Energie)</li>
 *   <li>{@code count x y z flüssigkeit} – Menge im Netz</li>
 *   <li>{@code cells x y z} – Bytes, Typen und Inhalt der Zellen eines Laufwerks oder einer ME-Truhe</li>
 *   <li>{@code tank x y z} – Inhalt eines Sky Stone Tank; {@code fill x y z flüssigkeit mB} / {@code drain x y z mB}
 *   füllen und leeren ihn nach seinen Regeln</li>
 *   <li>{@code filter x y z platz flüssigkeit|next|clear} – Flüssigkeit in einen Filterplatz legen (Busse …)</li>
 *   <li>{@code terminal x y z} – was ein Terminal an dieser Stelle an Flüssigkeiten zeigt</li>
 *   <li>{@code bucket x y z fill|empty flüssigkeit} – das Umfüllen eines Behälters am Terminal, mit einem
 *   Behälter in einem Probe-Feld statt im Inventar eines Spielers</li>
 *   <li>{@code moveslot x y z platz x2 y2 z2 platz2} – verschiebt einen Stapel zwischen zwei Block-Containern (eine
 *   Zelle aus dem Laufwerk nehmen und in ein anderes legen)</li>
 *   <li>{@code world x y z get|none|flüssigkeit} – Flüssigkeit an einer Stelle der Welt zeigen, entfernen oder mit
 *   vollem Pegel setzen (jede Flüssigkeit des Spiels, auch fließende; für Tests der Planes)</li>
 * </ul>
 */
final class FluidCommands extends AbstractCommandCollection {

    FluidCommands() {
        super("fluid", "ae2fluid.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new ListFluids());
        addSubCommand(new Insert());
        addSubCommand(new Extract());
        addSubCommand(new Count());
        addSubCommand(new Cells());
        addSubCommand(new Tank());
        addSubCommand(new Fill());
        addSubCommand(new Drain());
        addSubCommand(new Filter());
        addSubCommand(new Terminal());
        addSubCommand(new Bucket());
        addSubCommand(new MoveSlot());
        addSubCommand(new WorldFluid());
    }

    private abstract static class Sub extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> pos;

        Sub(String name, String description) {
            super(name, description);
            pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            run(ctx, world, WorldNetworks.of(world), p.x, p.y, p.z);
        }

        abstract void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z);

        /** Das Netz des Blocks; meldet sonst, dass dort keines ist. */
        @Nullable
        static Grid grid(CommandContext ctx, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node n = nets.node(x, y, z);
            Grid grid = n == null || n.gridNode() == null ? null : n.gridNode().getGrid();
            if (grid == null) {
                ctx.sendMessage(Message.translation("ae2cmd.noNetwork"));
            }
            return grid;
        }

        /** Die lagerbare Flüssigkeit mit dieser Kennung; meldet sonst, dass es sie nicht gibt. */
        @Nullable
        static AEFluidKey fluid(CommandContext ctx, String id) {
            AEFluidKey key = FluidTypes.key(id);
            if (key == null) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.unknown").param("fluid", id));
            }
            return key;
        }

        /** Der Tank an der Stelle; meldet sonst, dass dort keiner steht. */
        @Nullable
        static SkyStoneTankLogic tank(CommandContext ctx, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node n = nets.node(x, y, z);
            if (n != null && n.device() instanceof TankDevice tank) {
                return tank.logic();
            }
            ctx.sendMessage(Message.translation("ae2fluid.cmd.noTank").param("x", x).param("y", y).param("z", z));
            return null;
        }

        static Message tankState(SkyStoneTankLogic tank) {
            return Message.translation("ae2fluid.cmd.tank")
                    .param("fluid", tank.getFluid() == null ? "-" : tank.getFluid().fluidId())
                    .param("amount", tank.getAmount()).param("capacity", tank.getCapacity());
        }
    }

    /** „Water_Source=16000 Lava_Source=1000“ – nur Flüssigkeiten, nach Kennung sortiert. */
    private static String fluidsOf(KeyCounter stacks) {
        List<String> parts = new ArrayList<>();
        for (KeyCounter.Entry e : stacks) {
            if (e.getKey() instanceof AEFluidKey key && e.getLongValue() > 0) {
                parts.add(key.fluidId() + "=" + e.getLongValue());
            }
        }
        parts.sort(null);
        return parts.isEmpty() ? "-" : String.join(" ", parts);
    }

    private static final class ListFluids extends AbstractWorldCommand {
        ListFluids() {
            super("list", "ae2fluid.cmd.list.desc");
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            ctx.sendMessage(Message.translation("ae2fluid.cmd.list").param("count", FluidTypes.storable().size()));
            for (FluidTypes.Info info : FluidTypes.storable()) {
                FluidConfig.ContainerSpec container = FluidContainers.forFluid(info.id());
                ctx.sendMessage(Message.translation("ae2fluid.cmd.list.entry").param("fluid", info.id())
                        .param("icon", info.iconItem())
                        .param("container", container == null ? "-" : container.emptyItem() + "->"
                                + FluidContainers.filledItemId(container)));
            }
        }
    }

    private static final class Insert extends Sub {
        private final RequiredArg<String> fluid;
        private final RequiredArg<Integer> amount;

        Insert() {
            super("insert", "ae2fluid.cmd.insert.desc");
            fluid = withRequiredArg("fluid", "ae2fluid.cmd.arg.fluid", ArgTypes.STRING);
            amount = withRequiredArg("mB", "ae2fluid.cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(ctx, nets, x, y, z);
            AEFluidKey key = grid == null ? null : fluid(ctx, ctx.get(fluid));
            if (key == null) {
                return;
            }
            long in = grid.getStorageService().getInventory().insert(key, Math.max(0, ctx.get(amount)),
                    Actionable.MODULATE, IActionSource.empty());
            grid.getStorageService().invalidateCache();
            ctx.sendMessage(Message.translation("ae2fluid.cmd.insert.done").param("amount", in).param("fluid", key.fluidId()));
        }
    }

    private static final class Extract extends Sub {
        private final RequiredArg<String> fluid;
        private final RequiredArg<Integer> amount;

        Extract() {
            super("extract", "ae2fluid.cmd.extract.desc");
            fluid = withRequiredArg("fluid", "ae2fluid.cmd.arg.fluid", ArgTypes.STRING);
            amount = withRequiredArg("mB", "ae2fluid.cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(ctx, nets, x, y, z);
            if (grid == null) {
                return;
            }
            // auch Kennungen, die nicht (mehr) lagerbar sind: was im Netz liegt, muss sich herausholen lassen
            AEFluidKey key = AEFluidKey.of(ctx.get(fluid));
            long out = grid.getStorageService().getInventory().extract(key, Math.max(0, ctx.get(amount)),
                    Actionable.MODULATE, IActionSource.empty());
            grid.getStorageService().invalidateCache();
            ctx.sendMessage(Message.translation("ae2fluid.cmd.extract.done").param("amount", out).param("fluid", key.fluidId()));
        }
    }

    private static final class Count extends Sub {
        private final RequiredArg<String> fluid;

        Count() {
            super("count", "ae2fluid.cmd.count.desc");
            fluid = withRequiredArg("fluid", "ae2fluid.cmd.arg.fluid", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(ctx, nets, x, y, z);
            if (grid == null) {
                return;
            }
            grid.getStorageService().invalidateCache();
            ctx.sendMessage(Message.translation("ae2fluid.cmd.count.done").param("fluid", ctx.get(fluid))
                    .param("amount", grid.getStorageService().getCachedInventory().get(AEFluidKey.of(ctx.get(fluid)))));
        }
    }

    private static final class Cells extends Sub {
        Cells() {
            super("cells", "ae2fluid.cmd.cells.desc");
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node n = nets.node(x, y, z);
            if (n == null || !(n.device() instanceof CellHostDevice host)) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.noCells").param("x", x).param("y", y).param("z", z));
                return;
            }
            int shown = 0;
            for (int slot = 0; slot < host.logic().getSlots(); slot++) {
                StorageCell cell = host.logic().getCell(slot);
                if (cell == null) {
                    continue;
                }
                shown++;
                ItemStack stack = host.container() == null ? null : host.container().getItemStack((short) slot);
                StringJoiner content = new StringJoiner(" ");
                for (KeyCounter.Entry e : cell.getAvailableStacks()) {
                    AEKey key = e.getKey();
                    content.add((key instanceof AEFluidKey ? "fluid:" : "item:") + key.getId() + "=" + e.getLongValue());
                }
                BasicCellInventory basic = cell instanceof BasicCellInventory b ? b : null;
                ctx.sendMessage(Message.translation("ae2fluid.cmd.cells.slot").param("slot", slot)
                        .param("item", ItemStack.isEmpty(stack) ? "?" : stack.getItemId())
                        .param("used", basic == null ? -1 : basic.getUsedBytes())
                        .param("total", basic == null ? -1 : basic.getTotalBytes())
                        .param("types", basic == null ? -1 : basic.getStoredItemTypes())
                        .param("maxTypes", basic == null ? -1 : basic.getTotalItemTypes())
                        .param("content", content.length() == 0 ? "-" : content.toString()));
            }
            if (shown == 0) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.cells.none"));
            }
        }
    }

    private static final class Tank extends Sub {
        Tank() {
            super("tank", "ae2fluid.cmd.tank.desc");
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            SkyStoneTankLogic tank = tank(ctx, nets, x, y, z);
            if (tank != null) {
                ctx.sendMessage(tankState(tank));
            }
        }
    }

    private static final class Fill extends Sub {
        private final RequiredArg<String> fluid;
        private final RequiredArg<Integer> amount;

        Fill() {
            super("fill", "ae2fluid.cmd.fill.desc");
            fluid = withRequiredArg("fluid", "ae2fluid.cmd.arg.fluid", ArgTypes.STRING);
            amount = withRequiredArg("mB", "ae2fluid.cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            SkyStoneTankLogic tank = tank(ctx, nets, x, y, z);
            AEFluidKey key = tank == null ? null : fluid(ctx, ctx.get(fluid));
            if (key == null) {
                return;
            }
            long in = tank.insert(key, Math.max(0, ctx.get(amount)), Actionable.MODULATE);
            ctx.sendMessage(Message.translation("ae2fluid.cmd.fill.done").param("amount", in).param("fluid", key.fluidId()));
            ctx.sendMessage(tankState(tank));
        }
    }

    private static final class Drain extends Sub {
        private final RequiredArg<Integer> amount;

        Drain() {
            super("drain", "ae2fluid.cmd.drain.desc");
            amount = withRequiredArg("mB", "ae2fluid.cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            SkyStoneTankLogic tank = tank(ctx, nets, x, y, z);
            if (tank == null) {
                return;
            }
            AEFluidKey key = tank.getFluid();
            long out = key == null ? 0 : tank.extract(key, Math.max(0, ctx.get(amount)), Actionable.MODULATE);
            ctx.sendMessage(Message.translation("ae2fluid.cmd.drain.done").param("amount", out)
                    .param("fluid", key == null ? "-" : key.fluidId()));
            ctx.sendMessage(tankState(tank));
        }
    }

    private static final class Filter extends Sub {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> fluid;

        Filter() {
            super("filter", "ae2fluid.cmd.filter.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            fluid = withRequiredArg("fluid", "ae2fluid.cmd.arg.filter", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node n = nets.node(x, y, z);
            ConfigHost.GhostSlots slots = n != null && n.device() instanceof ConfigHost host
                    ? host.ghostSlots(new ConfigHost.Context(nets, n, "en-US")) : null;
            if (slots == null) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.filter.none").param("x", x).param("y", y).param("z", z));
                return;
            }
            int at = ctx.get(slot);
            String what = ctx.get(fluid);
            String result;
            if ("clear".equalsIgnoreCase(what)) {
                result = ConfigEdits.clear(slots.inventory(), slots.usable(), at) ? "CLEARED" : "EMPTY";
            } else {
                AEFluidKey key;
                if ("next".equalsIgnoreCase(what)) {
                    // wie ein Rechtsklick mit einem leeren Eimer: nächste freie Flüssigkeit
                    List<String> storable = new ArrayList<>();
                    FluidTypes.storable().forEach(info -> storable.add(info.id()));
                    key = ConfigEdits.fluidForSlot(slots.inventory(), slots.usable(), at, null, storable);
                } else {
                    key = fluid(ctx, what);
                    if (key == null) {
                        return;
                    }
                }
                result = key == null ? "NOTHING"
                        : ConfigEdits.put(slots.inventory(), slots.usable(), at, key, 1, true).name();
            }
            ((ConfigHost) n.device()).configChanged(n);
            AEKey now = at >= 0 && at < slots.inventory().size() ? slots.inventory().getKey(at) : null;
            ctx.sendMessage(Message.translation("ae2fluid.cmd.filter.done").param("slot", at).param("result", result)
                    .param("key", now == null ? "-" : now.toString()));
        }
    }

    private static final class Terminal extends Sub {
        Terminal() {
            super("terminal", "ae2fluid.cmd.terminal.desc");
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            TerminalBackend backend = new NetworkTerminalBackend(nets, x, y, z, null);
            TerminalBackend.Status status = backend.status();
            StringJoiner fluids = new StringJoiner(" ");
            for (TerminalBackend.Entry e : backend.fluids()) {
                fluids.add(e.key() + "=" + e.amount() + "(" + e.display().getItemId() + ","
                        + FluidText.slot(e.amount()) + ")");
            }
            ctx.sendMessage(Message.translation("ae2fluid.cmd.terminal")
                    .param("online", backend.isOnline())
                    .param("items", backend.entries().size())
                    .param("itemTotal", status == null ? 0 : status.items())
                    .param("fluidTotal", status == null ? 0 : status.fluid())
                    .param("fluids", fluids.length() == 0 ? "-" : fluids.toString()));
        }
    }

    private static final class WorldFluid extends Sub {
        private final RequiredArg<String> what;

        WorldFluid() {
            super("world", "ae2fluid.cmd.world.desc");
            what = withRequiredArg("fluid", "ae2fluid.cmd.arg.world", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            String wanted = ctx.get(what);
            Ref<ChunkStore> section = FluidWorld.section(world, x, y, z);
            if (section == null) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.world.unloaded"));
                return;
            }
            if (!"get".equalsIgnoreCase(wanted)) {
                int index = "none".equalsIgnoreCase(wanted) ? 0
                        : Fluid.getAssetMap().getIndexOrDefault(wanted, Integer.MIN_VALUE);
                if (index < 0) {
                    ctx.sendMessage(Message.translation("ae2fluid.cmd.unknown").param("fluid", wanted));
                    return;
                }
                FluidWorld.set(world, section, x, y, z, index);
                Planes.blockChanged(nets, x, y, z); // Planes vor dieser Stelle sehen sofort nach
            }
            String id = FluidWorld.fluidId(world, x, y, z);
            ctx.sendMessage(Message.translation("ae2fluid.cmd.world").param("x", x).param("y", y).param("z", z)
                    .param("fluid", id == null ? "-" : id).param("level", FluidWorld.fluidLevel(world, x, y, z))
                    .param("source", FluidTypes.sourceAt(world, x, y, z) != null));
        }
    }

    private static final class MoveSlot extends Sub {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<Integer> toX;
        private final RequiredArg<Integer> toY;
        private final RequiredArg<Integer> toZ;
        private final RequiredArg<Integer> toSlot;

        MoveSlot() {
            super("moveslot", "ae2fluid.cmd.moveslot.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            toX = withRequiredArg("x2", "ae2fluid.cmd.arg.target", ArgTypes.INTEGER);
            toY = withRequiredArg("y2", "ae2fluid.cmd.arg.target", ArgTypes.INTEGER);
            toZ = withRequiredArg("z2", "ae2fluid.cmd.arg.target", ArgTypes.INTEGER);
            toSlot = withRequiredArg("slot2", "ae2cmd.arg.slot", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer from = BlockAccess.container(world, x, y, z);
            ItemContainer to = BlockAccess.container(world, ctx.get(toX), ctx.get(toY), ctx.get(toZ));
            short a = (short) (int) ctx.get(slot);
            short b = (short) (int) ctx.get(toSlot);
            ItemStack stack = from == null || a < 0 || a >= from.getCapacity() ? null : from.getItemStack(a);
            if (to == null || b < 0 || b >= to.getCapacity() || ItemStack.isEmpty(stack)
                    || !ItemStack.isEmpty(to.getItemStack(b))) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.moveslot.failed"));
                return;
            }
            // erst herausnehmen, dann einlegen; nimmt das Ziel den Stapel nicht an, geht er zurück
            from.removeItemStackFromSlot(a, false);
            if (!to.setItemStackForSlot(b, stack).succeeded()) {
                from.setItemStackForSlot(a, stack, false);
                ctx.sendMessage(Message.translation("ae2fluid.cmd.moveslot.failed"));
                return;
            }
            ctx.sendMessage(Message.translation("ae2fluid.cmd.moveslot.done").param("item", stack.getItemId())
                    .param("slot", (int) b));
        }
    }

    private static final class Bucket extends Sub {
        private final RequiredArg<String> mode;
        private final RequiredArg<String> fluid;

        Bucket() {
            super("bucket", "ae2fluid.cmd.bucket.desc");
            mode = withRequiredArg("mode", "ae2fluid.cmd.arg.mode", ArgTypes.STRING);
            fluid = withRequiredArg("fluid", "ae2fluid.cmd.arg.fluid", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            AEFluidKey key = fluid(ctx, ctx.get(fluid));
            if (key == null) {
                return;
            }
            FluidConfig.ContainerSpec spec = FluidContainers.forFluid(key.fluidId());
            if (spec == null) {
                ctx.sendMessage(Message.translation("ae2fluid.cmd.bucket.none").param("fluid", key.fluidId()));
                return;
            }
            boolean fill = "fill".equals(ctx.get(mode).toLowerCase(Locale.ROOT));
            TerminalBackend backend = new NetworkTerminalBackend(nets, x, y, z, null);
            FluidTransfers.Endpoint network = TerminalView.fluidEndpoint(backend);
            // Probe-Feld statt eines Spielerinventars: derselbe Weg wie am Terminal
            SimpleItemContainer hand = new SimpleItemContainer((short) 1);
            hand.setItemStackForSlot((short) 0, fill ? FluidContainers.empty(spec) : FluidContainers.filled(spec), false);
            List<String> overflow = new ArrayList<>();
            FluidTransfers.Result result = fill
                    ? FluidTransfers.fill(hand, (short) 0, key, network, rest -> overflow.add(rest.getItemId()))
                    : FluidTransfers.empty(hand, (short) 0, network, rest -> overflow.add(rest.getItemId()));
            ItemStack after = hand.getItemStack((short) 0);
            ctx.sendMessage(Message.translation("ae2fluid.cmd.bucket.done").param("mode", fill ? "fill" : "empty")
                    .param("result", result.name())
                    .param("slot", ItemStack.isEmpty(after) ? "-" : after.getItemId() + "x" + after.getQuantity())
                    .param("overflow", overflow.isEmpty() ? "-" : String.join(",", overflow)));
        }
    }
}
