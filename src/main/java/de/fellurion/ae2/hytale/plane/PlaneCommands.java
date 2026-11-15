/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.component.Ref;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.automation.FormationPlaneLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.ticking.TickManager;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Drops;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /ae2 plane …}: Planes von der Konsole aus prüfen und einstellen (Admin, {@code ae2.admin}) – für Tests ohne
 * Client und zur Fehlersuche.
 * <ul>
 *   <li>{@code place x y z annihilation|formation richtung} – eine Plane setzen, die in die Richtung zeigt
 *   ({@code up, down, north, south, east, west}); {@code block set} kann nur die Grundausrichtung</li>
 *   <li>{@code info x y z} – Zustand einer Plane</li>
 *   <li>{@code filter x y z slot itemId} – Filterplatz der Formation Plane setzen ({@code none} leert ihn)</li>
 *   <li>{@code mode x y z block|item} – Formation Plane: als Block setzen oder als Item auswerfen</li>
 *   <li>{@code priority x y z n} – Priorität der Formation Plane</li>
 *   <li>{@code card x y z itemId} – Upgrade-Karte in die Formation Plane einbauen</li>
 *   <li>{@code items x y z radius} – herumliegende Items im Würfel um die Stelle zählen</li>
 *   <li>{@code drop x y z itemId amount} – ein Item an der Stelle fallen lassen</li>
 * </ul>
 */
final class PlaneCommands extends AbstractCommandCollection {

    PlaneCommands() {
        super("plane", "ae2plane.cmd.desc");
        addAliases("planes");
        requirePermission("ae2.admin");
        addSubCommand(new Place());
        addSubCommand(new Info());
        addSubCommand(new Filter());
        addSubCommand(new Mode());
        addSubCommand(new Priority());
        addSubCommand(new Card());
        addSubCommand(new Items());
        addSubCommand(new Drop());
    }

    private abstract static class PosCommand extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> pos;

