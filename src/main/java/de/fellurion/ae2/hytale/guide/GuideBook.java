/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.guide;

import de.fellurion.ae2.core.machines.InscriberRecipe;
import de.fellurion.ae2.core.machines.InscriberRecipes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Inhalt des Buchs „AE2 Basics“: Kapitel aus Bausteinen (Überschrift, Absatz, Bilder, Inscriber-Rezept) und deren
 * Aufteilung auf Seiten.
 *
 * <p>Die Texte stehen in {@code ae2guide.lang}; hier stehen nur Schlüssel und Item-Ids. Die Inscriber-Rezepte kommen
 * aus denselben Daten, mit denen der Inscriber arbeitet – das Buch zeigt also immer genau, was er kann.
 * Seitenumbrüche rechnet {@link #layout} aus geschätzten Höhen: Jede Seite ist {@value #PAGE_HEIGHT} Punkte hoch,
 * jedes Kapitel beginnt auf einer neuen Seite, und ein Baustein wird nie geteilt.
 */
final class GuideBook {

    static final int PAGE_HEIGHT = 540;
    /** Zeichen je Zeile eines Absatzes (Seite 400 Punkte, Schrift 14) – lieber etwas zu wenig, dann bleibt Luft. */
    static final int CHARS_PER_LINE = 50;
    static final int LINE_HEIGHT = 18;

    /** Ein Baustein einer Seite. */
    sealed interface Block permits Title, Heading, Subheading, Text, Picture, Recipe, Link {
        /** Höhe in Punkten; {@code tr} übersetzt einen Schlüssel (für die Länge von Absätzen). */
        int height(Function<String, String> tr);
    }

    record Title(String titleKey, String subtitleKey) implements Block {
        public int height(Function<String, String> tr) {
            return 110;
        }
    }

    record Heading(String key) implements Block {
        public int height(Function<String, String> tr) {
            return 44;
        }
    }

    record Subheading(String key) implements Block {
        public int height(Function<String, String> tr) {
            return 28;
        }
    }

    record Text(String key) implements Block {
        public int height(Function<String, String> tr) {
            return 8 + lines(tr.apply(key)) * LINE_HEIGHT;
        }
    }

    /** Bis zu fünf Items als Bilder mit Namen. */
    record Picture(List<String> itemIds) implements Block {
        Picture {
            if (itemIds.isEmpty() || itemIds.size() > 5) {
                throw new IllegalArgumentException("1 to 5 pictures");
            }
            itemIds = List.copyOf(itemIds);
        }

        public int height(Function<String, String> tr) {
            return 112;
        }
    }

    record Recipe(InscriberRecipe recipe) implements Block {
        public int height(Function<String, String> tr) {
            return 108;
        }
    }

    /** Eintrag im Inhaltsverzeichnis. */
    record Link(int chapter) implements Block {
        public int height(Function<String, String> tr) {
            return 30;
        }
    }

    record Chapter(String titleKey, List<Block> blocks) {
    }

    /** Fertig aufgeteiltes Buch. */
    record Layout(List<List<Block>> pages, List<Chapter> chapters, List<Integer> chapterPages) {
        int spreads() {
            return (pages.size() + 1) / 2;
        }

        List<Block> page(int index) {
            return index >= 0 && index < pages.size() ? pages.get(index) : List.of();
        }
    }

    private GuideBook() {
    }

    /** Zeilen eines Textes bei {@value #CHARS_PER_LINE} Zeichen je Zeile (Wortumbruch, Absätze mit \n). */
    static int lines(String text) {
        int lines = 0;
        for (String paragraph : text.split("\n", -1)) {
            int line = 0;
            int count = 1;
            for (String word : paragraph.split(" ")) {
                int len = word.length();
                if (line == 0) {
                    line = len;
                } else if (line + 1 + len <= CHARS_PER_LINE) {
                    line += 1 + len;
                } else {
                    count++;
                    line = len;
                }
                while (line > CHARS_PER_LINE) { // überlanges Wort
                    count++;
                    line -= CHARS_PER_LINE;
                }
            }
            lines += count;
        }
        return Math.max(1, lines);
    }

    // ------------------------------------------------------------------ Inhalt

    private static Text text(String chapter, int n) {
        return new Text("ae2guide." + chapter + ".text" + n);
    }

    private static Picture pic(String... ids) {
        return new Picture(List.of(ids));
    }

    /** Die Kapitel in Lesereihenfolge. */
    static List<Chapter> chapters(InscriberRecipes recipes) {
        List<Chapter> out = new ArrayList<>();
        out.add(chapter("workbench",
                pic("AE2_Energistics_Workbench"),
                text("workbench", 1),
                pic("Ingredient_Bar_Iron", "Ingredient_Bar_Copper", "Ingredient_Crystal_White", "Wood_Oak_Trunk"),
                text("workbench", 2)));
        out.add(chapter("meteorite",
                pic("AE2_Meteorite_Compass", "AE2_Sky_Stone_Block", "AE2_Mysterious_Cube"),
                text("meteorite", 1),
                pic("AE2_Calculation_Processor_Press", "AE2_Engineering_Processor_Press", "AE2_Logic_Processor_Press",
                        "AE2_Silicon_Press"),
                text("meteorite", 2),
                text("meteorite", 3)));
        out.add(chapter("quartz",
                pic("AE2_Flawless_Budding_Quartz", "AE2_Small_Quartz_Bud", "AE2_Large_Quartz_Bud", "AE2_Quartz_Cluster"),
                text("quartz", 1),
                pic("AE2_Growth_Accelerator", "AE2_Certus_Quartz_Crystal", "AE2_Certus_Quartz_Dust"),
                text("quartz", 2)));
        out.add(chapter("charger",
                pic("AE2_Charger", "AE2_Crank", "AE2_Certus_Quartz_Crystal", "AE2_Charged_Certus_Quartz_Crystal"),
                text("charger", 1),
                text("charger", 2)));
        out.add(chapter("fluix",
                pic("AE2_Charged_Certus_Quartz_Crystal", "Ingredient_Crystal_Red", "Ingredient_Crystal_White",
                        "AE2_Fluix_Crystal"),
                text("fluix", 1),
                pic("AE2_Charged_Certus_Quartz_Crystal", "AE2_Certus_Quartz_Dust", "AE2_Certus_Quartz_Crystal"),
                text("fluix", 2)));
        out.add(inscriber(recipes));
        out.add(chapter("power",
                pic("AE2_Vibration_Chamber", "AE2_Crystal_Resonance_Generator", "AE2_Energy_Cell"),
                text("power", 1),
                text("power", 2)));
        out.add(chapter("network",
                pic("AE2_Drive", "AE2_Item_Storage_Cell_1k", "AE2_Terminal", "AE2_Fluix_Glass_Cable"),
                text("network", 1),
                text("network", 2),
                pic("AE2_Controller", "AE2_Fluix_Smart_Cable", "AE2_Fluix_Covered_Dense_Cable"),
                text("network", 3)));
        out.add(chapter("autocrafting",
                pic("AE2_Pattern_Encoding_Terminal", "AE2_Blank_Pattern", "AE2_Pattern_Provider",
                        "AE2_Molecular_Assembler", "AE2_Crafting_Unit"),
                text("autocrafting", 1),
                text("autocrafting", 2)));
        out.add(chapter("tips",
                pic("AE2_Certus_Quartz_Wrench", "AE2_Network_Tool", "AE2_Memory_Card"),
                text("tips", 1),
                text("tips", 2),
                text("tips", 3)));
        return out;
    }

    private static Chapter chapter(String id, Block... blocks) {
        List<Block> list = new ArrayList<>();
        list.add(new Heading("ae2guide." + id + ".title"));
        Collections.addAll(list, blocks);
        return new Chapter("ae2guide." + id + ".title", list);
    }

    /** Gruppe eines Inscriber-Rezepts im Buch (in dieser Reihenfolge). */
    enum Group {
        CIRCUITS, PROCESSORS, DUSTS, PRESSES, OTHER;

        String key() {
            return "ae2guide.inscriber.group." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static Group group(InscriberRecipe r) {
        String out = r.output().itemId();
        String top = r.top() == null ? null : r.top().itemId();
        if (r.mode() == InscriberRecipe.ProcessType.PRESS) {
            return Group.PROCESSORS;
        }
        if (top != null && top.equals(out)) {
            return Group.PRESSES;
        }
        if (top != null && out.contains("Printed")) {
            return Group.CIRCUITS;
        }
        if (top == null && r.bottom() == null) {
            return Group.DUSTS;
        }
        return Group.OTHER;
    }

    private static Chapter inscriber(InscriberRecipes recipes) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading("ae2guide.inscriber.title"));
        blocks.add(pic("AE2_Inscriber", "AE2_Silicon", "AE2_Printed_Silicon", "AE2_Logic_Processor"));
        blocks.add(text("inscriber", 1));
        blocks.add(text("inscriber", 2));
        blocks.add(text("inscriber", 3));
        for (Group g : Group.values()) {
            List<InscriberRecipe> list = new ArrayList<>();
            for (InscriberRecipe r : recipes.getRecipes()) {
                if (!InscriberRecipes.isNamePressRecipe(r) && group(r) == g) {
                    list.add(r);
                }
            }
            if (list.isEmpty()) {
                continue;
            }
            list.sort((a, b) -> a.output().itemId().compareTo(b.output().itemId()));
            blocks.add(new Subheading(g.key()));
            for (InscriberRecipe r : list) {
                blocks.add(new Recipe(r));
            }
        }
        blocks.add(new Subheading("ae2guide.inscriber.group.name"));
        blocks.add(pic("AE2_Certus_Quartz_Cutting_Knife", "AE2_Name_Press"));
        blocks.add(text("inscriber", 4));
        return new Chapter("ae2guide.inscriber.title", blocks);
    }

    // ------------------------------------------------------------------ Seiten

    /**
     * Teilt das Buch auf Seiten auf: Seite 0 ist die Titelseite, Seite 1 das Inhaltsverzeichnis, danach die Kapitel,
     * jedes auf einer neuen Seite.
     */
    static Layout layout(List<Chapter> chapters, Function<String, String> tr) {
        List<List<Block>> pages = new ArrayList<>();
        pages.add(List.of(new Title("ae2guide.book.title", "ae2guide.book.subtitle"),
                pic("AE2_Controller", "AE2_Drive", "AE2_Terminal", "AE2_Fluix_Crystal"),
                new Text("ae2guide.book.intro")));
        List<Block> contents = new ArrayList<>();
        contents.add(new Heading("ae2guide.book.contents"));
        for (int i = 0; i < chapters.size(); i++) {
            contents.add(new Link(i));
        }
        pages.add(contents);
        List<Integer> chapterPages = new ArrayList<>();
        for (Chapter c : chapters) {
            List<Block> page = new ArrayList<>();
            int used = 0;
            chapterPages.add(pages.size());
            for (Block b : c.blocks()) {
                int h = b.height(tr);
                if (!page.isEmpty() && used + h > PAGE_HEIGHT) {
                    pages.add(page);
                    page = new ArrayList<>();
                    used = 0;
                }
                page.add(b);
                used += h;
            }
            pages.add(page);
        }
        return new Layout(List.copyOf(pages), List.copyOf(chapters), List.copyOf(chapterPages));
    }
}
