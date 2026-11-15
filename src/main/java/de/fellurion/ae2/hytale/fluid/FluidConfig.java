/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.fluid;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fellurion.ae2.core.stacks.AEFluidKey;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Einstellungen der Flüssigkeiten ({@code fluids.json} im Datenordner des Plugins). Ohne Datei gilt: Jede
 * Quell-Flüssigkeit des Spiels lässt sich lagern (Feuer ist keine), und der Holzeimer fasst Wasser.
 * <ul>
 *   <li>{@code deny} – Flüssigkeiten, die das ME-Netz nicht lagert (Asset-Kennung, z. B. {@code Lava_Source})</li>
 *   <li>{@code allow} – zusätzliche Flüssigkeiten, die das Plugin nicht von selbst als Quelle erkennt</li>
 *   <li>{@code icons} – Item, das eine Flüssigkeit im Terminal zeigt ({@code "Water_Source": "Fluid_Water"}); nötig
 *   nur für Flüssigkeiten aus Mods, zu denen sich kein Item findet</li>
 *   <li>{@code containers} – Behälter-Items: leeres Item, Zustand des gefüllten Items, Flüssigkeit, Inhalt in mB</li>
 * </ul>
 * Gelesen wird einmal beim Start; die Datei wird mit den Standardwerten angelegt, wenn sie fehlt.
 */
public final class FluidConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Ein Behälter-Item für eine Flüssigkeit.
     *
     * @param emptyItem   Item des leeren Behälters
     * @param filledState Zustand ({@code State}) des leeren Items, der den gefüllten Behälter ergibt
     * @param fluid       Kennung der Flüssigkeit
     * @param amount      Inhalt in mB
     */
    public record ContainerSpec(String emptyItem, String filledState, String fluid, long amount) {
    }

    /** Der Holzeimer des Spiels: der einzige Behälter, den es von Haus aus gibt (nur für Wasser). */
    static final ContainerSpec WATER_BUCKET = new ContainerSpec("Container_Bucket", "Filled_Water", "Water_Source",
            AEFluidKey.AMOUNT_BUCKET);

    private static volatile Set<String> allow = Set.of();
    private static volatile Set<String> deny = Set.of();
    private static volatile Map<String, String> icons = Map.of();
    private static volatile List<ContainerSpec> containers = List.of(WATER_BUCKET);

    private FluidConfig() {
    }

    static void load(Path dataDirectory, BiConsumer<String, Throwable> warning) {
        Path file = dataDirectory.resolve("fluids.json");
        if (Files.isRegularFile(file)) {
            try {
                read(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException | RuntimeException e) {
                warning.accept("AE2: could not read " + file + " – using defaults", e);
            }
            return;
        }
        try {
            Files.createDirectories(dataDirectory);
            Files.writeString(file, GSON.toJson(defaults()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            warning.accept("AE2: could not write " + file, e);
        }
    }

    private static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.add("deny", new JsonArray());
        o.add("allow", new JsonArray());
        o.add("icons", new JsonObject());
        JsonArray list = new JsonArray();
        JsonObject bucket = new JsonObject();
        bucket.addProperty("empty", WATER_BUCKET.emptyItem());
        bucket.addProperty("filledState", WATER_BUCKET.filledState());
        bucket.addProperty("fluid", WATER_BUCKET.fluid());
        bucket.addProperty("amount", WATER_BUCKET.amount());
        list.add(bucket);
        o.add("containers", list);
        return o;
    }

    /** Übernimmt die Werte einer gelesenen Datei; fehlende Felder behalten den Standard. */
    static void read(JsonObject o) {
        allow = strings(o, "allow");
        deny = strings(o, "deny");
        Map<String, String> iconMap = new LinkedHashMap<>();
        if (o.has("icons") && o.get("icons").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("icons").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    iconMap.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        icons = Map.copyOf(iconMap);
        if (o.has("containers") && o.get("containers").isJsonArray()) {
            List<ContainerSpec> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("containers")) {
                ContainerSpec spec = e.isJsonObject() ? container(e.getAsJsonObject()) : null;
                if (spec != null) {
                    list.add(spec);
                }
            }
            containers = List.copyOf(list);
        }
    }

    @Nullable
    private static ContainerSpec container(JsonObject o) {
        String empty = string(o, "empty");
        String state = string(o, "filledState");
        String fluid = string(o, "fluid");
        long amount = o.has("amount") && o.get("amount").isJsonPrimitive() ? o.get("amount").getAsLong()
                : AEFluidKey.AMOUNT_BUCKET;
        return empty == null || state == null || fluid == null || amount <= 0 ? null
                : new ContainerSpec(empty, state, fluid, amount);
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).getAsString().isBlank()
                ? o.get(key).getAsString() : null;
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> out = new LinkedHashSet<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                    out.add(e.getAsString());
                }
            }
        }
        return Set.copyOf(out);
    }

    /** Zusätzlich lagerbare Flüssigkeiten. */
    public static Set<String> allow() {
        return allow;
    }

    /** Nicht lagerbare Flüssigkeiten. */
    public static Set<String> deny() {
        return deny;
    }

    /** Vorgegebenes Anzeige-Item einer Flüssigkeit, oder null. */
    @Nullable
    public static String icon(String fluidId) {
        return icons.get(fluidId);
    }

    /** Die Behälter-Items in der Reihenfolge der Datei. */
    public static List<ContainerSpec> containers() {
        return containers;
    }
}
