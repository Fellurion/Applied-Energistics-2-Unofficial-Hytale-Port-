/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pattern provider logic (block and part). Port of {@code appeng.helpers.patternprovider.PatternProviderLogic} and
 * {@code PatternProviderReturnInventory}.
 * <ul>
 * <li>Offers the patterns of its pattern slots ({@link #setPatterns}) to the network.</li>
 * <li>On push it first offers the whole plan to crafting machines ({@link PatternProviderTarget#acceptsPlans()}),
 * then — if the pattern allows it — pushes the inputs into the first target (round robin) that accepts all of them.
 * Inputs that do not fit go to a send list; while the send list is non-empty the provider is busy.</li>
 * <li>Blocking mode: skip targets that still contain any pattern input.</li>
 * <li>Results come back into the {@linkplain #getReturnInventory() return inventory} (machines/integration insert
 * there) and are injected into the network on the next ticks (the CPU intercepts awaited results).</li>
 * </ul>
 * Deviations: the push targets are supplied by the integration ({@link #setTargetSupplier}) instead of scanning
 * adjacent block entities; redstone-based crafting locks and pattern access terminal grouping are not ported; the
 * "lock until result" mode is supported.
 */
public class PatternProviderLogic implements ICraftingProvider, IGridTickable, IGridNodeListener, IActionHost {
    public static final int DEFAULT_PATTERN_SLOTS = 9;

    private final List<IPatternDetails> patterns = new ArrayList<>();
    /** Inputs of all patterns, secondary dropped (for blocking mode). */
    private final Set<AEKey> patternInputs = new HashSet<>();
    private final List<GenericStack> sendList = new ArrayList<>();
    private PatternProviderTarget sendTarget;
    private final ReturnInventory returnInv = new ReturnInventory();
    private Supplier<List<PatternProviderTarget>> targetSupplier = List::of;
    private int priority;
    private boolean blocking;
    private boolean lockUntilResult;
    private GenericStack unlockStack;
    private int roundRobinIndex = 0;
    private GridNode node;
    private IActionSource actionSource;
    private Runnable changeListener;

    /** Supplies the current push targets (adjacent containers / machines, excluding other providers/interfaces). */
    public void setTargetSupplier(Supplier<List<PatternProviderTarget>> supplier) {
        this.targetSupplier = supplier == null ? List::of : supplier;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void saveChanges() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /** Sets the encoded pattern items in the provider's slots (invalid ones are ignored). */
    public void setPatterns(List<AEItemKey> encodedPatterns) {
        var details = new ArrayList<IPatternDetails>();
        for (var key : encodedPatterns) {
            var d = PatternDetailsHelper.decodePattern(key);
            if (d != null) {
                details.add(d);
            }
        }
        setPatternDetails(details);
    }

    public void setPatternDetails(List<? extends IPatternDetails> details) {
        patterns.clear();
        patternInputs.clear();
        for (var d : details) {
            patterns.add(d);
            for (var input : d.getInputs()) {
                for (var candidate : input.getPossibleInputs()) {
                    patternInputs.add(candidate.what().dropSecondary());
                }
            }
        }
        ICraftingProvider.requestUpdate(node);
        saveChanges();
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        ICraftingProvider.requestUpdate(node);
        saveChanges();
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
        saveChanges();
    }

    /** AE2 {@code LockCraftingMode.LOCK_UNTIL_RESULT}: after a push, wait until the primary output came back. */
    public void setLockUntilResult(boolean lock) {
        this.lockUntilResult = lock;
        if (!lock) {
            unlockStack = null;
        }
        saveChanges();
    }

    public boolean isCraftingLocked() {
        return unlockStack != null;
    }

    public ReturnInventory getReturnInventory() {
        return returnInv;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    @Override
    public int getPatternPriority() {
        return priority;
    }

    private <T> void rearrangeRoundRobin(List<T> list) {
        if (list.isEmpty()) {
            return;
        }
        roundRobinIndex %= list.size();
        for (int i = 0; i < roundRobinIndex; ++i) {
            list.add(list.get(i));
        }
        list.subList(0, roundRobinIndex).clear();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!sendList.isEmpty() || node == null || !node.isActive() || !patterns.contains(patternDetails)) {
            return false;
        }
        if (isCraftingLocked()) {
            return false;
        }
        var possibleTargets = new ArrayList<PatternProviderTarget>();
        for (var target : targetSupplier.get()) {
            if (target == null) {
                continue;
            }
            if (target.acceptsPlans()) {
                if (target.pushPattern(patternDetails, inputHolder, this)) {
                    onPushPatternSuccess(patternDetails);
                    return true;
                }
                continue;
            }
            possibleTargets.add(target);
        }
        if (!patternDetails.supportsPushInputsToExternalInventory()) {
            return false;
        }
        rearrangeRoundRobin(possibleTargets);
        for (int i = 0; i < possibleTargets.size(); ++i) {
            var adapter = possibleTargets.get(i);
            if (blocking && adapter.containsPatternInput(patternInputs)) {
                continue;
            }
            if (adapterAcceptsAll(adapter, inputHolder)) {
                patternDetails.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                    var inserted = adapter.insert(what, amount, Actionable.MODULATE);
                    if (inserted < amount) {
                        addToSendList(what, amount - inserted);
                    }
                });
                onPushPatternSuccess(patternDetails);
                sendTarget = adapter;
                sendStacksOut();
                roundRobinIndex += i + 1;
                return true;
            }
        }
        return false;
    }

    private void onPushPatternSuccess(IPatternDetails pattern) {
        unlockStack = null;
        if (lockUntilResult) {
            unlockStack = pattern.getPrimaryOutput();
        }
        saveChanges();
    }

    private static boolean adapterAcceptsAll(PatternProviderTarget target, KeyCounter[] inputHolder) {
        for (var inputList : inputHolder) {
            for (var input : inputList) {
                if (target.insert(input.getKey(), input.getLongValue(), Actionable.SIMULATE) == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void addToSendList(AEKey what, long amount) {
        if (amount > 0) {
            sendList.add(new GenericStack(what, amount));
            if (node != null) {
                node.alert();
            }
        }
    }

    private boolean sendStacksOut() {
        if (sendTarget == null) {
            return false;
        }
        boolean didSomething = false;
        for (var it = sendList.listIterator(); it.hasNext();) {
            var stack = it.next();
            var inserted = sendTarget.insert(stack.what(), stack.amount(), Actionable.MODULATE);
            if (inserted >= stack.amount()) {
                it.remove();
                didSomething = true;
            } else if (inserted > 0) {
                it.set(new GenericStack(stack.what(), stack.amount() - inserted));
                didSomething = true;
            }
        }
        if (sendList.isEmpty()) {
            sendTarget = null;
        }
        return didSomething;
    }

    @Override
    public boolean isBusy() {
        return !sendList.isEmpty();
    }

    private boolean hasWorkToDo() {
        return !sendList.isEmpty() || !returnInv.isEmpty();
    }

    private boolean doWork() {
        boolean did = false;
        if (node != null && node.getGrid() != null) {
            did = returnInv.injectIntoNetwork(node.getGrid().getStorageService().getInventory(), getSource(),
                    this::onStackReturnedToNetwork);
        }
        return did | sendStacksOut();
    }

    private void onStackReturnedToNetwork(GenericStack stack) {
        if (unlockStack != null && unlockStack.what().equals(stack.what())) {
            var remaining = unlockStack.amount() - stack.amount();
            unlockStack = remaining <= 0 ? null : new GenericStack(unlockStack.what(), remaining);
        }
    }

    private IActionSource getSource() {
        if (actionSource == null) {
            actionSource = IActionSource.ofMachine(this);
        }
        return actionSource;
    }

    /** Items still to be sent out (drops when broken). */
    public List<GenericStack> getSendList() {
        return List.copyOf(sendList);
    }

    // --- grid callbacks ---

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.Interface, !hasWorkToDo());
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (!node.isActive()) {
            return TickRateModulation.SLEEP;
        }
        boolean couldDoWork = doWork();
        return hasWorkToDo() ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                : TickRateModulation.SLEEP;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        ICraftingProvider.requestUpdate(node);
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state != NodeState.CONNECTIONS && node.isActive() && hasWorkToDo()) {
            node.alert();
        }
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    // --- persistence ---

    /** Saves priority, modes, send list and return inventory (pattern items are stored by the integration). */
    public String toJson() {
        var m = new LinkedHashMap<String, Object>();
        m.put("priority", priority);
        if (blocking) {
            m.put("blocking", true);
        }
        if (lockUntilResult) {
            m.put("lockResult", true);
        }
        if (unlockStack != null) {
            m.put("unlock", PatternHelper.stackToJson(unlockStack));
        }
        if (!sendList.isEmpty()) {
            m.put("send", PatternHelper.stacksToJson(sendList));
        }
        if (!returnInv.isEmpty()) {
            m.put("ret", PatternHelper.stacksToJson(returnInv.stacksView()));
        }
        return MiniJson.write(m);
    }

    /**
     * Restores state saved by {@link #toJson()}. Note: a restored send list is pushed into the first available
     * target (AE2 remembers the side).
     */
    public void fromJson(String json) {
        var m = MiniJson.parseObject(json);
        priority = MiniJson.getInt(m, "priority", 0);
        blocking = MiniJson.getBool(m, "blocking", false);
        lockUntilResult = MiniJson.getBool(m, "lockResult", false);
        unlockStack = PatternHelper.stackFromJson(m.get("unlock"));
        sendList.clear();
        for (var s : PatternHelper.stacksFromJson(MiniJson.getList(m, "send"))) {
            if (s != null) {
                sendList.add(s);
            }
        }
        if (!sendList.isEmpty()) {
            var targets = targetSupplier.get();
            sendTarget = targets.isEmpty() ? null : targets.getFirst();
        }
        returnInv.clear();
        for (var s : PatternHelper.stacksFromJson(MiniJson.getList(m, "ret"))) {
            if (s != null) {
                returnInv.insert(s.what(), s.amount(), Actionable.MODULATE);
            }
        }
    }

    /**
     * 9-slot buffer for results coming back from machines; injected into the network by the provider. Port of
     * {@code PatternProviderReturnInventory}; slot capacity: 64 items or 4 buckets (AE2 uses the item max stack size).
     */
    public final class ReturnInventory {
        public static final int NUMBER_OF_SLOTS = 9;
        public static final long ITEM_CAPACITY = 64;
        public static final long FLUID_CAPACITY = 4000;

        private final GenericStack[] stacks = new GenericStack[NUMBER_OF_SLOTS];
        private boolean injectingIntoNetwork;

        private long capacity(AEKey key) {
            return key instanceof AEItemKey ? ITEM_CAPACITY : FLUID_CAPACITY;
        }

        /** Inserts a returned result. @return amount accepted */
        public long insert(AEKey what, long amount, Actionable mode) {
            if (injectingIntoNetwork || amount <= 0) {
                return 0;
            }
            long remaining = amount;
            long cap = capacity(what);
            // stack onto existing slots first, then empty slots
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 0; i < stacks.length && remaining > 0; i++) {
                    var s = stacks[i];
                    if (pass == 0 && s != null && s.what().equals(what)) {
                        long add = Math.min(remaining, cap - s.amount());
                        if (add > 0) {
                            if (mode == Actionable.MODULATE) {
                                stacks[i] = new GenericStack(what, s.amount() + add);
                            }
                            remaining -= add;
                        }
                    } else if (pass == 1 && s == null) {
                        long add = Math.min(remaining, cap);
                        if (mode == Actionable.MODULATE) {
                            stacks[i] = new GenericStack(what, add);
                        }
                        remaining -= add;
                    }
                }
            }
            long inserted = amount - remaining;
            if (inserted > 0 && mode == Actionable.MODULATE) {
                if (node != null) {
                    node.alert();
                }
                saveChanges();
            }
            return inserted;
        }

        public boolean isEmpty() {
            for (var s : stacks) {
                if (s != null) {
                    return false;
                }
            }
            return true;
        }

        public void clear() {
            java.util.Arrays.fill(stacks, null);
        }

        List<GenericStack> stacksView() {
            var l = new ArrayList<GenericStack>();
            for (var s : stacks) {
                if (s != null) {
                    l.add(s);
                }
            }
            return l;
        }

        /** Content for drops. */
        public List<GenericStack> getStacks() {
            return stacksView();
        }

        boolean injectIntoNetwork(de.fellurion.ae2.core.storage.MEStorage storage, IActionSource src,
                java.util.function.Consumer<GenericStack> insertionCallback) {
            var didSomething = false;
            injectingIntoNetwork = true;
            try {
                for (int i = 0; i < stacks.length; ++i) {
                    var stack = stacks[i];
                    if (stack != null) {
                        var inserted = storage.insert(stack.what(), stack.amount(), Actionable.MODULATE, src);
                        stacks[i] = inserted >= stack.amount() ? null
                                : new GenericStack(stack.what(), stack.amount() - inserted);
                        if (inserted > 0) {
                            didSomething = true;
                            insertionCallback.accept(new GenericStack(stack.what(), inserted));
                        }
                    }
                }
            } finally {
                injectingIntoNetwork = false;
            }
            if (didSomething) {
                saveChanges();
            }
            return didSomething;
        }
    }
}
