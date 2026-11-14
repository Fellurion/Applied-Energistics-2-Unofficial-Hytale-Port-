/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.cells;

/**
 * Status of a storage cell, used for drive/chest LEDs. Port of {@code appeng.api.storage.cells.CellState}.
 */
public enum CellState {
    /** No cell at all. */
    ABSENT(0),
    /** A cell without anything stored (green). */
    EMPTY(0x00FF00),
    /** Stored something, but neither types nor totally full (blue). */
    NOT_EMPTY(0x00AAFF),
    /** Available types exhausted (orange). */
    TYPES_FULL(0xFFAA00),
    /** Full cell (red). */
    FULL(0xFF0000);

    private final int stateColor;

    CellState(int stateColor) {
        this.stateColor = stateColor;
    }

    /** RGB color for LEDs. */
    public int getStateColor() {
        return stateColor;
    }
}
