/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.stacks;

import de.fellurion.ae2.core.util.ReadableNumberConverter;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Amount formatting as in AE2 ({@code AEKeyType.formatAmount}, {@code ReadableNumberConverter}). */
class AmountFormatTest {

    @Test
    void wholeNumbersAreShortenedWithPostfixes() {
        assertEquals("0", ReadableNumberConverter.format(0L, 4));
        assertEquals("9999", ReadableNumberConverter.format(9999L, 4));
        assertEquals("10K", ReadableNumberConverter.format(10_000L, 4));
        assertEquals("12K", ReadableNumberConverter.format(12_345L, 4));
        assertEquals("155K", ReadableNumberConverter.format(155_555L, 4));
        assertEquals("1.5M", ReadableNumberConverter.format(1_555_555L, 4));
        assertEquals("15M", ReadableNumberConverter.format(15_555_555L, 4));
        assertEquals("999", ReadableNumberConverter.format(999L, 3));
        assertEquals("1K", ReadableNumberConverter.format(1_000L, 3));
        assertEquals("9K", ReadableNumberConverter.format(9_999L, 3));
        assertThrows(IllegalArgumentException.class, () -> ReadableNumberConverter.format(-1L, 4));
    }

    @Test
    void fractionsFillTheRemainingWidth() {
        assertEquals("1", ReadableNumberConverter.format(1.0, 4));
        assertEquals("1.5", ReadableNumberConverter.format(1.5, 4));
        assertEquals(".125", ReadableNumberConverter.format(0.125, 4));
        assertEquals("12.3", ReadableNumberConverter.format(12.345, 4));
        // no room for the fraction: "~" marks that something was cut off
        assertEquals("~123", ReadableNumberConverter.format(123.456, 4));
        assertEquals("1234", ReadableNumberConverter.format(1234.5, 4));
        assertEquals("12K", ReadableNumberConverter.format(12_345.678, 4));
    }

    @Test
    void fluidsAreWrittenInBuckets() {
        var f = AEKeyType.FLUIDS;
        assertEquals("1 B", f.formatAmount(1000, AmountFormat.FULL));
        assertEquals("1.5 B", f.formatAmount(1500, AmountFormat.FULL));
        assertEquals("0.125 B", f.formatAmount(125, AmountFormat.FULL));
        assertEquals("16 B", f.formatAmount(16_000, AmountFormat.FULL));
        assertEquals("12,345.678 B", f.formatAmount(12_345_678, AmountFormat.FULL, Locale.US));
        assertEquals("12.345,678 B", f.formatAmount(12_345_678, AmountFormat.FULL, Locale.GERMANY));
        assertEquals("0 B", f.formatAmount(0, AmountFormat.FULL));

        assertEquals("1", f.formatAmount(1000, AmountFormat.SLOT));
        assertEquals("1.5", f.formatAmount(1500, AmountFormat.SLOT));
        assertEquals(".125", f.formatAmount(125, AmountFormat.SLOT));
        assertEquals("16", f.formatAmount(16_000, AmountFormat.SLOT));
        assertEquals("12K", f.formatAmount(12_345_678, AmountFormat.SLOT));
        assertEquals("0", f.formatAmount(0, AmountFormat.SLOT));
        assertEquals("1.5", f.formatAmount(1500, AmountFormat.SLOT_LARGE_FONT));
        assertEquals("~16", f.formatAmount(16_125, AmountFormat.SLOT_LARGE_FONT));
    }

    @Test
    void itemsAreWrittenAsPieces() {
        var i = AEKeyType.ITEMS;
        assertEquals("1,234", i.formatAmount(1234, AmountFormat.FULL, Locale.US));
        assertEquals("1234", i.formatAmount(1234, AmountFormat.SLOT));
        assertEquals("1K", i.formatAmount(1234, AmountFormat.SLOT_LARGE_FONT));
        assertEquals("12K", i.formatAmount(12_345, AmountFormat.SLOT));
    }
}
