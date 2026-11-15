/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.ColorApplicatorItem, appeng.parts.networking.CablePart#changeColor).
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.tools.PaintItems;
import de.fellurion.ae2.core.tools.PaintStorage;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.ItemTooltips;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.items.PoweredItems;
import de.fellurion.ae2.hytale.security.AE2Security;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Color Applicator: Rechtsklick auf ein AE2-Kabel färbt es mit der gewählten Farbe ({@value #ENERGY_PER_USE} AE und
 * eine Farbkugel je Kabel); mit der Reinigung (Schnee) wird es wieder ein ungefärbtes Fluix-Kabel. Geduckt
 * Rechtsklick auf einen Block oder ein Kabel, das die Farbe schon hat, wechselt zur nächsten geladenen Farbe;
 * geduckt Rechtsklick in die Luft lädt Farbkugeln und Schnee aus dem Inventar.
 *
 * <p>Ein Kabel ist hier je Farbe ein eigener Block: Umfärben ersetzt den Block an Ort und Stelle durch den der
 * anderen Farbe – über Hytales Setzen für den Spieler ({@link WorldEdits}). Wie in AE2 ({@code changeColor} tauscht
 * dort das Kabel-Teil aus) meldet sich das Kabel dabei ab und neu an; das Netz findet sich im nächsten Takt wieder.
 *
 * <p>Abweichungen von AE2: Der Applicator ist keine Speicherzelle – die Farbe steht je Farbton als Anzahl in den
 * Item-Metadaten ({@link PaintStorage}, Größe wie AE2s Zelle) und kommt aus dem Inventar statt aus einer ME Chest;
 * entladen lässt er sich nicht. Als Farbe zählen Farbkugeln (leuchtende wie normale) und Schnee zum Reinigen,
 * keine Farbstoffe. Gefärbt werden nur AE2-Kabel; Farbkleckse, Schafe und andere Blöcke gibt es hier nicht.
 */
final class ColorApplicator {

    static final String ITEM = "AE2_Color_Applicator";
    /** AE2: {@code POWER_PER_USE}. */
    static final int ENERGY_PER_USE = (int) PaintStorage.ENERGY_PER_USE;
    /** Metadaten: geladene Farbe als Text ({@link PaintStorage#format()}). */
    static final String PAINT_KEY = "AE2Paint";
    /** Metadaten: gewählte Farbe (Id wie {@code light_blue}, {@code fluix} = Reinigung). */
    static final String SELECTED_KEY = "AE2PaintSelected";

    private ColorApplicator() {
    }

    static void setup() {
        ItemTooltips.addSection(ColorApplicator::tooltip);
    }

    // ------------------------------------------------------------------ Farbe im Item

    static PaintStorage paint(ItemStack applicator) {
        return PaintStorage.parse(applicator.getFromMetadataOrNull(PAINT_KEY, Codec.STRING));
    }

    /** AE2: {@code getColor} – die gewählte Farbe, sonst die erste geladene. */
    @Nullable
    static AEColor activeColor(ItemStack applicator) {
        String id = applicator.getFromMetadataOrNull(SELECTED_KEY, Codec.STRING);
        return paint(applicator).active(id == null ? null : PaintStorage.colorById(id));
    }

    static ItemStack with(ItemStack applicator, PaintStorage paint, @Nullable AEColor selected) {
        ItemStack out = applicator.withMetadata(PAINT_KEY, Codec.STRING, paint.format())
                .withMetadata(SELECTED_KEY, Codec.STRING, selected == null ? null : selected.id);
        return ItemTooltips.refresh(out);
    }

    /** Name der Farbe (AE2s eigene Farbnamen); die Reinigung heißt „Reinigung“. */
    static Message colorName(AEColor color) {
        if (color == AEColor.TRANSPARENT) {
            return Message.translation("ae2gadget.applicator.cleaning");
        }
        return Message.translation("ae2gui.gui.ae2." + PaintItems.title(color).replace("_", ""));
    }

    @Nullable
    private static Message tooltip(ItemStack stack) {
        if (!ITEM.equals(stack.getItemId())) {
            return null;
        }
        PaintStorage paint = paint(stack);
        if (paint.isEmpty()) {
            return Message.translation("ae2gadget.applicator.tooltip.empty");
        }
        AEColor active = activeColor(stack);
        Message out = Message.empty();
        if (active != null) {
            out.insert(Message.translation("ae2gadget.applicator.tooltip.selected").param("color", colorName(active)));
        }
        for (Map.Entry<AEColor, Integer> e : paint.amounts().entrySet()) {
            out.insert(Message.raw("\n"));
            out.insert(Message.translation("ae2gadget.applicator.tooltip.entry").param("count", Integer.toString(e.getValue()))
                    .param("color", colorName(e.getKey())));
        }
        return out;
    }

    // ------------------------------------------------------------------ Klick (AE2: use / useOn)

    static void use(ItemUseContext ctx) {
        if (ctx.primary()) {
            return;
        }
        ItemStack held = ctx.held();
        Vector3i target = Aim.targetBlock(ctx);
        if (target == null) {
            if (ctx.crouching()) {
                load(ctx);
            } else {
                cycle(ctx); // AE2: use() in die Luft wechselt die Farbe
            }
            return;
        }
        PaintStorage paint = paint(held);
        AEColor color = activeColor(held);
        if (color != null && paint.get(color) > 0 && cableAt(ctx.world(), target.x, target.y, target.z) != null) {
            // AE2: getAECurrentPower(is) > POWER_PER_USE
            if (!(PoweredItems.stored(held) > ENERGY_PER_USE)) {
                Feedback.noPower(ctx.playerRef(), ENERGY_PER_USE);
                return;
            }
            WorldEdits edits = WorldEdits.forPlayer(ctx.world(), ctx.commandBuffer(), ctx.playerEntity(), held,
                    ctx.interaction().getHeldItemContainer(), ctx.interaction().getHeldItemSlot());
            if (recolor(ctx.world(), edits, ctx.playerRef(), target.x, target.y, target.z, color)) {
                // AE2: consumeItem – eine Farbe und 100 AE; ist die Farbe aufgebraucht, verfällt die Auswahl
                paint.consume(color);
                ItemStack used = PoweredItems.use(held, ENERGY_PER_USE);
                if (used != null) {
                    ctx.setHeld(with(used, paint, paint.get(color) > 0 ? color : null));
                }
                return;
            }
        }
        // AE2: nichts gefärbt – geduckt wechselt die Farbe
        if (ctx.crouching()) {
            cycle(ctx);
        } else if (paint.isEmpty()) {
            Feedback.tell(ctx.playerRef(), "ae2gadget.applicator.empty");
        }
    }

    /** AE2: {@code cycleColors} – die nächste geladene Farbe wählen und anzeigen. */
    private static void cycle(ItemUseContext ctx) {
        ItemStack held = ctx.held();
        PaintStorage paint = paint(held);
        if (paint.isEmpty()) {
            Feedback.tell(ctx.playerRef(), "ae2gadget.applicator.empty");
            return;
        }
        AEColor next = paint.next(activeColor(held), 1);
        if (next != null && ctx.setHeld(with(held, paint, next))) {
            Feedback.tell(ctx.playerRef(), Message.translation("ae2gadget.applicator.selected").param("color", colorName(next))
                    .param("count", Integer.toString(paint.get(next))), NotificationStyle.Default);
        }
    }

    /** Geduckt in die Luft: Farbkugeln und Schnee aus dem Inventar laden. */
    private static void load(ItemUseContext ctx) {
        ItemStack held = ctx.held();
        // Vormerkung: der Platz für die nächste Farbe hängt davon ab, was schon eingeplant ist
        PaintStorage planned = paint(held);
        boolean[] found = {false};
        List<ItemStack> taken = PlayerItems.take(PlayerItems.containers(ctx.commandBuffer(), ctx.playerEntity()),
                ctx.interaction().getHeldItemContainer(), ctx.interaction().getHeldItemSlot(), stack -> {
                    AEColor c = PaintItems.paintColor(stack.getItemId());
                    if (c == null) {
                        return 0;
                    }
                    found[0] = true;
                    return planned.add(c, stack.getQuantity());
                });
        // gebucht wird nur, was wirklich aus dem Inventar genommen wurde
        PaintStorage paint = paint(held);
        int moved = 0;
        for (ItemStack s : taken) {
            AEColor c = PaintItems.paintColor(s.getItemId());
            if (c != null) {
                moved += paint.add(c, s.getQuantity());
            }
        }
        if (moved <= 0) {
            Feedback.tell(ctx.playerRef(), found[0] ? "ae2gadget.applicator.full" : "ae2gadget.applicator.noPaintInInventory");
            return;
        }
        String selected = held.getFromMetadataOrNull(SELECTED_KEY, Codec.STRING);
        if (!ctx.setHeld(with(held, paint, paint.active(selected == null ? null : PaintStorage.colorById(selected))))) {
            // Applicator ist weg: alles zurück
            for (ItemStack s : taken) {
                PlayerItems.give(ctx.commandBuffer(), ctx.playerEntity(), s.getItemId(), s.getQuantity());
            }
            return;
        }
        Feedback.tell(ctx.playerRef(), Message.translation("ae2gadget.applicator.loaded").param("count", Integer.toString(moved))
                .param("total", Integer.toString(paint.total())), NotificationStyle.Success);
    }

    // ------------------------------------------------------------------ Kabel färben

    /** Das Kabel an der Stelle, sobald es angemeldet ist; sonst null. */
    @Nullable
    static WorldNetworks.Node cableAt(World world, int x, int y, int z) {
        WorldNetworks.Node node = WorldNetworks.of(world).node(x, y, z);
        return node != null && "cable".equals(node.kind()) && node.item() != null ? node : null;
    }

    /**
     * Färbt das Kabel an der Stelle um ({@link AEColor#TRANSPARENT} = reinigen).
     *
     * @param player wer färbt (für die Zugriffsprüfung des Netzes), null für Testbefehle der Konsole
     * @return true, wenn das Kabel jetzt die Farbe hat; false, wenn dort kein Kabel ist, es die Farbe schon hat oder
     *         die Änderung nicht erlaubt wurde
     */
    static boolean recolor(World world, WorldEdits edits, @Nullable PlayerRef player, int x, int y, int z, AEColor color) {
        WorldNetworks nets = WorldNetworks.of(world);
        WorldNetworks.Node node = cableAt(world, x, y, z);
        String recolored = node == null ? null : PaintItems.recoloredCable(node.item().id(), color);
        if (recolored == null) {
            return false;
        }
        // fremde Netze bleiben, wie sie sind
        if (player != null && !AE2Security.checkUse(nets, x, y, z, player)) {
            return false;
        }
        if (!edits.placeBlock(x, y, z, recolored, BlockAccess.rotationIndex(world, x, y, z))) {
            return false;
        }
        Feedback.sound(world, "SFX_Mud_Build", x + 0.5, y + 0.5, z + 0.5);
        return true;
    }
}
