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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Seiten DeviceConfig.ui, InterfaceConfig.ui und Priority.ui passen zu ihrem Java-Code: Jedes Element, das der
 * Server setzt oder an das er ein Ereignis bindet, gibt es in der Datei – ein Befehl an ein fehlendes Element lässt
 * den Client die Seite verwerfen. Beide Layouts der Gerätekonfiguration haben dieselben Abschnitte; nur das
 * Vorrats-Layout hat zusätzlich Mengenknöpfe und echte Plätze.
 */
class ConfigUiTest {

    private static final Pattern ID = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)\\s*\\{");
    private static final Pattern SELECTOR = Pattern.compile("\"#([A-Za-z][A-Za-z0-9]*)");

    private static final List<String> SHARED = List.of("PageTitle", "Info", "FilterBox", "FilterHeader", "Filter",
            "ClearFilter", "Action", "UpgradeBox", "UpgradeHeader", "Upgrades", "UpgradeInfo", "SettingsHeader",
            "NoSettings", "Setting0", "Setting1", "Setting2", "Setting3", "Setting4", "NumberBox", "NumberLabel",
            "NumM2", "NumM1", "NumM0", "NumP0", "NumP1", "NumP2", "NumValue", "NumSet", "PriorityBox",
            "PriorityLabel", "PrioM100", "PrioM10", "PrioM1", "PrioP1", "PrioP10", "PrioP100", "PrioValue", "PrioSet",
            "InvHeader", "Inventory", "SelectedIcon", "SelectedText", "Status", "StatusError", "Hint");
    private static final List<String> BUS_ONLY = List.of();
    private static final List<String> INTERFACE_ONLY = List.of("AmountLabel", "AmtM64", "AmtM10", "AmtM1", "AmtP1",
            "AmtP10", "AmtP64", "StorageBox", "StorageHeader", "Storage");
    private static final List<String> PRIORITY = List.of("PageTitle", "Icon", "Name", "PriorityLabel", "PrioM100",
            "PrioM10", "PrioM1", "PrioP1", "PrioP10", "PrioP100", "PrioValue", "PrioSet", "TakeCell", "Hint");

    private static Set<String> ids(String file) throws IOException {
        String path = "/Common/UI/Custom/Pages/AE2/" + file;
        try (InputStream in = ConfigUiTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            Set<String> out = new TreeSet<>();
            Matcher m = ID.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (m.find()) {
                assertTrue(out.add(m.group(1)) || m.group(1).equals("Title") || m.group(1).equals("Content"),
                        "doppeltes Element #" + m.group(1) + " in " + file);
            }
            return out;
        }
    }

    /** Im Java-Code benutzte Elemente; "#Setting" (+ Nummer) steht für alle Elemente, die so beginnen. */
    private static Set<String> used(String javaFile) throws IOException {
        Path p = Path.of("src/main/java/de/fellurion/ae2/hytale/ui", javaFile);
        assertTrue(Files.isRegularFile(p), "Quelle nicht gefunden (Arbeitsordner?): " + p);
        Set<String> out = new TreeSet<>();
        Matcher m = SELECTOR.matcher(Files.readString(p, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static boolean known(Set<String> ids, String usedId) {
        return ids.contains(usedId) || ids.stream().anyMatch(id -> id.startsWith(usedId) && id.substring(usedId.length()).matches("\\d+"));
    }

    @Test
    void busUndInterfaceSeiteHabenAlleElemente() throws IOException {
        Set<String> bus = ids("DeviceConfig.ui");
        Set<String> iface = ids("InterfaceConfig.ui");
        for (String id : SHARED) {
            assertTrue(bus.contains(id), "DeviceConfig.ui: #" + id);
            assertTrue(iface.contains(id), "InterfaceConfig.ui: #" + id);
        }
        for (String id : BUS_ONLY) {
            assertTrue(bus.contains(id), "DeviceConfig.ui: #" + id);
            assertTrue(!iface.contains(id), "InterfaceConfig.ui hat #" + id + " nicht");
        }
        for (String id : INTERFACE_ONLY) {
            assertTrue(iface.contains(id), "InterfaceConfig.ui: #" + id);
            assertTrue(!bus.contains(id), "DeviceConfig.ui hat #" + id + " nicht");
        }
        Set<String> expected = new TreeSet<>(SHARED);
        expected.addAll(BUS_ONLY);
        expected.addAll(INTERFACE_ONLY);
        Set<String> used = used("DeviceConfigPage.java");
        assertTrue(used.size() > 15, "Verwendungen nicht gefunden");
        for (String id : used) {
            assertTrue(known(expected, id), "DeviceConfigPage benutzt #" + id + ", das die Seiten nicht (beide) haben");
        }
    }

    /** Die Knopfnamen, die die Seite aus Feldern zusammensetzt ("#" + NAME), gibt es in beiden Dateien. */
    @Test
    void zusammengesetzteKnopfnamenGibtEs() throws IOException {
        String java = Files.readString(Path.of("src/main/java/de/fellurion/ae2/hytale/ui/DeviceConfigPage.java"),
                StandardCharsets.UTF_8);
        Set<String> bus = ids("DeviceConfig.ui");
        Set<String> iface = ids("InterfaceConfig.ui");
        Matcher arrays = Pattern.compile("String\\[] (\\w+_BUTTONS) = \\{([^}]*)}").matcher(java);
        int found = 0;
        while (arrays.find()) {
            Matcher names = Pattern.compile("\"(\\w+)\"").matcher(arrays.group(2));
            while (names.find()) {
                found++;
                assertTrue(iface.contains(names.group(1)), "InterfaceConfig.ui: #" + names.group(1));
                if (!arrays.group(1).startsWith("AMOUNT")) {
                    assertTrue(bus.contains(names.group(1)), "DeviceConfig.ui: #" + names.group(1));
                }
            }
        }
        assertTrue(found >= 18, "Knopflisten nicht gefunden");
    }

    /**
     * Die Kästen, die der Server ausblendet, sind so hoch wie ihre Zeilen zusammen – sonst verschöbe das Einrahmen
     * die Seite der Busse und des Interface. Geprüft an den festen Höhen der direkten Kinder.
     */
    @Test
    void kastenhoehenSindDieSummeIhrerZeilen() throws IOException {
        for (String file : List.of("DeviceConfig.ui", "InterfaceConfig.ui")) {
            String path = "/Common/UI/Custom/Pages/AE2/" + file;
            String text;
            try (InputStream in = ConfigUiTest.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String box : List.of("FilterBox", "StorageBox", "UpgradeBox", "NumberBox")) {
                int at = text.indexOf("Group #" + box + " {");
                if (at < 0) {
                    assertTrue(box.equals("StorageBox") && file.equals("DeviceConfig.ui"), file + ": #" + box);
                    continue;
                }
                int open = text.indexOf('{', at);
                int depth = 0;
                int declared = -1;
                int sum = 0;
                Matcher height = Pattern.compile("(?:Top: (\\d+), )?Height: (\\d+)").matcher(text);
                for (int i = open; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '{') {
                        depth++;
                    } else if (c == '}' && --depth == 0) {
                        break;
                    } else if (c == 'A' && text.startsWith("Anchor: (", i) && height.find(i)
                            && height.start() < text.indexOf(')', i)) {
                        int h = Integer.parseInt(height.group(2)) + (height.group(1) == null ? 0 : Integer.parseInt(height.group(1)));
                        if (depth == 1) {
                            declared = h;
                        } else if (depth == 2) {
                            sum += h;
                        }
                    }
                }
                assertTrue(declared > 0, file + ": Höhe von #" + box);
                assertTrue(sum == declared, file + ": #" + box + " ist " + declared + " hoch, seine Zeilen " + sum);
            }
        }
    }

    @Test
    void prioritaetsSeiteHatAlleElemente() throws IOException {
        Set<String> ids = ids("Priority.ui");
        for (String id : PRIORITY) {
            assertTrue(ids.contains(id), "Priority.ui: #" + id);
        }
        Set<String> used = used("PriorityPage.java");
        assertTrue(used.size() > 5, "Verwendungen nicht gefunden");
        for (String id : used) {
            assertTrue(known(new TreeSet<>(PRIORITY), id), "PriorityPage benutzt #" + id + ", das Priority.ui nicht hat");
        }
    }
}
