/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.networktool.NetworkStatusMenu, appeng.client.gui.me.networktool.NetworkStatusScreen).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.tools.NetworkStatusReport;
import de.fellurion.ae2.core.tools.NetworkStatusReport.MachineGroup;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.ui.TerminalTexts;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Netzwerkstatus des Network Tools: gespeicherte Energie, Einspeisung und Verbrauch je Tick, belegte Kanäle und die
 * Geräte des Netzes nach Art mit Anzahl und Grundverbrauch (AE2: NetworkStatusMenu).
 *
 * <p>Nur Anzeige. Die Seite frischt sich alle 0,8 s auf ({@link ToolPages}), zeichnet aber nur neu, wenn sich etwas
 * geändert hat, und schließt sich, wenn der Block weg ist, kein Netz mehr hat oder der Spieler das Netz nicht mehr
 * benutzen darf.
 */
final class NetworkStatusPage extends InteractiveCustomUIPage<NetworkStatusPage.Payload>
        implements ToolPages.Refreshable {

    private static final String PAGE = "Pages/AE2/NetworkStatus.ui";
    private static final String ROW = "Pages/AE2/NetworkStatusRow.ui";

    private final WorldNetworks nets;
    private final int x, y, z;
    private String shown = "";
    private boolean open = true;

    private NetworkStatusPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Öffnet den Status für den anvisierten Block; der Zugriff auf das Netz ist schon geprüft. */
    static void open(ItemUseContext ctx) {
        Vector3i pos = ctx.targetBlock();
        if (pos == null) {
            return;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(),
                new NetworkStatusPage(ctx.playerRef(), WorldNetworks.of(ctx.world()), pos.x, pos.y, pos.z));
    }

    /** Das Netz des Blocks, solange er noch eines hat. */
    @Nullable
    private Grid grid() {
        WorldNetworks.Node node = nets.node(x, y, z);
        GridNode g = node == null ? null : node.gridNode();
        return g == null ? null : g.getGrid();
    }

    /** Die Zahlen des Netzes; Geräte heißen wie das Item ihres Blocks. */
    static NetworkStatusReport report(WorldNetworks nets, Grid grid) {
        return NetworkStatusReport.fromGrid(grid, machine -> {
            long pos = machine.getPos();
            WorldNetworks.Node node = nets.node(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
            return node == null || node.item() == null ? null : node.item().id();
        });
    }

    private static String rate(double aePerTick) {
        return String.format(Locale.ROOT, "%.1f", aePerTick);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", TerminalTexts.tr(AE2Language.of(playerRef), "ae2tools.status.title"));
        ToolPages.track(this);
        Grid grid = grid();
        if (grid != null) {
            NetworkStatusReport report = report(nets, grid);
            shown = fingerprint(report);
            fill(cmd, report);
        }
    }

    /** Alles, was die Seite zeigt, als Text: ändert er sich nicht, wird nicht neu gezeichnet. */
    private static String fingerprint(NetworkStatusReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append((long) r.storedPower()).append('/').append((long) r.maxStoredPower()).append(';')
                .append(rate(r.averagePowerInjection())).append(';').append(rate(r.averagePowerUsage())).append(';')
                .append(r.channelsUsed()).append(';').append(rate(r.channelPower())).append(';');
        for (MachineGroup g : r.groupedMachines()) {
            sb.append(g.display()).append(g.missingChannel() ? '!' : ':').append(g.count()).append(':')
                    .append(rate(g.idlePowerUsage())).append(':').append(rate(g.powerGenerationCapacity())).append(';');
        }
        return sb.toString();
    }

    private void fill(UICommandBuilder cmd, NetworkStatusReport r) {
        String l = AE2Language.of(playerRef);
        cmd.set("#Energy.Text", TerminalTexts.tr(l, "ae2tools.status.energy",
                "stored", TerminalTexts.grouped((long) r.storedPower(), l),
                "max", TerminalTexts.grouped((long) r.maxStoredPower(), l)));
        cmd.set("#Input.Text", TerminalTexts.tr(l, "ae2tools.status.input", "rate", rate(r.averagePowerInjection())));
        cmd.set("#Usage.Text", TerminalTexts.tr(l, "ae2tools.status.usage", "rate", rate(r.averagePowerUsage())));
        cmd.set("#Channels.Text", TerminalTexts.tr(l, "ae2tools.status.channels",
                "channels", Integer.toString(r.channelsUsed()), "rate", rate(r.channelPower())));
        List<MachineGroup> groups = r.groupedMachines();
        cmd.set("#MachinesHeader.Text", TerminalTexts.tr(l, "ae2tools.status.machines",
                "count", Integer.toString(groups.size())));
        cmd.clear("#Machines");
        String missing = TerminalTexts.tr(l, "ae2tools.status.row.missingChannel");
        for (int i = 0; i < groups.size(); i++) {
            MachineGroup g = groups.get(i);
            String sel = "#Machines[" + i + "]";
            cmd.append("#Machines", ROW);
            boolean known = Item.getAssetMap().getAsset(g.display()) != null;
            cmd.set(sel + " #Icon.Visible", known);
            if (known) {
                cmd.set(sel + " #Icon.ItemId", g.display());
            }
            cmd.set(sel + " #Name.Text", TerminalTexts.tr(l, "ae2tools.status.row.name",
                    "count", Integer.toString(g.count()), "name", TerminalTexts.itemName(l, g.display())));
            cmd.set(sel + " #Detail.Text", g.powerGenerationCapacity() > 0
                    ? TerminalTexts.tr(l, "ae2tools.status.row.generates", "rate", rate(g.idlePowerUsage()),
                    "generation", rate(g.powerGenerationCapacity()))
                    : TerminalTexts.tr(l, "ae2tools.status.row.idle", "rate", rate(g.idlePowerUsage())));
            cmd.set(sel + " #NoChannel.Text", g.missingChannel() ? missing : "");
        }
    }

    @Override
    public World world() {
        return nets.world();
    }

    /** Vom Takt der Welt: neu zeichnen, wenn sich etwas geändert hat; schließen, wenn es nichts mehr zu zeigen gibt. */
    @Override
    public boolean refreshTick() {
        if (!open || !playerRef.isValid()) {
            return false;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        if (store.getExternalData().getWorld() != nets.world()) {
            return false; // Spieler hat die Welt gewechselt
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager().getCustomPage() != this) {
            open = false;
            return false; // inzwischen ist eine andere Seite offen: dorthin darf kein Update dieser Seite gehen
        }
        Grid grid = grid();
        if (grid == null || !AE2Security.canUse(nets, x, y, z, playerRef.getUuid())) {
            open = false;
            close(); // Block abgebaut, ohne Netz, oder Zugriff entzogen
            return false;
        }
        NetworkStatusReport report = report(nets, grid);
        String now = fingerprint(report);
        if (!now.equals(shown)) {
            shown = now;
            UICommandBuilder cmd = new UICommandBuilder();
            fill(cmd, report);
            sendUpdate(cmd, false);
        }
        return true;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        // reine Anzeige: keine Ereignisse gebunden
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        open = false;
        ToolPages.untrack(this);
        super.onDismiss(ref, store);
    }

    /** Die Seite bindet keine Ereignisse; der Codec ist nur der Form halber da. */
    public static final class Payload {
        String action;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .build();
    }
}
