/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Kleiner Namensspeicher aller Spieler, die schon einmal da waren ({@code <Datenordner>/access/players.json}):
 * Damit lassen sich auch Spieler eintragen, die gerade offline sind, und Besitzer/Einträge zeigen aktuelle Namen.
 *
 * <pre>{@code { "<uuid>": { "name": "Steve", "seen": 1730800000000 }, … } }</pre>
 *
 * <p>Gespeichert wird bei neuen Spielern und Namenswechseln sofort, „zuletzt gesehen“ nur bei {@link #flush()}
 * (beim Verlassen des Servers). Höchstens {@value #MAX} Einträge; darüber fliegen die am längsten nicht gesehenen raus.
 */
final class KnownPlayers {

    static final int MAX = 2000;

    record Known(UUID uuid, String name, long seen) {
    }

    private final Path file;
    private final BiConsumer<String, Throwable> warn;
    private final Map<UUID, Known> players = new HashMap<>();
    private boolean loaded;
    private boolean dirty;

    KnownPlayers(Path file, BiConsumer<String, Throwable> warn) {
        this.file = file;
        this.warn = warn;
    }

    /** Merkt sich Name und Zeitpunkt; speichert sofort, wenn der Spieler neu ist oder anders heißt. */
    synchronized void remember(UUID uuid, @Nullable String name, long now) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        load();
        Known old = players.get(uuid);
        players.put(uuid, new Known(uuid, name, now));
        if (old == null || !old.name().equals(name)) {
            trim();
            save();
        } else if (old.seen() != now) {
            dirty = true;
        }
    }

    @Nullable
    synchronized String name(UUID uuid) {
        load();
        Known k = players.get(uuid);
        return k == null ? null : k.name();
    }

    synchronized List<Known> all() {
        load();
        return new ArrayList<>(players.values());
    }

    /** Schreibt ausstehende „zuletzt gesehen“-Zeiten. */
    synchronized void flush() {
        if (dirty) {
            save();
        }
    }

    private void trim() {
        if (players.size() <= MAX) {
            return;
        }
        List<Known> byAge = new ArrayList<>(players.values());
        byAge.sort(Comparator.comparingLong(Known::seen));
        for (int i = 0; i < byAge.size() - MAX; i++) {
            players.remove(byAge.get(i).uuid());
        }
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            JsonObject root = JsonFiles.read(file);
            if (root == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                UUID id = JsonFiles.uuid(e.getKey());
                if (id == null || !e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getValue().getAsJsonObject();
                String name = JsonFiles.string(o, "name", "");
                if (!name.isBlank()) {
                    players.put(id, new Known(id, name, JsonFiles.number(o, "seen", 0L)));
                }
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("AE2-Spielernamen nicht lesbar: " + file, e);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        List<Known> sorted = new ArrayList<>(players.values());
        sorted.sort(Comparator.comparing(Known::name, String.CASE_INSENSITIVE_ORDER));
        for (Known k : sorted) {
            JsonObject o = new JsonObject();
            o.addProperty("name", k.name());
            o.addProperty("seen", k.seen());
            root.add(k.uuid().toString(), o);
        }
        try {
            JsonFiles.write(file, root);
            dirty = false;
        } catch (IOException | RuntimeException e) {
            warn.accept("AE2-Spielernamen nicht speicherbar: " + file, e);
        }
    }
}
