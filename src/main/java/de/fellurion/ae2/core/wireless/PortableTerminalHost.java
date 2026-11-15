/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.storage.MEStorage;

import java.util.Objects;

/**
 * A terminal that lives in an item (wireless terminal, portable cell) and runs on the item's battery. Port of
 * {@code appeng.api.implementations.menuobjects.ItemMenuHost} (idle power) and
 * {@code appeng.api.implementations.menuobjects.IPortableTerminal}.
 * <p>
 * While the terminal is open, call {@link #tick()} once per game tick. To avoid changing the item every tick, idle
 * power is taken for {@value #BUFFER_ENERGY_TICKS} ticks at a time (AE2: twice per second).
 * <p>
 * Deviations: the item is behind the small {@link Battery} interface instead of Minecraft's {@code ItemStack}.
 * {@link #tick(int)} lets the integration layer account for several game ticks in one call; the surroundings (e.g. the
 * access point in range) are then looked at once per call instead of once per tick.
 */
public abstract class PortableTerminalHost implements IEnergySource {
    /** Idle power is consumed for this many ticks at once. */
    public static final int BUFFER_ENERGY_TICKS = 10;

    /** The battery of the hosting item (AE2 {@code IAEItemPowerStorage} on the item in its inventory slot). */
    public interface Battery {
        /** Stored energy in AE. */
        double getAECurrentPower();

        /**
         * Takes energy out of the item.
         *
         * @return the amount extracted
         */
        double extractAEPower(double amount, Actionable mode);
    }

    private final Battery battery;
    private boolean creative;
    private int remainingEnergyTicks = 0;

    protected PortableTerminalHost(Battery battery) {
        this.battery = Objects.requireNonNull(battery, "battery");
    }

    protected final Battery battery() {
        return battery;
    }

    /** Creative players do not drain idle power. */
    public void setCreative(boolean creative) {
        this.creative = creative;
    }

    public boolean isCreative() {
        return creative;
    }

    /** Periodic work while the terminal is open; call once per game tick. */
    public final void tick() {
        tick(1);
    }

    /**
     * Accounts for {@code ticks} game ticks at once: the idle power of every tick is consumed, the link status is
     * updated once at the end.
     */
    public abstract void tick(int ticks);

    /** Whether the storage can currently be reached. */
    public abstract LinkStatus getLinkStatus();

    /** The storage shown by the terminal. */
    public abstract MEStorage getInventory();

    /**
     * Consumes (or simulates consuming) the idle power for one tick.
     *
     * @return true if there is enough energy to last one tick
     */
    public boolean consumeIdlePower(Actionable action) {
        // Do not drain power for creative players
        if (creative) {
            return true;
        }

        // Remaining charge
        if (remainingEnergyTicks > 0) {
            if (action == Actionable.MODULATE) {
                remainingEnergyTicks--;
            }
            return true;
        }

        var powerDrainPerTick = getPowerDrainPerTick();
        if (powerDrainPerTick > 0) {
            var amt = BUFFER_ENERGY_TICKS * powerDrainPerTick;
            var actualExtracted = extractAEPower(amt, action, PowerMultiplier.CONFIG);
            var remaining = (int) Math.ceil(actualExtracted / powerDrainPerTick);
            if (action == Actionable.MODULATE) {
                this.remainingEnergyTicks = remaining;
            }

            // Return true if we drained enough energy to last one tick
            return remaining > 0;
        }

        // If no power is being drained, we're never out of power
        return true;
    }

    /** Idle power drain in AE per tick. */
    protected double getPowerDrainPerTick() {
        return 0.5;
    }

    /** Ticks that are already paid for. */
    public int getRemainingEnergyTicks() {
        return remainingEnergyTicks;
    }
}
