/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.spatial.SpatialPylonBlockEntity).
 */
package de.fellurion.ae2.hytale.spatial;

import de.fellurion.ae2.core.spatial.SpatialPylonLogic;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;

/**
 * Spatial Pylon: meldet seine Position bei den Pylonen der Welt an (dort entstehen die Linien) und zeigt seinen
 * Zustand. AE2 kennt vier Bilder – ohne Linie, in einer Linie ohne Strom, mit Strom aber ungültigem Gebiet (rot),
 * gültig (leuchtend); hier sind es die Block-States {@code Formed}, {@code Powered} und {@code Online}, soweit das
 * Block-Asset sie hat (fehlende States ignoriert Hytale).
 */
final class PylonDevice implements Device {

    private final SpatialWorld spatial;
    private final SpatialPylonLogic logic;
    private final int x, y, z;
    private long shownVersion = Long.MIN_VALUE;
    private String shown = "default";

    PylonDevice(WorldNetworks nets, WorldNetworks.Node node) {
        this.spatial = SpatialWorld.of(nets);
        this.x = node.x();
        this.y = node.y();
        this.z = node.z();
        // Vor dem Anlegen des Netzknotens: die Linie steht, wenn der Kern nach den Knoten der Linie fragt
        spatial.pylons.add(x, y, z);
        this.logic = new SpatialPylonLogic(spatial.pylons, x, y, z);
    }

    SpatialPylonLogic logic() {
        return logic;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        spatial.pylons.remove(x, y, z);
    }

    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        // Jeden Tick gefragt: nur neu bestimmen, wenn sich an Linien, Kanälen oder Strom etwas getan hat
        long version = spatial.pylons.stateVersion();
        if (version != shownVersion) {
            shownVersion = version;
            SpatialPylonLogic.DisplayState state = logic.displayState();
            if (state.axisPosition() == SpatialPylonLogic.AxisPosition.NONE) {
                shown = "default";
            } else if (state.online()) {
                shown = "Online";
            } else {
                shown = state.powered() ? "Powered" : "Formed";
            }
        }
        return shown;
    }
}
