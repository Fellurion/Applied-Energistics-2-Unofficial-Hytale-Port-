/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

/**
 * A node or connection participating in channel path finding. Port of {@code appeng.me.pathfinding.IPathItem}.
 */
interface IPathItem {
    void setAdHocChannels(int channels);

    IPathItem getControllerRoute();

    void setControllerRoute(IPathItem fast);

    int getMaxChannels();

    Iterable<IPathItem> getPossibleOptions();

    boolean hasFlag(GridFlag flag);

    void finalizeChannels();
}
