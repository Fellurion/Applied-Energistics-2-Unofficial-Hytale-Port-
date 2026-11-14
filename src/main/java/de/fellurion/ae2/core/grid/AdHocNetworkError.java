/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

/**
 * Why an ad-hoc network (no controller) has no channels. Port of {@code appeng.me.service.AdHocNetworkError}.
 */
public enum AdHocNetworkError {
    /** More devices require channels than the ad-hoc limit (8 by default). */
    TOO_MANY_CHANNELS,
    /** P2P tunnels nested in an ad-hoc network. */
    NESTED_P2P_TUNNEL
}
