/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.DelegatingMEStorage;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.NullStorage;
import de.fellurion.ae2.core.storage.StorageHelper;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;

/**
 * ME Interface: keeps its 9 local slots stocked according to its configuration and exposes them to adjacent
 * machines and storage buses. Port of {@code appeng.helpers.InterfaceLogic}.
 * <ul>
 * <li>Config slots (key + amount, items up to one stack, fluids up to 4 buckets) and storage slots of the same
 * size.</li>
 * <li>A plan per slot is computed whenever config or storage change: positive amounts are pulled from the network
 * ({@link StorageHelper#poweredExtraction}), negative ones (wrong/unconfigured content) are pushed back
 * ({@link StorageHelper#poweredInsert}).</li>
 * <li>Ticks at {@link TickRates#Interface} (5..120): URGENT while work could be done, SLOWER if stuck, SLEEP when
 * idle.</li>
 * <li>Fuzzy card: slots accept fuzzy-equal keys; pulls fuzzy candidates when the exact key is missing.</li>
 * <li>{@link #getInventory()}: with any config the local storage, otherwise the grid's whole network storage (this is
 * what a storage bus attached to the interface sees). Local storage refuses requests from other interfaces of the same
 * grid with lower or equal priority (prevents ping-pong).</li>
 * </ul>
 * One upgrade slot (crafting or fuzzy card). Crafting card: requests go to an optional {@link CraftingHook}.
 * <p>
 * The storage slots can be exposed to Hytale containers through {@link #getStorageSlotAccess()} (items only).
 */
public class InterfaceLogic implements IGridTickable, IGridNodeListener, IActionHost {
    public static final String BLOCK_ITEM_ID = "AE2_Interface";
    public static final String PART_ITEM_ID = "AE2_Cable_Interface";

    private final ConfigInventory config;
    private final ConfigInventory storage;
    private final UpgradeInventory upgrades;
    private final GenericStack[] plannedWork;
    private final IActionSource actionSource = IActionSource.ofMachine(this);
    private final IActionSource interfaceRequestSource = new InterfaceRequestSource();
    private final ToIntFunction<String> itemMaxStackSize;
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;
    private int priority;
    private boolean hasConfig;
    private GridNode node;
    private MEStorage localInvHandler;
    private CraftingHook craftingHook;
    private Runnable saveListener;

    /** 9-slot interface; items stack to 64 unless {@link #InterfaceLogic(String, int, ToIntFunction)} is used. */
    public InterfaceLogic(String hostItemId) {
        this(hostItemId, 9, id -> 64);
    }

    /**
     * @param hostItemId       {@link #BLOCK_ITEM_ID} or {@link #PART_ITEM_ID} (for upgrade rules)
     * @param slots            number of config/storage slots (9 in AE2)
     * @param itemMaxStackSize item id → Hytale max stack size
     */
    public InterfaceLogic(String hostItemId, int slots, ToIntFunction<String> itemMaxStackSize) {
        this.itemMaxStackSize = itemMaxStackSize;
        this.config = ConfigInventory.configStacks(slots).setItemMaxStackSize(itemMaxStackSize)
                .useRegisteredCapacities();
        this.storage = ConfigInventory.storage(slots).setItemMaxStackSize(itemMaxStackSize)
                .useRegisteredCapacities().slotFilter(this::isAllowedInStorageSlot);
        this.config.changeListener(this::onConfigRowChanged);
        this.storage.changeListener(this::onStorageChanged);
        this.upgrades = new UpgradeInventory(hostItemId, 1, this::onUpgradesChanged);
        this.plannedWork = new GenericStack[slots];
    }

    // ------------------------------------------------------------------------------------------------------------
    // Accessors

    /** Stocking configuration (key + amount per slot). */
    public ConfigInventory getConfig() {
        return config;
    }

    /** Local storage slots. */
    public ConfigInventory getStorage() {
        return storage;
    }

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public FuzzyMode getFuzzyMode() {
        return fuzzyMode;
    }

    public void setFuzzyMode(FuzzyMode mode) {
        this.fuzzyMode = mode;
        markDirty();
        updatePlan();
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        markDirty();
    }

