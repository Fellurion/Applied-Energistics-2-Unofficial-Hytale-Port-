/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import de.fellurion.ae2.core.crafting.CraftingCpuCluster;
import de.fellurion.ae2.core.crafting.CraftingUnitLogic;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridFlag;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import java.util.Locale;

/**
 * Was ein {@link InfoProvider} braucht: Sprache des Spielers, Zahlenformate und kleine Zwischenspeicher.
 * Eine Instanz je Spieler, nur auf dem Welt-Thread benutzt.
 */
public final class HudContext {

    /** Ab diesem Wert gilt Energie als unbegrenzt (Creative-Energiezelle meldet Long.MAX_VALUE / 10000). */
    static final double INFINITE_AE = 1e14;

    private String locale = "en-US";
    private Locale numberLocale = Locale.ROOT;
    private WorldNetworks nets;

    // Zahl der kanalpflichtigen Geräte je Netz: nur nach Topologie-Änderungen neu gezählt
    private Grid countedGrid;
    private long countedTopology = Long.MIN_VALUE;
    private int countedSize = -1;
    private int countedDevices;

    void reset(String locale, WorldNetworks nets) {
        if (!locale.equals(this.locale)) {
            this.locale = locale;
            this.numberLocale = Locale.forLanguageTag(locale);
        }
        if (nets != this.nets) {
            this.nets = nets;
            this.countedGrid = null;
        }
    }

    void forgetCaches() {
        countedGrid = null;
    }

    /** Sprachcode des Spielers (z. B. {@code de-DE}). */
    public String locale() {
        return locale;
    }

    public WorldNetworks nets() {
        return nets;
    }

    /** Übersetzt {@code ae2hud.<key>}; Platzhalter als Paare ({@code "used", 5, "max", 8}). */
    public String tr(String key, Object... params) {
        return TerminalTexts.tr(locale, "ae2hud." + key, params);
    }

    /** Übersetzter Itemname. */
    public String itemName(String itemId) {
        return TerminalTexts.itemName(locale, itemId);
    }

    /** Menge im AE2-Stil (9999, 10k, 12.5k, 1M); ab {@link #INFINITE_AE} „unbegrenzt“. */
    public String amount(double v) {
        if (v >= INFINITE_AE) {
            return tr("value.infinite");
        }
        return TerminalTexts.formatAmount(Math.max(0, (long) v));
    }

    /** AE/t mit einer Nachkommastelle in der Schreibweise der Sprache (12,5 bzw. 12.5). */
    public String rate(double aePerTick) {
        if (aePerTick >= 10_000) {
            return amount(aePerTick);
        }
        return String.format(numberLocale, "%.1f", Math.max(0, aePerTick));
    }

    /** Ganzzahliger Prozentwert, abgerundet (99,9 % bleibt 99 %). */
    public static int percent(double fraction) {
        if (Double.isNaN(fraction) || fraction <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.min(1, fraction) * 100);
    }

    /**
     * Kanalpflichtige Geräte im Netz (eine CPU zählt einmal, wie AE2s Multiblock-Kanal). Gezählt wird nur, wenn sich
     * Netz, Topologie oder Größe geändert haben – sonst kommt der Wert aus dem Zwischenspeicher.
     */
    public int devicesNeedingChannels(Grid grid) {
        long topology = nets == null ? 0 : nets.topologyVersion();
        int size = grid.size();
        if (grid == countedGrid && topology == countedTopology && size == countedSize) {
            return countedDevices;
        }
        int devices = 0;
        for (GridNode n : grid.getNodes()) {
            if (!n.hasFlag(GridFlag.REQUIRE_CHANNEL)) {
                continue;
            }
            if (n.hasFlag(GridFlag.MULTIBLOCK) && n.getOwner() instanceof CraftingUnitLogic unit) {
                CraftingCpuCluster cluster = unit.getCluster();
                if (cluster != null && !cluster.isDestroyed() && cluster.getCore() != unit) {
                    continue;
                }
            }
            devices++;
        }
        countedGrid = grid;
        countedTopology = topology;
        countedSize = size;
        countedDevices = devices;
        return devices;
    }
}
