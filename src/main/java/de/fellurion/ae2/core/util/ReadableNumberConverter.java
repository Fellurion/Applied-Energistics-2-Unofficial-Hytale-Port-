/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.util;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Restricts the string representation of a number to a width (3 or 4 characters in a slot) by using the postfixes
 * K, M, G, T, P and E. Port of {@code appeng.util.ReadableNumberConverter}.
 * <p>
 * Deviation: AE2 writes the decimal separator of the JVM's default locale; here it is always a dot, so that the
 * server's locale does not leak into what every player sees.
 */
public final class ReadableNumberConverter {
    /** Defines the base for a division. */
    private static final int DIVISION_BASE = 1000;

    /** The sorted postfixes. */
    private static final char[] ENCODED_POSTFIXES = "KMGTPE".toCharArray();

    private ReadableNumberConverter() {
    }

    /**
     * Restricts a non-negative whole number to {@code width} characters.
     *
     * @param number the number
     * @param width  width limitation of the result
     * @return the formatted number
     */
    public static String format(long number, int width) {
        if (number < 0) {
            throw new IllegalArgumentException("Negative numbers cannot be formatted by this method");
        }

        // handles low numbers more efficiently since no format is needed
        final String numberString = Long.toString(number);
        int numberSize = numberString.length();
        if (numberSize <= width) {
            return numberString;
        }

        long base = number;
        double last = base * 1000d;
        int exponent = -1;
        String postFix = "";

        while (numberSize > width) {
            last = base;
            base /= DIVISION_BASE;

            exponent++;

            // adds +1 due to the postfix
            numberSize = Long.toString(base).length() + 1;
            postFix = String.valueOf(ENCODED_POSTFIXES[exponent]);
        }

        final String withPrecision = getFormat().format(last / DIVISION_BASE) + postFix;
        final String withoutPrecision = base + postFix;

        return withPrecision.length() <= width ? withPrecision : withoutPrecision;
    }

    /**
     * Restricts a non-negative number with a fractional part to {@code width} characters, for example ".125",
     * "12.3" or "~123" (the fraction does not fit).
     *
     * @param number the number
     * @param width  width limitation of the result
     * @return the formatted number
     */
    public static String format(double number, int width) {
        if (!(number >= 0)) {
            throw new IllegalArgumentException("Negative numbers cannot be formatted by this method");
        }

        var integerDigits = (int) Math.max(0, Math.log10(number) + 1);
        var fractionalDigits = width - integerDigits - 1;
        var minFractional = Math.pow(10, -fractionalDigits);
        var fractional = number - Math.floor(number);

        // In cases where we have more integer digits than we have space, or when the fractional part is zero,
        // we can go to the integer-formatter.
        // We have to take the decimal point into account which we'd need if we would show the fractional.
        if (fractional < 1e-9 || integerDigits > width - 1) {
            return format((long) number, width);
        }

        // If the fractional is smaller than the amount of fractional space we have, we omit
        // the fractional and instead prefix "~"
        if (fractional + 1e-9 < minFractional && integerDigits - 1 <= width) {
            return "~" + format((long) number, width - 1);
        }

        var format = getFormat();
        format.setMaximumFractionDigits(fractionalDigits);
        return format.format(number);
    }

    private static DecimalFormat getFormat() {
        var format = new DecimalFormat(".#;0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setDecimalSeparatorAlwaysShown(false);
        format.setRoundingMode(RoundingMode.DOWN);
        return format;
    }
}
