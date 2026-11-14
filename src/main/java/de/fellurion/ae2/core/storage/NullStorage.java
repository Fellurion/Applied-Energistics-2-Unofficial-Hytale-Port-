/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

/**
 * An empty storage that accepts nothing. Port of {@code appeng.me.storage.NullInventory}.
 */
public final class NullStorage implements MEStorage {
    public static final NullStorage INSTANCE = new NullStorage();

    private NullStorage() {
    }

    public static NullStorage of() {
        return INSTANCE;
    }

    @Override
    public String getDescription() {
        return "ae2.null_storage";
    }
}
