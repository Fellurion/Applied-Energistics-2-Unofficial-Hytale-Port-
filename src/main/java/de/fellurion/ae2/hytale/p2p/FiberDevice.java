/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.networking.QuartzFiberPart).
 */
package de.fellurion.ae2.hytale.p2p;

import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.parts.QuartzFiberLogic;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;

import javax.annotation.Nullable;

/**
 * Quartz Fiber: teilt die Energie zweier ME-Netze, ohne sie zu verbinden. Das Netz an der Rückseite und das Netz an
 * der Vorderseite bleiben getrennt (eigene Kanäle, eigener Controller, eigener Speicher), bedienen sich aber aus
 * denselben Energiespeichern. Die Logik steckt im Kern ({@link QuartzFiberLogic}); hier wird nur der zweite Knoten
 * an der Vorderseite angelegt.
 */
final class FiberDevice implements Device {

    private final QuartzFiberLogic logic = new QuartzFiberLogic();

    QuartzFiberLogic logic() {
        return logic;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public NodeType nodeType(WorldNetworks nets, WorldNetworks.Node node) {
        return NodeType.QUARTZ_FIBER_MAIN;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        GridNode main = node.gridNode();
        if (main != null) {
            logic.createOuterNode(main);
        }
    }

    /** {@code Online}, wenn beide Seiten an einem Netz hängen und eines davon Strom hat. */
    @Nullable
    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        GridNode main = logic.getMainNode();
        GridNode outer = logic.getOuterNode();
        boolean online = main != null && outer != null && (main.isPowered() || outer.isPowered());
        return online ? "Online" : "default";
    }
}
