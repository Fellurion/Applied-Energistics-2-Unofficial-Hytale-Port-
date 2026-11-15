/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.CableType;

import java.util.Locale;

/**
 * Item ids of everything that carries an AE2 colour: paint balls ({@code appeng.items.misc.PaintBallItem}), the
 * cleaning item of the Color Applicator ({@code ColorApplicatorItem#getColorFrom}: a snowball counts as
 * {@link AEColor#TRANSPARENT}) and the coloured cables.
 * <p>
 * Deviations from AE2: vanilla dyes are no paint here (Hytale has no dye item for every colour); in AE2 a cable is one
 * block whose colour is a property, here every colour is its own block ({@code AE2_<Colour>_<Type>_Cable}).
 */
public final class PaintItems {

    /** What Minecraft's snowball is mapped to (see {@code tools/ingredient_map.json}). */
    public static final String CLEANING_ITEM = "Soil_Snow";

    private static final String PREFIX = "AE2_";
    private static final String PAINT_BALL = "_Paint_Ball";
    private static final String LUMEN_PAINT_BALL = "_Lumen_Paint_Ball";
    private static final String CABLE = "_Cable";

    private PaintItems() {
    }

    /** {@code light_blue} → {@code Light_Blue}, {@code fluix} → {@code Fluix}. */
    public static String title(AEColor color) {
        var sb = new StringBuilder();
        for (var word : color.id.split("_")) {
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }

    /**
     * Colour of a paint ball (normal or lumen), or null if the item is none. There are no fluix paint balls.
     */
    public static AEColor paintBallColor(String itemId) {
        if (itemId == null || !itemId.startsWith(PREFIX) || !itemId.endsWith(PAINT_BALL)) {
            return null;
        }
        var end = itemId.endsWith(LUMEN_PAINT_BALL) ? LUMEN_PAINT_BALL : PAINT_BALL;
        var name = itemId.substring(PREFIX.length(), itemId.length() - end.length());
        for (var c : AEColor.values()) {
            if (c != AEColor.TRANSPARENT && title(c).equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** True for a lumen paint ball. */
    public static boolean isLumen(String itemId) {
        return itemId != null && itemId.endsWith(LUMEN_PAINT_BALL) && paintBallColor(itemId) != null;
    }

    /**
     * The colour an item gives a Color Applicator: a paint ball's colour, {@link AEColor#TRANSPARENT} for the
     * cleaning item, null for everything else. AE2: {@code ColorApplicatorItem#getColorFrom}.
     */
    public static AEColor paintColor(String itemId) {
        if (CLEANING_ITEM.equals(itemId)) {
            return AEColor.TRANSPARENT;
        }
        return paintBallColor(itemId);
    }

    /** Item id of the normal paint ball of a colour; null for {@link AEColor#TRANSPARENT}. */
    public static String paintBall(AEColor color) {
        return color == AEColor.TRANSPARENT ? null : PREFIX + title(color) + PAINT_BALL;
    }

    /** Item (and block) id of a cable. */
    public static String cable(AEColor color, CableType type) {
        return PREFIX + title(color) + "_" + title(type) + CABLE;
    }

    /** Colour of a cable item, or null if the id is no cable. */
    public static AEColor cableColor(String itemId) {
        var parts = cableParts(itemId);
        return parts == null ? null : (AEColor) parts[0];
    }

    /** Type of a cable item, or null if the id is no cable. */
    public static CableType cableType(String itemId) {
        var parts = cableParts(itemId);
        return parts == null ? null : (CableType) parts[1];
    }

    /**
     * The id of the same cable in another colour, or null if {@code itemId} is no cable or already has that colour.
     */
    public static String recoloredCable(String itemId, AEColor color) {
        var parts = cableParts(itemId);
        if (parts == null || parts[0] == color) {
            return null;
        }
        return cable(color, (CableType) parts[1]);
    }

    private static Object[] cableParts(String itemId) {
        if (itemId == null || !itemId.startsWith(PREFIX) || !itemId.endsWith(CABLE)) {
            return null;
        }
        var name = itemId.substring(PREFIX.length(), itemId.length() - CABLE.length());
        for (var c : AEColor.values()) {
            var start = title(c) + "_";
            if (!name.startsWith(start)) {
                continue;
            }
            var rest = name.substring(start.length());
            for (var t : CableType.values()) {
                if (title(t).equals(rest)) {
                    return new Object[]{c, t};
                }
            }
        }
        return null;
    }

    private static String title(CableType type) {
        var sb = new StringBuilder();
        for (var word : type.id.split("_")) {
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }
}
