/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.p2p;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEKeyType;

import java.util.List;

/**
 * One end of a P2P tunnel: its frequency, its role (input or output) and the lookup of the other ends on the same
 * network. Port of {@code appeng.parts.p2p.P2PTunnelPart}; it is the owner of the tunnel's main grid node (back side
 * of the block, on the carrying network: {@link P2PTunnelType#mainNodeType()}).
 * <p>
 * A tunnel with frequency 0 is not linked. Per frequency a network has one input and any number of outputs; they
 * are tracked by the network's {@link P2PService}. Linking works as with AE2's memory card:
 * {@link #saveToMemoryCard()} turns a tunnel into the input of a frequency, {@link #loadFromMemoryCard(short)} makes
 * another tunnel an output of it.
 * <p>
 * What a tunnel transports is up to its user: {@link MEP2PTunnelLogic} carries channels inside the core; item,
 * redstone and light tunnels are driven by the integration layer through {@link #getInput()} /
 * {@link #getOutputs()}; fluid and FE tunnels only link.
 * <p>
 * Deviations: converting a tunnel to another type (attunement, memory card of another type) replaces the block and
 * is done by the integration layer, which carries frequency and role over with {@link #restore}; AE2's memory card
 * colors are available through {@link P2PFrequency}.
 */
public class P2PTunnelLogic {
    private final P2PTunnelType type;
    private boolean output;
    private short freq;
    private GridNode mainNode;
    private long changeCount;
    private Runnable saveListener;

    protected P2PTunnelLogic(P2PTunnelType type) {
        this.type = type;
    }

    /** Creates the logic for a tunnel of the given type ({@link MEP2PTunnelLogic} for ME tunnels). */
    public static P2PTunnelLogic create(P2PTunnelType type) {
        return type == P2PTunnelType.ME ? new MEP2PTunnelLogic() : new P2PTunnelLogic(type);
    }

    public P2PTunnelType getType() {
        return type;
    }

    /** The tunnel's main node (set when it joins a grid), or null. */
    public GridNode getMainNode() {
        return mainNode;
    }

    void setMainNode(GridNode node) {
        this.mainNode = node;
    }

    /** 0 = not linked. */
    public short getFrequency() {
        return freq;
    }

    void setFrequency(short freq) {
        short old = this.freq;
        this.freq = freq;
        if (old != freq) {
            markForSave();
        }
    }

    public boolean isOutput() {
        return output;
    }

    void setOutput(boolean output) {
        if (this.output != output) {
            this.output = output;
            markForSave();
        }
    }

    /**
     * Sets the persisted frequency and role. Only before the main node is added to the world (AE2:
     * {@code readFromNBT}).
     */
    public void restore(short freq, boolean output) {
        if (mainNode != null && !mainNode.isRemoved()) {
            throw new IllegalStateException("restore() must be called before the tunnel joins a grid");
        }
        this.freq = freq;
        this.output = output;
    }

    /** Called whenever frequency or role changed and should be persisted. */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    private void markForSave() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    /** True while the main node is powered and has its channel; a tunnel transports nothing otherwise. */
    public boolean isActive() {
        return mainNode != null && mainNode.isActive();
    }

    /** The input of this tunnel's frequency on its network, if it is of the same type; null if not linked. */
    public P2PTunnelLogic getInput() {
        if (freq == 0 || mainNode == null) {
            return null;
        }
        var grid = mainNode.getGrid();
        if (grid != null) {
            var tunnel = grid.getP2PService().getInput(freq);
            if (tunnel != null && tunnel.type == type) {
                return tunnel;
            }
        }
        return null;
    }

    /**
     * The outputs of this tunnel's frequency on its network (same type, and only if the frequency's input is of that
     * type too). Empty while this tunnel is not online.
     */
    public List<P2PTunnelLogic> getOutputs() {
        if (mainNode != null && mainNode.isOnline()) {
            var grid = mainNode.getGrid();
            if (grid != null) {
                return grid.getP2PService().getOutputs(freq, type);
            }
        }
        return List.of();
    }

