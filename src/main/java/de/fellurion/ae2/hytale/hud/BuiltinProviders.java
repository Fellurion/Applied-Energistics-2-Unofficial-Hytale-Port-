/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.integration.modules.igtooltip.* – Jade/TOP-Provider für Kabel, Knoten, Energie, Drives, CPUs).
 */
package de.fellurion.ae2.hytale.hud;

import de.fellurion.ae2.core.cells.BasicCellInventory;
import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.cells.CellState;
import de.fellurion.ae2.core.cells.CreativeCellInventory;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.crafting.CraftingCpuCluster;
import de.fellurion.ae2.core.crafting.CraftingJobStatus;
import de.fellurion.ae2.core.crafting.CraftingUnitLogic;
import de.fellurion.ae2.core.crafting.MolecularAssemblerLogic;
import de.fellurion.ae2.core.crafting.PatternProviderLogic;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.EnergyService;
import de.fellurion.ae2.core.energy.IAEPowerStorage;
import de.fellurion.ae2.core.grid.ControllerState;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.machines.ChargerLogic;
import de.fellurion.ae2.core.machines.InscriberLogic;
import de.fellurion.ae2.core.machines.VibrationChamberLogic;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CraftingDevices;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.GlassDevice;
import de.fellurion.ae2.hytale.hud.LookInfo.Span;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Die mitgelieferten {@link InfoProvider}: Kabel, Controller, Energiezellen, Drive/ME Chest, Maschinen, Crafting. */
final class BuiltinProviders {

    /** Netzblock ohne eigenen Provider: Name + Zustand. */
    static final InfoProvider GENERIC = BuiltinProviders::generic;

    private BuiltinProviders() {
    }

    static void registerAll() {
        InfoProviders.register(BuiltinProviders::cable, "cable");
        InfoProviders.register(BuiltinProviders::controller, "controller");
        InfoProviders.register(BuiltinProviders::energyStorage, "energy_cell", "dense_energy_cell",
                "creative_energy_cell");
        InfoProviders.register(BuiltinProviders::cellHost, "drive", "me_chest");
        InfoProviders.register(BuiltinProviders::machine, "inscriber", "charger", "vibration_chamber");
        InfoProviders.register(BuiltinProviders::assembler, "molecular_assembler");
        InfoProviders.register(BuiltinProviders::patternProvider, "pattern_provider");
        InfoProviders.register(BuiltinProviders::craftingUnit, "crafting_unit", "crafting_accelerator",
                "crafting_storage", "crafting_monitor");
        // Reine Deko: keine Anzeige (sonst steht sie bei jedem Blick durchs Fenster da)
        InfoProviders.register((ctx, node, out) -> out.suppress(), GlassDevice.KIND);
    }

    // ------------------------------------------------------------------ gemeinsam

    static void generic(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        crouchHint(ctx, node, out);
    }

    /**
     * Geräte, deren Benutzen ihr Slot-Fenster öffnet und die ihre Karten und Einstellungen geduckt zeigen
     * ({@link ConfigHost#opensOnCrouch}): sagt das als letzte Zeile – sofern noch eine frei ist.
     */
    static void crouchHint(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (node.device() instanceof ConfigHost host && host.opensOnCrouch()) {
            out.line(muted(TerminalTexts.tr(ctx.locale(), host.upgradeCards() != null ? "ae2bus.hud.crouch"
                    : "ae2bus.hud.crouchSettings")));
        }
    }

