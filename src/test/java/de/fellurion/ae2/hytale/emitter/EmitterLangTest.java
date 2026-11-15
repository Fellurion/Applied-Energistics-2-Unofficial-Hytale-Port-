/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.emitter;

import de.fellurion.ae2.core.config.Enums.RedstoneMode;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ae2emitter.lang (Emitter, Lichtsensor, Monitore, Signal): gleiche Schlüssel und Platzhalter in beiden Sprachen,
 * jeder im Code benutzte Schlüssel vorhanden, kein Schlüssel ohne Verwendung.
 */
class EmitterLangTest {

    private static final String FILE = "ae2emitter";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    /** Voller Schlüssel irgendwo im Code; Gruppe 2 = endet auf einen Punkt (zusammengesetzter Schlüssel). */
    private static final Pattern USED = Pattern.compile("\"" + FILE + "\\.([A-Za-z0-9_.]*[A-Za-z0-9_])(\\.?)\"");
    /** In diesem Bereich: Blickanzeige {@code tr(ctx, "hud.x")} und Befehle {@code msg("x")} ohne Präfix. */
    private static final Pattern HUD = Pattern.compile("\"(hud\\.[A-Za-z0-9_.]*[A-Za-z0-9_])(\\.?)\"");
    private static final Pattern COMMAND = Pattern.compile("msg\\(([^;{]*?)\\)[;.\\n]", Pattern.DOTALL);
    private static final Pattern LITERAL = Pattern.compile("\"([A-Za-z0-9_.]+)\"");
    private static final Pattern COMMAND_NAME = Pattern.compile("super\\(\"([a-z]+)\"");

    private static Map<String, String> entries(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/" + FILE + ".lang";
        try (InputStream in = EmitterLangTest.class.getResourceAsStream(path)) {
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
        Set<String> prefixes = new TreeSet<>();
        Path root = Path.of("src/main/java");
        assertTrue(Files.isDirectory(root), "Quellen nicht gefunden (Arbeitsordner?)");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = USED.matcher(source);
                while (m.find()) {
                    (m.group(2).isEmpty() ? exact : prefixes).add(m.group(1));
                }
                String name = f.getFileName().toString();
                if (name.equals("EmitterHud.java")) {
                    Matcher h = HUD.matcher(source);
                    while (h.find()) {
                        (h.group(2).isEmpty() ? exact : prefixes).add(h.group(1));
                    }
                } else if (name.equals("EmitterCommands.java")) {
                    Matcher c = COMMAND.matcher(source);
                    while (c.find()) {
                        Matcher lit = LITERAL.matcher(c.group(1));
                        while (lit.find()) {
                            exact.add("cmd." + lit.group(1));
                        }
                    }
                    Matcher n = COMMAND_NAME.matcher(source);
                    while (n.find()) {
                        exact.add("cmd." + n.group(1) + ".desc");
                    }
                }
            }
        }
        assertTrue(exact.size() > 60, "Verwendungen nicht gefunden: " + exact.size());

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

    /** Die Signalmodi der Emitter haben für beide erlaubten Werte einen Text – am Item- und am Energie-Emitter. */
    @Test
    void jederSignalmodusHatEinenText() throws IOException {
        Set<String> known = entries("en-US").keySet();
        for (RedstoneMode mode : new RedstoneMode[]{RedstoneMode.HIGH_SIGNAL, RedstoneMode.LOW_SIGNAL}) {
            String name = mode.name().toLowerCase(Locale.ROOT);
            assertTrue(known.contains("setting.level." + name), name);
            assertTrue(known.contains("setting.energy." + name), name);
        }
        assertTrue(known.contains("setting.craft.yes") && known.contains("setting.craft.no"));
    }
}
