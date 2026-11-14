/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.devices;

import de.fellurion.ae2.core.cells.CellState;
import de.fellurion.ae2.core.cells.DriveWatcher;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.storage.IStorageMounts;
import de.fellurion.ae2.core.storage.IStorageProvider;

import java.util.function.IntConsumer;

/**
 * Logic of an ME Drive (10 cell slots) or ME Chest (1 cell slot): mounts the inserted cells into the network and
 * adjusts the node's idle power. Port of {@code appeng.blockentity.storage.DriveBlockEntity} (storage part) and the
 * cell handling of {@code MEChestBlockEntity}.
 * <ul>
 * <li>Drive idle power: 2 AE/t + sum of the cells' idle drain. ME chest: 1 AE/t + cell drain.</li>
 * <li>Cells are mounted with the host's priority while the node is online.</li>
 * <li>{@link #getCellStatus(int)} gives the LED state; the activity listener fires when a cell's status changes.</li>
 * </ul>
 * Usage: create the logic, pass it as owner to {@code MEWorld.addNode}, and call {@link #setCell} whenever the
 * container slot changes (create cells with {@code StorageCells.getCellInventory}).
 */
public class CellHostLogic implements IStorageProvider, IGridNodeListener, IActionHost {
    private final StorageCell[] cells;
    private final DriveWatcher[] watchers;
    private final double baseIdlePower;
    private int priority;
    private GridNode node;
    private IntConsumer cellStatusListener;

    public CellHostLogic(int slots, double baseIdlePower) {
        this.cells = new StorageCell[slots];
        this.watchers = new DriveWatcher[slots];
        this.baseIdlePower = baseIdlePower;
    }

    /** ME Drive: 10 slots, 2 AE/t base. */
    public static CellHostLogic drive() {
        return new CellHostLogic(10, 2.0);
    }

    /** ME Chest: 1 slot, 1 AE/t base. */
    public static CellHostLogic meChest() {
        return new CellHostLogic(1, 1.0);
    }

    /** Called with the slot index whenever a cell's LED status changes. */
    public void setCellStatusListener(IntConsumer listener) {
        this.cellStatusListener = listener;
    }

    public int getSlots() {
        return cells.length;
    }

    public StorageCell getCell(int slot) {
        return cells[slot];
    }

    /** Sets or clears (null) the cell in a slot and remounts. Persist the old cell before removing it! */
    public void setCell(int slot, StorageCell cell) {
        cells[slot] = cell;
        final int s = slot;
        watchers[slot] = cell == null ? null : new DriveWatcher(cell, () -> {
            if (cellStatusListener != null) {
                cellStatusListener.accept(s);
            }
        });
        updateIdlePower();
        IStorageProvider.requestUpdate(node);
        if (cellStatusListener != null) {
            cellStatusListener.accept(slot);
        }
    }

    /** LED state of a slot ({@link CellState#ABSENT} if empty). */
    public CellState getCellStatus(int slot) {
        var w = watchers[slot];
        return w == null ? CellState.ABSENT : w.getStatus();
    }

    /** Persists all cells into their {@code CellItemData} (call before saving the block). */
    public void persistAll() {
        for (var c : cells) {
            if (c != null) {
                c.persist();
            }
        }
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        IStorageProvider.requestUpdate(node);
    }

    public double getIdlePowerUsage() {
        double power = baseIdlePower;
        for (var c : cells) {
            if (c != null) {
                power += c.getIdleDrain();
            }
        }
        return power;
    }

    private void updateIdlePower() {
        if (node != null) {
            node.setIdlePowerUsage(getIdlePowerUsage());
        }
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        for (var w : watchers) {
            if (w != null) {
                mounts.mount(w, priority);
            }
        }
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        updateIdlePower();
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        persistAll();
        this.node = null;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }
}
