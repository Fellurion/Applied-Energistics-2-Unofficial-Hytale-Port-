/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.qnb.QuantumBridgeBlockEntity).
 */
package de.fellurion.ae2.hytale.quantum;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.quantum.QuantumBridgeLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;

import javax.annotation.Nullable;

/**
 * Quantum Ring und Quantum Link Chamber. Die Brücke selbst – Form, anschließbare Seiten, Verbindung zur Gegenstelle –
 * regelt der Kern ({@link QuantumBridgeLogic}) aus den geladenen Blöcken: Jeder Block meldet sich mit seinem Knoten
 * an und ab, mehr braucht es nicht. Beim Platzieren, Abbauen, Laden und Entladen prüft der Kern die Brücke neu,
 * nicht in jedem Takt.
 *
 * <p>Die Link Chamber liest zusätzlich die Frequenz des Singularity in ihrem Slot (Metadaten
 * {@link Quantum#FREQUENCY_KEY}) und gibt sie dem Kern, sobald sich der Slot ändert und nach jedem Laden.
 *
 * <p><b>Abweichungen von AE2:</b>
 * <ul>
 *   <li>In den Slot passt jedes Quantum Entangled Singularity. AE2 lässt nur verschränkte hinein und höchstens
 *       eines; ein Hytale-Slotfilter kann die Menge nicht begrenzen. Ein unverschränktes Singularity oder ein
 *       zweites im selben Slot bewirkt nichts – die Blickanzeige sagt, woran es liegt.</li>
 *   <li>Die Blöcke sehen immer gleich aus: Für die Zustände „gebildet“ und „leuchtend“ gibt es keine Modelle.</li>
 * </ul>
 */
final class QuantumDevice implements Device {

    private final QuantumBridgeLogic logic;
    @Nullable
    private ItemContainer container;
    @Nullable
    private EventRegistration<?, ?> changeReg;
    /** Der Slot hat sich geändert; gelesen wird im nächsten Takt auf dem Welt-Thread. */
    private volatile boolean slotChanged;
    /** Anzahl Singularities im Slot (für die Blickanzeige). */
    private int singularities;

    QuantumDevice(WorldNetworks nets, WorldNetworks.Node node) {
        this.logic = QuantumBridgeLogic.forKind(node.kind(), Quantum.registry(nets));
    }

    QuantumBridgeLogic logic() {
        return logic;
    }

    /** Anzahl Singularities im Slot der Link Chamber. */
    int singularities() {
        return singularities;
    }

    @Override
    public Object owner() {
        return logic;
    }

    /** Ohne anschließbare Seite: Erst die gebildete Brücke öffnet ihre Seiten (Eck-Ringe nie zu Kabeln hin). */
    @Override
    public NodeType nodeType(WorldNetworks nets, WorldNetworks.Node node) {
        return logic.nodeType();
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        if (!logic.isCenter()) {
            return;
        }
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container != null) {
            // In die Link Chamber passt nur das Singularity (AE2: QuantumBridgeBlockEntity.EntangledSingularityFilter)
            for (short s = 0; s < container.getCapacity(); s++) {
                container.setSlotFilter(FilterActionType.ADD, s,
                        (action, c, slot, stack) -> stack == null || stack.isEmpty() || Quantum.isSingularity(stack));
            }
            changeReg = container.registerChangeEvent(e -> slotChanged = true);
        }
        readSlot();
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (slotChanged) {
            slotChanged = false;
            readSlot();
        }
    }

    /** Hytale → Kern: Frequenz des Singularity im Slot (AE2: onChangeInventory → cluster.updateStatus). */
    private void readSlot() {
        ItemStack stack = container == null || container.getCapacity() == 0 ? null : container.getItemStack((short) 0);
        singularities = Quantum.isSingularity(stack) ? stack.getQuantity() : 0;
        logic.setQEFrequency(Quantum.frequency(stack));
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
        // Das Singularity bleibt im Container des Blocks (fällt beim Abbauen mit heraus, bleibt beim Entladen liegen)
    }

    /** Kein eigener Zustand: Es gibt nur das Grundmodell. */
    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        return "default";
    }
}
