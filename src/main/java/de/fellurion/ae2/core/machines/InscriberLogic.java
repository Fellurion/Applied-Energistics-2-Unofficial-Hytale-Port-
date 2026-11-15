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
import java.util.List;
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
 * <li>Settings as in AE2: {@linkplain #setSeparateSides separate sides} (which slots automation reaches from which
 * side, {@link #getExposedInventory}), {@linkplain #setAutoExport auto export} (the output is pushed into adjacent
 * inventories) and the {@linkplain #setInputCapacity input capacity} (1, 4 or 64 items per input slot).</li>
 * <li>Name press: with an {@code AE2_Name_Press} as plate (top and/or bottom, no other plate) the middle item is
 * renamed instead of processed by a recipe; the presses are kept ({@link InscriberRecipes#findNamePressRecipe},
 * {@link InscriberRecipes#namePressResult}).</li>
 * </ul>
 * Register the logic as owner of an {@code inscriber} node ({@code NodeType.INSCRIBER}); it must be a grid node even
 * when only powered by a crank. Call {@link #onInventoryChanged()} whenever the container changes.
 * <p>
 * Deviations: item names are not known to the core - the name of a press and the renamed item come from the
 * integration's {@link InscriberRecipes.NameHandler}; without one the name press does nothing. The adjacent
 * inventories for auto export are supplied by the integration ({@link #setExportTargets}); the input capacity limits
 * what automation inserts through {@link #getExposedInventory} (a slot window of the integration may ignore it).
 */
public final class InscriberLogic implements IGridTickable, IGridNodeListener {
    public static final int SLOT_TOP = 0;
    public static final int SLOT_MIDDLE = 1;
    public static final int SLOT_BOTTOM = 2;
    public static final int SLOT_OUTPUT = 3;
    public static final int MAX_PROCESSING_STEPS = 200;
    public static final double MAX_POWER = 1600;
    public static final String ITEM_ID = "AE2_Inscriber";
    /** AE2 {@code InscriberInputCapacity}: ONE, FOUR, SIXTY_FOUR. */
    public static final int[] INPUT_CAPACITIES = {1, 4, 64};

    /** The side of the inscriber automation accesses it from (relative to the block's orientation). */
    public enum Side {
        TOP, BOTTOM, SIDE
    }

    /** Supplies the inventories next to the inscriber for auto export. */
    @FunctionalInterface
    public interface ExportTargets {
        /**
         * @param sidesOnly true in separate-sides mode: only the four sides, not the inventories above and below
         */
        List<SlotAccess> get(boolean sidesOnly);
    }

    private final SlotAccess inv;
    private final InscriberRecipes recipes;
    private final UpgradeInventory upgrades;
    private final PoweredMachineBuffer buffer = new PoweredMachineBuffer(MAX_POWER);
    private int processingTime = 0;
    private boolean smash;
    private int finalStep;
    private int inputCapacity = 64;
    private boolean separateSides;
    private boolean autoExport;
    private ExportTargets exportTargets = sidesOnly -> List.of();
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
        this.inputCapacity = Math.max(1, capacity);
    }

    public int getInputCapacity() {
        return inputCapacity;
    }

    /** AE2 setting {@code INSCRIBER_SEPARATE_SIDES}: automation reaches the plates only from above and below. */
    public boolean isSeparateSides() {
        return separateSides;
    }

    public void setSeparateSides(boolean separateSides) {
        this.separateSides = separateSides;
    }

    /** AE2 setting {@code AUTO_EXPORT}: push the output into adjacent inventories. */
    public boolean isAutoExport() {
        return autoExport;
    }

    public void setAutoExport(boolean autoExport) {
        this.autoExport = autoExport;
        if (node != null) {
            node.wake();
        }
    }

    public void setExportTargets(ExportTargets targets) {
        this.exportTargets = targets == null ? sidesOnly -> List.of() : targets;
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
        var out = resultOf(task);
        return out != null
                && SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, out.key(), out.count(), true) == out.count();
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

    private boolean hasAutoExportWork() {
        return autoExport && inv.get(SLOT_OUTPUT) != null;
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.Inscriber, !hasAutoExportWork() && !hasCraftWork());
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        checkInputsChanged();
        if (smash) {
            finalStep++;
            if (finalStep == 8) {
                var out = getTask();
                if (out != null) {
                    var output = resultOf(out);
                    if (output != null && outputFits(out)) {
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
        if (pushOutResult()) {
            return TickRateModulation.URGENT;
        }
        return hasCraftWork() ? TickRateModulation.URGENT
                : hasAutoExportWork() ? TickRateModulation.SLOWER : TickRateModulation.SLEEP;
    }

    /**
     * Auto export: moves the output (up to 64 items) into the first adjacent inventory that takes any of it.
     *
     * @return true if something was pushed, false otherwise
     */
    private boolean pushOutResult() {
        if (!hasAutoExportWork()) {
            return false;
        }
        for (var target : exportTargets.get(separateSides)) {
            var out = inv.get(SLOT_OUTPUT);
            if (out == null) {
                return false;
            }
            if (target == null) {
                continue;
            }
            int fits = (int) SlotInventories.insert(target, out.key(), Math.min(64, out.count()), true);
            if (fits <= 0) {
                continue;
            }
            // take first, then insert: every item is in exactly one place; what the target refuses after all goes back
            int taken = SlotInventories.extractFromSlot(inv, SLOT_OUTPUT, fits, false);
            int pushed = (int) SlotInventories.insert(target, out.key(), taken, false);
            if (pushed < taken) {
                SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, out.key(), taken - pushed, false);
            }
            if (pushed > 0) {
                return true;
            }
        }
        return false;
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

    /**
     * Automation extract rule ({@code AutomationFilter}): always from the output slot; from the plate slots only in
     * separate-sides mode and not while smashing; never from the middle input.
     */
    public boolean canExtract(int slot) {
        if (slot == SLOT_OUTPUT) {
            return true; // Can always extract from output slot
        }
        if (smash) {
            return false;
        }
        // Can only extract from top and bottom in separated sides mode
        return separateSides && (slot == SLOT_TOP || slot == SLOT_BOTTOM);
    }

    /**
     * Which slots automation sees from a side ({@code getExposedInventoryForSide}): all four unless the sides are
     * separated – then the top plate from above, the bottom plate from below, input and output from the sides.
     */
    public boolean isExposed(Side side, int slot) {
        if (!separateSides) {
            return slot >= SLOT_TOP && slot <= SLOT_OUTPUT;
        }
        return switch (side) {
            case TOP -> slot == SLOT_TOP;
            case BOTTOM -> slot == SLOT_BOTTOM;
            case SIDE -> slot == SLOT_MIDDLE || slot == SLOT_OUTPUT;
        };
    }

    /** Extraction rule for automation at a side: {@link #isExposed} and {@link #canExtract(int)}. */
    public boolean canExtract(Side side, int slot) {
        return isExposed(side, slot) && canExtract(slot);
    }

    /**
     * The inventory automation (buses, pattern providers) sees from a side: the exposed slots with the insert rule
     * {@link #canInsert} and the input capacity as slot limit. Slots that are not exposed look empty and accept
     * nothing. Extraction additionally needs {@link #canExtract(Side, int)}. The view follows setting changes.
     */
    public SlotAccess getExposedInventory(Side side) {
        Objects.requireNonNull(side);
        return new SlotAccess() {
            @Override
            public int size() {
                return SLOT_OUTPUT + 1;
            }

            @Override
            public SimpleStack get(int slot) {
                return isExposed(side, slot) ? inv.get(slot) : null;
            }

            @Override
            public void set(int slot, SimpleStack stack) {
                if (isExposed(side, slot)) {
                    inv.set(slot, stack);
                }
            }

            @Override
            public int getMaxStackSize(int slot, String itemId) {
                // 0 also keeps automation from stacking onto the output or onto anything while smashing
                if (!isExposed(side, slot) || slot == SLOT_OUTPUT || smash) {
                    return 0;
                }
                return Math.min(inv.getMaxStackSize(slot, itemId), getSlotLimit(slot));
            }

            @Override
            public boolean isItemValid(int slot, SimpleStack stack) {
                return isExposed(side, slot) && canInsert(slot, stack) && inv.isItemValid(slot, stack);
            }
        };
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("progress", processingTime);
        map.put("smash", smash);
        map.put("step", finalStep);
        map.put("power", buffer.getAECurrentPower());
        map.put("capacity", inputCapacity);
        map.put("separateSides", separateSides);
        map.put("autoExport", autoExport);
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
        inputCapacity = Math.max(1, MiniJson.getInt(map, "capacity", 64));
        separateSides = MiniJson.getBool(map, "separateSides", false);
        autoExport = MiniJson.getBool(map, "autoExport", false);
        for (int i = 0; i < 3; i++) {
            lastStacks[i] = inv.get(i);
        }
        cachedTask = null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Name press (AE2: InscriberRecipes.makeNamePressRecipe)

    /** The name press job {@link #namedResult} was computed for. */
    private InscriberRecipe namedTask;
    private SimpleStack namedResult;

    /**
     * The stack a task produces. A recipe produces its fixed output. The name press job
     * ({@link InscriberRecipes#findNamePressRecipe}) produces the middle item itself, with all its metadata, under
     * the name of the press(es); it is computed once per job, which is replaced whenever an input item changes.
     *
     * @return the result, or null if a name press job has nothing to rename (never a stripped copy of the input)
     */
    private SimpleStack resultOf(InscriberRecipe task) {
        if (!InscriberRecipes.isNamePressRecipe(task)) {
            return task.output().toStack();
        }
        if (task != namedTask) {
            namedResult = InscriberRecipes.namePressResult(inv.get(SLOT_MIDDLE), inv.get(SLOT_TOP),
                    inv.get(SLOT_BOTTOM));
            namedTask = task;
        }
        return namedResult;
    }
}
