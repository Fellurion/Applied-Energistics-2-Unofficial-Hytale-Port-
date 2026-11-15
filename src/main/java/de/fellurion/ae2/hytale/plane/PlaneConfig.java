/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: Tag ae2:blacklisted/annihilation_plane und die Plane-Einträge von appeng.core.AEConfig).
 */
package de.fellurion.ae2.hytale.plane;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fellurion.ae2.core.automation.ItemPlacementStrategy;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Einstellungen der Planes aus {@code planes.json} im Datenordner des Plugins:
 * <pre>
 * {
 *   "annihilationPlane": {
 *     "blockedBlocks": ["Portal_*", ...],   // Blöcke, die keine Annihilation Plane abbaut
 *     "blockedItems": [],                   // herumliegende Items, die sie nicht einsammelt
 *     "skyDustGeneration": true             // ganz oben nach oben gerichtet: Sky Stone Dust erzeugen
 *   },
 *   "formationPlane": { "entityLimit": 128 }  // so viele Items dürfen im Umkreis liegen, bevor sie nichts mehr auswirft
 * }
 * </pre>
 * Einträge sind Block- bzw. Item-Ids; {@code *} steht für beliebig viele Zeichen. Geprüft wird die Id des Blocks
 * und die seines Items (Zustandsvarianten eines Blocks haben eigene Ids, aber dasselbe Item). Fehlt die Datei, wird
 * sie mit den Standardwerten angelegt.
 */
public final class PlaneConfig {

    /**
     * Standard-Sperrliste: Portale, Ausgänge von Instanzen, Kontrollpunkte und Spawner. Wer so etwas abbaut, sperrt
     * Spieler aus oder legt eine Begegnung lahm – das soll kein Automat versehentlich tun.
     */
    static final List<String> DEFAULT_BLOCKED_BLOCKS = List.of("Portal_*", "*_Portal", "*_Portal_*", "Instance_Gateway",
            "Leave_Instance", "Dungeon_Checkpoint_Statue", "GoblinLab_Teleporter", "*Spawner*");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile Snapshot snapshot = new Snapshot(DEFAULT_BLOCKED_BLOCKS, List.of(), true);

    /** Ein gelesener Stand; die Sperrlisten sind schon in Muster zerlegt. */
    private record Snapshot(List<String> blockedBlocks, List<String> blockedItems, boolean skyDust) {
    }

    private PlaneConfig() {
    }

    /** Liest {@code planes.json} (legt sie beim ersten Start an). Kaputte Dateien: Standardwerte und eine Warnung. */
    static void load(Path dataDirectory, BiConsumer<String, Throwable> warning) {
        Path file = dataDirectory.resolve("planes.json");
        Snapshot loaded = new Snapshot(DEFAULT_BLOCKED_BLOCKS, List.of(), true);
        int entityLimit = ItemPlacementStrategy.DEFAULT_ENTITY_LIMIT;
        try {
            if (Files.isRegularFile(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject ap = object(root, "annihilationPlane");
                loaded = new Snapshot(strings(ap, "blockedBlocks", DEFAULT_BLOCKED_BLOCKS), strings(ap, "blockedItems", List.of()),
                        !ap.has("skyDustGeneration") || ap.get("skyDustGeneration").getAsBoolean());
                JsonObject fp = object(root, "formationPlane");
                if (fp.has("entityLimit")) {
                    entityLimit = fp.get("entityLimit").getAsInt();
                }
            } else {
                Files.createDirectories(dataDirectory);
                Files.writeString(file, GSON.toJson(defaults()), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            warning.accept("AE2: could not read planes.json, using the defaults", e);
        }
        snapshot = loaded;
        ItemPlacementStrategy.setEntityLimit(entityLimit);
        PlaneBlocks.forgetCaches();
    }

    private static JsonObject defaults() {
        JsonObject ap = new JsonObject();
        JsonArray blocks = new JsonArray();
        DEFAULT_BLOCKED_BLOCKS.forEach(blocks::add);
        ap.add("blockedBlocks", blocks);
        ap.add("blockedItems", new JsonArray());
        ap.addProperty("skyDustGeneration", true);
        JsonObject fp = new JsonObject();
        fp.addProperty("entityLimit", ItemPlacementStrategy.DEFAULT_ENTITY_LIMIT);
        JsonObject root = new JsonObject();
        root.add("annihilationPlane", ap);
        root.add("formationPlane", fp);
        return root;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static List<String> strings(JsonObject parent, String key, List<String> fallback) {
        JsonElement e = parent.get(key);
        if (e == null || !e.isJsonArray()) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement el : e.getAsJsonArray()) {
            if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                out.add(el.getAsString().trim());
            }
        }
        return List.copyOf(out);
    }

    /** Steht der Block (seine Id oder die seines Items) auf der Sperrliste? Nicht zwischengespeichert. */
    static boolean blockBlocked(String blockId, @Nullable String itemId) {
        return matchesAny(snapshot.blockedBlocks(), blockId) || itemId != null && matchesAny(snapshot.blockedBlocks(), itemId);
    }

    /** Sammelt die Annihilation Plane dieses Item nicht ein? */
    static boolean itemBlocked(String itemId) {
        List<String> list = snapshot.blockedItems();
        return !list.isEmpty() && matchesAny(list, itemId);
    }

    /** AE2: {@code annihilationPlaneSkyDustGeneration}. */
    static boolean skyDustGeneration() {
        return snapshot.skyDust();
    }

    private static boolean matchesAny(List<String> patterns, String id) {
        for (String pattern : patterns) {
            if (matches(pattern, id)) {
                return true;
            }
        }
        return false;
    }

    /** Vergleich mit {@code *} als Platzhalter für beliebig viele Zeichen; Groß-/Kleinschreibung zählt. */
    static boolean matches(String pattern, String id) {
        int star = pattern.indexOf('*');
        if (star < 0) {
            return pattern.equals(id);
        }
        String head = pattern.substring(0, star);
        if (!id.startsWith(head)) {
            return false;
        }
        int at = head.length();
        String rest = pattern.substring(star + 1);
        while (true) {
            int next = rest.indexOf('*');
            if (next < 0) {
                // letztes Stück muss am Ende stehen
                return id.length() - rest.length() >= at && id.endsWith(rest);
            }
            String part = rest.substring(0, next);
            int found = id.indexOf(part, at);
            if (found < 0) {
                return false;
            }
            at = found + part.length();
            rest = rest.substring(next + 1);
        }
    }
}
