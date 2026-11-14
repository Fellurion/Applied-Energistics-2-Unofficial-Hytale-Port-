/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Cable / grid node colors. Port of {@code appeng.api.util.AEColor}. {@link #TRANSPARENT} is AE2's "Fluix" color,
 * which connects to every color.
 */
public enum AEColor {
    WHITE("white", 0xe0e0e0),
    LIGHT_GRAY("light_gray", 0xa09fa0),
    GRAY("gray", 0x6c6b6c),
    BLACK("black", 0x272727),
    LIME("lime", 0x70e259),
    YELLOW("yellow", 0xffe359),
    ORANGE("orange", 0xeca23c),
    BROWN("brown", 0x7e5c16),
    RED("red", 0xd73e42),
    PINK("pink", 0xff99bb),
    MAGENTA("magenta", 0xd5719c),
    PURPLE("purple", 0x915dcd),
    BLUE("blue", 0x3894ff),
    LIGHT_BLUE("light_blue", 0x70d2ff),
    CYAN("cyan", 0x2fccb7),
    GREEN("green", 0x17b86d),
    /** Fluix: connects to all colors. */
    TRANSPARENT("fluix", 0x915dcd);

    /** Lower-case id as used in AE2 registry names ("fluix" for transparent). */
    public final String id;
    /** AE2's medium color variant (RGB). */
    public final int mediumVariant;

    AEColor(String id, int mediumVariant) {
        this.id = id;
        this.mediumVariant = mediumVariant;
    }

    /**
     * Two nodes may connect iff one of them is transparent (fluix) or both have the same color. Port of
     * {@code InWorldGridNode.hasCompatibleColor}.
     */
    public boolean isCompatibleWith(AEColor other) {
        return this == TRANSPARENT || other == TRANSPARENT || this == other;
    }

    /** Parses "white", "light_blue", "fluix", "transparent" (case-insensitive). Unknown → TRANSPARENT. */
    public static AEColor fromId(String id) {
        if (id == null) {
            return TRANSPARENT;
        }
        var lower = id.toLowerCase(java.util.Locale.ROOT);
        for (var c : values()) {
            if (c.id.equals(lower) || c.name().toLowerCase(java.util.Locale.ROOT).equals(lower)) {
                return c;
            }
        }
        return TRANSPARENT;
    }
}
