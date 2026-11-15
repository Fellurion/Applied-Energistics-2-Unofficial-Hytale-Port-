/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.WirelessTerminalItem.LinkableHandler, appeng.api.features.IGridLinkableHandler,
 * appeng.init.internal.InitGridLinkables).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Verknüpfung eines Wireless Terminals mit einem Access Point: Welt und Position des Access Points (AE2:
 * {@code AEComponents.WIRELESS_LINK_TARGET}, ein {@code GlobalPos}) in den Item-Metadaten unter {@link #KEY}.
 *
 * <p>Abweichung von AE2: Zusätzlich steht die Kennung des Access Points dabei ({@link WirelessAccessPointDevice#linkId}).
 * Wird der Access Point abgebaut und an derselben Stelle ein neuer gesetzt, ist das Terminal damit nicht mehr
 * verknüpft (AE2 prüft nur, ob an der Position irgendein Access Point steht).
 *
 * @param world Name der Welt des Access Points
 * @param id    Kennung des Access Points
 */
public record WirelessLink(String world, int x, int y, int z, String id) {

    /** Metadaten-Schlüssel: {@code x,y,z,Kennung,Weltname}. */
    public static final String KEY = "AE2Link";

    /** Items, die sich mit einem Access Point verknüpfen lassen (AE2: {@code InitGridLinkables}). */
    private static final Set<String> LINKABLE = Set.of("AE2_Wireless_Terminal", "AE2_Wireless_Crafting_Terminal");

    public static boolean linkable(@Nullable ItemStack stack) {
        return !ItemStack.isEmpty(stack) && LINKABLE.contains(stack.getItemId());
    }

    public static boolean linkable(String itemId) {
        return LINKABLE.contains(itemId);
    }

    /** Die Verknüpfung des Items, oder null (nie verknüpft bzw. unlesbar). */
    @Nullable
    public static WirelessLink of(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        return parse(stack.getFromMetadataOrNull(KEY, Codec.STRING));
    }

    @Nullable
    static WirelessLink parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // der Weltname steht hinten und darf Kommas enthalten
        String[] p = value.split(",", 5);
        if (p.length != 5 || p[3].isBlank() || p[4].isEmpty()) {
            return null;
        }
        try {
            return new WirelessLink(p[4], Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String encode() {
        return x + "," + y + "," + z + "," + id + "," + world;
    }

    /** Der Stack mit dieser Verknüpfung (AE2: {@code IGridLinkableHandler.link}) und passendem Tooltip. */
    public ItemStack applyTo(ItemStack stack) {
        return ItemTooltips.refresh(stack.withMetadata(KEY, Codec.STRING, encode()));
    }

    /** Der Stack ohne Verknüpfung (AE2: {@code IGridLinkableHandler.unlink}). */
    public static ItemStack unlink(ItemStack stack) {
        return ItemTooltips.refresh(stack.withMetadata(KEY, Codec.STRING, null));
    }

    /** Tooltip-Zeile „Verknüpft“ / „Nicht verknüpft“ (AE2: {@code WirelessTerminalItem.appendHoverText}). */
    @Nullable
    static Message tooltip(ItemStack stack) {
        if (!LINKABLE.contains(stack.getItemId())) {
            return null;
        }
        return of(stack) == null
                ? Message.translation("ae2wireless.tooltip.unlinked").color("#e0796b")
                : Message.translation("ae2wireless.tooltip.linked").color("#9ad47f");
    }
}
