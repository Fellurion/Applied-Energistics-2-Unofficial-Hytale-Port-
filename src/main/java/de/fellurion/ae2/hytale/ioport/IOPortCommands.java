/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ioport;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.automation.IOPortLogic;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.FullnessMode;
import de.fellurion.ae2.core.config.OperationMode;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CellTooltips;
import de.fellurion.ae2.hytale.signal.Signals;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ae2 ioport …}: Admin-/Testbefehle für den IO-Port (Berechtigung {@code ae2.admin}), damit er sich ohne
 * Spieler über die Konsole prüfen lässt.
 * <ul>
 *   <li>{@code state x y z} – Einstellungen, Karten, Zustand, Summe im Netz und der Inhalt der Zellen (aus den Items
 *   gelesen)</li>
 *   <li>{@code set x y z operation|fullness|redstone wert} – Einstellung ändern</li>
 *   <li>{@code card x y z kartenItem} – Upgrade-Karte einbauen</li>
 *   <li>{@code cell x y z slot zellenItem item menge} – Zelle mit Inhalt in einen Platz legen</li>
 *   <li>{@code signal x y z on|off} – AE2-Signal an einer Stelle an- oder abschalten</li>
 *   <li>{@code drops x y z} – herumliegende Items in der Nähe (nach dem Abbauen)</li>
 * </ul>
 */
final class IOPortCommands extends AbstractCommandCollection {

    IOPortCommands() {
        super("ioport", "ae2ioport.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new State());
        addSubCommand(new Set());
        addSubCommand(new Card());
        addSubCommand(new Cell());
        addSubCommand(new Signal());
        addSubCommand(new DropsNear());
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
            run(ctx, world, store, p.x, p.y, p.z);
        }

        abstract void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z);

