/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.hooks.WrenchHook, appeng.items.tools.NetworkToolItem, appeng.items.tools.MemoryCardItem,
 * appeng.items.tools.quartz.QuartzCuttingKnifeItem).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.tools.MemoryCardSettings.Part;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.ItemUse;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.security.AE2Security;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Rechtsklick mit einem der Handwerkzeuge. Hier steht nur, wer was darf und was der Spieler dazu erfährt; die
 * eigentliche Arbeit machen {@link Wrenches}, {@link MemoryCards} und die Seiten.
 *
 * <ul>
 *   <li><b>Schraubenschlüssel</b> (Certus und Nether): Rechtsklick auf einen AE2-Block dreht ihn, geduckt +
 *   Rechtsklick baut ihn ab.</li>
 *   <li><b>Network Tool:</b> Rechtsklick auf einen Block eines ME-Netzes zeigt den Netzwerkstatus, sonst öffnet sich
 *   die Werkzeugkiste; geduckt + Rechtsklick auf einen AE2-Block baut ihn ab (AE2: das Network Tool zählt als
 *   Schraubenschlüssel, dreht aber nicht).</li>
 *   <li><b>Memory Card:</b> geduckt + Rechtsklick auf ein Gerät speichert, Rechtsklick überträgt, geduckt +
 *   Rechtsklick woandershin löscht die Karte.</li>
 *   <li><b>Schneidmesser:</b> Rechtsklick öffnet die Seite für die Namenspresse.</li>
 * </ul>
 *
 * <p>Alles am Block prüft zuerst den Zugriff auf das ME-Netz ({@link AE2Security#checkUse}). Drehen fragt zusätzlich
 * über {@code UseBlockEvent.Pre}, ob ein Schutz-Plugin etwas dagegen hat; Abbauen tut das über das
 * {@code BreakBlockEvent} des normalen Abbau-Wegs.
 */
final class ToolItems {

    static final String CERTUS_WRENCH = "AE2_Certus_Quartz_Wrench";
    static final String NETHER_WRENCH = "AE2_Nether_Quartz_Wrench";
    static final String CERTUS_KNIFE = "AE2_Certus_Quartz_Cutting_Knife";
    static final String NETHER_KNIFE = "AE2_Nether_Quartz_Cutting_Knife";

    private ToolItems() {
    }

    static void register() {
        ItemUse.register(CERTUS_WRENCH, ToolItems::useWrench);
        ItemUse.register(NETHER_WRENCH, ToolItems::useWrench);
        ItemUse.register(Toolbox.ITEM_ID, ToolItems::useNetworkTool);
        ItemUse.register(MemoryCardData.ITEM_ID, ToolItems::useMemoryCard);
        ItemUse.register(CERTUS_KNIFE, KnifePage::open);
        ItemUse.register(NETHER_KNIFE, KnifePage::open);
    }

    static boolean isKnife(@Nullable ItemStack stack) {
        return !ItemStack.isEmpty(stack)
                && (CERTUS_KNIFE.equals(stack.getItemId()) || NETHER_KNIFE.equals(stack.getItemId()));
    }

    // ------------------------------------------------------------------ Meldungen

    static void notify(PlayerRef player, Message text, @Nullable Message detail, NotificationStyle style) {
        try {
            NotificationUtil.sendNotification(player.getPacketHandler(), text, detail, null, null, style);
        } catch (RuntimeException e) {
            // Benachrichtigung ging nicht (Verbindung weg) – dann eben ohne
        }
    }

    private static void ok(ItemUseContext ctx, String key) {
        notify(ctx.playerRef(), Message.translation(key), null, NotificationStyle.Success);
    }

    private static void warn(ItemUseContext ctx, String key) {
        notify(ctx.playerRef(), Message.translation(key), null, NotificationStyle.Warning);
    }

    // ------------------------------------------------------------------ Schraubenschlüssel

    private static void useWrench(ItemUseContext ctx) {
        UseContext use = ctx.primary() ? null : ctx.asBlockUse();
        if (use == null || !AE2Security.checkUse(use)) {
            return; // kein AE2-Block anvisiert, oder das Netz gehört jemand anderem (Meldung kam schon)
        }
        if (ctx.crouching()) {
            dismantle(ctx);
        } else {
            rotate(ctx);
        }
    }

    private static void rotate(ItemUseContext ctx) {
        Vector3i pos = ctx.targetBlock();
        if (!Wrenches.isRotatable(ctx.world(), pos.x, pos.y, pos.z)) {
            warn(ctx, "ae2tools.wrench.notRotatable");
            return;
        }
        // Schutz-Plugins fragen: Drehen zählt als Benutzen des Blocks
        BlockType type = BlockAccess.blockType(ctx.world(), pos.x, pos.y, pos.z);
        if (type != null) {
            UseBlockEvent.Pre event = new UseBlockEvent.Pre(ctx.type(), ctx.interaction(), new Vector3i(pos), type);
            ctx.commandBuffer().invoke(ctx.playerEntity(), event);
            if (event.isCancelled()) {
                warn(ctx, "ae2tools.wrench.denied");
                return;
            }
        }
        if (Wrenches.rotate(ctx.world(), pos.x, pos.y, pos.z) == Wrenches.Rotated.NOT_ROTATABLE) {
            warn(ctx, "ae2tools.wrench.notRotatable");
        }
    }

    /** Abbauen mit Schraubenschlüssel oder Network Tool; der Zugriff auf das Netz ist schon geprüft. */
    private static void dismantle(ItemUseContext ctx) {
        Vector3i pos = ctx.targetBlock();
        Wrenches.Dismantling done = Wrenches.dismantle(ctx.world(), pos.x, pos.y, pos.z, ctx.playerEntity(), ctx.held(),
                ctx.commandBuffer());
        if (done.result() == Wrenches.Dismantled.NOT_ALLOWED || done.result() == Wrenches.Dismantled.VETOED) {
            warn(ctx, "ae2tools.wrench.denied");
        }
    }

    // ------------------------------------------------------------------ Network Tool

    private static void useNetworkTool(ItemUseContext ctx) {
        if (ctx.primary()) {
            return;
        }
        UseContext use = ctx.asBlockUse();
        if (use != null) {
            WorldNetworks.Node node = ctx.targetNode();
            boolean crouching = ctx.crouching();
            boolean networked = node != null && node.gridNode() != null;
            if (crouching || networked) {
                if (!AE2Security.checkUse(use)) {
                    return;
                }
                if (crouching) {
                    dismantle(ctx);
                } else {
                    NetworkStatusPage.open(ctx);
                }
                return;
            }
        }
        // in die Luft, auf einen fremden Block oder einen AE2-Block ohne Netz: die Werkzeugkiste
        ToolboxPage.open(ctx);
    }

    // ------------------------------------------------------------------ Memory Card

    private static void useMemoryCard(ItemUseContext ctx) {
        if (ctx.primary()) {
            return;
        }
        boolean crouching = ctx.crouching();
        UseContext use = ctx.asBlockUse();
        WorldNetworks.Node node = use == null ? null : ctx.targetNode();
        if (use == null) {
            // AE2: geduckt benutzt, ohne ein Gerät zu treffen, löscht die Karte (und meldet das auch bei einer leeren)
            if (crouching && (MemoryCardData.read(ctx.held()) == null
                    || ctx.setHeld(MemoryCardData.write(ctx.held(), null)))) {
                ok(ctx, "ae2tools.card.cleared");
            }
            return;
        }
        if (!MemoryCards.isMachine(node)) {
            return; // gerade erst platziert, oder ein Block ohne Gerät (Glas): nichts, was die Karte kopieren könnte
        }
        if (!AE2Security.checkUse(use)) {
            return;
        }
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        String lang = AE2Language.of(ctx.playerRef());
        if (crouching) {
            MemoryCardData data = MemoryCards.save(nets, node, lang);
            if (data != null && ctx.setHeld(MemoryCardData.write(ctx.held(), data))) {
                ok(ctx, "ae2tools.card.saved");
            }
            return;
        }
        MemoryCardData data = MemoryCardData.read(ctx.held());
        if (data == null) {
            warn(ctx, "ae2tools.card.invalid"); // leere Karte (AE2: nichts übernommen)
            return;
        }
        // der CommandBuffer der Interaktion: Karten, die vor die Füße fallen, entstehen erst nach dem Durchlauf
        MemoryCards.Loaded loaded = MemoryCards.load(nets, node, data, PlayerItems.cards(ctx.commandBuffer(),
                ctx.playerEntity(), ctx.world(), new Vector3i(node.x(), node.y(), node.z())), lang);
        report(ctx, loaded);
    }

    private static void report(ItemUseContext ctx, MemoryCards.Loaded loaded) {
        Message missing = missing(loaded.missing());
        switch (loaded.outcome()) {
            case LOADED -> notify(ctx.playerRef(), Message.translation("ae2tools.card.loaded"), missing,
                    missing == null ? NotificationStyle.Success : NotificationStyle.Warning);
            case PARTIAL -> notify(ctx.playerRef(), Message.translation("ae2tools.card.partial")
                    .param("parts", parts(loaded)), missing, NotificationStyle.Warning);
            case FAILED -> warn(ctx, "ae2tools.card.failed");
            default -> warn(ctx, "ae2tools.card.invalid");
        }
    }

    /** „Upgrades, Einstellungen, Filter“ – die übernommenen Teile in AE2s Reihenfolge. */
    private static Message parts(MemoryCards.Loaded loaded) {
        Message out = Message.empty();
        boolean first = true;
        for (Part part : Part.values()) {
            if (!loaded.parts().contains(part)) {
                continue;
            }
            if (!first) {
                out.insert(Message.raw(", "));
            }
            first = false;
            out.insert(Message.translation("ae2tools.card.part." + part.id()));
        }
        return out;
    }

    /** „Nicht genug … (es fehlen n)“ je Kartenart, oder null, wenn nichts fehlt. */
    @Nullable
    private static Message missing(Map<UpgradeCard, Integer> missing) {
        if (missing.isEmpty()) {
            return null;
        }
        Message out = Message.empty();
        boolean first = true;
        for (Map.Entry<UpgradeCard, Integer> e : missing.entrySet()) {
            if (!first) {
                out.insert(Message.raw("\n"));
            }
            first = false;
            out.insert(Message.translation("ae2tools.card.missingUpgrades")
                    .param("card", Message.translation("ae2.items." + e.getKey().itemId + ".name"))
                    .param("count", Integer.toString(e.getValue())));
        }
        return out;
    }

    // ------------------------------------------------------------------ Tooltip der Memory Card

    /** Zusatzzeile der Memory Card: wovon sie stammt (AE2: Name der Quelle bzw. „Blank“). */
    @Nullable
    static Message cardTooltip(ItemStack stack) {
        if (!MemoryCardData.isCard(stack)) {
            return null;
        }
        MemoryCardData data = MemoryCardData.read(stack);
        if (data == null) {
            return null; // leere Karte: keine Metadaten, damit sie bleibt, wie sie aus der Werkbank kommt
        }
        Message source = data.nameKey() != null ? Message.translation(data.nameKey()) : Message.raw(data.type());
        return Message.translation("ae2tools.card.tooltip.source").param("source", source);
    }
}
