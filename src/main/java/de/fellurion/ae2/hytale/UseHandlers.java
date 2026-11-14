/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.crafting.CraftingAccess;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CrankDevice;
import de.fellurion.ae2.hytale.ui.CellWorkbenchPage;
import de.fellurion.ae2.hytale.ui.NetworkTerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalOpener;

import java.util.Set;

/** Was beim Benutzen eines AE2-Blocks passiert – je {@code Kind}. */
public final class UseHandlers {

    private UseHandlers() {
    }

    private static final Set<String> TERMINAL_KINDS = Set.of("terminal", "crafting_terminal",
            "pattern_access_terminal");

    static void handle(UseContext ctx) {
        // Nur Besitzer und eingetragene Spieler dürfen ein ME-Netz benutzen (Zugriff im Terminal)
        if (!de.fellurion.ae2.hytale.security.AE2Security.checkUse(ctx)) {
            return;
        }
        String kind = ctx.block().kind();
        if (TERMINAL_KINDS.contains(kind)) {
            openTerminal(ctx, "ae2.items.AE2_" + titleCase(kind) + ".name");
            return;
        }
        switch (kind) {
            case "me_chest" -> useMeChest(ctx);
            case "pattern_encoding_terminal" -> CraftingAccess.openPatternEncoding(ctx.player(), ctx.playerRef(),
                    ctx.playerEntity(), ctx.entityStore(), WorldNetworks.of(ctx.world()), ctx.pos().x, ctx.pos().y, ctx.pos().z);
            case "crafting_unit", "crafting_accelerator", "crafting_storage", "crafting_monitor" ->
                    CraftingAccess.openStatus(ctx.player(), ctx.playerRef(), ctx.playerEntity(), ctx.entityStore(),
                            WorldNetworks.of(ctx.world()), ctx.pos().x, ctx.pos().y, ctx.pos().z);
            case "cell_workbench" -> useCellWorkbench(ctx);
            case "crank" -> {
                WorldNetworks nets = WorldNetworks.of(ctx.world());
                WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
                if (node != null && node.device() instanceof CrankDevice crank && !crank.turn(nets, node)) {
                    ctx.playerRef().sendMessage(Message.translation("ae2ui.crank.noTarget"));
                }
            }
            case "quartz_glass" -> {
                // Glas hat keine Aktion
            }
            case "controller", "energy_cell", "dense_energy_cell", "creative_energy_cell", "energy_acceptor", "cable" -> {
                // Netz-Infos zeigt die Blickanzeige (hud.AE2Hud) beim Anschauen – nicht mehr im Chat
            }
            default ->
                    // Blöcke mit Slots (Drive, Inscriber, Charger, Interface, ...): Hytales Container-Fenster;
                    // ohne Slots gibt es nichts zu öffnen (Infos stehen in der Blickanzeige)
                    AE2UseBlockInteraction.openContainerWindow(ctx);
        }
    }

    /**
     * ME Chest: mit einer Zelle in der Hand wird sie eingelegt (bzw. getauscht), ohne Zelle öffnet sich
     * das Terminal – ist keine Zelle drin, das Slot-Fenster.
     */
    private static void useMeChest(UseContext ctx) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        ItemContainer chest = node != null && node.device() instanceof CellHostDevice d ? d.container() : null;
        if (chest != null && swapHeldCell(ctx, chest)) {
            return;
        }
        ItemStack cell = chest == null ? null : chest.getItemStack((short) 0);
        if (cell == null || cell.isEmpty()) {
            AE2UseBlockInteraction.openContainerWindow(ctx);
        } else {
            openTerminal(ctx, "ae2.items.AE2_ME_Chest.name");
        }
    }

    /**
     * Zellen-Werkbank: Zelle in der Hand wird eingelegt/getauscht; liegt eine Zelle drin, öffnet sich die
     * Konfiguration (Partition, Creative-Inhalt, Upgrades), sonst das Slot-Fenster.
     */
    private static void useCellWorkbench(UseContext ctx) {
        ItemContainer bench = BlockAccess.container(ctx.world(), ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (bench == null) {
            return;
        }
        if (swapHeldCell(ctx, bench)) {
            return;
        }
        ItemStack cell = bench.getItemStack((short) 0);
        if (cell == null || cell.isEmpty()) {
            AE2UseBlockInteraction.openContainerWindow(ctx);
        } else {
            ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(),
                    new CellWorkbenchPage(ctx.playerRef(), ctx.world(), ctx.pos().x, ctx.pos().y, ctx.pos().z));
        }
    }

    /** Legt eine gehaltene Zelle in Slot 0 von {@code target} (tauscht eine vorhandene in die Hand). */
    private static boolean swapHeldCell(UseContext ctx, ItemContainer target) {
        ItemStack held = ctx.itemInHand();
        if (held == null || held.isEmpty() || !StorageCells.isCellItem(held.getItemId())) {
            return false;
        }
        InventoryComponent.Hotbar hotbar = ctx.entityStore().getComponent(ctx.playerEntity(),
                InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return false;
        }
        short handSlot = hotbar.getActiveSlot();
        ItemStack inside = target.getItemStack((short) 0);
        target.setItemStackForSlot((short) 0, held);
        if (inside != null && !inside.isEmpty()) {
            hotbar.getInventory().setItemStackForSlot(handSlot, inside);
        } else {
            hotbar.getInventory().removeItemStackFromSlot(handSlot);
        }
        return true;
    }

    public static void openTerminal(UseContext ctx, String titleKey) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        NetworkTerminalBackend backend = new NetworkTerminalBackend(nets, ctx.pos().x, ctx.pos().y, ctx.pos().z,
                ctx.playerRef().getUuid());
        // Mit FTCore: Terminal-Reiter in der Schale (Inventar daneben, Drag & Drop), sonst eigene Seite
        TerminalOpener.open(ctx, titleKey, backend);
    }


    /** "crafting_terminal" -> "Crafting_Terminal". */
    private static String titleCase(String kind) {
        StringBuilder sb = new StringBuilder();
        for (String part : kind.split("_")) {
            if (!sb.isEmpty()) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
