/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.WirelessTerminalItem / WirelessCraftingTerminalItem,
 * appeng.menu.implementations.WirelessAccessPointMenu#onChangeInventory).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.core.wireless.LinkStatus;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.items.PoweredItems;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.ui.TerminalOpener;
import org.joml.Vector3d;

/**
 * Wireless Terminal und Wireless Crafting Terminal in der Hand.
 * <ul>
 *   <li><b>Verknüpfen:</b> Rechtsklick mit dem Terminal auf einen ME Wireless Access Point (oder ihn mit dem Terminal
 *       in der Hand benutzen). AE2 hat dafür im Fenster des Access Points einen Slot, in den man das Terminal legt;
 *       hier übernimmt der Klick dessen Rolle.</li>
 *   <li><b>Öffnen:</b> Rechtsklick sonstwohin öffnet das Terminal auf dem verknüpften Netz – wenn das Terminal
 *       verknüpft ist, der Access Point geladen und aktiv, der Spieler in Reichweite eines aktiven Access Points
 *       dieses Netzes steht, der Akku nicht leer ist und der Spieler das Netz benutzen darf. Sonst erklärt eine
 *       Meldung, woran es liegt.</li>
 * </ul>
 */
final class WirelessTerminals {

    private WirelessTerminals() {
    }

    /** Rechtsklick mit dem Terminal in der Hand. */
    static void use(ItemUseContext ctx, boolean crafting) {
        if (ctx.primary()) {
            return;
        }
        WorldNetworks.Node target = ctx.targetNode();
        if (target != null && WirelessAccessPointDevice.KIND.equals(target.kind())) {
            UseContext use = ctx.asBlockUse();
            // nur wer das Netz benutzen darf, kann ein Terminal damit verknüpfen
            if (use != null && AE2Security.checkUse(use)) {
                ItemStack linked = link(ctx.world(), target, ctx.held());
                if (linked != null && ctx.setHeld(linked)) {
                    ctx.playerRef().sendMessage(Message.translation("ae2wireless.msg.linked"));
                }
            }
            return;
        }
        open(ctx, crafting);
    }

    /**
     * Benutzen (F) des Access Points: mit einem Wireless Terminal in der Hand wird es verknüpft, sonst öffnet sich
     * der Booster-Slot. Der Zugriff auf das Netz ist hier schon geprüft.
     */
    static void useAccessPoint(UseContext ctx) {
        ItemStack held = ctx.itemInHand();
        if (!WirelessLink.linkable(held)) {
            AE2UseBlockInteraction.openContainerWindow(ctx);
            return;
        }
        WorldNetworks.Node node = WorldNetworks.of(ctx.world()).node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        InventoryComponent.Hotbar hotbar = ctx.entityStore().getComponent(ctx.playerEntity(),
                InventoryComponent.Hotbar.getComponentType());
        if (node == null || hotbar == null) {
            return;
        }
        short slot = hotbar.getActiveSlot();
        if (slot < 0 || slot >= hotbar.getInventory().getCapacity()) {
            return;
        }
        // Das gehaltene Terminal kann auch aus der Zweithand stammen: nur schreiben, wenn es im aktiven Feld liegt
        ItemStack active = hotbar.getInventory().getItemStack(slot);
        if (ItemStack.isEmpty(active) || !(active == held || active.equals(held))) {
            return;
        }
        ItemStack linked = link(ctx.world(), node, active);
        if (linked != null && hotbar.getInventory().setItemStackForSlot(slot, linked).succeeded()) {
            ctx.playerRef().sendMessage(Message.translation("ae2wireless.msg.linked"));
        }
    }

    /**
     * Verknüpft das Terminal mit dem Access Point (AE2: {@code IGridLinkableHandler.link(stack, accessPoint.getGlobalPos())}).
     *
     * @return der verknüpfte Stack, oder null, wenn das Item nicht verknüpfbar oder der Block kein Access Point ist
     */
    static ItemStack link(World world, WorldNetworks.Node accessPoint, ItemStack terminal) {
        if (!WirelessLink.linkable(terminal) || !(accessPoint.device() instanceof WirelessAccessPointDevice)) {
            return null;
        }
        String id = WirelessAccessPointDevice.ensureLinkId(accessPoint);
        return new WirelessLink(world.getName(), accessPoint.x(), accessPoint.y(), accessPoint.z(), id).applyTo(terminal);
    }

    /** Öffnet das Terminal auf dem verknüpften Netz oder sagt, warum das nicht geht. */
    private static void open(ItemUseContext ctx, boolean crafting) {
        ItemStack held = ctx.held();
        if (held.getQuantity() != 1) {
            return;
        }
        WirelessLink link = WirelessLink.of(held);
        if (link == null) {
            deny(ctx, LinkStatus.NOT_LINKED);
            return;
        }
        Vector3d pos = position(ctx);
        if (pos == null) {
            return;
        }
        int section = ctx.interaction().getHeldItemSectionId();
        short slot = ctx.interaction().getHeldItemSlot();
        WirelessTerminalSession session = new WirelessTerminalSession(ctx.playerRef(), ItemPlace.of(ctx.playerRef()),
                section, slot, held, link, ctx.world(), pos.x, pos.y, pos.z);
        LinkStatus status = session.host().getLinkStatus();
        if (status.connected() && !PoweredItems.has(held, 1)) {
            // ganz nah am Access Point kostet der Leerlauf nichts – ein leerer Akku öffnet trotzdem nicht
            status = LinkStatus.OUT_OF_POWER;
        }
        if (!status.connected()) {
            ctx.playerRef().sendMessage(Message.translation(session.failureKey(status)));
            return;
        }
        if (!session.accessPointActive()) {
            // ein anderer Access Point des Netzes ist in Reichweite, der verknüpfte selbst hat aber keinen Kanal
            ctx.playerRef().sendMessage(Message.translation("ae2wireless.msg.networkOffline"));
            return;
        }
        // prüft den Zugriff auf das Netz (Meldung „gehört X“) und beginnt die Sitzung
        TerminalOpener.open(ctx, "ae2.items." + held.getItemId() + ".name",
                new WirelessTerminalBackend(WorldNetworks.of(ctx.world()), session, ctx.playerRef().getUuid()), crafting);
    }

    private static void deny(ItemUseContext ctx, LinkStatus status) {
        ctx.playerRef().sendMessage(Message.translation(HeldItemSession.messageKey(status)));
    }

    private static Vector3d position(ItemUseContext ctx) {
        try {
            TransformComponent t = ctx.commandBuffer().getComponent(ctx.playerEntity(), TransformComponent.getComponentType());
            return t == null ? null : t.getPosition();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
