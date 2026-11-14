/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.CellWorkbenchMenu / appeng.blockentity.misc.CellWorkbenchBlockEntity).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.cells.BasicCellInventory;
import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Zellen-Werkbank: konfiguriert die Zelle im Slot des Blocks.
 * <ul>
 *   <li>Konfiguration (Partition) – bei der Creative-Zelle die Items, die sie unendlich liefert</li>
 *   <li>„Zelleninhalt übernehmen" (AE2: Partition Storage), „Leeren"</li>
 *   <li>Upgrade-Karten einsetzen/entnehmen, Fuzzy-Modus</li>
 * </ul>
 */
public final class CellWorkbenchPage extends InteractiveCustomUIPage<CellWorkbenchPage.Payload> {

    private static final String PAGE = "Pages/AE2/CellWorkbench.ui";
    private static final String ENTRY = "Pages/AE2/TerminalEntry.ui";
    /** AE2: 63 Konfigurationsslots. */
    private static final int MAX_CONFIG = 63;

    private final World world;
    private final int x, y, z;

    public CellWorkbenchPage(PlayerRef playerRef, World world, int x, int y, int z) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // ------------------------------------------------------------------ Zelle lesen/schreiben

    @Nullable
    private ItemContainer container() {
        return BlockAccess.container(world, x, y, z);
    }

    @Nullable
    private ItemStack cellStack() {
        ItemContainer c = container();
        if (c == null || c.getCapacity() < 1) {
            return null;
        }
        ItemStack s = c.getItemStack((short) 0);
        return s == null || s.isEmpty() || !StorageCells.isCellItem(s.getItemId()) ? null : s;
    }

    private static CellItemData data(ItemStack cell) {
        return CellItemData.fromJson(cell.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
    }

    private void write(ItemStack cell, CellItemData data) {
        ItemContainer c = container();
        if (c == null) {
            return;
        }
        c.setItemStackForSlot((short) 0, de.fellurion.ae2.hytale.device.CellTooltips.write(cell, data, null));
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        cmd.set("#WorkbenchTitle.Text", Message.translation("ae2.items.AE2_Cell_Workbench.name"));
        cmd.set("#AddHeld.Text", Message.translation("ae2ui.workbench.addHeld"));
        cmd.set("#AddInventory.Text", Message.translation("ae2ui.workbench.addInventory"));
        cmd.set("#FromContents.Text", Message.translation("ae2ui.workbench.fromContents"));
        cmd.set("#Clear.Text", Message.translation("ae2ui.workbench.clear"));
        cmd.set("#InsertCard.Text", Message.translation("ae2ui.workbench.insertCard"));
        for (String action : new String[]{"AddHeld", "AddInventory", "FromContents", "Clear", "InsertCard", "FuzzyMode"}) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#" + action, EventData.of("Action", action), false);
        }
        fill(cmd, evt);
    }

    private void fill(UICommandBuilder cmd, UIEventBuilder evt) {
        ItemStack cell = cellStack();
        cmd.clear("#Config");
        cmd.clear("#Upgrades");
        if (cell == null) {
            cmd.set("#CellIcon.Visible", false);
            cmd.set("#CellName.Text", Message.translation("ae2ui.workbench.noCell"));
            cmd.set("#CellInfo.Text", "");
            cmd.set("#CellHint.Text", "");
            cmd.set("#ConfigHeader.Text", "");
            cmd.set("#UpgradeHeader.Text", "");
            cmd.set("#FuzzyMode.Visible", false);
            return;
        }
        String id = cell.getItemId();
        boolean creative = CellDefinition.CREATIVE_CELL_ID.equals(id);
        CellItemData data = data(cell);
        CellDefinition def = CellDefinition.byItemId(id);

        cmd.set("#CellIcon.Visible", true);
        cmd.set("#CellIcon.ItemId", id);
        cmd.set("#CellName.Text", Message.translation("ae2.items." + id + ".name"));
        if (creative) {
            cmd.set("#CellInfo.Text", Message.translation("ae2ui.workbench.creativeInfo"));
        } else {
            StorageCell inv = StorageCells.getCellInventory(id, data, () -> { });
            if (inv instanceof BasicCellInventory b) {
                cmd.set("#CellInfo.Text", Message.translation("ae2ui.workbench.cellInfo")
                        .param("used", TerminalPage.formatAmount(b.getUsedBytes()))
                        .param("total", TerminalPage.formatAmount(b.getTotalBytes()))
                        .param("types", Long.toString(b.getStoredItemTypes()))
                        .param("maxTypes", Long.toString(b.getTotalItemTypes())));
            } else {
                cmd.set("#CellInfo.Text", "");
            }
        }
        cmd.set("#CellHint.Text", Message.translation(creative ? "ae2ui.workbench.creativeHint" : "ae2ui.workbench.partitionHint"));
        List<AEKey> config = data.getConfigKeys();
        cmd.set("#ConfigHeader.Text", Message.translation("ae2ui.workbench.configHeader")
                .param("count", Integer.toString(config.size())).param("max", Integer.toString(MAX_CONFIG)));

        int i = 0;
        for (AEKey key : config) {
            if (!(key instanceof AEItemKey ik)) {
                continue;
            }
            String sel = "#Config[" + i + "]";
            cmd.append("#Config", ENTRY);
            cmd.set(sel + " #Icon.ItemId", ik.itemId());
            cmd.set(sel + " #Quantity.Text", creative ? "∞" : "");
            evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                    new EventData().append("Action", "RemoveConfig").append("Index", Integer.toString(i)), false);
            i++;
        }

