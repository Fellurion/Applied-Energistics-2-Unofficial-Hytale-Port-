/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.AbstractPortableCell#use / PortableCellItem).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.wireless.LinkStatus;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.items.PoweredItems;
import de.fellurion.ae2.hytale.ui.TerminalOpener;

import java.util.ArrayList;
import java.util.List;

/**
 * Tragbare Zellen in der Hand: Rechtsklick öffnet das Terminal auf dem eigenen Inhalt der Zelle.
 *
 * <p>Solange es offen ist, verbraucht die Zelle wie in AE2 0,5 AE je Tick aus ihrem Akku, dazu 1 AE je bewegtem Item.
 * Abweichungen von AE2: Mit leerem Akku öffnet die Zelle gar nicht erst (AE2 öffnet und zeigt „Out of Power“); das
 * Zerlegen leerer Zellen per geducktem Rechtsklick gibt es nicht.
 *
 * <p>Tragbare Fluid-Zellen öffnen genauso: Das Terminal zeigt ihre Flüssigkeiten, ein Eimer wird wie am
 * ME-Terminal gefüllt bzw. geleert (1 AE je 125 mB aus dem Akku). Akku, Karten und Sitzung sind dieselben.
 */
final class PortableCells {

    private PortableCells() {
    }

    /** Item-Ids der tragbaren Zellen, die sich in der Hand öffnen lassen. */
    static List<String> openableCells() {
        List<String> ids = new ArrayList<>();
        for (CellDefinition def : CellDefinition.PORTABLE_ITEM_CELLS) {
            ids.add(def.itemId());
        }
        for (CellDefinition def : CellDefinition.PORTABLE_FLUID_CELLS) {
            ids.add(def.itemId());
        }
        return ids;
    }

    /** Rechtsklick mit der Zelle in der Hand. */
    static void use(ItemUseContext ctx) {
        if (ctx.primary()) {
            return;
        }
        ItemStack held = ctx.held();
        CellDefinition def = CellDefinition.byItemId(held.getItemId());
        if (def == null || !def.portable() || held.getQuantity() != 1) {
            return;
        }
        int section = ctx.interaction().getHeldItemSectionId();
        short slot = ctx.interaction().getHeldItemSlot();
        PortableCellSession session = new PortableCellSession(ctx.playerRef(), ItemPlace.of(ctx.playerRef()), section,
                slot, held);
        if (session.cell() == null) {
            return;
        }
        if (!PoweredItems.has(held, 1) || !session.host().getLinkStatus().connected()) {
            ctx.playerRef().sendMessage(Message.translation(HeldItemSession.messageKey(LinkStatus.OUT_OF_POWER)));
            return;
        }
        TerminalOpener.open(ctx, "ae2.items." + held.getItemId() + ".name",
                new PortableCellBackend(session, ctx.playerRef().getUuid()), false);
    }
}
