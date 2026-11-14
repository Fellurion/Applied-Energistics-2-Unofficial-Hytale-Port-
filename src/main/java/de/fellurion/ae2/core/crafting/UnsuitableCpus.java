/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

/**
 * Why no CPU could be selected. Port of {@code appeng.api.networking.crafting.UnsuitableCpus}.
 */
public record UnsuitableCpus(int offline, int busy, int tooSmall, int excluded) {
}
