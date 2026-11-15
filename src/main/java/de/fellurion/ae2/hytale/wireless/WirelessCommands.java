/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.wireless.LinkStatus;
import de.fellurion.ae2.core.wireless.WirelessAccessPointLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CellTooltips;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.items.ItemUpgrades;
import de.fellurion.ae2.hytale.items.PoweredItems;
import de.fellurion.ae2.hytale.ui.ChestTerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalTexts;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Admin-/Testbefehle dieses Bereichs (Berechtigung {@code ae2.admin}). Sie lassen prüfen, was sonst einen Spieler
 * bräuchte: Die Terminals in Items laufen dabei über dieselben Sitzungen wie im Spiel, nur liegt das Item im Slot
 * eines Blocks statt im Inventar ({@link ItemPlace#of(World, int, int, int)}).
 * <ul>
 *   <li>{@code /ae2 slot x y z slot} – Item im Slot: Ladung, Karten, Verknüpfung, Zelleninhalt, Tooltip</li>
 *   <li>{@code /ae2 putslot x y z slot itemId amount} – Items in einen Slot legen (z. B. mehrere Booster)</li>
 *   <li>{@code /ae2 card x y z slot cardItemId} / {@code /ae2 uncard x y z slot index} – Karte ein-/ausbauen</li>
 *   <li>{@code /ae2 charge x y z slot amount} – Akku des Items im Slot um {@code amount} AE laden</li>
 *   <li>{@code /ae2 wap x y z} – Access Point: Booster, Reichweite, Verbrauch, Zeilen der Blickanzeige</li>
 *   <li>{@code /ae2 wlink x y z slot ax ay az} – Terminal im Slot mit dem Access Point bei ax/ay/az verknüpfen</li>
 *   <li>{@code /ae2 wterm x y z slot px py pz ticks itemId amount} – Wireless Terminal im Slot, Halter bei px/py/pz:
 *       Verbindung prüfen, {@code amount} Items ein- (+) oder auslagern (−), {@code ticks} Ticks offen lassen</li>
 *   <li>{@code /ae2 pcell x y z slot ticks itemId amount} – tragbare Zelle im Slot ebenso</li>
 *   <li>{@code /ae2 chest x y z itemId amount} – über das Terminal der ME Chest ein- (+) oder auslagern (−)</li>
 * </ul>
 */
final class WirelessCommands {

    private WirelessCommands() {
    }

    static List<AbstractCommand> all() {
        return List.of(new Slot(), new PutSlot(), new Card(), new Uncard(), new Charge(), new Wap(), new Link(),
                new Term(), new PortableCell(), new Chest());
    }

    private abstract static class PosCommand extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        PosCommand(String name) {
            super(name, "ae2wireless.cmd." + name + ".desc");
            this.pos = withRequiredArg("x y z", "ae2wireless.cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        RequiredArg<Integer> intArg(String name) {
            return withRequiredArg(name, "ae2wireless.cmd.arg." + name, ArgTypes.INTEGER);
        }

        RequiredArg<String> itemArg() {
            return withRequiredArg("itemId", "ae2wireless.cmd.arg.itemId", ArgTypes.STRING);
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, world, WorldNetworks.of(world), p.x, p.y, p.z));
        }

        abstract Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z);
    }

    private static Message msg(String key) {
        return Message.translation("ae2wireless.cmd." + key);
    }

    @Nullable
    private static ItemStack stackAt(World world, int x, int y, int z, int slot) {
        ItemContainer c = BlockAccess.container(world, x, y, z);
        if (c == null || slot < 0 || slot >= c.getCapacity()) {
            return null;
        }
        ItemStack s = c.getItemStack((short) slot);
        return ItemStack.isEmpty(s) ? null : s;
    }

    private static String number(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** Beschreibung eines Stacks: Ladung, Karten, Verknüpfung, Zelleninhalt. */
    private static Message describe(int slot, ItemStack s) {
        WirelessLink link = WirelessLink.of(s);
        String cell = "-";
        if (StorageCells.isCellItem(s.getItemId())) {
            CellItemData data = CellItemData.fromJson(s.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
            long items = 0;
            for (Long v : data.getContents().values()) {
                items += v == null ? 0 : v;
            }
            cell = data.getContents().size() + "/" + items;
        }
        return msg("slot.info").param("slot", slot).param("item", s.getItemId()).param("count", s.getQuantity())
                .param("stored", number(PoweredItems.stored(s))).param("max", number(PoweredItems.capacity(s)))
                .param("rate", number(PoweredItems.chargeRate(s)))
                .param("cards", String.join("+", ItemUpgrades.cards(s)))
                .param("link", link == null ? "-" : link.encode())
                .param("cell", cell)
                .param("tooltip", TerminalTexts.itemDescription(s, "en-US").replace("\n", " | "));
    }

    static final class Slot extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");

        Slot() {
            super("slot");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemStack s = stackAt(world, x, y, z, ctx.get(slot));
            return s == null ? msg("slot.empty").param("slot", ctx.get(slot)) : describe(ctx.get(slot), s);
        }
    }

    static final class PutSlot extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<String> item = itemArg();
        private final RequiredArg<Integer> amount = intArg("amount");

        PutSlot() {
            super("putslot");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            String id = ctx.get(item);
            int n = ctx.get(amount);
            int index = ctx.get(slot);
            if (c == null || index < 0 || index >= c.getCapacity() || Item.getAssetMap().getAsset(id) == null) {
                return msg("noContainerOrItem");
            }
            boolean ok = n <= 0 ? c.removeItemStackFromSlot((short) index).succeeded()
                    : c.setItemStackForSlot((short) index, new ItemStack(id, n)).succeeded();
            return msg(ok ? "putslot.done" : "putslot.refused").param("item", id).param("amount", n).param("slot", index);
        }
    }

    static final class Card extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<String> item = itemArg();

        Card() {
            super("card");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            ItemStack s = stackAt(world, x, y, z, ctx.get(slot));
            UpgradeCard card = UpgradeCard.byItemId(ctx.get(item));
            if (c == null || s == null || card == null) {
                return msg("noContainerOrItem");
            }
            ItemStack upgraded = insertCard(s, card);
            if (upgraded == null || !c.setItemStackForSlot((short) (int) ctx.get(slot), upgraded).succeeded()) {
                return msg("card.refused").param("card", card.itemId).param("item", s.getItemId());
            }
            return describe(ctx.get(slot), upgraded);
        }
    }

    static final class Uncard extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<Integer> index = intArg("index");

        Uncard() {
            super("uncard");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            ItemStack s = stackAt(world, x, y, z, ctx.get(slot));
            if (c == null || s == null) {
                return msg("noContainerOrItem");
            }
            ItemStack stripped = removeCard(s, ctx.get(index));
            if (stripped == null || !c.setItemStackForSlot((short) (int) ctx.get(slot), stripped).succeeded()) {
                return msg("card.refused").param("card", "#" + ctx.get(index)).param("item", s.getItemId());
            }
            return describe(ctx.get(slot), stripped);
        }
    }

    static final class Charge extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<Integer> amount = intArg("amount");

        Charge() {
            super("charge");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            ItemStack s = stackAt(world, x, y, z, ctx.get(slot));
            if (c == null || s == null || !PoweredItems.isPowered(s.getItemId())) {
                return msg("noContainerOrItem");
            }
            ItemStack charged = PoweredItems.charge(s, ctx.get(amount), null);
            c.setItemStackForSlot((short) (int) ctx.get(slot), charged);
            return describe(ctx.get(slot), charged);
        }
    }

    /** Karte einbauen wie die Zellen-Werkbank: bei Zellen in den Zelleninhalt, sonst in die Metadaten des Items. */
    @Nullable
    private static ItemStack insertCard(ItemStack s, UpgradeCard card) {
        if (!StorageCells.isCellItem(s.getItemId())) {
            return HandheldUpgrades.insert(s, card);
        }
        CellDefinition def = CellDefinition.byItemId(s.getItemId());
        if (def == null) {
            return null;
        }
        CellItemData data = CellItemData.fromJson(s.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
        UpgradeInventory inv = new UpgradeInventory(s.getItemId(), def.upgradeSlots());
        inv.fromItemIds(data.getUpgrades());
        if (!inv.insert(card)) {
            return null;
        }
        data.setUpgrades(inv.toItemIds());
        ItemStack written = CellTooltips.write(s, data, null);
        return PoweredItems.isPowered(written.getItemId())
                ? PoweredItems.withStored(written, PoweredItems.stored(written)) : written;
    }

    @Nullable
    private static ItemStack removeCard(ItemStack s, int index) {
        if (!StorageCells.isCellItem(s.getItemId())) {
            return HandheldUpgrades.remove(s, index, new String[1]);
        }
        CellItemData data = CellItemData.fromJson(s.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
        List<String> cards = new java.util.ArrayList<>(data.getUpgrades());
        if (index < 0 || index >= cards.size()) {
            return null;
        }
        cards.remove(index);
        data.setUpgrades(cards);
        ItemStack written = CellTooltips.write(s, data, null);
        return PoweredItems.isPowered(written.getItemId())
                ? PoweredItems.withStored(written, PoweredItems.stored(written)) : written;
    }

    static final class Wap extends PosCommand {
        Wap() {
            super("wap");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null || !(node.device() instanceof WirelessAccessPointDevice device)) {
                return msg("wap.none");
            }
            WirelessAccessPointLogic logic = device.logic();
            // dieselben Zeilen, die ein Spieler beim Anschauen sieht
            LookInfo.Builder out = LookInfo.builder(null, "");
            InfoProviders.get(node.kind()).describe(new HudContext(), node, out);
            LookInfo info = out.build();
            StringBuilder hud = new StringBuilder(info.status() == null ? "" : info.status().text());
            for (LookInfo.Line line : info.lines()) {
                hud.append(" | ");
                for (LookInfo.Span span : line.spans()) {
                    hud.append(span.text());
                }
            }
            return msg("wap.info").param("boosters", logic.getBoosters()).param("range", number(logic.getRange()))
                    .param("drain", String.format(Locale.ROOT, "%.3f", logic.getIdlePowerUsage()))
                    .param("nodeDrain", String.format(Locale.ROOT, "%.3f",
                            node.gridNode() == null ? -1 : node.gridNode().getIdlePowerUsage()))
                    .param("active", Boolean.toString(logic.isActive()))
                    .param("linkId", String.valueOf(WirelessAccessPointDevice.linkId(node)))
                    .param("hud", hud.toString());
        }
    }

    static final class Link extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<Integer> ax = intArg("ax");
        private final RequiredArg<Integer> ay = intArg("ay");
        private final RequiredArg<Integer> az = intArg("az");

        Link() {
            super("wlink");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            ItemStack s = stackAt(world, x, y, z, ctx.get(slot));
            WorldNetworks.Node ap = nets.node(ctx.get(ax), ctx.get(ay), ctx.get(az));
            ItemStack linked = c == null || s == null || ap == null ? null : WirelessTerminals.link(world, ap, s);
            if (linked == null || !c.setItemStackForSlot((short) (int) ctx.get(slot), linked).succeeded()) {
                return msg("wlink.refused");
            }
            return describe(ctx.get(slot), linked);
        }
    }

    /**
     * Lagert über ein Backend ein (amount &gt; 0) oder aus (amount &lt; 0); liefert die bewegte Menge. Mit
     * {@code fluid:<Kennung>} statt einer Item-Id wird eine Flüssigkeit bewegt (Menge in mB).
     */
    private static long move(TerminalBackend backend, String itemId, int amount) {
        String fluidId = TerminalBackend.fluidIdOf(itemId);
        if (fluidId != null) {
            return amount > 0 ? backend.insertFluid(fluidId, amount, false)
                    : amount < 0 ? backend.extractFluid(itemId, -(long) amount, false) : 0;
        }
        if (amount > 0) {
            return backend.insert(new ItemStack(itemId, amount));
        }
        if (amount < 0) {
            String wanted = AEItemKey.of(itemId).toString();
            for (TerminalBackend.Entry e : backend.entries()) {
                if (e.key().equals(wanted)) {
                    ItemStack got = backend.extract(e.key(), -amount);
                    return got == null ? 0 : got.getQuantity();
                }
            }
        }
        return 0;
    }

    private static long amountOf(TerminalBackend backend, String itemId) {
        if (TerminalBackend.fluidIdOf(itemId) != null) {
            for (TerminalBackend.Entry e : backend.fluids()) {
                if (e.key().equals(itemId)) {
                    return e.amount();
                }
            }
            return 0;
        }
        String wanted = AEItemKey.of(itemId).toString();
        for (TerminalBackend.Entry e : backend.entries()) {
            if (e.key().equals(wanted)) {
                return e.amount();
            }
        }
        return 0;
    }

    static final class Term extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<Integer> px = intArg("px");
        private final RequiredArg<Integer> py = intArg("py");
        private final RequiredArg<Integer> pz = intArg("pz");
        private final RequiredArg<Integer> ticks = intArg("ticks");
        private final RequiredArg<String> item = itemArg();
        private final RequiredArg<Integer> amount = intArg("amount");

        Term() {
            super("wterm");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            int index = ctx.get(slot);
            ItemStack s = stackAt(world, x, y, z, index);
            if (s == null || !WirelessLink.linkable(s)) {
                return msg("noContainerOrItem");
            }
            WirelessLink link = WirelessLink.of(s);
            if (link == null) {
                return msg("wterm.status").param("status", LinkStatus.NOT_LINKED.name());
            }
            WirelessTerminalSession session = new WirelessTerminalSession(null, ItemPlace.of(world, x, y, z), 0,
                    (short) index, s, link, world, ctx.get(px), ctx.get(py), ctx.get(pz));
            LinkStatus before = session.host().getLinkStatus();
            boolean apActive = session.accessPointActive();
            WirelessTerminalBackend backend = new WirelessTerminalBackend(nets, session, null);
            long moved = 0;
            long inNetwork = 0;
            LinkStatus after = before;
            if (before.connected() && apActive && backend.onOpen(null)) {
                moved = move(backend, ctx.get(item), ctx.get(amount));
                inNetwork = amountOf(backend, ctx.get(item));
                after = session.advance(ctx.get(ticks));
                backend.onClose();
            }
            return msg("wterm.result").param("status", before.name()).param("apActive", Boolean.toString(apActive))
                    .param("distance", number(session.host().getCurrentAccessPoint() == null ? -1
                            : session.host().getCurrentDistance()))
                    .param("drain", number(session.drainPerTick()))
                    .param("moved", moved).param("inNetwork", inNetwork).param("after", after.name())
                    .param("stored", number(session.getAECurrentPower()));
        }
    }

    static final class PortableCell extends PosCommand {
        private final RequiredArg<Integer> slot = intArg("slot");
        private final RequiredArg<Integer> ticks = intArg("ticks");
        private final RequiredArg<String> item = itemArg();
        private final RequiredArg<Integer> amount = intArg("amount");

        PortableCell() {
            super("pcell");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            int index = ctx.get(slot);
            ItemStack s = stackAt(world, x, y, z, index);
            CellDefinition def = s == null ? null : CellDefinition.byItemId(s.getItemId());
            if (def == null || !def.portable()) {
                return msg("noContainerOrItem");
            }
            PortableCellSession session = new PortableCellSession(null, ItemPlace.of(world, x, y, z), 0, (short) index, s);
            LinkStatus before = session.host().getLinkStatus();
            PortableCellBackend backend = new PortableCellBackend(session, null);
            long moved = 0;
            long inCell = 0;
            LinkStatus after = before;
            if (before.connected() && backend.onOpen(null)) {
                moved = move(backend, ctx.get(item), ctx.get(amount));
                inCell = amountOf(backend, ctx.get(item));
                after = session.advance(ctx.get(ticks));
                backend.onClose();
            }
            return msg("pcell.result").param("status", before.name()).param("moved", moved).param("inCell", inCell)
                    .param("after", after.name()).param("stored", number(session.getAECurrentPower()));
        }
    }

    static final class Chest extends PosCommand {
        private final RequiredArg<String> item = itemArg();
        private final RequiredArg<Integer> amount = intArg("amount");

        Chest() {
            super("chest");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ChestTerminalBackend backend = new ChestTerminalBackend(nets, x, y, z, null);
            boolean online = backend.isOnline();
            long moved = online ? move(backend, ctx.get(item), ctx.get(amount)) : 0;
            List<TerminalBackend.Entry> entries = backend.entries();
            long total = 0;
            for (TerminalBackend.Entry e : entries) {
                total += e.amount();
            }
            long inNetwork = 0;
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node != null && node.gridNode() != null && node.gridNode().getGrid() != null) {
                KeyCounter all = node.gridNode().getGrid().getStorageService().getCachedInventory();
                inNetwork = all.get(AEItemKey.of(ctx.get(item)));
            }
            return msg("chest.result").param("online", Boolean.toString(online)).param("moved", moved)
                    .param("inCell", amountOf(backend, ctx.get(item))).param("types", entries.size())
                    .param("items", total).param("inNetwork", inNetwork);
        }
    }
}
