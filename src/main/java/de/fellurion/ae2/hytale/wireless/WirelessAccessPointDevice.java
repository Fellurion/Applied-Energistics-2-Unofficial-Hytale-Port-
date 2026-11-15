/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.networking.WirelessAccessPointBlockEntity, appeng.menu.implementations.WirelessAccessPointMenu).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.wireless.WirelessAccessPointLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * ME Wireless Access Point: Die Wireless Booster im Slot des Blocks bestimmen Reichweite und Energieverbrauch
 * (Formeln in {@link WirelessAccessPointLogic}); aktiv ist er, solange sein Netzknoten versorgt ist und einen Kanal hat.
 *
 * <p>Gezählt werden die Booster nur, wenn sich der Slot ändert – der Tick des Geräts tut nichts.
 *
 * <p>Abweichung von AE2: Die beiden Verknüpfungs-Slots des AE2-Fensters gibt es nicht; ein Wireless Terminal wird
 * verknüpft, indem man den Access Point mit dem Terminal in der Hand anklickt bzw. benutzt
 * ({@link WirelessTerminals#link}).
 */
public final class WirelessAccessPointDevice implements Device {

    public static final String KIND = "wireless_access_point";
    public static final String BOOSTER = "AE2_Wireless_Booster";
    /** Schlüssel in der Block-Komponente: Kennung, an der verknüpfte Terminals diesen Access Point wiedererkennen. */
    private static final String LINK_ID = "LinkId";

    private final WirelessAccessPointLogic logic = new WirelessAccessPointLogic();
    @Nullable
    private ItemContainer container;
    @Nullable
    private EventRegistration<?, ?> changeReg;

    public WirelessAccessPointLogic logic() {
        return logic;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container != null) {
            // In den Slot passen nur Wireless Booster (AE2: AEItemDefinitionFilter(WIRELESS_BOOSTER))
            for (short s = 0; s < container.getCapacity(); s++) {
                container.setSlotFilter(FilterActionType.ADD, s,
                        (action, c, slot, stack) -> ItemStack.isEmpty(stack) || BOOSTER.equals(stack.getItemId()));
            }
            changeReg = container.registerChangeEvent(e -> countBoosters());
        }
        countBoosters();
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (changeReg != null) {
            try {
                changeReg.unregister();
            } catch (RuntimeException ignored) {
                // Container schon weg
            }
            changeReg = null;
        }
        container = null;
    }

    /** Hytale → Kern: Anzahl der Booster im Slot (AE2: {@code getBoosters()}). */
    private void countBoosters() {
        int n = 0;
        ItemContainer c = container;
        if (c != null && c.getCapacity() > 0) {
            ItemStack stack = c.getItemStack((short) 0);
            if (!ItemStack.isEmpty(stack) && BOOSTER.equals(stack.getItemId())) {
                n = stack.getQuantity();
            }
        }
        logic.setBoosters(n);
    }

    // ------------------------------------------------------------------ Verknüpfung

    /** Kennung dieses Access Points, oder null, solange noch kein Terminal mit ihm verknüpft wurde. */
    @Nullable
    public static String linkId(WorldNetworks.Node node) {
        String id = node.comp.config().get(LINK_ID);
        return id == null || id.isBlank() ? null : id;
    }

    /** Kennung dieses Access Points; beim ersten Verknüpfen wird sie angelegt und mit dem Block gespeichert. */
    static String ensureLinkId(WorldNetworks.Node node) {
        String id = linkId(node);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            node.comp.config().put(LINK_ID, id);
            node.markDirty();
        }
        return id;
    }

    // ------------------------------------------------------------------ Blickanzeige

    /** Zustand, Reichweite, Booster und Verbrauch (AE2-Fenster: „Range: … m“, „Energy Usage: …“). */
    static void describe(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (node.gridNode() != null) {
            NetworkStatus s = NetworkStatus.of(node.gridNode());
            out.status(ctx.tr(s.key()), s.color());
        }
        if (!(node.device() instanceof WirelessAccessPointDevice device)) {
            return;
        }
        WirelessAccessPointLogic logic = device.logic;
        String l = ctx.locale();
        out.line(TerminalTexts.tr(l, "ae2wireless.hud.range"),
                TerminalTexts.tr(l, "ae2wireless.hud.rangeValue", "range", ctx.rate(logic.getRange())));
        out.line(TerminalTexts.tr(l, "ae2wireless.hud.boosters"), Integer.toString(logic.getBoosters()),
                logic.getBoosters() > 0 ? HudColors.GOOD : HudColors.TEXT);
        out.line(TerminalTexts.tr(l, "ae2wireless.hud.usage"),
                TerminalTexts.tr(l, "ae2wireless.hud.usageValue", "usage", ctx.rate(logic.getIdlePowerUsage())));
    }
}
