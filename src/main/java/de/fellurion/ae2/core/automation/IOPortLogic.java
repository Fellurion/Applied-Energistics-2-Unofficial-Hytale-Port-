/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.CellState;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.FullnessMode;
import de.fellurion.ae2.core.config.OperationMode;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.energy.EnergyService;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.StorageHelper;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Logic of the ME IO Port: moves the contents of the storage cells in its six input slots into the network
 * ({@link OperationMode#EMPTY}) or fills them from the network ({@link OperationMode#FILL}) and puts a finished cell
 * into one of its six output slots. Port of {@code appeng.blockentity.storage.IOPortBlockEntity}.
 * <ul>
 * <li>{@value #OPERATIONS_PER_TICK} operations per tick, doubled by each acceleration (speed) card: 256 / 512 / 1024 /
 * 2048 with 0..3 cards. One operation is one item or 125 mB of fluid. The budget is shared by all six cells, slot 0
 * first.</li>
 * <li>Every transferred operation costs 1 AE ({@link StorageHelper#poweredInsert}); the block itself idles at 1 AE/t
 * and needs a channel ({@code NodeType.IO_PORT}).</li>
 * <li>{@link FullnessMode}: a cell moves to the output once nothing more can be transferred within the tick's budget
 * and it is empty ({@code EMPTY}), full ({@code FULL}), or in any state ({@code HALF}, "work is done").</li>
 * <li>Ticking: {@link TickRates#IOPort} (1..5 ticks). {@code URGENT} while the budget is used up or a cell was moved,
 * {@code IDLE} while a cell waits, {@code SLEEP} without cells. The port sleeps while it has no input cell or is
 * switched off by the redstone card and is woken by {@link #onInventoryChanged()}, a setting or a signal change.</li>
 * <li>Upgrades: 3 slots, up to 3 acceleration cards and 1 redstone card ({@code Upgrades}).</li>
 * </ul>
 * Usage: implement {@link CellSlots} on the block's item slots, pass the logic as owner to
 * {@code MEWorld.addNode(pos, NodeType.IO_PORT, null, null, logic)}, call {@link #onInventoryChanged()} whenever an
 * input slot changes and {@link #setRedstonePowered(boolean)} with the block's signal state.
 * <p>
 * Deviations from AE2:
 * <ul>
 * <li>AE2 re-creates the cell inventory from the item stack on every tick and the inventory writes through to the
 * stack. Here the host keeps the cell inventories ({@link CellSlots#getCell}) and is told when a cell has to be
 * written back into its item ({@link CellSlots#cellChanged}).</li>
 * <li>Nothing leaves its source before it is certain to arrive: the energy for an operation is checked before the
 * extraction, and when a cell is emptied the items are taken out of the cell only after the network has accepted
 * them. AE2 extracts first and puts back what could not be inserted, which loses items if the source refuses to take
 * them back (cell partitioned afterwards, extract-only storage bus). The amounts and the energy are the same.</li>
 * <li>If a key cannot be inserted although the simulation accepted it, the next key is tried (AE2 ends the tick's
 * work there).</li>
 * <li>Redstone card: {@link RedstoneMode#IGNORE} means "always active" as its button says. AE2 treats every mode
 * but {@code HIGH_SIGNAL} as "active without signal" once the card is installed; that is kept for
 * {@code LOW_SIGNAL} and {@code SIGNAL_PULSE}. The signal state is reported from outside instead of being read from
 * the level.</li>
 * <li>Installing or removing a card re-evaluates sleeping at once, and a tick while switched off does nothing (in AE2
 * a running port notices a new redstone card only at the next inventory, setting or signal change).</li>
 * <li>Which block sides expose which slots to automation is up to the integration layer.</li>
 * </ul>
 */
public class IOPortLogic implements IGridTickable, IGridNodeListener, IActionHost {
    /** Item id of the block; decides which upgrade cards fit ({@code Upgrades}). */
    public static final String HOST_ITEM_ID = "AE2_IO_Port";
    /** Input slots, and as many output slots. */
    public static final int NUMBER_OF_CELL_SLOTS = 6;
    public static final int NUMBER_OF_UPGRADE_SLOTS = 3;
    /** Operations per tick without acceleration cards. */
    public static final long OPERATIONS_PER_TICK = 256;

    /**
     * The item slots of the port as the logic needs them. Implemented by the integration layer on the block's
     * container (slot numbers are input slots {@code 0..5}).
     */
    public interface CellSlots {
        /** @return true if the input slot holds no item at all */
        boolean isEmpty(int slot);

        /**
         * @return the cell inventory of the item in the input slot, or null if the slot is empty or the item is no
         *         storage cell. The same instance must be returned as long as the item stays in the slot.
         */
        StorageCell getCell(int slot);

        /**
         * The port has moved contents into or out of the cell of this input slot during the current tick: persist the
         * cell ({@link StorageCell#persist()}) and write its data into the item now, so that the item is up to date
         * whenever it can leave the slot.
         */
        void cellChanged(int slot);

        /**
         * Moves the item of the input slot into a free output slot (the cell has to be persisted into the item
         * first).
         *
         * @return false if no output slot is free; the item then stays where it is
         */
        boolean moveToOutput(int slot);
    }

    private final CellSlots slots;
    private final UpgradeInventory upgrades = new UpgradeInventory(HOST_ITEM_ID, NUMBER_OF_UPGRADE_SLOTS);
    private final IActionSource source = IActionSource.ofMachine(this);
    private OperationMode operationMode = OperationMode.EMPTY;
    private FullnessMode fullnessMode = FullnessMode.EMPTY;
    private RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private boolean redstonePowered;
    private GridNode node;
    private Runnable saveListener;
    private boolean loading;

    public IOPortLogic(CellSlots slots) {
        this.slots = Objects.requireNonNull(slots, "slots");
        this.upgrades.setChangeListener(this::onSettingChanged);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Configuration

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    /** Called whenever persistent state changed (settings, upgrades). */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    public OperationMode getOperationMode() {
        return operationMode;
    }

    public void setOperationMode(OperationMode mode) {
        this.operationMode = Objects.requireNonNull(mode);
        onSettingChanged();
    }

    public FullnessMode getFullnessMode() {
        return fullnessMode;
    }

    public void setFullnessMode(FullnessMode mode) {
        this.fullnessMode = Objects.requireNonNull(mode);
        onSettingChanged();
    }

    /** Only has an effect while a redstone card is installed. */
    public RedstoneMode getRedstoneMode() {
        return redstoneMode;
    }

    public void setRedstoneMode(RedstoneMode mode) {
        this.redstoneMode = Objects.requireNonNull(mode);
        onSettingChanged();
    }

    /** Port of the config manager's change listener ({@code updateTask}); upgrades are handled the same way. */
    private void onSettingChanged() {
        if (loading) {
            return;
        }
        updateTask();
        if (saveListener != null) {
            saveListener.run();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Redstone

    /** Reports the signal state of the block (port of {@code updateRedstoneState}). */
    public void setRedstonePowered(boolean powered) {
        if (this.redstonePowered != powered) {
            this.redstonePowered = powered;
            updateTask();
        }
    }

    public boolean isRedstonePowered() {
        return redstonePowered;
    }

    /** False while the redstone card switches the port off. */
    public boolean isEnabled() {
        if (!upgrades.isInstalled(UpgradeCard.REDSTONE)) {
            return true;
        }
        return switch (redstoneMode) {
            case IGNORE -> true;
            case HIGH_SIGNAL -> redstonePowered;
            case LOW_SIGNAL, SIGNAL_PULSE -> !redstonePowered;
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // Work

    /** Call when the content of an input slot changed (port of {@code onChangeInventory}). */
    public void onInventoryChanged() {
        updateTask();
    }

    /** Enabled and at least one input slot is occupied. */
    public boolean hasWork() {
        if (!isEnabled()) {
            return false;
        }
        for (int x = 0; x < NUMBER_OF_CELL_SLOTS; x++) {
            if (!slots.isEmpty(x)) {
                return true;
            }
        }
        return false;
    }

    private void updateTask() {
        if (node == null) {
            return;
        }
        if (hasWork()) {
            node.wake();
        } else {
            node.sleep();
        }
    }

    /** Operations per tick: 256, doubled by each acceleration card (up to 3). */
    public long getOperationsPerTick() {
        return switch (upgrades.getInstalledUpgrades(UpgradeCard.SPEED)) {
            case 1 -> OPERATIONS_PER_TICK * 2;
            case 2 -> OPERATIONS_PER_TICK * 4;
            case 3 -> OPERATIONS_PER_TICK * 8;
            default -> OPERATIONS_PER_TICK;
        };
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.IOPort, !hasWork());
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (!node.isActive()) {
            return TickRateModulation.IDLE;
        }
        if (!isEnabled()) {
            return TickRateModulation.SLEEP;
        }
        var grid = node.getGrid();
        if (grid == null) {
            return TickRateModulation.IDLE;
        }

        var ret = TickRateModulation.SLEEP;
        long itemsToMove = getOperationsPerTick();

        for (int x = 0; x < NUMBER_OF_CELL_SLOTS; x++) {
            var cellInv = slots.getCell(x);
            if (cellInv == null) {
                // This item is not a valid storage cell, try to move it to the output
                if (!slots.isEmpty(x)) {
                    slots.moveToOutput(x);
                }
                continue;
            }

            if (itemsToMove > 0) {
                long before = itemsToMove;
                itemsToMove = transferContents(grid, cellInv, itemsToMove);
                if (itemsToMove != before) {
                    slots.cellChanged(x);
                }
                if (itemsToMove > 0) {
                    ret = TickRateModulation.IDLE;
                } else {
                    ret = TickRateModulation.URGENT;
                }
            }

            if (itemsToMove > 0 && matchesFullnessMode(cellInv) && slots.moveToOutput(x)) {
                ret = TickRateModulation.URGENT;
            }
        }

        return ret;
    }

    /** Work is complete when the inventory has reached the desired end-state. */
    public boolean matchesFullnessMode(StorageCell inv) {
        return switch (fullnessMode) {
            // In this mode, work completes as soon as no more items are moved within one operation,
            // independent of the actual inventory state
            case HALF -> true;
            case EMPTY -> inv.getStatus() == CellState.EMPTY;
            case FULL -> inv.getStatus() == CellState.FULL;
        };
    }

    /**
     * Moves up to {@code itemsToMove} operations between the cell and the network.
     *
     * @return the operations left over
     */
    private long transferContents(Grid grid, StorageCell cellInv, long itemsToMove) {
        var networkInv = grid.getStorageService().getInventory();
        boolean emptying = operationMode == OperationMode.EMPTY;

        KeyCounter srcList;
        MEStorage destination;
        if (emptying) {
            srcList = cellInv.getAvailableStacks();
            destination = networkInv;
        } else {
            srcList = grid.getStorageService().getCachedInventory();
            destination = cellInv;
        }

        var energy = grid.getEnergyService();
        boolean didStuff;

        do {
            didStuff = false;

            for (var srcEntry : srcList) {
                var totalStackSize = srcEntry.getLongValue();
                if (totalStackSize > 0) {
                    var what = srcEntry.getKey();
                    var possible = destination.insert(what, totalStackSize, Actionable.SIMULATE, this.source);

                    if (possible > 0) {
                        possible = Math.min(possible, itemsToMove * what.getAmountPerOperation());

                        var inserted = emptying ? moveToNetwork(energy, cellInv, networkInv, what, possible)
                                : moveToCell(energy, networkInv, cellInv, what, possible);
                        if (inserted > 0) {
                            itemsToMove -= Math.max(1, inserted / what.getAmountPerOperation());
                            didStuff = true;
                            break;
                        }
                    }
                }
            }
        } while (itemsToMove > 0 && didStuff);

        return itemsToMove;
    }

    /**
     * Cell → network. The cell hands out exactly what it reports, so the items leave it only after the network has
     * taken (and the energy service has paid for) them.
     */
    private long moveToNetwork(EnergyService energy, StorageCell cell, MEStorage network, AEKey what, long amount) {
        long available = cell.extract(what, amount, Actionable.SIMULATE, this.source);
        if (available <= 0) {
            return 0;
        }
        long inserted = StorageHelper.poweredInsert(energy, network, what, available, this.source);
        if (inserted <= 0) {
            return 0;
        }
        long removed = cell.extract(what, inserted, Actionable.MODULATE, this.source);
        if (removed < inserted) {
            // Cannot happen with a cell that reports its content correctly; never leave a copy behind
            network.extract(what, inserted - removed, Actionable.MODULATE, this.source);
            return removed;
        }
        return inserted;
    }

    /**
     * Network → cell. Only what the cell accepts and the network can pay for is taken out of the network.
     */
    private long moveToCell(EnergyService energy, MEStorage network, StorageCell cell, AEKey what, long amount) {
        amount = StorageHelper.poweredInsert(energy, cell, what, amount, this.source, Actionable.SIMULATE);
        if (amount <= 0) {
            return 0;
        }
        long extracted = network.extract(what, amount, Actionable.MODULATE, this.source);
        if (extracted <= 0) {
            return 0;
        }
        long inserted = StorageHelper.poweredInsert(energy, cell, what, extracted, this.source);
        if (inserted < extracted) {
            // As in AE2: back to where it came from; what the network refuses goes into the cell without payment
            long rest = extracted - inserted;
            rest -= network.insert(what, rest, Actionable.MODULATE, this.source);
            if (rest > 0) {
                inserted += cell.insert(what, rest, Actionable.MODULATE, this.source);
            }
        }
        return inserted;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Node

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence

    /** Saves settings, upgrades and the last signal state as JSON. The cells are saved with their items. */
    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("op", operationMode.name());
        map.put("full", fullnessMode.name());
        map.put("rs", redstoneMode.name());
        map.put("up", upgrades.toItemIds());
        if (redstonePowered) {
            map.put("pw", true);
        }
        return MiniJson.write(map);
    }

    /** Loads state written by {@link #saveState()}. */
    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        loading = true;
        try {
            operationMode = MiniJson.getEnum(map, "op", OperationMode.class, OperationMode.EMPTY);
            fullnessMode = MiniJson.getEnum(map, "full", FullnessMode.class, FullnessMode.EMPTY);
            redstoneMode = MiniJson.getEnum(map, "rs", RedstoneMode.class, RedstoneMode.IGNORE);
            upgrades.fromItemIds(MiniJson.getList(map, "up"));
            redstonePowered = MiniJson.getBool(map, "pw", false);
        } finally {
            loading = false;
        }
        updateTask();
    }
}
