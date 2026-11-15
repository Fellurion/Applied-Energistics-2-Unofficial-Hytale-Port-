/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.QuartzKnifeMenu, appeng.client.gui.implementations.QuartzKnifeScreen,
 * appeng.items.tools.quartz.QuartzCuttingKnifeItem).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.tools.NamePressNames;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Seite des Schneidmessers: einen Namen eingeben und aus einem Metallbarren eine Namenspresse schneiden (AE2:
 * QuartzKnifeMenu – Barren in den Eingang, Presse aus dem Ausgang nehmen, das Messer nutzt sich um 1 ab).
 *
 * <p>Eine eigene Seite hat keine Slots zum Hineinlegen; der Barren kommt deshalb direkt aus dem Inventar. Als
 * Metallbarren gelten die Entsprechungen von AE2s {@code metal_ingots}, die es in Hytale gibt: Eisen, Kupfer und
 * Gold – genommen wird der erste in dieser Reihenfolge, die Seite zeigt vorher, welcher es ist.
 *
 * <p>Ablauf je Klick, alles auf dem Server: Name bereinigen ({@link NamePressNames#clean}), Messer am gemerkten
 * Platz prüfen, einen Barren mit Vergleich entnehmen, das Messer abnutzen, die Presse geben (was nicht ins Inventar
 * passt, fällt vor die Füße). Ein Barren wird nur verbraucht, wenn dafür eine Presse entsteht.
 */
final class KnifePage extends InteractiveCustomUIPage<KnifePage.Payload> {

    private static final String PAGE = "Pages/AE2/QuartzKnife.ui";
    /** AE2s {@code ae2:metal_ingots} (Kupfer, Eisen, Gold …) auf Hytale-Items abgebildet; Reihenfolge = Vorrang. */
    static final List<String> METAL_BARS = List.of("Ingredient_Bar_Iron", "Ingredient_Bar_Copper", "Ingredient_Bar_Gold");

    /** Ein Barrenstapel im Inventar. */
    private record Bar(ItemContainer container, short slot, ItemStack stack, int total) {
    }

    private final World world;
    private final ItemContainer knifeContainer;
    private final short knifeSlot;
    private final String knifeId;
    @Nullable
    private String status;
    private boolean statusError;

    private KnifePage(PlayerRef playerRef, World world, ItemContainer knifeContainer, short knifeSlot, String knifeId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.world = world;
        this.knifeContainer = knifeContainer;
        this.knifeSlot = knifeSlot;
        this.knifeId = knifeId;
    }

    /** Rechtsklick mit dem Messer: öffnet die Seite (AE2: use / useOn). */
    static void open(ItemUseContext ctx) {
        ItemContainer container = ctx.interaction().getHeldItemContainer();
        short slot = ctx.interaction().getHeldItemSlot();
        if (ctx.primary() || container == null || slot < 0 || slot >= container.getCapacity()
                || !ToolItems.isKnife(container.getItemStack(slot))) {
            return;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(), new KnifePage(
                ctx.playerRef(), ctx.world(), container, slot, container.getItemStack(slot).getItemId()));
    }

    private String lang() {
        return AE2Language.of(playerRef);
    }

    /** Das Messer, solange es noch an seinem Platz liegt. */
    @Nullable
    private ItemStack knife() {
        if (knifeSlot < 0 || knifeSlot >= knifeContainer.getCapacity()) {
            return null;
        }
        ItemStack stack = knifeContainer.getItemStack(knifeSlot);
        return !ItemStack.isEmpty(stack) && knifeId.equals(stack.getItemId()) ? stack : null;
    }

    /** Der Barren, der als Nächstes verbraucht würde: der erste Stapel der ersten vorhandenen Sorte, ohne Metadaten. */
    @Nullable
    private static Bar bar(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<ItemContainer> bags = Toolbox.bags(store, ref);
        for (String id : METAL_BARS) {
            Bar first = null;
            int total = 0;
            for (ItemContainer c : bags) {
                for (short s = 0; s < c.getCapacity(); s++) {
                    ItemStack stack = c.getItemStack(s);
                    if (ItemStack.isEmpty(stack) || !id.equals(stack.getItemId())) {
                        continue;
                    }
                    AEItemKey key = ItemKeys.of(stack);
                    if (key == null || key.nbt() != null) {
                        continue; // ein besonderer Barren (eigene Metadaten) wird nicht zerschnitten
                    }
                    total += stack.getQuantity();
                    if (first == null) {
                        first = new Bar(c, s, stack, 0);
                    }
                }
            }
            if (first != null) {
                return new Bar(first.container(), first.slot(), first.stack(), total);
            }
        }
        return null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        String l = lang();
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", TerminalTexts.tr(l, "ae2tools.knife.title"));
        cmd.set("#NameLabel.Text", TerminalTexts.tr(l, "ae2tools.knife.label"));
        cmd.set("#Make.Text", TerminalTexts.tr(l, "ae2tools.knife.make"));
        cmd.set("#Hint.Text", TerminalTexts.tr(l, "ae2tools.knife.hint"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Make",
                new EventData().append("Action", "Make").append(Payload.F_NAME, "#NameInput.Value"), false);
        fill(cmd, store, ref);
    }

    private void fill(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref) {
        String l = lang();
        Bar bar = bar(store, ref);
        cmd.set("#BarIcon.Visible", bar != null);
        if (bar != null) {
            cmd.set("#BarIcon.ItemId", bar.stack().getItemId());
        }
        cmd.set("#BarText.Text", bar == null ? TerminalTexts.tr(l, "ae2tools.knife.bar.none")
                : TerminalTexts.tr(l, "ae2tools.knife.bar.uses", "item", TerminalTexts.itemName(l, bar.stack().getItemId()),
                "count", Integer.toString(bar.total())));
        boolean error = status != null && statusError;
        cmd.set("#Status.Text", status == null || error ? "" : status);
        cmd.set("#Status.Visible", !error);
        cmd.set("#StatusError.Text", error ? status : "");
        cmd.set("#StatusError.Visible", error);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        ItemStack knife = knife();
        if (knife == null) {
            close(); // das Messer liegt nicht mehr dort, wo es beim Öffnen war
            return;
        }
        if (!"Make".equals(p.action)) {
            return;
        }
        String l = lang();
        make(store, ref, knife, NamePressNames.clean(p.name), l);
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd, store, ref);
        sendUpdate(cmd, false);
    }

    private void fail(String l, String key) {
        status = TerminalTexts.tr(l, key);
        statusError = true;
    }

    private void make(Store<EntityStore> store, Ref<EntityStore> ref, ItemStack knife, @Nullable String name, String l) {
        if (name == null) {
            fail(l, "ae2tools.knife.msg.noName");
            return;
        }
        if (knife.isBroken()) {
            fail(l, "ae2tools.knife.msg.blunt");
            return;
        }
        Bar bar = bar(store, ref);
        if (bar == null) {
            fail(l, "ae2tools.knife.bar.none");
            return;
        }
        ItemStack press;
        try {
            press = NamePresses.create(name);
        } catch (RuntimeException e) {
            fail(l, "ae2tools.knife.msg.failed"); // nichts verbraucht
            return;
        }
        if (!bar.container().removeItemStackFromSlot(bar.slot(), bar.stack(), 1, true, false).succeeded()) {
            fail(l, "ae2tools.knife.msg.failed"); // Inventar hat sich inzwischen geändert: nichts verbraucht
            return;
        }
        // Der Barren ist verbraucht: ab hier entsteht in jedem Fall die Presse.
        // AE2: das Messer verliert je Presse 1 Haltbarkeit (nicht im Creative-Modus)
        if (!knife.isUnbreakable() && ItemUtils.canDecreaseItemStackDurability(ref, store)) {
            ItemUtils.updateItemStackDurability(ref, knife, knifeContainer, knifeSlot, -1, store);
        }
        PlayerItems.give(store, ref, press, world, null);
        status = TerminalTexts.tr(l, "ae2tools.knife.msg.made", "name", name);
        statusError = false;
    }

    // ------------------------------------------------------------------ Event-Daten

    public static final class Payload {
        static final String F_NAME = "@Name";

        String action;
        String name;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>(F_NAME, Codec.STRING), (o, v) -> o.name = v, o -> o.name).add()
                .build();
    }
}