        PosCommand(String name, String desc) {
            super(name, desc);
            this.pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, world, WorldNetworks.of(world), p.x, p.y, p.z));
        }

        abstract Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z);

        /** Die Formation Plane an der Stelle, oder null. */
        @Nullable
        static FormationPlaneDevice formation(WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            return node != null && node.device() instanceof FormationPlaneDevice d ? d : null;
        }
    }

    private static Message noPlane() {
        return Message.translation("ae2plane.cmd.noFormationPlane");
    }

    // ------------------------------------------------------------------ place

    private static final class Place extends PosCommand {
        private final RequiredArg<String> type;
        private final RequiredArg<String> direction;

        Place() {
            super("place", "ae2plane.cmd.place.desc");
            type = withRequiredArg("type", "ae2plane.cmd.arg.type", ArgTypes.STRING);
            direction = withRequiredArg("direction", "ae2plane.cmd.arg.direction", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            String t = ctx.get(type).toLowerCase(Locale.ROOT);
            String itemId = t.startsWith("a") ? "AE2_Annihilation_Plane" : t.startsWith("f") ? "AE2_Formation_Plane" : null;
            BlockAccess.Dir dir;
            try {
                dir = BlockAccess.Dir.valueOf(ctx.get(direction).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                dir = null;
            }
            ItemCatalog.Info info = itemId == null ? null : ItemCatalog.get(itemId);
            if (dir == null || info == null || info.front() == null) {
                return Message.translation("ae2plane.cmd.place.invalid");
            }
            boolean placed = BlockAccess.setBlock(world, x, y, z, itemId, BlockAccess.rotationFacing(dir, info.front()), 0);
            return Message.translation(placed ? "ae2plane.cmd.place.done" : "ae2plane.cmd.place.failed")
                    .param("item", itemId).param("direction", dir.name().toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ info

    private static final class Info extends PosCommand {
        Info() {
            super("info", "ae2plane.cmd.info.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            PlaneTarget target = node == null ? null : node.device() instanceof AnnihilationPlaneDevice a ? a.target()
                    : node.device() instanceof FormationPlaneDevice f ? f.target() : null;
            if (node == null || target == null) {
                return Message.translation("ae2plane.cmd.noPlane");
            }
            GridNode grid = node.gridNode();
            String front = BlockAccess.blockKey(world, target.x(), target.y(), target.z());
            Message state = Message.translation(grid != null && grid.isActive() ? "ae2plane.cmd.state.online"
                    : "ae2plane.cmd.state.offline");
            if (node.device() instanceof FormationPlaneDevice plane) {
                FormationPlaneLogic logic = plane.logic();
                List<String> filter = new ArrayList<>();
                for (int i = 0; i < logic.availableSlots(); i++) {
                    AEKey key = logic.getConfig().getKey(i);
                    if (key != null) {
                        filter.add(i + "=" + key.getId());
                    }
                }
                List<String> cards = new ArrayList<>();
                for (int i = 0; i < logic.getUpgrades().size(); i++) {
                    UpgradeCard card = logic.getUpgrades().get(i);
                    if (card != null) {
                        cards.add(card.itemId);
                    }
                }
                return Message.translation("ae2plane.cmd.info.formation").param("facing", facing(target))
                        .param("x", target.x()).param("y", target.y()).param("z", target.z())
                        .param("block", front == null ? "?" : front).param("state", state)
                        .param("mode", logic.getPlaceBlock() == YesNo.YES ? "block" : "item")
                        .param("priority", logic.getPriority()).param("slots", logic.availableSlots())
                        .param("filter", String.join(", ", filter)).param("cards", String.join(", ", cards));
            }
            AnnihilationPlaneDevice plane = (AnnihilationPlaneDevice) node.device();
            String ticking = "-";
            if (grid != null && grid.getGrid() != null) {
                TickManager ticks = grid.getGrid().getTickManager();
                ticking = ticks.isSleeping(grid) ? "sleeping" : "every " + ticks.getCurrentRate(grid) + " ticks";
            }
            List<Ref<EntityStore>> items = new ArrayList<>();
            PlaneItems.collectInFront(target, new Vector3d(), new Vector3d(), items);
            return Message.translation("ae2plane.cmd.info.annihilation").param("facing", facing(target))
                    .param("x", target.x()).param("y", target.y()).param("z", target.z())
                    .param("block", front == null ? "?" : front).param("state", state).param("ticking", ticking)
                    .param("items", items.size())
                    .param("generating", plane.logic().isContinuouslyGenerating());
        }

        private static String facing(PlaneTarget target) {
            return target.facing().name().toLowerCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ Formation Plane einstellen

    private static final class Filter extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> item;

        Filter() {
            super("filter", "ae2plane.cmd.filter.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            FormationPlaneDevice plane = formation(nets, x, y, z);
            if (plane == null) {
                return noPlane();
            }
            int s = ctx.get(slot);
            String id = ctx.get(item);
            boolean clear = "none".equalsIgnoreCase(id);
            if (s < 0 || s >= plane.logic().availableSlots() || !clear && Item.getAssetMap().getAsset(id) == null) {
                return Message.translation("ae2plane.cmd.filter.invalid").param("slots", plane.logic().availableSlots());
            }
            plane.logic().getConfig().setKey(s, clear ? null : AEItemKey.of(id));
            return Message.translation(clear ? "ae2plane.cmd.filter.cleared" : "ae2plane.cmd.filter.done")
                    .param("slot", s).param("item", id);
        }
    }

    private static final class Mode extends PosCommand {
        private final RequiredArg<String> mode;

        Mode() {
            super("mode", "ae2plane.cmd.mode.desc");
            mode = withRequiredArg("mode", "ae2plane.cmd.arg.mode", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            FormationPlaneDevice plane = formation(nets, x, y, z);
            if (plane == null) {
                return noPlane();
            }
            String m = ctx.get(mode).toLowerCase(Locale.ROOT);
            if (!m.equals("block") && !m.equals("item")) {
                return Message.translation("ae2plane.cmd.mode.invalid");
            }
            plane.logic().setPlaceBlock(m.equals("block") ? YesNo.YES : YesNo.NO);
            return Message.translation("ae2plane.cmd.mode.done").param("mode", m);
        }
    }

    private static final class Priority extends PosCommand {
        private final RequiredArg<Integer> priority;

        Priority() {
            super("priority", "ae2plane.cmd.priority.desc");
            priority = withRequiredArg("priority", "ae2plane.cmd.arg.priority", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            FormationPlaneDevice plane = formation(nets, x, y, z);
            WorldNetworks.Node node = nets.node(x, y, z);
            if (plane == null || node == null) {
                return noPlane();
            }
            plane.setPriority(node, ctx.get(priority));
            return Message.translation("ae2plane.cmd.priority.done").param("priority", plane.priority());
        }
    }

    private static final class Card extends PosCommand {
        private final RequiredArg<String> item;

        Card() {
            super("card", "ae2plane.cmd.card.desc");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            FormationPlaneDevice plane = formation(nets, x, y, z);
            if (plane == null) {
                return noPlane();
            }
            UpgradeCard card = UpgradeCard.byItemId(ctx.get(item));
            if (card == null || !plane.logic().getUpgrades().insert(card)) {
                return Message.translation("ae2plane.cmd.card.refused").param("item", ctx.get(item));
            }
            return Message.translation("ae2plane.cmd.card.done").param("item", card.itemId)
                    .param("slots", plane.logic().availableSlots());
        }
    }

    // ------------------------------------------------------------------ Items in der Welt

    /** Zählt herumliegende Items – so lässt sich prüfen, dass beim Abbauen nichts doppelt oder daneben fällt. */
    private static final class Items extends PosCommand {
        private final RequiredArg<Integer> radius;

        Items() {
            super("items", "ae2plane.cmd.items.desc");
            radius = withRequiredArg("radius", "ae2plane.cmd.arg.radius", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            int r = Math.max(0, Math.min(64, ctx.get(radius)));
            List<Ref<EntityStore>> refs = new ArrayList<>();
            PlaneItems.collect(world, new Vector3d(x - r, y - r, z - r), new Vector3d(x + 1 + r, y + 1 + r, z + 1 + r), refs);
            Store<EntityStore> store = world.getEntityStore().getStore();
            Map<String, Long> byItem = new TreeMap<>();
            long total = 0;
            for (Ref<EntityStore> ref : refs) {
                ItemStack stack = PlaneItems.stackOf(store, ref);
                if (stack != null) {
                    byItem.merge(stack.getItemId(), (long) stack.getQuantity(), Long::sum);
                    total += stack.getQuantity();
                }
            }
            StringBuilder list = new StringBuilder();
            for (Map.Entry<String, Long> e : byItem.entrySet()) {
                if (!list.isEmpty()) {
                    list.append(", ");
                }
                list.append(e.getValue()).append(' ').append(e.getKey());
            }
            return Message.translation("ae2plane.cmd.items.done").param("entities", refs.size()).param("amount", total)
                    .param("list", list.toString());
        }
    }

    private static final class Drop extends PosCommand {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Drop() {
            super("drop", "ae2plane.cmd.drop.desc");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            String id = ctx.get(item);
            int n = ctx.get(amount);
            if (Item.getAssetMap().getAsset(id) == null || n <= 0) {
                return Message.translation("ae2plane.cmd.drop.invalid");
            }
            Drops.at(world, x, y, z, List.of(new ItemStack(id, n)));
            return Message.translation("ae2plane.cmd.drop.done").param("amount", n).param("item", id);
        }
    }
}
