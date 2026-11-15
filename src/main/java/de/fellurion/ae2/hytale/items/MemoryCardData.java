/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.MemoryCardItem, appeng.api.implementations.items.IMemoryCard).
 */
package de.fellurion.ae2.hytale.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nullable;

/**
 * Was auf einer Memory Card ({@value #ITEM_ID}) gespeichert ist: die Art der Quelle und deren Einstellungen.
 *
 * <p>{@code type} sagt, wovon die Karte stammt und worauf sie passt – die Block-Art des Geräts ({@code "export_bus"})
 * oder {@code "p2p"} für die Verknüpfung von P2P-Tunneln. {@code payload} gehört dem, der die Karte beschrieben hat
 * (meist JSON). {@code nameKey} ist der Übersetzungsschlüssel der Quelle für den Tooltip.
 */
public record MemoryCardData(String type, String payload, @Nullable String nameKey) {

    public static final String ITEM_ID = "AE2_Memory_Card";
    private static final String TYPE_KEY = "AE2CardType";
    private static final String PAYLOAD_KEY = "AE2CardData";
    private static final String NAME_KEY = "AE2CardName";

    public static boolean isCard(@Nullable ItemStack stack) {
        return !ItemStack.isEmpty(stack) && ITEM_ID.equals(stack.getItemId());
    }

    /** Inhalt der Karte, oder null für eine leere Karte. */
    @Nullable
    public static MemoryCardData read(@Nullable ItemStack card) {
        if (!isCard(card)) {
            return null;
        }
        String type = card.getFromMetadataOrNull(TYPE_KEY, Codec.STRING);
        String payload = card.getFromMetadataOrNull(PAYLOAD_KEY, Codec.STRING);
        if (type == null || payload == null) {
            return null;
        }
        return new MemoryCardData(type, payload, card.getFromMetadataOrNull(NAME_KEY, Codec.STRING));
    }

    /** Die Karte mit diesem Inhalt ({@code null} löscht sie) und passendem Tooltip. */
    public static ItemStack write(ItemStack card, @Nullable MemoryCardData data) {
        ItemStack out = card
                .withMetadata(TYPE_KEY, Codec.STRING, data == null ? null : data.type())
                .withMetadata(PAYLOAD_KEY, Codec.STRING, data == null ? null : data.payload())
                .withMetadata(NAME_KEY, Codec.STRING, data == null ? null : data.nameKey());
        return ItemTooltips.refresh(out);
    }
}
