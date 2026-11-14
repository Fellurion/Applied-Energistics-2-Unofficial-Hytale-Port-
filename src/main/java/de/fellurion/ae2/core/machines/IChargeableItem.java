/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.inv.SimpleStack;

/**
 * Hook through which the integration layer exposes AE-powered items (wireless terminals, portable cells, energy
 * cell items, ...) to the charger. Port of {@code appeng.api.implementations.items.IAEItemPowerStorage}; since
 * {@link SimpleStack} is immutable, injection returns the updated stack.
 */
public interface IChargeableItem {
    /** Result of {@link #injectAEPower}: the updated stack and the energy that did not fit. */
    record Charged(SimpleStack stack, double leftover) {
    }

    boolean isChargeable(SimpleStack stack);

    double getAECurrentPower(SimpleStack stack);

    double getAEMaxPower(SimpleStack stack);

    /** AE per tick the charger may push into this item. */
    double getChargeRate(SimpleStack stack);

    Charged injectAEPower(SimpleStack stack, double amount);

    /** No chargeable items. */
    IChargeableItem NONE = new IChargeableItem() {
        @Override
        public boolean isChargeable(SimpleStack stack) {
            return false;
        }

        @Override
        public double getAECurrentPower(SimpleStack stack) {
            return 0;
        }

        @Override
        public double getAEMaxPower(SimpleStack stack) {
            return 0;
        }

        @Override
        public double getChargeRate(SimpleStack stack) {
            return 0;
        }

        @Override
        public Charged injectAEPower(SimpleStack stack, double amount) {
            return new Charged(stack, amount);
        }
    };
}
