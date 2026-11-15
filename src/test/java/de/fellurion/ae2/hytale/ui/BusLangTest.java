/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

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
 * ae2bus.lang (Konfiguration der Busse und des Interface, Priorität): gleiche Schlüssel und Platzhalter in beiden
 * Sprachen, jeder im Code benutzte Schlüssel vorhanden, kein Schlüssel ohne Verwendung.
 */
class BusLangTest {

    private static final String FILE = "ae2bus";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern USED = Pattern.compile("\"" + FILE + "\\.([A-Za-z0-9_.]*[A-Za-z0-9_])(\\.?)\"");

    private static Map<String, String> entries(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/" + FILE + ".lang";
        try (InputStream in = BusLangTest.class.getResourceAsStream(path)) {
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
        Set<String> prefixes = new TreeSet<>(); // zusammengesetzte Schlüssel: "ae2bus.setting.access." + …
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "Quellen nicht gefunden (Arbeitsordner?)");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = USED.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    (m.group(2).isEmpty() ? exact : prefixes).add(m.group(1));
                }
            }
        }
        assertTrue(exact.size() > 20, "Verwendungen nicht gefunden");

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

    /** Jede Einstellung, die die Seite weiterschalten kann, hat einen Text – für jeden Wert ihres Enums. */
    @Test
    void jederEinstellungswertHatEinenText() throws IOException {
        Set<String> known = entries("en-US").keySet();
        for (var mode : de.fellurion.ae2.core.config.Enums.RedstoneMode.values()) {
            assertTrue(known.contains("setting.redstone." + mode.name().toLowerCase()), mode.name());
        }
        for (var mode : de.fellurion.ae2.core.config.Enums.SchedulingMode.values()) {
            assertTrue(known.contains("setting.scheduling." + mode.name().toLowerCase()), mode.name());
        }
        for (var mode : de.fellurion.ae2.core.config.Enums.StorageFilter.values()) {
            assertTrue(known.contains("setting.storageFilter." + mode.name().toLowerCase()), mode.name());
        }
        for (String access : new String[]{"read", "write", "read_write"}) {
            assertTrue(known.contains("setting.access." + access), access);
        }
        for (var mode : de.fellurion.ae2.core.config.Enums.LockCraftingMode.values()) {
            assertTrue(known.contains("setting.lock." + mode.name().toLowerCase()), mode.name());
        }
        for (var mode : de.fellurion.ae2.core.config.Enums.CondenserOutput.values()) {
            assertTrue(known.contains("setting.condenser." + mode.name().toLowerCase()), mode.name());
        }
        for (String yesNo : new String[]{"yes", "no"}) {
            assertTrue(known.contains("setting.craftOnly." + yesNo));
            assertTrue(known.contains("setting.filterOnExtract." + yesNo));
            assertTrue(known.contains("setting.separateSides." + yesNo));
            assertTrue(known.contains("setting.autoExport." + yesNo));
            assertTrue(known.contains("setting.blocking." + yesNo));
            assertTrue(known.contains("setting.accessTerminal." + yesNo));
        }
    }
}
