/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.tools;

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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Seiten der Werkzeuge passen zu ihrem Java-Code: Jedes Element, das der Server setzt oder an das er ein Ereignis
 * bindet, gibt es in der Datei – ein Befehl an ein fehlendes Element lässt den Client die Seite verwerfen.
 */
class ToolsUiTest {

    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)\\s*\\{");
    private static final Pattern SELECTOR = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)[.\"\\[]");
    private static final Pattern PATH = Pattern.compile("\"(Pages/AE2/[A-Za-z]+\\.ui)\"");

    private static String resource(String path) throws IOException {
        try (InputStream in = ToolsUiTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> ids(String file) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = ID.matcher(resource("/Common/UI/Custom/Pages/AE2/" + file));
        while (m.find()) {
            assertTrue(out.add(m.group(1)) || m.group(1).equals("Title") || m.group(1).equals("Content"),
                    "doppeltes Element #" + m.group(1) + " in " + file);
        }
        return out;
    }

    private static String java(String file) throws IOException {
        Path p = Path.of("src/main/java/de/fellurion/ae2/hytale/tools", file);
        assertTrue(Files.isRegularFile(p), "Quelle nicht gefunden (Arbeitsordner?): " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** Im Java-Code als Selektor benutzte Elemente ({@code "#Name.Text"}, {@code " #Name.Text"}, {@code "#List["}). */
    private static Set<String> used(String source) {
        Set<String> out = new TreeSet<>();
        Matcher m = SELECTOR.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void check(String javaFile, int expectedAtLeast, String... uiFiles) throws IOException {
        String source = java(javaFile);
        Set<String> known = new TreeSet<>();
        for (String ui : uiFiles) {
            known.addAll(ids(ui));
            assertTrue(source.contains("\"Pages/AE2/" + ui + "\""), javaFile + " lädt " + ui + " nicht");
        }
        Matcher paths = PATH.matcher(source);
        while (paths.find()) {
            assertNotNull(ToolsUiTest.class.getResource("/Common/UI/Custom/" + paths.group(1)), paths.group(1));
        }
        Set<String> used = used(source);
        assertTrue(used.size() >= expectedAtLeast, "Verwendungen in " + javaFile + " nicht gefunden: " + used);
        for (String id : used) {
            assertTrue(known.contains(id), javaFile + " benutzt #" + id + ", das es in den Seiten nicht gibt");
        }
    }

    @Test
    void netzwerkstatus() throws IOException {
        check("NetworkStatusPage.java", 10, "NetworkStatus.ui", "NetworkStatusRow.ui");
    }

    @Test
    void werkzeugkiste() throws IOException {
        check("ToolboxPage.java", 7, "Toolbox.ui");
    }

    @Test
    void schneidmesser() throws IOException {
        check("KnifePage.java", 9, "QuartzKnife.ui");
    }

    /** Das Namensfeld lässt wie in AE2 höchstens 32 Zeichen zu – dieselbe Grenze, auf die der Server kürzt. */
    @Test
    void namensfeldHatDieGrenzeDesServers() throws IOException {
        String ui = resource("/Common/UI/Custom/Pages/AE2/QuartzKnife.ui");
        assertTrue(ui.contains("MaxLength: " + de.fellurion.ae2.core.tools.NamePressNames.MAX_LENGTH + ";"));
    }
}
