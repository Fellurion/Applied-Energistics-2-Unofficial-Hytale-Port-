/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import java.util.Objects;

/**
 * A key with an amount. Port of {@code appeng.api.stacks.GenericStack}.
 */
public record GenericStack(AEKey what, long amount) {
    public GenericStack {
        Objects.requireNonNull(what, "what");
    }

    public static GenericStack of(AEKey what, long amount) {
        return new GenericStack(what, amount);
    }

    /** Sum of amounts of the given stacks whose key equals {@code what}. */
    public static long sum(Iterable<GenericStack> stacks, AEKey what) {
        long sum = 0;
        for (var s : stacks) {
            if (s != null && s.what().equals(what)) {
                sum += s.amount();
            }
        }
        return sum;
    }

    @Override
    public String toString() {
        return amount + "x" + what;
    }
}
