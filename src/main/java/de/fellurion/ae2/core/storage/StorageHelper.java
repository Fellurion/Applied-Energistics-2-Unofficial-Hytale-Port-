/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.stacks.AEKey;

import java.util.Objects;

/**
 * Inserting into / extracting from ME storage costs energy. Port of {@code appeng.api.storage.StorageHelper}.
 * <p>
 * Cost: {@code amount / max(1, amountPerOperation)} AE (times the config usage multiplier), i.e. 1 AE per item and
 * 1 AE per 125 mB of fluid.
 */
public final class StorageHelper {
    private StorageHelper() {
    }

    public static long poweredExtraction(IEnergySource energy, MEStorage inv, AEKey request, long amount,
            IActionSource src) {
        return poweredExtraction(energy, inv, request, amount, src, Actionable.MODULATE);
    }

    /**
     * Extracts from a storage respecting power requirements.
     *
     * @return the amount extracted
     */
    public static long poweredExtraction(IEnergySource energy, MEStorage inv, AEKey request, long amount,
            IActionSource src, Actionable mode) {
        Objects.requireNonNull(energy, "energy");
        Objects.requireNonNull(inv, "inv");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(mode, "mode");

        var retrieved = inv.extract(request, amount, Actionable.SIMULATE, src);

        var energyFactor = Math.max(1.0, request.getAmountPerOperation());
        var availablePower = energy.extractAEPower(retrieved / energyFactor, Actionable.SIMULATE,
                PowerMultiplier.CONFIG);
        var itemToExtract = Math.min((long) (availablePower * energyFactor + 0.9), retrieved);

        if (itemToExtract > 0) {
            if (mode == Actionable.MODULATE) {
                energy.extractAEPower(retrieved / energyFactor, Actionable.MODULATE, PowerMultiplier.CONFIG);
                return inv.extract(request, itemToExtract, Actionable.MODULATE, src);
            } else {
                return itemToExtract;
            }
        }
        return 0;
    }

    public static long poweredInsert(IEnergySource energy, MEStorage inv, AEKey input, long amount,
            IActionSource src) {
        return poweredInsert(energy, inv, input, amount, src, Actionable.MODULATE);
    }

    /**
     * Inserts into a storage respecting power requirements.
     *
     * @return the amount inserted
     */
    public static long poweredInsert(IEnergySource energy, MEStorage inv, AEKey input, long amount,
            IActionSource src, Actionable mode) {
        Objects.requireNonNull(energy);
        Objects.requireNonNull(inv);
        Objects.requireNonNull(input);
        Objects.requireNonNull(src);
        Objects.requireNonNull(mode);

        amount = inv.insert(input, amount, Actionable.SIMULATE, src);
        if (amount <= 0) {
            return 0;
        }

        final double energyFactor = Math.max(1.0, input.getAmountPerOperation());
        final double availablePower = energy.extractAEPower(amount / energyFactor, Actionable.SIMULATE,
                PowerMultiplier.CONFIG);
        amount = Math.min((long) (availablePower * energyFactor + 0.9), amount);

        if (amount <= 0) {
            return 0;
        }

        if (mode == Actionable.MODULATE) {
            energy.extractAEPower(amount / energyFactor, Actionable.MODULATE, PowerMultiplier.CONFIG);
            return inv.insert(input, amount, Actionable.MODULATE, src);
        } else {
            return amount;
        }
    }
}
