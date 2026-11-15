/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.storage.SkyStoneTankBlockEntity, appeng.block.storage.SkyStoneTankBlock).
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import de.fellurion.ae2.core.automation.ExternalStorage;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.machines.SkyStoneTankLogic;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.StorageTarget;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;

/**
 * Sky Stone Tank: fasst 16 Eimer einer Flüssigkeit (Kern: {@link SkyStoneTankLogic}). Der Inhalt steht in der
 * Block-Komponente ({@code Data}).
 *
 * <p><b>Benutzen</b> (AE2: {@code FluidUtil.interactWithFluidHandler}): mit einem gefüllten Behälter in der Hand
 * wird er in den Tank geleert, mit einem leeren aus dem Tank gefüllt – immer ein ganzer Behälter oder nichts. Ohne
 * Behälter sagt der Tank, was er enthält. Der Tank hat wie in AE2 kein Fenster.
 *
 * <p><b>Automation:</b> Der Tank ist kein Netzblock. Busse und Fluid-P2P-Tunnel, die auf ihn zeigen, bekommen sein
 * Fluidlager ({@link StorageTarget}): Der Storage-Bus hängt es ins Netz, Import- und Export-Bus bewegen je
 * Arbeitsgang 125 mB (mit Beschleunigungskarten mehr).
 *
 * <p>Wie in AE2 geht der Inhalt verloren, wenn der Tank abgebaut wird („Does not retain its contents when picked
 * up“); es läuft nichts in die Welt aus. Der Block hat keine Füllstands-Zustände – den Füllstand zeigt die
 * Blickanzeige.
 */
public final class TankDevice implements Device, StorageTarget {

    public static final String KIND = "sky_stone_tank";

    private final SkyStoneTankLogic logic = new SkyStoneTankLogic();
    private boolean saveRequested;
    private final FluidTransfers.Endpoint endpoint = new FluidTransfers.Endpoint() {
        @Override
        public long insert(AEFluidKey what, long amount, boolean simulate) {
            return logic.insert(what, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE);
        }

        @Override
        public long extract(AEFluidKey what, long amount, boolean simulate) {
            return logic.extract(what, amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE);
        }
    };

    /** Inhalt und Fassungsvermögen (Kern). */
    public SkyStoneTankLogic logic() {
        return logic;
    }

    @Override
    public ExternalStorage busStorage(Dir side) {
        return logic.getStorage();
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        try {
            logic.loadState(node.comp.data());
        } catch (RuntimeException ignored) {
            // kaputter Zustand: leerer Tank
        }
        logic.setChangeListener(() -> saveRequested = true);
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            saveRequested = false;
            save(node);
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (!broken) {
            save(node); // Chunk wird entladen: ausstehende Änderung noch speichern
        }
    }

    private void save(WorldNetworks.Node node) {
        String json = logic.isEmpty() ? null : logic.saveState();
        if (json == null ? node.comp.data() != null : !json.equals(node.comp.data())) {
            node.comp.setData(json);
            node.markDirty();
        }
    }

    // ------------------------------------------------------------------ Benutzen

    /** Der Tank an der benutzten Stelle, oder null (im Tick des Platzierens ist er noch nicht angemeldet). */
    @Nullable
    private static TankDevice at(UseContext ctx) {
        WorldNetworks.Node node = WorldNetworks.of(ctx.world()).node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        return node != null && node.device() instanceof TankDevice tank ? tank : null;
    }

