/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import java.util.Objects;

/**
 * A fluid variant. Amounts of fluids are counted in milli-buckets (1 bucket = {@link #AMOUNT_BUCKET} = 1000).
 * <p>
 * Port of {@code appeng.api.stacks.AEFluidKey}. Deviation: AE2 fluid keys carry data components; Hytale fluids are
 * identified by id only.
 *
 * @param fluidId Hytale fluid id, e.g. {@code "Water"}
 */
public record AEFluidKey(String fluidId) implements AEKey {
    public static final int AMOUNT_BUCKET = 1000;
    public static final int AMOUNT_BLOCK = 1000;

    public AEFluidKey {
        Objects.requireNonNull(fluidId, "fluidId");
    }

    public static AEFluidKey of(String fluidId) {
        return new AEFluidKey(fluidId);
    }

    @Override
    public AEKeyType getType() {
        return AEKeyType.FLUIDS;
    }

    @Override
    public String getId() {
        return fluidId;
    }

    @Override
    public AEFluidKey dropSecondary() {
        return this;
    }

    @Override
    public String toString() {
        return "fluid:" + fluidId;
    }
}
