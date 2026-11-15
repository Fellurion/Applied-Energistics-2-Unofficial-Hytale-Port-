/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.features.P2PTunnelAttunement, appeng.init.internal.InitP2PAttunements und die Item-Tags
 * ae2:p2p_attunements/*).
 */
package de.fellurion.ae2.hytale.p2p;

import de.fellurion.ae2.core.p2p.P2PTunnelType;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.items.PoweredItems;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Welches Item einen P2P-Tunnel auf welche Art abstimmt – AE2s Tags {@code ae2:p2p_attunements/<tunnel>},
 * übersetzt auf Hytale-Items (Zuordnung der Minecraft-Zutaten wie in {@code tools/ingredient_map.json}):
 *
 * <table>
 *   <caption>Abstimm-Items</caption>
 *   <tr><th>Tunnel</th><th>AE2</th><th>hier</th></tr>
 *   <tr><td>ME</td><td>alle ME-Kabel</td><td>alle AE2-Kabel</td></tr>
 *   <tr><td>Item</td><td>Storage/Export/Import Bus, Interface, Truhe, Trichter</td><td>dieselben AE2-Items; jede
 *       Hytale-Truhe ({@code Furniture_*_Chest_*})</td></tr>
 *   <tr><td>Redstone</td><td>Redstone, Repeater, Hebel, Fackel ...</td><td>{@code Ingredient_Crystal_Red}</td></tr>
 *   <tr><td>Licht</td><td>Fackel, Glowstone</td><td>jede Fackel ({@code *_Torch*}), {@code Ingredient_Crystal_Yellow}</td></tr>
 *   <tr><td>Fluid</td><td>Eimer; Items mit Fluid-Fähigkeit</td><td>{@code Container_Bucket}</td></tr>
 *   <tr><td>Energie</td><td>Energiezellen, Energy Acceptor; Items mit Energie-Fähigkeit</td><td>dieselben
 *       AE2-Blöcke; jedes AE2-Item mit Akku ({@code PoweredItems})</td></tr>
 * </table>
 *
 * Wie in AE2 gelten zuerst die festen Zuordnungen, danach die Fähigkeiten (Akku). Weitere Items meldet
 * {@link #register} an.
 */
public final class TunnelAttunement {

    private static final Map<String, P2PTunnelType> BY_ITEM = new ConcurrentHashMap<>();

    static {
        for (String id : new String[] {"AE2_Storage_Bus", "AE2_Export_Bus", "AE2_Import_Bus", "AE2_Interface",
                "AE2_Cable_Interface"}) {
            BY_ITEM.put(id, P2PTunnelType.ITEM);
        }
        for (String id : new String[] {"AE2_Energy_Cell", "AE2_Dense_Energy_Cell", "AE2_Creative_Energy_Cell",
                "AE2_Energy_Acceptor"}) {
            BY_ITEM.put(id, P2PTunnelType.FE);
        }
        BY_ITEM.put("Ingredient_Crystal_Red", P2PTunnelType.REDSTONE);
        BY_ITEM.put("Ingredient_Crystal_Yellow", P2PTunnelType.LIGHT);
        BY_ITEM.put("Container_Bucket", P2PTunnelType.FLUID);
    }

    private TunnelAttunement() {
    }

    /** Meldet ein weiteres Abstimm-Item an (einmal beim Plugin-Start). */
    public static void register(String itemId, P2PTunnelType type) {
        BY_ITEM.put(itemId, type);
    }

    /** Die Tunnelart, auf die dieses Item abstimmt, oder null (AE2: {@code getTunnelPartByTriggerItem}). */
    @Nullable
    public static P2PTunnelType byTriggerItem(@Nullable String itemId) {
        if (itemId == null) {
            return null;
        }
        P2PTunnelType fixed = BY_ITEM.get(itemId);
        if (fixed != null) {
            return fixed;
        }
        ItemCatalog.Info info = ItemCatalog.get(itemId);
        if (info != null && "cable".equals(info.kind())) {
            return P2PTunnelType.ME;
        }
        if (itemId.startsWith("Furniture_") && itemId.contains("_Chest")) {
            return P2PTunnelType.ITEM;
        }
        if (itemId.contains("_Torch")) {
            return P2PTunnelType.LIGHT;
        }
        // AE2: Items mit Energie-Fähigkeit stimmen auf den Energie-Tunnel ab
        if (PoweredItems.isPowered(itemId)) {
            return P2PTunnelType.FE;
        }
        return null;
    }
}
