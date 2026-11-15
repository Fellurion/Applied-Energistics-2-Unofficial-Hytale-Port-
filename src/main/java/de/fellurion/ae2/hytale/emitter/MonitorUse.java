/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.reporting.AbstractMonitorPart#onUseItemOn / #onUseWithoutItem,
 * appeng.parts.reporting.ConversionMonitorPart#onUseItemOn / #onUseWithoutItem / #onClicked).
 */
package de.fellurion.ae2.hytale.emitter;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import de.fellurion.ae2.core.devices.StorageMonitorLogic;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.crafting.CraftingAccess;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;

/**
 * Was „Benutzen“ an einem Storage Monitor bzw. Conversion Monitor tut. AE2 unterscheidet Rechtsklick, geduckten
 * Rechtsklick und Linksklick; Hytale hat für Blöcke nur „Benutzen“, also:
 *
 * <table>
 *   <caption>Gesten</caption>
 *   <tr><th>Geste</th><th>Storage Monitor</th><th>Conversion Monitor</th></tr>
 *   <tr><td>geduckt benutzen</td><td>sperren / entsperren</td><td>mit dem gezeigten Item in der Hand: alle
 *   passenden Items aus dem Inventar einlagern (AE2: Rechtsklick mit leerer Hand am gesperrten Monitor); sonst
 *   sperren / entsperren</td></tr>
 *   <tr><td>benutzen, entsperrt, Item in der Hand</td><td>Item anzeigen</td><td>gezeigtes Item: gehaltenen Stapel
 *   einlagern; anderes Item: anzeigen</td></tr>
 *   <tr><td>benutzen, entsperrt, leere Hand</td><td colspan="2">Anzeige leeren</td></tr>
 *   <tr><td>benutzen, gesperrt, Item in der Hand</td><td>–</td><td>gehaltenen Stapel einlagern (AE2: jedes
 *   Item)</td></tr>
 *   <tr><td>benutzen, gesperrt, leere Hand</td><td>–</td><td>einen Stapel entnehmen (AE2: Linksklick); ist nichts
 *   da, das Item aber herstellbar: Craft-Anfrage öffnen</td></tr>
 * </table>
 *
 * <p>Nicht übernommen: AE2s „ein Stück entnehmen“ (geduckter Linksklick) – dafür ist keine Geste frei. Wie in AE2
 * geschieht nichts, solange der Monitor nicht am Netz ist. Der Zugriff auf das Netz ist beim Aufruf schon geprüft
 * ({@code UseHandlers.handle}).
 *
 * <p>Items liegen zu jedem Zeitpunkt an genau einer Stelle: erst aus dem Feld nehmen, dann ins Netz, den Rest
 * zurück – und umgekehrt beim Entnehmen.
 */
final class MonitorUse {

    private MonitorUse() {
    }

    static void use(UseContext ctx) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (node == null || !(node.device() instanceof MonitorDevice device)) {
            return; // im Tick des Platzierens ist das Gerät noch nicht angemeldet
        }
        StorageMonitorLogic logic = device.logic();
        if (!logic.isActive()) {
            tell(ctx, Message.translation("ae2emitter.monitor.offline"), NotificationStyle.Warning);
            return;
        }
        ItemStack held = ctx.itemInHand();
        AEItemKey heldKey = ItemKeys.of(held);
        boolean crouching = UseHandlers.isCrouching(ctx);
        boolean matches = heldKey != null && heldKey.equals(logic.getConfiguredItem());

