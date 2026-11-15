/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One plot of spatial storage: the place the content of one spatial storage cell lives in. Port of
 * {@code appeng.spatial.SpatialStoragePlot}.
 * <p>
 * The size is dictated by the pylon structure used for the first transfer into the plot; once set it cannot change.
 * <p>
 * Deviations: AE2 places every plot at a computed origin inside its spatial storage dimension
 * ({@code getOrigin()}, {@code getRegionFilename()}); this port has no such dimension - the integration layer keeps
 * the blocks of a plot in a file named after the plot id, so those methods are not ported. The owner is the id of
 * the player owning the network (a string, AE2: its numeric player id), or null if unknown.
 */
public final class SpatialStoragePlot {
    /** Largest plot edge (AE2: {@code MAX_SIZE}). */
    public static final int MAX_SIZE = 128;

    private final int id;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String owner;
    private TransitionInfo lastTransition;

    public SpatialStoragePlot(int id, int sizeX, int sizeY, int sizeZ, String owner) {
        this(id, sizeX, sizeY, sizeZ, owner, null);
    }

    public SpatialStoragePlot(int id, int sizeX, int sizeY, int sizeZ, String owner, TransitionInfo lastTransition) {
        this.id = id;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.owner = owner;
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException(
                    "Plot size " + sizeX + "x" + sizeY + "x" + sizeZ + " is smaller than minimum size.");
        }
        if (sizeX > MAX_SIZE || sizeY > MAX_SIZE || sizeZ > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "Plot size " + sizeX + "x" + sizeY + "x" + sizeZ + " exceeds maximum size of " + MAX_SIZE);
        }
        this.lastTransition = lastTransition;
    }

    public int getId() {
        return id;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public boolean hasSize(int x, int y, int z) {
        return sizeX == x && sizeY == y && sizeZ == z;
    }

    /** Owner of the network that allocated the plot, or null if unknown. */
    public String getOwner() {
        return owner;
    }

    /** Information about the last transition into this plot (if any). */
    public TransitionInfo getLastTransition() {
        return lastTransition;
    }

    void setLastTransition(TransitionInfo info) {
        this.lastTransition = info;
    }

    public SpatialPlotInfo toPlotInfo() {
        return new SpatialPlotInfo(id, sizeX, sizeY, sizeZ);
    }

    /** The plot as JSON (id, size, owner, last transition). */
    public String toJson() {
        return MiniJson.write(toMap());
    }

    /** @return the plot described by {@code json}, or null if it is not a valid plot */
    public static SpatialStoragePlot fromJson(String json) {
        try {
            return fromMap(MiniJson.parseObject(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("size", java.util.List.of(sizeX, sizeY, sizeZ));
        if (owner != null) {
            m.put("owner", owner);
        }
        if (lastTransition != null) {
            m.put("last_transition", lastTransition.toMap());
        }
        return m;
    }

    static SpatialStoragePlot fromMap(Map<?, ?> m) {
        var size = MiniJson.getList(m, "size");
        int id = MiniJson.getInt(m, "id", -1);
        if (id < 0 || size.size() != 3) {
            return null;
        }
        TransitionInfo last = m.get("last_transition") instanceof Map<?, ?> t ? TransitionInfo.fromMap(t) : null;
        try {
            return new SpatialStoragePlot(id, num(size.get(0)), num(size.get(1)), num(size.get(2)),
                    MiniJson.getString(m, "owner", null), last);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
