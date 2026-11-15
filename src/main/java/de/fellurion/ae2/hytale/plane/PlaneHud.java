/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.integration.modules.igtooltip.parts.AnnihilationPlaneDataProvider).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import de.fellurion.ae2.core.automation.FormationPlaneLogic;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

/** Blickanzeige der Planes: Netzzustand, was vor ihnen steht und – bei der Formation Plane – ihre Einstellungen. */
final class PlaneHud {

    private PlaneHud() {
    }

    static void annihilation(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof AnnihilationPlaneDevice plane) || plane.target() == null) {
            return;
        }
        String lang = ctx.locale();
        if (plane.logic().isContinuouslyGenerating()) {
            out.line(TerminalTexts.tr(lang, "ae2plane.hud.front"), TerminalTexts.tr(lang, "ae2plane.hud.skyDust"),
                    HudColors.INFO);
            return;
        }
        out.line(TerminalTexts.tr(lang, "ae2plane.hud.front"), frontName(lang, plane.target()));
    }

    static void formation(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof FormationPlaneDevice plane)) {
            return;
        }
        String lang = ctx.locale();
        FormationPlaneLogic logic = plane.logic();
        if (plane.target() != null) {
            out.line(TerminalTexts.tr(lang, "ae2plane.hud.front"), frontName(lang, plane.target()));
        }
        out.line(TerminalTexts.tr(lang, "ae2plane.hud.mode"),
                TerminalTexts.tr(lang, logic.getPlaceBlock() == YesNo.YES ? "ae2plane.hud.place" : "ae2plane.hud.drop"));
        int slots = logic.availableSlots();
        int used = 0;
        for (int i = 0; i < slots; i++) {
            if (logic.getConfig().getKey(i) != null) {
                used++;
            }
        }
        String filter = used == 0 ? TerminalTexts.tr(lang, "ae2plane.hud.filterEmpty")
                : TerminalTexts.tr(lang, logic.isInverted() ? "ae2plane.hud.filterInverted" : "ae2plane.hud.filterUsed",
                "used", used, "slots", slots);
        out.line(TerminalTexts.tr(lang, "ae2plane.hud.filter"), filter);
        out.line(TerminalTexts.tr(lang, "ae2plane.hud.priority"), Integer.toString(logic.getPriority()));
    }

    private static void status(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g != null) {
            NetworkStatus s = NetworkStatus.of(g);
            out.status(ctx.tr(s.key()), s.color());
        }
    }

    private static String frontName(String lang, PlaneTarget target) {
        int block = target.blockId();
        if (block == PlaneTarget.NOT_LOADED) {
            return TerminalTexts.tr(lang, "ae2plane.front.unloaded");
        }
        return blockName(lang, block);
    }

    /** Anzeigename eines Blocks (über sein Item), „nichts“ für Luft. */
    static String blockName(String lang, int blockId) {
        BlockType type = blockId <= 0 ? null : BlockType.getAssetMap().getAsset(blockId);
        if (type == null || type == BlockType.EMPTY) {
            return TerminalTexts.tr(lang, "ae2plane.front.none");
        }
        Item item = type.getItem();
        return item == null ? type.getId() : TerminalTexts.itemName(lang, item.getId());
    }
}
