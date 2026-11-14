/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Molecular assembler: executes crafting patterns pushed by an adjacent pattern provider. Port of the crafting logic of
 * {@code appeng.blockentity.crafting.MolecularAssemblerBlockEntity} ({@code ICraftingMachine}).
 * <ul>
 * <li>Accepts one {@link CraftingPattern} plan at a time ({@link #acceptsPlans()}).</li>
 * <li>Progress per tick: {@code energy(ticks · speed · tax) / tax} with (speed, tax) = (10, 1.0), (13, 1.3),
 * (17, 1.7), (20, 2.0), (25, 2.5), (50, 5.0) for 0..5 acceleration cards; a craft completes at 100 progress, i.e. 10
 * ticks without cards and 2 ticks with 5 cards.</li>
 * <li>The result and container remainders are returned to the pushing pattern provider's return inventory (AE2
 * pushes the output towards the provider that sent the pattern).</li>
 * </ul>
 * Node: type {@code molecular_assembler} (0 AE/t idle), this object as owner.
 */
public final class MolecularAssemblerLogic implements PatternProviderTarget, IGridTickable, IGridNodeListener,
        IActionHost {
    private final UpgradeInventory upgrades = new UpgradeInventory("AE2_Molecular_Assembler", 5);
    private CraftingPattern plan;
    private KeyCounter[] heldInputs;
    private PatternProviderLogic returnTo;
    private final List<GenericStack> pendingOutput = new ArrayList<>();
    private double progress;
    /** AE2: the first tick after a new plan counts as one tick, regardless of how long the device slept. */
    private boolean reboot = true;
    private GridNode node;
    private Runnable changeListener;

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** Crafting progress 0..100 (for the UI / block state "Working"). */
    public int getCraftingProgress() {
        return (int) progress;
    }

    public boolean isWorking() {
        return plan != null;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable type) {
        return 0; // only accepts whole plans
    }

    @Override
    public boolean containsPatternInput(Set<AEKey> patternInputs) {
        return plan != null;
    }

    @Override
    public boolean acceptsPlans() {
        return plan == null && pendingOutput.isEmpty();
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs, PatternProviderLogic provider) {
        if (!acceptsPlans() || !(details instanceof CraftingPattern cp)) {
            return false;
        }
        this.plan = cp;
        this.heldInputs = inputs;
        this.returnTo = provider;
        this.progress = 0;
        this.reboot = true;
        if (node != null) {
            node.alert();
        }
        changed();
        return true;
    }

    private void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(1, 1, plan == null && pendingOutput.isEmpty());
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (!pendingOutput.isEmpty()) {
            pushOut();
            return pendingOutput.isEmpty() && plan == null ? TickRateModulation.SLEEP : TickRateModulation.IDLE;
        }
        if (plan == null) {
            return TickRateModulation.SLEEP;
        }
        if (reboot) {
            ticksSinceLastCall = 1;
            reboot = false;
        }
        int speedCards = upgrades.getInstalledUpgrades(UpgradeCard.SPEED);
        switch (speedCards) {
            case 0 -> progress += userPower(ticksSinceLastCall, 10, 1.0);
            case 1 -> progress += userPower(ticksSinceLastCall, 13, 1.3);
            case 2 -> progress += userPower(ticksSinceLastCall, 17, 1.7);
            case 3 -> progress += userPower(ticksSinceLastCall, 20, 2.0);
            case 4 -> progress += userPower(ticksSinceLastCall, 25, 2.5);
            default -> progress += userPower(ticksSinceLastCall, 50, 5.0);
        }
        if (progress >= 100) {
            progress = 0;
            pendingOutput.add(plan.getPrimaryOutput());
            // container remainders of the used inputs
            for (var counter : heldInputs) {
                for (var e : counter) {
                    if (e.getKey() instanceof AEItemKey item) {
                        var rem = plan.getRemainder(item);
                        if (rem != null) {
                            pendingOutput.add(new GenericStack(rem, e.getLongValue()));
                        }
                    }
                }
            }
            plan = null;
            heldInputs = null;
            pushOut();
            changed();
            return pendingOutput.isEmpty() ? TickRateModulation.SLEEP : TickRateModulation.IDLE;
        }
        return TickRateModulation.FASTER;
    }

    private int userPower(int ticksPassed, int bonusValue, double acceleratorTax) {
        if (node == null || node.getGrid() == null) {
            return 0;
        }
        return (int) (node.getGrid().getEnergyService().extractAEPower(ticksPassed * bonusValue * acceleratorTax,
                Actionable.MODULATE, PowerMultiplier.CONFIG) / acceleratorTax);
    }

    private void pushOut() {
        if (returnTo == null) {
            return;
        }
        var it = pendingOutput.listIterator();
        while (it.hasNext()) {
            var s = it.next();
            long inserted = returnTo.getReturnInventory().insert(s.what(), s.amount(), Actionable.MODULATE);
            if (inserted >= s.amount()) {
                it.remove();
            } else if (inserted > 0) {
                it.set(new GenericStack(s.what(), s.amount() - inserted));
            }
        }
    }

    /**
     * Deviation from AE2 (which serializes the block entity): the Hytale side saves the running plan when the
     * chunk stops ticking, so the crafting job resumes instead of getting stuck.
     */
    public String saveState() {
        var map = new java.util.LinkedHashMap<String, Object>();
        if (plan != null) {
            map.put("plan", plan.getDefinition().toJsonMap());
            var inputs = new ArrayList<Object>();
            if (heldInputs != null) {
                for (var counter : heldInputs) {
                    var list = new ArrayList<Object>();
                    for (var e : counter) {
                        list.add(PatternHelper.stackToJson(new GenericStack(e.getKey(), e.getLongValue())));
                    }
                    inputs.add(list);
                }
            }
            map.put("inputs", inputs);
            map.put("progress", progress);
        }
        var pending = new ArrayList<Object>();
        for (var s : pendingOutput) {
            pending.add(PatternHelper.stackToJson(s));
        }
        map.put("pending", pending);
        return de.fellurion.ae2.core.util.MiniJson.write(map);
    }

    /** Restores {@link #saveState()}; the return target is set again via {@link #setReturnTarget}. */
    public void loadState(String json) {
        var map = de.fellurion.ae2.core.util.MiniJson.parseObject(json);
        plan = null;
        heldInputs = null;
        pendingOutput.clear();
        if (map.get("plan") instanceof java.util.Map<?, ?> pm && AEKey.fromJsonMap(pm) instanceof AEItemKey def
                && PatternDetailsHelper.decodePattern(def) instanceof CraftingPattern cp) {
            plan = cp;
            var inputs = de.fellurion.ae2.core.util.MiniJson.getList(map, "inputs");
            heldInputs = new KeyCounter[inputs.size()];
            for (int i = 0; i < inputs.size(); i++) {
                heldInputs[i] = new KeyCounter();
                if (inputs.get(i) instanceof java.util.List<?> list) {
                    for (var o : list) {
                        var gs = PatternHelper.stackFromJson(o);
                        if (gs != null) {
                            heldInputs[i].add(gs.what(), gs.amount());
                        }
                    }
                }
            }
            progress = de.fellurion.ae2.core.util.MiniJson.getDouble(map, "progress", 0);
            reboot = true;
        }
        for (var o : de.fellurion.ae2.core.util.MiniJson.getList(map, "pending")) {
            var gs = PatternHelper.stackFromJson(o);
            if (gs != null) {
                pendingOutput.add(gs);
            }
        }
    }

    /** True if a resumed plan/output has no provider to return its result to. */
    public boolean needsReturnTarget() {
        return returnTo == null && (plan != null || !pendingOutput.isEmpty());
    }

    /** Sets the pattern provider that receives the results (after a reload). */
    public void setReturnTarget(PatternProviderLogic provider) {
        this.returnTo = provider;
        if (node != null) {
            node.alert();
        }
    }

    /** Held inputs and pending outputs (drops when broken). */
    public List<GenericStack> getContents() {
        var list = new ArrayList<>(pendingOutput);
        if (heldInputs != null) {
            for (var c : heldInputs) {
                for (var e : c) {
                    list.add(new GenericStack(e.getKey(), e.getLongValue()));
                }
            }
        }
        return list;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }
}
