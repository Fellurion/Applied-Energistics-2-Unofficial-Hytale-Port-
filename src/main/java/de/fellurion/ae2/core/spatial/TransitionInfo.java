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
 * Where and when the content of a plot was last swapped. Port of {@code appeng.spatial.TransitionInfo}; it lets admin
 * commands tell where the content of a plot came from.
 *
 * @param worldId   name of the world of the transfer
 * @param minX      corner of the pylon bounding box (AE2 stores the service's min/max, not the inner region)
 * @param timestamp time of the transfer in milliseconds since the epoch (AE2: an {@code Instant})
 */
public record TransitionInfo(String worldId, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        long timestamp) {

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world_id", worldId);
        m.put("min", java.util.List.of(minX, minY, minZ));
        m.put("max", java.util.List.of(maxX, maxY, maxZ));
        m.put("timestamp", timestamp);
        return m;
    }

    static TransitionInfo fromMap(Map<?, ?> m) {
        var min = MiniJson.getList(m, "min");
        var max = MiniJson.getList(m, "max");
        if (min.size() != 3 || max.size() != 3) {
            return null;
        }
        return new TransitionInfo(MiniJson.getString(m, "world_id", ""), num(min.get(0)), num(min.get(1)),
                num(min.get(2)), num(max.get(0)), num(max.get(1)), num(max.get(2)),
                MiniJson.getLong(m, "timestamp", 0));
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
