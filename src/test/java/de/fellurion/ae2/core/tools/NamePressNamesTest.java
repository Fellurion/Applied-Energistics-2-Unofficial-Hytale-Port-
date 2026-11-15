/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NamePressNamesTest {

    @Test
    void blankNamesMakeNoPress() {
        assertNull(NamePressNames.clean(null));
        assertNull(NamePressNames.clean(""));
        assertNull(NamePressNames.clean("   \t\n "));
        assertNull(NamePressNames.clean("\u0000\u0007​"), "control and invisible format characters only");
    }

    @Test
    void whitespaceCollapsesAndEndsAreTrimmed() {
        assertEquals("Iron Depot", NamePressNames.clean("  Iron \t\n  Depot  "));
        assertEquals("a b", NamePressNames.clean("a  b"), "no-break and em spaces count as spaces");
        assertEquals("Ähre №5 – ✓", NamePressNames.clean("Ähre №5 – ✓"));
    }

    @Test
    void markupBracketsAreReplaced() {
        assertEquals("‹color is=\"#f00\"›red", NamePressNames.clean("<color is=\"#f00\">red"));
    }

    @Test
    void namesAreCutAtThirtyTwoCharacters() {
        String thirtyTwo = "abcdefghijklmnopqrstuvwxyz012345";
        assertEquals(32, thirtyTwo.length());
        assertEquals(thirtyTwo, NamePressNames.clean(thirtyTwo));
        assertEquals(thirtyTwo, NamePressNames.clean(thirtyTwo + "6789"));
        // counted in code points, never cutting a surrogate pair in half
        String emoji = "😀".repeat(40);
        String cut = NamePressNames.clean(emoji);
        assertEquals(32, cut.codePointCount(0, cut.length()));
        assertEquals(64, cut.length());
        // a cut never ends in a space
        assertEquals("abcdefghijklmnopqrstuvwxyz01234", NamePressNames.clean("abcdefghijklmnopqrstuvwxyz01234 6789"));
        assertEquals("abcdefghijklmnopqrstuvwxyz0123 5", NamePressNames.clean("abcdefghijklmnopqrstuvwxyz0123 56789"));
    }
}
