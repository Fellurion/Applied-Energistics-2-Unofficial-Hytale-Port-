/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage.partition;

import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.IncludeExclude;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

/**
 * A filter list (partition / whitelist / blacklist). Port of {@code appeng.util.prioritylist.IPartitionList} with
 * {@code DefaultPriorityList}, {@code PrecisePriorityList} and {@code FuzzyPriorityList}.
 */
public interface IPartitionList {
    boolean isListed(AEKey input);

    boolean isEmpty();

    Iterable<AEKey> getItems();

    /**
     * Checks if the given key matches this partition list assuming WHITELIST/BLACKLIST mode. Empty lists always
     * match.
     */
    default boolean matchesFilter(AEKey key, IncludeExclude mode) {
        if (!isEmpty()) {
            switch (mode) {
                case WHITELIST -> {
                    if (!isListed(key)) {
                        return false;
                    }
                }
                case BLACKLIST -> {
                    if (isListed(key)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static Builder builder() {
        return new Builder();
    }

    /** The empty list. */
    IPartitionList EMPTY = new IPartitionList() {
        @Override
        public boolean isListed(AEKey input) {
            return false;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public Iterable<AEKey> getItems() {
            return java.util.List.of();
        }
    };

    final class Builder {
        private final KeyCounter keys = new KeyCounter();
        private FuzzyMode fuzzyMode;

        private Builder() {
        }

        public Builder add(AEKey key) {
            if (key != null) {
                keys.add(key, 1);
            }
            return this;
        }

        public Builder addAll(Iterable<? extends AEKey> keys) {
            for (var key : keys) {
                add(key);
            }
            return this;
        }

        public Builder fuzzyMode(FuzzyMode mode) {
            this.fuzzyMode = mode;
            return this;
        }

        public IPartitionList build() {
            if (keys.isEmpty()) {
                return EMPTY;
            }
            if (fuzzyMode != null) {
                return new FuzzyPriorityList(keys, fuzzyMode);
            }
            return new PrecisePriorityList(keys);
        }
    }

    /** Exact matching. Port of {@code PrecisePriorityList}. */
    record PrecisePriorityList(KeyCounter list) implements IPartitionList {
        @Override
        public boolean isListed(AEKey input) {
            return list.get(input) > 0;
        }

        @Override
        public boolean isEmpty() {
            return list.isEmpty();
        }

        @Override
        public Iterable<AEKey> getItems() {
            return list.keySet();
        }
    }

    /** Fuzzy matching (fuzzy card). Port of {@code FuzzyPriorityList}. */
    record FuzzyPriorityList(KeyCounter list, FuzzyMode mode) implements IPartitionList {
        @Override
        public boolean isListed(AEKey input) {
            return !list.findFuzzy(input, mode).isEmpty();
        }

        @Override
        public boolean isEmpty() {
            return list.isEmpty();
        }

        @Override
        public Iterable<AEKey> getItems() {
            return list.keySet();
        }
    }
}
