/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.render.model.QuartzGlassModel).
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;

/**
 * Quarzglas / lebendiges Quarzglas: verbindet sich optisch mit benachbartem Quarzglas. Der State
 * {@code C<maske>} wählt eins der 64 Formmodelle (Seiten zum Nachbarglas
 * entfallen, Rahmen nur an Kanten ohne Nachbarglas).
 */
public final class GlassDevice implements Device {

    public static final String KIND = "quartz_glass";

    private long seenVersion = -1;
    private String state = "default";

    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        if (seenVersion != nets.topologyVersion()) {
            seenVersion = nets.topologyVersion();
            int mask = 0;
            for (Dir d : Dir.values()) {
                WorldNetworks.Node n = nets.node(node.x() + d.dx, node.y() + d.dy, node.z() + d.dz);
                if (n != null && KIND.equals(n.kind())) {
                    mask |= d.bit();
                }
            }
            state = mask == 0 ? "default" : "C" + mask;
        }
        return state;
    }
}
