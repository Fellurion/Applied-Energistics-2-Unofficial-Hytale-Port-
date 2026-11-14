/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One side (CPU or requester) of a crafting job link. Port of {@code appeng.crafting.CraftingLink} and
 * {@code CraftingLinkNexus} (the nexus ties the CPU link and the requester link with the same id together).
 */
public final class CraftingLink implements ICraftingLink {
    private final ICraftingRequester req;
    private final CraftingCpuCluster cpu;
    private final UUID craftId;
    private final boolean standalone;
    private boolean canceled = false;
    private boolean done = false;
    private Nexus tie;

    public CraftingLink(UUID craftId, boolean standalone, ICraftingRequester requester) {
        this.craftId = craftId;
        this.standalone = standalone;
        this.req = requester;
        this.cpu = null;
    }

    public CraftingLink(UUID craftId, boolean standalone, CraftingCpuCluster cpu) {
        this.craftId = craftId;
        this.standalone = standalone;
        this.req = null;
        this.cpu = cpu;
    }

    @Override
    public boolean isCanceled() {
        if (canceled) {
            return true;
        }
        if (done) {
            return false;
        }
        return tie != null && tie.canceled;
    }

    @Override
    public boolean isDone() {
        if (done) {
            return true;
        }
        if (canceled) {
            return false;
        }
        return tie != null && tie.done;
    }

    @Override
    public void cancel() {
        if (done) {
            return;
        }
        canceled = true;
        if (tie != null) {
            tie.cancel();
        }
        tie = null;
    }

    @Override
    public boolean isStandalone() {
        return standalone;
    }

    @Override
    public UUID getCraftingID() {
        return craftId;
    }

    void setNexus(Nexus n) {
        if (tie != null) {
            tie.remove(this);
        }
        if (isCanceled() && n != null) {
            n.cancel();
            tie = null;
            return;
        }
        tie = n;
        if (n != null) {
            n.add(this);
        }
    }

    /** Delivers crafted final output to the requester (0 if there is none). */
    long insert(AEKey what, long amount, Actionable mode) {
        if (tie == null || tie.req == null || tie.req.req == null) {
            return 0;
        }
        if (tie.canceled) {
            return 0;
        }
        return tie.req.req.insertCraftedItems(tie.req, what, amount, mode);
    }

    void markDone() {
        if (tie != null) {
            tie.markDone();
        }
    }

    ICraftingRequester getRequester() {
        return req;
    }

    CraftingCpuCluster getCpu() {
        return cpu;
    }

    Map<String, Object> toJson() {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", craftId.toString());
        m.put("canceled", isCanceled());
        m.put("done", isDone());
        m.put("standalone", standalone);
        return m;
    }

    static CraftingLink cpuLinkFromJson(Map<?, ?> m, CraftingCpuCluster cpu) {
        var link = new CraftingLink(UUID.fromString(MiniJson.getString(m, "id", UUID.randomUUID().toString())),
                MiniJson.getBool(m, "standalone", true), cpu);
        link.canceled = MiniJson.getBool(m, "canceled", false);
        link.done = MiniJson.getBool(m, "done", false);
        return link;
    }

    /** Port of {@code CraftingLinkNexus}. */
    static final class Nexus {
        final UUID craftId;
        boolean canceled;
        boolean done;
        int tickOfDeath;
        CraftingLink req;
        CraftingLink cpu;

        Nexus(UUID craftId) {
            this.craftId = craftId;
        }

        boolean isDead(CraftingService service) {
            if (canceled || done) {
                return true;
            }
            if (req == null || cpu == null) {
                tickOfDeath++;
            } else {
                boolean hasCpu = service.hasCpu(cpu.getCpu());
                var node = req.getRequester().getActionableNode();
                boolean hasMachine = node != null && node.getGrid() == service.getGrid();
                if (hasCpu && hasMachine) {
                    tickOfDeath = 0;
                } else {
                    tickOfDeath += 60;
                }
            }
            if (tickOfDeath > 60) {
                cancel();
                return true;
            }
            return false;
        }

        void cancel() {
            canceled = true;
            if (req != null) {
                req.canceled = true;
                if (req.getRequester() != null) {
                    req.getRequester().jobStateChange(req);
                }
            }
            if (cpu != null) {
                cpu.canceled = true;
            }
        }

        void remove(CraftingLink link) {
            if (req == link) {
                req = null;
            } else if (cpu == link) {
                cpu = null;
            }
        }

        void add(CraftingLink link) {
            if (link.getCpu() != null) {
                cpu = link;
            } else if (link.getRequester() != null) {
                req = link;
            }
        }

        void markDone() {
            done = true;
            if (req != null) {
                req.done = true;
                if (req.getRequester() != null) {
                    req.getRequester().jobStateChange(req);
                }
            }
            if (cpu != null) {
                cpu.done = true;
            }
        }

        boolean isRequester(ICraftingRequester requester) {
            return req != null && req.getRequester() == requester;
        }

        void removeNode() {
            if (req != null) {
                req.setNexus(null);
            }
            req = null;
            tickOfDeath = 0;
        }
    }
}
