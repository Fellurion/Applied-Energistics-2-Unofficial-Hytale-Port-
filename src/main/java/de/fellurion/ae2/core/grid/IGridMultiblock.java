/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

import java.util.Iterator;

/**
 * Service of multiblock nodes (crafting CPU, spatial pylons): all nodes of the multiblock share one channel. Port of
 * {@code appeng.api.networking.IGridMultiblock}.
 */
public interface IGridMultiblock {
    Iterator<GridNode> getMultiblockNodes();
}
