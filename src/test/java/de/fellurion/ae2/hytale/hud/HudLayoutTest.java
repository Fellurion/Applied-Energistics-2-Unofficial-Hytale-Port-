/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Maße der Blockinfo: Höhe nach Zeilen, Breite nach Text, Zeilen rücken ohne Zustandszeile hoch. */
class HudLayoutTest {

    @Test
    void nameOnlyHasMinimumSize() {
        LookInfo info = LookInfo.builder("AE2_Quartz_Glass", "Quarzglas").build();
        HudLayout l = HudLayout.of(info);
        assertEquals(HudLayout.MIN_HEIGHT, l.height());
        assertEquals(HudLayout.MIN_WIDTH, l.width());
        assertFalse(l.status());
        assertEquals(0, l.lines());
    }

    @Test
    void rowsStackBelowStatus() {
        LookInfo info = LookInfo.builder("AE2_Fluix_Smart_Cable", "Fluix Schlaues ME-Kabel")
                .status("Online", HudColors.GOOD)
                .line("Kanäle", "5 / 8")
                .line("Seiten", "Ost 3 · West 5")
                .bar(5 / 8.0, HudColors.ACCENT)
                .build();
        HudLayout l = HudLayout.of(info);
        assertEquals(HudLayout.FIRST_ROW, l.statusTop());
        assertEquals(HudLayout.FIRST_ROW + HudLayout.ROW_HEIGHT, l.lineTop(0));
        assertEquals(HudLayout.FIRST_ROW + 2 * HudLayout.ROW_HEIGHT, l.lineTop(1));
        assertEquals(l.lineTop(2) + HudLayout.BAR_GAP, l.barTop());
        assertEquals(l.barTop() + HudLayout.BAR_HEIGHT + HudLayout.BOTTOM_PAD, l.height());
        assertEquals(0, l.width() % HudLayout.WIDTH_STEP);
        assertEquals(0.625, info.bar(), 1e-9);
    }

    @Test
    void linesMoveUpWithoutStatus() {
        LookInfo info = LookInfo.builder(null, "Condenser").line("A", "B").build();
        HudLayout l = HudLayout.of(info);
        assertEquals(HudLayout.FIRST_ROW, l.lineTop(0));
    }

    @Test
    void widthFollowsLongestLineAndIsCapped() {
        LookInfo shortInfo = LookInfo.builder(null, "X").line("Kanäle", "1 / 8").build();
        LookInfo longInfo = LookInfo.builder(null, "X").line("Seiten", "Ost 8 · West 8 · Oben 8 · Unten 8").build();
        LookInfo huge = LookInfo.builder(null, "X").line("L", "x".repeat(400)).build();
        assertTrue(HudLayout.of(longInfo).width() > HudLayout.of(shortInfo).width());
        assertEquals(HudLayout.MAX_WIDTH, HudLayout.of(huge).width());
    }

    @Test
    void builderKeepsAtMostMaxLines() {
        LookInfo.Builder b = LookInfo.builder(null, "Drive");
        for (int i = 0; i < 10; i++) {
            b.line("Zelle", String.valueOf(i));
        }
        assertEquals(0, b.freeLines());
        assertEquals(LookInfo.MAX_LINES, b.build().lines().size());
    }

    @Test
    void equalContentIsEqual() {
        LookInfo a = LookInfo.builder("AE2_Controller", "ME-Controller").status("Online", HudColors.GOOD)
                .line("Netz", "12 Blöcke").bar(0.5, HudColors.ACCENT).build();
        LookInfo b = LookInfo.builder("AE2_Controller", "ME-Controller").status("Online", HudColors.GOOD)
                .line("Netz", "12 Blöcke").bar(0.5004, HudColors.ACCENT).build();
        assertEquals(a, b); // Balken auf 0,5 % gerundet: kein neues Paket für winzige Schwankungen
    }
}
