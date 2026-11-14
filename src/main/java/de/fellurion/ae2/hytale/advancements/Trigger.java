/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.core.stats.AdvancementTriggers, minecraft:inventory_changed).
 */
package de.fellurion.ae2.hytale.advancements;

import java.util.Locale;

/** Auslöser eines Erfolgs-Kriteriums ({@code "trigger"} in {@code ae2data/advancements.json}). */
public enum Trigger {
    /** {@code minecraft:inventory_changed}: der Spieler hat eines der Items im Inventar. */
    HAS_ITEM,
    /** {@code ae2:network_apprentice/engineer/admin}: ein Netz in der Nähe nutzt mindestens N Kanäle. */
    NETWORK_CHANNELS,
    /** {@code ae2:recursive_networking}: Speicherbus an einer ME-Schnittstelle. */
    RECURSIVE_NETWORKING,
    /** {@code ae2:spatial_explorer}: in einer Raumspeicherzelle gespeichert (Spatial IO noch nicht portiert). */
    SPATIAL_EXPLORER;

    /** {@code "has_item"} → {@link #HAS_ITEM}. */
    public static Trigger fromId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }
}