    /** Zustandszeile für Netzblöcke; ohne Netzknoten (noch nicht angelegt / kein Netzblock) keine. */
    static void status(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g != null) {
            NetworkStatus s = NetworkStatus.of(g);
            out.status(ctx.tr(s.key()), s.color());
        }
    }

    private static Span muted(String text) {
        return new Span(text, HudColors.MUTED);
    }

    private static Span value(String text, String color) {
        return new Span(text, color);
    }

    @Nullable
    private static Object owner(WorldNetworks.Node node) {
        Device d = node.device();
        return d == null ? null : d.owner();
    }

    // ------------------------------------------------------------------ Kabel

    /** Kanäle (belegt / Kapazität), bei Smart- und Dense-Kabeln je Seite. */
    static void cable(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g == null) {
            return;
        }
        status(ctx, node, out);
        int used = g.getUsedChannels();
        int max = g.getMaxChannels();
        boolean infinite = max == Integer.MAX_VALUE;
        boolean full = !infinite && max > 0 && used >= max;
        out.line(ctx.tr("label.channels"), used + " / " + (infinite ? ctx.tr("value.infinite") : String.valueOf(max)),
                full ? HudColors.WARN : HudColors.TEXT);
        String type = node.comp.cableType();
        if (type != null && (type.contains("smart") || type.contains("dense"))) {
            Map<Direction, Integer> sides = g.getChannelsPerSide();
            if (!sides.isEmpty()) {
                List<Span> spans = new ArrayList<>(2 + sides.size() * 3);
                spans.add(muted(ctx.tr("label.sides") + "  "));
                boolean first = true;
                for (Direction d : Direction.values()) {
                    Integer c = sides.get(d);
                    if (c == null) {
                        continue;
                    }
                    if (!first) {
                        spans.add(muted("  ·  "));
                    }
                    first = false;
                    spans.add(muted(ctx.tr("side." + d.name().toLowerCase(Locale.ROOT)) + " "));
                    spans.add(value(String.valueOf(c), HudColors.TEXT));
                }
                out.line(spans);
            }
        }
        if (!infinite && max > 0) {
            out.bar(used / (double) max, full ? HudColors.WARN : HudColors.ACCENT);
        }
    }

    // ------------------------------------------------------------------ Controller

    /** Netzübersicht: versorgte Geräte, Größe, Energie, Verbrauch. */
    static void controller(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        Grid grid = g == null ? null : g.getGrid();
        if (grid == null) {
            status(ctx, node, out);
            return;
        }
        if (grid.getPathingService().getControllerState() == ControllerState.CONTROLLER_CONFLICT) {
            out.status(ctx.tr(NetworkStatus.CONFLICT.key()), NetworkStatus.CONFLICT.color());
        } else if (!g.isPowered()) {
            out.status(ctx.tr(NetworkStatus.NO_POWER.key()), NetworkStatus.NO_POWER.color());
        } else {
            out.status(ctx.tr(NetworkStatus.ONLINE.key()), NetworkStatus.ONLINE.color());
        }
        int used = grid.getPathingService().getUsedChannels();
        int devices = ctx.devicesNeedingChannels(grid);
        out.line(ctx.tr("label.channels"), ctx.tr("value.channelsOfDevices", "used", used, "devices", devices),
                used < devices ? HudColors.WARN : HudColors.TEXT);
        out.line(ctx.tr("label.network"), ctx.tr("value.blocks", "count", grid.size()));
        networkEnergy(ctx, grid, out, "label.energy", true);
        EnergyService e = grid.getEnergyService();
        out.line(muted(ctx.tr("label.usage") + "  "),
                value(ctx.tr("value.rate", "rate", ctx.rate(e.getAvgPowerUsage())), HudColors.TEXT),
                muted("  ·  " + ctx.tr("label.input") + "  "),
                value(ctx.tr("value.rate", "rate", ctx.rate(e.getAvgPowerInjection())), HudColors.TEXT));
    }

    /** Netzenergie (gespeichert / maximal); mit Balken, wenn {@code withBar}. */
    private static void networkEnergy(HudContext ctx, Grid grid, LookInfo.Builder out, String labelKey,
                                      boolean withBar) {
        EnergyService e = grid.getEnergyService();
        double max = e.getMaxStoredPower();
        if (max >= HudContext.INFINITE_AE) {
            out.line(ctx.tr(labelKey), ctx.tr("value.infinite"), HudColors.GOOD);
            return;
        }
        double stored = e.getStoredPower();
        double fraction = max > 0 ? stored / max : 0;
        out.line(ctx.tr(labelKey), ctx.tr("value.energyPercent", "stored", ctx.amount(stored), "max", ctx.amount(max),
                "percent", HudContext.percent(fraction)));
        if (withBar && max > 0) {
            out.bar(fraction, energyColor(fraction));
        }
    }

    private static String energyColor(double fraction) {
        return fraction < 0.1 ? HudColors.WARN : HudColors.ACCENT;
    }

    // ------------------------------------------------------------------ Energiezellen

    /** Ladung der Zelle (mit Balken) und Energie des ganzen Netzes. */
    static void energyStorage(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        GridNode g = node.gridNode();
        Object owner = g != null ? g.getOwner() : owner(node);
        if (owner instanceof IAEPowerStorage cell) {
            double max = cell.getAEMaxPower();
            if (max >= HudContext.INFINITE_AE) {
                out.line(ctx.tr("label.stored"), ctx.tr("value.infinite"), HudColors.GOOD);
            } else if (max > 0) {
                double stored = cell.getAECurrentPower();
                double fraction = stored / max;
                out.line(ctx.tr("label.stored"), ctx.tr("value.energyPercent", "stored", ctx.amount(stored),
                        "max", ctx.amount(max), "percent", HudContext.percent(fraction)));
                out.bar(fraction, energyColor(fraction));
            }
        }
        Grid grid = g == null ? null : g.getGrid();
        if (grid != null) {
            networkEnergy(ctx, grid, out, "label.network", false);
        }
    }

    // ------------------------------------------------------------------ Drive / ME Chest

    /** Je Zelle Bytes und Typen (AE2-Tooltip), Farbe wie die Drive-LED; Balken = Bytes aller Zellen. */
    static void cellHost(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof CellHostDevice host)) {
            return;
        }
        CellHostLogic logic = host.logic();
        int slots = logic.getSlots();
        List<StorageCell> cells = new ArrayList<>(slots);
        long used = 0;
        long total = 0;
        for (int i = 0; i < slots; i++) {
            StorageCell c = logic.getCell(i);
            if (c == null) {
                continue;
            }
            cells.add(c);
            if (c instanceof BasicCellInventory b) {
                used += b.getUsedBytes();
                total += b.getTotalBytes();
            }
        }
        if (cells.isEmpty()) {
            out.line(muted(ctx.tr("cell.none")));
            return;
        }
        if (slots > 1) {
            out.line(ctx.tr("label.cells"), ctx.tr("value.cellsSummary", "count", cells.size(), "slots", slots,
                    "used", ctx.amount(used), "total", ctx.amount(total)));
        }
        if (cells.size() <= out.freeLines()) {
            for (StorageCell c : cells) {
                out.line(cellSpans(ctx, c, false));
            }
        } else {
            // Mehr Zellen als Zeilen: zwei je Zeile in Kurzform
            for (int i = 0; i < cells.size(); i += 2) {
                List<Span> spans = cellSpans(ctx, cells.get(i), true);
                if (i + 1 < cells.size()) {
                    spans.add(muted("    "));
                    spans.addAll(cellSpans(ctx, cells.get(i + 1), true));
                }
                out.line(spans);
            }
        }
        if (total > 0) {
            double fraction = used / (double) total;
            out.bar(fraction, fraction >= 1 ? HudColors.BAD : HudColors.ACCENT);
        }
    }

    private static List<Span> cellSpans(HudContext ctx, StorageCell cell, boolean compact) {
        List<Span> spans = new ArrayList<>(5);
        String color = cellColor(cell.getStatus());
        if (cell instanceof BasicCellInventory b) {
            CellDefinition def = b.getCellType();
            String size = def.tier().namePrefix();
            if (def.keyType() == AEKeyType.FLUIDS) {
                size = size + " " + ctx.tr("cell.fluid");
            }
            spans.add(muted(size + (compact ? " " : "  ")));
            spans.add(value(ctx.tr(compact ? "value.cellShort" : "value.cell",
                    "used", ctx.amount(b.getUsedBytes()), "total", ctx.amount(b.getTotalBytes()),
                    "types", b.getStoredItemTypes(), "maxTypes", b.getTotalItemTypes()), color));
        } else if (cell instanceof CreativeCellInventory) {
            spans.add(muted(ctx.tr("cell.creative") + (compact ? " " : "  ")));
            spans.add(value(ctx.tr("value.infinite"), HudColors.GOOD));
        } else {
            spans.add(value(ctx.tr("cell.other"), color));
        }
        return spans;
    }

    /** Farben der Drive-LEDs: leer grün, belegt blau, Typen voll orange, voll rot. */
    private static String cellColor(CellState state) {
        return switch (state) {
            case EMPTY -> HudColors.GOOD;
            case NOT_EMPTY -> HudColors.INFO;
            case TYPES_FULL -> HudColors.WARN;
            case FULL -> HudColors.BAD;
            case ABSENT -> HudColors.MUTED;
        };
    }

    // ------------------------------------------------------------------ Maschinen

    /** Inscriber (Fortschritt), Charger (lädt), Vibrationskammer (Erzeugung, Brennstoff). */
    static void machine(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        Object owner = owner(node);
        if (owner instanceof InscriberLogic l) {
            if (l.isWorking() && l.getMaxProcessingTime() > 0) {
                double f = l.getProcessingTime() / (double) l.getMaxProcessingTime();
                out.line(ctx.tr("label.progress"), ctx.tr("value.percent", "percent", HudContext.percent(f)),
                        HudColors.GOOD);
                out.bar(f, HudColors.ACCENT);
            } else {
                out.line(muted(ctx.tr("machine.idle")));
            }
        } else if (owner instanceof ChargerLogic l) {
            boolean working = l.isWorking();
            out.line(value(ctx.tr(working ? "machine.charging" : "machine.idle"),
                    working ? HudColors.GOOD : HudColors.MUTED));
        } else if (owner instanceof VibrationChamberLogic l) {
            if (l.isOn()) {
                out.line(ctx.tr("label.output"), ctx.tr("value.rate", "rate", ctx.rate(l.getCurrentEnergyRate())),
                        HudColors.GOOD);
                double perTick = l.getCurrentFuelTicksPerTick();
                if (perTick > 0) {
                    int seconds = (int) Math.ceil(l.getRemainingFuelTicks() / perTick / 20.0);
                    out.line(ctx.tr("label.fuel"), ctx.tr("value.seconds", "seconds", seconds));
                }
                if (l.getFuelItemFuelTicks() > 0) {
                    out.bar(l.getRemainingFuelTicks() / l.getFuelItemFuelTicks(), HudColors.ACCENT);
                }
            } else {
                out.line(muted(ctx.tr("machine.noFuel")));
            }
        }
        crouchHint(ctx, node, out);
    }

    /** Molecular Assembler: Fortschritt des aktuellen Rezepts. */
    static void assembler(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof CraftingDevices.MolecularAssembler ma)) {
            return;
        }
        MolecularAssemblerLogic l = ma.logic();
        if (l.isWorking()) {
            int p = Math.max(0, Math.min(100, l.getCraftingProgress()));
            out.line(ctx.tr("label.progress"), ctx.tr("value.percent", "percent", p), HudColors.GOOD);
            out.bar(p / 100.0, HudColors.ACCENT);
        } else {
            out.line(muted(ctx.tr("machine.idle")));
        }
        crouchHint(ctx, node, out);
    }

    /** Pattern Provider: Anzahl Muster, ob er gerade Zutaten ausliefert. */
    static void patternProvider(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof CraftingDevices.PatternProvider pp)) {
            return;
        }
        PatternProviderLogic l = pp.logic();
        int patterns = l.getAvailablePatterns().size();
        out.line(ctx.tr("label.patterns"), String.valueOf(patterns), patterns == 0 ? HudColors.MUTED : HudColors.TEXT);
        if (l.isBusy()) {
            out.line(value(ctx.tr("pattern.busy"), HudColors.INFO));
        }
        crouchHint(ctx, node, out);
    }

    // ------------------------------------------------------------------ Crafting-CPU

    /** CPU-Blöcke: Speicher/Co-Prozessoren der CPU und laufender Auftrag mit Fortschritt. */
    static void craftingUnit(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof CraftingDevices.CraftingUnit cu)) {
            return;
        }
        CraftingUnitLogic unit = cu.logic();
        CraftingCpuCluster cluster = unit.getCluster();
        if (cluster == null || cluster.isDestroyed()) {
            out.line(value(ctx.tr("cpu.notFormed"), HudColors.WARN));
            return;
        }
        out.line(ctx.tr("label.cpu"), ctx.tr("value.cpu", "bytes", ctx.amount(cluster.getAvailableStorage()),
                "threads", cluster.getCoProcessors()));
        CraftingJobStatus job = cluster.getJobStatus();
        if (job == null) {
            out.line(muted(ctx.tr("cpu.idle")));
            return;
        }
        String what = job.crafting().what() instanceof AEItemKey key ? ctx.itemName(key.itemId())
                : String.valueOf(job.crafting().what());
        boolean suspended = job.suspended();
        out.line(ctx.tr("label.job"), ctx.tr("value.job", "amount", ctx.amount(job.crafting().amount()), "item", what),
                suspended ? HudColors.WARN : HudColors.TEXT);
        double f = job.totalItems() > 0 ? job.progress() / (double) job.totalItems() : 0;
        String progress = ctx.tr("value.percent", "percent", HudContext.percent(f));
        if (suspended) {
            progress = progress + "  ·  " + ctx.tr("cpu.suspended");
        }
        out.line(ctx.tr("label.progress"), progress, suspended ? HudColors.WARN : HudColors.GOOD);
        out.bar(f, suspended ? HudColors.WARN : HudColors.ACCENT);
    }
}
