/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.wireless;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ae2wireless.lang: gleiche Schlüssel und Platzhalter in beiden Sprachen, jeder im Code benutzte Schlüssel vorhanden
 * (auch die zusammengesetzten der Befehle).
 */
class WirelessLangTest {

    private static final String FILE = "ae2wireless";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");

    private static Map<String, String> entries(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/" + FILE + ".lang";
        try (InputStream in = WirelessLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            Map<String, String> out = new TreeMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = l.substring(0, eq).strip();
                assertTrue(out.put(key, l.substring(eq + 1).strip()) == null, "doppelter Schlüssel " + key);
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
        assertEquals(de.keySet(), en.keySet());
        for (String key : en.keySet()) {
            assertEquals(placeholders(en.get(key)), placeholders(de.get(key)), key + ": Platzhalter");
        }
    }

    @Test
    void benutzteSchluesselVorhanden() throws IOException {
        Set<String> known = entries("en-US").keySet();
        Pattern used = Pattern.compile(FILE + "\\.([A-Za-z0-9_.]+[A-Za-z0-9_])");
        Pattern command = Pattern.compile("super\\(\"([a-z]+)\"\\)");
        Pattern argument = Pattern.compile("intArg\\(\"([a-z]+)\"\\)");
        Pattern message = Pattern.compile("msg\\(\"([A-Za-z.]+)\"\\)|msg\\([^\"]*\"([A-Za-z.]+)\" : \"([A-Za-z.]+)\"\\)");
        Set<String> missing = new TreeSet<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = used.matcher(text);
                while (m.find()) {
                    String key = m.group(1);
                    found++;
                    boolean prefix = known.stream().anyMatch(k -> k.startsWith(key + "."));
                    if (!key.equals("lang") && !known.contains(key) && !prefix) {
                        missing.add(key);
                    }
                }
                if (!f.getFileName().toString().equals("WirelessCommands.java")) {
                    continue;
                }
                for (Matcher c = command.matcher(text); c.find(); ) {
                    check(known, missing, "cmd." + c.group(1) + ".desc");
                }
                for (Matcher a = argument.matcher(text); a.find(); ) {
                    check(known, missing, "cmd.arg." + a.group(1));
                }
                for (Matcher k = message.matcher(text); k.find(); ) {
                    for (int g = 1; g <= 3; g++) {
                        if (k.group(g) != null) {
                            check(known, missing, "cmd." + k.group(g));
                        }
                    }
                }
            }
        }
        assertTrue(found > 10, "Quellen nicht gefunden (Arbeitsordner?)");
        assertTrue(missing.isEmpty(), "fehlende Schlüssel in " + FILE + ".lang: " + missing);
    }

    private static void check(Set<String> known, Set<String> missing, String key) {
        if (!known.contains(key)) {
            missing.add(key);
        }
    }
}