    /**
     * Memory card, save: makes this tunnel the input of a frequency - a new one if it was an output or not linked,
     * else it keeps its frequency. Port of the save branch of {@code P2PTunnelPart.onUseItemOn}; store
     * {@link #getFrequency()} and {@link #getType()} on the card afterwards.
     *
     * @return true if a new frequency was generated (AE2: {@code SETTINGS_RESET}), false if the existing one was
     *         kept ({@code SETTINGS_SAVED})
     */
    public boolean saveToMemoryCard() {
        short newFreq = this.freq;
        boolean wasOutput = this.output;
        setOutput(false);

        boolean needsNewFrequency = wasOutput || newFreq == 0;

        var grid = mainNode == null ? null : mainNode.getGrid();
        if (grid != null) {
            var p2p = grid.getP2PService();
            if (needsNewFrequency) {
                newFreq = p2p.newFrequency();
            }
            p2p.updateFreq(this, newFreq);
        }

        onTunnelConfigChange();
        return needsNewFrequency;
    }

    /**
     * Memory card, load: makes this tunnel an output of {@code frequency}. Port of
     * {@code P2PTunnelPart.importSettings}. Nothing happens if the tunnel already is on that frequency - otherwise
     * loading an input with its own settings would make it unusable.
     *
     * @return true if the tunnel changed
     */
    public boolean loadFromMemoryCard(short frequency) {
        if (frequency == this.freq) {
            return false;
        }
        setOutput(true);
        var grid = mainNode == null ? null : mainNode.getGrid();
        if (grid != null) {
            grid.getP2PService().updateFreq(this, frequency);
        } else {
            setFrequency(frequency); // Remember it for when we actually join the grid
            onTunnelNetworkChange();
        }
        return true;
    }

    /**
     * Counts every change of this tunnel's link: its own frequency or role, and inputs/outputs of its frequency
     * joining, leaving or being relinked. Whoever caches something derived from {@link #getInput()} /
     * {@link #getOutputs()} compares this number.
     */
    public long getChangeCount() {
        return changeCount;
    }

    /** The frequency or role of this tunnel or of a tunnel linked to it changed. */
    protected void onTunnelConfigChange() {
        changeCount++;
    }

    /** A tunnel of this tunnel's frequency joined or left the network. */
    protected void onTunnelNetworkChange() {
        changeCount++;
    }

    /**
     * Charges the network of this tunnel for transported items/fluids: {@code p2pTunnelTransportTax} (0.025 AE) per
     * operation unit (one item, 125 mB). Port of {@code P2PTunnelPart.deductTransportCost}.
     */
    public void deductTransportCost(long amountTransported, AEKeyType typeTransported) {
        double costFactor = AEConfig.get().p2pTunnelTransportTax();
        if (costFactor <= 0 || amountTransported <= 0) {
            return;
        }
        var grid = mainNode == null ? null : mainNode.getGrid();
        if (grid != null) {
            double operations = amountTransported / (double) typeTransported.getAmountPerOperation();
            grid.getEnergyService().extractAEPower(operations * costFactor, Actionable.MODULATE, PowerMultiplier.ONE);
        }
    }

    /**
     * Charges the network of this tunnel for transported energy: {@code p2pTunnelEnergyTax} (2.5 %) of the amount,
     * given in AE. Port of {@code P2PTunnelPart.deductEnergyCost}.
     */
    public void deductEnergyCost(double aeTransported) {
        double costFactor = AEConfig.get().p2pTunnelEnergyTax();
        if (costFactor <= 0 || aeTransported <= 0) {
            return;
        }
        var grid = mainNode == null ? null : mainNode.getGrid();
        if (grid != null) {
            grid.getEnergyService().extractAEPower(aeTransported * costFactor, Actionable.MODULATE,
                    PowerMultiplier.ONE);
        }
    }

    @Override
    public String toString() {
        return "P2PTunnel[" + type.id() + " " + (output ? "output" : "input") + " "
                + P2PFrequency.toHexString(freq) + (mainNode == null ? "" : " @ " + mainNode) + "]";
    }
}
