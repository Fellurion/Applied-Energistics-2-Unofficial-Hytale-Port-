/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.fluid;

import com.google.gson.JsonParser;
import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.fluid.FluidRules.Candidate;
import de.fellurion.ae2.hytale.ui.ConfigEdits;
import de.fellurion.ae2.hytale.ui.TerminalBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Welche Flüssigkeiten lagerbar sind, ihre Namen und Mengen, und das Durchschalten eines Filterplatzes. */
class FluidRulesTest {

    /** Die Flüssigkeiten des Spiels, wie sie in seinen Assets stehen (Quelle = {@code CanDemote: false}). */
    private static final List<Candidate> GAME = List.of(
            new Candidate("Empty", false, false),
            new Candidate("Fire", false, false),
            new Candidate("Lava", false, false),
            new Candidate("Lava_Source", true, false),
            new Candidate("Poison_Source", true, false),
            new Candidate("Slime_Red_Source", true, false),
            new Candidate("Tar_Source", true, false),
            new Candidate("Water", false, false),
            new Candidate("Water_Finite", false, false),
            new Candidate("Water_Source", true, false),
            new Candidate("Gone_Source", true, true));

    @AfterEach
    void resetConfig() {
        FluidConfig.read(JsonParser.parseString("{\"allow\":[],\"deny\":[],\"icons\":{},\"containers\":["
                + "{\"empty\":\"Container_Bucket\",\"filledState\":\"Filled_Water\",\"fluid\":\"Water_Source\","
                + "\"amount\":1000}]}").getAsJsonObject());
    }

    @Test
    void nurQuellenSindLagerbar() {
        assertEquals(List.of("Lava_Source", "Poison_Source", "Slime_Red_Source", "Tar_Source", "Water_Source"),
                FluidRules.storable(GAME, Set.of(), Set.of()));
    }

    @Test
    void listenAusDemDatenordnerErweiternUndSperren() {
        assertEquals(List.of("Poison_Source", "Slime_Red_Source", "Tar_Source", "Water_Finite", "Water_Source"),
                FluidRules.storable(GAME, Set.of("Water_Finite"), Set.of("Lava_Source")));
        // sperren gewinnt, Platzhalter und die leere Zelle bleiben immer draußen
        assertEquals(List.of("Water_Source"), FluidRules.storable(GAME, Set.of("Empty", "Gone_Source", "Lava_Source"),
                Set.of("Lava_Source", "Poison_Source", "Slime_Red_Source", "Tar_Source")));
    }

    @Test
    void namenUndAnzeigeItems() {
        assertEquals("Water", FluidRules.baseName("Water_Source"));
        assertEquals("Fluid_Water", FluidRules.conventionalIconItem("Water_Source"));
        assertEquals("Fluid_Slime_Red", FluidRules.conventionalIconItem("Slime_Red_Source"));
        assertEquals("Fluid_Oil", FluidRules.conventionalIconItem("Oil"));
        assertEquals("Slime Red", FluidRules.fallbackName("Slime_Red_Source"));
        assertEquals("_Source", FluidRules.baseName("_Source"));
    }

    @Test
    void durchschaltenNimmtDieNaechsteFreie() {
        List<String> all = List.of("Lava_Source", "Poison_Source", "Water_Source");
        assertEquals("Lava_Source", FluidRules.next(all, null, id -> false));
        assertEquals("Poison_Source", FluidRules.next(all, "Lava_Source", id -> false));
        assertEquals("Lava_Source", FluidRules.next(all, "Water_Source", id -> false), "nach der letzten wieder vorn");
        assertEquals("Water_Source", FluidRules.next(all, "Lava_Source", "Poison_Source"::equals));
        assertNull(FluidRules.next(all, "Lava_Source", id -> true));
        assertNull(FluidRules.next(List.of("Lava_Source"), "Lava_Source", id -> false));
        assertNull(FluidRules.next(List.of(), null, id -> false));
        // die Flüssigkeit im Platz ist nicht mehr lagerbar: es beginnt vorn
        assertEquals("Lava_Source", FluidRules.next(all, "Oil_Source", id -> false));
    }

