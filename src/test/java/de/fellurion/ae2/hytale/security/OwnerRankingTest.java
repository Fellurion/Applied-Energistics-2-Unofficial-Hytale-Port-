/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Wer besitzt ein Netz? Ältester Block zuerst, dann Controller, dann Position; Fremde übernehmen nichts. */
class OwnerRankingTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static OwnerRanking.Candidate c(UUID owner, long since, boolean controller, int x, int y, int z) {
        return new OwnerRanking.Candidate(owner, "", since, controller, x, y, z);
    }

    @Test
    void ohneBesitzerNiemand() {
        assertNull(OwnerRanking.pick(List.of()));
    }

    @Test
    void aeltesterBlockGewinnt() {
        var alice = c(ALICE, 1_000, false, 50, 64, 50);
        var bob = c(BOB, 2_000, true, -100, 0, -100);
        // Bobs später angehängter Controller (kleinere Position) übernimmt Alices Netz nicht
        assertEquals(ALICE, OwnerRanking.pick(List.of(bob, alice)).owner());
    }

    @Test
    void altbestandSchlaegtNeueBloecke() {
        // Altbestand (ohne Zeitstempel = 0) ist immer älter als alles, was nach dem Update platziert wird
        var legacy = c(ALICE, 0, false, 99, 99, 99);
        var fresh = c(BOB, 5, true, 0, 0, 0);
        assertEquals(ALICE, OwnerRanking.pick(List.of(fresh, legacy)).owner());
    }

    @Test
    void gleichesAlterControllerZuerst() {
        var cable = c(BOB, 0, false, 0, 0, 0);
        var controller = c(ALICE, 0, true, 10, 10, 10);
        assertEquals(ALICE, OwnerRanking.pick(List.of(cable, controller)).owner());
    }

    @Test
    void gleichesAlterUndArtKleinstePosition() {
        var a = c(ALICE, 0, true, 1, 5, 0);
        var b = c(BOB, 0, true, 1, 4, 9);
        assertEquals(BOB, OwnerRanking.pick(List.of(a, b)).owner());
        assertEquals(BOB, OwnerRanking.pick(List.of(b, a)).owner());
    }

    @Test
    void blockBesitzerInKonfiguration() {
        Map<String, String> config = new HashMap<>();
        assertNull(BlockOwners.read(config));
        BlockOwners.write(config, ALICE, "Alice", 1234L);
        BlockOwners.Info info = BlockOwners.read(config);
        assertEquals(ALICE, info.owner());
        assertEquals("Alice", info.name());
        assertEquals(1234L, info.since());

        // Altbestand: ohne Zeitstempel und Namen
        BlockOwners.write(config, BOB, null, 0L);
        info = BlockOwners.read(config);
        assertEquals(BOB, info.owner());
        assertEquals("", info.name());
        assertEquals(0L, info.since());

        config.put(BlockOwners.OWNER, "kaputt");
        assertNull(BlockOwners.read(config));
    }

    @Test
    void anzeigenameOhneNamen() {
        assertEquals("Alice", Owner.displayName(ALICE, "Alice"));
        assertEquals("00000000", Owner.displayName(ALICE, null));
        assertEquals("00000000", Owner.displayName(ALICE, " "));
    }
}
