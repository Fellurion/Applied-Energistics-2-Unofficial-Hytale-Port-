/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.storage.IOPortBlockEntity, appeng.menu.implementations.IOPortMenu).
 */
package de.fellurion.ae2.hytale.ioport;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.automation.IOPortLogic;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.ISaveProvider;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.FullnessMode;
import de.fellurion.ae2.core.config.OperationMode;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CellTooltips;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.Drops;
import de.fellurion.ae2.hytale.device.ExtractRules;
import de.fellurion.ae2.hytale.device.Watchers;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.ui.ConfigHost;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ME IO Port: sechs Eingangs- und sechs Ausgangsplätze im Hytale-Container des Blocks (Plätze 0–5 und 6–11). Was mit
 * den Zellen geschieht, entscheidet {@link IOPortLogic}; dieses Gerät hält zu jeder Zelle im Eingang ihr Inventar,
 * schreibt deren Inhalt zurück ins Item ({@link CellHostDevice#CELL_KEY}, {@link CellTooltips}) und verschiebt fertige
 * Zellen in den Ausgang.
 *
 * <ul>
 *   <li>Eingang: nimmt nur Speicherzellen an. Ausgang: nichts lässt sich hineinlegen, nur herausnehmen.</li>
 *   <li>Busse entnehmen nur aus dem Ausgang ({@link ExtractRules}); Spieler dürfen auch eine Zelle aus dem Eingang
 *   nehmen.</li>
 *   <li>Der Inhalt einer Zelle steht nach jedem Takt, in dem der Port sie verändert hat, schon im Item – eine Zelle,
 *   die den Platz verlässt (Spieler, Abbau), trägt immer ihren aktuellen Stand.</li>
 *   <li>Einstellungen und Upgrade-Karten: {@code hytale.ui.DeviceConfigPage} über {@link ConfigHost} (geduckt
 *   benutzen); der Block selbst öffnet beim Benutzen sein Slot-Fenster.</li>
 * </ul>
 *
 * <p>Abweichungen von AE2: Dort sind die Eingangsplätze für Automatik oben und unten erreichbar, die Ausgangsplätze
 * an den Seiten; hier gilt die Regel je Platz, gleich von welcher Seite. Der Zellen-Tooltip folgt bei laufendem
 * Verkehr nur einmal je Sekunde (der Inhalt selbst jeden Takt).
 */
public final class IOPortDevice implements Device, ConfigHost, ExtractRules, IOPortLogic.CellSlots {

    /** Eingangsplätze 0..5, Ausgangsplätze 6..11. */
    public static final int INPUTS = IOPortLogic.NUMBER_OF_CELL_SLOTS;
    public static final int SLOTS = 2 * INPUTS;

    /** Namen der Einstellungen wie in AE2 ({@code appeng.api.config.Settings}), für die Memory Card. */
    public static final String SETTING_OPERATION_MODE = "operation_mode";
    public static final String SETTING_FULLNESS_MODE = "fullness_mode";
    public static final String SETTING_REDSTONE_CONTROLLED = "redstone_controlled";

    /** Tooltip-Aktualisierung bei laufendem Verkehr höchstens alle 20 Ticks (1 s). */
    private static final int TOOLTIP_INTERVAL = 20;
    /** Sicherheitsnetz: so oft werden die Eingangsplätze ohne Container-Ereignis verglichen. */
    private static final int SCAN_INTERVAL = 20;
    private static final long NO_SIGNAL_VERSION = Long.MIN_VALUE;

    /** Zellen melden Änderungen nicht einzeln: der Port lässt sie nach jedem Takt speichern ({@link #cellChanged}). */
    private static final ISaveProvider SAVED_BY_PORT = () -> {
    };

    /** Reihenfolge der Knöpfe (AE2: der Standard „Zelle → Netz“ zuerst). */
    private static final OperationMode[] OPERATION_MODES = {OperationMode.EMPTY, OperationMode.FILL};
    /** „Einmal je Impuls“ hat beim IO-Port keine eigene Bedeutung und wird nicht angeboten. */
    private static final RedstoneMode[] REDSTONE_MODES = {RedstoneMode.IGNORE, RedstoneMode.LOW_SIGNAL,
            RedstoneMode.HIGH_SIGNAL};

    /** Ein Eingangsplatz: der zuletzt gesehene Stack und – wenn es eine Zelle ist – ihr Inventar. */
    private static final class Slot {
        @Nullable
        ItemStack known;
        @Nullable
        CellItemData data;
        @Nullable
        StorageCell cell;
        long tooltipTick = -1_000_000L;
        long writeTick = -1_000_000L;
        boolean tooltipStale;

        void clear() {
            known = null;
            data = null;
            cell = null;
            tooltipStale = false;
            tooltipTick = -1_000_000L;
            writeTick = -1_000_000L;
        }
    }

    private final IOPortLogic logic = new IOPortLogic(this);
    private final Slot[] inputs = new Slot[INPUTS];
    private final Watchers watchers = new Watchers();
    @Nullable
    private ItemContainer container;
    @Nullable
    private EventRegistration<?, ?> changeReg;
    @Nullable
    private WorldNetworks nets;
    @Nullable
    private Signals signals;
    /** Schutz gegen Rekursion: eigene Schreibvorgänge lösen das Container-Ereignis aus. */
    private boolean busy;
    private boolean saveRequested;
    private boolean redstoneCard;
    /** Stand von {@link Signals#version()}, zu dem das Signal zuletzt abgefragt wurde. */
    private long signalVersion = NO_SIGNAL_VERSION;
    /** Gibt es Plätze, deren Tooltip dem Inhalt hinterherhinkt? */
    private boolean staleTooltips;
    private int x, y, z;

    public IOPortDevice() {
        for (int i = 0; i < inputs.length; i++) {
            inputs[i] = new Slot();
        }
    }

    public IOPortLogic logic() {
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

    // ------------------------------------------------------------------ Gerät

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        this.nets = nets;
        this.x = node.x();
        this.y = node.y();
        this.z = node.z();
        this.signals = Signals.of(nets.world());
        try {
            logic.loadState(node.comp.data());
        } catch (RuntimeException ignored) {
            // kaputter Zustand: Standardwerte
        }
        cardsChanged();
        logic.setSaveListener(() -> {
            saveRequested = true;
            cardsChanged();
        });
        container = BlockAccess.container(nets.world(), x, y, z);
        if (container != null) {
            int capacity = container.getCapacity();
            for (short s = 0; s < capacity; s++) {
                // AE2: Eingang StorageCellInsertOnlyFilter / RestrictedInputSlot(STORAGE_CELLS), Ausgang
                // AEItemFilters.EXTRACT_ONLY / OutputSlot – der Port selbst schreibt am Filter vorbei
                container.setSlotFilter(FilterActionType.ADD, s, s < INPUTS ? IOPortDevice::mayAddToInput
                        : IOPortDevice::mayAddToOutput);
            }
            // Zelle rein/raus sofort übernehmen: der Port darf nie mit einer Zelle weiterarbeiten, die der Spieler
            // schon in der Hand hält
            changeReg = container.registerChangeEvent(e -> sync());
        }
        sync();
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        // Signal: nur mit Redstone-Karte, und nur wenn sich in der Welt ein Signal geändert hat
        Signals sig = signals;
        if (redstoneCard && sig != null) {
            long version = sig.version();
            if (version != signalVersion) {
                signalVersion = version;
                logic.setRedstonePowered(sig.isPowered(x, y, z));
            }
        }
        if ((nets.tickCount() + (node.pos & 15)) % SCAN_INTERVAL == 0) {
            sync(); // Sicherheitsnetz, falls eine Änderung ohne Ereignis kam (6 Objektvergleiche)
        }
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            saveRequested = false;
            String json = logic.saveState();
            if (!json.equals(node.comp.data())) {
                node.comp.setData(json);
                node.markDirty();
            }
        }
        if (staleTooltips) {
            refreshStaleTooltips(nets.tickCount());
        }
        watchers.tick(nets.tickCount());
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
        // Die Items tragen ihren Inhalt schon (siehe cellChanged); hier nur noch der Tooltip. Den Container selbst
        // lässt Hytale beim Abbauen fallen.
        for (int i = 0; i < inputs.length; i++) {
            write(i, true);
        }
        if (broken) {
            // AE2: IOPortBlockEntity.addAdditionalDrops – die Karten fallen heraus
            Drops.upgrades(nets.world(), node.x(), node.y(), node.z(), logic.getUpgrades());
        } else {
            node.comp.setData(logic.saveState());
            node.markDirty();
        }
        watchers.run(); // offene Seiten merken, dass es den Port nicht mehr gibt, und schließen sich
    }

    private static boolean mayAddToInput(FilterActionType action, ItemContainer c, short slot, @Nullable ItemStack stack) {
        return ItemStack.isEmpty(stack) || StorageCells.isCellItem(stack.getItemId()) || sameAsInSlot(c, slot, stack);
    }

    private static boolean mayAddToOutput(FilterActionType action, ItemContainer c, short slot, @Nullable ItemStack stack) {
        return ItemStack.isEmpty(stack) || sameAsInSlot(c, slot, stack);
    }

    /**
     * Was schon im Platz liegt, darf in der Menge verändert werden. Das betrifft nur Stapel, die keine Zellen sind
     * (aus der Zeit, als der Block ein bloßer Behälter war): Lehnte der Container die verringerte Menge ab, hätte ein
     * Bus Items entnommen, ohne dass sie hier fehlen.
     */
    private static boolean sameAsInSlot(ItemContainer c, short slot, ItemStack stack) {
        if (slot < 0 || slot >= c.getCapacity()) {
            return false;
        }
        ItemStack current = c.getItemStack(slot);
        return !ItemStack.isEmpty(current) && stack.isStackableWith(current);
    }

    /** Nach jeder Änderung an den Karten: gibt es eine Redstone-Karte? Dann das Signal neu abfragen. */
    private void cardsChanged() {
        redstoneCard = logic.getUpgrades().isInstalled(UpgradeCard.REDSTONE);
        signalVersion = NO_SIGNAL_VERSION;
    }

    // ------------------------------------------------------------------ Container → Kern

    /** Übernimmt Eingangsplätze, deren Stack sich geändert hat (ItemStacks sind unveränderlich: gleiches Objekt = gleich). */
    private void sync() {
        ItemContainer c = container;
        if (c == null || busy) {
            return;
        }
        boolean changed = false;
        int n = Math.min(inputs.length, c.getCapacity());
        for (int i = 0; i < n; i++) {
            Slot s = inputs[i];
            ItemStack stack = c.getItemStack((short) i);
            if (ItemStack.isEmpty(stack)) {
                stack = null;
            }
            if (stack == s.known) {
                continue;
            }
            // Anderer Stack: was vorher hier lag, trägt seinen Inhalt schon im Item
            changed = true;
            s.clear();
            s.known = stack;
            if (stack != null && StorageCells.isCellItem(stack.getItemId())) {
                try {
                    CellItemData data = CellItemData.fromJson(stack.getFromMetadataOrNull(CellHostDevice.CELL_KEY, Codec.STRING));
                    s.cell = StorageCells.getCellInventory(stack.getItemId(), data, SAVED_BY_PORT);
                    s.data = data;
                } catch (RuntimeException e) {
                    // Unlesbarer Zelleninhalt: nicht anfassen – der Port legt das Item unverändert in den Ausgang
                    s.cell = null;
                    s.data = null;
                }
            }
        }
        if (changed) {
            logic.onInventoryChanged();
        }
    }

    // ------------------------------------------------------------------ Kern → Container (IOPortLogic.CellSlots)

    @Override
    public boolean isEmpty(int slot) {
        return inputs[slot].known == null;
    }

    @Nullable
    @Override
    public StorageCell getCell(int slot) {
        return inputs[slot].cell;
    }

    @Override
    public void cellChanged(int slot) {
        write(slot, false);
    }

    /**
     * Schreibt den Inhalt der Zelle eines Eingangsplatzes in ihr Item.
     *
     * @param tooltip true: den Tooltip auf jeden Fall mitschreiben, sonst höchstens einmal je Sekunde
     */
    private void write(int slot, boolean tooltip) {
        ItemContainer c = container;
        Slot s = inputs[slot];
        if (c == null || busy || s.cell == null || s.data == null || s.known == null || slot >= c.getCapacity()) {
            return;
        }
        if (c.getItemStack((short) slot) != s.known) {
            return; // Platz inzwischen verändert: nie ein fremdes Item überschreiben – sync() übernimmt
        }
        long now = nets == null ? 0 : nets.tickCount();
        boolean withTooltip = tooltip || now - s.tooltipTick >= TOOLTIP_INTERVAL;
        s.cell.persist();
        ItemStack updated = CellTooltips.write(s.known, s.data, s.cell, withTooltip);
        busy = true;
        try {
            if (!c.setItemStackForSlot((short) slot, updated, false).succeeded()) {
                return;
            }
        } finally {
            busy = false;
        }
        s.known = updated;
        s.writeTick = now;
        if (withTooltip) {
            s.tooltipTick = now;
            s.tooltipStale = false;
        } else {
            s.tooltipStale = true;
            staleTooltips = true;
        }
    }

    /** Zellen, deren Verkehr gerade aufgehört hat, bekommen den aktuellen Tooltip. */
    private void refreshStaleTooltips(long now) {
        boolean left = false;
        for (int i = 0; i < inputs.length; i++) {
            Slot s = inputs[i];
            if (!s.tooltipStale) {
                continue;
            }
            if (now - s.writeTick < 2) {
                left = true; // noch in Bewegung (gerade erst geschrieben)
                continue;
            }
            write(i, true);
            left |= s.tooltipStale;
        }
        staleTooltips = left;
    }

    @Override
    public boolean moveToOutput(int slot) {
        ItemContainer c = container;
        if (c == null || busy || slot >= c.getCapacity()) {
            return false;
        }
        Slot s = inputs[slot];
        ItemStack stack = c.getItemStack((short) slot);
        if (ItemStack.isEmpty(stack) || stack != s.known) {
            return false; // der Platz hat sich geändert, ohne dass der Port es schon weiß
        }
        short out = freeOutput(c);
        if (out < 0) {
            return false;
        }
        if (s.cell != null && s.data != null) {
            // Die Zelle verlässt den Platz: Inhalt und Tooltip stehen danach vollständig im Item
            s.cell.persist();
            stack = CellTooltips.write(stack, s.data, s.cell, true);
        }
        busy = true;
        try {
            // Verschieben, nicht kopieren: erst herausnehmen, dann ablegen
            if (!c.removeItemStackFromSlot((short) slot, false).succeeded()) {
                return false;
            }
            if (!c.setItemStackForSlot(out, stack, false).succeeded()) {
                c.setItemStackForSlot((short) slot, stack, false); // zurück an seinen Platz
                s.known = c.getItemStack((short) slot);
                return false;
            }
        } finally {
            busy = false;
        }
        s.clear();
        return true;
    }

    /** Erster freier Ausgangsplatz, oder -1. */
    private static short freeOutput(ItemContainer c) {
        int end = Math.min(SLOTS, c.getCapacity());
        for (short s = (short) INPUTS; s < end; s++) {
            if (ItemStack.isEmpty(c.getItemStack(s))) {
                return s;
            }
        }
        return -1;
    }

    /** AE2: Automatik entnimmt nur aus dem Ausgang ({@code StorageCellInsertOnlyFilter.allowExtract} ist false). */
    @Override
    public boolean busMayExtract(int slot) {
        return slot >= INPUTS;
    }

    // ------------------------------------------------------------------ Anzeige

    /** Belegte Eingangsplätze. */
    public int waitingCells() {
        int n = 0;
        for (Slot s : inputs) {
            if (s.known != null) {
                n++;
            }
        }
        return n;
    }

    /** Belegte Ausgangsplätze. */
    public int finishedCells() {
        ItemContainer c = container;
        if (c == null) {
            return 0;
        }
        int n = 0;
        int end = Math.min(SLOTS, c.getCapacity());
        for (short s = (short) INPUTS; s < end; s++) {
            if (!ItemStack.isEmpty(c.getItemStack(s))) {
                n++;
            }
        }
        return n;
    }

    public boolean hasRedstoneCard() {
        return redstoneCard;
    }

    // ------------------------------------------------------------------ Konfigurationsseite (ConfigHost)

    @Override
    public UpgradeInventory upgradeCards() {
        return logic.getUpgrades();
    }

    /** AE2 (IOPortScreen): Richtung, Auswurf und – nur mit Redstone-Karte – das Signal. */
    @Override
    public List<Setting> settings(Context ctx) {
        List<Setting> out = new ArrayList<>(3);
        out.add(Setting.cycle(ctx, "ae2ioport.setting.operation.", logic.getOperationMode(), OPERATION_MODES,
                logic::setOperationMode));
        out.add(Setting.cycle(ctx, "ae2ioport.setting.fullness.", logic.getFullnessMode(), FullnessMode.values(),
                logic::setFullnessMode));
        if (logic.getUpgrades().isInstalled(UpgradeCard.REDSTONE)) {
            out.add(Setting.cycle(ctx, "ae2ioport.setting.redstone.", shownRedstoneMode(), REDSTONE_MODES,
                    logic::setRedstoneMode));
        }
        return out;
    }

    /**
     * Einstellungen unter AE2s Namen, für die Memory Card (AE2: der Config-Manager von {@code IOPortBlockEntity}) –
     * alle drei, auch der Signalmodus, dessen Knopf erst mit der Redstone-Karte erscheint.
     */
    @Override
    public Map<String, String> exportSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(SETTING_REDSTONE_CONTROLLED, logic.getRedstoneMode().name());
        out.put(SETTING_FULLNESS_MODE, logic.getFullnessMode().name());
        out.put(SETTING_OPERATION_MODE, logic.getOperationMode().name());
        return out;
    }

    @Override
    public boolean importSettings(Map<String, String> in) {
        boolean any = MemoryCardSettings.importEnum(in, SETTING_REDSTONE_CONTROLLED, RedstoneMode.class,
                logic::setRedstoneMode);
        any |= MemoryCardSettings.importEnum(in, SETTING_FULLNESS_MODE, FullnessMode.class, logic::setFullnessMode);
        any |= MemoryCardSettings.importEnum(in, SETTING_OPERATION_MODE, OperationMode.class, logic::setOperationMode);
        return any;
    }

    /** Der Modus, wie ihn die Knöpfe kennen („einmal je Impuls“ wirkt beim IO-Port wie „aktiv ohne Signal“). */
    public RedstoneMode shownRedstoneMode() {
        RedstoneMode mode = logic.getRedstoneMode();
        return mode == RedstoneMode.SIGNAL_PULSE ? RedstoneMode.LOW_SIGNAL : mode;
    }

    /** Wie man an die Zellenplätze kommt – die Seite selbst öffnet sich nur geduckt. */
    @Override
    public String note(Context ctx) {
        return ctx.tr("ae2ioport.note.slots");
    }

    @Override
    public Watchers watchers() {
        return watchers;
    }

    @Override
    public void configChanged(WorldNetworks.Node node) {
        String json = logic.saveState();
        if (!json.equals(node.comp.data())) {
            node.comp.setData(json);
            node.markDirty();
        }
        saveRequested = false;
    }

    @Override
    public boolean opensOnCrouch() {
        return true;
    }
}
