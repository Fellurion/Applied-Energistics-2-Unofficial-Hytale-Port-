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
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Inscriber machine logic. Port of {@code appeng.blockentity.misc.InscriberBlockEntity}.
 * <p>
 * Slots: 0 top plate, 1 middle input, 2 bottom plate, 3 output. Behaviour:
 * <ul>
 * <li>Internal buffer 1600 AE (filled by cranks); 4 acceleration card slots.</li>
 * <li>While a recipe matches and its output fits, each tick costs {@code 10 · speedFactor} AE (from the buffer if it
 * has enough, otherwise from the grid) and advances progress by {@code speedFactor} (2/3/5/10/50 for 0..4 cards).
 * After progress exceeds 200, the "smash" phase starts: the result is produced on its 8th tick and the phase ends after
 * 16 ticks. I.e. one item takes 101+16 = 117 ticks without cards and 5+16 = 21 ticks with 4 cards (AE2's comments
 * say ~116 / 20).</li>
 * <li>{@link InscriberRecipe.ProcessType#PRESS} consumes the plates, {@code INSCRIBE} keeps them.</li>
 * <li>Changing the item type in an input slot resets the progress (count changes don't).</li>
 * </ul>
 * Register the logic as owner of an {@code inscriber} node ({@code NodeType.INSCRIBER}); it must be a grid node even
 * when only powered by a crank. Call {@link #onInventoryChanged()} whenever the container changes.
 * <p>
 * Deviations: auto-export, "separate sides" exposure and the name press are not ported (automation access rules are
 * available through {@link #canInsert}/{@link #canExtract}).
 */
public final class InscriberLogic implements IGridTickable, IGridNodeListener {
    public static final int SLOT_TOP = 0;
    public static final int SLOT_MIDDLE = 1;
    public static final int SLOT_BOTTOM = 2;
    public static final int SLOT_OUTPUT = 3;
    public static final int MAX_PROCESSING_STEPS = 200;
    public static final double MAX_POWER = 1600;
    public static final String ITEM_ID = "AE2_Inscriber";

    private final SlotAccess inv;
    private final InscriberRecipes recipes;
    private final UpgradeInventory upgrades;
    private final PoweredMachineBuffer buffer = new PoweredMachineBuffer(MAX_POWER);
    private int processingTime = 0;
    private boolean smash;
    private int finalStep;
    private int inputCapacity = 64;
    private InscriberRecipe cachedTask;
    private final SimpleStack[] lastStacks = new SimpleStack[3];
    private GridNode node;
    private Runnable stateListener;
    private boolean lastWorking;

    /**
     * @param inv     4 slots: top, middle, bottom, output
     * @param recipes recipe registry
     */
    public InscriberLogic(SlotAccess inv, InscriberRecipes recipes) {
        if (inv.size() < 4) {
            throw new IllegalArgumentException("Inscriber needs 4 slots");
        }
        this.inv = inv;
        this.recipes = Objects.requireNonNull(recipes);
        this.upgrades = new UpgradeInventory(ITEM_ID, 4, this::onInventoryChanged);
        for (int i = 0; i < 3; i++) {
            lastStacks[i] = inv.get(i);
        }
    }

    /** Called when {@link #isWorking()} or the visual smash state may have changed. */
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public PoweredMachineBuffer getBuffer() {
        return buffer;
    }

    /** Crank target (AE2: any side except the front). */
    public ICrankable getCrankable() {
        return buffer.crankable();
    }

    /** Max items per input slot (AE2 setting INSCRIBER_INPUT_CAPACITY: 1, 4 or 64). Output slot: 64. */
    public int getSlotLimit(int slot) {
        return slot == SLOT_OUTPUT ? 64 : inputCapacity;
    }

    public void setInputCapacity(int capacity) {
        this.inputCapacity = capacity;
    }

    public int getProcessingTime() {
        return processingTime;
    }

    public int getMaxProcessingTime() {
        return MAX_PROCESSING_STEPS;
    }

    public boolean isSmash() {
        return smash;
    }

    /** True while processing or smashing (block state {@code Working}). */
    public boolean isWorking() {
        return smash || processingTime > 0;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    /** Call when the container changed (player or automation). Resets progress if an input item type changed. */
    public void onInventoryChanged() {
        checkInputsChanged();
        if (node != null) {
            node.wake();
        }
    }

    private void checkInputsChanged() {
        for (int i = 0; i < 3; i++) {
            var now = inv.get(i);
            var last = lastStacks[i];
            boolean same = now == null ? last == null : now.isSameItem(last);
            lastStacks[i] = now;
            if (!same) {
                // Reset recipe
                processingTime = 0;
                cachedTask = null;
            }
        }
    }

    /** Current recipe for the slot contents, or null. */
    public InscriberRecipe getTask() {
        if (cachedTask == null) {
            var input = inv.get(SLOT_MIDDLE);
            if (input == null) {
                return null;
            }
            cachedTask = recipes.findRecipe(input, inv.get(SLOT_TOP), inv.get(SLOT_BOTTOM));
        }
        return cachedTask;
    }

    private boolean outputFits(InscriberRecipe task) {
        var out = task.output().toStack();
        return SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, out.key(), out.count(), true) == out.count();
    }

    private boolean hasCraftWork() {
        var task = getTask();
        if (task != null) {
            // Only process if the result would fit.
            return outputFits(task);
        }
        processingTime = 0;
        return smash;
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.Inscriber, !hasCraftWork());
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        checkInputsChanged();
        if (smash) {
            finalStep++;
            if (finalStep == 8) {
                var out = getTask();
                if (out != null) {
                    var output = out.output().toStack();
                    if (outputFits(out)) {
                        SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, output.key(), output.count(), false);
                        processingTime = 0;
                        if (out.mode() == InscriberRecipe.ProcessType.PRESS) {
                            SlotInventories.extractFromSlot(inv, SLOT_TOP, 1, false);
                            SlotInventories.extractFromSlot(inv, SLOT_BOTTOM, 1, false);
                        }
                        SlotInventories.extractFromSlot(inv, SLOT_MIDDLE, out.middle().quantity(), false);
                        // Our own extraction must not count as an input type change
                        for (int i = 0; i < 3; i++) {
                            var now = inv.get(i);
                            if (now == null || !now.isSameItem(lastStacks[i])) {
                                cachedTask = null;
                            }
                            lastStacks[i] = now;
                        }
                    }
                }
            } else if (finalStep == 16) {
                finalStep = 0;
                smash = false;
            }
        } else if (hasCraftWork()) {
            final int speedFactor = switch (upgrades.getInstalledUpgrades(UpgradeCard.SPEED)) {
                case 1 -> 3; // 83 ticks
                case 2 -> 5; // 56 ticks
                case 3 -> 10; // 36 ticks
                case 4 -> 50; // 20 ticks
                default -> 2; // 116 ticks
            };
            final int powerConsumption = 10 * speedFactor;
            final double powerThreshold = powerConsumption - 0.01;
            IEnergySource src = buffer;
            double powerReq = buffer.extractAEPower(powerConsumption, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            if (powerReq <= powerThreshold && node.getGrid() != null) {
                src = node.getGrid().getEnergyService();
                powerReq = src.extractAEPower(powerConsumption, Actionable.SIMULATE, PowerMultiplier.CONFIG);
            }
            if (powerReq > powerThreshold) {
                src.extractAEPower(powerConsumption, Actionable.MODULATE, PowerMultiplier.CONFIG);
                processingTime += speedFactor;
            }

            if (processingTime > MAX_PROCESSING_STEPS) {
                processingTime = MAX_PROCESSING_STEPS;
                var out = getTask();
                if (out != null && outputFits(out)) {
                    smash = true;
                    finalStep = 0;
                }
            }
        }

        fireStateIfChanged();
        return hasCraftWork() ? TickRateModulation.URGENT : TickRateModulation.SLEEP;
    }

    private void fireStateIfChanged() {
        boolean working = isWorking();
        if (working != lastWorking) {
            lastWorking = working;
            if (stateListener != null) {
                stateListener.run();
            }
        }
    }

    /** Base slot filter: may the stack be placed into the slot at all (UI)? */
    public boolean isItemValid(int slot, SimpleStack stack) {
        return recipes.allowInsert(slot, stack, inv.get(SLOT_TOP), inv.get(SLOT_MIDDLE), inv.get(SLOT_BOTTOM));
    }

    /** Automation insert rule ({@code AutomationFilter}): never into the output, not while smashing. */
    public boolean canInsert(int slot, SimpleStack stack) {
        if (slot == SLOT_OUTPUT || smash) {
            return false;
        }
        return isItemValid(slot, stack);
    }

    /** Automation extract rule: only from the output slot. */
    public boolean canExtract(int slot) {
        return slot == SLOT_OUTPUT;
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("progress", processingTime);
        map.put("smash", smash);
        map.put("step", finalStep);
        map.put("power", buffer.getAECurrentPower());
        map.put("capacity", inputCapacity);
        map.put("upgrades", upgrades.toItemIds());
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        upgrades.fromItemIds(MiniJson.getList(map, "upgrades"));
        processingTime = MiniJson.getInt(map, "progress", 0);
        smash = MiniJson.getBool(map, "smash", false);
        finalStep = MiniJson.getInt(map, "step", 0);
        buffer.setAECurrentPower(MiniJson.getDouble(map, "power", 0));
        inputCapacity = MiniJson.getInt(map, "capacity", 64);
        for (int i = 0; i < 3; i++) {
            lastStacks[i] = inv.get(i);
        }
        cachedTask = null;
    }
}
