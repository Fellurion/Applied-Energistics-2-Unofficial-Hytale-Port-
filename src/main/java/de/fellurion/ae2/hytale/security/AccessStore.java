/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Zugriffslisten je <b>Besitzer</b> (gilt für alle ME-Netze dieses Spielers und übersteht Umbauten am Netz):
 * {@code <Datenordner>/access/<besitzer-uuid>.json}.
 *
 * <pre>{@code
 * {
 *   "owner": "…uuid…",
 *   "ownerName": "Fellurion",
 *   "trusted": [ { "uuid": "…", "name": "Steve", "added": 1730800000000 } ]
 * }
 * }</pre>
 *
 * <p>Geladen wird beim ersten Zugriff (danach aus dem Speicher – Prüfungen laufen bei jeder Terminal-Aktion),
 * geschrieben sofort bei jeder Änderung über Temp-Datei und atomares Umbenennen.
 */
final class AccessStore {

    /** Ein eingetragener Spieler. */
    record Entry(UUID uuid, String name, long added) {
    }

    private static final class OwnerList {
        String ownerName = "";
        final LinkedHashMap<UUID, Entry> trusted = new LinkedHashMap<>();
    }

    private final Path dir;
    private final BiConsumer<String, Throwable> warn;
    private final Map<UUID, OwnerList> cache = new HashMap<>();

    /**
     * @param dir  Ordner der Listen (wird beim ersten Schreiben angelegt)
     * @param warn Meldung + Ursache bei Lese-/Schreibfehlern
     */
    AccessStore(Path dir, BiConsumer<String, Throwable> warn) {
        this.dir = dir;
        this.warn = warn;
    }

    Path file(UUID owner) {
        return dir.resolve(owner + ".json");
    }

    synchronized boolean isTrusted(UUID owner, UUID player) {
        return list(owner).trusted.containsKey(player);
    }

    /** Eingetragene Spieler in Eintragsreihenfolge. */
    synchronized List<Entry> trusted(UUID owner) {
        return new ArrayList<>(list(owner).trusted.values());
    }

    /**
     * Trägt {@code player} bei {@code owner} ein.
     *
     * @return false, wenn er schon drinsteht oder der Besitzer selbst ist
     */
    synchronized boolean add(UUID owner, @Nullable String ownerName, UUID player, String name, long now) {
        if (owner.equals(player)) {
            return false;
        }
        OwnerList l = list(owner);
        if (ownerName != null && !ownerName.isBlank()) {
            l.ownerName = ownerName;
        }
        if (l.trusted.containsKey(player)) {
            return false;
        }
        l.trusted.put(player, new Entry(player, name == null ? "" : name, now));
        save(owner, l);
        return true;
    }

    /** Austragen; false, wenn er nicht eingetragen war. */
    synchronized boolean remove(UUID owner, UUID player) {
        OwnerList l = list(owner);
        if (l.trusted.remove(player) == null) {
            return false;
        }
        save(owner, l);
        return true;
    }

    /** Aktualisiert gespeicherte Namen (Namenswechsel); speichert nur bei Änderung. */
    synchronized void rename(UUID owner, UUID player, String name) {
        OwnerList l = list(owner);
        Entry e = l.trusted.get(player);
        if (e != null && name != null && !name.isBlank() && !name.equals(e.name())) {
            l.trusted.put(player, new Entry(player, name, e.added()));
            save(owner, l);
        }
    }

    private OwnerList list(UUID owner) {
        return cache.computeIfAbsent(owner, this::load);
    }

    private OwnerList load(UUID owner) {
        OwnerList l = new OwnerList();
        Path f = file(owner);
        try {
            JsonObject root = JsonFiles.read(f);
            if (root == null) {
                return l;
            }
            l.ownerName = JsonFiles.string(root, "ownerName", "");
            JsonElement arr = root.get("trusted");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement e : arr.getAsJsonArray()) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = e.getAsJsonObject();
                    UUID id = JsonFiles.uuid(JsonFiles.string(o, "uuid", null));
                    if (id != null && !id.equals(owner)) {
                        l.trusted.put(id, new Entry(id, JsonFiles.string(o, "name", ""), JsonFiles.number(o, "added", 0L)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Kaputte Datei: leer weiterarbeiten (= nur der Besitzer), die Datei bleibt bis zur nächsten Änderung
            warn.accept("AE2: could not read access list: " + f, e);
        }
        return l;
    }

    private void save(UUID owner, OwnerList l) {
        JsonObject root = new JsonObject();
        root.addProperty("owner", owner.toString());
        root.addProperty("ownerName", l.ownerName);
        JsonArray arr = new JsonArray();
        for (Entry e : l.trusted.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.uuid().toString());
            o.addProperty("name", e.name());
            o.addProperty("added", e.added());
            arr.add(o);
        }
        root.add("trusted", arr);
        Path f = file(owner);
        try {
            JsonFiles.write(f, root);
        } catch (IOException | RuntimeException e) {
            warn.accept("AE2: could not save access list: " + f, e);
        }
    }
}
