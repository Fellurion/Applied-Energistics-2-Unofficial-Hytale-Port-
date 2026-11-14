/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.Objects;

/**
 * Forwards all calls to a (replaceable) delegate. Port of {@code appeng.me.storage.DelegatingMEInventory}.
 */
public class DelegatingMEStorage implements MEStorage {
    private MEStorage delegate;

    public DelegatingMEStorage(MEStorage delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    public MEStorage getDelegate() {
        return delegate;
    }

    public void setDelegate(MEStorage delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public boolean isPreferredStorageFor(AEKey input, IActionSource source) {
        return getDelegate().isPreferredStorageFor(input, source);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return getDelegate().insert(what, amount, mode, source);
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        return getDelegate().extract(what, amount, mode, source);
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        getDelegate().getAvailableStacks(out);
    }

    @Override
    public String getDescription() {
        return getDelegate().getDescription();
    }

    @Override
    public int getEstimatedStackCount() {
        return getDelegate().getEstimatedStackCount();
    }
}
