/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.cells.StorageCellTier;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.CondenserOutput;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;

/**
 * Matter condenser. Port of {@code appeng.blockentity.misc.CondenserBlockEntity} and
 * {@code CondenserMEStorage}.
 * <ul>
 * <li>Every inserted item adds 1 "power" (fluids: 1 per 125 mB = amountPerOperation); the stored power is clamped to
 * the storage of the component in the component slot: {@code componentBytes · 8} (1k component: 8192).</li>
 * <li>Output mode {@link CondenserOutput}: TRASH only voids, MATTER_BALLS costs 256 per matter ball, SINGULARITY
 * 256,000 per singularity (values from {@code AEConfig}).</li>
 * <li>Inputs are only accepted (voided) while the output slot can take another output item.</li>
 * </ul>
 * Slots: {@link #SLOT_INPUT} (inbox: whatever is put there is condensed on {@link #onInventoryChanged()} /
 * {@link #tick()}), {@link #SLOT_COMPONENT}, {@link #SLOT_OUTPUT}.
 * <p>
 * Difference to AE2: the input slot is a real slot here (AE2's input slot is a virtual "void" slot that never holds
 * items), so the condenser has three slots.
 */
public final class CondenserLogic {
    public static final int SLOT_INPUT = 0;
    public static final int SLOT_COMPONENT = 1;
    public static final int SLOT_OUTPUT = 2;
    public static final int BYTE_MULTIPLIER = 8;
    public static final String MATTER_BALL_ID = "AE2_Matter_Ball";
    public static final String SINGULARITY_ID = "AE2_Singularity";

    private final SlotAccess inv;
    private CondenserOutput mode = CondenserOutput.TRASH;
    private double storedPower = 0;
    private final MEStorage meStorage = new CondenserMEStorage();

    public CondenserLogic(SlotAccess inv) {
        if (inv.size() < 3) {
            throw new IllegalArgumentException("Condenser needs 3 slots (input, component, output)");
        }
        this.inv = inv;
    }

    public CondenserOutput getMode() {
        return mode;
    }

    public void setMode(CondenserOutput mode) {
        this.mode = mode;
        addPower(0);
    }

    public double getStoredPower() {
        return storedPower;
    }

    /** Storage capacity provided by the component: bytes · 8, or 0 without a component. */
    public double getStorage() {
        var is = inv.get(SLOT_COMPONENT);
        if (is != null) {
            for (var tier : StorageCellTier.ALL) {
                if (is.is(tier.componentItemId())) {
                    return (double) tier.bytes() * BYTE_MULTIPLIER;
                }
            }
        }
        return 0;
    }

    public double getRequiredPower() {
        return mode.requiredPower();
    }

    /** Adds raw power (clamped to the storage) and fills the output slot. */
    public void addPower(double rawPower) {
        storedPower = Math.max(0.0, Math.min(getStorage(), storedPower + rawPower));
        fillOutput();
    }

    private void fillOutput() {
        var requiredPower = getRequiredPower();
        var outputId = getOutputItemId();
        if (requiredPower <= 0 || outputId == null) {
            return;
        }
        var key = AEItemKey.of(outputId);
        // Produce as many items as the stored power allows and the output slot can hold in one go
        var amount = (int) Math.min(storedPower / requiredPower, inv.getMaxStackSize(SLOT_OUTPUT, outputId));
        if (amount <= 0) {
            return;
        }
        amount = SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, key, amount, true);
        if (amount > 0) {
            storedPower -= requiredPower * amount;
            SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, key, amount, false);
        }
    }

    private String getOutputItemId() {
        return switch (mode) {
            case MATTER_BALLS -> MATTER_BALL_ID;
            case SINGULARITY -> SINGULARITY_ID;
            default -> null;
        };
    }

    /** True if the output slot can take one more output item (TRASH: always). */
    public boolean canAddOutput() {
        var outputId = getOutputItemId();
        if (outputId == null) {
            return true;
        }
        return SlotInventories.insertIntoSlot(inv, SLOT_OUTPUT, AEItemKey.of(outputId), 1, true) == 1;
    }

    /**
     * Condenses items (automation / hopper-like insertion). AE2's virtual input slot.
     *
     * @return the number of items accepted (all or nothing)
     */
    public int insertItems(SimpleStack stack, boolean simulate) {
        if (stack == null || !canAddOutput()) {
            return 0;
        }
        if (!simulate) {
            addPower(stack.count());
        }
        return stack.count();
    }

    /**
     * Condenses fluid ({@code CondenseResourceHandler}: at most one bucket per operation, 1 power per 125 mB).
     *
     * @return the amount (mB) accepted
     */
    public long insertFluid(AEFluidKey fluid, long amount, boolean simulate) {
        var accepted = Math.min(AEFluidKey.AMOUNT_BUCKET, amount);
        if (!simulate && accepted > 0) {
            addPower(accepted * (1.0 / fluid.getAmountPerOperation()));
        }
        return accepted;
    }

    /** Condenses the content of the input slot (call after the container changed). */
    public void onInventoryChanged() {
        var input = inv.get(SLOT_INPUT);
        if (input != null && canAddOutput()) {
            inv.set(SLOT_INPUT, null);
            addPower(input.count());
        } else {
            fillOutput();
        }
    }

    /** Optional periodic call; equivalent to {@link #onInventoryChanged()}. */
    public void tick() {
        onInventoryChanged();
    }

    /** ME facade (for storage buses / annihilation): voids inserts, exposes the output slot. */
    public MEStorage getMEStorage() {
        return meStorage;
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("power", storedPower);
        map.put("mode", mode.name());
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        storedPower = MiniJson.getDouble(map, "power", 0);
        mode = MiniJson.getEnum(map, "mode", CondenserOutput.class, CondenserOutput.TRASH);
    }

    /** Port of {@code appeng.blockentity.misc.CondenserMEStorage}. */
    private final class CondenserMEStorage implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            if (!canAddOutput()) {
                return 0;
            }
            if (mode == Actionable.MODULATE) {
                addPower(amount / what.getAmountPerOperation());
            }
            return amount;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            var slotItem = inv.get(SLOT_OUTPUT);
            if (what instanceof AEItemKey itemKey && slotItem != null && slotItem.matches(itemKey)) {
                int count = (int) Math.min(amount, Integer.MAX_VALUE);
                return SlotInventories.extractFromSlot(inv, SLOT_OUTPUT, count, mode == Actionable.SIMULATE);
            }
            return 0;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            var stack = inv.get(SLOT_OUTPUT);
            if (stack != null) {
                out.add(stack.key(), stack.count());
            }
        }

        @Override
        public String getDescription() {
            return "AE2_Condenser";
        }
    }
}
