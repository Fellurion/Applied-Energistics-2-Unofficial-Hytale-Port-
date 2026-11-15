/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.common.MEStorageMenu mit appeng.items.contents.PortableCellMenuHost als Host).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.wireless.PortableTerminalHost;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.fluid.FluidTypes;
import de.fellurion.ae2.hytale.ui.TerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Terminal-Zugriff auf den Inhalt einer tragbaren Zelle in der Hand. Die Zelle gehört zu keinem Netz: es gibt weder
 * Besitzer noch Autocrafting, und bezahlt wird aus ihrem Akku ({@link PortableCellSession}). Eine tragbare
 * Fluid-Zelle zeigt und bewegt ihre Flüssigkeiten ({@link #fluids}, {@link #extractFluid}, {@link #insertFluid});
 * Items nimmt sie nicht an.
 */
final class PortableCellBackend implements TerminalBackend {

    private final PortableCellSession session;
    private final IActionSource source;
    private final Map<String, AEItemKey> keys = new HashMap<>();

    PortableCellBackend(PortableCellSession session, @Nullable Object player) {
        this.session = session;
        this.source = IActionSource.ofPlayer(player);
    }

    @Override
    public boolean onOpen(PlayerRef playerRef) {
        return session.open();
    }

    @Override
    public void onClose() {
        session.close();
    }

    @Override
    public boolean isOnline() {
        return session.cell() != null && session.live();
    }

    @Override
    public String statusText() {
        Status s = status();
        if (s == null || !s.online()) {
            return "Offline";
        }
        return String.format(Locale.ROOT, "Online · %s / %s AE · %.1f AE/t",
                TerminalTexts.formatAmount((long) s.storedPower()), TerminalTexts.formatAmount((long) s.maxPower()),
                s.avgUsage());
    }

    @Override
    public String storageText() {
        Status s = status();
        if (s == null || !s.online()) {
            return "";
        }
        return s.types() + " types · " + TerminalTexts.formatAmount(s.items()) + " Items";
    }

    /** Kopfzeile: Akku der Zelle und ihr Leerlaufverbrauch (AE2: 0,5 AE/t, solange sie offen ist). */
    @Override
    @Nullable
    public Status status() {
        StorageCell cell = session.cell();
        if (cell == null || !isOnline()) {
            return new Status(false, 0, 0, 0, 0, 0);
        }
        int types = 0;
        long items = 0;
        long fluid = 0;
        for (KeyCounter.Entry e : cell.getAvailableStacks()) {
            types++;
            if (e.getKey() instanceof AEFluidKey) {
                fluid += e.getLongValue();
            } else {
                items += e.getLongValue();
            }
        }
        return new Status(true, session.getAECurrentPower(), session.capacity(), idleDrain(), types, items, fluid);
    }

    private double idleDrain() {
        PortableTerminalHost host = session.host();
        return host.isCreative() ? 0 : 0.5;
    }

    @Override
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        keys.clear();
        StorageCell cell = session.cell();
        if (cell == null) {
            return out;
        }
        for (KeyCounter.Entry e : cell.getAvailableStacks()) {
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEItemKey key)) {
                continue;
            }
            String id = key.toString();
            keys.put(id, key);
            try {
                out.add(new Entry(id, ItemKeys.toStack(key, 1), e.getLongValue(), false));
            } catch (RuntimeException ignored) {
                // Item existiert nicht mehr (z. B. Mod entfernt) – nicht anzeigen
            }
        }
        return out;
    }

    @Override
    public List<Entry> fluids() {
        List<Entry> out = new ArrayList<>();
        StorageCell cell = session.cell();
        if (cell == null) {
            return out;
        }
        for (KeyCounter.Entry e : cell.getAvailableStacks()) {
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEFluidKey key)) {
                continue;
            }
            try {
                out.add(Entry.ofFluid(TerminalBackend.fluidKey(key.fluidId()), key.fluidId(),
                        FluidTypes.displayStack(key.fluidId()), e.getLongValue()));
            } catch (RuntimeException ignored) {
                // kein Item zum Anzeigen – nicht anzeigen
            }
        }
        return out;
    }

    @Override
    public long extractFluid(String keyId, long amount, boolean simulate) {
        String fluidId = TerminalBackend.fluidIdOf(keyId);
        if (fluidId == null || amount <= 0) {
            return 0;
        }
        AEFluidKey key = AEFluidKey.of(fluidId);
        if (simulate) {
            return isOnline() ? session.host().extract(key, amount, Actionable.SIMULATE, source) : 0;
        }
        return session.extract(key, amount, source);
    }

    @Override
    public long insertFluid(String fluidId, long amount, boolean simulate) {
        AEFluidKey key = FluidTypes.key(fluidId);
        if (key == null || amount <= 0) {
            return 0;
        }
        if (simulate) {
            return isOnline() ? session.host().insert(key, amount, Actionable.SIMULATE, source) : 0;
        }
        return session.insert(key, amount, source);
    }

    @Override
    public long insert(ItemStack stack) {
        AEItemKey key = ItemKeys.of(stack);
        if (key == null) {
            return 0;
        }
        return session.insert(key, stack.getQuantity(), source);
    }

    @Override
    @Nullable
    public ItemStack extract(String keyId, int amount) {
        AEItemKey key = keys.get(keyId);
        if (key == null || amount <= 0) {
            return null;
        }
        long got = session.extract(key, amount, source);
        return got <= 0 ? null : ItemKeys.toStack(key, (int) got);
    }

    @Override
    public boolean canExtract(Map<String, Long> amounts) {
        StorageCell cell = session.cell();
        if (cell == null || !isOnline()) {
            return false;
        }
        double total = 0;
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            AEItemKey key = keys.get(e.getKey());
            long amount = e.getValue() == null ? 0 : e.getValue();
            if (amount <= 0) {
                continue;
            }
            if (key == null || cell.extract(key, amount, Actionable.SIMULATE, source) < amount) {
                return false;
            }
            total += amount / Math.max(1.0, key.getAmountPerOperation());
        }
        return total <= 0 || session.host().extractAEPower(total, Actionable.SIMULATE, PowerMultiplier.CONFIG)
                >= total - 0.01;
    }

    @Override
    public long revision() {
        // neu zeichnen bei jeder Änderung am Inhalt, beim Verbindungsverlust und je Prozent Ladung
        double capacity = session.capacity();
        long percent = capacity <= 0 ? 0 : (long) Math.floor(session.getAECurrentPower() * 100 / capacity);
        return (session.changes() * 31 + percent) * 2 + (isOnline() ? 1 : 0);
    }
}
