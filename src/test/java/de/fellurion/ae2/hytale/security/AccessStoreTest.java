/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zugriffslisten und Namensspeicher: Speichern, Laden, kaputte Dateien. */
class AccessStoreTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STEVE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ALEX = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final List<String> warnings = new ArrayList<>();
    private final BiConsumer<String, Throwable> warn = (m, e) -> warnings.add(m);

    @Test
    void eintragenAustragenUndNeuLaden(@TempDir Path dir) {
        AccessStore store = new AccessStore(dir, warn);
        assertFalse(store.isTrusted(OWNER, STEVE));
        assertTrue(store.add(OWNER, "Owner", STEVE, "Steve", 10));
        assertFalse(store.add(OWNER, "Owner", STEVE, "Steve", 11), "doppelt");
        assertFalse(store.add(OWNER, "Owner", OWNER, "Owner", 12), "Besitzer selbst");
        assertTrue(store.add(OWNER, "Owner", ALEX, "Alex", 13));
        assertTrue(Files.isRegularFile(store.file(OWNER)));
        assertFalse(Files.exists(dir.resolve(OWNER + ".json.tmp")), "Temp-Datei umbenannt");

        AccessStore reloaded = new AccessStore(dir, warn);
        assertTrue(reloaded.isTrusted(OWNER, STEVE));
        assertTrue(reloaded.isTrusted(OWNER, ALEX));
        List<AccessStore.Entry> list = reloaded.trusted(OWNER);
        assertEquals(List.of(STEVE, ALEX), list.stream().map(AccessStore.Entry::uuid).toList());
        assertEquals("Steve", list.get(0).name());
        assertEquals(10, list.get(0).added());

        assertTrue(reloaded.remove(OWNER, STEVE));
        assertFalse(reloaded.remove(OWNER, STEVE));
        assertFalse(new AccessStore(dir, warn).isTrusted(OWNER, STEVE));
        assertTrue(new AccessStore(dir, warn).isTrusted(OWNER, ALEX));
        // Listen sind je Besitzer
        assertFalse(reloaded.isTrusted(ALEX, STEVE));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    void namenswechselWirdGespeichert(@TempDir Path dir) {
        AccessStore store = new AccessStore(dir, warn);
        store.add(OWNER, "Owner", STEVE, "Steve", 1);
        store.rename(OWNER, STEVE, "Steve2");
        assertEquals("Steve2", new AccessStore(dir, warn).trusted(OWNER).get(0).name());
    }

    @Test
    void kaputteDateiHeisstLeer(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(OWNER + ".json"), "{ nicht json", StandardCharsets.UTF_8);
        AccessStore store = new AccessStore(dir, warn);
        assertFalse(store.isTrusted(OWNER, STEVE));
        assertEquals(1, warnings.size());
    }

    @Test
    void namensspeicher(@TempDir Path dir) {
        Path file = dir.resolve("players.json");
        KnownPlayers players = new KnownPlayers(file, warn);
        assertNull(players.name(STEVE));
        players.remember(STEVE, "Steve", 100);
        players.remember(ALEX, "Alex", 200);
        players.remember(STEVE, "Steve", 300);
        players.flush();

        KnownPlayers reloaded = new KnownPlayers(file, warn);
        assertEquals("Steve", reloaded.name(STEVE));
        assertEquals("Alex", reloaded.name(ALEX));
        assertEquals(300, reloaded.all().stream().filter(k -> k.uuid().equals(STEVE)).findFirst().orElseThrow().seen());

        reloaded.remember(STEVE, "Stevie", 400);
        assertEquals("Stevie", new KnownPlayers(file, warn).name(STEVE));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }
}
