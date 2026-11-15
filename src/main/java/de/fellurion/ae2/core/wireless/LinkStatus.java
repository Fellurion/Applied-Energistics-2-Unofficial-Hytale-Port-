/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.wireless;

/**
 * Whether a handheld terminal can currently reach its storage, and if not, why. Port of
 * {@code appeng.api.storage.ILinkStatus} with the reasons AE2 reports for wireless terminals and portable cells
 * ({@code PlayerMessages.DeviceNotLinked}, {@code LinkedNetworkNotFound}, {@code OutOfRange},
 * {@code GuiText.OutOfPower}). The integration layer translates the reasons.
 */
public enum LinkStatus {
    CONNECTED,
    /** The battery of the item is empty. */
    OUT_OF_POWER,
    /** The terminal was never linked to an access point. */
    NOT_LINKED,
    /** The linked access point is gone, not loaded or has no grid. */
    NETWORK_NOT_FOUND,
    /** No active access point of the linked grid is in range. */
    OUT_OF_RANGE;

    public boolean connected() {
        return this == CONNECTED;
    }
}
