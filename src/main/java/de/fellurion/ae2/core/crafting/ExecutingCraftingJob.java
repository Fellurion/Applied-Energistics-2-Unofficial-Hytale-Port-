/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A job running on a CPU. Port of {@code appeng.crafting.execution.ExecutingCraftingJob}.
 */
final class ExecutingCraftingJob {
    final CraftingLink link;
    final ListCraftingInventory waitingFor;
    final Map<IPatternDetails, long[]> tasks = new LinkedHashMap<>();
    final ElapsedTimeTracker timeTracker;
    GenericStack finalOutput;
    long remainingAmount;
    boolean suspended;

    ExecutingCraftingJob(CraftingPlan plan, ListCraftingInventory.ChangeListener listener, CraftingLink link) {
        this.finalOutput = plan.finalOutput();
        this.remainingAmount = finalOutput.amount();
        this.waitingFor = new ListCraftingInventory(listener);
        this.timeTracker = new ElapsedTimeTracker();
        for (var entry : plan.emittedItems()) {
            waitingFor.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
            timeTracker.addMaxItems(entry.getLongValue(), entry.getKey().getType());
        }
        for (var entry : plan.patternTimes().entrySet()) {
            tasks.computeIfAbsent(entry.getKey(), p -> new long[1])[0] += entry.getValue();
            for (var output : entry.getKey().getOutputs()) {
                var amount = output.amount() * entry.getValue() * output.what().getAmountPerUnit();
                timeTracker.addMaxItems(amount, output.what().getType());
            }
        }
        this.link = link;
        this.suspended = false;
    }

    private ExecutingCraftingJob(CraftingLink link, ListCraftingInventory waitingFor, ElapsedTimeTracker tracker) {
        this.link = link;
        this.waitingFor = waitingFor;
        this.timeTracker = tracker;
    }

    Map<String, Object> toJson() {
        var m = new LinkedHashMap<String, Object>();
        m.put("link", link.toJson());
        if (finalOutput != null) {
            m.put("out", PatternHelper.stackToJson(finalOutput));
        }
        m.put("rem", remainingAmount);
        m.put("wait", waitingFor.toJson());
        m.put("time", timeTracker.toJson());
        var taskList = new ArrayList<Object>();
        for (var e : tasks.entrySet()) {
            var t = new LinkedHashMap<String, Object>();
            t.put("def", e.getKey().getDefinition().toJsonMap());
            t.put("n", e.getValue()[0]);
            taskList.add(t);
        }
        m.put("tasks", taskList);
        if (suspended) {
            m.put("susp", true);
        }
        return m;
    }

    static ExecutingCraftingJob fromJson(Map<?, ?> m, ListCraftingInventory.ChangeListener listener,
            CraftingCpuCluster cpu) {
        var link = CraftingLink.cpuLinkFromJson(MiniJson.getMap(m, "link"), cpu);
        var waiting = new ListCraftingInventory(listener);
        waiting.fromJson(MiniJson.getList(m, "wait"));
        var job = new ExecutingCraftingJob(link, waiting, ElapsedTimeTracker.fromJson(MiniJson.getMap(m, "time")));
        job.finalOutput = PatternHelper.stackFromJson(m.get("out"));
        job.remainingAmount = MiniJson.getLong(m, "rem", 0);
        for (var o : MiniJson.getList(m, "tasks")) {
            if (o instanceof Map<?, ?> t) {
                var def = AEKey.fromJsonMap(MiniJson.getMap(t, "def"));
                if (def instanceof AEItemKey itemKey) {
                    var details = PatternDetailsHelper.decodePattern(itemKey);
                    if (details != null) {
                        job.tasks.put(details, new long[] { MiniJson.getLong(t, "n", 0) });
                    }
                }
            }
        }
        job.suspended = MiniJson.getBool(m, "susp", false);
        return job;
    }
}
