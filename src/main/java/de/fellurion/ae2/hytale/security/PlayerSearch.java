/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Spielerauswahl der Zugriffsseite: Suchtext filtert, Online-Spieler und Namensanfänge zuerst. */
final class PlayerSearch {

    private PlayerSearch() {
    }

    record Candidate(UUID uuid, String name, boolean online) {
    }

    /** Online-Spieler und bekannte Namen zusammenführen (online gewinnt, mit aktuellem Namen). */
    static List<Candidate> merge(Collection<Candidate> online, Collection<Candidate> known) {
        Map<UUID, Candidate> all = new LinkedHashMap<>();
        for (Candidate c : known) {
            if (c != null && c.name() != null && !c.name().isBlank()) {
                all.put(c.uuid(), new Candidate(c.uuid(), c.name(), false));
            }
        }
        for (Candidate c : online) {
            if (c != null && c.name() != null && !c.name().isBlank()) {
                all.put(c.uuid(), new Candidate(c.uuid(), c.name(), true));
            }
        }
        return new ArrayList<>(all.values());
    }

    /**
     * Treffer für den Suchtext (Teil des Namens, Groß-/Kleinschreibung egal), ohne {@code exclude}.
     * Reihenfolge: genauer Name, Namensanfang, online, alphabetisch.
     */
    static List<Candidate> filter(Collection<Candidate> all, @Nullable String query, Set<UUID> exclude, int limit) {
        String q = normalize(query);
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : all) {
            if (exclude.contains(c.uuid())) {
                continue;
            }
            if (q.isEmpty() || normalize(c.name()).contains(q)) {
                out.add(c);
            }
        }
        Comparator<Candidate> order = Comparator
                .comparing((Candidate c) -> !normalize(c.name()).equals(q))
                .thenComparing(c -> q.isEmpty() || !normalize(c.name()).startsWith(q))
                .thenComparing(c -> !c.online())
                .thenComparing(Candidate::name, String.CASE_INSENSITIVE_ORDER);
        out.sort(order);
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * Wen „Hinzufügen“ meint, wenn im Auswahlfeld nichts gewählt ist: genau passender Name, sonst der einzige Treffer.
     */
    @Nullable
    static Candidate resolve(List<Candidate> matches, @Nullable String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return null;
        }
        for (Candidate c : matches) {
            if (normalize(c.name()).equals(q)) {
                return c;
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    static String normalize(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
