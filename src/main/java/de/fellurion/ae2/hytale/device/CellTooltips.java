/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.storage.BasicStorageCell#appendHoverText, appeng.api.storage.cells.ICellWorkbenchItem).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.cells.BasicCellInventory;
import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.ui.TerminalTexts;
import org.bson.BsonDocument;
import org.bson.BsonString;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tooltip gefüllter Speicherzellen wie in AE2: „1.024 / 4.096 Bytes genutzt“, „12 / 63 Typen“, Partition und die
 * größten Inhalte. Steht als {@link ItemDisplayMetadata}-Beschreibung am Item (unter der normalen Beschreibung).
 *
 * <p>Leere, unkonfigurierte Zellen bekommen keinen Tooltip, damit sie sich weiter mit neuen Zellen stapeln.
 */
public final class CellTooltips {

    /** So viele Inhalte zeigt der Tooltip (AE2 zeigt eine Vorschau der ersten Typen). */
    private static final int PREVIEW = 5;

    private CellTooltips() {
    }

    /** Schreibt Zelleninhalt ({@link CellHostDevice#CELL_KEY}) und passenden Tooltip an den Stack. */
    public static ItemStack write(ItemStack stack, CellItemData data, @Nullable StorageCell liveCell) {
        return write(stack, data, liveCell, true);
    }

    /**
     * Schreibt den Zelleninhalt und – wenn {@code withTooltip} – den Tooltip in EINEM Schritt (eine Kopie der
     * Metadaten statt einer je Schlüssel; das passiert bei Itemverkehr für jede Zelle in jedem Tick).
     */
    public static ItemStack write(ItemStack stack, CellItemData data, @Nullable StorageCell liveCell, boolean withTooltip) {
        BsonDocument current = ItemKeys.metadataView(stack);
        BsonDocument doc = current == null ? new BsonDocument() : current.clone();
        String json = data.toJson();
        if ("{}".equals(json)) {
            doc.remove(CellHostDevice.CELL_KEY);
        } else {
            doc.put(CellHostDevice.CELL_KEY, new BsonString(json));
        }
        if (withTooltip) {
            ItemDisplayMetadata display = display(stack, data, liveCell);
            if (display == null) {
                doc.remove(ItemDisplayMetadata.KEY);
            } else {
                doc.put(ItemDisplayMetadata.KEY, ItemDisplayMetadata.KEYED_CODEC.getChildCodec().encode(display));
            }
        }
        return stack.withMetadata(doc.isEmpty() ? null : doc);
    }

    /** Setzt den Tooltip passend zu {@code data} (entfernt ihn bei leeren Zellen). */
    public static ItemStack apply(ItemStack stack, CellItemData data, @Nullable StorageCell liveCell) {
        ItemDisplayMetadata display = display(stack, data, liveCell);
        if (display == null && stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC) == null) {
            return stack;
        }
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, display);
    }

    /** Anzeige-Metadaten (Name bleibt erhalten), oder null wenn nichts anzuzeigen ist. */
    @Nullable
    private static ItemDisplayMetadata display(ItemStack stack, CellItemData data, @Nullable StorageCell liveCell) {
        ItemDisplayMetadata old = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message name = old == null ? null : old.getName();
        Message block = describe(stack.getItemId(), data, liveCell);
        if (block == null) {
            return name == null ? null : new ItemDisplayMetadata(name, null);
        }
        Message description = Message.empty();
        Message base = defaultDescription(stack);
        if (base != null) {
            description.insert(base);
            description.insert(Message.raw("\n\n"));
        }
        description.insert(block);
        return new ItemDisplayMetadata(name, description);
    }

    /** Tooltip-Zeilen, oder null für leere Zellen ohne Konfiguration. */
    @Nullable
    static Message describe(String itemId, CellItemData data, @Nullable StorageCell liveCell) {
        List<AEKey> config = data.getConfigKeys();
        boolean hasUpgrades = data.getUpgrades().stream().anyMatch(u -> u != null);
        if (CellDefinition.CREATIVE_CELL_ID.equals(itemId)) {
            if (config.isEmpty()) {
                return null;
            }
            return Message.translation("ae2ui.cell.creative").param("types", Integer.toString(config.size()));
        }
        StorageCell cell = liveCell != null ? liveCell : StorageCells.getCellInventory(itemId, data, () -> { });
        if (!(cell instanceof BasicCellInventory b)) {
            return null;
        }
        if (b.getUsedBytes() == 0 && config.isEmpty() && !hasUpgrades) {
            return null;
        }
        List<Message> lines = new ArrayList<>();
        lines.add(Message.translation("ae2ui.cell.bytes")
                .param("used", TerminalTexts.formatAmount(b.getUsedBytes()))
                .param("total", TerminalTexts.formatAmount(b.getTotalBytes())));
        lines.add(Message.translation("ae2ui.cell.types")
                .param("types", Long.toString(b.getStoredItemTypes()))
                .param("maxTypes", Long.toString(b.getTotalItemTypes())));
        if (!config.isEmpty()) {
            lines.add(Message.translation("ae2ui.cell.partitioned").param("count", Integer.toString(config.size())));
        }
        if (hasUpgrades) {
            long n = data.getUpgrades().stream().filter(u -> u != null).count();
            lines.add(Message.translation("ae2ui.cell.upgrades").param("count", Long.toString(n)));
        }
        // Vorschau: die größten Inhalte
        List<Map.Entry<AEKey, Long>> top = new ArrayList<>(data.getContents().entrySet());
        top.removeIf(e -> e.getValue() == null || e.getValue() <= 0);
        top.sort((a, c) -> Long.compare(c.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(PREVIEW, top.size()); i++) {
            Map.Entry<AEKey, Long> e = top.get(i);
            lines.add(Message.translation("ae2ui.cell.entry")
                    .param("amount", TerminalTexts.formatAmount(e.getValue()))
                    .param("item", itemName(e.getKey())));
        }
        if (top.size() > PREVIEW) {
            lines.add(Message.translation("ae2ui.cell.more").param("count", Integer.toString(top.size() - PREVIEW)));
        }
        Message out = Message.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.insert(Message.raw("\n"));
            }
            out.insert(lines.get(i));
        }
        return out;
    }

    private static Message itemName(AEKey key) {
        if (key instanceof AEItemKey ik) {
            Item item = Item.getAssetMap().getAsset(ik.itemId());
            if (item != null) {
                return item.getTranslationMessage();
            }
            return Message.raw(ik.itemId());
        }
        return Message.raw(key.toString());
    }

    @Nullable
    private static Message defaultDescription(ItemStack stack) {
        Item item = stack.getItem();
        return item == null ? null : item.getDescriptionTranslationMessage();
    }
}
