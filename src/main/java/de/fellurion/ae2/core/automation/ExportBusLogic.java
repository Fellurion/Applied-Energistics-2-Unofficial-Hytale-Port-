/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.SchedulingMode;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.StorageHelper;
import de.fellurion.ae2.core.storage.partition.IPartitionList;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Export bus: pushes the configured keys from the network into the inventory in front. Port of
 * {@code appeng.parts.automation.ExportBusPart} with {@code StorageExportStrategy}.
 * <ul>
 * <li>For each usable filter slot (order by {@link SchedulingMode}: DEFAULT = slot order, ROUNDROBIN = continue after
 * the last exported slot, RANDOM) up to {@code operations · amountPerOperation} is moved using
 * {@link StorageHelper#poweredExtraction} (1 AE per item / 125 mB).</li>
 * <li>With a fuzzy card all fuzzy-equal keys of the network are exported.</li>
 * <li>Crafting card: if nothing could be exported (or "craft only" is set), the {@link CraftingHook} is asked to
 * craft the missing amount. Deviation: AE2 uses {@code MultiCraftingTracker} directly; here the integration/crafting
 * module supplies the hook. Without a hook crafting cards have no effect.</li>
 * </ul>
 */
public class ExportBusLogic extends IOBusLogic {
    public static final String ITEM_ID = "AE2_Export_Bus";

    private SchedulingMode schedulingMode = SchedulingMode.DEFAULT;
    private YesNo craftOnly = YesNo.NO;
    private int nextSlot = 0;
    private RandomGenerator random = new Random();
    private CraftingHook craftingHook;

    public ExportBusLogic() {
        super(TickRates.ExportBus, ITEM_ID);
    }

    public SchedulingMode getSchedulingMode() {
        return schedulingMode;
    }

    public void setSchedulingMode(SchedulingMode mode) {
        this.schedulingMode = mode;
        markDirty();
    }

    public boolean isCraftOnly() {
        return craftOnly == YesNo.YES;
    }

    public void setCraftOnly(boolean craftOnly) {
        this.craftOnly = craftOnly ? YesNo.YES : YesNo.NO;
        markDirty();
    }

    /** For RANDOM scheduling (seedable for tests). */
    public void setRandom(RandomGenerator random) {
        this.random = random;
    }

    public void setCraftingHook(CraftingHook hook) {
        this.craftingHook = hook;
    }

    @Override
    protected void upgradesChanged() {
        if (!isCraftingEnabled() && craftingHook != null) {
            craftingHook.cancelAll();
        }
        super.upgradesChanged();
    }

    @Override
    protected boolean doBusWork(Grid grid) {
        var storageService = grid.getStorageService();
        var context = new StackTransferContext(storageService, grid.getEnergyService(), source,
                getOperationsPerTick(), IPartitionList.EMPTY);
        int x;
        for (x = 0; x < availableSlots() && context.hasOperationsLeft(); x++) {
            final int slotToExport = getStartingSlot(x);
            var what = getConfig().getKey(slotToExport);
            if (what == null) {
                continue;
            }
            if (craftOnly()) {
                attemptCrafting(context, slotToExport, what);
                continue;
            }
            var before = context.getOperationsRemaining();
            if (isUpgradedWith(UpgradeCard.FUZZY)) {
                // When fuzzy exporting, simply attempt export of all items in the set of fuzzy-equals keys
                var fuzzyKeys = new ArrayList<AEKey>();
                for (var e : storageService.getCachedInventory().findFuzzy(what, getFuzzyMode())) {
                    fuzzyKeys.add(e.getKey());
                }
                for (var fuzzyWhat : fuzzyKeys) {
                    var transferFactor = fuzzyWhat.getAmountPerOperation();
                    long amount = (long) context.getOperationsRemaining() * transferFactor;
                    amount = transfer(context, fuzzyWhat, amount);
                    context.reduceOperationsRemaining(Math.max(1, amount / transferFactor));
                    if (!context.hasOperationsLeft()) {
                        break;
                    }
                }
            } else {
                var transferFactor = what.getAmountPerOperation();
                long amount = (long) context.getOperationsRemaining() * transferFactor;
                amount = transfer(context, what, amount);
                if (amount > 0) {
                    context.reduceOperationsRemaining(Math.max(1, amount / transferFactor));
                }
            }
            if (before == context.getOperationsRemaining() && isCraftingEnabled()) {
                attemptCrafting(context, slotToExport, what);
            }
        }
        // Round-robin should only advance if something was actually exported
        if (context.hasDoneWork()) {
            updateSchedulingMode(x);
        }
        return context.hasDoneWork();
    }

    /** Port of {@code StorageExportStrategy.transfer}. @return amount moved */
    private long transfer(StackTransferContext context, AEKey what, long amount) {
        var adjacentStorage = getTarget();
        if (adjacentStorage == null) {
            return 0;
        }
        var inv = context.getInternalStorage().getInventory();
        var extracted = StorageHelper.poweredExtraction(context.getEnergySource(), inv, what, amount,
                context.getActionSource(), Actionable.SIMULATE);
        long wasInserted = adjacentStorage.insert(what, extracted, Actionable.SIMULATE, context.getActionSource());
        if (wasInserted > 0) {
            extracted = StorageHelper.poweredExtraction(context.getEnergySource(), inv, what, wasInserted,
                    context.getActionSource(), Actionable.MODULATE);
            wasInserted = adjacentStorage.insert(what, extracted, Actionable.MODULATE, context.getActionSource());
            if (wasInserted < extracted) {
                // Be nice and try to give the overflow back
                long leftover = extracted - wasInserted;
                inv.insert(what, leftover, Actionable.MODULATE, context.getActionSource());
            }
        }
        return wasInserted;
    }

    private void attemptCrafting(StackTransferContext context, int slotToExport, AEKey what) {
        var target = getTarget();
        if (craftingHook == null || target == null) {
            return;
        }
        // don't bother crafting if target cannot accept at least 1 of the requested item
        var maxAmount = (long) context.getOperationsRemaining() * what.getAmountPerOperation();
        var amount = target.insert(what, maxAmount, Actionable.SIMULATE, context.getActionSource());
        if (amount > 0) {
            craftingHook.requestCrafting(slotToExport, what, amount);
            context.reduceOperationsRemaining(Math.max(1, amount / what.getAmountPerOperation()));
        }
    }

    private int getStartingSlot(int x) {
        if (schedulingMode == SchedulingMode.RANDOM) {
            return random.nextInt(availableSlots());
        }
        if (schedulingMode == SchedulingMode.ROUNDROBIN) {
            return (nextSlot + x) % availableSlots();
        }
        return x;
    }

    private void updateSchedulingMode(int x) {
        if (schedulingMode == SchedulingMode.ROUNDROBIN) {
            nextSlot = (nextSlot + x) % availableSlots();
        }
    }

    private boolean craftOnly() {
        return isCraftingEnabled() && craftOnly == YesNo.YES;
    }

    private boolean isCraftingEnabled() {
        return isUpgradedWith(UpgradeCard.CRAFTING);
    }

    /** Round-robin position (for debugging/tests). */
    public int getNextSlot() {
        return nextSlot;
    }

    @Override
    protected Map<String, Object> writeJsonMap() {
        var map = super.writeJsonMap();
        map.put("sched", schedulingMode.name());
        map.put("craftOnly", craftOnly == YesNo.YES);
        map.put("nextSlot", nextSlot);
        return map;
    }

    @Override
    protected void readJsonMap(Map<String, Object> map) {
        super.readJsonMap(map);
        schedulingMode = MiniJson.getEnum(map, "sched", SchedulingMode.class, SchedulingMode.DEFAULT);
        craftOnly = MiniJson.getBool(map, "craftOnly", false) ? YesNo.YES : YesNo.NO;
        nextSlot = MiniJson.getInt(map, "nextSlot", 0);
    }
}
