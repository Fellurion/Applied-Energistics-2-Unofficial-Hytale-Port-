/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.wireless;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.cells.Upgrades;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reine Logik ohne Server: Verknüpfung eines Terminals lesen/schreiben, Kartenplätze der Items wie in AE2. */
class WirelessItemsTest {

    @Test
    void verknuepfungUeberlebtSchreibenUndLesen() {
        WirelessLink link = new WirelessLink("default", -12, 200, 34, "0123456789abcdef");
        WirelessLink read = WirelessLink.parse(link.encode());
        assertEquals(link, read);
        // Weltnamen dürfen Kommas enthalten
        WirelessLink odd = new WirelessLink("my, world", 1, 2, 3, "ab");
        assertEquals(odd, WirelessLink.parse(odd.encode()));
    }

    @Test
    void kaputteVerknuepfungGiltAlsNichtVerknuepft() {
        assertNull(WirelessLink.parse(null));
        assertNull(WirelessLink.parse(""));
        assertNull(WirelessLink.parse("1,2,3"));
        assertNull(WirelessLink.parse("a,2,3,id,world"));
        assertNull(WirelessLink.parse("1,2,3,,world"));
        assertNotNull(WirelessLink.parse("1,2,3,id,world"));
    }

    @Test
    void nurWirelessTerminalsLassenSichVerknuepfen() {
        assertTrue(WirelessLink.linkable("AE2_Wireless_Terminal"));
        assertTrue(WirelessLink.linkable("AE2_Wireless_Crafting_Terminal"));
        assertFalse(WirelessLink.linkable("AE2_Portable_Item_Cell_1k"));
        assertFalse(WirelessLink.linkable("AE2_Terminal"));
    }

    @Test
    void kartenplaetzeWieInAE2() {
        // UpgradeInventories.forItem(stack, n, ...) der jeweiligen AE2-Items
        assertEquals(2, HandheldUpgrades.slots("AE2_Wireless_Terminal"));
        assertEquals(2, HandheldUpgrades.slots("AE2_Wireless_Crafting_Terminal"));
        assertEquals(4, HandheldUpgrades.slots("AE2_Matter_Cannon"));
        assertEquals(2, HandheldUpgrades.slots("AE2_Color_Applicator"));
        assertEquals(0, HandheldUpgrades.slots("AE2_Entropy_Manipulator"));
        assertFalse(HandheldUpgrades.accepts("AE2_Portable_Item_Cell_1k")); // Zelle: Karten im Zelleninhalt
        assertFalse(HandheldUpgrades.accepts(null));
    }

    @Test
    void erlaubteKartenWieInInitUpgrades() {
        // Wireless Terminal: nur Energiekarten, zwei Stück
        UpgradeInventory terminal = new UpgradeInventory("AE2_Wireless_Terminal", 2);
        assertFalse(terminal.insert(UpgradeCard.FUZZY));
        assertTrue(terminal.insert(UpgradeCard.ENERGY));
        assertTrue(terminal.insert(UpgradeCard.ENERGY));
        assertFalse(terminal.insert(UpgradeCard.ENERGY));
        // Color Applicator: 2 Plätze für Energie (2), Gleichverteilung (1), Überlauf (1)
        UpgradeInventory applicator = new UpgradeInventory("AE2_Color_Applicator", 2);
        assertTrue(applicator.insert(UpgradeCard.EQUAL_DISTRIBUTION));
        assertFalse(applicator.insert(UpgradeCard.EQUAL_DISTRIBUTION));
        assertTrue(applicator.insert(UpgradeCard.VOID));
        assertFalse(applicator.insert(UpgradeCard.ENERGY)); // voll
        // Matter Cannon: 4 Plätze, bis zu 4 Beschleunigungskarten
        assertEquals(4, Upgrades.getMaxInstallable(UpgradeCard.SPEED, "AE2_Matter_Cannon"));
        assertEquals(2, Upgrades.getMaxInstallable(UpgradeCard.ENERGY, "AE2_Matter_Cannon"));
        // Tragbare Zellen: zwei Energiekarten
        assertEquals(2, Upgrades.getMaxInstallable(UpgradeCard.ENERGY, "AE2_Portable_Item_Cell_1k"));
        assertEquals(2, Upgrades.getMaxInstallable(UpgradeCard.ENERGY, "AE2_Portable_Fluid_Cell_64k"));
    }
}
