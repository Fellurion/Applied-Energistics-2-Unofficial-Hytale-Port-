/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.UUID;

/**
 * Wer ein ME-Netz besitzt: der Besitzer des <b>ältesten</b> Blocks mit Besitzer. Bei gleichem Alter (Altbestand ohne
 * Zeitstempel = 0) gewinnt ein Controller vor anderen Blöcken, danach die kleinste Position (x, y, z).
 *
 * <p>Warum das Alter zuerst zählt und nicht „der Controller“: Sonst könnte ein Fremder ein Kabel an ein Netz ohne
 * Controller (oder einen Controller passend an einen bestehenden) hängen und wäre plötzlich Besitzer – samt Zugriff
 * auf alle Items. Was später dazukommt, ist immer jünger und übernimmt das Netz deshalb nie.
 */
final class OwnerRanking {

    private OwnerRanking() {
    }

    /**
     * Ein Block mit Besitzer.
     *
     * @param placedAt Platzierzeit in ms seit 1970; 0 = unbekannt (vor der Besitzerfunktion platziert)
     */
    record Candidate(UUID owner, @Nullable String name, long placedAt, boolean controller, int x, int y, int z) {
    }

    static final Comparator<Candidate> ORDER = Comparator.comparingLong(Candidate::placedAt)
            .thenComparing((Candidate c) -> !c.controller())
            .thenComparingInt(Candidate::x)
            .thenComparingInt(Candidate::y)
            .thenComparingInt(Candidate::z);

    /** Der maßgebliche Block; null, wenn kein Block des Netzes einen Besitzer hat. */
    @Nullable
    static Candidate pick(Iterable<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (c != null && (best == null || ORDER.compare(c, best) < 0)) {
                best = c;
            }
        }
        return best;
    }
}
