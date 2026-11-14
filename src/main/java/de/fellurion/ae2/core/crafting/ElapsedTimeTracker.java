/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks elapsed time and progress of a crafting job. Port of {@code appeng.crafting.execution.ElapsedTimeTracker}.
 */
public final class ElapsedTimeTracker {
    private long lastTime = System.nanoTime();
    private long elapsedTime = 0;
    private final Map<AEKeyType, Long> startedWorkByType = new EnumMap<>(AEKeyType.class);
    private final Map<AEKeyType, Long> completedWorkByType = new EnumMap<>(AEKeyType.class);

    private void updateTime() {
        long currentTime = System.nanoTime();
        this.elapsedTime = this.elapsedTime + (currentTime - this.lastTime);
        this.lastTime = currentTime;
    }

    void decrementItems(long itemDiff, AEKeyType keyType) {
        updateTime();
        completedWorkByType.merge(keyType, itemDiff, ElapsedTimeTracker::saturatedSum);
    }

    void addMaxItems(long itemDiff, AEKeyType keyType) {
        updateTime();
        startedWorkByType.merge(keyType, itemDiff, ElapsedTimeTracker::saturatedSum);
    }

    private static long saturatedSum(long a, long b) {
        var result = a + b;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    public long getElapsedTime() {
        boolean allDone = true;
        for (var keyType : AEKeyType.values()) {
            if (completedWorkByType.getOrDefault(keyType, 0L) < startedWorkByType.getOrDefault(keyType, 0L)) {
                allDone = false;
                break;
            }
        }
        return allDone ? elapsedTime : elapsedTime + (System.nanoTime() - lastTime);
    }

    /** Progress in [0, 1], fluids weighted per bucket. */
    public float getProgress() {
        double startedUnits = 0;
        double completedUnits = 0;
        for (var keyType : AEKeyType.values()) {
            startedUnits += startedWorkByType.getOrDefault(keyType, 0L) / (double) keyType.getAmountPerUnit();
            completedUnits += completedWorkByType.getOrDefault(keyType, 0L) / (double) keyType.getAmountPerUnit();
        }
        if (startedUnits <= 0) {
            return 0;
        }
        return (float) Math.max(0, Math.min(1, completedUnits / startedUnits));
    }

    Map<String, Object> toJson() {
        var m = new LinkedHashMap<String, Object>();
        m.put("t", getElapsedTime());
        var s = new LinkedHashMap<String, Object>();
        startedWorkByType.forEach((k, v) -> s.put(k.shortId(), v));
        var c = new LinkedHashMap<String, Object>();
        completedWorkByType.forEach((k, v) -> c.put(k.shortId(), v));
        m.put("s", s);
        m.put("c", c);
        return m;
    }

    static ElapsedTimeTracker fromJson(Map<?, ?> m) {
        var t = new ElapsedTimeTracker();
        t.elapsedTime = MiniJson.getLong(m, "t", 0);
        MiniJson.getMap(m, "s").forEach((k, v) -> t.startedWorkByType.put(AEKeyType.byShortId(String.valueOf(k)),
                ((Number) v).longValue()));
        MiniJson.getMap(m, "c").forEach((k, v) -> t.completedWorkByType.put(AEKeyType.byShortId(String.valueOf(k)),
                ((Number) v).longValue()));
        return t;
    }
}
