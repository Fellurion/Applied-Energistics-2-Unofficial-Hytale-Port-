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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ae2ui.lang und ae2craft.lang (Terminals, Crafting-Terminal, Pattern-Terminals): gleiche Schlüssel und gleiche
 * Platzhalter in beiden Sprachen, jeder im Code/UI benutzte Schlüssel vorhanden.
 */
class UiLangTest {

    private static final String[] FILES = {"ae2ui", "ae2craft"};
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");

    private static Map<String, String> entries(String file, String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/" + file + ".lang";
        try (InputStream in = UiLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            Map<String, String> out = new TreeMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                    continue;
                }
                String key = l.substring(0, eq).strip();
                assertTrue(out.put(key, l.substring(eq + 1).strip()) == null, "doppelter Schlüssel " + key + " in " + path);
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
        for (String file : FILES) {
            Map<String, String> de = entries(file, "de-DE");
            Map<String, String> en = entries(file, "en-US");
            assertEquals(de.keySet(), en.keySet(), file);
            for (String key : en.keySet()) {
                assertEquals(placeholders(en.get(key)), placeholders(de.get(key)), file + "." + key + ": Platzhalter");
            }
        }
    }

    @Test
    void benutzteSchluesselVorhanden() throws IOException {
        for (String file : FILES) {
            Set<String> known = entries(file, "en-US").keySet();
            Pattern used = Pattern.compile(file + "\\.([A-Za-z0-9_.]+[A-Za-z0-9_])");
            Set<String> missing = new TreeSet<>();
            int found = 0;
            for (Path root : new Path[]{Path.of("src/main/java"), Path.of("src/main/resources/Common/UI/Custom/Pages/AE2")}) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".ui")).toList()) {
                        Matcher m = used.matcher(Files.readString(f, StandardCharsets.UTF_8));
                        while (m.find()) {
                            String key = m.group(1);
                            found++;
                            // "ae2ui.lang" in Kommentaren; zusammengesetzte Schlüssel ("ae2ui.terminal.sort." + …)
                            // erscheinen als Präfix vorhandener Schlüssel
                            boolean prefix = known.stream().anyMatch(k -> k.startsWith(key + "."));
                            if (!key.equals("lang") && !known.contains(key) && !prefix) {
                                missing.add(key);
                            }
                        }
                    }
                }
            }
            assertTrue(found > 10, "Quellen nicht gefunden (Arbeitsordner?)");
            assertTrue(missing.isEmpty(), "fehlende Schlüssel in " + file + ".lang: " + missing);
        }
    }
}
