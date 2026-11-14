/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.misc.CrankBlockEntity).
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.machines.CrankLogic;
import de.fellurion.ae2.core.machines.ICrankable;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;

/**
 * Kurbel: treibt den Inscriber/Charger an, auf dem sie sitzt (AE2: Block hinter der Kurbel). Jede volle
 * Umdrehung liefert der Maschine Energie (Kern: 160 AE) – Strom ohne ME-Netz.
 */
public final class CrankDevice implements Device {

    private final CrankLogic logic = new CrankLogic();

    /** Spieler benutzt die Kurbel. */
    public boolean turn(WorldNetworks nets, WorldNetworks.Node node) {
        ICrankable target = target(nets, node);
        return target != null && logic.power(target);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (logic.isTurning()) {
            ICrankable target = target(nets, node);
            if (target != null) {
                logic.tick(target);
            }
        }
    }

    @Nullable
    private static ICrankable target(WorldNetworks nets, WorldNetworks.Node node) {
        Dir front = node.facing() == null ? Dir.UP : node.facing();
        Dir back = front.opposite();
        WorldNetworks.Node t = nets.node(node.x() + back.dx, node.y() + back.dy, node.z() + back.dz);
        return t != null && t.device() instanceof MachineDevice m ? m.crankable() : null;
    }
}
