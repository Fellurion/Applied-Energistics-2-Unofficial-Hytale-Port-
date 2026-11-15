/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.upgrades.IUpgradeableItem#getUpgrades der Items WirelessTerminalItem, MatterCannonItem,
 * ColorApplicatorItem; erlaubte Karten: appeng.init.internal.InitUpgrades).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.hytale.items.ItemUpgrades;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Upgrade-Karten in Items, die keine Speicherzellen sind: Wireless Terminal, Wireless Crafting Terminal, Matter
 * Cannon und Color Applicator. Welche Karten wie oft passen, steht wie bei allen Geräten in
 * {@code de.fellurion.ae2.core.cells.Upgrades} (AE2: {@code InitUpgrades}); hier steht nur die Zahl der Kartenplätze.
 *
 * <p>Abweichung von AE2: Dort werden die Karten im eigenen Fenster des Items eingesetzt. Hier ist die Zellen-Werkbank
 * der eine Ort dafür – für Zellen wie für diese Items.
 */
public final class HandheldUpgrades {

    /** Kartenplätze je Item (AE2: {@code UpgradeInventories.forItem(stack, n, ...)}). */
    private static final Map<String, Integer> SLOTS = Map.of(
            "AE2_Wireless_Terminal", 2,
            "AE2_Wireless_Crafting_Terminal", 2,
            "AE2_Matter_Cannon", 4,
            "AE2_Color_Applicator", 2);

    private HandheldUpgrades() {
    }

    /** Nimmt dieses Item Upgrade-Karten auf, ohne eine Speicherzelle zu sein? */
    public static boolean accepts(@Nullable String itemId) {
        return itemId != null && SLOTS.containsKey(itemId);
    }

    /** Zahl der Kartenplätze; 0 für alle anderen Items. */
    public static int slots(String itemId) {
        return SLOTS.getOrDefault(itemId, 0);
    }

    /** Die eingebauten Karten als Kern-Inventar (prüft erlaubte Karten und Höchstzahlen). */
    public static UpgradeInventory inventory(ItemStack stack) {
        UpgradeInventory inv = new UpgradeInventory(stack.getItemId(), slots(stack.getItemId()));
        inv.fromItemIds(ItemUpgrades.cards(stack));
        return inv;
    }

    /**
     * Baut eine Karte ein.
     *
     * @return der Stack mit der Karte (Akkugröße und Tooltip nachgezogen), oder null, wenn sie nicht passt
     */
    @Nullable
    public static ItemStack insert(ItemStack stack, UpgradeCard card) {
        if (!accepts(stack.getItemId()) || stack.getQuantity() != 1) {
            return null;
        }
        UpgradeInventory inv = inventory(stack);
        if (!inv.insert(card)) {
            return null;
        }
        return ItemUpgrades.withCards(stack, ids(inv));
    }

    /**
     * Nimmt die Karte am Platz {@code index} der Liste {@link ItemUpgrades#cards} heraus.
     *
     * @param removed nimmt in {@code [0]} die Item-Id der entnommenen Karte auf
     * @return der Stack ohne die Karte, oder null, wenn dort keine steckt
     */
    @Nullable
    public static ItemStack remove(ItemStack stack, int index, String[] removed) {
        if (!accepts(stack.getItemId()) || stack.getQuantity() != 1) {
            return null;
        }
        List<String> cards = new ArrayList<>(ItemUpgrades.cards(stack));
        if (index < 0 || index >= cards.size()) {
            return null;
        }
        removed[0] = cards.remove(index);
        return ItemUpgrades.withCards(stack, cards);
    }

    private static List<String> ids(UpgradeInventory inv) {
        List<String> out = new ArrayList<>();
        for (String id : inv.toItemIds()) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }
}
