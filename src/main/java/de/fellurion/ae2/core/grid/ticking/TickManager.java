/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid.ticking;

import de.fellurion.ae2.core.grid.GridNode;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Adaptive tick scheduler of a grid. Port of {@code appeng.me.service.TickManagerService} and
 * {@code appeng.me.service.helpers.TickTracker}.
 * <p>
 * Deviation: AE2 keeps one queue per Minecraft level and ticks it at the level's end tick; there is only one
 * {@code MEWorld} per Hytale world here, so a single queue is used. Timing statistics are omitted.
 */
public final class TickManager {
    private static final int TICK_RATE_SPEED_UP_FACTOR = 2;
    private static final int TICK_RATE_SLOW_DOWN_FACTOR = 1;

    private final Map<GridNode, TickTracker> alertable = new IdentityHashMap<>();
    private final Map<GridNode, TickTracker> sleeping = new IdentityHashMap<>();
    private final Map<GridNode, TickTracker> awake = new IdentityHashMap<>();
    private final PriorityQueue<TickTracker> upcomingTicks = new PriorityQueue<>();
    private long currentTick = 0;
    private GridNode currentlyTicking;

    public void onServerStartTick() {
        this.currentTick++;
    }

    public long getCurrentTick() {
        return currentTick;
    }

    /** Ticks all devices whose next tick is due. */
    public void tickQueue() {
        TickTracker tt;
        while (!upcomingTicks.isEmpty()) {
            tt = upcomingTicks.peek();
            if (tt.getNextTick() > this.currentTick) {
                break;
            }
            upcomingTicks.poll();
            var diff = (int) (this.currentTick - tt.getLastTick());
            currentlyTicking = tt.node;
            TickRateModulation mod;
            try {
                mod = tt.tickable.tickingRequest(tt.node, diff);
            } finally {
                currentlyTicking = null;
            }
            tt.setLastTick(this.currentTick);
            var newRate = switch (mod) {
                case URGENT -> tt.request.minTickRate();
                case FASTER -> tt.getCurrentRate() - TICK_RATE_SPEED_UP_FACTOR;
                case IDLE, SLEEP -> tt.request.maxTickRate();
                case SLOWER -> tt.getCurrentRate() + TICK_RATE_SLOW_DOWN_FACTOR;
                case SAME -> tt.getCurrentRate();
            };
            tt.setCurrentRate(newRate);
            if (mod == TickRateModulation.SLEEP) {
                sleepDevice(tt.node);
            } else if (this.awake.containsKey(tt.node)) {
                upcomingTicks.add(tt);
            }
        }
    }

    public void addNode(GridNode node) {
        var tickable = node.getService(IGridTickable.class);
        if (tickable != null) {
            var tr = Objects.requireNonNull(tickable.getTickingRequest(node));
            var tt = new TickTracker(tr, node, tickable, this.currentTick);
            this.alertable.put(node, tt);
            if (tr.isSleeping()) {
                this.sleeping.put(node, tt);
            } else {
                this.awake.put(node, tt);
                this.upcomingTicks.add(tt);
            }
        }
    }

    public void removeNode(GridNode node) {
        if (alertable.remove(node) != null) {
            sleeping.remove(node);
            var tt = awake.remove(node);
            if (tt != null) {
                upcomingTicks.remove(tt);
            }
        }
    }

    /** Wakes the device (if sleeping) and ticks it on the next tick. */
    public boolean alertDevice(GridNode node) {
        Objects.requireNonNull(node);
        if (node == currentlyTicking) {
            return false;
        }
        var tt = this.alertable.get(node);
        if (tt == null) {
            return false;
        }
        this.sleeping.remove(node);
        this.awake.put(node, tt);
        tt.setTickOnNextTick();
        upcomingTicks.remove(tt);
        upcomingTicks.add(tt);
        return true;
    }

    public boolean sleepDevice(GridNode node) {
        Objects.requireNonNull(node);
        if (node == currentlyTicking) {
            return false;
        }
        var tracker = awake.remove(node);
        if (tracker != null) {
            tracker.setCurrentRate(tracker.request.maxTickRate());
            sleeping.put(node, tracker);
            upcomingTicks.remove(tracker);
            return true;
        }
        return false;
    }

    public boolean wakeDevice(GridNode node) {
        Objects.requireNonNull(node);
        if (node == currentlyTicking) {
            return false;
        }
        var tt = sleeping.remove(node);
        if (tt != null) {
            awake.put(node, tt);
            upcomingTicks.remove(tt);
            upcomingTicks.add(tt);
            return true;
        }
        return false;
    }

    public boolean isSleeping(GridNode node) {
        return sleeping.containsKey(node);
    }

    /** Current tick rate of a device (debugging / tests), or 0. */
    public int getCurrentRate(GridNode node) {
        var tt = alertable.get(node);
        return tt == null ? 0 : tt.getCurrentRate();
    }

    static final class TickTracker implements Comparable<TickTracker> {
        final TickingRequest request;
        final IGridTickable tickable;
        final GridNode node;
        private long lastTick;
        private int currentRate;

        TickTracker(TickingRequest req, GridNode node, IGridTickable tickable, long currentTick) {
            this.request = req;
            this.tickable = tickable;
            this.node = node;
            this.setCurrentRate(req.initialTickRate());
            this.lastTick = currentTick;
        }

        @Override
        public int compareTo(TickTracker t) {
            int next = Long.compare(this.getNextTick(), t.getNextTick());
            if (next != 0) {
                return next;
            }
            int last = Long.compare(this.lastTick, t.lastTick);
            if (last != 0) {
                return last;
            }
            return Integer.compare(this.currentRate, t.currentRate);
        }

        int getCurrentRate() {
            return currentRate;
        }

        void setCurrentRate(int rate) {
            this.currentRate = Math.max(request.minTickRate(), Math.min(rate, request.maxTickRate()));
        }

        void setTickOnNextTick() {
            this.currentRate = 1;
        }

        long getNextTick() {
            return lastTick + currentRate;
        }

        long getLastTick() {
            return lastTick;
        }

        void setLastTick(long t) {
            this.lastTick = t;
        }
    }
}
