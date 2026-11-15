/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.util.inv.FilteredInternalInventory mit appeng.util.inv.filter.IAEItemFilter#allowExtract).
 */
package de.fellurion.ae2.hytale.device;

/**
 * Ein Gerät, aus dessen Container Busse nicht aus jedem Platz entnehmen dürfen – etwa die Eingangsplätze des
 * IO-Ports, deren Zellen gerade bearbeitet werden. Import- und Storage-Bus fragen das Gerät des Blocks vor ihnen
 * ({@link BusDevice}); Spieler sind davon nicht betroffen.
 *
 * <p>Ein Slot-Filter des Containers ({@code FilterActionType.REMOVE}) taugt dafür nicht: Er gälte auch für Spieler,
 * und ein Bus bemerkte die abgelehnte Entnahme nicht.
 */
public interface ExtractRules {

    /** Darf ein Bus aus diesem Platz des Containers entnehmen? */
    boolean busMayExtract(int slot);
}
