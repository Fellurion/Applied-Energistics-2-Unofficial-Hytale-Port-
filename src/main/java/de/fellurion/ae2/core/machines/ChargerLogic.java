/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
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
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Charger logic. Port of {@code appeng.blockentity.misc.ChargerBlockEntity}.
 * <ul>
 * <li>Ticks every 10 game ticks ({@link TickRates#Charger}); internal buffer 1600 AE, refilled from the grid with up
 * to 800 AE per charger tick while below 1599.</li>
 * <li>Recipes: once the buffer holds at least 1599 AE, each charger tick has a 20% chance
 * ({@code nextFloat() > 0.8}) to convert the item, consuming the whole buffer (1600 AE · usage multiplier).</li>
 * <li>Chargeable items ({@link IChargeableItem}): charged with {@code chargeRate · ticks · chargerChargeRate} AE per
 * charger tick, first from the buffer, then directly from the grid.</li>
 * </ul>
 * Slot 0 holds the item (max stack 1). Register as owner of a {@code charger} node ({@code NodeType.CHARGER}).
 */
public final class ChargerLogic implements IGridTickable, IGridNodeListener {
    public static final int POWER_MAXIMUM_AMOUNT = 1600;
    private static final int POWER_THRESHOLD = POWER_MAXIMUM_AMOUNT - 1;

    private final SlotAccess inv;
    private final ChargerRecipes recipes;
    private final IChargeableItem chargeable;
    private final RandomGenerator random;
    private final PoweredMachineBuffer buffer = new PoweredMachineBuffer(POWER_MAXIMUM_AMOUNT);
    private boolean working;
    private GridNode node;
    private Runnable stateListener;

    /**
     * @param inv        1 slot
     * @param recipes    charger recipes
     * @param chargeable hook for powered items (may be {@link IChargeableItem#NONE})
     * @param random     random source (seedable for tests)
     */
    public ChargerLogic(SlotAccess inv, ChargerRecipes recipes, IChargeableItem chargeable, RandomGenerator random) {
        this.inv = inv;
        this.recipes = Objects.requireNonNull(recipes);
        this.chargeable = chargeable == null ? IChargeableItem.NONE : chargeable;
        this.random = random == null ? RandomGenerator.getDefault() : random;
    }

    /** Called when {@link #isWorking()} or the slot content changed (visual update). */
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    public PoweredMachineBuffer getBuffer() {
        return buffer;
    }

    /** Crank target (AE2: any side but the front). */
    public ICrankable getCrankable() {
        return buffer.crankable();
    }

    /** Block state {@code Working}. */
    public boolean isWorking() {
        return working;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    /** Call when the slot changed. */
    public void onInventoryChanged() {
        if (node != null) {
            node.wake();
        }
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.Charger, false);
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        doWork(ticksSinceLastCall);
        return TickRateModulation.FASTER;
    }

    /** One charger work step (exposed for tests / integrations that tick manually). */
    public void doWork(int ticksSinceLastCall) {
        var wasWorking = working;
        working = false;
        var changed = false;
        var grid = node == null ? null : node.getGrid();

        var myItem = inv.get(0);
        if (myItem != null) {
            if (chargeable.isChargeable(myItem)) {
                var currentPower = chargeable.getAECurrentPower(myItem);
                var maxPower = chargeable.getAEMaxPower(myItem);
                if (currentPower < maxPower) {
                    // Charge rate is "per tick": scale by the ticks since the last charger tick.
                    var chargeRate = chargeable.getChargeRate(myItem) * ticksSinceLastCall
                            * AEConfig.get().chargerChargeRate();
                    // First charge from the local buffer
                    double extractedAmount = buffer.extractAEPower(chargeRate, Actionable.MODULATE,
                            PowerMultiplier.CONFIG);
                    var missingChargeRate = chargeRate - extractedAmount;
                    var missingAEPower = maxPower - currentPower;
                    var toExtract = Math.min(missingChargeRate, missingAEPower);
                    // Then directly extract from the grid
                    if (grid != null) {
                        extractedAmount += grid.getEnergyService().extractAEPower(toExtract, Actionable.MODULATE,
                                PowerMultiplier.ONE);
                    }
                    if (extractedAmount > 0) {
                        var result = chargeable.injectAEPower(myItem, extractedAmount);
                        inv.set(0, result.stack());
                        buffer.setAECurrentPower(buffer.getAECurrentPower() + result.leftover());
                        working = true;
                        changed = true;
                    }
                }
            } else if (buffer.getAECurrentPower() >= POWER_THRESHOLD && recipes.findRecipe(myItem) != null) {
                working = true;
                if (random.nextFloat() > 0.8f) {
                    buffer.extractAEPower(buffer.getAEMaxPower(), Actionable.MODULATE, PowerMultiplier.CONFIG);
                    var recipe = recipes.findRecipe(myItem);
                    inv.set(0, recipe.output().toStack());
                    changed = true;
                }
            }
        }

        // charge from the network!
        if (buffer.getAECurrentPower() < POWER_THRESHOLD && grid != null) {
            double toExtract = Math.min(800.0, buffer.getAEMaxPower() - buffer.getAECurrentPower());
            double extracted = grid.getEnergyService().extractAEPower(toExtract, Actionable.MODULATE,
                    PowerMultiplier.ONE);
            buffer.injectAEPower(extracted, Actionable.MODULATE);
        }

        if ((changed || working != wasWorking) && stateListener != null) {
            stateListener.run();
        }
    }

    /** Slot filter: chargeable items or recipe inputs. */
    public boolean isItemValid(SimpleStack stack) {
        return chargeable.isChargeable(stack) || recipes.allowInsert(stack);
    }

    /** Extraction filter: fully charged items or recipe results. */
    public boolean canExtract() {
        var item = inv.get(0);
        if (item == null) {
            return false;
        }
        if (chargeable.isChargeable(item)
                && chargeable.getAECurrentPower(item) >= chargeable.getAEMaxPower(item)) {
            return true;
        }
        return recipes.allowExtract(item);
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("power", buffer.getAECurrentPower());
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        buffer.setAECurrentPower(MiniJson.getDouble(MiniJson.parseObject(json), "power", 0));
    }
}
