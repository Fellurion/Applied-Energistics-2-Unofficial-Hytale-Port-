/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.tools.MemoryCardSettings.Part;
import de.fellurion.ae2.core.tools.NamePressNames;
import de.fellurion.ae2.core.tools.NetworkStatusReport;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.Drops;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.ui.ConfigEdits;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /ae2 tool …}: Testbefehle für Admins ({@code ae2.admin}). Sie rufen dieselbe Block-Seite auf wie die
 * Werkzeuge in der Hand – nur ohne Spieler, damit sich Drehen, Abbauen und das Kopieren von Einstellungen auch auf
 * einem Server ohne Client prüfen lassen.
 * <ul>
 *   <li>{@code rotate x y z} – drehen wie der Schraubenschlüssel</li>
 *   <li>{@code dismantle x y z} – abbauen wie der Schraubenschlüssel (der Block fällt an Ort und Stelle)</li>
 *   <li>{@code copy x y z x2 y2 z2} – Einstellungen kopieren wie die Memory Card (ohne Upgrade-Karten: die kommen
 *   nur aus dem Inventar eines Spielers)</li>
 *   <li>{@code filter x y z slot itemId} – Filterplatz setzen ({@code -} leert ihn)</li>
 *   <li>{@code setting x y z name wert} – eine Einstellung über ihren AE2-Namen setzen ({@code priority} setzt die
 *   Priorität)</li>
 *   <li>{@code card x y z kartenId} – Upgrade-Karte einbauen</li>
 *   <li>{@code show x y z} – Ausrichtung, Ziel und kopierbare Einstellungen</li>
 *   <li>{@code status x y z} – die Zahlen des Netzwerkstatus</li>
 *   <li>{@code press x y z slot name} – Namenspresse mit Namen in einen Platz legen</li>
 *   <li>{@code slot x y z slot} – Item eines Platzes samt eigenem Namen</li>
 *   <li>{@code takecell x y z x2 y2 z2} – die Zelle einer ME-Truhe entnehmen wie „Zelle entnehmen“ auf ihrer
 *   Prioritätsseite; statt ins Inventar eines Spielers geht sie in den Container bei x2 y2 z2 (was nicht hineinpasst,
 *   fällt an der Truhe)</li>
 * </ul>
 */
public final class ToolCommands extends AbstractCommandCollection {

    private static final String LANG = AE2Language.ENGLISH;

