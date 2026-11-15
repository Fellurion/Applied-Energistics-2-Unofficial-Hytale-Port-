/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.quantum;

import de.fellurion.ae2.core.grid.GridConnection;

/**
 * The one connection of a linked pair of quantum bridges. Both ends hold the same wrapper, so the end that takes the
 * connection down clears it for the other end as well. Port of {@code appeng.me.service.helpers.ConnectionWrapper}.
 */
final class ConnectionWrapper {
    private GridConnection connection;

    ConnectionWrapper(GridConnection connection) {
        this.connection = connection;
    }

    GridConnection getConnection() {
        return connection;
    }

    void setConnection(GridConnection connection) {
        this.connection = connection;
    }
}
