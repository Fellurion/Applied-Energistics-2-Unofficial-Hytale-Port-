/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.plane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sperrliste der Annihilation Plane: Muster mit {@code *}. */
class PlaneConfigTest {

    @Test
    void musterMitPlatzhalter() {
        assertTrue(PlaneConfig.matches("Rock_Bedrock", "Rock_Bedrock"));
        assertFalse(PlaneConfig.matches("Rock_Bedrock", "Rock_Bedrock_Slab"));
        assertTrue(PlaneConfig.matches("Portal_*", "Portal_Device"));
        assertFalse(PlaneConfig.matches("Portal_*", "Hub_Portal_Default"));
        assertTrue(PlaneConfig.matches("*_Portal", "VoidInvasion_Portal"));
        assertFalse(PlaneConfig.matches("*_Portal", "Portal_Return"));
        assertTrue(PlaneConfig.matches("*_Portal_*", "Hub_Portal_Zone3_Taiga1"));
        assertTrue(PlaneConfig.matches("*Spawner*", "Block_Spawner_Block_Large"));
        assertTrue(PlaneConfig.matches("*Spawner*", "Spawner_Rat"));
        assertTrue(PlaneConfig.matches("*", "anything"));
        assertTrue(PlaneConfig.matches("A*B*C", "AxxBxxC"));
        assertFalse(PlaneConfig.matches("A*B*C", "AxxCxxB"));
        assertFalse(PlaneConfig.matches("A*A", "A"), "Anfang und Ende dürfen sich nicht überlappen");
        assertTrue(PlaneConfig.matches("A*A", "AA"));
    }

    @Test
    void standardlisteSperrtPortaleAberKeineGewoehnlichenBloecke() {
        assertTrue(PlaneConfig.blockBlocked("Portal_Device", "Portal_Device"));
        assertTrue(PlaneConfig.blockBlocked("*Hub_Portal_Default_State_Definitions_Active", "Hub_Portal_Default"));
        assertTrue(PlaneConfig.blockBlocked("Leave_Instance", null));
        assertFalse(PlaneConfig.blockBlocked("Rock_Stone", "Rock_Stone"));
        assertFalse(PlaneConfig.blockBlocked("Furniture_Crude_Chest_Small", "Furniture_Crude_Chest_Small"));
        assertFalse(PlaneConfig.itemBlocked("Rock_Stone"));
    }
}
