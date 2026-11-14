/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

/**
 * Upgrade cards with their Hytale item ids ({@code AE2_} + Title_Case of the AE2 id). Port of the card
 * definitions in {@code appeng.core.definitions.AEItems}.
 */
public enum UpgradeCard {
    REDSTONE("AE2_Redstone_Card"),
    CAPACITY("AE2_Capacity_Card"),
    /** "Overflow Destruction Card" (AE2 id void_card). */
    VOID("AE2_Void_Card"),
    FUZZY("AE2_Fuzzy_Card"),
    /** "Acceleration Card" (AE2 id speed_card). */
    SPEED("AE2_Speed_Card"),
    INVERTER("AE2_Inverter_Card"),
    CRAFTING("AE2_Crafting_Card"),
    EQUAL_DISTRIBUTION("AE2_Equal_Distribution_Card"),
    /** Energy card: multiplier 1 ({@code EnergyCardItem(p, 1)}). */
    ENERGY("AE2_Energy_Card");

    public final String itemId;

    UpgradeCard(String itemId) {
        this.itemId = itemId;
    }

    /** @return the card for a Hytale item id, or null if the item is no upgrade card. */
    public static UpgradeCard byItemId(String itemId) {
        for (var c : values()) {
            if (c.itemId.equals(itemId)) {
                return c;
            }
        }
        return null;
    }

    /** Energy multiplier contribution ({@code Upgrades.getEnergyCardMultiplier}). */
    public int energyMultiplier() {
        return this == ENERGY ? 1 : 0;
    }
}
