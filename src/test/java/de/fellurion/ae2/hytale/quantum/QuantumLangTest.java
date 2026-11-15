/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.quantum;

import de.fellurion.ae2.core.quantum.QuantumBridgeLogic.Formation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ae2quantum.lang: gleiche Schlüssel und Platzhalter in beiden Sprachen, jeder im Code benutzte Schlüssel vorhanden,
 * kein Schlüssel ohne Verwendung, keine Zeile zu lang für die Blickanzeige.
 */
class QuantumLangTest {

    private static final String FILE = "ae2quantum";
    private static final Path SOURCES = Path.of("src/main/java/de/fellurion/ae2/hytale/quantum");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern LITERAL = Pattern.compile("\"([A-Za-z0-9_.]+)\"");
    /** Breiteste Zeile der Blickanzeige: (440 − 52 − 12) px bei 6,3 px je Zeichen (siehe HudLayout). */
    private static final int HUD_MAX_CHARS = 59;

    private static Map<String, String> entries(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/" + FILE + ".lang";
        try (InputStream in = QuantumLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            Map<String, String> out = new TreeMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = l.substring(0, eq).strip();
                String value = l.substring(eq + 1).strip();
                assertFalse(value.isEmpty(), "leerer Text für " + key + " in " + path);
                assertTrue(out.put(key, value) == null, "doppelter Schlüssel " + key + " in " + path);
            }
            return out;
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** Alle Zeichenketten einer Quelldatei, die wie (Teil-)Schlüssel aussehen. */
    private static Set<String> literals(String file) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = LITERAL.matcher(Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1));
        }
        assertFalse(out.isEmpty(), "Quelle nicht gefunden (Arbeitsordner?): " + file);
        return out;
    }

    @Test
    void beideSprachenGleich() throws IOException {
        Map<String, String> de = entries("de-DE");
        Map<String, String> en = entries("en-US");
        assertEquals(en.keySet(), de.keySet());
        for (String key : en.keySet()) {
            assertEquals(placeholders(en.get(key)), placeholders(de.get(key)), "Platzhalter von " + key);
        }
    }

    @Test
    void schluesselDerBlickanzeige() throws IOException {
        Set<String> known = new TreeSet<>();
        for (String key : entries("en-US").keySet()) {
            if (key.startsWith("hud.")) {
                known.add(key.substring(4));
            }
        }
        // Im Code stehen die Schlüssel ohne das Präfix "ae2quantum.hud."; die Gründe hängen am Enum
        Set<String> used = new TreeSet<>();
        for (String literal : literals("QuantumHud.java")) {
            if (known.contains(literal)) {
                used.add(literal);
            } else {
                assertFalse(literal.contains(".") && !literal.endsWith(".") && !literal.startsWith("ae2"),
                        "unbekannter Schlüssel hud." + literal);
            }
        }
        for (Formation f : Formation.values()) {
            if (f != Formation.FORMED) {
                String key = "reason." + f.name().toLowerCase(Locale.ROOT);
                assertTrue(known.contains(key), "fehlt: hud." + key);
                used.add(key);
            }
        }
        assertEquals(known, used, "Schlüssel ohne Verwendung in QuantumHud");
    }

    @Test
    void schluesselDerBefehle() throws IOException {
        Set<String> known = new TreeSet<>();
        for (String key : entries("en-US").keySet()) {
            if (key.startsWith("cmd.")) {
                known.add(FILE + "." + key);
            }
        }
        Set<String> used = new TreeSet<>();
        for (String literal : literals("QuantumCommands.java")) {
            if (literal.startsWith(FILE + ".")) {
                used.add(literal);
            }
        }
        assertEquals(known, used);
    }

    @Test
    void zeilenPassenInDieBlickanzeige() throws IOException {
        for (String locale : new String[]{"en-US", "de-DE"}) {
            Map<String, String> all = entries(locale);
            for (Map.Entry<String, String> e : all.entrySet()) {
                if (!e.getKey().startsWith("hud.")) {
                    continue;
                }
                // Zeilen „Beschriftung  Wert“: die längste Beschriftung plus zwei Leerzeichen kommt davor
                int label = 0;
                if (e.getKey().startsWith("hud.link.")) {
                    label = all.get("hud.link").length() + 2;
                } else if (e.getKey().startsWith("hud.power.")) {
                    label = all.get("hud.power").length() + 2;
                } else if (e.getKey().startsWith("hud.block.")) {
                    label = all.get("hud.block").length() + 2;
                }
                // Platzhalter: Koordinaten bis je 7 Zeichen, sonst kurze Zahlen
                int extra = e.getKey().equals("hud.link.linkedAt") ? 3 * 4 : 0;
                assertTrue(label + e.getValue().length() + extra <= HUD_MAX_CHARS,
                        locale + " " + e.getKey() + " ist zu lang: " + (label + e.getValue().length() + extra));
            }
        }
    }
}
