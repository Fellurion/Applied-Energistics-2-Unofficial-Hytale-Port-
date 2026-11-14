/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.cells;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The upgrade slots of a machine, part or item (one card per slot). Port of {@code appeng.api.upgrades.UpgradeInventory}
 * ({@code MachineUpgradeInventory} / {@code ItemUpgradeInventory}).
 * <p>
 * Only cards supported by the host ({@link Upgrades}) can be inserted, up to the max count. The installed count of a
 * card is capped at the host's maximum.
 */
public final class UpgradeInventory {
    private final String hostItemId;
    private final UpgradeCard[] slots;
    private Runnable changeListener;

    public UpgradeInventory(String hostItemId, int slots) {
        this.hostItemId = Objects.requireNonNull(hostItemId);
        this.slots = new UpgradeCard[slots];
    }

    public UpgradeInventory(String hostItemId, int slots, Runnable changeListener) {
        this(hostItemId, slots);
        this.changeListener = changeListener;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    public String getHostItemId() {
        return hostItemId;
    }

    public int size() {
        return slots.length;
    }

    public UpgradeCard get(int slot) {
        return slots[slot];
    }

    public int getMaxInstalled(UpgradeCard card) {
        return Upgrades.getMaxInstallable(card, hostItemId);
    }

    /** Number of installed cards of this kind (capped at the host's maximum). */
    public int getInstalledUpgrades(UpgradeCard card) {
        int count = 0;
        for (var c : slots) {
            if (c == card) {
                count++;
            }
        }
        return Math.min(count, getMaxInstalled(card));
    }

    public boolean isInstalled(UpgradeCard card) {
        return getInstalledUpgrades(card) > 0;
    }

    /** Whether one more of this card may be inserted. */
    public boolean canInsert(UpgradeCard card) {
        return card != null && getInstalledUpgrades(card) < getMaxInstalled(card) && firstFreeSlot() >= 0;
    }

    /** Inserts a card into the first free slot. @return true on success */
    public boolean insert(UpgradeCard card) {
        if (!canInsert(card)) {
            return false;
        }
        slots[firstFreeSlot()] = card;
        changed();
        return true;
    }

    /** Sets a slot directly (no validation; used for loading / UI slots). */
    public void set(int slot, UpgradeCard card) {
        slots[slot] = card;
        changed();
    }

    /** Removes and returns the card in a slot. */
    public UpgradeCard remove(int slot) {
        var c = slots[slot];
        slots[slot] = null;
        if (c != null) {
            changed();
        }
        return c;
    }

    public void clear() {
        Arrays.fill(slots, null);
        changed();
    }

    private int firstFreeSlot() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /** Sum of energy card multipliers ({@code Upgrades.getEnergyCardMultiplier}). */
    public int getEnergyCardMultiplier() {
        int m = 0;
        for (var c : slots) {
            if (c != null) {
                m += c.energyMultiplier();
            }
        }
        return m;
    }

    /** Card item ids per slot (null for empty), for serialization. */
    public List<String> toItemIds() {
        var list = new ArrayList<String>(slots.length);
        for (var c : slots) {
            list.add(c == null ? null : c.itemId);
        }
        return list;
    }

    /** Loads card item ids (unknown ids are dropped). */
    public void fromItemIds(List<?> ids) {
        Arrays.fill(slots, null);
        for (int i = 0; i < Math.min(ids.size(), slots.length); i++) {
            var o = ids.get(i);
            slots[i] = o == null ? null : UpgradeCard.byItemId(String.valueOf(o));
        }
        changed();
    }

    private void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }
}
