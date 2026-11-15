/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The device configuration a memory card carries, and the rules for applying it. Port of the generic part of
 * {@code appeng.items.tools.MemoryCardItem} ({@code exportGenericSettings}, {@code importGenericSettings},
 * {@code storeUpgrades}, {@code restoreUpgrades}) and of {@code appeng.util.SettingsFrom#MEMORY_CARD}.
 * <p>
 * A configuration has up to four {@link Part parts}; a device exports the parts it has and imports the parts it
 * understands, so a card written by one kind of device can be partially applied to another kind (AE2:
 * {@code importGenericSettingsAndNotify}). The settings are a map of AE2's setting names
 * ({@code appeng.api.config.Settings}, e.g. {@code fuzzy_mode}) to the names of their values, as in
 * {@code IConfigManager.exportSettings}.
 * <p>
 * Deviations: AE2 stores each part in its own data component of the card; here the parts are one JSON object.
 * AE2's creative shortcut (cards are created out of nothing for a player in creative mode) is not ported: upgrade
 * cards always come from a {@link CardStock} and surplus cards always go back to it.
 */
public final class MemoryCardSettings {

    /** The parts of a configuration, in the order AE2 imports them. */
    public enum Part {
        /** AE2 {@code exported_upgrades}: the installed upgrade cards. */
        UPGRADES,
        /** AE2 {@code exported_settings}: the config manager's settings. */
        SETTINGS,
        /** AE2 {@code exported_priority}. */
        PRIORITY,
        /** AE2 {@code exported_config_inv}: the filter / stocking configuration. */
        CONFIG_INV;

        /** Lower-case name, used as suffix of the translation key of the part. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Where upgrade cards come from and go to while a configuration is applied (the player's inventory). */
    public interface CardStock {
        /**
         * Removes up to {@code amount} cards from the stock.
         *
         * @return the number of cards actually removed
         */
        int take(UpgradeCard card, int amount);

        /** Hands {@code amount} cards back. Must not lose any of them. */
        void give(UpgradeCard card, int amount);
    }

    private Map<String, Integer> upgrades;
    private Map<String, String> settings;
    private Integer priority;
    private List<Object> config;

    /** Installed cards as card item id to count, or null if the device has no upgrade slots. */
    public Map<String, Integer> upgrades() {
        return upgrades;
    }

    public MemoryCardSettings upgrades(Map<String, Integer> upgrades) {
        this.upgrades = upgrades;
        return this;
    }

    /** Setting name to value name, or null if the device has no settings. */
    public Map<String, String> settings() {
        return settings;
    }

    public MemoryCardSettings settings(Map<String, String> settings) {
        this.settings = settings;
        return this;
    }

    /** Priority, or null if the device has none. */
    public Integer priority() {
        return priority;
    }

    public MemoryCardSettings priority(Integer priority) {
        this.priority = priority;
        return this;
    }

    /** The filter / stocking slots in the form of {@link ConfigInventory#toJsonList()}, or null if there are none. */
    public List<Object> config() {
        return config;
    }

    public MemoryCardSettings config(List<Object> config) {
        this.config = config;
        return this;
    }

    /** True if no part is present. */
    public boolean isEmpty() {
        return upgrades == null && settings == null && priority == null && config == null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Serialization

    /** Compact JSON with the present parts: {@code {"upgrades":{..},"settings":{..},"priority":n,"config":[..]}}. */
    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        if (upgrades != null) {
            map.put("upgrades", upgrades);
        }
        if (settings != null) {
            map.put("settings", settings);
        }
        if (priority != null) {
            map.put("priority", priority);
        }
        if (config != null) {
            map.put("config", config);
        }
        return MiniJson.write(map);
    }

    /**
     * Reads what {@link #toJson()} wrote. Anything else (a card written by a device with its own format, broken data)
     * yields the parts that can be recognized, possibly none; this never throws.
     */
    public static MemoryCardSettings fromJson(String json) {
        var result = new MemoryCardSettings();
        if (json == null || json.isBlank()) {
            return result;
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (RuntimeException e) {
            return result;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return result;
        }
        if (map.get("upgrades") instanceof Map<?, ?> m) {
            var cards = new LinkedHashMap<String, Integer>();
            for (var e : m.entrySet()) {
                if (e.getValue() instanceof Number n && n.intValue() > 0) {
                    cards.put(String.valueOf(e.getKey()), n.intValue());
                }
            }
            result.upgrades = cards;
        }
        if (map.get("settings") instanceof Map<?, ?> m) {
            var values = new LinkedHashMap<String, String>();
            for (var e : m.entrySet()) {
                if (e.getValue() instanceof String s) {
                    values.put(String.valueOf(e.getKey()), s);
                }
            }
            result.settings = values;
        }
        if (map.get("priority") instanceof Number n) {
            result.priority = n.intValue();
        }
        if (map.get("config") instanceof List<?> l) {
            result.config = new ArrayList<>(l);
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Upgrades

    /** Port of {@code MemoryCardItem.storeUpgrades}: the installed cards summed up per card item id. */
    public static Map<String, Integer> storeUpgrades(UpgradeInventory upgrades) {
        var count = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < upgrades.size(); i++) {
            var card = upgrades.get(i);
            if (card != null) {
                count.merge(card.itemId, 1, Integer::sum);
            }
        }
        return count;
    }

    /**
     * Port of {@code MemoryCardItem.restoreUpgrades}: makes the installed cards match {@code desired}. Cards the
     * device has too many of are handed to {@code stock}; missing cards are taken from it, as many as the device
     * accepts. No card is created or destroyed.
     *
     * @param desired card item id to count, as written by {@link #storeUpgrades}
     * @return the cards that are still missing because the stock did not have them (card to missing count), in the
     *         order of {@code desired}; empty if everything could be restored
     */
    public static Map<UpgradeCard, Integer> restoreUpgrades(UpgradeInventory upgrades, Map<String, Integer> desired,
            CardStock stock) {
        // Map of desired upgrade to the desired count (unknown item ids are no cards and are ignored)
        var desiredCounts = new LinkedHashMap<UpgradeCard, Integer>();
        for (var e : desired.entrySet()) {
            var card = UpgradeCard.byItemId(e.getKey());
            if (card != null && e.getValue() != null && e.getValue() > 0) {
                desiredCounts.merge(card, e.getValue(), Integer::sum);
            }
        }

        // Move out excess
        for (int i = 0; i < upgrades.size(); i++) {
            var current = upgrades.get(i);
            if (current == null) {
                continue;
            }
            int toRemove = installed(upgrades, current) - desiredCounts.getOrDefault(current, 0);
            if (toRemove > 0 && upgrades.remove(i) == current) {
                stock.give(current, 1);
            }
        }

        // Move in what's still missing
        var missing = new LinkedHashMap<UpgradeCard, Integer>();
        for (var e : desiredCounts.entrySet()) {
            var card = e.getKey();
            int missingAmount = e.getValue() - installed(upgrades, card);
            if (missingAmount <= 0) {
                continue;
            }
            // Determine how many we can *actually* insert
            missingAmount = Math.min(missingAmount, insertable(upgrades, card));
            if (missingAmount <= 0) {
                continue;
            }
            int taken = Math.max(0, Math.min(missingAmount, stock.take(card, missingAmount)));
            for (int n = 0; n < taken; n++) {
                if (!upgrades.insert(card)) {
                    stock.give(card, taken - n);
                    break;
                }
            }
            missingAmount -= taken;
            if (missingAmount > 0) {
                missing.put(card, missingAmount);
            }
        }
        return missing;
    }

    /** Cards of this kind in the slots, not capped at the host's maximum (unlike {@code getInstalledUpgrades}). */
    private static int installed(UpgradeInventory upgrades, UpgradeCard card) {
        int n = 0;
        for (int i = 0; i < upgrades.size(); i++) {
            if (upgrades.get(i) == card) {
                n++;
            }
        }
        return n;
    }

    /** How many more cards of this kind the inventory accepts: free slots, limited by the host's maximum. */
    private static int insertable(UpgradeInventory upgrades, UpgradeCard card) {
        int free = 0;
        for (int i = 0; i < upgrades.size(); i++) {
            if (upgrades.get(i) == null) {
                free++;
            }
        }
        return Math.max(0, Math.min(free, upgrades.getMaxInstalled(card) - installed(upgrades, card)));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Config inventory

    /**
     * Port of {@code GenericStackInv.readFromList}: sets every slot of {@code target} from an exported list; slots
     * beyond the list are cleared. Entries the target cannot hold (unsupported key type, unreadable entry) leave
     * their slot empty; amounts are normalized by the target's mode.
     */
    public static void readConfig(ConfigInventory target, List<?> list) {
        for (int i = 0; i < target.size(); i++) {
            GenericStack stack = null;
            if (i < list.size() && list.get(i) instanceof Map<?, ?> m) {
                try {
                    AEKey key = AEKey.fromJsonMap(m);
                    Object a = m.get("a");
                    stack = new GenericStack(key, a instanceof Number n ? n.longValue() : 0);
                } catch (RuntimeException e) {
                    stack = null;
                }
            }
            target.setStack(i, null);
            if (stack != null) {
                target.setStack(i, stack);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Settings helpers for devices

    /** AE2 writes yes/no settings ({@code appeng.api.config.YesNo}) as {@code YES} / {@code NO}. */
    public static String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }

    /**
     * Reads one enum setting (AE2: {@code Setting.setFromString}).
     *
     * @return true if the setting was present with a valid value and has been passed to {@code setter}
     */
    public static <E extends Enum<E>> boolean importEnum(Map<String, String> settings, String name, Class<E> type,
            Consumer<E> setter) {
        String value = settings == null ? null : settings.get(name);
        if (value == null) {
            return false;
        }
        E parsed;
        try {
            parsed = Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        setter.accept(parsed);
        return true;
    }

    /**
     * Reads one yes/no setting.
     *
     * @return true if the setting was present with a valid value and has been passed to {@code setter}
     */
    public static boolean importYesNo(Map<String, String> settings, String name, Consumer<Boolean> setter) {
        String value = settings == null ? null : settings.get(name);
        if ("YES".equals(value)) {
            setter.accept(Boolean.TRUE);
            return true;
        }
        if ("NO".equals(value)) {
            setter.accept(Boolean.FALSE);
            return true;
        }
        return false;
    }
}