    public void setCraftingHook(CraftingHook hook) {
        this.craftingHook = hook;
    }

    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    private void markDirty() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    private boolean isAllowedInStorageSlot(int slot, AEKey what) {
        if (slot < config.size()) {
            var configured = config.getKey(slot);
            if (configured == null || configured.equals(what)) {
                return true;
            }
            if (upgrades.isInstalled(UpgradeCard.FUZZY)) {
                return configured.fuzzyEquals(what, fuzzyMode);
            }
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Inventories exposed to others

    /**
     * What an ME-API user (storage bus) sees: the local storage if any slot is configured, otherwise the whole network
     * storage of the interface's grid.
     */
    public MEStorage getInventory() {
        if (hasConfig) {
            return getLocalInventory();
        }
        if (node != null && node.getGrid() != null) {
            return node.getGrid().getStorageService().getInventory();
        }
        return NullStorage.of();
    }

    /**
     * A stable storage that always forwards to {@link #getInventory()}; give this to a storage bus of another network
     * facing this interface.
     */
    public MEStorage getExposedStorage() {
        return exposedStorage;
    }

    private final MEStorage exposedStorage = new MEStorage() {
        @Override
        public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
            return getInventory().isPreferredStorageFor(what, source);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            return getInventory().insert(what, amount, mode, source);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            return getInventory().extract(what, amount, mode, source);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            getInventory().getAvailableStacks(out);
        }

        @Override
        public int getEstimatedStackCount() {
            return getInventory().getEstimatedStackCount();
        }

        @Override
        public String getDescription() {
            return "ae2.interface";
        }
    };

    private MEStorage getLocalInventory() {
        if (localInvHandler == null) {
            localInvHandler = new InterfaceInventory();
        }
        return localInvHandler;
    }

    /** Item view of the storage slots for Hytale containers (fluid slots appear empty and cannot be set). */
    public SlotAccess getStorageSlotAccess() {
        return storageSlotAccess;
    }

    private final SlotAccess storageSlotAccess = new SlotAccess() {
        @Override
        public int size() {
            return storage.size();
        }

        @Override
        public SimpleStack get(int slot) {
            var s = storage.getStack(slot);
            if (s == null || !(s.what() instanceof AEItemKey item) || s.amount() <= 0) {
                return null;
            }
            return SimpleStack.of(item, (int) Math.min(Integer.MAX_VALUE, s.amount()));
        }

        @Override
        public void set(int slot, SimpleStack stack) {
            var current = storage.getStack(slot);
            if (current != null && !(current.what() instanceof AEItemKey)) {
                return; // don't overwrite fluids
            }
            storage.setStack(slot, stack == null ? null : new GenericStack(stack.key(), stack.count()));
        }

        @Override
        public int getMaxStackSize(int slot, String itemId) {
            return (int) Math.min(Integer.MAX_VALUE, storage.getMaxAmount(AEItemKey.of(itemId)));
        }

        @Override
        public boolean isItemValid(int slot, SimpleStack stack) {
            return storage.isAllowedIn(slot, stack.key());
        }
    };

    // ------------------------------------------------------------------------------------------------------------
    // Planning

    private void readConfig() {
        this.hasConfig = !config.isEmpty();
        updatePlan();
        if (node != null && node.isActive()) {
            node.wake();
        }
    }

    public boolean hasWorkToDo() {
        for (var requiredWork : plannedWork) {
            if (requiredWork != null) {
                return true;
            }
        }
        return false;
    }

    private void updatePlan() {
        var hadWork = hasWorkToDo();
        for (int x = 0; x < config.size(); x++) {
            updatePlan(x);
        }
        var hasWork = hasWorkToDo();
        if (hadWork != hasWork && node != null) {
            if (hasWork) {
                node.alert();
            } else {
                node.sleep();
            }
        }
    }

    /** Delta between config and storage of one slot. Port of {@code InterfaceLogic.updatePlan(int)}. */
    private void updatePlan(int slot) {
        var req = config.getStack(slot);
        var stored = storage.getStack(slot);
        if (req == null && stored != null) {
            plannedWork[slot] = new GenericStack(stored.what(), -stored.amount());
        } else if (req != null) {
            if (stored == null) {
                plannedWork[slot] = req; // Nothing stored, request from network
            } else if (storedRequestEquals(req.what(), stored.what())) {
                if (req.amount() != stored.amount()) {
                    plannedWork[slot] = new GenericStack(req.what(), req.amount() - stored.amount());
                } else {
                    plannedWork[slot] = null;
                }
            } else {
                // Requested item differs from stored -> push back into storage before fulfilling request
                plannedWork[slot] = new GenericStack(stored.what(), -stored.amount());
            }
        } else {
            plannedWork[slot] = null;
        }
    }

    private boolean storedRequestEquals(AEKey request, AEKey stored) {
        if (upgrades.isInstalled(UpgradeCard.FUZZY) && request.supportsFuzzyRangeSearch()) {
            return request.fuzzyEquals(stored, fuzzyMode);
        }
        return request.equals(stored);
    }

    private boolean updateStorage() {
        boolean didSomething = false;
        for (int x = 0; x < plannedWork.length; x++) {
            var work = plannedWork[x];
            if (work != null) {
                var amount = (int) work.amount();
                didSomething = usePlan(x, work.what(), amount) || didSomething;
            }
        }
        return didSomething;
    }

    private boolean usePlan(int x, AEKey what, int amount) {
        boolean changed = tryUsePlan(x, what, amount);
        if (changed) {
            updatePlan(x);
        }
        return changed;
    }

    /** Executes the plan of one slot. Port of {@code InterfaceLogic.tryUsePlan}. */
    private boolean tryUsePlan(int slot, AEKey what, int amount) {
        if (node == null || node.getGrid() == null) {
            return false;
        }
        var grid = node.getGrid();
        var networkInv = grid.getStorageService().getInventory();
        var energySrc = grid.getEnergyService();

        // Always move out unwanted items before handling crafting or restocking
        if (amount < 0) {
            amount = -amount;
            var inSlot = storage.getStack(slot);
            if (!what.matches(inSlot) || inSlot.amount() < amount) {
                return true; // Replan
            }
            var inserted = (int) StorageHelper.poweredInsert(energySrc, networkInv, what, amount,
                    interfaceRequestSource);
            if (inserted > 0) {
                storage.extract(slot, what, inserted, Actionable.MODULATE);
            }
            return inserted > 0;
        }

        if (craftingHook != null && craftingHook.isBusy(slot)) {
            // We are already waiting for a crafting result for this slot
            return handleCrafting(slot, what, amount);
        } else if (amount > 0) {
            // Ensure the plan isn't outdated
            if (storage.insert(slot, what, amount, Actionable.SIMULATE) != amount) {
                return true;
            }
            // Try to pull the exact item
            if (acquireFromNetwork(energySrc, networkInv, slot, what, amount)) {
                return true;
            }
            // Try a fuzzy import from network instead if we don't have stacks in stock yet
            if (storage.getStack(slot) == null && upgrades.isInstalled(UpgradeCard.FUZZY)) {
                var candidates = new ArrayList<AEKey>();
                for (var entry : grid.getStorageService().getCachedInventory().findFuzzy(what, fuzzyMode)) {
                    candidates.add(entry.getKey());
                }
                for (var candidate : candidates) {
                    long maxAmount = storage.insert(slot, candidate, amount, Actionable.SIMULATE);
                    if (acquireFromNetwork(energySrc, networkInv, slot, candidate, maxAmount)) {
                        return true;
                    }
                }
            }
            return handleCrafting(slot, what, amount);
        }
        return false;
    }

    private boolean acquireFromNetwork(IEnergySource energySrc, MEStorage networkInv, int slot, AEKey what,
            long amount) {
        var acquired = StorageHelper.poweredExtraction(energySrc, networkInv, what, amount, interfaceRequestSource);
        if (acquired > 0) {
            var inserted = storage.insert(slot, what, acquired, Actionable.MODULATE);
            if (inserted < acquired) {
                throw new IllegalStateException("bad attempt at managing inventory. Voided items: " + inserted);
            }
            return true;
        }
        return false;
    }

    private boolean handleCrafting(int slot, AEKey key, long amount) {
        if (craftingHook != null && upgrades.isInstalled(UpgradeCard.CRAFTING) && key != null) {
            return craftingHook.requestCrafting(slot, key, amount);
        }
        return false;
    }

    private void onConfigRowChanged() {
        markDirty();
        readConfig();
    }

    private void onStorageChanged() {
        markDirty();
        updatePlan();
    }

    private void onUpgradesChanged() {
        markDirty();
        if (!upgrades.isInstalled(UpgradeCard.CRAFTING) && craftingHook != null) {
            craftingHook.cancelAll();
        }
        updatePlan();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Grid

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.Interface, !hasWorkToDo());
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (!node.isActive()) {
            return TickRateModulation.SLEEP;
        }
        boolean couldDoWork = updateStorage();
        return hasWorkToDo() ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                : TickRateModulation.SLEEP;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        if (hasWorkToDo()) {
            node.alert();
        }
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        // Wake up when the node becomes active again (AE2: notifyNeighbors on gridChanged / power changes)
        if (hasWorkToDo() && node.isActive()) {
            node.wake();
        }
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    /** Port of {@code InterfaceLogic.getRequestInterfacePriority}. */
    private static OptionalInt getRequestInterfacePriority(IActionSource src) {
        return src.context(InterfaceRequestContext.class)
                .map(ctx -> OptionalInt.of(ctx.getPriority()))
                .orElseGet(OptionalInt::empty);
    }

    private boolean isSameGrid(IActionSource src) {
        var otherGrid = src.machine().map(IActionHost::getActionableNode).map(GridNode::getGrid).orElse(null);
        return node != null && otherGrid == node.getGrid();
    }

    /** Context attached to stocking requests of an interface. */
    public final class InterfaceRequestContext {
        public int getPriority() {
            return priority;
        }
    }

    private final class InterfaceRequestSource implements IActionSource {
        private final InterfaceRequestContext context = new InterfaceRequestContext();

        @Override
        public Optional<Object> player() {
            return Optional.empty();
        }

        @Override
        public Optional<IActionHost> machine() {
            return Optional.of(InterfaceLogic.this);
        }

        @Override
        public <T> Optional<T> context(Class<T> key) {
            if (key == InterfaceRequestContext.class) {
                return Optional.of(key.cast(context));
            }
            return Optional.empty();
        }
    }

    /**
     * Wraps the local storage to respect interface priorities when attached to a storage bus. Port of
     * {@code InterfaceLogic.InterfaceInventory}.
     */
    private final class InterfaceInventory extends DelegatingMEStorage {
        InterfaceInventory() {
            super(storage);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            // Prevents other interfaces from injecting their items into this interface when they push their local
            // inventory into the network (no bouncing between interfaces).
            if (getRequestInterfacePriority(source).isPresent() && isSameGrid(source)) {
                return 0;
            }
            return super.insert(what, amount, mode, source);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            // Prevents interfaces of lower priority fulfilling their stocking requests from this interface.
            var requestPriority = getRequestInterfacePriority(source);
            if (requestPriority.isPresent() && requestPriority.getAsInt() <= getPriority() && isSameGrid(source)) {
                return 0;
            }
            return super.extract(what, amount, mode, source);
        }

        @Override
        public String getDescription() {
            return "ae2.interface";
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence

    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("cfg", config.toJsonList());
        map.put("st", storage.toJsonList());
        map.put("up", upgrades.toItemIds());
        map.put("fz", fuzzyMode.name());
        map.put("prio", priority);
        return MiniJson.write(map);
    }

    public void fromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        upgrades.fromItemIds(MiniJson.getList(map, "up"));
        fuzzyMode = MiniJson.getEnum(map, "fz", FuzzyMode.class, FuzzyMode.IGNORE_ALL);
        config.fromJsonList(MiniJson.getList(map, "cfg"));
        storage.fromJsonList(MiniJson.getList(map, "st"));
        priority = MiniJson.getInt(map, "prio", 0);
        readConfig();
    }

    /** Port of {@code InterfaceLogic.clearContent}: returns and removes the stored stacks (for block drops). */
    public List<GenericStack> removeStoredStacks() {
        var list = new ArrayList<GenericStack>();
        for (int i = 0; i < storage.size(); i++) {
            var s = storage.getStack(i);
            if (s != null) {
                list.add(s);
            }
        }
        storage.clear();
        return list;
    }
}
