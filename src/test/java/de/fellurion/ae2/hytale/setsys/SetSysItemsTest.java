/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.setsys;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Liest das Seelenbund-Format von SetSys (de.fellurion.setsys.service.ItemRolls) ohne SetSys-Klassen. */
class SetSysItemsTest {

    @Test
    void aktuelleFassungMitBesitzer() {
        UUID id = UUID.randomUUID();
        SetSysItems.Bond b = SetSysItems.parse("3|" + id + "|Fellurion|S,damage,M,6.1000,0;S,health,A,12.0000,1");
        assertEquals(id, b.owner());
        assertEquals("Fellurion", b.ownerName());
    }

    @Test
    void alteFassungOhneBesitzer() {
        SetSysItems.Bond b = SetSysItems.parse("1|S,damage,M,6.1000");
        assertNull(b.owner());
        assertEquals("", b.ownerName());
    }

    @Test
    void kaputteSpielernummer() {
        SetSysItems.Bond b = SetSysItems.parse("2|keine-uuid|Name|");
        assertNull(b.owner());
        assertEquals("Name", b.ownerName());
    }
}
