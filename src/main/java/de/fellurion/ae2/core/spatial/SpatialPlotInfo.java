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
import java.util.Locale;
import java.util.Map;

/**
 * What a spatial storage cell remembers about its plot: the plot id and the size the cell was bound to by its first
 * transfer. Port of {@code appeng.items.storage.SpatialPlotInfo} (the data component {@code ae2:spatial_plot_info});
 * here it is stored as compact JSON in the cell's item metadata.
 *
 * @param id    plot id (see {@link SpatialStoragePlotManager})
 * @param sizeX stored size in blocks
 */
public record SpatialPlotInfo(int id, int sizeX, int sizeY, int sizeZ) {

    /** The serial number AE2 shows in the cell's tooltip ({@code SP-0001}). */
    public String serialNumber() {
        return String.format(Locale.ROOT, "SP-%04d", id);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("x", sizeX);
        m.put("y", sizeY);
        m.put("z", sizeZ);
        return MiniJson.write(m);
    }

    /** @return the plot info, or null if {@code json} is empty or not a plot info */
    public static SpatialPlotInfo fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = MiniJson.parseObject(json);
            int id = MiniJson.getInt(m, "id", -1);
            int x = MiniJson.getInt(m, "x", 0);
            int y = MiniJson.getInt(m, "y", 0);
            int z = MiniJson.getInt(m, "z", 0);
            if (id < 0 || x < 1 || y < 1 || z < 1) {
                return null;
            }
            return new SpatialPlotInfo(id, x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
