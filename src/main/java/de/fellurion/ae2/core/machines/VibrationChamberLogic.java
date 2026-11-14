/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Vibration chamber: burns fuel and injects AE into the grid. Exact port of
 * {@code appeng.blockentity.misc.VibrationChamberBlockEntity}.
 * <ul>
 * <li>Energy per fuel tick: {@code baseEnergyPerFuelTick (5) · (1 + energyCards/2)}.</li>
 * <li>Burn speed (fuel ticks per game tick) ranges from {@code minEnergyPerGameTick (4) / energyPerFuelTick} to
 * {@code maxEnergyRate / energyPerFuelTick}, with {@code maxEnergyRate = 40 · (1 + speedCards/2)}; it starts at
 * {@code baseEnergyPerFuelTick / energyPerFuelTick} (i.e. 5 AE/t).</li>
 * <li>The speed scales over the full range within 100 game ticks: up while the grid accepts all generated power,
 * down when power overflows (the overflow is voided).</li>
 * </ul>
 * Fuel burn times (in fuel ticks per item, Minecraft furnace semantics: coal = 1600) come from the integration via
 * {@code fuelBurnTime}; {@code remainder} may map a consumed last fuel item to a container item (lava bucket →
 * bucket). Register as owner of a {@code vibration_chamber} node.
 */
public final class VibrationChamberLogic implements IGridTickable, IGridNodeListener {
    public static final String ITEM_ID = "AE2_Vibration_Chamber";

    private final SlotAccess inv;
    private final ToIntFunction<SimpleStack> fuelBurnTime;
    private final Function<SimpleStack, SimpleStack> remainder;
    private final UpgradeInventory upgrades;
    private double currentFuelTicksPerTick;
    private double remainingFuelTicks = 0;
    private double fuelItemFuelTicks = 0;
    private double minFuelTicksPerTick;
    private double maxFuelTicksPerTick;
    private double initialFuelTicksPerTick;
    private boolean isOn;
    private final double minEnergyRate;
    private final double baseMaxEnergyRate;
    private final double initialEnergyRate;
    private GridNode node;
    private Runnable stateListener;

    /**
     * @param inv          1 fuel slot
     * @param fuelBurnTime burn time in fuel ticks for one item (0 = not a fuel)
     * @param remainder    container item left after burning the last item of a stack (may be null)
     */
    public VibrationChamberLogic(SlotAccess inv, ToIntFunction<SimpleStack> fuelBurnTime,
            Function<SimpleStack, SimpleStack> remainder) {
        this.inv = inv;
        this.fuelBurnTime = fuelBurnTime;
        this.remainder = remainder;
        this.upgrades = new UpgradeInventory(ITEM_ID, 3);
        var cfg = AEConfig.get();
        minEnergyRate = cfg.vibrationChamberMinEnergyPerGameTick();
        baseMaxEnergyRate = cfg.vibrationChamberMaxEnergyPerGameTick();
        initialEnergyRate = Math.max(minEnergyRate,
                Math.min(baseMaxEnergyRate, cfg.vibrationChamberBaseEnergyPerFuelTick()));
        minFuelTicksPerTick = minEnergyRate / getEnergyPerFuelTick();
        maxFuelTicksPerTick = baseMaxEnergyRate / getEnergyPerFuelTick();
        initialFuelTicksPerTick = initialEnergyRate / getEnergyPerFuelTick();
        currentFuelTicksPerTick = initialFuelTicksPerTick;
    }

