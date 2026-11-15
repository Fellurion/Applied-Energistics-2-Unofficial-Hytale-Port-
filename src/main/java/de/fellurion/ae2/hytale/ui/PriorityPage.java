/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.PriorityMenu / appeng.client.gui.implementations.PriorityScreen).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.tools.PlayerItems;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Priorität eines ME-Laufwerks bzw. einer ME-Truhe. AE2 hat dafür einen Knopf im Fenster des Blocks; dessen Fenster
 * ist hier Hytales Slot-Fenster ohne eigene Knöpfe, deshalb öffnet sich die Seite, wenn der Spieler den Block
 * <b>geduckt</b> benutzt.
 *
 * <p>Wie in AE2: eingelagert wird zuerst in den Speicher mit der höchsten Priorität, entnommen zuerst aus dem mit
 * der niedrigsten. Der Wert steht in der Block-Komponente ({@code Priority}) und gilt sofort.
 *
 * <p>Nur bei der ME-Truhe gibt es zusätzlich „Zelle entnehmen“: Benutzen öffnet dort, solange eine Zelle drin ist,
 * das Terminal der Truhe und nie das Slot-Fenster – die Zelle käme sonst nur im Tausch gegen eine andere oder durch
 * Abbauen wieder heraus (AE2: der Zellenplatz im Fenster der Truhe). Die Zelle geht ins Inventar des Spielers, was
 * nicht passt vor seine Füße; danach schließt sich die Seite.
 */
public final class PriorityPage extends InteractiveCustomUIPage<PriorityPage.Payload> {

    private static final String PAGE = "Pages/AE2/Priority.ui";
    private static final int[] STEPS = {-100, -10, -1, 1, 10, 100};
    private static final String[] BUTTONS = {"PrioM100", "PrioM10", "PrioM1", "PrioP1", "PrioP10", "PrioP100"};
    /** Block-Art, bei der die Seite „Zelle entnehmen“ anbietet. */
    private static final String ME_CHEST = "me_chest";

    private final WorldNetworks nets;
    private final int x, y, z;
    private final String kind;

    private PriorityPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z, String kind) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
        this.kind = kind;
    }

    /** Öffnet die Seite für ein benutztes Laufwerk / eine ME-Truhe. @return false, wenn der Block keines ist */
    public static boolean open(UseContext ctx) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (node == null || !(node.device() instanceof CellHostDevice)) {
            return false;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(),
                new PriorityPage(ctx.playerRef(), nets, ctx.pos().x, ctx.pos().y, ctx.pos().z, node.kind()));
        return true;
    }

    /** Der Block, solange dort noch dasselbe Gerät steht. */
    @Nullable
    private WorldNetworks.Node node() {
        WorldNetworks.Node n = nets.node(x, y, z);
        return n != null && kind.equals(n.kind()) && n.device() instanceof CellHostDevice ? n : null;
    }

    private static int priority(WorldNetworks.Node node) {
        return ((CellHostDevice) node.device()).logic().getPriority();
    }

    private static void setPriority(WorldNetworks.Node node, int value) {
        ((CellHostDevice) node.device()).logic().setPriority(value);
        node.comp.setPriority(value);
        node.markDirty();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        String l = AE2Language.of(playerRef);
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", TerminalTexts.tr(l, "ae2bus.priority.title"));
        cmd.set("#PrioSet.Text", TerminalTexts.tr(l, "ae2bus.priority.set"));
        cmd.set("#Hint.Text", TerminalTexts.tr(l, "ae2bus.priority.hint"));
        for (int i = 0; i < BUTTONS.length; i++) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#" + BUTTONS[i],
                    new EventData().append("Action", "Priority").append("Index", Integer.toString(i)), false);
        }
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#PrioSet",
                new EventData().append("Action", "PrioritySet").append("@Value", "#PrioValue.Value"), false);
        cmd.set("#TakeCell.Text", TerminalTexts.tr(l, "ae2bus.priority.takeCell"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#TakeCell",
                new EventData().append("Action", "TakeCell"), false);
        WorldNetworks.Node node = node();
        if (node != null) {
            String itemId = node.item() != null ? node.item().id() : "AE2_Drive";
            cmd.set("#Icon.ItemId", itemId);
            cmd.set("#Name.Text", TerminalTexts.itemName(l, itemId));
            fill(cmd, node, l);
        }
    }

    private static void fill(UICommandBuilder cmd, WorldNetworks.Node node, String l) {
        int priority = priority(node);
        cmd.set("#PriorityLabel.Text", TerminalTexts.tr(l, "ae2bus.priority.value", "value", Integer.toString(priority)));
        cmd.set("#PrioValue.Value", (double) priority);
        cmd.set("#TakeCell.Visible", hasCell(node));
    }

    /** ME-Truhe mit einer Zelle im Platz? Nur dann gibt es etwas zu entnehmen. */
    private static boolean hasCell(WorldNetworks.Node node) {
        if (!ME_CHEST.equals(node.kind())) {
            return false;
        }
        ItemContainer chest = ((CellHostDevice) node.device()).container();
        return chest != null && chest.getCapacity() > 0 && !ItemStack.isEmpty(chest.getItemStack((short) 0));
    }

    /**
     * ME-Truhe: nimmt die Zelle aus dem Platz und gibt sie dem Spieler. Der Platz wird geleert und der Stapel
     * übergeben, ohne dass dazwischen etwas anderes läuft (Welt-Thread); liegt nichts (mehr) im Platz, geschieht
     * nichts.
     *
     * @return true, wenn eine Zelle entnommen wurde
     */
    private boolean takeCell(Ref<EntityStore> ref, Store<EntityStore> store, WorldNetworks.Node node) {
        if (!ME_CHEST.equals(node.kind())) {
            return false;
        }
        ItemStack cell = ((CellHostDevice) node.device()).takeCell(0);
        if (ItemStack.isEmpty(cell)) {
            return false;
        }
        // ins Inventar, der Rest vor die Füße, notfalls an die Truhe – der Stapel landet genau einmal
        PlayerItems.give(store, ref, cell, nets.world(), new Vector3i(x, y, z));
        return true;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (!AE2Security.checkUse(nets, x, y, z, playerRef)) {
            close(); // Zugriff entzogen, während die Seite offen war
            return;
        }
        WorldNetworks.Node node = node();
        if (node == null) {
            close(); // Block abgebaut oder ersetzt
            return;
        }
        if (p.action == null) {
            return;
        }
        int current = priority(node);
        switch (p.action) {
            case "Priority" -> {
                int i = parse(p.index);
                if (i >= 0 && i < STEPS.length) {
                    setPriority(node, ConfigEdits.addPriority(current, STEPS[i]));
                }
            }
            case "PrioritySet" -> setPriority(node, ConfigEdits.toPriority(p.value, current));
            case "TakeCell" -> {
                if (takeCell(ref, store, node)) {
                    close();
                    return;
                }
            }
            default -> {
            }
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd, node, AE2Language.of(playerRef));
        sendUpdate(cmd, false);
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static final class Payload {
        String action;
        String index;
        Double value;

        /** Das Zahlenfeld schickt der Client als Zahl. */
        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (o, v) -> o.index = v, o -> o.index).add()
                .append(new KeyedCodec<>("@Value", Codec.DOUBLE), (o, v) -> o.value = v, o -> o.value).add()
                .build();
    }
}
