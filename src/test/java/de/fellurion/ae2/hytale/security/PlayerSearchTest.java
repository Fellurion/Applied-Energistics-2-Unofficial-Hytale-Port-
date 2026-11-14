/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spielerauswahl der Zugriffsseite. */
class PlayerSearchTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STEFAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MAX = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static PlayerSearch.Candidate c(UUID id, String name, boolean online) {
        return new PlayerSearch.Candidate(id, name, online);
    }

    private static List<PlayerSearch.Candidate> all() {
        return PlayerSearch.merge(
                List.of(c(STEFAN, "Stefan", true), c(MAX, "Max", true)),
                List.of(c(STEVE, "Steve", false), c(ALEX, "Alex", false), c(STEFAN, "StefanAlt", false)));
    }

    private static List<String> names(List<PlayerSearch.Candidate> l) {
        return l.stream().map(PlayerSearch.Candidate::name).toList();
    }

    @Test
    void onlineGewinntBeimZusammenfuehren() {
        PlayerSearch.Candidate stefan = all().stream().filter(x -> x.uuid().equals(STEFAN)).findFirst().orElseThrow();
        assertTrue(stefan.online());
        assertEquals("Stefan", stefan.name());
    }

    @Test
    void ohneSucheOnlineZuerstDannAlphabetisch() {
        assertEquals(List.of("Max", "Stefan", "Alex", "Steve"), names(PlayerSearch.filter(all(), "", Set.of(), 10)));
    }

    @Test
    void sucheFiltertUndSortiertNachGenauigkeit() {
        // beide beginnen mit "ste": der Online-Spieler zuerst
        assertEquals(List.of("Stefan", "Steve"), names(PlayerSearch.filter(all(), "ste", Set.of(), 10)));
        // genauer Name vor allem anderen
        assertEquals(List.of("Steve"), names(PlayerSearch.filter(all(), " STEVE ", Set.of(), 10)));
        // Namensanfang vor Teiltreffer (Max ist online, „a“ steckt aber nur mittendrin)
        assertEquals(List.of("Alex", "Max", "Stefan"), names(PlayerSearch.filter(all(), "a", Set.of(), 10)));
    }

    @Test
    void ausgeschlosseneUndGrenze() {
        assertEquals(List.of("Max", "Alex", "Steve"), names(PlayerSearch.filter(all(), "", Set.of(STEFAN), 10)));
        assertEquals(2, PlayerSearch.filter(all(), "", Set.of(), 2).size());
    }

    @Test
    void hinzufuegenOhneAuswahl() {
        List<PlayerSearch.Candidate> ste = PlayerSearch.filter(all(), "ste", Set.of(), 10);
        assertNull(PlayerSearch.resolve(ste, "ste"), "mehrdeutig");
        assertEquals(STEVE, PlayerSearch.resolve(PlayerSearch.filter(all(), "steve", Set.of(), 10), "steve").uuid());
        assertEquals(ALEX, PlayerSearch.resolve(PlayerSearch.filter(all(), "al", Set.of(), 10), "al").uuid());
        assertNull(PlayerSearch.resolve(PlayerSearch.filter(all(), "", Set.of(), 10), ""));
    }
}
