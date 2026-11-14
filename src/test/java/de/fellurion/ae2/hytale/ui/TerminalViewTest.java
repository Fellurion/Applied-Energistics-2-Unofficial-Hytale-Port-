/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reine Logik der Terminal-Ansicht (ohne Server): Mengenformat, Suche, Seitenaufteilung. */
class TerminalViewTest {

    @Test
    void mengenImAe2Stil() {
        assertEquals("9999", TerminalTexts.formatAmount(9_999));
        assertEquals("10k", TerminalTexts.formatAmount(10_000));
        assertEquals("12.5k", TerminalTexts.formatAmount(12_550));
        assertEquals("999k", TerminalTexts.formatAmount(999_999));
        assertEquals("1M", TerminalTexts.formatAmount(1_000_000));
        assertEquals("2.1G", TerminalTexts.formatAmount(2_150_000_000L));
    }

    @Test
    void tausendertrennungNachSprache() {
        assertEquals("1.234.567", TerminalTexts.grouped(1_234_567, "de-DE"));
        assertEquals("1,234,567", TerminalTexts.grouped(1_234_567, "en-US"));
    }

    @Test
    void sucheTrifftNameIdUndIdMitLeerzeichen() {
        assertTrue(TerminalView.matches("eisen", "Ingredient_Bar_Iron", "Eisenbarren"));
        assertTrue(TerminalView.matches("bar iron", "Ingredient_Bar_Iron", "Eisenbarren"));
        assertTrue(TerminalView.matches("bar_iron", "Ingredient_Bar_Iron", "Eisenbarren"));
        assertFalse(TerminalView.matches("gold", "Ingredient_Bar_Iron", "Eisenbarren"));
    }

    @Test
    void seitenGroesse() {
        // TerminalPage selbst lässt sich ohne laufenden Server nicht laden (Hytale-Logger)
        assertEquals(63, new TerminalView.Layout(7, "Pages/AE2/TerminalAmountRow.ui").pageSize());
    }
}
