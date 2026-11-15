/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * The toolbox of the Network Tool: {@value #SLOTS} slots that hold upgrade cards only and travel with the item. Port
 * of {@code appeng.items.tools.NetworkToolItem#getInventory} with its {@code NetworkToolInventoryFilter} and of the
 * card summary of {@code NetworkToolItem#getTooltipImage}.
 * <p>
 * The content is serialized with {@link #toJson()} into the item's metadata; an empty toolbox serializes to
 * {@code null}, so an unused tool carries no metadata. All mutating methods move items: what {@link #insert} reports
 * as inserted is in the toolbox afterwards, what {@link #extract} or {@link #removeCards} return is gone from it.
 * <p>
 * Deviation: AE2 stores the slots in the vanilla {@code minecraft:container} data component; here it is a JSON list.
 */
public final class ToolboxInventory {
    /** AE2: the network tool has a 3x3 grid of upgrade slots. */
    public static final int SLOTS = 9;

    private final SimpleStack[] slots = new SimpleStack[SLOTS];
    private final ToIntFunction<String> maxStackSize;

    /**
     * @param maxStackSize maximum stack size per item id (the integration passes the Hytale item's max stack)
     */
    public ToolboxInventory(ToIntFunction<String> maxStackSize) {
        this.maxStackSize = Objects.requireNonNull(maxStackSize);
    }

    /** Port of {@code NetworkToolInventoryFilter.allowInsert}: only upgrade cards fit into the toolbox. */
    public static boolean isUpgradeCard(String itemId) {
        return itemId != null && UpgradeCard.byItemId(itemId) != null;
    }

    /** @return the stack in the slot or null */
    public SimpleStack get(int slot) {
        return slot < 0 || slot >= SLOTS ? null : slots[slot];
    }

    public boolean isEmpty() {
        for (var s : slots) {
            if (s != null) {
                return false;
            }
        }
        return true;
    }

    private int limit(String itemId) {
        return Math.max(1, maxStackSize.applyAsInt(itemId));
    }

    /**
     * How many items of {@code stack} would fit (existing stacks of the same item first, then empty slots). 0 for
     * anything that is not an upgrade card.
     */
    public int simulateInsert(SimpleStack stack) {
        return insert(stack, true);
    }

    /**
     * Inserts as much of {@code stack} as fits: existing stacks of the same item are filled first, then empty slots
     * (AE2: {@code InternalInventory.addItems}).
     *
     * @return the number of items that are now in the toolbox (0 if it is no upgrade card or nothing fits)
     */
    public int insert(SimpleStack stack) {
        return insert(stack, false);
    }

    private int insert(SimpleStack stack, boolean simulate) {
        if (stack == null || !isUpgradeCard(stack.itemId())) {
            return 0;
        }
        int limit = limit(stack.itemId());
        int left = stack.count();
        for (int i = 0; i < SLOTS && left > 0; i++) {
            var cur = slots[i];
            if (cur != null && cur.isSameItem(stack) && cur.count() < limit) {
                int add = Math.min(left, limit - cur.count());
                if (!simulate) {
                    slots[i] = cur.withCount(cur.count() + add);
                }
                left -= add;
            }
        }
        for (int i = 0; i < SLOTS && left > 0; i++) {
            if (slots[i] == null) {
                int add = Math.min(left, limit);
                if (!simulate) {
                    slots[i] = stack.withCount(add);
                }
                left -= add;
            }
        }
        return stack.count() - left;
    }

    /**
     * Removes up to {@code count} items from a slot.
     *
     * @return the removed stack, or null if the slot is empty or {@code count} is not positive
     */
    public SimpleStack extract(int slot, int count) {
        var cur = get(slot);
        if (cur == null || count <= 0) {
            return null;
        }
        int taken = Math.min(count, cur.count());
        slots[slot] = cur.withCount(cur.count() - taken);
        return cur.withCount(taken);
    }

    /**
     * Puts back what {@link #extract} took from {@code slot} and could not be delivered. The slot is still empty or
     * holds the same item, because nothing else touched the toolbox in between.
     *
     * @return the number of items that did not fit back (0 in normal use)
     */
    public int putBack(int slot, SimpleStack stack) {
        if (stack == null) {
            return 0;
        }
        var cur = get(slot);
        if (slot >= 0 && slot < SLOTS && (cur == null || cur.isSameItem(stack))) {
            slots[slot] = stack.withCount(stack.count() + (cur == null ? 0 : cur.count()));
            return 0;
        }
        return stack.count() - insert(stack);
    }

    /** Number of plain (metadata-free) cards of this item id in the toolbox. */
    public int count(String itemId) {
        int n = 0;
        for (var s : slots) {
            if (s != null && s.nbt() == null && s.itemId().equals(itemId)) {
                n += s.count();
            }
        }
        return n;
    }

    /**
     * Removes up to {@code amount} plain cards of the given item id (AE2: {@code InternalInventory.removeItems}, used
     * when a memory card restores upgrades from the network tool).
     *
     * @return the number of cards removed
     */
    public int removeCards(String itemId, int amount) {
        int left = amount;
        for (int i = 0; i < SLOTS && left > 0; i++) {
            var s = slots[i];
            if (s != null && s.nbt() == null && s.itemId().equals(itemId)) {
                int take = Math.min(left, s.count());
                slots[i] = s.withCount(s.count() - take);
                left -= take;
            }
        }
        return Math.max(0, amount - left);
    }

    /**
     * The cards in the toolbox summed up per item, most numerous first (AE2 shows this as the tool's tooltip).
     */
    public List<SimpleStack> summary() {
        var totals = new LinkedHashMap<String, Integer>();
        for (var s : slots) {
            if (s != null) {
                totals.merge(s.itemId(), s.count(), Integer::sum);
            }
        }
        var list = new ArrayList<SimpleStack>(totals.size());
        for (var e : totals.entrySet()) {
            list.add(SimpleStack.of(e.getKey(), e.getValue()));
        }
        list.sort(Comparator.comparingInt(SimpleStack::count).reversed());
        return list;
    }

    /**
     * Canonical JSON for the item metadata: {@code [{"id":"AE2_Speed_Card","n":3},null,...]} without trailing empty
     * slots; null for an empty toolbox.
     */
    public String toJson() {
        int last = SLOTS;
        while (last > 0 && slots[last - 1] == null) {
            last--;
        }
        if (last == 0) {
            return null;
        }
        var list = new ArrayList<Object>(last);
        for (int i = 0; i < last; i++) {
            var s = slots[i];
            if (s == null) {
                list.add(null);
                continue;
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("id", s.itemId());
            if (s.nbt() != null) {
                m.put("nbt", s.nbt());
            }
            m.put("n", s.count());
            list.add(m);
        }
        return MiniJson.write(list);
    }

    /**
     * Reads what {@link #toJson()} wrote. Unreadable input yields an empty toolbox; entries without an id or with a
     * non-positive count are skipped. Counts are not clamped: whatever was stored is kept.
     */
    public static ToolboxInventory fromJson(String json, ToIntFunction<String> maxStackSize) {
        var inv = new ToolboxInventory(maxStackSize);
        if (json == null || json.isBlank()) {
            return inv;
        }
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (RuntimeException e) {
            return inv;
        }
        if (parsed instanceof List<?> list) {
            for (int i = 0; i < Math.min(SLOTS, list.size()); i++) {
                if (list.get(i) instanceof Map<?, ?> m) {
                    String id = MiniJson.getString(m, "id", null);
                    int n = MiniJson.getInt(m, "n", 0);
                    if (id != null && !id.isEmpty() && n > 0) {
                        inv.slots[i] = new SimpleStack(id, MiniJson.getString(m, "nbt", null), n);
                    }
                }
            }
        }
        return inv;
    }
}
