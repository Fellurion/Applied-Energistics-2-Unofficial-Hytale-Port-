/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ae2access.lang: gleiche Schlüssel in beiden Sprachen, jeder im Code/UI benutzte Schlüssel vorhanden. */
class AccessLangTest {

    private static final Pattern USED = Pattern.compile("ae2access\\.([A-Za-z0-9_.]+[A-Za-z0-9_])");

    private static Set<String> keys(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/ae2access.lang";
        try (InputStream in = AccessLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            Set<String> keys = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                    continue;
                }
                keys.add(l.substring(0, eq).strip());
            }
            return keys;
        }
    }

    @Test
    void beideSprachenGleich() throws IOException {
        assertEquals(keys("de-DE"), keys("en-US"));
    }

    @Test
    void benutzteSchluesselVorhanden() throws IOException {
        Set<String> known = keys("de-DE");
        Set<String> used = new TreeSet<>();
        for (Path root : new Path[]{Path.of("src/main/java"), Path.of("src/main/resources/Common/UI/Custom/Pages/AE2")}) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".ui")).toList()) {
                    Matcher m = USED.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) {
                        used.add(m.group(1));
                    }
                }
            }
        }
        // "ae2access." als Präfix in Kommentaren/Dateinamen ist kein Schlüssel
        used.remove("lang");
        assertTrue(used.size() > 20, "Quellen nicht gefunden (Arbeitsordner?): " + used);
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(known);
        assertTrue(missing.isEmpty(), "fehlende Schlüssel in ae2access.lang: " + missing);
    }
}
