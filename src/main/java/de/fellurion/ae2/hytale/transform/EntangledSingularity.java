/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.qnb.QuantumBridgeBlockEntity#assignFrequency, AEComponents.ENTANGLED_SINGULARITY_ID).
 */
package de.fellurion.ae2.hytale.transform;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.tools.SingularityFrequency;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import javax.annotation.Nullable;

/**
 * Verschränkte Singularitäten: Die beiden Singularitäten eines Paars tragen dieselbe Frequenz in den Item-Metadaten
 * unter {@link #KEY}; eine Quantum Network Bridge verbindet sich mit der Brücke, in der die andere steckt. Ohne den
 * Schlüssel ist eine Singularität nicht verschränkt.
 */
public final class EntangledSingularity {

    public static final String ITEM = "AE2_Quantum_Entangled_Singularity";
    /** Metadaten-Schlüssel der Frequenz ({@code Codec.LONG}, nie 0). */
    public static final String KEY = "AE2QuantumFrequency";

    private static volatile boolean tooltipInstalled;

    private EntangledSingularity() {
    }

    /** Meldet die Tooltip-Zeile an (einmal beim Start). */
    static synchronized void setup() {
        if (!tooltipInstalled) {
            tooltipInstalled = true;
            ItemTooltips.addSection(EntangledSingularity::tooltip);
        }
    }

    /** Frequenz der Singularität; 0 = nicht verschränkt (oder gar keine Singularität). */
    public static long frequency(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack) || !ITEM.equals(stack.getItemId())) {
            return 0;
        }
        Long v = stack.getFromMetadataOrNull(KEY, Codec.LONG);
        return v == null ? 0 : v;
    }

    /** Der Stapel mit dieser Frequenz (0 entfernt sie) und passendem Tooltip. */
    public static ItemStack withFrequency(ItemStack stack, long frequency) {
        return ItemTooltips.refresh(stack.withMetadata(KEY, Codec.LONG, frequency == 0 ? null : frequency));
    }

    /** Gibt dem Stapel eine neue Frequenz: alle Singularitäten darin gehören danach zusammen. */
    public static ItemStack entangle(ItemStack stack) {
        return withFrequency(stack, SingularityFrequency.next());
    }

    @Nullable
    private static Message tooltip(ItemStack stack) {
        if (!ITEM.equals(stack.getItemId())) {
            return null;
        }
        long frequency = frequency(stack);
        return frequency == 0 ? Message.translation("ae2gadget.singularity.unlinked")
                : Message.translation("ae2gadget.singularity.frequency").param("frequency", Long.toString(frequency));
    }
}
