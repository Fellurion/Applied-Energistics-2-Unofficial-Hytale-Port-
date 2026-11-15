/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ioport;

import de.fellurion.ae2.core.automation.IOPortLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.FullnessMode;
import de.fellurion.ae2.core.config.OperationMode;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.ConfigHost.Context;
import de.fellurion.ae2.hytale.ui.ConfigHost.Setting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Was der IO-Port der Konfigurationsseite als {@link ConfigHost} anbietet (AE2: IOPortScreen) und welche Plätze er
 * Bussen freigibt. Ohne Container: ein Port ohne Block hat keine Zellen.
 */
class IOPortDeviceTest {

    /** Ohne Server gibt es keine Übersetzung: Der Schlüssel steht für den Text. */
    private static final Context CTX = new Context(null, null, "en-US") {
        @Override
        public String tr(String key, Object... params) {
            return key;
        }
    };

    private static List<String> texts(ConfigHost host) {
        List<String> out = new ArrayList<>();
        for (Setting s : host.settings(CTX)) {
            out.add(s.text());
        }
        return out;
    }

    @Test
    void abschnitteDerSeite() {
        IOPortDevice port = new IOPortDevice();
        assertNull(port.ghostSlots(CTX), "kein Filter");
        assertFalse(port.hasPriority());
        assertNull(port.numberField(CTX));
        assertSame(port.logic().getUpgrades(), port.upgradeCards());
        assertEquals(IOPortLogic.HOST_ITEM_ID, port.upgradeCards().getHostItemId());
        assertEquals(3, port.upgradeCards().size());
        assertTrue(port.opensOnCrouch(), "Benutzen öffnet die Zellenplätze, geduckt die Seite");
        assertEquals("ae2ioport.note.slots", port.note(CTX));
        assertSame(port.logic(), port.owner());
    }

    @Test
    void einstellungenUndIhreReihenfolge() {
        IOPortDevice port = new IOPortDevice();
        IOPortLogic logic = port.logic();
        assertEquals(List.of("ae2ioport.setting.operation.empty", "ae2ioport.setting.fullness.empty"), texts(port));

        // Richtung: Zelle → Netz, Netz → Zelle und wieder zurück
        port.settings(CTX).get(0).next().run();
        assertEquals(OperationMode.FILL, logic.getOperationMode());
        port.settings(CTX).get(0).next().run();
        assertEquals(OperationMode.EMPTY, logic.getOperationMode());

        // Auswurf: leer, fertig, voll
        port.settings(CTX).get(1).next().run();
        assertEquals(FullnessMode.HALF, logic.getFullnessMode());
        port.settings(CTX).get(1).next().run();
        assertEquals(FullnessMode.FULL, logic.getFullnessMode());
        port.settings(CTX).get(1).next().run();
        assertEquals(FullnessMode.EMPTY, logic.getFullnessMode());
    }

    @Test
    void signalNurMitRedstoneKarte() {
        IOPortDevice port = new IOPortDevice();
        IOPortLogic logic = port.logic();
        assertEquals(2, port.settings(CTX).size());
        assertTrue(logic.getUpgrades().insert(UpgradeCard.REDSTONE));
        assertEquals(List.of("ae2ioport.setting.operation.empty", "ae2ioport.setting.fullness.empty",
                "ae2ioport.setting.redstone.ignore"), texts(port));

        // immer aktiv → ohne Signal → mit Signal → immer aktiv; „einmal je Impuls“ kommt nicht vor
        port.settings(CTX).get(2).next().run();
        assertEquals(RedstoneMode.LOW_SIGNAL, logic.getRedstoneMode());
        port.settings(CTX).get(2).next().run();
        assertEquals(RedstoneMode.HIGH_SIGNAL, logic.getRedstoneMode());
        port.settings(CTX).get(2).next().run();
        assertEquals(RedstoneMode.IGNORE, logic.getRedstoneMode());

        // ein von Hand gesetzter Impuls-Modus wird als „ohne Signal“ gezeigt (so wirkt er) und lässt sich weiterschalten
        logic.setRedstoneMode(RedstoneMode.SIGNAL_PULSE);
        assertEquals("ae2ioport.setting.redstone.low_signal", texts(port).get(2));
        port.settings(CTX).get(2).next().run();
        assertEquals(RedstoneMode.HIGH_SIGNAL, logic.getRedstoneMode());
    }

    @Test
    void busseEntnehmenNurAusDemAusgang() {
        IOPortDevice port = new IOPortDevice();
        for (int slot = 0; slot < IOPortDevice.SLOTS; slot++) {
            assertEquals(slot >= IOPortDevice.INPUTS, port.busMayExtract(slot), "Platz " + slot);
        }
    }

    @Test
    void ohneContainerGibtEsNichtsZuTun() {
        IOPortDevice port = new IOPortDevice();
        assertEquals(0, port.waitingCells());
        assertEquals(0, port.finishedCells());
        for (int slot = 0; slot < IOPortDevice.INPUTS; slot++) {
            assertTrue(port.isEmpty(slot));
            assertNull(port.getCell(slot));
            assertFalse(port.moveToOutput(slot));
        }
        assertFalse(port.logic().hasWork());
    }
}
