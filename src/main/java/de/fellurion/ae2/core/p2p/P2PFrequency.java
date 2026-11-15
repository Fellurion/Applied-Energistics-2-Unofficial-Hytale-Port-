/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.p2p;

import de.fellurion.ae2.core.grid.AEColor;

/**
 * Display forms of a P2P frequency: four hex digits, each standing for one of the 16 colors. Port of
 * {@code appeng.util.helpers.P2PHelper}.
 */
public final class P2PFrequency {
    private static final AEColor[] COLORS = AEColor.values();

    private P2PFrequency() {
    }

    /** The four colors of a frequency, most significant nibble first. */
    public static AEColor[] toColors(short frequency) {
        var colors = new AEColor[4];
        for (int i = 0; i < 4; i++) {
            colors[i] = COLORS[nibble(frequency, i)];
        }
        return colors;
    }

    /** Nibble {@code i} (0 = most significant) of the frequency. */
    public static int nibble(short frequency, int i) {
        return frequency >> 4 * (3 - i) & 0xF;
    }

    public static short fromColors(AEColor[] colors) {
        if (colors.length != 4) {
            throw new IllegalArgumentException("A frequency has four colors");
        }
        int t = 0;
        for (int i = 0; i < 4; i++) {
            t |= colors[3 - i].ordinal() << 4 * i;
        }
        return (short) (t & 0xFFFF);
    }

    /** Four upper-case hex digits, e.g. {@code 0A3F}. */
    public static String toHexString(short frequency) {
        return String.format("%04X", frequency & 0xFFFF);
    }

    /** Parses {@link #toHexString}; 0 (= not linked) for anything else. */
    public static short fromHexString(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() > 4) {
            return 0;
        }
        try {
            return (short) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
