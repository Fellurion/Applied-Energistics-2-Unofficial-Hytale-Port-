/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.hytale.device.CellHostDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Upgrade-Karten, die in einem Item stecken (AE2: {@code IUpgradeableItem}).
 *
 * <p>Speicherzellen (auch tragbare) führen ihre Karten im Zelleninhalt ({@link CellItemData#getUpgrades()}); alle
 * anderen Items (Wireless Terminal, Matter Cannon, Color Applicator ...) in den Metadaten unter {@link #KEY} als
 * durch Komma getrennte Item-Ids der Karten.
 */
public final class ItemUpgrades {

    /** Metadaten-Schlüssel: Item-Ids der eingebauten Karten, durch Komma getrennt. */
    public static final String KEY = "AE2Upgrades";
    public static final String ENERGY_CARD = "AE2_Energy_Card";

    private ItemUpgrades() {
    }

    /** Item-Ids der eingebauten Karten (ohne leere Plätze). */
    public static List<String> cards(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (ItemStack.isEmpty(stack)) {
            return out;
        }
        if (StorageCells.isCellItem(stack.getItemId())) {
            String json = stack.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING);
            if (json != null) {
                for (String id : CellItemData.fromJson(json).getUpgrades()) {
                    if (id != null) {
                        out.add(id);
                    }
                }
            }
            return out;
        }
        String joined = stack.getFromMetadataOrNull(KEY, Codec.STRING);
        if (joined != null) {
            for (String id : joined.split(",")) {
                if (!id.isBlank()) {
                    out.add(id.trim());
                }
            }
        }
        return out;
    }

    /** Wie viele Karten dieser Art eingebaut sind. */
    public static int count(ItemStack stack, String cardItemId) {
        int n = 0;
        for (String id : cards(stack)) {
            if (cardItemId.equals(id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Setzt die Karten eines Items, das keine Speicherzelle ist (bei Zellen stehen sie im Zelleninhalt), und
     * zieht Tooltip und Ladungsgrenze nach.
     */
    public static ItemStack withCards(ItemStack stack, List<String> cards) {
        if (StorageCells.isCellItem(stack.getItemId())) {
            throw new IllegalArgumentException("cells keep their upgrades in CellItemData");
        }
        ItemStack out = stack.withMetadata(KEY, Codec.STRING, cards.isEmpty() ? null : String.join(",", cards));
        // weniger Energiekarten = kleinerer Akku: überschüssige Ladung verfällt wie in AE2
        return PoweredItems.isPowered(out.getItemId()) ? PoweredItems.withStored(out, PoweredItems.stored(out))
                : ItemTooltips.refresh(out);
    }
}