    @Test
    void filterplatzMitBehaelter() {
        List<String> all = List.of("Lava_Source", "Poison_Source", "Water_Source");
        AEFluidKey water = AEFluidKey.of("Water_Source");
        ConfigInventory filter = ConfigInventory.configTypes(63);

        // gefüllter Eimer auf einen leeren Platz: seine Flüssigkeit; leerer Eimer: die erste freie
        assertEquals(water, ConfigEdits.fluidForSlot(filter, 18, 0, water, all));
        assertEquals(AEFluidKey.of("Lava_Source"), ConfigEdits.fluidForSlot(filter, 18, 0, null, all));
        assertEquals(ConfigEdits.Result.DONE, ConfigEdits.put(filter, 18, 0, water, 1, true));
        assertEquals(water, filter.getKey(0));

        // weiterer Rechtsklick: nächste Flüssigkeit, die noch nicht im Filter steht
        assertEquals(ConfigEdits.Result.DONE, ConfigEdits.put(filter, 18, 1, AEFluidKey.of("Lava_Source"), 1, true));
        AEFluidKey next = ConfigEdits.fluidForSlot(filter, 18, 0, water, all);
        assertEquals(AEFluidKey.of("Poison_Source"), next);
        assertEquals(ConfigEdits.Result.DONE, ConfigEdits.put(filter, 18, 0, next, 1, true));
        assertEquals(next, filter.getKey(0));
        assertEquals(water, ConfigEdits.fluidForSlot(filter, 18, 0, null, all));

        // ein Item im Platz bleibt, wie es ist; Plätze hinter den freigeschalteten gibt es nicht
        filter.setKey(2, AEItemKey.of("Rock_Stone"));
        assertNull(ConfigEdits.fluidForSlot(filter, 18, 2, water, all));
        assertNull(ConfigEdits.fluidForSlot(filter, 18, 18, water, all));
        assertNull(ConfigEdits.fluidForSlot(filter, 18, -1, water, all));
        // dieselbe Flüssigkeit zweimal lehnt der Filter ab
        assertEquals(ConfigEdits.Result.DUPLICATE, ConfigEdits.put(filter, 18, 3, AEFluidKey.of("Lava_Source"), 1, true));
    }

    @Test
    void mengenInEimern() {
        assertEquals("16 B", FluidText.full(16_000, "en-US"));
        assertEquals("12.5 B", FluidText.full(12_500, null));
        assertEquals("12,5 B", FluidText.full(12_500, "de-DE"));
        assertEquals("0 B", FluidText.full(-5, null));
        assertEquals("16", FluidText.slot(16_000));
        assertEquals(".125", FluidText.slot(125));
        assertEquals("12k", FluidText.slot(12_345_678));
        assertEquals("12.5 / 16 B", FluidText.of(12_500, 16_000, "en-US"));
        assertEquals("0 / 16 B", FluidText.of(0, 16_000, null));
    }

    @Test
    void eintragSchluesselImTerminal() {
        assertEquals("fluid:Water_Source", TerminalBackend.fluidKey("Water_Source"));
        assertEquals("Water_Source", TerminalBackend.fluidIdOf("fluid:Water_Source"));
        // Item-Schlüssel (auch das Item, das Wasser nur anzeigt) sind keine Flüssigkeiten
        assertNull(TerminalBackend.fluidIdOf("Fluid_Water"));
        assertNull(TerminalBackend.fluidIdOf("fluid:"));
        assertNull(TerminalBackend.fluidIdOf(null));
    }

    @Test
    void einstellungenAusDerDatei() {
        FluidConfig.read(JsonParser.parseString("{\"deny\":[\"Lava_Source\",\"\"],\"allow\":[\"Oil\"],"
                + "\"icons\":{\"Oil\":\"Mod_Oil_Bucket\"},\"containers\":[{\"empty\":\"Mod_Can\",\"filledState\":"
                + "\"Filled_Oil\",\"fluid\":\"Oil\",\"amount\":250},{\"empty\":\"\",\"fluid\":\"Oil\"}]}").getAsJsonObject());
        assertEquals(Set.of("Lava_Source"), FluidConfig.deny());
        assertEquals(Set.of("Oil"), FluidConfig.allow());
        assertEquals("Mod_Oil_Bucket", FluidConfig.icon("Oil"));
        assertNull(FluidConfig.icon("Water_Source"));
        assertEquals(List.of(new FluidConfig.ContainerSpec("Mod_Can", "Filled_Oil", "Oil", 250)), FluidConfig.containers());

        // fehlende Felder: der Eimer bleibt, die Listen sind leer
        resetConfig();
        FluidConfig.read(JsonParser.parseString("{}").getAsJsonObject());
        assertTrue(FluidConfig.deny().isEmpty());
        assertEquals(List.of(FluidConfig.WATER_BUCKET), FluidConfig.containers());
    }
}
