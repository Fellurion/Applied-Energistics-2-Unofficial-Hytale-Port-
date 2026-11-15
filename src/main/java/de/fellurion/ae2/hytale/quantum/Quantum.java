/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.qnb.QuantumBridgeBlockEntity, appeng.block.qnb.*).
 */
package de.fellurion.ae2.hytale.quantum;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.quantum.QuantumBridgeLogic;
import de.fellurion.ae2.core.quantum.QuantumBridgeRegistry;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Quantum Network Bridge: acht Quantum Rings um eine Quantum Link Chamber (3×3 Blöcke in einer der drei Ebenen)
 * bilden eine Brücke; zwei Brücken, in deren Link Chamber Quantum Entangled Singularities derselben Frequenz
 * liegen, verbinden ihre Netze über beliebige Entfernung zu einem – mit 32 Kanälen wie ein Dense-Kabel.
 *
 * <p>Die Regeln stehen im Kern ({@link QuantumBridgeLogic}, {@code QuantumCluster}); hier hängen sie an den
 * Hytale-Blöcken ({@link QuantumDevice}), der Blickanzeige ({@link QuantumHud}) und den Testbefehlen
 * ({@link QuantumCommands}).
 *
 * <p><b>Umfang dieser Portierung:</b> Je Hytale-Welt gibt es ein Netz-Modell, deshalb verbindet eine Brücke zwei
 * Orte DERSELBEN Welt (AE2 verbindet auch Dimensionen). Wie in AE2 müssen beide Enden geladen sein; wird eines
 * entladen, trennt sich die Verbindung und kommt wieder, sobald beide geladen sind. Gespeichert wird dafür nichts:
 * Form und Verbindung ergeben sich nach dem Laden aus den Blöcken und dem Singularity im Slot.
 */
public final class Quantum {

    public static final String KIND_RING = QuantumBridgeLogic.KIND_RING;
    public static final String KIND_LINK = QuantumBridgeLogic.KIND_LINK;

    /** Das Item, das in die Link Chamber gehört. */
    public static final String SINGULARITY_ITEM = "AE2_Quantum_Entangled_Singularity";

    /**
     * Metadaten-Schlüssel der Frequenz eines Quantum Entangled Singularity ({@code Codec.LONG}, nie 0). Zwei
     * Singularities aus derselben Explosion tragen dieselbe Frequenz; ohne den Schlüssel ist ein Singularity nicht
     * verschränkt. Hier wird der Schlüssel nur gelesen.
     */
    public static final String FREQUENCY_KEY = de.fellurion.ae2.hytale.transform.EntangledSingularity.KEY;

    /** Brücken je Welt (AE2: Locatables.quantumNetworkBridges). Der Wert hält die Welt nicht fest. */
    private static final Map<WorldNetworks, QuantumBridgeRegistry> REGISTRIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Quantum() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        Devices.register(KIND_RING, QuantumDevice::new);
        Devices.register(KIND_LINK, QuantumDevice::new);
        // Benutzen: Die Link Chamber öffnet ihr Slot-Fenster (Standard für Blöcke mit Container), der Ring tut nichts

        InfoProviders.register(QuantumHud::describe, KIND_RING, KIND_LINK);

        AE2Command.registerSub(new QuantumCommands());
    }

    /** Die Brücken der Welt, nach Frequenz. */
    static QuantumBridgeRegistry registry(WorldNetworks nets) {
        return REGISTRIES.computeIfAbsent(nets, n -> QuantumBridgeRegistry.create());
    }

    /** Ist das ein Quantum Entangled Singularity (verschränkt oder nicht)? */
    static boolean isSingularity(@Nullable ItemStack stack) {
        return !ItemStack.isEmpty(stack) && SINGULARITY_ITEM.equals(stack.getItemId());
    }

    /** Frequenz des Singularity im Stack; 0, wenn es keines ist oder es nicht verschränkt ist. */
    static long frequency(@Nullable ItemStack stack) {
        if (!isSingularity(stack)) {
            return 0;
        }
        try {
            return de.fellurion.ae2.hytale.transform.EntangledSingularity.frequency(stack);
        } catch (RuntimeException e) {
            return 0; // unlesbarer Wert: wie nicht verschränkt
        }
    }

    /**
     * Ein Singularity mit dieser Frequenz (0 = unverschränkt), für Testbefehle. Der Tooltip wird wie bei einem
     * hergestellten Singularity aufgebaut, damit beide dieselben Metadaten tragen und sich stapeln lassen.
     */
    static ItemStack singularity(long frequency) {
        ItemStack stack = new ItemStack(SINGULARITY_ITEM, 1);
        return frequency == 0 ? stack
                : de.fellurion.ae2.hytale.transform.EntangledSingularity.withFrequency(stack, frequency);
    }

    /** Das Gerät zu einem Brückenblock des Kerns (über die Position seines Knotens), oder null. */
    @Nullable
    static QuantumDevice device(WorldNetworks nets, @Nullable QuantumBridgeLogic logic) {
        GridNode g = logic == null ? null : logic.getNode();
        if (g == null || g.isRemoved()) {
            return null;
        }
        long p = g.getPos();
        WorldNetworks.Node node = nets.node(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
        return node != null && node.device() instanceof QuantumDevice d && d.logic() == logic ? d : null;
    }
}
