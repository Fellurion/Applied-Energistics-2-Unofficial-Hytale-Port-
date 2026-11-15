/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.reporting.AbstractMonitorPart / StorageMonitorPart / ConversionMonitorPart).
 */
package de.fellurion.ae2.hytale.emitter;

import de.fellurion.ae2.core.devices.StorageMonitorLogic;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;

/**
 * ME Storage Monitor und ME Conversion Monitor: beobachten ein Item und zeigen seine Menge im Netz – in der
 * Blickanzeige und, solange der Monitor am Netz ist, als Item mit Namensschild vor dem Bildschirm
 * ({@link MonitorDisplay}). Item und Sperre stehen als JSON in der Block-Komponente ({@code Data}).
 *
 * <p>Die Bedienung (Item setzen, sperren, beim Conversion Monitor ein- und auslagern) steht in {@link MonitorUse}.
 */
public final class MonitorDevice implements Device {

    /** Die Anzeige wird höchstens so oft nachgezogen (AE2-Ticks). */
    static final int DISPLAY_INTERVAL_TICKS = 10;

    private final StorageMonitorLogic logic = new StorageMonitorLogic();
    private final boolean conversion;
    private final MonitorDisplay display = new MonitorDisplay();
    private final int phase;
    private boolean saveRequested;
    private boolean displayDirty = true;
    private long shownAmount = -1;

    MonitorDevice(WorldNetworks nets, WorldNetworks.Node node, boolean conversion) {
        this.conversion = conversion;
        this.phase = (int) Math.floorMod(node.pos, (long) DISPLAY_INTERVAL_TICKS);
        // Der Zustand muss stehen, bevor der Netzknoten entsteht: der Kern fragt beim Anlegen nach dem Item.
        try {
            logic.loadState(node.comp.data());
        } catch (RuntimeException ignored) {
            // kaputte Daten: leerer, entsperrter Monitor
        }
        logic.setSaveListener(() -> saveRequested = true);
        logic.setChangeListener(() -> displayDirty = true);
    }

    @Override
    public Object owner() {
        return logic;
    }

    public StorageMonitorLogic logic() {
        return logic;
    }

    /** Conversion Monitor (lagert auch ein und aus)? */
    public boolean conversion() {
        return conversion;
    }

    /** Lebt die Anzeige in der Welt gerade? (Für Prüfbefehle.) */
    public boolean displayAlive() {
        return display.alive();
    }

    /** Wo die Anzeige gerade steht, als „x y z“ mit zwei Nachkommastellen, oder „-“. (Für Prüfbefehle.) */
    public String displayPosition() {
        return display.position();
    }

    /** Das beobachtete Item, wenn es ein Item ist. */
    @Nullable
    public AEItemKey item() {
        return logic.getConfiguredItem() instanceof AEItemKey key ? key : null;
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            save(node);
        }
        if (nets.tickCount() % DISPLAY_INTERVAL_TICKS == phase) {
            updateDisplay(nets, node);
        }
    }

    /** Zieht die Anzeige nach: nur am Netz, nur mit Item; ohne Änderung geschieht nichts. */
    private void updateDisplay(WorldNetworks nets, WorldNetworks.Node node) {
        AEItemKey key = item();
        Dir facing = node.facing();
        if (key == null || facing == null || !EmitterSettings.monitorDisplay() || !logic.isActive()) {
            if (display.present()) {
                display.remove(nets.world());
            }
            shownAmount = -1;
            return;
        }
        long amount = logic.getAmount();
        if (displayDirty || amount != shownAmount || !display.shows(key)) {
            displayDirty = false;
            shownAmount = amount;
            display.show(nets.world(), node.x(), node.y(), node.z(), facing, key, TerminalTexts.formatAmount(amount));
        }
    }

    /**
     * Gedreht: Die Anzeige steht noch vor der alten Vorderseite. Sie wird entfernt und – wenn der Monitor nach der
     * Drehung noch am Netz ist – sofort vor der neuen angelegt; es bleibt bei einer Anzeige je Monitor.
     */
    @Override
    public void onRotated(WorldNetworks nets, WorldNetworks.Node node) {
        display.remove(nets.world());
        displayDirty = true;
        shownAmount = -1;
        updateDisplay(nets, node);
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        display.remove(nets.world());
        if (!broken) {
            save(node); // Chunk wird entladen
        }
    }

    private void save(WorldNetworks.Node node) {
        saveRequested = false;
        node.comp.setData(logic.saveState());
        node.markDirty();
    }
}
