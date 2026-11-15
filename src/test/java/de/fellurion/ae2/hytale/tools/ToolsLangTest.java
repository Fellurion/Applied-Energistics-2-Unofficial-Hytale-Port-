/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.tools;

import de.fellurion.ae2.core.tools.MemoryCardSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ae2tools.lang (Schraubenschlüssel, Network Tool, Memory Card, Schneidmesser): gleiche Schlüssel und Platzhalter in
 * beiden Sprachen, jeder im Code oder in einer Seite benutzte Schlüssel vorhanden, kein Schlüssel ohne Verwendung.
 */
class ToolsLangTest {

    private static final String FILE = "ae2tools";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern USED = Pattern.compile("\"" + FILE + "\\.([A-Za-z0-9_.]*[A-Za-z0-9_])(\\.?)\"");
    private static final Pattern USED_IN_UI = Pattern.compile("%" + FILE + "\\.([A-Za-z0-9_.]*[A-Za-z0-9_])");

    private static Map<String, String> entries(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/" + FILE + ".lang";
        try (InputStream in = ToolsLangTest.class.getResourceAsStream(path)) {
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

    @Test
    void beideSprachenGleich() throws IOException {
        Map<String, String> de = entries("de-DE");
        Map<String, String> en = entries("en-US");
        assertEquals(en.keySet(), de.keySet());
        for (String key : en.keySet()) {
            assertEquals(placeholders(en.get(key)), placeholders(de.get(key)), key + ": Platzhalter");
        }
    }

    @Test
    void benutzteSchluesselVorhandenUndKeinerUnbenutzt() throws IOException {
        Set<String> known = entries("en-US").keySet();
        Set<String> exact = new TreeSet<>();
        Set<String> prefixes = new TreeSet<>(); // zusammengesetzte Schlüssel: "ae2tools.card.part." + …
        Path java = Path.of("src/main/java");
        Path ui = Path.of("src/main/resources/Common/UI/Custom/Pages/AE2");
        assertTrue(Files.isDirectory(java) && Files.isDirectory(ui), "Quellen nicht gefunden (Arbeitsordner?)");
        try (Stream<Path> files = Files.walk(java)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = USED.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    (m.group(2).isEmpty() ? exact : prefixes).add(m.group(1));
                }
            }
        }
        try (Stream<Path> files = Files.walk(ui)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".ui")).toList()) {
                Matcher m = USED_IN_UI.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    exact.add(m.group(1));
                }
            }
        }
        assertTrue(exact.size() > 40, "Verwendungen nicht gefunden");

        Set<String> missing = new TreeSet<>();
        for (String key : exact) {
            if (!known.contains(key)) {
                missing.add(key);
            }
        }
        for (String prefix : prefixes) {
            if (known.stream().noneMatch(k -> k.startsWith(prefix + "."))) {
                missing.add(prefix + ".*");
            }
        }
        assertTrue(missing.isEmpty(), "fehlende Schlüssel in " + FILE + ".lang: " + missing);

        Set<String> unused = new TreeSet<>();
        for (String key : known) {
            if (!exact.contains(key) && prefixes.stream().noneMatch(p -> key.startsWith(p + "."))) {
                unused.add(key);
            }
        }
        assertTrue(unused.isEmpty(), "unbenutzte Schlüssel in " + FILE + ".lang: " + unused);
    }

    /** Jeder Teil einer Konfiguration, den die Memory Card melden kann, hat einen Namen. */
    @Test
    void jederTeilDerKonfigurationHatEinenNamen() throws IOException {
        Set<String> known = entries("en-US").keySet();
        for (MemoryCardSettings.Part part : MemoryCardSettings.Part.values()) {
            assertTrue(known.contains("card.part." + part.id()), part.name());
        }
    }

    /** Meldungen der Testbefehle beginnen mit „[AE2] “ – daran erkennt sie ein Test auf einem Server ohne Client. */
    @Test
    void befehlsmeldungenTragenDasKennzeichen() throws IOException {
        for (String locale : new String[]{"en-US", "de-DE"}) {
            for (Map.Entry<String, String> e : entries(locale).entrySet()) {
                boolean result = e.getKey().startsWith("cmd.") && !e.getKey().endsWith(".desc")
                        && !e.getKey().startsWith("cmd.arg.");
                assertEquals(result, e.getValue().startsWith("[AE2] "), locale + " " + e.getKey());
            }
        }
    }
}
