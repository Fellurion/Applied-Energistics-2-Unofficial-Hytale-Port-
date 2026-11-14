/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fellurion.ae2.hytale.BlockAccess.Dir;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Liest {@code ae2data/*.json} (beim Portieren aus dem AE2-Quellcode erzeugt). */
public final class ItemCatalog {

    /** Ein Eintrag aus {@code ae2data/items.json}. */
    public record Info(String ae2, String id, boolean block, @Nullable String kind, boolean part,
                       @Nullable String rotation, @Nullable Dir front, int slots) {
    }

    private static final Map<String, Info> BY_ID = new HashMap<>();

    private ItemCatalog() {
    }

    static void load() {
        BY_ID.clear();
        JsonElement root = JsonParser.parseString(resource("items.json"));
        JsonArray arr = root.getAsJsonArray();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            if (id == null) {
                continue;
            }
            BY_ID.put(id, new Info(str(o, "ae2"), id, o.has("block") && o.get("block").getAsBoolean(), str(o, "kind"),
                    o.has("part") && o.get("part").getAsBoolean(), str(o, "rotation"), dir(str(o, "front")),
                    o.has("slots") ? o.get("slots").getAsInt() : 0));
        }
    }

    @Nullable
    public static Info get(String itemId) {
        return BY_ID.get(itemId);
    }

    public static int size() {
        return BY_ID.size();
    }

    /** Liest eine Datei aus {@code ae2data/} im Jar. */
    public static String resource(String name) {
        try (InputStream in = ItemCatalog.class.getResourceAsStream("/ae2data/" + name)) {
            if (in == null) {
                throw new IllegalStateException("ae2data/" + name + " fehlt im Jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("ae2data/" + name + " nicht lesbar", e);
        }
    }

    @Nullable
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @Nullable
    private static Dir dir(@Nullable String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "+X" -> Dir.EAST;
            case "-X" -> Dir.WEST;
            case "+Y" -> Dir.UP;
            case "-Y" -> Dir.DOWN;
            case "+Z" -> Dir.SOUTH;
            case "-Z" -> Dir.NORTH;
            default -> null;
        };
    }
}
