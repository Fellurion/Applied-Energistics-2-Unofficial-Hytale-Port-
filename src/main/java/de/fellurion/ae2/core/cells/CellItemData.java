/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data a storage cell item carries (AE2 data components {@code STORAGE_CELL_INV}, {@code CONFIG_INV},
 * {@code UPGRADES}, {@code STORAGE_CELL_FUZZY_MODE}, {@code STORED_ENERGY}), with a compact canonical JSON form for
 * Hytale ItemStack metadata:
 *
 * <pre>
 * {"inv":[{"t":"i","id":"Rock_Stone","a":128}],"cfg":[{"t":"i","id":"Rock_Stone"},null],"up":["AE2_Void_Card"],
 *  "fz":"PERCENT_50","e":1500.0}
 * </pre>
 *
 * Empty/default fields are omitted; an empty cell serializes to {@code {}}.
 */
public final class CellItemData {
    /** Stored amounts (insertion order is preserved). */
    private final Map<AEKey, Long> contents = new LinkedHashMap<>();
    /** Partition config slots (may contain nulls, max 63). */
    private final List<AEKey> config = new ArrayList<>();
    /** Upgrade card item ids per slot (may contain nulls). */
    private final List<String> upgrades = new ArrayList<>();
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;
    /** Stored energy of portable cells. */
    private double storedEnergy;

    public Map<AEKey, Long> getContents() {
        return contents;
    }

    public List<AEKey> getConfig() {
        return config;
    }

    /** Distinct non-null config keys. */
    public List<AEKey> getConfigKeys() {
        var list = new ArrayList<AEKey>();
        for (var k : config) {
            if (k != null && !list.contains(k)) {
                list.add(k);
            }
        }
        return Collections.unmodifiableList(list);
    }

    public CellItemData setConfig(List<AEKey> keys) {
        config.clear();
        config.addAll(keys);
        return this;
    }

    public List<String> getUpgrades() {
        return upgrades;
    }

    public CellItemData setUpgrades(List<String> ids) {
        upgrades.clear();
        upgrades.addAll(ids);
        return this;
    }

    public CellItemData addUpgrade(UpgradeCard card) {
        upgrades.add(card.itemId);
        return this;
    }

    public FuzzyMode getFuzzyMode() {
        return fuzzyMode;
    }

    public CellItemData setFuzzyMode(FuzzyMode mode) {
        this.fuzzyMode = mode == null ? FuzzyMode.IGNORE_ALL : mode;
        return this;
    }

    public double getStoredEnergy() {
        return storedEnergy;
    }

    public CellItemData setStoredEnergy(double e) {
        this.storedEnergy = e;
        return this;
    }

    /** True if the cell stores anything. */
    public boolean hasContents() {
        for (var v : contents.values()) {
            if (v > 0) {
                return true;
            }
        }
        return false;
    }

    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        if (!contents.isEmpty()) {
            var inv = new ArrayList<Object>();
            for (var e : contents.entrySet()) {
                if (e.getValue() > 0) {
                    var m = e.getKey().toJsonMap();
                    m.put("a", e.getValue());
                    inv.add(m);
                }
            }
            if (!inv.isEmpty()) {
                map.put("inv", inv);
            }
        }
        if (config.stream().anyMatch(k -> k != null)) {
            var cfg = new ArrayList<Object>();
            int last = config.size() - 1;
            while (last >= 0 && config.get(last) == null) {
                last--;
            }
            for (int i = 0; i <= last; i++) {
                var k = config.get(i);
                cfg.add(k == null ? null : k.toJsonMap());
            }
            map.put("cfg", cfg);
        }
        if (upgrades.stream().anyMatch(u -> u != null)) {
            map.put("up", new ArrayList<>(upgrades));
        }
        if (fuzzyMode != FuzzyMode.IGNORE_ALL) {
            map.put("fz", fuzzyMode.name());
        }
        if (storedEnergy > 0) {
            map.put("e", storedEnergy);
        }
        return MiniJson.write(map);
    }

    /** Parses data written by {@link #toJson()}; null/blank yields an empty cell. */
    public static CellItemData fromJson(String json) {
        var data = new CellItemData();
        if (json == null || json.isBlank()) {
            return data;
        }
        var map = MiniJson.parseObject(json);
        for (var o : MiniJson.getList(map, "inv")) {
            if (o instanceof Map<?, ?> m) {
                long amount = MiniJson.getLong(m, "a", 0);
                if (amount > 0) {
                    data.contents.merge(AEKey.fromJsonMap(m), amount, Long::sum);
                }
            }
        }
        for (var o : MiniJson.getList(map, "cfg")) {
            data.config.add(o instanceof Map<?, ?> m ? AEKey.fromJsonMap(m) : null);
        }
        for (var o : MiniJson.getList(map, "up")) {
            data.upgrades.add(o == null ? null : String.valueOf(o));
        }
        data.fuzzyMode = MiniJson.getEnum(map, "fz", FuzzyMode.class, FuzzyMode.IGNORE_ALL);
        data.storedEnergy = MiniJson.getDouble(map, "e", 0);
        return data;
    }

    @Override
    public String toString() {
        return toJson();
    }
}
