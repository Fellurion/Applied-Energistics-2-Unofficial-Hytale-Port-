/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.crafting.CraftAmountMenu / CraftConfirmMenu).
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.crafting.CalculationStrategy;
import de.fellurion.ae2.core.crafting.CraftingPlan;
import de.fellurion.ae2.core.crafting.CraftingSubmitResult;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.TerminalPage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/** Craft-Anfrage: Menge wählen → berechnen (benötigte/fehlende Items, Bytes) → auf freier CPU starten. */
public final class CraftRequestPage extends InteractiveCustomUIPage<CraftRequestPage.Payload> {

    private static final String PAGE = "Pages/AE2/CraftRequest.ui";
    private static final String INGREDIENT = "Pages/AE2/IngredientEntry.ui";
    private static final int MAX_SHOWN = 80;

    private final WorldNetworks nets;
    private final int x, y, z;
    private final AEItemKey what;
    private long amount = 1;
    @Nullable
    private CraftingPlan plan;
    private Message info = Message.raw("");

    CraftRequestPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z, AEItemKey what) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
        this.what = what;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", Message.translation("ae2craft.request.title"));
        cmd.set("#Calculate.Text", Message.translation("ae2craft.request.calculate"));
        cmd.set("#Start.Text", Message.translation("ae2craft.request.start"));
        for (String a : new String[]{"Minus64", "Minus10", "Minus1", "Plus1", "Plus10", "Plus64", "Calculate", "Start"}) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#" + a, EventData.of("Action", a), false);
        }
        fill(cmd);
    }

    private void fill(UICommandBuilder cmd) {
        cmd.set("#TargetIcon.ItemId", what.itemId());
        var item = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(what.itemId());
        cmd.set("#TargetName.Text", Message.translation(item != null && item.getTranslationKey() != null
                ? item.getTranslationKey() : what.itemId()));
        cmd.set("#Amount.Text", Message.translation("ae2craft.request.amount").param("amount", Long.toString(amount)));
        cmd.set("#Start.Visible", plan != null && !plan.simulation());
        cmd.clear("#Plan");
        if (plan == null) {
            cmd.set("#PlanInfo.Text", info);
            cmd.set("#MissingHeader.Text", "");
            return;
        }
        cmd.set("#PlanInfo.Text", Message.translation("ae2craft.request.planInfo")
                .param("bytes", TerminalPage.formatAmount(plan.bytes()))
                .param("output", TerminalPage.formatAmount(plan.finalOutput().amount())));
        KeyCounter shown = plan.simulation() ? plan.missingItems() : plan.usedItems();
        cmd.set("#MissingHeader.Text", Message.translation(plan.simulation() ? "ae2craft.request.missing" : "ae2craft.request.used"));
        int i = 0;
        for (KeyCounter.Entry e : shown) {
            if (i >= MAX_SHOWN || !(e.getKey() instanceof AEItemKey k)) {
                continue;
            }
            String sel = "#Plan[" + i + "]";
            cmd.append("#Plan", INGREDIENT);
            cmd.set(sel + " #Icon.ItemId", k.itemId());
            cmd.set(sel + " #Quantity.Text", TerminalPage.formatAmount(e.getLongValue()));
            i++;
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (!de.fellurion.ae2.hytale.security.AE2Security.checkUse(nets, x, y, z, playerRef)) {
            close(); // Zugriff entzogen, während die Seite offen war
            return;
        }
        if (p.action == null) {
            return;
        }
        switch (p.action) {
            case "Minus64" -> changeAmount(-64);
            case "Minus10" -> changeAmount(-10);
            case "Minus1" -> changeAmount(-1);
            case "Plus1" -> changeAmount(1);
            case "Plus10" -> changeAmount(10);
            case "Plus64" -> changeAmount(64);
            case "Calculate" -> calculate();
            case "Start" -> {
                if (start()) {
                    close();
                    return;
                }
            }
            default -> {
            }
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        sendUpdate(cmd, false);
    }

    private void changeAmount(long delta) {
        amount = Math.max(1, Math.min(Integer.MAX_VALUE, amount + delta));
        plan = null;
        info = Message.raw("");
    }

    private IActionSource source() {
        return IActionSource.ofPlayer(playerRef.getUuid());
    }

    private void calculate() {
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        if (grid == null || !CraftingAccess.active(nets, x, y, z)) {
            plan = null;
            info = Message.translation("ae2craft.request.offline");
            return;
        }
        plan = grid.getCraftingService().calculate(source(), what, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        if (plan == null) {
            info = Message.translation("ae2craft.request.notCraftable");
        }
    }

    private boolean start() {
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        if (grid == null || plan == null || plan.simulation()) {
            return false;
        }
        CraftingSubmitResult result = grid.getCraftingService().submitJob(plan, null, null, true, source());
        if (result.successful()) {
            playerRef.sendMessage(Message.translation("ae2craft.request.started")
                    .param("amount", TerminalPage.formatAmount(plan.finalOutput().amount())));
            return true;
        }
        info = Message.translation("ae2craft.request.error." + result.errorCode().name().toLowerCase(Locale.ROOT));
        plan = null;
        return false;
    }

    public static final class Payload {
        String action;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .build();
    }
}
