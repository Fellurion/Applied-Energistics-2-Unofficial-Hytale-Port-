/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.spatial.SpatialIOPortBlockEntity).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.spatial.SpatialIOPortLogic;
import de.fellurion.ae2.core.spatial.SpatialPlotInfo;
import de.fellurion.ae2.core.spatial.SpatialPylonService;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.security.Owner;
import de.fellurion.ae2.hytale.signal.Signals;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Spatial IO Port: Slot 0 nimmt eine Spatial-Zelle auf, Slot 1 ist die Ausgabe (AE2: {@code SpatialIOFilter}).
 * Ausgelöst wird der Transfer wie in AE2 von der steigenden Flanke eines Signals am Port
 * ({@link Signals#isPowered}, der Ersatz für Redstone) – und zusätzlich, weil die wenigsten Spieler eine
 * Signalquelle haben, durch geducktes Benutzen des Ports ({@link Spatial}).
 *
 * <p>Der Port merkt sich den zuletzt gesehenen Signalzustand in seiner Komponente (AE2: {@code lastRedstoneState}),
 * damit ein anliegendes Signal nach dem Laden nicht noch einmal auslöst. Abweichung: Hytales Behälter kennen keine
 * Seiten, deshalb können Automaten die Zelle auch aus dem Eingabe-Slot ziehen (AE2 gibt nach außen nur den
 * Ausgabe-Slot frei).
 */
final class IOPortDevice implements Device {

    /** Schlüssel in {@code AE2_Block.Config}: zuletzt gesehener Signalzustand ("1"/"0"). */
    private static final String CONFIG_SIGNAL = "spatialSignal";
    static final short SLOT_INPUT = 0;
    static final short SLOT_OUTPUT = 1;

    private final SpatialIOPortLogic logic;
    @Nullable
    private ItemContainer container;
    @Nullable
    private Signals signals;
    private long signalVersion = Long.MIN_VALUE;

    IOPortDevice(SpatialStore store) {
        this.logic = new SpatialIOPortLogic(store.plots());
    }

    SpatialIOPortLogic logic() {
        return logic;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container != null && container.getCapacity() > SLOT_OUTPUT) {
            container.setSlotFilter(FilterActionType.ADD, SLOT_INPUT,
                    (action, c, slot, stack) -> stack == null || stack.isEmpty() || SpatialCells.isCell(stack));
            container.setSlotFilter(FilterActionType.ADD, SLOT_OUTPUT,
                    (action, c, slot, stack) -> stack == null || stack.isEmpty());
        }
        String saved = node.comp.config().get(CONFIG_SIGNAL);
        logic.setLastRedstoneState(saved == null ? null : "1".equals(saved));
        signals = Signals.of(nets.world());
        signalVersion = Long.MIN_VALUE;
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        Signals s = signals;
        if (s == null) {
            return;
        }
        long version = s.version();
        if (version == signalVersion) {
            return; // an den Sendern der Welt hat sich nichts geändert
        }
        signalVersion = version;
        Boolean before = logic.getLastRedstoneState();
        boolean powered = s.isPowered(node.x(), node.y(), node.z());
        boolean trigger = logic.updateRedstoneState(powered);
        if (!Objects.equals(before, logic.getLastRedstoneState())) {
            node.comp.config().put(CONFIG_SIGNAL, powered ? "1" : "0");
            node.markDirty();
        }
        if (trigger) {
            SpatialTransfers.request(nets, node.x(), node.y(), node.z(), null);
        }
    }

    /** Die Sicht des Kerns auf diesen Port. */
    SpatialIOPortLogic.Host host(WorldNetworks nets, WorldNetworks.Node node) {
        return new Host(nets, node, container);
    }

    private static final class Host implements SpatialIOPortLogic.Host {
        private final WorldNetworks nets;
        private final WorldNetworks.Node node;
        @Nullable
        private final ItemContainer container;
        @Nullable
        private final Grid grid;
        @Nullable
        private ItemStack cell;

        Host(WorldNetworks nets, WorldNetworks.Node node, @Nullable ItemContainer container) {
            this.nets = nets;
            this.node = node;
            this.container = container;
            GridNode g = node.gridNode();
            this.grid = g == null ? null : g.getGrid();
        }

        @Override
        public SpatialIOPortLogic.Cell inputCell() {
            if (container == null || container.getCapacity() <= SLOT_OUTPUT) {
                return null;
            }
            ItemStack stack = container.getItemStack(SLOT_INPUT);
            if (!SpatialCells.isCell(stack)) {
                return null;
            }
            cell = stack;
            return new SpatialIOPortLogic.Cell() {
                @Override
                public String itemId() {
                    return stack.getItemId();
                }

                @Override
                public SpatialPlotInfo plotInfo() {
                    return SpatialCells.info(cell);
                }

                @Override
                public void setPlotInfo(SpatialPlotInfo info) {
                    cell = SpatialCells.withInfo(cell, info);
                }
            };
        }

        @Override
        public boolean isOutputEmpty() {
            return container != null && container.getCapacity() > SLOT_OUTPUT
                    && ItemStack.isEmpty(container.getItemStack(SLOT_OUTPUT));
        }

        @Override
        public void moveCellToOutput() {
            if (container == null || cell == null) {
                return;
            }
            // erst herausnehmen, dann ablegen: die Zelle gibt es zu jedem Zeitpunkt genau einmal
            container.removeItemStackFromSlot(SLOT_INPUT, false);
            if (!container.setItemStackForSlot(SLOT_OUTPUT, cell, false).succeeded()) {
                container.setItemStackForSlot(SLOT_INPUT, cell, false);
            }
        }

        @Override
        public boolean isActive() {
            GridNode g = node.gridNode();
            return g != null && g.isActive();
        }

        @Override
        public SpatialPylonService spatial() {
            return grid == null ? null : SpatialWorld.of(nets).pylons.serviceOf(grid);
        }

        @Override
        public IEnergySource energy() {
            return grid == null ? null : grid.getEnergyService();
        }

        @Override
        public void returnPower(double ae) {
            if (grid != null && ae > 0) {
                grid.getEnergyService().injectPower(ae, Actionable.MODULATE);
            }
        }

        @Override
        public String owner() {
            Owner owner = AE2Security.ownerOf(nets, node);
            return owner == null ? null : owner.uuid().toString();
        }

        @Override
        public String worldId() {
            return nets.world().getName();
        }
    }
}
