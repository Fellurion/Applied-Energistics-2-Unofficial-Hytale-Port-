/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The list of all spatial storage plots. Port of {@code appeng.spatial.SpatialStoragePlotManager} together with its
 * saved data {@code appeng.spatial.SpatialStorageWorldData}.
 * <p>
 * Plot ids start at 1 and count up.
 * <p>
 * Deviations: AE2 keeps the list as saved data of the spatial storage dimension and {@code freePlot} may reset the
 * plot's blocks to matrix frames; here the list is plain data ({@link #toJson()} / {@link #load(String)}) that the
 * integration layer stores next to the plot files, and deleting the blocks of a freed plot (its file) is the
 * integration layer's job. {@link #setSaveListener(Runnable)} replaces {@code setDirty()}. AE2 gives a new plot the
 * highest existing id plus one, so the id of a freed plot can come back; here an id is never used twice (the highest
 * id ever handed out is saved), so a cell that still carries the id of a freed plot can never reach the content of
 * a newer one. {@link #restorePlot} and {@link #reserveIdsUpTo} have no AE2 counterpart: they keep the list and the
 * plot files of the integration layer consistent after a lost or damaged list. All methods are synchronized:
 * unlike the rest of the core the list is shared by all worlds of a server, each of which has its own tick thread.
 */
public final class SpatialStoragePlotManager {
    private final TreeMap<Integer, SpatialStoragePlot> plots = new TreeMap<>();
    private Runnable saveListener;
    private int highestKnownId;

    /** Called after every change (allocate, free, last transition). */
    public synchronized void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    /** @return the plot, or null if {@code plotId} is -1 or unknown */
    public synchronized SpatialStoragePlot getPlot(int plotId) {
        if (plotId == -1) {
            return null;
        }
        return plots.get(plotId);
    }

    /**
     * The plot a cell with this plot info is bound to (AE2 {@code SpatialStorageCellItem.getAllocatedPlotId}): null if
     * the cell is unformatted or its plot no longer exists.
     */
    public SpatialStoragePlot getPlot(SpatialPlotInfo info) {
        return info == null ? null : getPlot(info.id());
    }

    public synchronized SpatialStoragePlot allocatePlot(int sizeX, int sizeY, int sizeZ, String owner) {
        int nextId = Math.max(1, highestKnownId + 1);
        for (int id : plots.keySet()) {
            if (id >= nextId) {
                nextId = id + 1;
            }
        }
        SpatialStoragePlot plot = new SpatialStoragePlot(nextId, sizeX, sizeY, sizeZ, owner);
        plots.put(nextId, plot);
        highestKnownId = nextId;
        setDirty();
        return plot;
    }

    /**
     * Adds a plot that exists outside of the list (its content was found on disk). A known id is left alone.
     *
     * @return true if the plot was added
     */
    public synchronized boolean restorePlot(SpatialStoragePlot plot) {
        if (plots.putIfAbsent(plot.getId(), plot) != null) {
            return false;
        }
        highestKnownId = Math.max(highestKnownId, plot.getId());
        setDirty();
        return true;
    }

    /**
     * Never hand out this id or a lower one again, even if no such plot is listed (content with that id exists on
     * disk and must not be taken over by a new plot).
     */
    public synchronized void reserveIdsUpTo(int id) {
        this.highestKnownId = Math.max(this.highestKnownId, id);
    }

    /**
     * Sets the last source for a spatial storage transition into the given plot. This is used to allow the
     * server-admin commands to know where the content of a given plot came from.
     */
    public synchronized void setLastTransition(int plotId, TransitionInfo info) {
        SpatialStoragePlot plot = plots.get(plotId);
        if (plot != null) {
            plot.setLastTransition(info);
        }
        setDirty();
    }

    /** Returns an immutable list of all plots, ordered by id. */
    public synchronized List<SpatialStoragePlot> getPlots() {
        return List.copyOf(plots.values());
    }

    /** Removes the plot from the list. No-op if it is already gone. */
    public synchronized void freePlot(int plotId) {
        if (plots.remove(plotId) != null) {
            setDirty();
        }
    }

    private void setDirty() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    public synchronized String toJson() {
        List<Object> list = new ArrayList<>();
        for (SpatialStoragePlot plot : plots.values()) {
            list.add(plot.toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("highest_id", highestKnownId);
        root.put("plots", list);
        return MiniJson.write(root);
    }

    /** Replaces the list with the content of {@code json} (as written by {@link #toJson()}); does not notify. */
    public synchronized void load(String json) {
        plots.clear();
        highestKnownId = 0;
        if (json == null || json.isBlank()) {
            return;
        }
        Map<String, Object> root = MiniJson.parseObject(json);
        highestKnownId = Math.max(0, MiniJson.getInt(root, "highest_id", 0));
        for (Object o : MiniJson.getList(root, "plots")) {
            if (o instanceof Map<?, ?> m) {
                SpatialStoragePlot plot = SpatialStoragePlot.fromMap(m);
                if (plot != null) {
                    plots.put(plot.getId(), plot);
                    highestKnownId = Math.max(highestKnownId, plot.getId());
                }
            }
        }
    }
}
