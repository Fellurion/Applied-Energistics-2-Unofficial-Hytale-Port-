/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import de.fellurion.ae2.core.util.ReadableNumberConverter;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The kind of resource an {@link AEKey} describes.
 * <p>
 * Port of {@code appeng.api.stacks.AEKeyType} together with {@code AEItemKeys} / {@code AEFluidKeys}. Fluids are
 * counted in milli-buckets like AE2 does ({@link AEFluidKey#AMOUNT_BUCKET} = 1000).
 */
public enum AEKeyType {
    /** Items: 1 per operation, 8 per byte. */
    ITEMS("i", 1, 8, 1, null),
    /**
     * Fluids: 125 mB per operation (AE2: "On Forge this was 125mb"), 8 buckets (8000 mB) per byte, 1000 mB per
     * displayed unit "B".
     */
    FLUIDS("f", AEFluidKey.AMOUNT_BUCKET * 125 / 1000, 8 * AEFluidKey.AMOUNT_BUCKET, AEFluidKey.AMOUNT_BUCKET, "B");

    private final String shortId;
    private final int amountPerOperation;
    private final int amountPerByte;
    private final int amountPerUnit;
    private final String unitSymbol;

    AEKeyType(String shortId, int amountPerOperation, int amountPerByte, int amountPerUnit, String unitSymbol) {
        this.shortId = shortId;
        this.amountPerOperation = amountPerOperation;
        this.amountPerByte = amountPerByte;
        this.amountPerUnit = amountPerUnit;
        this.unitSymbol = unitSymbol;
    }

    /** Short id used in serialized form ("i" / "f"). */
    public String shortId() {
        return shortId;
    }

    public static AEKeyType byShortId(String id) {
        for (var t : values()) {
            if (t.shortId.equals(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown key type: " + id);
    }

    /**
     * How much of this key will be transferred as part of one transfer operation (buses, IO port). Used to balance
     * item vs. fluid transfers.
     */
    public int getAmountPerOperation() {
        return amountPerOperation;
    }

    /** The amount of this key type that can be stored per byte used in a storage cell (8 items / 8000 mB). */
    public int getAmountPerByte() {
        return amountPerByte;
    }

    /** Amount per displayed unit (1 for items, 1000 for fluids = 1 bucket). */
    public int getAmountPerUnit() {
        return amountPerUnit;
    }

    /** Unit symbol for display, or null. */
    public String getUnitSymbol() {
        return unitSymbol;
    }

    /**
     * Formats an amount of this key type (items: pieces, fluids: mB) like AE2 does. Fluids are written in buckets:
     * {@code FULL} gives "1.5 B", {@code SLOT} "1.5" (at most four characters, see {@link ReadableNumberConverter}).
     * <p>
     * Port of {@code AEKeyType.formatAmount}. Deviation: the full amount uses {@link Locale#ROOT}; pass the reader's
     * locale to {@link #formatAmount(long, AmountFormat, Locale)} for grouping and decimal separators in their
     * language.
     */
    public String formatAmount(long amount, AmountFormat format) {
        return formatAmount(amount, format, Locale.ROOT);
    }

    /** As {@link #formatAmount(long, AmountFormat)}; {@code locale} is used for the full amount only. */
    public String formatAmount(long amount, AmountFormat format, Locale locale) {
        return switch (format) {
            case FULL -> formatFullAmount(amount, locale);
            case SLOT -> formatShortAmount(amount, 4);
            case SLOT_LARGE_FONT -> formatShortAmount(amount, 3);
        };
    }

    private String formatFullAmount(long amount, Locale locale) {
        var result = new StringBuilder();
        var numbers = NumberFormat.getNumberInstance(locale == null ? Locale.ROOT : locale);
        if (amountPerUnit > 1) {
            result.append(numbers.format(amount / (double) amountPerUnit));
        } else {
            result.append(numbers.format(amount));
        }
        if (unitSymbol != null) {
            result.append(' ').append(unitSymbol);
        }
        return result.toString();
    }

    private String formatShortAmount(long amount, int maxWidth) {
        if (amountPerUnit > 1) {
            return ReadableNumberConverter.format(amount / (double) amountPerUnit, maxWidth);
        }
        return ReadableNumberConverter.format(amount, maxWidth);
    }

    /**
     * True if keys of this type support damage-based fuzzy range search (AE2: items only).
     */
    public boolean supportsFuzzyRangeSearch() {
        return this == ITEMS;
    }

    public boolean contains(AEKey key) {
        return key != null && key.getType() == this;
    }
}
