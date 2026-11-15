/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.guide;

import de.fellurion.ae2.core.machines.InscriberRecipe;
import de.fellurion.ae2.core.machines.InscriberRecipes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inhalt und Seitenaufteilung des Buchs „AE2 Basics“ – mit den echten Rezepten und Texten. */
class GuideBookTest {

    private static final Path RES = Path.of("src/main/resources");

    private static InscriberRecipes recipes() throws IOException {
        return InscriberRecipes.fromJson(Files.readString(RES.resolve("ae2data/inscriber.json"), StandardCharsets.UTF_8));
    }

    private static Map<String, String> lang(String locale) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(RES.resolve("Server/Languages/" + locale + "/ae2guide.lang"))) {
            int eq = line.indexOf('=');
            if (line.isBlank() || line.startsWith("#") || eq < 0) {
                continue;
            }
            out.put("ae2guide." + line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    private static Function<String, String> tr(Map<String, String> texts) {
        return key -> {
            String t = texts.get(key);
            assertNotNull(t, "missing text " + key);
            return t;
        };
    }

    @Test
    void everyInscriberRecipeIsInTheBook() throws IOException {
        InscriberRecipes recipes = recipes();
        List<GuideBook.Chapter> chapters = GuideBook.chapters(recipes);
        long shown = chapters.stream().flatMap(c -> c.blocks().stream())
                .filter(b -> b instanceof GuideBook.Recipe).count();
        long real = recipes.getRecipes().stream().filter(r -> !InscriberRecipes.isNamePressRecipe(r)).count();
        assertEquals(real, shown);
        assertTrue(shown >= 15, "the data has 15 inscriber recipes");
    }

    @Test
    void recipeGroups() throws IOException {
        for (InscriberRecipe r : recipes().getRecipes()) {
            GuideBook.Group g = GuideBook.group(r);
            String out = r.output().itemId();
            if (out.endsWith("_Press")) {
                assertEquals(GuideBook.Group.PRESSES, g, out);
            } else if (out.contains("Printed")) {
                assertEquals(GuideBook.Group.CIRCUITS, g, out);
            } else if (out.endsWith("_Processor")) {
                assertEquals(GuideBook.Group.PROCESSORS, g, out);
            } else if (out.endsWith("_Dust")) {
                assertEquals(GuideBook.Group.DUSTS, g, out);
            }
        }
    }

    @Test
    void pagesFitAndEveryTextExistsInBothLanguages() throws IOException {
        for (String locale : List.of("en-US", "de-DE")) {
            Function<String, String> tr = tr(lang(locale));
            List<GuideBook.Chapter> chapters = GuideBook.chapters(recipes());
            GuideBook.Layout book = GuideBook.layout(chapters, tr);
            assertEquals(chapters.size(), book.chapterPages().size());
            for (int i = 0; i < book.pages().size(); i++) {
                List<GuideBook.Block> page = book.page(i);
                assertFalse(page.isEmpty(), locale + ": empty page " + i);
                int height = 0;
                for (GuideBook.Block b : page) {
                    height += b.height(tr);
                    switch (b) {
                        case GuideBook.Title t -> {
                            tr.apply(t.titleKey());
                            tr.apply(t.subtitleKey());
                        }
                        case GuideBook.Heading h -> tr.apply(h.key());
                        case GuideBook.Subheading h -> tr.apply(h.key());
                        case GuideBook.Text t -> tr.apply(t.key());
                        case GuideBook.Link l -> tr.apply(chapters.get(l.chapter()).titleKey());
                        default -> {
                        }
                    }
                }
                assertTrue(height <= GuideBook.PAGE_HEIGHT, locale + ": page " + i + " is " + height + " high");
            }
            // jedes Kapitel beginnt mit seiner Überschrift
            for (int c = 0; c < chapters.size(); c++) {
                assertTrue(book.page(book.chapterPages().get(c)).get(0) instanceof GuideBook.Heading);
            }
            for (String key : List.of("ae2guide.inscriber.top", "ae2guide.inscriber.middle", "ae2guide.inscriber.bottom",
                    "ae2guide.inscriber.kept", "ae2guide.inscriber.consumed", "ae2guide.inscriber.noPress",
                    "ae2guide.book.contentsButton", "ae2guide.item.name", "ae2guide.item.description")) {
                tr.apply(key);
            }
            for (GuideBook.Group g : GuideBook.Group.values()) {
                tr.apply(g.key());
            }
        }
    }

    @Test
    void lineCount() {
        assertEquals(1, GuideBook.lines("short"));
        assertEquals(2, GuideBook.lines("a\nb"));
        String words = "word ".repeat(30).trim(); // 149 Zeichen
        assertTrue(GuideBook.lines(words) >= 3);
    }
}
