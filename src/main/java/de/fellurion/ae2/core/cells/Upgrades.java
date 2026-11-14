/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.cells;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which upgrade cards (and how many) each upgradable item/block accepts. Port of {@code appeng.api.upgrades.Upgrades}
 * with the associations of {@code appeng.init.internal.InitUpgrades}, keyed by Hytale item id.
 */
public final class Upgrades {
    private static final Map<String, Map<UpgradeCard, Integer>> ASSOCIATIONS = new HashMap<>();

    private Upgrades() {
    }

    /** Registers that {@code upgradableItemId} accepts up to {@code maxSupported} of {@code card}. */
    public static synchronized void add(UpgradeCard card, String upgradableItemId, int maxSupported) {
        ASSOCIATIONS.computeIfAbsent(upgradableItemId, k -> new HashMap<>()).put(card, maxSupported);
    }

    /** How many of the card can be installed into the item; 0 if none. */
    public static synchronized int getMaxInstallable(UpgradeCard card, String upgradableItemId) {
        var m = ASSOCIATIONS.get(upgradableItemId);
        return m == null ? 0 : m.getOrDefault(card, 0);
    }

    public static synchronized Map<UpgradeCard, Integer> getSupportedUpgrades(String upgradableItemId) {
        return Map.copyOf(ASSOCIATIONS.getOrDefault(upgradableItemId, Map.of()));
    }

    static {
        // Interface (block and part)
        for (var id : List.of("AE2_Interface", "AE2_Cable_Interface")) {
            add(UpgradeCard.CRAFTING, id, 1);
            add(UpgradeCard.FUZZY, id, 1);
        }
        // IO Port
        add(UpgradeCard.SPEED, "AE2_IO_Port", 3);
        add(UpgradeCard.REDSTONE, "AE2_IO_Port", 1);
        // Level Emitter
        add(UpgradeCard.FUZZY, "AE2_Level_Emitter", 1);
        add(UpgradeCard.CRAFTING, "AE2_Level_Emitter", 1);
        // Import Bus
        add(UpgradeCard.FUZZY, "AE2_Import_Bus", 1);
        add(UpgradeCard.REDSTONE, "AE2_Import_Bus", 1);
        add(UpgradeCard.CAPACITY, "AE2_Import_Bus", 5);
        add(UpgradeCard.SPEED, "AE2_Import_Bus", 4);
        add(UpgradeCard.INVERTER, "AE2_Import_Bus", 1);
        // Export Bus
        add(UpgradeCard.FUZZY, "AE2_Export_Bus", 1);
        add(UpgradeCard.REDSTONE, "AE2_Export_Bus", 1);
        add(UpgradeCard.CAPACITY, "AE2_Export_Bus", 5);
        add(UpgradeCard.SPEED, "AE2_Export_Bus", 4);
        add(UpgradeCard.CRAFTING, "AE2_Export_Bus", 1);
        // Storage cells
        for (var tier : StorageCellTier.ALL) {
            var item = "AE2_Item_Storage_Cell_" + tier.namePrefix();
            add(UpgradeCard.FUZZY, item, 1);
            add(UpgradeCard.INVERTER, item, 1);
            add(UpgradeCard.EQUAL_DISTRIBUTION, item, 1);
            add(UpgradeCard.VOID, item, 1);

            var fluid = "AE2_Fluid_Storage_Cell_" + tier.namePrefix();
            add(UpgradeCard.INVERTER, fluid, 1);
            add(UpgradeCard.EQUAL_DISTRIBUTION, fluid, 1);
            add(UpgradeCard.VOID, fluid, 1);

            var portable = "AE2_Portable_Item_Cell_" + tier.namePrefix();
            add(UpgradeCard.FUZZY, portable, 1);
            add(UpgradeCard.INVERTER, portable, 1);
            add(UpgradeCard.EQUAL_DISTRIBUTION, portable, 1);
            add(UpgradeCard.VOID, portable, 1);
            add(UpgradeCard.ENERGY, portable, 2);

            var portableFluid = "AE2_Portable_Fluid_Cell_" + tier.namePrefix();
            add(UpgradeCard.INVERTER, portableFluid, 1);
            add(UpgradeCard.EQUAL_DISTRIBUTION, portableFluid, 1);
            add(UpgradeCard.VOID, portableFluid, 1);
            add(UpgradeCard.ENERGY, portableFluid, 2);
        }
        // Wireless terminals
        add(UpgradeCard.ENERGY, "AE2_Wireless_Terminal", 2);
        add(UpgradeCard.ENERGY, "AE2_Wireless_Crafting_Terminal", 2);
        // View cell
        add(UpgradeCard.FUZZY, "AE2_View_Cell", 1);
        add(UpgradeCard.INVERTER, "AE2_View_Cell", 1);
        // Storage bus
        add(UpgradeCard.FUZZY, "AE2_Storage_Bus", 1);
        add(UpgradeCard.INVERTER, "AE2_Storage_Bus", 1);
        add(UpgradeCard.CAPACITY, "AE2_Storage_Bus", 5);
        add(UpgradeCard.VOID, "AE2_Storage_Bus", 1);
        // Formation plane
        add(UpgradeCard.FUZZY, "AE2_Formation_Plane", 1);
        add(UpgradeCard.INVERTER, "AE2_Formation_Plane", 1);
        add(UpgradeCard.CAPACITY, "AE2_Formation_Plane", 5);
        // Color applicator
        add(UpgradeCard.ENERGY, "AE2_Color_Applicator", 2);
        add(UpgradeCard.EQUAL_DISTRIBUTION, "AE2_Color_Applicator", 1);
        add(UpgradeCard.VOID, "AE2_Color_Applicator", 1);
        // Matter cannon
        add(UpgradeCard.ENERGY, "AE2_Matter_Cannon", 2);
        add(UpgradeCard.FUZZY, "AE2_Matter_Cannon", 1);
        add(UpgradeCard.INVERTER, "AE2_Matter_Cannon", 1);
        add(UpgradeCard.VOID, "AE2_Matter_Cannon", 1);
        add(UpgradeCard.SPEED, "AE2_Matter_Cannon", 4);
        // Machines
        add(UpgradeCard.SPEED, "AE2_Molecular_Assembler", 5);
        add(UpgradeCard.SPEED, "AE2_Inscriber", 4);
        add(UpgradeCard.ENERGY, "AE2_Vibration_Chamber", 3);
        add(UpgradeCard.SPEED, "AE2_Vibration_Chamber", 3);
    }
}
