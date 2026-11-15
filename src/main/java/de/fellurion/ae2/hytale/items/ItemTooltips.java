/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CellTooltips;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zusatzzeilen im Tooltip eines Items (unter der normalen Beschreibung): Ladung, eingebaute Karten, Verknüpfung ...
 *
 * <p>Jeder Bereich meldet einmal beim Start einen {@link Section} an, der für „seine“ Items Zeilen liefert und für
 * alle anderen null. Nach einer Änderung an den Metadaten ruft er {@link #refresh} auf und schreibt den Stack
 * zurück. Speicherzellen laufen weiter über {@link CellTooltips}; deren Zusatzzeilen hängen hinten an.
 */
public final class ItemTooltips {

    /** Zeilen eines Bereichs für einen Stack, oder null. */
    @FunctionalInterface
    public interface Section {
        @Nullable
        Message lines(ItemStack stack);
    }

    private static final List<Section> SECTIONS = new CopyOnWriteArrayList<>();

    private ItemTooltips() {
    }

    public static void addSection(Section section) {
        SECTIONS.add(section);
    }

    /** Die Zusatzzeilen aller Bereiche für diesen Stack (durch Zeilenumbrüche getrennt), oder null. */
    @Nullable
    public static Message extra(ItemStack stack) {
        Message out = null;
        for (Section section : SECTIONS) {
            Message lines = section.lines(stack);
            if (lines == null) {
                continue;
            }
            if (out == null) {
                out = Message.empty();
            } else {
                out.insert(Message.raw("\n"));
            }
            out.insert(lines);
        }
        return out;
    }

    /** Baut den Tooltip des Stacks neu auf (ein eigener Name bleibt erhalten). */
    public static ItemStack refresh(ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return stack;
        }
        if (StorageCells.isCellItem(stack.getItemId())) {
            return CellTooltips.apply(stack, CellItemData.fromJson(
                    stack.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING)), null);
        }
        ItemDisplayMetadata old = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message name = old == null ? null : old.getName();
        Message extra = extra(stack);
        if (extra == null) {
            if (old == null) {
                return stack;
            }
            return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, name == null ? null : new ItemDisplayMetadata(name, null));
        }
        Message description = Message.empty();
        Item item = stack.getItem();
        Message base = item == null ? null : item.getDescriptionTranslationMessage();
        if (base != null) {
            description.insert(base);
            description.insert(Message.raw("\n\n"));
        }
        description.insert(extra);
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, new ItemDisplayMetadata(name, description));
    }
}
