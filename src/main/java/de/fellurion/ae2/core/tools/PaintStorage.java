/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.grid.AEColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * The paint a Color Applicator holds: uses per colour, the cleaning charge counting as {@link AEColor#TRANSPARENT}.
 * Port of the storage side of {@code appeng.items.tools.powered.ColorApplicatorItem} ({@code consumeColor},
 * {@code getColor}, {@code findNextColor}, {@code cycleColors}).
 * <p>
 * In AE2 the applicator is a storage cell (2048 bytes, 32 bytes per type, 27 types) holding dyes, paint balls and
 * snowballs. Here only the amount per colour is kept; the capacity follows the same cell maths: every colour in use
 * takes 32 bytes, the rest holds 8 uses per byte.
 * <p>
 * Text form (item metadata): {@code white:12,red:300,fluix:5} in the order of {@link AEColor}.
 */
public final class PaintStorage {

    /** AE energy per painted block. */
    public static final double ENERGY_PER_USE = 100;
    /** AE2: {@code StorageTier.SIZE_4K.bytes() / 2}. */
    public static final int BYTES = 2048;
    /** AE2: {@code StorageTier.SIZE_4K.bytes() / 128}. */
    public static final int BYTES_PER_TYPE = 32;
    private static final int USES_PER_BYTE = 8;

    private final EnumMap<AEColor, Integer> amounts = new EnumMap<>(AEColor.class);

    /** Parses the text form; unknown colours and broken entries are skipped. */
    public static PaintStorage parse(String text) {
        var result = new PaintStorage();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (var part : text.split(",")) {
            int sep = part.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            var color = colorById(part.substring(0, sep).trim());
            if (color == null) {
                continue;
            }
            try {
                int n = Integer.parseInt(part.substring(sep + 1).trim());
                if (n > 0) {
                    result.amounts.merge(color, n, Integer::sum);
                }
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return result;
    }

    /** The colour with exactly this id ("fluix" = transparent), or null. */
    public static AEColor colorById(String id) {
        for (var c : AEColor.values()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** Text form, or null if nothing is stored. */
    public String format() {
        if (amounts.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var e : amounts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey().id).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    public int get(AEColor color) {
        return amounts.getOrDefault(color, 0);
    }

    public boolean isEmpty() {
        return amounts.isEmpty();
    }

    /** Stored colours with their uses, in the order of {@link AEColor}. */
    public Map<AEColor, Integer> amounts() {
        return java.util.Collections.unmodifiableMap(amounts);
    }

    /** All uses of all colours together. */
    public int total() {
        int sum = 0;
        for (int n : amounts.values()) {
            sum += n;
        }
        return sum;
    }

    /** How many more uses of this colour fit (AE2: {@code BasicCellInventory#getRemainingItemCount}). */
    public int room(AEColor color) {
        int types = amounts.size() + (amounts.containsKey(color) ? 0 : 1);
        return Math.max(0, (BYTES - BYTES_PER_TYPE * types) * USES_PER_BYTE - total());
    }

    /**
     * Stores up to {@code count} uses of the colour.
     *
     * @return how many were stored
     */
    public int add(AEColor color, int count) {
        int added = Math.min(Math.max(0, count), room(color));
        if (added > 0) {
            amounts.merge(color, added, Integer::sum);
        }
        return added;
    }

    /**
     * Takes one use of the colour.
     *
     * @return false if there is none
     */
    public boolean consume(AEColor color) {
        int have = get(color);
        if (have <= 0) {
            return false;
        }
        if (have == 1) {
            amounts.remove(color);
        } else {
            amounts.put(color, have - 1);
        }
        return true;
    }

    /**
     * The colour in use: the selected one, or – as in {@code ColorApplicatorItem#getColor} – the first stored colour
     * when nothing is selected. Null if the applicator is empty and nothing is selected.
     */
    public AEColor active(AEColor selected) {
        if (selected != null) {
            return selected;
        }
        return amounts.isEmpty() ? null : amounts.keySet().iterator().next();
    }

    /**
     * The colour after {@code current} among the stored ones, in the order of {@link AEColor} and wrapping around.
     * Port of {@code ColorApplicatorItem#findNextColor} with a scroll offset of +1 ({@code direction > 0}) or −1.
     *
     * @return the next colour, or null if nothing is stored
     */
    public AEColor next(AEColor current, int direction) {
        if (amounts.isEmpty()) {
            return null;
        }
        var list = new java.util.ArrayDeque<>(amounts.keySet());
        if (current == null) {
            return list.peekFirst();
        }
        // AE2: rotate until the current colour is in front, at most size + 1 times
        int cycles = 1 + list.size();
        while (cycles > 0 && list.peekFirst() != current) {
            list.addLast(list.removeFirst());
            cycles--;
        }
        if (direction > 0) {
            list.addLast(list.removeFirst());
        } else if (direction < 0) {
            list.addFirst(list.removeLast());
        }
        return list.peekFirst();
    }
}
