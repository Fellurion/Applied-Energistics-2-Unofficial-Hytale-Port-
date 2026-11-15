/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.crafting.CraftingAccess;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.CrankDevice;
import de.fellurion.ae2.hytale.ui.CellWorkbenchPage;
import de.fellurion.ae2.hytale.ui.ChestTerminalBackend;
import de.fellurion.ae2.hytale.ui.DeviceConfigPage;
import de.fellurion.ae2.hytale.ui.NetworkTerminalBackend;
import de.fellurion.ae2.hytale.ui.PriorityPage;
import de.fellurion.ae2.hytale.ui.TerminalOpener;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Was beim Benutzen eines AE2-Blocks passiert – je {@code Kind}. */
public final class UseHandlers {

    private static final Map<String, Consumer<UseContext>> HANDLERS = new ConcurrentHashMap<>();
    private static final List<Predicate<UseContext>> INTERCEPTORS = new CopyOnWriteArrayList<>();

    private UseHandlers() {
    }

    /**
     * Meldet an, was „Benutzen“ bei einer Block-Art tut (einmal beim Plugin-Start). Der Zugriff auf das Netz ist
     * beim Aufruf schon geprüft. Angemeldete Arten haben Vorrang vor den fest eingebauten.
     */
    public static void register(String kind, Consumer<UseContext> handler) {
        if (HANDLERS.putIfAbsent(kind, handler) != null) {
            throw new IllegalStateException("AE2: use handler for kind '" + kind + "' registered twice");
        }
    }

    /**
     * Läuft vor jedem Benutzen eines AE2-Blocks (Zugriff schon geprüft), etwa für ein bestimmtes Item in der Hand
     * ({@code ctx.itemInHand()}). Liefert er true, ist das Benutzen damit erledigt.
     */
    public static void addInterceptor(Predicate<UseContext> interceptor) {
        INTERCEPTORS.add(interceptor);
    }

    /** Terminals mit Blick ins Netz; das Crafting-Terminal zeigt zusätzlich das Herstellen. */
    private static final Set<String> TERMINAL_KINDS = Set.of("terminal", "crafting_terminal");

    /** Speicher mit eigenem Slot-Fenster, deren Priorität über „geduckt benutzen“ eingestellt wird. */
    private static final Set<String> PRIORITY_KINDS = Set.of("drive", "me_chest");

