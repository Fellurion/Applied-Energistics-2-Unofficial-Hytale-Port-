/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.storage.DriveBlockEntity / MEChestBlockEntity).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * ME Drive / ME Chest: Speicherzellen liegen als Items im Hytale-Container des Blocks; ihr Inhalt
 * ({@link CellItemData}) steht als JSON in den Item-Metadaten unter {@link #CELL_KEY}.
 */
public final class CellHostDevice implements Device {

    /** Metadaten-Schlüssel für den Zelleninhalt. */
    public static final String CELL_KEY = "AE2Cell";

    private final CellHostLogic logic;
    @Nullable
    private ItemContainer container;
    private final Slot[] slots;
    @Nullable
    private WorldNetworks nets;
    @Nullable
    private com.hypixel.hytale.event.EventRegistration<?, ?> changeReg;
    /** Schutz gegen Rekursion: eigene Schreibvorgänge lösen das Container-Ereignis erneut aus. */
    private boolean busy;

    private static final class Slot {
        @Nullable
        ItemStack known;
        @Nullable
        String writtenJson;
        @Nullable
        CellItemData data;
        @Nullable
        StorageCell cell;
        boolean dirty;
        /** Tick der letzten Tooltip-Aktualisierung; bei Dauerverkehr nur 1×/s (der Inhalt selbst jeden Tick). */
        long tooltipTick = -1_000_000L;
        boolean tooltipStale;
    }

    /** Tooltip-Aktualisierung bei laufendem Itemverkehr höchstens alle 20 Ticks (1 s). */
    private static final int TOOLTIP_INTERVAL = 20;

    public CellHostDevice(CellHostLogic logic) {
        this.logic = logic;
        this.slots = new Slot[logic.getSlots()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
    }

    public CellHostLogic logic() {
        return logic;
    }

    @Nullable
    public ItemContainer container() {
        return container;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        logic.setPriority(node.comp.priority());
        this.nets = nets;
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container != null) {
            // Zelle rein/raus sofort übernehmen, nicht erst im nächsten Tick (sonst wäre eine entnommene Zelle
            // bis dahin noch im Netz und Terminal-Zugriffe gingen auf eine Zelle, die der Spieler schon hält)
            changeReg = container.registerChangeEvent(e -> {
                if (!busy) {
                    sync();
                }
            });
            // In Drive/ME Chest passen nur Speicherzellen (AE2: DriveBlockEntity.CellValidInventoryFilter)
            for (short s = 0; s < Math.min(slots.length, container.getCapacity()); s++) {
                container.setSlotFilter(FilterActionType.ADD, s,
                        (action, c, slot, stack) -> stack == null || stack.isEmpty() || StorageCells.isCellItem(stack.getItemId()));
            }
        }
        sync();
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        sync();
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        flush();
        refreshStaleTooltips();
    }

    /** Zellen, deren Verkehr gerade aufgehört hat, bekommen den aktuellen Tooltip. */
    private void refreshStaleTooltips() {
        if (container == null || busy) {
            return;
        }
        busy = true;
        try {
            for (int i = 0; i < slots.length && i < container.getCapacity(); i++) {
                Slot s = slots[i];
                if (!s.tooltipStale || s.dirty || s.cell == null || s.data == null || s.known == null
                        || container.getItemStack((short) i) != s.known) {
                    continue;
                }
                long now = nets == null ? 0 : nets.tickCount();
                if (now - s.tooltipTick < 2) {
                    continue; // noch in Bewegung (gerade erst geschrieben)
                }
                ItemStack updated = CellTooltips.apply(s.known, s.data, s.cell);
                s.tooltipStale = false;
                s.tooltipTick = now;
                if (updated != s.known) {
                    container.setItemStackForSlot((short) i, updated);
                    s.known = updated;
                    s.writtenJson = meta(updated);
                }
            }
        } finally {
            busy = false;
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (changeReg != null) {
            try {
                changeReg.unregister();
            } catch (RuntimeException ignored) {
                // Container schon weg
            }
            changeReg = null;
        }
        for (Slot s : slots) {
            if (s.cell != null) {
                s.dirty = true;
            }
        }
        flush();
    }

    /** Hytale → Kern: neue/entfernte Zellen erkennen. */
    private void sync() {
        if (container == null || busy) {
            return;
        }
        busy = true;
        try {
            syncInner();
        } finally {
            busy = false;
        }
    }

    private void syncInner() {
        flushInner();
        compact();
        for (int i = 0; i < slots.length && i < container.getCapacity(); i++) {
            Slot s = slots[i];
            ItemStack stack = container.getItemStack((short) i);
            if (stack != null && stack.isEmpty()) {
                stack = null;
            }
            if (stack == s.known) {
                continue;
            }
            if (stack != null && s.known != null && stack.getItemId().equals(s.known.getItemId())
                    && Objects.equals(meta(stack), s.writtenJson)) {
                s.known = stack; // gleiche Zelle, nur neues Stack-Objekt
                continue;
            }
            // Zelle gewechselt
            if (s.cell != null) {
                s.cell.persist();
            }
            s.cell = null;
            s.data = null;
            s.dirty = false;
            s.known = stack;
            s.writtenJson = stack == null ? null : meta(stack);
            StorageCell cell = null;
            if (stack != null && StorageCells.isCellItem(stack.getItemId())) {
                CellItemData data = CellItemData.fromJson(s.writtenJson);
                final Slot slot = s;
                cell = StorageCells.getCellInventory(stack.getItemId(), data, () -> {
                    slot.dirty = true;
                    // Spieleraktion zwischen zwei Ticks: sofort ins Item schreiben
                    if (this.nets != null && !this.nets.isTicking()) {
                        flush();
                    }
                });
                s.data = data;
                s.cell = cell;
            }
            logic.setCell(i, cell);
        }
    }

    /**
     * Drive: Zellen nach vorne rücken (Slot 0..n-1), damit die sichtbaren Schächte ({@code Cells_<n>}) genau
     * der Belegung entsprechen. AE2 lässt Lücken zu; in Hytale gibt es dafür keine Darstellung.
     */
    private void compact() {
        if (slots.length < 2) {
            return;
        }
        int cap = Math.min(slots.length, container.getCapacity());
        int free = 0;
        for (int i = 0; i < cap; i++) {
            ItemStack st = container.getItemStack((short) i);
            if (st == null || st.isEmpty()) {
                continue;
            }
            if (i != free) {
                container.setItemStackForSlot((short) free, st);
                container.removeItemStackFromSlot((short) i);
            }
            free++;
        }
    }

    /** Anzahl belegter Zellenslots. */
    public int cellCount() {
        int n = 0;
        for (Slot s : slots) {
            if (s.cell != null) {
                n++;
            }
        }
        return n;
    }

    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        int n = cellCount();
        boolean online = node.gridNode() != null && node.gridNode().isOnline();
        if (slots.length == 1) {
            // ME Chest
            if (n > 0) {
                return online ? "Cell_Online" : "Cell";
            }
            return online ? "Online" : "default";
        }
        if (n == 0) {
            return "default";
        }
        return "Cells_" + n + (online ? "_Online" : "");
    }

    /** Kern → Hytale: geänderte Zelleninhalte in die Item-Metadaten zurückschreiben. */
    private void flush() {
        if (container == null || busy) {
            return;
        }
        busy = true;
        try {
            flushInner();
        } finally {
            busy = false;
        }
    }

    private void flushInner() {
        for (int i = 0; i < slots.length && i < container.getCapacity(); i++) {
            Slot s = slots[i];
            if (!s.dirty || s.cell == null || s.data == null || s.known == null) {
                continue;
            }
            if (container.getItemStack((short) i) != s.known) {
                continue; // Slot wurde verändert – sync() übernimmt (nie eine fremde/entnommene Zelle überschreiben)
            }
            s.dirty = false;
            s.cell.persist();
            long now = nets == null ? 0 : nets.tickCount();
            boolean inTick = nets != null && nets.isTicking();
            boolean tooltip = !inTick || now - s.tooltipTick >= TOOLTIP_INTERVAL;
            ItemStack updated = CellTooltips.write(s.known, s.data, s.cell, tooltip);
            if (tooltip) {
                s.tooltipTick = now;
                s.tooltipStale = false;
            } else {
                s.tooltipStale = true;
            }
            container.setItemStackForSlot((short) i, updated);
            s.known = updated;
            s.writtenJson = meta(updated);
        }
    }

    @Nullable
    private static String meta(ItemStack stack) {
        return stack.getFromMetadataOrNull(CELL_KEY, Codec.STRING);
    }
}