        if (device.conversion()) {
            if (crouching && matches) {
                insertAll(ctx, logic, heldKey);
                return;
            }
            if (!crouching && heldKey != null && (matches || logic.isLocked())) {
                insertHeld(ctx, logic, heldKey);
                return;
            }
            if (!crouching && logic.isLocked()) {
                extractStack(ctx, nets, device);
                return;
            }
        }
        if (crouching) {
            if (logic.toggleLocked()) {
                tell(ctx, Message.translation(logic.isLocked() ? "ae2emitter.monitor.locked"
                        : "ae2emitter.monitor.unlocked"), NotificationStyle.Default);
            }
            return;
        }
        if (logic.isLocked()) {
            tell(ctx, Message.translation("ae2emitter.monitor.isLocked"), NotificationStyle.Warning);
            return;
        }
        logic.configure(heldKey); // leere Hand leert die Anzeige
    }

    // ------------------------------------------------------------------ Conversion Monitor

    /** Lagert den gehaltenen Stapel ein (AE2: {@code insertItem}). */
    private static void insertHeld(UseContext ctx, StorageMonitorLogic logic, AEItemKey key) {
        InventoryComponent.Hotbar hotbar = ctx.entityStore().getComponent(ctx.playerEntity(),
                InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return;
        }
        short slot = hotbar.getActiveSlot();
        ItemContainer container = hotbar.getInventory();
        if (slot < 0 || slot >= container.getCapacity()) {
            return;
        }
        // Eingelagert wird aus dem aktiven Feld der Schnellleiste – nur, wenn dort wirklich das gehaltene Item liegt
        // (es kann auch aus der Zweithand stammen)
        if (!key.equals(ItemKeys.of(container.getItemStack(slot)))) {
            return;
        }
        long moved = storeSlot(ctx, logic, container, slot, key);
        report(ctx, moved, key, "ae2emitter.monitor.inserted", "ae2emitter.monitor.insertFailed");
    }

    /** Lagert alle passenden Items aus Schnellleiste, Inventar und Rucksack ein (AE2: {@code insertAllItem}). */
    private static void insertAll(UseContext ctx, StorageMonitorLogic logic, AEItemKey key) {
        long moved = 0;
        for (var type : InventoryComponent.HOTBAR_STORAGE_BACKPACK) {
            InventoryComponent part = ctx.entityStore().getComponent(ctx.playerEntity(), type);
            ItemContainer container = part == null ? null : part.getInventory();
            if (container == null) {
                continue;
            }
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (!ItemStack.isEmpty(stack) && key.itemId().equals(stack.getItemId())
                        && key.equals(ItemKeys.of(stack))) {
                    moved += storeSlot(ctx, logic, container, slot, key);
                }
            }
        }
        report(ctx, moved, key, "ae2emitter.monitor.inserted", "ae2emitter.monitor.insertFailed");
    }

    /**
     * Nimmt den Stapel aus dem Feld, lagert ihn ein und legt zurück, was das Netz nicht genommen hat.
     *
     * @return eingelagerte Menge
     */
    private static long storeSlot(UseContext ctx, StorageMonitorLogic logic, ItemContainer container, short slot,
                                  AEItemKey key) {
        ItemStack stack = container.getItemStack(slot);
        if (ItemStack.isEmpty(stack)) {
            return 0;
        }
        int count = stack.getQuantity();
        ItemStackSlotTransaction tx = container.removeItemStackFromSlot(slot, count, true, false);
        if (!tx.succeeded()) {
            return 0;
        }
        ItemStack taken = ItemStack.isEmpty(tx.getOutput()) ? stack : tx.getOutput();
        long inserted;
        try {
            inserted = logic.insert(key, taken.getQuantity(), source(ctx, logic));
        } catch (RuntimeException e) {
            inserted = 0;
        }
        int rest = (int) Math.max(0, taken.getQuantity() - inserted);
        if (rest > 0) {
            ItemStack left = container.addItemStackToSlot(slot, taken.withQuantity(rest)).getRemainder();
            if (!ItemStack.isEmpty(left)) {
                give(ctx, null, left);
            }
        }
        return Math.max(0, inserted);
    }

    /** Entnimmt einen Stapel des gezeigten Items ins Inventar (AE2: {@code onClicked} → {@code extractItem}). */
    private static void extractStack(UseContext ctx, WorldNetworks nets, MonitorDevice device) {
        StorageMonitorLogic logic = device.logic();
        AEItemKey key = device.item();
        if (key == null) {
            return;
        }
        if (logic.getAmount() == 0 && logic.canCraft()) {
            // AE2: CraftAmountMenu
            CraftingAccess.openCraftRequest(ctx.player(), ctx.playerRef(), ctx.playerEntity(), ctx.entityStore(), nets,
                    ctx.pos().x, ctx.pos().y, ctx.pos().z, key);
            return;
        }
        long got;
        try {
            got = logic.extract(ItemKeys.maxStack(key.itemId()), source(ctx, logic));
        } catch (RuntimeException e) {
            got = 0;
        }
        if (got > 0) {
            give(ctx, logic, ItemKeys.toStack(key, (int) got));
        }
        report(ctx, got, key, "ae2emitter.monitor.extracted", "ae2emitter.monitor.extractFailed");
    }

    /**
     * Gibt dem Spieler einen Stapel: ins Inventar (Schnellleiste zuerst); was nicht passt, geht – wenn {@code logic}
     * gegeben ist – zurück ins Netz, sonst und im Notfall vor die Füße.
     */
    private static void give(UseContext ctx, @Nullable StorageMonitorLogic logic, ItemStack stack) {
        ItemContainer inventory = InventoryComponent.getCombined(ctx.commandBuffer(), ctx.playerEntity(),
                InventoryComponent.HOTBAR_FIRST);
        ItemStack rest = inventory.addItemStack(stack).getRemainder();
        if (ItemStack.isEmpty(rest)) {
            return;
        }
        long back = 0;
        AEItemKey key = logic == null ? null : ItemKeys.of(rest);
        if (key != null) {
            try {
                back = logic.insert(key, rest.getQuantity(), source(ctx, logic));
            } catch (RuntimeException e) {
                back = 0;
            }
        }
        if (back < rest.getQuantity()) {
            // im laufenden Durchlauf des Entity-Stores: über den CommandBuffer fallen lassen
            ItemUtils.dropItem(ctx.playerEntity(), rest.withQuantity((int) (rest.getQuantity() - back)),
                    ctx.commandBuffer());
        }
    }

    /** AE2: {@code new PlayerSource(player, this)}. */
    private static IActionSource source(UseContext ctx, StorageMonitorLogic logic) {
        return IActionSource.ofPlayer(ctx.playerRef().getUuid(), logic);
    }

    // ------------------------------------------------------------------ Meldungen

    private static void report(UseContext ctx, long moved, AEItemKey key, String okKey, String failKey) {
        Message item = Message.translation(itemNameKey(key));
        if (moved > 0) {
            tell(ctx, Message.translation(okKey).param("amount", TerminalTexts.formatAmount(moved))
                    .param("item", item), NotificationStyle.Success);
        } else {
            tell(ctx, Message.translation(failKey).param("item", item), NotificationStyle.Warning);
        }
    }

    private static String itemNameKey(AEItemKey key) {
        var item = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(key.itemId());
        return item != null && item.getTranslationKey() != null ? item.getTranslationKey() : key.itemId();
    }

    private static void tell(UseContext ctx, Message message, NotificationStyle style) {
        try {
            NotificationUtil.sendNotification(ctx.playerRef().getPacketHandler(), message, style);
        } catch (RuntimeException e) {
            // Benachrichtigung ging nicht (Verbindung weg) – dann eben ohne
        }
    }
}
