/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.crafting.CraftingCPUMenu / CraftingStatusMenu).
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
import de.fellurion.ae2.core.crafting.CraftingCpuCluster;
import de.fellurion.ae2.core.crafting.CraftingJobStatus;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.TerminalPage;

import javax.annotation.Nonnull;
import java.util.List;

/** Crafting-Status: alle CPUs des Netzes mit Auftrag, Fortschritt und „Abbrechen“; aktualisiert sich selbst. */
public final class CraftingStatusPage extends InteractiveCustomUIPage<CraftingStatusPage.Payload>
        implements CraftingAccess.Refreshable {

    private static final String PAGE = "Pages/AE2/CraftingStatus.ui";
    private static final String ROW = "Pages/AE2/CpuRow.ui";

    private final WorldNetworks nets;
    private final int x, y, z;
    private List<CraftingCpuCluster> cpus = List.of();
    private String shown = "";
    private int ticks;
    private boolean open = true;

    CraftingStatusPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", Message.translation("ae2craft.status.title"));
        CraftingAccess.track(this);
        fill(cmd, evt);
    }

    private String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (CraftingCpuCluster c : cpus) {
            CraftingJobStatus s = c.getJobStatus();
            sb.append(c.isActive()).append(s == null ? "-" : s.progress() + "/" + s.totalItems()).append(';');
        }
        return sb.toString();
    }

    private void fill(UICommandBuilder cmd, UIEventBuilder evt) {
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        cpus = grid == null ? List.of() : grid.getCraftingService().getCpus();
        shown = fingerprint();
        long busy = cpus.stream().filter(CraftingCpuCluster::isBusy).count();
        cmd.set("#Summary.Text", Message.translation(cpus.isEmpty() ? "ae2craft.status.noCpu" : "ae2craft.status.summary")
                .param("cpus", Integer.toString(cpus.size())).param("busy", Long.toString(busy)));
        cmd.clear("#Cpus");
        for (int i = 0; i < cpus.size(); i++) {
            CraftingCpuCluster cpu = cpus.get(i);
            String sel = "#Cpus[" + i + "]";
            cmd.append("#Cpus", ROW);
            cmd.set(sel + " #CpuName.Text", Message.translation("ae2craft.status.cpu")
                    .param("index", Integer.toString(i + 1))
                    .param("bytes", TerminalPage.formatAmount(cpu.getAvailableStorage()))
                    .param("threads", Integer.toString(cpu.getCoProcessors())));
            CraftingJobStatus job = cpu.getJobStatus();
            boolean hasJob = job != null && job.crafting() != null;
            cmd.set(sel + " #JobIcon.Visible", hasJob);
            cmd.set(sel + " #Cancel.Visible", hasJob);
            cmd.set(sel + " #Cancel.Text", Message.translation("ae2craft.status.cancel"));
            if (hasJob && job.crafting().what() instanceof AEItemKey k) {
                cmd.set(sel + " #JobIcon.ItemId", k.itemId());
                cmd.set(sel + " #JobText.Text", Message.translation(job.suspended() ? "ae2craft.status.suspended" : "ae2craft.status.job")
                        .param("amount", TerminalPage.formatAmount(job.crafting().amount()))
                        .param("progress", TerminalPage.formatAmount(job.progress()))
                        .param("total", TerminalPage.formatAmount(job.totalItems()))
                        .param("seconds", Long.toString(job.elapsedTimeNanos() / 1_000_000_000L)));
                evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Cancel",
                        new EventData().append("Action", "Cancel").append("Index", Integer.toString(i)), false);
            } else {
                cmd.set(sel + " #JobText.Text", Message.translation(cpu.isActive() ? "ae2craft.status.idle" : "ae2craft.status.offline"));
            }
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (!de.fellurion.ae2.hytale.security.AE2Security.checkUse(nets, x, y, z, playerRef)) {
            close(); // Zugriff entzogen, während die Seite offen war
            return;
        }
        if ("Cancel".equals(p.action) && p.index != null) {
            try {
                int i = Integer.parseInt(p.index);
                if (i >= 0 && i < cpus.size()) {
                    cpus.get(i).cancelJob();
                }
            } catch (NumberFormatException ignored) {
                // ungültig
            }
        }
        refresh();
    }

    private void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder evt = new UIEventBuilder();
        fill(cmd, evt);
        sendUpdate(cmd, evt, false);
    }

    /** Alle 10 Netz-Ticks: nur neu zeichnen, wenn sich Aufträge/Fortschritt geändert haben. */
    @Override
    public WorldNetworks networks() {
        return nets;
    }

    @Override
    public boolean refreshTick() {
        if (!open || !playerRef.isValid()) {
            return false;
        }
        if (++ticks % 10 != 0) {
            return true;
        }
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        cpus = grid == null ? List.of() : grid.getCraftingService().getCpus();
        if (!fingerprint().equals(shown)) {
            refresh();
        }
        return true;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        open = false;
        CraftingAccess.untrack(this);
        super.onDismiss(ref, store);
    }

    public static final class Payload {
        String action;
        String index;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (o, v) -> o.index = v, o -> o.index).add()
                .build();
    }
}