        boolean upgradable = def != null && def.upgradeSlots() > 0;
        List<String> cards = data.getUpgrades();
        cmd.set("#UpgradeHeader.Text", upgradable
                ? Message.translation("ae2ui.workbench.upgradeHeader").param("count", Integer.toString(cards.size()))
                        .param("max", Integer.toString(def.upgradeSlots()))
                : Message.translation("ae2ui.workbench.noUpgrades"));
        cmd.set("#InsertCard.Visible", upgradable);
        for (int c = 0; c < cards.size(); c++) {
            String sel = "#Upgrades[" + c + "]";
            cmd.append("#Upgrades", ENTRY);
            cmd.set(sel + " #Icon.ItemId", cards.get(c));
            cmd.set(sel + " #Quantity.Text", "");
            evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                    new EventData().append("Action", "RemoveCard").append("Index", Integer.toString(c)), false);
        }
        boolean fuzzy = cards.contains(UpgradeCard.FUZZY.itemId);
        cmd.set("#FuzzyMode.Visible", fuzzy);
        if (fuzzy) {
            cmd.set("#FuzzyMode.Text", Message.translation("ae2ui.workbench.fuzzy." + data.getFuzzyMode().name().toLowerCase()));
        }
    }

    // ------------------------------------------------------------------ Ereignisse

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (!de.fellurion.ae2.hytale.security.AE2Security.checkUse(WorldNetworks.of(world), x, y, z, playerRef)) {
            close(); // Zugriff entzogen, während die Seite offen war
            return;
        }
        ItemStack cell = cellStack();
        if (cell == null || p.action == null) {
            refresh();
            return;
        }
        CellItemData data = data(cell);
        List<AEKey> config = new ArrayList<>(data.getConfigKeys());
        switch (p.action) {
            case "AddHeld" -> {
                AEItemKey key = ItemKeys.of(InventoryComponent.getItemInHand(store, ref));
                if (key != null && !config.contains(key) && config.size() < MAX_CONFIG && !StorageCells.isCellItem(key.itemId())) {
                    config.add(key.nbt() == null ? key : AEItemKey.of(key.itemId()));
                }
                data.setConfig(config);
            }
            case "AddInventory" -> {
                InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
                InventoryComponent.Storage storage = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
                for (InventoryComponent inv : new InventoryComponent[]{hotbar, storage}) {
                    if (inv == null) {
                        continue;
                    }
                    ItemContainer c = inv.getInventory();
                    for (short s = 0; s < c.getCapacity() && config.size() < MAX_CONFIG; s++) {
                        AEItemKey key = ItemKeys.of(c.getItemStack(s));
                        if (key != null && !StorageCells.isCellItem(key.itemId())) {
                            AEItemKey plain = AEItemKey.of(key.itemId());
                            if (!config.contains(plain)) {
                                config.add(plain);
                            }
                        }
                    }
                }
                data.setConfig(config);
            }
            case "FromContents" -> {
                List<AEKey> keys = new ArrayList<>();
                for (AEKey k : data.getContents().keySet()) {
                    if (keys.size() < MAX_CONFIG) {
                        keys.add(k);
                    }
                }
                data.setConfig(keys);
            }
            case "Clear" -> data.setConfig(List.of());
            case "RemoveConfig" -> {
                int i = parse(p.index);
                if (i >= 0 && i < config.size()) {
                    config.remove(i);
                    data.setConfig(config);
                }
            }
            case "InsertCard" -> insertCard(ref, store, cell, data);
            case "RemoveCard" -> {
                int i = parse(p.index);
                List<String> cards = new ArrayList<>(data.getUpgrades());
                if (i >= 0 && i < cards.size()) {
                    String card = cards.remove(i);
                    data.setUpgrades(cards);
                    give(ref, store, new ItemStack(card, 1));
                }
            }
            case "FuzzyMode" -> {
                FuzzyMode[] modes = FuzzyMode.values();
                data.setFuzzyMode(modes[(data.getFuzzyMode().ordinal() + 1) % modes.length]);
            }
            default -> {
            }
        }
        write(cell, data);
        refresh();
    }

    private void insertCard(Ref<EntityStore> ref, Store<EntityStore> store, ItemStack cell, CellItemData data) {
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        CellDefinition def = CellDefinition.byItemId(cell.getItemId());
        if (hotbar == null || def == null) {
            return;
        }
        short slot = hotbar.getActiveSlot();
        ItemStack held = hotbar.getInventory().getItemStack(slot);
        UpgradeCard card = held == null ? null : UpgradeCard.byItemId(held.getItemId());
        if (card == null) {
            return;
        }
        UpgradeInventory upgrades = new UpgradeInventory(cell.getItemId(), def.upgradeSlots());
        upgrades.fromItemIds(data.getUpgrades());
        if (upgrades.insert(card)) {
            data.setUpgrades(upgrades.toItemIds());
            hotbar.getInventory().removeItemStackFromSlot(slot, 1);
        }
    }

    private static void give(Ref<EntityStore> ref, Store<EntityStore> store, ItemStack stack) {
        SimpleItemContainer.addOrDropItemStack(store, ref,
                InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST), stack);
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder evt = new UIEventBuilder();
        fill(cmd, evt);
        sendUpdate(cmd, evt, false);
    }

    public static final class Payload {
        String action;
        String index;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (o, v) -> o.index = v, o -> o.index).add()
                .build();
    }
}
