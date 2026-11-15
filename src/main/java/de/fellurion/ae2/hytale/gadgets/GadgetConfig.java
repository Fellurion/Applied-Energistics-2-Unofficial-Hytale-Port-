/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.core.AEConfig – matterCannonBlockDamage, tinyTntBlockDamage).
 */
package de.fellurion.ae2.hytale.gadgets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Einstellungen der Werkzeuge ({@code gadgets.json} im Datenordner des Plugins), Standard wie in AE2:
 * <ul>
 *   <li>{@code matterCannonBlockDamage} – die Matter Cannon darf Blöcke zerstören (AE2: true)</li>
 *   <li>{@code tinyTntBlockDamage} – Tiny TNT darf Blöcke zerstören (AE2: true)</li>
 * </ul>
 * Gelesen wird einmal beim Start; die Datei wird mit den Standardwerten angelegt, wenn sie fehlt.
 */
public final class GadgetConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile boolean matterCannonBlockDamage = true;
    private static volatile boolean tinyTntBlockDamage = true;
    @Nullable
    private static Path file;

    private GadgetConfig() {
    }

    static void load(Path dataDirectory, BiConsumer<String, Throwable> warning) {
        file = dataDirectory.resolve("gadgets.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                matterCannonBlockDamage = bool(o, "matterCannonBlockDamage", true);
                tinyTntBlockDamage = bool(o, "tinyTntBlockDamage", true);
            } catch (IOException | RuntimeException e) {
                warning.accept("AE2: could not read " + file + " – using defaults", e);
            }
            return;
        }
        try {
            Files.createDirectories(dataDirectory);
            JsonObject o = new JsonObject();
            o.addProperty("matterCannonBlockDamage", matterCannonBlockDamage);
            o.addProperty("tinyTntBlockDamage", tinyTntBlockDamage);
            Files.writeString(file, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            warning.accept("AE2: could not write " + file, e);
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
    }

    /** Darf die Matter Cannon Blöcke zerstören? */
    public static boolean matterCannonBlockDamage() {
        return matterCannonBlockDamage;
    }

    /** Darf Tiny TNT Blöcke zerstören? */
    public static boolean tinyTntBlockDamage() {
        return tinyTntBlockDamage;
    }

    /** Für Testbefehle: stellt die Schalter für diesen Serverlauf um (wird nicht gespeichert). */
    static void override(@Nullable Boolean cannon, @Nullable Boolean tnt) {
        if (cannon != null) {
            matterCannonBlockDamage = cannon;
        }
        if (tnt != null) {
            tinyTntBlockDamage = tnt;
        }
    }
}
