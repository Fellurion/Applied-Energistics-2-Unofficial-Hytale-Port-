/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Slots of generic stacks used as filter configuration (buses), stocking configuration and local storage
 * (interfaces). Port of {@code appeng.util.ConfigInventory} and {@code appeng.helpers.externalstorage.GenericStackInv}.
 * <ul>
 * <li>{@link Mode#CONFIG_TYPES}: amounts are ignored (always 0), used for filters.</li>
 * <li>{@link Mode#CONFIG_STACKS}: amounts are respected; amounts &lt;= 0 clear the slot (or become 1 for an empty
 * slot).</li>
 * <li>{@link Mode#STORAGE}: real storage.</li>
 * </ul>
 * Capacities: AE2's registered slot capacities ({@code GenericSlotCapacities}: items 99 = Minecraft's absolute max
 * stack size, fluids 4 buckets) are enabled with {@link #useRegisteredCapacities()}; items are additionally capped at
 * their max stack size, which the integration supplies via {@link #setItemMaxStackSize}. Default item max stack: 64.
 * <p>
 * Deviation: AE2 snapshot journaling / batching is not ported; JSON replaces NBT.
 */
public final class ConfigInventory implements MEStorage {
    /** Port of {@code GenericStackInv.Mode}. */
    public enum Mode {
        CONFIG_TYPES,
        CONFIG_STACKS,
        STORAGE
    }

    /** Port of {@code AEKeySlotFilter}. */
    @FunctionalInterface
    public interface SlotFilter {
        boolean isAllowed(int slot, AEKey what);
    }

    /** AE2 {@code Item.ABSOLUTE_MAX_STACK_SIZE}, registered as slot capacity for items. */
    public static final long ITEM_SLOT_CAPACITY = 99;
    /** AE2 registered slot capacity for fluids: 4 buckets. */
    public static final long FLUID_SLOT_CAPACITY = 4L * AEFluidKey.AMOUNT_BUCKET;

    private final GenericStack[] stacks;
    private final Mode mode;
    private final Set<AEKeyType> supportedTypes = EnumSet.allOf(AEKeyType.class);
    private final Map<AEKeyType, Long> capacities = new EnumMap<>(AEKeyType.class);
    private SlotFilter filter;
    private Runnable listener;
    private boolean allowOverstacking;
    private ToIntFunction<String> itemMaxStackSize = id -> 64;
    private String description = "";

    public ConfigInventory(Mode mode, int size) {
        this.mode = Objects.requireNonNull(mode);
        this.stacks = new GenericStack[size];
    }

    public static ConfigInventory configTypes(int size) {
        return new ConfigInventory(Mode.CONFIG_TYPES, size);
    }

    public static ConfigInventory configStacks(int size) {
        return new ConfigInventory(Mode.CONFIG_STACKS, size);
    }

    public static ConfigInventory storage(int size) {
        return new ConfigInventory(Mode.STORAGE, size);
    }

    public ConfigInventory supportedTypes(Set<AEKeyType> types) {
        if (types.isEmpty()) {
            throw new IllegalArgumentException("Configuration inventories must support at least one key type");
        }
        supportedTypes.clear();
        supportedTypes.addAll(types);
        return this;
    }

    public ConfigInventory slotFilter(SlotFilter filter) {
        this.filter = filter;
        return this;
    }

    public ConfigInventory changeListener(Runnable listener) {
        this.listener = listener;
        return this;
    }

    public ConfigInventory allowOverstacking(boolean allow) {
        this.allowOverstacking = allow;
        return this;
    }

    public ConfigInventory setItemMaxStackSize(ToIntFunction<String> fn) {
        this.itemMaxStackSize = Objects.requireNonNull(fn);
        return this;
    }

    public ConfigInventory setCapacity(AEKeyType type, long capacity) {
        capacities.put(type, capacity);
        return this;
    }

    /** Port of {@code GenericStackInv.useRegisteredCapacities}: items 99, fluids 4000 mB. */
    public ConfigInventory useRegisteredCapacities() {
        capacities.put(AEKeyType.ITEMS, ITEM_SLOT_CAPACITY);
        capacities.put(AEKeyType.FLUIDS, FLUID_SLOT_CAPACITY);
        return this;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Mode getMode() {
        return mode;
    }

    public int size() {
        return stacks.length;
    }

    public boolean isSupportedType(AEKey what) {
        return supportedTypes.contains(what.getType());
    }

    public boolean isEmpty() {
        for (var s : stacks) {
            if (s != null) {
                return false;
            }
        }
        return true;
    }

    public GenericStack getStack(int slot) {
        var stack = stacks[slot];
        // Filter and clear stacks not supported by the underlying storage channel
        if (stack != null && !isSupportedType(stack.what())) {
            stacks[slot] = null;
            onChange();
            stack = null;
        }
        return stack;
    }

    public AEKey getKey(int slot) {
        var s = getStack(slot);
        return s == null ? null : s.what();
    }

    public long getAmount(int slot) {
        var s = getStack(slot);
        return s == null ? 0 : s.amount();
    }

    public long getCapacity(AEKeyType type) {
        return capacities.getOrDefault(type, Long.MAX_VALUE);
    }

    /** Max amount of a key per slot. */
    public long getMaxAmount(AEKey key) {
        if (allowOverstacking) {
            return getCapacity(key.getType());
        }
        if (key instanceof AEItemKey itemKey) {
            return Math.min(itemMaxStackSize.applyAsInt(itemKey.itemId()), getCapacity(key.getType()));
        }
        return getCapacity(key.getType());
    }

    /** Sets a slot, normalizing amounts according to the mode and clamping to capacity. */
    public void setStack(int slot, GenericStack stack) {
        if (stack != null) {
            if (!isSupportedType(stack.what())) {
                return;
            }
            boolean typesOnly = mode == Mode.CONFIG_TYPES;
            if (typesOnly && stack.amount() != 0) {
                stack = new GenericStack(stack.what(), 0);
            } else if (!typesOnly && stack.amount() <= 0) {
                if (mode == Mode.CONFIG_STACKS && stacks[slot] == null) {
                    // filter the slot to a minimum of 1 of that stack, if currently empty
                    stack = new GenericStack(stack.what(), 1);
                } else {
                    stack = null;
                }
            }
        }
        // Clamp to capacity
        if (stack != null && getMaxAmount(stack.what()) < stack.amount()) {
            stack = new GenericStack(stack.what(), getMaxAmount(stack.what()));
        }
        if (!Objects.equals(stacks[slot], stack)) {
            stacks[slot] = stack;
            onChange();
        }
    }

    /** Convenience for filters: sets a key (amount 0 in types mode). */
    public void setKey(int slot, AEKey key) {
        setStack(slot, key == null ? null : new GenericStack(key, mode == Mode.CONFIG_TYPES ? 0 : 1));
    }

    public boolean isAllowedIn(int slot, AEKey what) {
        return isSupportedType(what) && (filter == null || filter.isAllowed(slot, what));
    }

    /** Inserts into one slot. Port of {@code GenericStackInv.insert(int, ...)}. */
    public long insert(int slot, AEKey what, long amount, Actionable mode) {
        Objects.requireNonNull(what, "what");
        if (amount < 0) {
            throw new IllegalArgumentException("amount >= 0");
        }
        if (!isAllowedIn(slot, what)) {
            return 0;
        }
        var currentWhat = getKey(slot);
        var currentAmount = getAmount(slot);
        if (currentWhat == null || currentWhat.equals(what)) {
            var newAmount = Math.min(currentAmount + amount, getMaxAmount(what));
            if (newAmount > currentAmount) {
                if (mode == Actionable.MODULATE) {
                    setStack(slot, new GenericStack(what, newAmount));
                    newAmount = getAmount(slot);
                }
                return newAmount - currentAmount;
            }
        }
        return 0;
    }

    /** Extracts from one slot. Port of {@code GenericStackInv.extract(int, ...)}. */
    public long extract(int slot, AEKey what, long amount, Actionable mode) {
        Objects.requireNonNull(what, "what");
        var currentWhat = getKey(slot);
        if (currentWhat == null || !currentWhat.equals(what)) {
            return 0;
        }
        var currentAmount = getAmount(slot);
        var canExtract = Math.min(currentAmount, amount);
        if (canExtract > 0 && mode == Actionable.MODULATE) {
            var newAmount = currentAmount - canExtract;
            setStack(slot, newAmount <= 0 ? null : new GenericStack(what, newAmount));
            canExtract = Math.max(0, currentAmount - getAmount(slot));
        }
        return canExtract;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        Objects.requireNonNull(what, "what");
        if (!isSupportedType(what)) {
            return 0;
        }
        if (this.mode == Mode.CONFIG_TYPES) {
            // just shove it into the first free slot
            int freeSlot = -1;
            for (int i = 0; i < stacks.length; i++) {
                var key = getKey(i);
                if (what.equals(key)) {
                    return 0;
                } else if (key == null && freeSlot == -1) {
                    freeSlot = i;
                }
            }
            if (freeSlot != -1 && mode == Actionable.MODULATE) {
                setStack(freeSlot, new GenericStack(what, 0));
            }
            return 0;
        }
        long inserted = 0;
        for (int i = 0; i < stacks.length && inserted < amount; i++) {
            inserted += insert(i, what, amount - inserted, mode);
        }
        return inserted;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        long extracted = 0;
        for (int i = 0; i < stacks.length && extracted < amount; i++) {
            extracted += extract(i, what, amount - extracted, mode);
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (var stack : stacks) {
            if (stack != null) {
                out.add(stack.what(), stack.amount());
            }
        }
    }

    @Override
    public int getEstimatedStackCount() {
        return stacks.length;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /** Distinct non-null keys in slot order. */
    public Set<AEKey> keySet() {
        var result = new LinkedHashSet<AEKey>();
        for (int i = 0; i < stacks.length; i++) {
            var what = getKey(i);
            if (what != null) {
                result.add(what);
            }
        }
        return result;
    }

    /** Clears all slots (single change notification). */
    public void clear() {
        boolean changed = false;
        for (int i = 0; i < stacks.length; i++) {
            changed |= stacks[i] != null;
            stacks[i] = null;
        }
        if (changed) {
            onChange();
        }
    }

    private void onChange() {
        if (listener != null) {
            listener.run();
        }
    }

    /** JSON-compatible list (trailing empty slots omitted): {@code [{"t":"i","id":"x","a":5}, null, ...]}. */
    public List<Object> toJsonList() {
        int last = stacks.length;
        while (last > 0 && stacks[last - 1] == null) {
            last--;
        }
        var list = new ArrayList<Object>(last);
        for (int i = 0; i < last; i++) {
            var s = stacks[i];
            if (s == null) {
                list.add(null);
            } else {
                var m = s.what().toJsonMap();
                m.put("a", s.amount());
                list.add(m);
            }
        }
        return list;
    }

    /** Loads from {@link #toJsonList()} output; remaining slots are cleared. One change notification. */
    public void fromJsonList(List<?> list) {
        var oldListener = listener;
        listener = null;
        boolean changed = false;
        try {
            for (int i = 0; i < stacks.length; i++) {
                GenericStack s = null;
                if (i < list.size() && list.get(i) instanceof Map<?, ?> m) {
                    var key = AEKey.fromJsonMap(m);
                    var a = m.get("a");
                    s = new GenericStack(key, a instanceof Number n ? n.longValue() : 0);
                }
                if (!Objects.equals(stacks[i], s)) {
                    stacks[i] = s;
                    changed = true;
                }
            }
        } finally {
            listener = oldListener;
        }
        if (changed) {
            onChange();
        }
    }
}
