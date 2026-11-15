/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.automation.ExternalStorage;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;

/**
 * Sky Stone Tank: holds up to {@value #BUCKET_CAPACITY} buckets of one fluid. Port of
 * {@code appeng.blockentity.storage.SkyStoneTankBlockEntity} (a single-tank fluid handler of
 * {@code BUCKET_VOLUME * BUCKET_CAPACITY}) together with the fluid side of
 * {@code appeng.me.storage.ExternalStorageFacade}, which is what buses and P2P tunnels see of it.
 * <ul>
 * <li>An empty tank takes any fluid, a filled one only more of the same fluid, up to the capacity.</li>
 * <li>Fluid can be taken out in any amount; a tank drained to zero is empty again and forgets its fluid.</li>
 * <li>{@link #fillFromContainer} / {@link #drainIntoContainer} move the content of a container item (a bucket) as a
 * whole or not at all, like {@code FluidUtil.interactWithFluidHandler} does for a bucket.</li>
 * </ul>
 * The tank has no grid node. As in AE2 the content is not kept when the block is broken.
 */
public final class SkyStoneTankLogic {
    /** AE2: {@code SkyStoneTankBlockEntity.BUCKET_CAPACITY}. */
    public static final int BUCKET_CAPACITY = 16;

    private AEFluidKey fluid;
    private long amount;
    private Runnable changeListener;
    private final ExternalStorage storage = new TankStorage();

    /** Called after every change of the content (AE2: {@code onContentsChanged}). */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** Capacity in mB. */
    public long getCapacity() {
        return (long) BUCKET_CAPACITY * AEFluidKey.AMOUNT_BUCKET;
    }

    /** The stored fluid, or null if the tank is empty. */
    public AEFluidKey getFluid() {
        return fluid;
    }

    /** The stored amount in mB. */
    public long getAmount() {
        return amount;
    }

    public boolean isEmpty() {
        return amount <= 0;
    }

    /** Fill level between 0 and 1. */
    public double getFillFraction() {
        return amount / (double) getCapacity();
    }

    /**
     * Fills the tank.
     *
     * @return the amount (mB) accepted
     */
    public long insert(AEFluidKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0 || (fluid != null && !fluid.equals(what))) {
            return 0;
        }
        long accepted = Math.min(amount, getCapacity() - this.amount);
        if (accepted <= 0) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            this.fluid = what;
            this.amount += accepted;
            changed();
        }
        return accepted;
    }

    /**
     * Drains the tank.
     *
     * @return the amount (mB) taken out
     */
    public long extract(AEFluidKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0 || fluid == null || !fluid.equals(what)) {
            return 0;
        }
        long taken = Math.min(amount, this.amount);
        if (taken <= 0) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            this.amount -= taken;
            if (this.amount <= 0) {
                this.amount = 0;
                this.fluid = null;
            }
            changed();
        }
        return taken;
    }

    /**
     * Empties a container item into the tank, as a whole or not at all.
     *
     * @param what            content of the container
     * @param containerAmount content of the container in mB
     * @return true if the tank took everything (the container is empty now)
     */
    public boolean fillFromContainer(AEFluidKey what, long containerAmount) {
        if (containerAmount <= 0 || insert(what, containerAmount, Actionable.SIMULATE) != containerAmount) {
            return false;
        }
        return insert(what, containerAmount, Actionable.MODULATE) == containerAmount;
    }

    /**
     * Fills a container item from the tank, as a whole or not at all.
     *
     * @param containerAmount how much the container holds in mB
     * @return the fluid the container holds now, or null if the tank does not hold that much
     */
    public AEFluidKey drainIntoContainer(long containerAmount) {
        var what = fluid;
        if (what == null || containerAmount <= 0 || extract(what, containerAmount, Actionable.SIMULATE) != containerAmount) {
            return null;
        }
        return extract(what, containerAmount, Actionable.MODULATE) == containerAmount ? what : null;
    }

    /** Replaces the content (creative / debug). Amounts are clamped to the capacity; null or 0 empties the tank. */
    public void set(AEFluidKey what, long amount) {
        long clamped = what == null ? 0 : Math.max(0, Math.min(amount, getCapacity()));
        this.fluid = clamped > 0 ? what : null;
        this.amount = clamped;
        changed();
    }

    /**
     * What a bus or tunnel next to the tank sees: one slot of fluid storage. Always the same object, so that a bus
     * recognises its target.
     */
    public ExternalStorage getStorage() {
        return storage;
    }

    private void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        if (fluid != null && amount > 0) {
            map.put("fluid", fluid.fluidId());
            map.put("amount", amount);
        }
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        fluid = null;
        amount = 0;
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        var id = MiniJson.getString(map, "fluid", null);
        long stored = Math.max(0, Math.min(MiniJson.getLong(map, "amount", 0), getCapacity()));
        if (id != null && !id.isEmpty() && stored > 0) {
            fluid = AEFluidKey.of(id);
            amount = stored;
        }
    }

    /** Port of the fluid-handler side of {@code appeng.me.storage.ExternalStorageFacade}. */
    private final class TankStorage implements ExternalStorage {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public GenericStack getStackInSlot(int slot) {
            return slot != 0 || fluid == null || amount <= 0 ? null : new GenericStack(fluid, amount);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            return what instanceof AEFluidKey key ? SkyStoneTankLogic.this.insert(key, amount, mode) : 0;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            return what instanceof AEFluidKey key ? SkyStoneTankLogic.this.extract(key, amount, mode) : 0;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (fluid != null && amount > 0) {
                out.add(fluid, amount);
            }
        }

        @Override
        public String getDescription() {
            return "AE2_Sky_Stone_Tank";
        }
    }
}