    /** Called when {@link #isOn()} changes (block state {@code Working}). */
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public boolean isOn() {
        return isOn;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    /** Call when the fuel slot changed. */
    public void onInventoryChanged() {
        if (remainingFuelTicks <= 0 && canEatFuel() && node != null) {
            node.wake();
        }
    }

    private boolean canEatFuel() {
        var is = inv.get(0);
        return is != null && fuelBurnTime.applyAsInt(is) > 0;
    }

    /** Fuel slot filter: only items with a burn time. */
    public boolean isItemValid(SimpleStack stack) {
        return stack != null && fuelBurnTime.applyAsInt(stack) > 0;
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        if (remainingFuelTicks <= 0) {
            eatFuel();
        }
        return new TickingRequest(TickRates.VibrationChamber, remainingFuelTicks <= 0);
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        // Recalculate fuel tick rate min and max
        minFuelTicksPerTick = minEnergyRate / getEnergyPerFuelTick();
        maxFuelTicksPerTick = getMaxFuelTicksPerTick();
        initialFuelTicksPerTick = initialEnergyRate / getEnergyPerFuelTick();

        if (remainingFuelTicks <= 0) {
            eatFuel();
            if (remainingFuelTicks > 0) {
                return TickRateModulation.URGENT;
            }
            setCurrentFuelTicksPerTick(initialFuelTicksPerTick);
            return TickRateModulation.SLEEP;
        }

        double fuelTicksConsumed = ticksSinceLastCall * currentFuelTicksPerTick;
        remainingFuelTicks -= fuelTicksConsumed;
        if (remainingFuelTicks < 0) {
            fuelTicksConsumed += remainingFuelTicks;
            remainingFuelTicks = 0;
        }

        // The full range should scale in 5 seconds (=100 ticks)
        var speedScalingPerTick = (maxFuelTicksPerTick - minFuelTicksPerTick) / 100;
        double speedStep = ticksSinceLastCall * speedScalingPerTick;

        var energy = node.getGrid().getEnergyService();

        // If our burn rate is zero, check if the network would now accept any power
        if (Math.abs(fuelTicksConsumed) < 0.01) {
            if (energy.injectPower(1, Actionable.SIMULATE) == 0) {
                setCurrentFuelTicksPerTick(currentFuelTicksPerTick + speedStep);
                return TickRateModulation.FASTER;
            }
            return TickRateModulation.IDLE;
        }

        final double newPower = fuelTicksConsumed * getEnergyPerFuelTick();
        final double overFlow = energy.injectPower(newPower, Actionable.MODULATE);

        // Speed up or slow down the burn rate, the overflow is voided
        if (overFlow > 0) {
            setCurrentFuelTicksPerTick(currentFuelTicksPerTick - speedStep);
        } else {
            setCurrentFuelTicksPerTick(currentFuelTicksPerTick + speedStep);
        }
        updateOnState();
        return overFlow > 0 ? TickRateModulation.SLOWER : TickRateModulation.FASTER;
    }

    private void eatFuel() {
        var is = inv.get(0);
        if (is != null) {
            int newBurnTime = fuelBurnTime.applyAsInt(is);
            if (newBurnTime > 0) {
                remainingFuelTicks += newBurnTime;
                fuelItemFuelTicks = remainingFuelTicks;
                if (is.count() <= 1) {
                    var rest = remainder == null ? null : remainder.apply(is);
                    inv.set(0, rest);
                } else {
                    inv.set(0, is.withCount(is.count() - 1));
                }
            }
        }
        if (remainingFuelTicks > 0 && node != null) {
            node.wake();
        }
        updateOnState();
    }

    private void updateOnState() {
        boolean on = remainingFuelTicks > 0;
        if (on != isOn) {
            isOn = on;
            if (stateListener != null) {
                stateListener.run();
            }
        }
    }

    public double getCurrentFuelTicksPerTick() {
        return currentFuelTicksPerTick;
    }

    private void setCurrentFuelTicksPerTick(double v) {
        currentFuelTicksPerTick = Math.max(minFuelTicksPerTick, Math.min(v, maxFuelTicksPerTick));
    }

    public double getFuelItemFuelTicks() {
        return fuelItemFuelTicks;
    }

    public double getRemainingFuelTicks() {
        return remainingFuelTicks;
    }

    /** AE generated per consumed fuel tick. */
    public double getEnergyPerFuelTick() {
        return AEConfig.get().vibrationChamberBaseEnergyPerFuelTick()
                * (1 + upgrades.getInstalledUpgrades(UpgradeCard.ENERGY) / 2.0f);
    }

    public double getMinFuelTicksPerTick() {
        return minFuelTicksPerTick;
    }

    public double getMaxFuelTicksPerTick() {
        return getMaxEnergyRate() / getEnergyPerFuelTick();
    }

    /** Max AE per game tick. */
    public double getMaxEnergyRate() {
        return baseMaxEnergyRate + baseMaxEnergyRate * upgrades.getInstalledUpgrades(UpgradeCard.SPEED) / 2.0f;
    }

    /** Current generation in AE per game tick while burning. */
    public double getCurrentEnergyRate() {
        return currentFuelTicksPerTick * getEnergyPerFuelTick();
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("burnTime", remainingFuelTicks);
        map.put("maxBurnTime", fuelItemFuelTicks);
        // Save as percentage of max-speed
        map.put("burnSpeed", (int) (currentFuelTicksPerTick * 100 / maxFuelTicksPerTick));
        map.put("upgrades", upgrades.toItemIds());
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        upgrades.fromItemIds(MiniJson.getList(map, "upgrades"));
        maxFuelTicksPerTick = getMaxFuelTicksPerTick();
        minFuelTicksPerTick = minEnergyRate / getEnergyPerFuelTick();
        remainingFuelTicks = MiniJson.getDouble(map, "burnTime", 0);
        fuelItemFuelTicks = MiniJson.getDouble(map, "maxBurnTime", 0);
        setCurrentFuelTicksPerTick(MiniJson.getInt(map, "burnSpeed", 0) * maxFuelTicksPerTick / 100.0);
        isOn = remainingFuelTicks > 0;
    }
}
