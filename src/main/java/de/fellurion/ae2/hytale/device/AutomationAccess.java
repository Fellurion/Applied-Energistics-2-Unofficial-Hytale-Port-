/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.AEBaseInvBlockEntity#getExposedInventoryForSide und die IAEItemFilter der Maschinen).
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;

import javax.annotation.Nullable;

/**
 * Ein Gerät, das Bussen, Pattern Providern und anderen Maschinen nicht seinen rohen Container zeigt, sondern eine
 * eigene Sicht je Seite – so wie AE2-Maschinen der Automation nur bestimmte Slots öffnen (Inscriber: Eingaben hinein,
 * nur das Ergebnis heraus; mit getrennten Seiten die Platten von oben und unten).
 */
public interface AutomationAccess {

    /**
     * Die Plätze, die Automation von {@code side} aus sieht.
     *
     * @param side Seite dieses Blocks, an der der Zugreifende steht
     * @return null = keine eigene Sicht: der ganze Container mit seinen Slot-Filtern
     */
    @Nullable
    SlotAccess automationSlots(Dir side);

    /** Darf Automation von {@code side} aus dem Platz {@code slot} der Sicht entnehmen? */
    default boolean automationCanExtract(Dir side, int slot) {
        return true;
    }
}