    static void handle(UseContext ctx) {
        // Nur Besitzer und eingetragene Spieler dürfen ein ME-Netz benutzen (Zugriff im Terminal)
        if (!de.fellurion.ae2.hytale.security.AE2Security.checkUse(ctx)) {
            return;
        }
        String kind = ctx.block().kind();
        for (Predicate<UseContext> interceptor : INTERCEPTORS) {
            if (interceptor.test(ctx)) {
                return;
            }
        }
        Consumer<UseContext> registered = HANDLERS.get(kind);
        if (registered != null) {
            registered.accept(ctx);
            return;
        }
        if (TERMINAL_KINDS.contains(kind)) {
            openTerminal(ctx, "ae2.items.AE2_" + titleCase(kind) + ".name", "crafting_terminal".equals(kind));
            return;
        }
        // Laufwerk / ME-Truhe geduckt benutzen: Priorität einstellen (AE2: Prioritäts-Knopf im Fenster des Blocks)
        if (PRIORITY_KINDS.contains(kind) && isCrouching(ctx) && PriorityPage.open(ctx)) {
            return;
        }
        // Geräte mit eigenem Slot-Fenster (Maschinen, Pattern Provider …) geduckt benutzen: Upgrade-Karten und
        // Einstellungen (AE2: Kartenplätze und Knöpfe im Fenster des Blocks) – für jedes Gerät, das ui.ConfigHost ist
        if (isCrouching(ctx) && DeviceConfigPage.openOnCrouch(ctx)) {
            return;
        }
        switch (kind) {
            case "me_chest" -> useMeChest(ctx);
            case "import_bus", "export_bus", "storage_bus", "interface" ->
                    // Filter, Upgrade-Karten und Einstellungen (AE2: IOBusMenu, StorageBusMenu, InterfaceMenu). Im Tick
                    // des Platzierens ist das Gerät noch nicht angemeldet – dann passiert nichts.
                    DeviceConfigPage.open(ctx);
            case "pattern_encoding_terminal" -> CraftingAccess.openPatternEncoding(ctx.player(), ctx.playerRef(),
                    ctx.playerEntity(), ctx.entityStore(), WorldNetworks.of(ctx.world()), ctx.pos().x, ctx.pos().y, ctx.pos().z);
            case "pattern_access_terminal" -> CraftingAccess.openPatternAccess(ctx.player(), ctx.playerRef(),
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
                    // Blöcke mit Slots (Drive, Inscriber, Charger, ...): Hytales Container-Fenster;
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
        if (chest != null && swapHeldCell(ctx, chest, StorageCells::isCellItem)) {
            return;
        }
        ItemStack cell = chest == null ? null : chest.getItemStack((short) 0);
        if (cell == null || cell.isEmpty()) {
            AE2UseBlockInteraction.openContainerWindow(ctx);
        } else {
            // Das Terminal der Truhe zeigt wie in AE2 nur ihre eigene Zelle, nicht das ganze Netz
            TerminalOpener.open(ctx, "ae2.items.AE2_ME_Chest.name", new ChestTerminalBackend(nets, ctx.pos().x,
                    ctx.pos().y, ctx.pos().z, ctx.playerRef().getUuid()), false);
        }
    }

    /**
     * Zellen-Werkbank: Zelle in der Hand wird eingelegt/getauscht; liegt eine Zelle drin, öffnet sich die
     * Konfiguration (Partition, Creative-Inhalt, Upgrades), sonst das Slot-Fenster. Dasselbe gilt für Items mit
     * Kartenplätzen, die keine Zellen sind (Wireless Terminal, Matter Cannon, Color Applicator): bei ihnen zeigt die
     * Seite nur die Upgrade-Karten. Geduckt benutzen öffnet immer das Slot-Fenster – so kommt das Item wieder heraus,
     * ohne ein anderes dagegen zu tauschen.
     */
    private static void useCellWorkbench(UseContext ctx) {
        ItemContainer bench = BlockAccess.container(ctx.world(), ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (bench == null) {
            return;
        }
        if (isCrouching(ctx)) {
            AE2UseBlockInteraction.openContainerWindow(ctx);
            return;
        }
        if (swapHeldCell(ctx, bench, CellWorkbenchPage::accepts)) {
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

    /**
     * Legt eine gehaltene Zelle in Slot 0 von {@code target} (tauscht eine vorhandene in die Hand).
     *
     * @param accepted welche Items der Block annimmt (Item-Id)
     */
    private static boolean swapHeldCell(UseContext ctx, ItemContainer target, Predicate<String> accepted) {
        ItemStack held = ctx.itemInHand();
        if (held == null || held.isEmpty() || !accepted.test(held.getItemId())) {
            return false;
        }
        InventoryComponent.Hotbar hotbar = ctx.entityStore().getComponent(ctx.playerEntity(),
                InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return false;
        }
        short handSlot = hotbar.getActiveSlot();
        if (handSlot < 0 || handSlot >= hotbar.getInventory().getCapacity()) {
            return false;
        }
        // Getauscht wird mit dem aktiven Feld der Schnellleiste. Die gehaltene Zelle kann aber auch aus einem anderen
        // Abschnitt stammen (Zweithand, Werkzeugleiste): dann entstünde in der Truhe eine Kopie, und das Item im
        // aktiven Feld ginge verloren. Also nur tauschen, wenn dort wirklich diese Zelle liegt.
        ItemStack active = hotbar.getInventory().getItemStack(handSlot);
        if (ItemStack.isEmpty(active) || !(active == held
                || (active.isStackableWith(held) && active.getQuantity() == held.getQuantity()))) {
            return false;
        }
        ItemStack inside = target.getItemStack((short) 0);
        if (!target.setItemStackForSlot((short) 0, active).succeeded()) {
            return false; // Slot nimmt die Zelle nicht an: nichts anfassen
        }
        if (inside != null && !inside.isEmpty()) {
            hotbar.getInventory().setItemStackForSlot(handSlot, inside);
        } else {
            hotbar.getInventory().removeItemStackFromSlot(handSlot);
        }
        return true;
    }

    public static void openTerminal(UseContext ctx, String titleKey) {
        openTerminal(ctx, titleKey, false);
    }

    /** @param crafting true = ME-Crafting-Terminal (Netzinhalt plus Herstellen aus dem Netz) */
    public static void openTerminal(UseContext ctx, String titleKey, boolean crafting) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        NetworkTerminalBackend backend = new NetworkTerminalBackend(nets, ctx.pos().x, ctx.pos().y, ctx.pos().z,
                ctx.playerRef().getUuid());
        // Mit FTCore: Terminal-Reiter in der Schale (Inventar daneben, Drag & Drop), sonst eigene Seite
        TerminalOpener.open(ctx, titleKey, backend, crafting);
    }


    /** Duckt sich der Spieler gerade? */
    public static boolean isCrouching(UseContext ctx) {
        try {
            MovementStatesComponent states = ctx.entityStore().getComponent(ctx.playerEntity(),
                    MovementStatesComponent.getComponentType());
            return states != null && states.getMovementStates() != null && states.getMovementStates().crouching;
        } catch (RuntimeException e) {
            return false;
        }
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
