/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.grid.IActionHost;

import java.util.Optional;

/**
 * Describes who performs a storage operation (a player or a machine). Port of
 * {@code appeng.api.networking.security.IActionSource} (+ {@code appeng.me.helpers.PlayerSource} /
 * {@code MachineSource} / {@code BaseActionSource}).
 * <p>
 * The player object is opaque to the core: the integration layer can pass its own player handle (e.g. a Hytale
 * {@code PlayerRef} or a UUID).
 */
public interface IActionSource {
    /** The player performing the action, if any (opaque integration object). */
    Optional<Object> player();

    /** The machine performing the action, if any. */
    Optional<IActionHost> machine();

    /** Additional typed context (AE2 uses it e.g. for the interface priority of interface-to-interface requests). */
    default <T> Optional<T> context(Class<T> key) {
        return Optional.empty();
    }

    IActionSource EMPTY = new IActionSource() {
        @Override
        public Optional<Object> player() {
            return Optional.empty();
        }

        @Override
        public Optional<IActionHost> machine() {
            return Optional.empty();
        }
    };

    static IActionSource empty() {
        return EMPTY;
    }

    static IActionSource ofPlayer(Object player) {
        return ofPlayer(player, null);
    }

    /** A player acting through a terminal/machine (AE2 {@code PlayerSource(player, host)}). */
    static IActionSource ofPlayer(Object player, IActionHost via) {
        return new IActionSource() {
            @Override
            public Optional<Object> player() {
                return Optional.ofNullable(player);
            }

            @Override
            public Optional<IActionHost> machine() {
                return Optional.ofNullable(via);
            }
        };
    }

    static IActionSource ofMachine(IActionHost host) {
        return new IActionSource() {
            @Override
            public Optional<Object> player() {
                return Optional.empty();
            }

            @Override
            public Optional<IActionHost> machine() {
                return Optional.ofNullable(host);
            }
        };
    }
}
