/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.networking.EnergyCellBlockEntity).
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.hytale.WorldNetworks;

/** Energiezelle / dichte Energiezelle: Ladung in der Block-Komponente, Füllstand als State {@code Fill_N}. */
public final class EnergyCellDevice implements Device {

    private final EnergyCellLogic logic;
    private boolean changed;
    private long lastSaveTick;

    public EnergyCellDevice(EnergyCellLogic logic) {
        this.logic = logic;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        logic.setAECurrentPower(Math.min(node.comp.energy(), logic.getAEMaxPower()));
        logic.setChangeListener(() -> changed = true);
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        // höchstens 1× pro Sekunde in die Komponente schreiben
        if (changed && nets.tickCount() - lastSaveTick >= 20) {
            save(node);
            lastSaveTick = nets.tickCount();
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        save(node);
    }

    private void save(WorldNetworks.Node node) {
        changed = false;
        node.comp.setEnergy(logic.getAECurrentPower());
        node.markDirty();
    }

    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        int level = logic.getFillLevel();
        return level <= 0 ? "default" : "Fill_" + Math.min(4, level);
    }
}