    public ToolCommands() {
        super("tool", "ae2tools.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new Rotate());
        addSubCommand(new Dismantle());
        addSubCommand(new Copy());
        addSubCommand(new Filter());
        addSubCommand(new Setting());
        addSubCommand(new Card());
        addSubCommand(new Show());
        addSubCommand(new Status());
        addSubCommand(new Press());
        addSubCommand(new Slot());
        addSubCommand(new TakeCell());
    }

    private abstract static class PosCommand extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        PosCommand(String name, String desc) {
            super(name, desc);
            this.pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, world, store, WorldNetworks.of(world), p.x, p.y, p.z));
        }

        /** Ergebnis als übersetzte Meldung ({@code ae2tools.cmd.*}). */
        abstract Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y,
                             int z);
    }

    private static Message none(int x, int y, int z) {
        return Message.translation("ae2tools.cmd.none").param("x", x).param("y", y).param("z", z);
    }

    private static String blockName(World world, int x, int y, int z) {
        String key = BlockAccess.blockKey(world, x, y, z);
        return key == null ? "?" : key;
    }

    private static String facing(@Nullable WorldNetworks.Node node) {
        return node == null || node.facing() == null ? "-" : node.facing().name();
    }

    /** Das Gerät an der Stelle als {@link ConfigHost}, oder null. */
    @Nullable
    private static ConfigHost host(@Nullable WorldNetworks.Node node) {
        return node != null && node.device() instanceof ConfigHost host ? host : null;
    }

    private static final class Rotate extends PosCommand {
        Rotate() {
            super("rotate", "ae2tools.cmd.rotate.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            String block = blockName(world, x, y, z);
            Wrenches.Rotated result = Wrenches.rotate(world, x, y, z);
            return Message.translation("ae2tools.cmd.rotate.done").param("block", block).param("result", result.name())
                    .param("facing", facing(nets.node(x, y, z)));
        }
    }

    private static final class Dismantle extends PosCommand {
        Dismantle() {
            super("dismantle", "ae2tools.cmd.dismantle.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            String block = blockName(world, x, y, z);
            Wrenches.Dismantling done = Wrenches.dismantle(world, x, y, z, null, null, store);
            StringBuilder drops = new StringBuilder();
            for (ItemStack drop : done.drops()) {
                drops.append(drops.isEmpty() ? "" : ", ").append(drop.getQuantity()).append(' ').append(drop.getItemId());
            }
            return Message.translation("ae2tools.cmd.dismantle.done").param("block", block)
                    .param("result", done.result().name()).param("drops", drops.isEmpty() ? "-" : drops.toString());
        }
    }

    private static final class Copy extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> from;
        private final RequiredArg<RelativeIntPosition> to;

        Copy() {
            super("copy", "ae2tools.cmd.copy.desc");
            requirePermission("ae2.admin");
            from = withRequiredArg("x y z", "ae2tools.cmd.arg.from", ArgTypes.RELATIVE_BLOCK_POSITION);
            to = withRequiredArg("x2 y2 z2", "ae2tools.cmd.arg.to", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i a = ctx.get(from).getBlockPosition(ctx, store);
            Vector3i b = ctx.get(to).getBlockPosition(ctx, store);
            WorldNetworks nets = WorldNetworks.of(world);
            WorldNetworks.Node source = nets.node(a.x, a.y, a.z);
            WorldNetworks.Node target = nets.node(b.x, b.y, b.z);
            if (!MemoryCards.isMachine(source)) {
                ctx.sendMessage(none(a.x, a.y, a.z));
                return;
            }
            if (!MemoryCards.isMachine(target)) {
                ctx.sendMessage(none(b.x, b.y, b.z));
                return;
            }
            MemoryCardData data = MemoryCards.save(nets, source, LANG);
            if (data == null) {
                ctx.sendMessage(Message.translation("ae2tools.cmd.copy.done").param("type", source.kind())
                        .param("result", "NOTHING_TO_SAVE").param("parts", "-"));
                return;
            }
            MemoryCards.Loaded loaded = MemoryCards.load(nets, target, data, null, LANG);
            StringBuilder parts = new StringBuilder();
            for (Part part : loaded.parts()) {
                parts.append(parts.isEmpty() ? "" : ",").append(part.id());
            }
            ctx.sendMessage(Message.translation("ae2tools.cmd.copy.done").param("type", data.type())
                    .param("result", loaded.outcome().name()).param("parts", parts.isEmpty() ? "-" : parts.toString()));
        }
    }

    private static final class TakeCell extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> from;
        private final RequiredArg<RelativeIntPosition> to;

        TakeCell() {
            super("takecell", "ae2tools.cmd.takecell.desc");
            requirePermission("ae2.admin");
            from = withRequiredArg("x y z", "ae2tools.cmd.arg.from", ArgTypes.RELATIVE_BLOCK_POSITION);
            to = withRequiredArg("x2 y2 z2", "ae2tools.cmd.arg.to", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i a = ctx.get(from).getBlockPosition(ctx, store);
            Vector3i b = ctx.get(to).getBlockPosition(ctx, store);
            WorldNetworks.Node node = WorldNetworks.of(world).node(a.x, a.y, a.z);
            if (node == null || !"me_chest".equals(node.kind()) || !(node.device() instanceof CellHostDevice chest)) {
                ctx.sendMessage(none(a.x, a.y, a.z));
                return;
            }
            ItemStack cell = chest.takeCell(0);
            String result = "EMPTY";
            if (!ItemStack.isEmpty(cell)) {
                // der Stapel landet genau einmal: im Zielcontainer, sonst an der Truhe
                ItemContainer target = BlockAccess.container(world, b.x, b.y, b.z);
                ItemStack rest = target == null ? cell : target.addItemStack(cell).getRemainder();
                result = ItemStack.isEmpty(rest) ? "MOVED" : "DROPPED";
                if (!ItemStack.isEmpty(rest)) {
                    Drops.at(world, a.x, a.y, a.z, List.of(rest));
                }
            }
            ctx.sendMessage(Message.translation("ae2tools.cmd.takecell.done").param("result", result)
                    .param("item", ItemStack.isEmpty(cell) ? "-" : cell.getItemId()));
        }
    }

    private static final class Filter extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> item;

        Filter() {
            super("filter", "ae2tools.cmd.filter.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            ConfigHost host = host(node);
            ConfigHost.GhostSlots slots = host == null ? null : host.ghostSlots(new ConfigHost.Context(nets, node, LANG));
            if (slots == null) {
                return none(x, y, z);
            }
            String id = ctx.get(item);
            int index = ctx.get(slot);
            String result;
            if ("-".equals(id)) {
                result = ConfigEdits.clear(slots.inventory(), slots.usable(), index) ? "CLEARED" : "EMPTY";
            } else if (Item.getAssetMap().getAsset(id) == null) {
                result = "UNKNOWN_ITEM";
            } else {
                result = ConfigEdits.put(slots.inventory(), slots.usable(), index, AEItemKey.of(id), 1, !slots.amounts())
                        .name();
            }
            host.configChanged(node);
            return Message.translation("ae2tools.cmd.filter.done").param("slot", index).param("result", result);
        }
    }

    private static final class Setting extends PosCommand {
        private final RequiredArg<String> name;
        private final RequiredArg<String> value;

        Setting() {
            super("setting", "ae2tools.cmd.setting.desc");
            name = withRequiredArg("name", "ae2tools.cmd.arg.name", ArgTypes.STRING);
            value = withRequiredArg("value", "ae2tools.cmd.arg.value", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            ConfigHost host = host(node);
            if (host == null) {
                return none(x, y, z);
            }
            boolean read;
            if ("priority".equals(ctx.get(name))) {
                // kein AE2-Setting, aber Teil dessen, was die Memory Card kopiert
                Integer priority = null;
                try {
                    priority = Integer.valueOf(ctx.get(value));
                } catch (NumberFormatException ignored) {
                    // bleibt null
                }
                read = priority != null && host.hasPriority();
                if (read) {
                    host.setPriority(node, priority);
                }
            } else {
                read = host.importSettings(Map.of(ctx.get(name), ctx.get(value)));
            }
            host.configChanged(node);
            return Message.translation("ae2tools.cmd.setting.done").param("name", ctx.get(name))
                    .param("value", ctx.get(value)).param("result", read ? "SET" : "IGNORED");
        }
    }

    private static final class Card extends PosCommand {
        private final RequiredArg<String> card;

        Card() {
            super("card", "ae2tools.cmd.card.desc");
            card = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            ConfigHost host = host(node);
            UpgradeInventory upgrades = host == null ? null : host.upgradeCards();
            if (upgrades == null) {
                return none(x, y, z);
            }
            UpgradeCard upgrade = UpgradeCard.byItemId(ctx.get(card));
            boolean inserted = upgrade != null && upgrades.insert(upgrade);
            host.configChanged(node);
            return Message.translation("ae2tools.cmd.card.done").param("card", ctx.get(card))
                    .param("result", upgrade == null ? "NOT_A_CARD" : inserted ? "INSERTED" : "REJECTED");
        }
    }

    private static final class Show extends PosCommand {
        Show() {
            super("show", "ae2tools.cmd.show.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null) {
                return none(x, y, z);
            }
            GridNode grid = node.gridNode();
            String target = "-";
            if (node.device() instanceof BusDevice bus) {
                target = bus.hasTarget() ? bus.targetX() + "," + bus.targetY() + "," + bus.targetZ() : "none";
            }
            return Message.translation("ae2tools.cmd.show.done").param("block", blockName(world, x, y, z))
                    .param("kind", node.kind()).param("facing", facing(node))
                    .param("online", grid == null ? "-" : Boolean.toString(grid.isOnline()))
                    .param("target", target).param("payload", MemoryCards.export(nets, node, LANG).toJson());
        }
    }

    private static final class Status extends PosCommand {
        Status() {
            super("status", "ae2tools.cmd.status.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            GridNode grid = node == null ? null : node.gridNode();
            if (grid == null || grid.getGrid() == null) {
                return none(x, y, z);
            }
            NetworkStatusReport r = NetworkStatusPage.report(nets, grid.getGrid());
            StringBuilder machines = new StringBuilder();
            for (NetworkStatusReport.MachineGroup g : r.groupedMachines()) {
                machines.append(machines.isEmpty() ? "" : ", ").append(g.count()).append('x').append(g.display())
                        .append(g.missingChannel() ? "(no channel)" : "")
                        .append('@').append(String.format(Locale.ROOT, "%.2f", g.idlePowerUsage()));
            }
            return Message.translation("ae2tools.cmd.status.done")
                    .param("stored", (long) r.storedPower()).param("max", (long) r.maxStoredPower())
                    .param("input", String.format(Locale.ROOT, "%.1f", r.averagePowerInjection()))
                    .param("usage", String.format(Locale.ROOT, "%.1f", r.averagePowerUsage()))
                    .param("channels", r.channelsUsed())
                    .param("power", String.format(Locale.ROOT, "%.2f", r.channelPower()))
                    .param("machines", machines.toString());
        }
    }

    private static final class Press extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> name;

        Press() {
            super("press", "ae2tools.cmd.press.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            name = withRequiredArg("name", "ae2tools.cmd.arg.name", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            int index = ctx.get(slot);
            String clean = NamePressNames.clean(ctx.get(name));
            if (c == null || index < 0 || index >= c.getCapacity() || clean == null) {
                return none(x, y, z);
            }
            boolean ok = c.setItemStackForSlot((short) index, NamePresses.create(clean)).succeeded();
            return Message.translation("ae2tools.cmd.press.done").param("name", clean).param("slot", index)
                    .param("result", ok ? "SET" : "REJECTED");
        }
    }

    private static final class Slot extends PosCommand {
        private final RequiredArg<Integer> slot;

        Slot() {
            super("slot", "ae2tools.cmd.slot.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            int index = ctx.get(slot);
            if (c == null || index < 0 || index >= c.getCapacity()) {
                return none(x, y, z);
            }
            ItemStack stack = c.getItemStack((short) index);
            boolean empty = ItemStack.isEmpty(stack);
            String custom = empty ? null
                    : NamePresses.isPress(stack) ? NamePresses.name(stack) : NamePresses.customName(stack);
            return Message.translation("ae2tools.cmd.slot.done").param("slot", index)
                    .param("amount", empty ? 0 : stack.getQuantity()).param("item", empty ? "-" : stack.getItemId())
                    .param("name", custom == null ? "-" : custom);
        }
    }
}
