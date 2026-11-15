/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: die Fähigkeiten, die ein AE2-Block seinen Nachbarn anbietet – der Fluid-Handler des Sky Stone Tank,
 * appeng.blockentity.misc.CondenserMEStorage; gefunden von appeng.parts.automation.StackWorldBehaviors und
 * appeng.parts.storagebus.StorageBusPart).
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.automation.ExternalStorage;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.hytale.BlockAccess.Dir;

import javax.annotation.Nullable;

/**
 * Ein Gerät, das Bussen und P2P-Tunneln vor ihm statt seines Item-Containers ein eigenes Lager anbietet – etwa der
 * Sky Stone Tank seine Flüssigkeit oder der Condenser sein „alles verschwindet“-Lager. Wer auf den Block vor sich
 * zugreift ({@link BusDevice}, Fluid-P2P-Tunnel), fragt das Gerät dort zuerst danach.
 *
 * <p>Die Lager müssen je Gerät <b>dieselben Objekte</b> bleiben: Ein Bus erkennt daran, dass sich sein Ziel nicht
 * geändert hat. Liefert eine Methode null, gilt für diesen Zugriff der Container des Blocks wie bisher.
 */
public interface StorageTarget {

    /**
     * Das Lager für Import- und Export-Bus und für P2P-Tunnel (AE2: Item- bzw. Fluid-Fähigkeit des Blocks).
     *
     * @param side Seite dieses Blocks, an der der Zugreifende steht
     */
    @Nullable
    default ExternalStorage busStorage(Dir side) {
        return null;
    }

    /**
     * Das Lager für den Storage-Bus (AE2: zuerst die ME-Storage-Fähigkeit des Blocks, sonst seine Item- und
     * Fluid-Fähigkeit). Standard: dasselbe wie {@link #busStorage}.
     *
     * @param side Seite dieses Blocks, an der der Storage-Bus steht
     */
    @Nullable
    default MEStorage meStorage(Dir side) {
        return busStorage(side);
    }
}
