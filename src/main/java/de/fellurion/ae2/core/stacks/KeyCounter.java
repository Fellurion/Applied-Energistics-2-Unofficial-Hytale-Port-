/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import de.fellurion.ae2.core.config.Enums.FuzzyMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Counts amounts per {@link AEKey}, grouped by primary key (item / fluid id) for fast fuzzy lookups.
 * <p>
 * Port of {@code appeng.api.stacks.KeyCounter} (+ {@code VariantCounter}). As in AE2, entries with an amount of 0
 * remain in the counter (and count for {@link #isEmpty()} / {@link #size()}) until {@link #removeZeros()} is called.
 * Deviation: iteration order is insertion order (LinkedHashMap) instead of hash order, which makes results
 * deterministic.
 */
public final class KeyCounter implements Iterable<KeyCounter.Entry> {

    /** A mutable entry of the counter. */
    public static final class Entry implements Map.Entry<AEKey, Long> {
        private final AEKey key;
        private long value;

        Entry(AEKey key, long value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public AEKey getKey() {
            return key;
        }

        public long getLongValue() {
            return value;
        }

        @Override
        public Long getValue() {
            return value;
        }

        public void setValue(long v) {
            this.value = v;
        }

        @Override
        public Long setValue(Long v) {
            long old = value;
            value = v;
            return old;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    private final Map<AEKey.PrimaryKey, LinkedHashMap<AEKey, Entry>> lists = new LinkedHashMap<>();

    public KeyCounter() {
    }

    public KeyCounter copy() {
        var c = new KeyCounter();
        c.addAll(this);
        return c;
    }

    /** All entries in the same fuzzy partition as {@code key}. */
    public Collection<Entry> findFuzzy(AEKey key, FuzzyMode fuzzy) {
        Objects.requireNonNull(key, "key");
        var sub = lists.get(key.getPrimaryKey());
        if (sub == null) {
            return List.of();
        }
        var result = new ArrayList<Entry>();
        for (var e : sub.values()) {
            if (key.fuzzyEquals(e.key, fuzzy)) {
                result.add(e);
            }
        }
        return result;
    }

    public void removeZeros() {
        var it = lists.values().iterator();
        while (it.hasNext()) {
            var sub = it.next();
            sub.values().removeIf(e -> e.value == 0);
            if (sub.isEmpty()) {
                it.remove();
            }
        }
    }

    public void removeEmptySubmaps() {
        lists.values().removeIf(Map::isEmpty);
    }

    public void addAll(KeyCounter other) {
        for (var e : other) {
            add(e.key, e.value);
        }
    }

    public void removeAll(KeyCounter other) {
        for (var e : other) {
            add(e.key, -e.value);
        }
    }

    public void add(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        var sub = lists.computeIfAbsent(key.getPrimaryKey(), k -> new LinkedHashMap<>());
        var e = sub.get(key);
        if (e == null) {
            sub.put(key, new Entry(key, amount));
        } else {
            e.value += amount;
        }
    }

    /** Subtracts the given amount from the value associated with the given key. */
    public void remove(AEKey key, long amount) {
        add(key, -amount);
    }

    /** Removes the given key from this counter, and returns the old value (or 0). */
    public long remove(AEKey key) {
        var sub = lists.get(key.getPrimaryKey());
        if (sub == null) {
            return 0;
        }
        var e = sub.remove(key);
        if (sub.isEmpty()) {
            lists.remove(key.getPrimaryKey());
        }
        return e == null ? 0 : e.value;
    }

    public void set(AEKey key, long amount) {
        var sub = lists.computeIfAbsent(key.getPrimaryKey(), k -> new LinkedHashMap<>());
        var e = sub.get(key);
        if (e == null) {
            sub.put(key, new Entry(key, amount));
        } else {
            e.value = amount;
        }
    }

    public long get(AEKey key) {
        Objects.requireNonNull(key);
        var sub = lists.get(key.getPrimaryKey());
        if (sub == null) {
            return 0;
        }
        var e = sub.get(key);
        return e == null ? 0 : e.value;
    }

    /** Sets all amounts to 0 (keeps the keys). */
    public void reset() {
        for (var sub : lists.values()) {
            for (var e : sub.values()) {
                e.value = 0;
            }
        }
    }

    /** Removes all entries. */
    public void clear() {
        for (var sub : lists.values()) {
            sub.clear();
        }
    }

    public boolean isEmpty() {
        for (var sub : lists.values()) {
            if (!sub.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int size() {
        int tot = 0;
        for (var sub : lists.values()) {
            tot += sub.size();
        }
        return tot;
    }

    @Override
    public Iterator<Entry> iterator() {
        var outer = lists.values().iterator();
        return new Iterator<>() {
            Iterator<Entry> inner = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!inner.hasNext()) {
                    if (!outer.hasNext()) {
                        return false;
                    }
                    inner = outer.next().values().iterator();
                }
                return true;
            }

            @Override
            public Entry next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return inner.next();
            }

            @Override
            public void remove() {
                inner.remove();
            }
        };
    }

    public AEKey getFirstKey() {
        var e = getFirstEntry();
        return e != null ? e.key : null;
    }

    public Entry getFirstEntry() {
        var it = iterator();
        return it.hasNext() ? it.next() : null;
    }

    public Set<AEKey> keySet() {
        var keys = new HashSet<AEKey>(size());
        for (var e : this) {
            keys.add(e.key);
        }
        return keys;
    }

    /** Snapshot as an immutable map (insertion order). */
    public Map<AEKey, Long> toMap() {
        var map = new LinkedHashMap<AEKey, Long>();
        for (var e : this) {
            map.put(e.key, e.value);
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
