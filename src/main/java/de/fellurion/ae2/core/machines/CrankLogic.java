/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;

/**
 * Manual crank. Port of {@code appeng.blockentity.misc.CrankBlockEntity}.
 * <p>
 * A player use calls {@link #power(ICrankable)}: if the crank is (almost) idle and the target can turn, a rotation of
 * 18 ticks is queued. {@link #tick(ICrankable)} must be called every game tick; after each 18 ticks of rotation
 * {@link ICrankable#applyTurn()} is called (160 AE for powered machines). If the target cannot turn more than 10
 * times in a row, {@link #shouldBreak()} becomes true (AE2 destroys the crank block).
 * <p>
 * Deviation: the crankable target is passed per call (the integration looks up the block below/next to the crank);
 * AE2 resolves it from the block state.
 */
public final class CrankLogic {
    public static final int POWER_PER_CRANK_TURN = 160;
    public static final int TICKS_PER_ROTATION = 18;

    private float visibleRotation = 0;
    private int charge = 0;
    private int hits = 0;
    private int rotation = 0;
    private boolean shouldBreak;

    /**
     * Player turns the crank.
     *
     * @param target the machine the crank is attached to (may be null)
     * @return true if this counted as a turn (AE2: counts towards stats)
     */
    public boolean power(ICrankable target) {
        if (this.rotation < 3) {
            if (target != null) {
                if (target.canTurn()) {
                    this.hits = 0;
                    this.rotation += TICKS_PER_ROTATION;
                    return true;
                } else {
                    this.hits++;
                    if (this.hits > 10) {
                        shouldBreak = true;
                    }
                }
            }
        }
        return false;
    }

    /** Called every game tick. */
    public void tick(ICrankable target) {
        if (this.rotation > 0) {
            this.visibleRotation -= 360.0f / TICKS_PER_ROTATION;
            this.charge++;
            if (this.charge >= TICKS_PER_ROTATION) {
                this.charge -= TICKS_PER_ROTATION;
                if (target != null) {
                    target.applyTurn();
                }
            }
            this.rotation--;
        }
    }

    public boolean isTurning() {
        return rotation > 0;
    }

    /** Remaining rotation ticks. */
    public int getRotation() {
        return rotation;
    }

    /** Rotation in degrees for visuals. */
    public float getVisibleRotation() {
        return visibleRotation;
    }

    /** True after the crank was forced more than 10 times against a full machine: break the block. */
    public boolean shouldBreak() {
        return shouldBreak;
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("rotation", rotation);
        map.put("charge", charge);
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        rotation = MiniJson.getInt(map, "rotation", 0);
        charge = MiniJson.getInt(map, "charge", 0);
    }
}