        /** Der IO-Port an der Stelle; meldet sonst, dass dort keiner steht. */
        @Nullable
        static IOPortDevice port(CommandContext ctx, World world, int x, int y, int z) {
            WorldNetworks.Node node = WorldNetworks.of(world).node(x, y, z);
            if (node != null && node.device() instanceof IOPortDevice port) {
                return port;
            }
            ctx.sendMessage(Message.translation("ae2ioport.cmd.none").param("x", x).param("y", y).param("z", z));
            return null;
        }
    }

    /** Menge und Typen im Zelleninhalt, wie er im Item steht. */
    private static long[] stored(ItemStack stack) {
        long amount = 0;
        long types = 0;
        try {
            CellItemData data = CellItemData.fromJson(stack.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
            for (long v : data.getContents().values()) {
                if (v > 0) {
                    amount += v;
                    types++;
                }
            }
        } catch (RuntimeException ignored) {
            // kein lesbarer Zelleninhalt
        }
        return new long[]{amount, types};
    }

    /** Summe aller Mengen im Netz des Ports – im selben Augenblick gelesen wie die Zellen, also vergleichbar. */
    private static long networkTotal(@Nullable GridNode g) {
        if (g == null || g.getGrid() == null) {
            return 0;
        }
        long sum = 0;
        for (var entry : g.getGrid().getStorageService().getCachedInventory()) {
            sum += entry.getLongValue();
        }
        return sum;
    }

    private static final class State extends Sub {
        State() {
            super("state", "ae2ioport.cmd.state.desc");
        }

        @Override
        void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            IOPortDevice port = port(ctx, world, x, y, z);
            if (port == null) {
                return;
            }
            IOPortLogic logic = port.logic();
            GridNode g = logic.getActionableNode();
            List<String> cards = new ArrayList<>();
            for (int i = 0; i < logic.getUpgrades().size(); i++) {
                UpgradeCard card = logic.getUpgrades().get(i);
                if (card != null) {
                    cards.add(card.itemId);
                }
            }
            ctx.sendMessage(Message.translation("ae2ioport.cmd.state")
                    .param("operation", logic.getOperationMode().name())
                    .param("fullness", logic.getFullnessMode().name())
                    .param("redstone", logic.getRedstoneMode().name())
                    .param("powered", logic.isRedstonePowered())
                    .param("enabled", logic.isEnabled())
                    .param("active", g != null && g.isActive())
                    .param("sleeping", g != null && g.getGrid() != null && g.getGrid().getTickManager().isSleeping(g))
                    .param("rate", logic.getOperationsPerTick())
                    .param("cards", String.join(",", cards))
                    .param("network", networkTotal(g)));
            ItemContainer c = port.container();
            if (c == null) {
                return;
            }
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                if (ItemStack.isEmpty(stack)) {
                    continue;
                }
                long[] content = stored(stack);
                ctx.sendMessage(Message.translation("ae2ioport.cmd.state.slot").param("slot", (int) s)
                        .param("item", stack.getItemId()).param("stored", content[0]).param("types", content[1]));
            }
        }
    }

    private static final class Set extends Sub {
        private final RequiredArg<String> setting;
        private final RequiredArg<String> value;

        Set() {
            super("set", "ae2ioport.cmd.set.desc");
            setting = withRequiredArg("setting", "ae2ioport.cmd.arg.setting", ArgTypes.STRING);
            value = withRequiredArg("value", "ae2ioport.cmd.arg.value", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            IOPortDevice port = port(ctx, world, x, y, z);
            if (port == null) {
                return;
            }
            IOPortLogic logic = port.logic();
            String name = ctx.get(setting).toLowerCase(Locale.ROOT);
            String v = ctx.get(value).toUpperCase(Locale.ROOT);
            try {
                switch (name) {
                    case "operation" -> logic.setOperationMode(OperationMode.valueOf(v));
                    case "fullness" -> logic.setFullnessMode(FullnessMode.valueOf(v));
                    case "redstone" -> logic.setRedstoneMode(RedstoneMode.valueOf(v));
                    default -> throw new IllegalArgumentException(name);
                }
            } catch (IllegalArgumentException e) {
                ctx.sendMessage(Message.translation("ae2ioport.cmd.set.unknown").param("setting", name).param("value", v));
                return;
            }
            ctx.sendMessage(Message.translation("ae2ioport.cmd.set.done").param("setting", name).param("value", v));
        }
    }

    private static final class Card extends Sub {
        private final RequiredArg<String> card;

        Card() {
            super("card", "ae2ioport.cmd.card.desc");
            card = withRequiredArg("cardItemId", "ae2ioport.cmd.arg.card", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            IOPortDevice port = port(ctx, world, x, y, z);
            if (port == null) {
                return;
            }
            String id = ctx.get(card);
            UpgradeCard c = UpgradeCard.byItemId(id);
            boolean done = c != null && port.logic().getUpgrades().insert(c);
            ctx.sendMessage(Message.translation(done ? "ae2ioport.cmd.card.done" : "ae2ioport.cmd.card.refused")
                    .param("card", id));
        }
    }

    private static final class Cell extends Sub {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> cell;
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Cell() {
            super("cell", "ae2ioport.cmd.cell.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            cell = withRequiredArg("cellItemId", "ae2ioport.cmd.arg.cell", ArgTypes.STRING);
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            IOPortDevice port = port(ctx, world, x, y, z);
            if (port == null) {
                return;
            }
            ItemContainer c = port.container();
            String cellId = ctx.get(cell);
            String itemId = ctx.get(item);
            int s = ctx.get(slot);
            CellItemData data = new CellItemData();
            StorageCell inventory = StorageCells.isCellItem(cellId) ? StorageCells.getCellInventory(cellId, data, null) : null;
            if (c == null || inventory == null || s < 0 || s >= c.getCapacity() || Item.getAssetMap().getAsset(cellId) == null
                    || Item.getAssetMap().getAsset(itemId) == null) {
                ctx.sendMessage(Message.translation("ae2ioport.cmd.cell.refused"));
                return;
            }
            long in = inventory.insert(AEItemKey.of(itemId), Math.max(0, ctx.get(amount)), Actionable.MODULATE,
                    IActionSource.empty());
            inventory.persist();
            ItemStack stack = CellTooltips.write(new ItemStack(cellId, 1), data, inventory);
            if (!c.setItemStackForSlot((short) s, stack).succeeded()) {
                ctx.sendMessage(Message.translation("ae2ioport.cmd.cell.refused"));
                return;
            }
            ctx.sendMessage(Message.translation("ae2ioport.cmd.cell.done").param("cell", cellId).param("amount", in)
                    .param("item", itemId).param("slot", s));
        }
    }

    private static final class Signal extends Sub {
        private final RequiredArg<String> state;

        Signal() {
            super("signal", "ae2ioport.cmd.signal.desc");
            state = withRequiredArg("on|off", "ae2ioport.cmd.arg.state", ArgTypes.STRING);
        }

        @Override
        void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            boolean on = "on".equalsIgnoreCase(ctx.get(state));
            Signals.of(world).setEmitting(x, y, z, on);
            ctx.sendMessage(Message.translation("ae2ioport.cmd.signal.done").param("x", x).param("y", y).param("z", z)
                    .param("state", on ? "on" : "off"));
        }
    }

    /** Herumliegende Items im Umkreis von vier Blöcken (waagerecht), mit dem Zelleninhalt, den sie tragen. */
    private static final class DropsNear extends Sub {
        private static final int RADIUS = 4;

        DropsNear() {
            super("drops", "ae2ioport.cmd.drops.desc");
        }

        @Override
        void run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            List<ItemStack> found = new ArrayList<>();
            Query<EntityStore> query = Query.and(ItemComponent.getComponentType(), TransformComponent.getComponentType());
            world.getEntityStore().getStore().forEachChunk(query, (chunk, buffer) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    ItemComponent item = chunk.getComponent(i, ItemComponent.getComponentType());
                    TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    ItemStack stack = item == null ? null : item.getItemStack();
                    if (ItemStack.isEmpty(stack) || transform == null) {
                        continue;
                    }
                    Vector3d p = transform.getPosition();
                    if (Math.abs(p.x - (x + 0.5)) <= RADIUS && Math.abs(p.z - (z + 0.5)) <= RADIUS) {
                        found.add(stack);
                    }
                }
            });
            ctx.sendMessage(Message.translation("ae2ioport.cmd.drops").param("count", found.size()).param("x", x)
                    .param("y", y).param("z", z));
            for (ItemStack stack : found) {
                ctx.sendMessage(Message.translation("ae2ioport.cmd.drops.item").param("amount", stack.getQuantity())
                        .param("item", stack.getItemId()).param("stored", stored(stack)[0]));
            }
        }
    }
}
