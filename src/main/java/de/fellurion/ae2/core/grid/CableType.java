/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Cable kinds. Port of {@code appeng.api.util.AECableType} (minus
 * NONE/facades). Glass, covered and smart cables are functionally identical (8 channels); dense cables carry 32.
 */
public enum CableType {
    GLASS("glass", false),
    COVERED("covered", false),
    SMART("smart", false),
    COVERED_DENSE("covered_dense", true),
    SMART_DENSE("smart_dense", true);

    public final String id;
    public final boolean dense;

    CableType(String id, boolean dense) {
        this.id = id;
        this.dense = dense;
    }

    /** Smart cables display their channel usage. */
    public boolean isSmart() {
        return this == SMART || this == SMART_DENSE;
    }

    public static CableType fromId(String id) {
        for (var t : values()) {
            if (t.id.equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown cable type: " + id);
    }
}
