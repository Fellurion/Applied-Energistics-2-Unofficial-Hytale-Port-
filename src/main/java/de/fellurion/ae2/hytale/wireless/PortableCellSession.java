/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.contents.PortableCellMenuHost, appeng.items.tools.powered.AbstractPortableCell).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.wireless.PortableCellHost;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CellTooltips;
import de.fellurion.ae2.hytale.items.PoweredItems;

import javax.annotation.Nullable;

/**
 * Eine in der Hand geöffnete tragbare Zelle: der Zelleninhalt aus den Metadaten des Items als Lager, bezahlt aus dem
 * Akku des Items (Kern: {@link PortableCellHost}).
 *
 * <p><b>Der Inhalt steht immer im Item:</b> Jede Entnahme und jedes Einlagern schreibt den neuen Inhalt sofort in das
 * Item zurück ({@link #commit}). Vor jeder Aktion wird geprüft, dass das Item noch an seinem Platz liegt
 * ({@link #live}); lässt es sich danach nicht schreiben, wird die Aktion verworfen und der Stand aus dem Item neu
 * geladen ({@link #reload}) – der Aufrufer bekommt dann 0 zurück und bewegt nichts. So gibt es den Inhalt zu jedem
 * Zeitpunkt genau einmal, auch wenn die Zelle verschoben, fallen gelassen oder in ein Laufwerk gelegt wird.
 *
 * <p>Tragbare Fluid-Zellen laufen über dieselbe Sitzung; Akku, Karten und Öffnen sind dieselben.
 */
final class PortableCellSession extends HeldItemSession {

    private final String itemId;
    private CellItemData data;
    @Nullable
    private StorageCell cell;
    private final PortableCellHost host;
    /** Zählt jede Änderung am Inhalt (für die automatische Aktualisierung der Ansicht). */
    private long changes;

    PortableCellSession(@Nullable PlayerRef playerRef, ItemPlace place, int section, short slot, ItemStack stack) {
        super(playerRef, place, section, slot, stack);
        this.itemId = stack.getItemId();
        load(stack);
        this.host = new PortableCellHost(this, () -> cell);
    }

    private void load(ItemStack stack) {
        data = CellItemData.fromJson(stack.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
        // ohne Speicher-Rückruf: jede Änderung steht sofort in data
        cell = StorageCells.getCellInventory(itemId, data, null);
        power = Math.min(capacity(), PoweredItems.stored(stack));
    }

    /** Verwirft den Stand im Speicher und lädt ihn aus dem Item, wie es zuletzt geschrieben wurde. */
    private void reload() {
        load(known);
    }

    @Override
    PortableCellHost host() {
        return host;
    }

    @Nullable
    StorageCell cell() {
        return cell;
    }

    long changes() {
        return changes;
    }

    @Override
    ItemStack render() {
        if (cell != null) {
            cell.persist();
        }
        double stored = Math.max(0, Math.min(capacity(), power));
        ItemStack charged = known.withMetadata(PoweredItems.KEY, Codec.DOUBLE, stored <= 0 ? null : stored);
        return CellTooltips.write(charged, data, cell, true);
    }

    /**
     * Lagert in die Zelle ein (1 AE je Item aus dem Akku).
     *
     * @return die eingelagerte Menge; 0, wenn die Zelle nicht mehr an ihrem Platz liegt oder der Akku leer ist
     */
    long insert(AEKey what, long amount, IActionSource source) {
        if (amount <= 0 || !live()) {
            return 0;
        }
        long inserted = host.insert(what, amount, Actionable.MODULATE, source);
        if (inserted <= 0) {
            return 0;
        }
        if (!commit()) {
            reload();
            return 0;
        }
        changes++;
        return inserted;
    }

    /**
     * Entnimmt aus der Zelle (1 AE je Item aus dem Akku).
     *
     * @return die entnommene Menge; 0, wenn die Zelle nicht mehr an ihrem Platz liegt oder der Akku leer ist
     */
    long extract(AEKey what, long amount, IActionSource source) {
        if (amount <= 0 || !live()) {
            return 0;
        }
        long extracted = host.extract(what, amount, Actionable.MODULATE, source);
        if (extracted <= 0) {
            return 0;
        }
        if (!commit()) {
            reload();
            return 0;
        }
        changes++;
        return extracted;
    }
}
