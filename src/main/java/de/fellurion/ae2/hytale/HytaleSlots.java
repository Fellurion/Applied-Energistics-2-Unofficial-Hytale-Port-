/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.stacks.AEItemKey;

import java.util.function.BiPredicate;

/** {@link SlotAccess} (Kern) über einem Hytale-{@link ItemContainer}. {@code null} = leerer Slot. */
public final class HytaleSlots implements SlotAccess {

    private final ItemContainer container;
    private final int offset;
    private final int size;
    private final BiPredicate<Integer, SimpleStack> validator;

    public HytaleSlots(ItemContainer container) {
        this(container, 0, container.getCapacity(), (slot, stack) -> true);
    }

    public HytaleSlots(ItemContainer container, int offset, int size, BiPredicate<Integer, SimpleStack> validator) {
        this.container = container;
        this.offset = offset;
        this.size = Math.max(0, Math.min(size, container.getCapacity() - offset));
        this.validator = validator;
    }

    public ItemContainer container() {
        return container;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public SimpleStack get(int slot) {
        if (slot < 0 || slot >= size) {
            return null;
        }
        ItemStack s = container.getItemStack((short) (offset + slot));
        if (s == null || s.isEmpty() || s.getQuantity() <= 0) {
            return null;
        }
        AEItemKey key = ItemKeys.of(s);
        return key == null ? null : new SimpleStack(key.itemId(), key.nbt(), s.getQuantity());
    }

    @Override
    public void set(int slot, SimpleStack stack) {
        if (slot < 0 || slot >= size) {
            return;
        }
        short s = (short) (offset + slot);
        if (stack == null || stack.count() <= 0) {
            ItemStack cur = container.getItemStack(s);
            if (cur != null && !cur.isEmpty()) {
                container.removeItemStackFromSlot(s);
            }
            return;
        }
        container.setItemStackForSlot(s, ItemKeys.toStack(stack.key(), stack.count()));
    }

    @Override
    public int getMaxStackSize(int slot, String itemId) {
        return ItemKeys.maxStack(itemId);
    }

    @Override
    public boolean isItemValid(int slot, SimpleStack stack) {
        if (!validator.test(slot, stack)) {
            return false;
        }
        if (stack == null || slot < 0 || slot >= size) {
            return true;
        }
        // Auch der Slot-Filter des Containers entscheidet (z. B. nimmt ein Drive nur Zellen): sonst würde set()
        // stillschweigend scheitern und das Item wäre verloren.
        try {
            short s = (short) (offset + slot);
            ItemStack current = container.getItemStack(s);
            if (current != null && !current.isEmpty()) {
                return true; // belegter Slot: ob gestapelt werden kann, prüft der Kern über get()
            }
            return container.canAddItemStackToSlot(s, ItemKeys.toStack(stack.key(), 1), false, true);
        } catch (RuntimeException e) {
            return true;
        }
    }
}
