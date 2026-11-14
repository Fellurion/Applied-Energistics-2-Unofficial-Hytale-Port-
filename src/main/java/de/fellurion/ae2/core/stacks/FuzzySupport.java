/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import java.util.Objects;

/**
 * Hook that lets the integration layer expose item durability for AE2's damage-based fuzzy modes (fuzzy card).
 * <p>
 * In AE2 the fuzzy value of an item key is its damage and the max value its max damage (see
 * {@code AEItemKey.getFuzzySearchValue}). The core cannot read Hytale item metadata, so by default every item is
 * treated as non-damageable (max damage 0). In that case every fuzzy mode behaves like {@code IGNORE_ALL}: all
 * variants of the same item id match, regardless of metadata — exactly like non-damageable items in AE2.
 */
public final class FuzzySupport {
    /** Resolves damage / max damage of an item key. */
    public interface DurabilityResolver {
        /** @return the current damage (0 = undamaged). */
        int damage(AEItemKey key);

        /** @return the max damage, or 0 if not damageable. */
        int maxDamage(AEItemKey key);
    }

    private static final DurabilityResolver NONE = new DurabilityResolver() {
        @Override
        public int damage(AEItemKey key) {
            return 0;
        }

        @Override
        public int maxDamage(AEItemKey key) {
            return 0;
        }
    };

    private static volatile DurabilityResolver resolver = NONE;

    private FuzzySupport() {
    }

    public static void setDurabilityResolver(DurabilityResolver r) {
        resolver = Objects.requireNonNullElse(r, NONE);
    }

    static int getDamage(AEItemKey key) {
        return resolver.damage(key);
    }

    static int getMaxDamage(AEItemKey key) {
        return resolver.maxDamage(key);
    }
}
