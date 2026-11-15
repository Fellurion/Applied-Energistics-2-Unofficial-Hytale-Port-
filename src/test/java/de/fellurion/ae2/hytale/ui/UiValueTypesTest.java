/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Werte, die der Client beim Aufbau einer Seite nicht annimmt – er wirft den Spieler dann aus der Welt.
 *
 * <ul>
 *   <li>Eine {@code Message} gehört in {@code .TextSpans}, nicht in {@code .Text}: Der Client liest {@code .Text} als
 *       Zeichenkette und stürzt bei einer rohen Meldung ({@code Message.raw}, {@code Message.empty}) mit „No
 *       parameterless constructor defined for type 'System.String'“ ab. Geprüft werden Variablen und Felder vom Typ
 *       {@code Message}, die in {@code .Text} landen.</li>
 *   <li>Felder, die nur angeklickt werden, dürfen keine ziehbaren Items haben: Ein gezogenes Rezept-Item landet sonst
 *       als Ablage-Ereignis ohne Herkunft beim Server.</li>
 * </ul>
 */
class UiValueTypesTest {

    private static final Path JAVA = Path.of("src/main/java/de/fellurion/ae2/hytale");
    private static final Path UI = Path.of("src/main/resources/Common/UI/Custom/Pages/AE2");
    private static final Pattern SET_TEXT = Pattern.compile("\\.set\\(\\s*[^;]*?\\.Text\"\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

    @Test
    void messagesAreNeverSetAsPlainText() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = SET_TEXT.matcher(src);
                while (m.find()) {
                    String name = m.group(1);
                    if (Pattern.compile("\\bMessage\\s+" + name + "\\b").matcher(src).find()) {
                        problems.add(file.getFileName() + ": " + m.group().trim());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "Message in .Text (use .TextSpans): " + problems);
    }

    @Test
    void recipeGridsAreClickOnly() throws IOException {
        Set<String> clickOnly = Set.of("#AE2CraftGrid", "#AE2CraftOutput", "#AE2CraftInputs");
        Pattern grid = Pattern.compile("ItemGrid (#\\w+) \\{(.*?)\\n\\s*\\}", Pattern.DOTALL);
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(UI)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ui")).toList()) {
                Matcher m = grid.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (clickOnly.contains(m.group(1)) && !m.group(2).contains("AreItemsDraggable: false")) {
                        problems.add(file.getFileName() + " " + m.group(1));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "draggable recipe grid: " + problems);
    }
}
