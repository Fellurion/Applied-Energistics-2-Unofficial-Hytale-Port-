/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Zustandszeile der Blockinfo und Gerätezählung am Controller, gegen den echten Kern. */
class NetworkStatusTest {

    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    private static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private GridNode cable(int x) {
        return world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
    }

    private GridNode terminalAbove(int x) {
        return world.addNode(p(x, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
    }

    private void power() {
        world.addNode(p(-1, 0, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
    }

    @Test
    void onlineWithPowerAndChannel() {
        power();
        GridNode c = cable(0);
        GridNode t = terminalAbove(0);
        world.tick();
        assertEquals(NetworkStatus.ONLINE, NetworkStatus.of(t));
        assertEquals(NetworkStatus.ONLINE, NetworkStatus.of(c));
    }

    @Test
    void noPowerWithoutEnergy() {
        cable(0);
        GridNode t = terminalAbove(0);
        world.tick(5);
        assertEquals(NetworkStatus.NO_POWER, NetworkStatus.of(t));
    }

    @Test
    void tooManyDevicesWithoutController() {
        power();
        List<GridNode> terminals = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            cable(x);
            terminals.add(terminalAbove(x));
        }
        world.tick();
        assertEquals(NetworkStatus.TOO_MANY_DEVICES, NetworkStatus.of(terminals.getFirst()));
    }

    @Test
    void conflictWhenControllerTooLarge() {
        power();
        for (int x = 0; x < 8; x++) {
            world.addNode(p(x, 0, 0), NodeType.CONTROLLER, null, null, null);
        }
        GridNode t = terminalAbove(0);
        world.tick();
        assertEquals(NetworkStatus.CONFLICT, NetworkStatus.of(t));
    }

    @Test
    void controllerCountsDevicesNeedingChannels() {
        power();
        world.addNode(p(0, -1, 0), NodeType.CONTROLLER, null, null, null);
        for (int x = 0; x < 5; x++) {
            cable(x);
            terminalAbove(x);
        }
        world.tick();
        GridNode controller = world.getNode(p(0, -1, 0));
        HudContext ctx = new HudContext();
        assertEquals(5, ctx.devicesNeedingChannels(controller.getGrid()));
        assertEquals(5, controller.getGrid().getPathingService().getUsedChannels());
        // Zwischenspeicher: gleiche Größe -> kein neues Zählen, neue Geräte -> neu gezählt
        assertEquals(5, ctx.devicesNeedingChannels(controller.getGrid()));
        cable(5);
        terminalAbove(5);
        world.tick();
        assertEquals(6, ctx.devicesNeedingChannels(controller.getGrid()));
    }

    @Test
    void percentRoundsDown() {
        assertEquals(0, HudContext.percent(Double.NaN));
        assertEquals(0, HudContext.percent(-1));
        assertEquals(99, HudContext.percent(0.999));
        assertEquals(100, HudContext.percent(1.5));
    }
}
