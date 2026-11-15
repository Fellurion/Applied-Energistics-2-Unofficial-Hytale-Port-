/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches a placement to the strategy of the key's type; keys without a strategy are not placed. Port of
 * {@code appeng.parts.automation.PlacementStrategyFacade}.
 */
public final class PlacementStrategyFacade implements PlacementStrategy {
    private final Map<AEKeyType, PlacementStrategy> strategies;

    public PlacementStrategyFacade(Map<AEKeyType, PlacementStrategy> strategies) {
        this.strategies = new EnumMap<>(AEKeyType.class);
        this.strategies.putAll(strategies);
    }

    /** The key types that can be placed (AE2: {@code StackWorldBehaviors.withPlacementStrategy()}). */
    public Set<AEKeyType> supportedTypes() {
        return strategies.keySet();
    }

    @Override
    public void clearBlocked() {
        for (var strategy : strategies.values()) {
            strategy.clearBlocked();
        }
    }

    @Override
    public long placeInWorld(AEKey what, long amount, Actionable type, boolean placeAsEntity) {
        var strategy = strategies.get(what.getType());
        return strategy != null ? strategy.placeInWorld(what, amount, type, placeAsEntity) : 0;
    }
}