    /** „Benutzen“ am Tank: Behälter in der Hand leeren bzw. füllen, sonst den Inhalt nennen. */
    static void use(UseContext ctx) {
        TankDevice tank = at(ctx);
        if (tank == null) {
            return;
        }
        SkyStoneTankLogic logic = tank.logic;
        ItemStack held = ctx.itemInHand();
        FluidContainers.Content content = FluidContainers.contentOf(held);
        AEFluidKey stored = logic.getFluid();
        boolean emptyContainer = content == null && !ItemStack.isEmpty(held)
                && FluidContainers.isContainerItem(held.getItemId());
        if (content == null && !emptyContainer) {
            tell(ctx, tank.contentMessage(), false);
            return;
        }
        // Umgefüllt wird im aktiven Feld der Schnellleiste – nur, wenn dort wirklich das gehaltene Item liegt
        // (es kann auch aus der Zweithand stammen; dann bliebe das Feld der Schnellleiste unberührt)
        InventoryComponent.Hotbar hotbar = ctx.entityStore().getComponent(ctx.playerEntity(),
                InventoryComponent.Hotbar.getComponentType());
        ItemContainer bar = hotbar == null ? null : hotbar.getInventory();
        short slot = hotbar == null ? -1 : hotbar.getActiveSlot();
        ItemStack active = bar == null || slot < 0 || slot >= bar.getCapacity() ? null : bar.getItemStack(slot);
        if (ItemStack.isEmpty(active) || !(active == held
                || (active.isStackableWith(held) && active.getQuantity() == held.getQuantity()))) {
            tell(ctx, Message.translation("ae2fluid.tank.mainHand"), true);
            return;
        }
        FluidTransfers.Result result;
        if (content != null) {
            result = FluidTransfers.empty(bar, slot, tank.endpoint, rest -> give(ctx, rest));
        } else if (stored == null) {
            tell(ctx, Message.translation("ae2fluid.tank.empty"), true);
            return;
        } else if (FluidContainers.emptyFor(active, stored.fluidId()) == null) {
            // z. B. Lava: dafür gibt es im Spiel keinen Behälter
            tell(ctx, Message.translation("ae2fluid.tank.noContainer")
                    .param("fluid", FluidTypes.nameMessage(stored.fluidId())), true);
            return;
        } else {
            result = FluidTransfers.fill(bar, slot, stored, tank.endpoint, rest -> give(ctx, rest));
        }
        switch (result) {
            case DONE -> tell(ctx, tank.contentMessage(), false);
            case NO_ROOM -> tell(ctx, stored != null && content != null && !stored.equals(content.fluid())
                    ? Message.translation("ae2fluid.tank.otherFluid").param("fluid", FluidTypes.nameMessage(stored.fluidId()))
                    : Message.translation("ae2fluid.tank.full"), true);
            case NOT_ENOUGH -> tell(ctx, Message.translation("ae2fluid.tank.notEnough"), true);
            default -> {
                // nichts passiert (Feld hat sich geändert)
            }
        }
    }

    /** „Wasser: 12 / 16 B“ bzw. „leer“. */
    private Message contentMessage() {
        AEFluidKey stored = logic.getFluid();
        if (stored == null) {
            return Message.translation("ae2fluid.tank.empty");
        }
        return Message.translation("ae2fluid.tank.content").param("fluid", FluidTypes.nameMessage(stored.fluidId()))
                .param("amount", FluidText.of(logic.getAmount(), logic.getCapacity(), null));
    }

    private static void tell(UseContext ctx, Message message, boolean warning) {
        FluidText.tell(ctx.playerRef(), message, warning);
    }

    /** Ins Inventar, sonst vor die Füße (über den Zugriff der laufenden Interaktion). */
    private static void give(UseContext ctx, ItemStack stack) {
        SimpleItemContainer.addOrDropItemStack(ctx.commandBuffer(), ctx.playerEntity(),
                InventoryComponent.getCombined(ctx.commandBuffer(), ctx.playerEntity(), InventoryComponent.HOTBAR_FIRST),
                stack);
    }

    // ------------------------------------------------------------------ Blickanzeige

    /** Flüssigkeit, Menge und Füllbalken. */
    static void describe(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (!(node.device() instanceof TankDevice tank)) {
            return;
        }
        String l = ctx.locale();
        SkyStoneTankLogic logic = tank.logic;
        AEFluidKey stored = logic.getFluid();
        out.line(TerminalTexts.tr(l, "ae2fluid.hud.fluid"), stored == null ? TerminalTexts.tr(l, "ae2fluid.hud.none")
                : FluidTypes.name(l, stored.fluidId()), stored == null ? HudColors.MUTED : HudColors.TEXT);
        out.line(TerminalTexts.tr(l, "ae2fluid.hud.amount"), FluidText.of(logic.getAmount(), logic.getCapacity(), l));
        out.bar(logic.getFillFraction(), HudColors.INFO);
        out.line(new LookInfo.Span(TerminalTexts.tr(l, "ae2fluid.hud.hint"), HudColors.MUTED));
    }
}
