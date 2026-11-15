/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Welche Werkbank-Rezepte das Crafting-Terminal anbietet (ohne Server: nur die Werkbank-Regel). */
class TerminalRecipesTest {

    private static BenchRequirement bench(BenchType type, String id, int tier) {
        return new BenchRequirement(type, id, null, tier, null);
    }

    @Test
    void einfacheWerkbaenkeUndEnergisticsWerkbank() {
        assertEquals("Workbench", TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Crafting, "Workbench", 0)}));
        assertEquals("Fieldcraft", TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Crafting, "Fieldcraft", 0)}));
        assertEquals("AE2_Workbench", TerminalRecipes.benchOf(
                new BenchRequirement[]{bench(BenchType.Crafting, "AE2_Workbench", 0)}));
    }

    @Test
    void spezialwerkbaenkeUndVerarbeitungNicht() {
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Crafting, "Weapon_Bench", 0)}));
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Crafting, "Alchemybench", 0)}));
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Processing, "Furnace", 0)}));
        // gleicher Name, aber kein Handwerk an der Werkbank
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.StructuralCrafting, "Workbench", 0)}));
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[0]));
        assertNull(TerminalRecipes.benchOf(null));
    }

    @Test
    void terminalZaehltAlsFrischGebauteWerkbank() {
        assertEquals("Workbench", TerminalRecipes.benchOf(
                new BenchRequirement[]{bench(BenchType.Crafting, "Workbench", TerminalRecipes.BENCH_TIER)}));
        // Rezepte für ausgebaute Werkbänke (Stufe 2+) bleiben der Werkbank vorbehalten
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Crafting, "Workbench", 2)}));
        assertNull(TerminalRecipes.benchOf(new BenchRequirement[]{bench(BenchType.Crafting, "Workbench", 3)}));
    }

    @Test
    void mehrereWerkbaenkeErsteAngeboteneZaehlt() {
        BenchRequirement[] both = {bench(BenchType.Crafting, "Weapon_Bench", 0), bench(BenchType.Crafting, "Workbench", 0),
                bench(BenchType.Crafting, "Fieldcraft", 0)};
        assertEquals("Fieldcraft", TerminalRecipes.benchOf(both));
        // die Werkbank verlangt Stufe 3, die Energistics-Werkbank nichts: dort geht es
        BenchRequirement[] tiered = {bench(BenchType.Crafting, "Workbench", 3), bench(BenchType.Crafting, "AE2_Workbench", 0)};
        assertEquals("AE2_Workbench", TerminalRecipes.benchOf(tiered));
    }
}
