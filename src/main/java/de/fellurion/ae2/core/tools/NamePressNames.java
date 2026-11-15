/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

/**
 * The name a player types for a name press. Port of the name handling of
 * {@code appeng.menu.implementations.QuartzKnifeMenu} (a blank name makes no press) and of the 32 character limit of
 * {@code appeng.client.gui.implementations.QuartzKnifeScreen}.
 * <p>
 * Deviation: AE2 relies on the client's text field for the limit and stores the text as it is; here the server
 * cleans it, because the text arrives from the client and ends up as an item name in other players' tooltips.
 */
public final class NamePressNames {
    /** AE2: {@code name.setMaxLength(32)}. */
    public static final int MAX_LENGTH = 32;

    private NamePressNames() {
    }

    /**
     * Cleans a typed name: control characters and line breaks count as spaces, runs of whitespace collapse to one
     * space, the angle brackets of the client's text markup are replaced by look-alikes, and the result is trimmed
     * and cut to {@link #MAX_LENGTH} characters (code points).
     *
     * @return the name to inscribe, or null if nothing printable is left (AE2: blank name, no press)
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        var sb = new StringBuilder();
        int length = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length() && length < MAX_LENGTH;) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) {
                pendingSpace = length > 0;
                continue;
            }
            if (!Character.isDefined(cp) || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.SURROGATE
                    || Character.getType(cp) == Character.PRIVATE_USE) {
                continue;
            }
            if (pendingSpace) {
                if (length + 1 >= MAX_LENGTH) {
                    break; // no room for the space plus another character
                }
                sb.append(' ');
                length++;
                pendingSpace = false;
            }
            sb.appendCodePoint(cp == '<' ? '‹' : cp == '>' ? '›' : cp);
            length++;
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
