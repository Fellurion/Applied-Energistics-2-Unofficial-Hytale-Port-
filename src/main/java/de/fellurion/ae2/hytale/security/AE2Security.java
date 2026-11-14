/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: AE2s Security Terminal – hier vereinfacht: Besitzer + Liste vertrauter Spieler).
 */
package de.fellurion.ae2.hytale.security;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.BlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import de.fellurion.ae2.hytale.AE2BlockComponent;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Besitz und Zugriffsschutz für ME-Netze – der einzige Einstieg für den Rest des Plugins.
 *
 * <ul>
 *   <li><b>Block-Besitzer:</b> wer den Block platziert hat ({@link BlockOwners}, {@link OwnerPlacedSystem}).</li>
 *   <li><b>Netz-Besitzer:</b> Besitzer des ältesten Blocks im Netz ({@link OwnerRanking}, {@link NetworkOwners});
 *       Blöcke ohne Netz gehören ihrem eigenen Besitzer. Ohne Besitzer darf jeder (Altbestand ohne Platzierer).</li>
 *   <li><b>Zugriffsliste</b> je Besitzer für alle seine Netze ({@link AccessStore}), verwaltet im Terminal
 *       (Knopf „Zugriff“, {@link AccessPage}).</li>
 *   <li>Admins ({@value #ADMIN_PERMISSION}) dürfen alles.</li>
 * </ul>
 *
 * <p>Abbauen ist bewusst nicht geschützt – dafür gibt es Schutz-Plugins für Gebiete.
 */
public final class AE2Security {

    public static final String ADMIN_PERMISSION = "ae2.admin";

    /** Meldung „gehört X“ höchstens so oft je Spieler (Dauerklicken soll nicht spammen). */
    static final long DENY_COOLDOWN_MS = 1500L;

    /** Blöcke ohne Aktion: kein Hinweis beim Anklicken. */
    private static final Set<String> FREE_KINDS = Set.of("quartz_glass");

    @Nullable
    private static volatile AccessStore access;
    @Nullable
    private static volatile KnownPlayers players;
    @Nullable
    private static volatile HytaleLogger logger;
    private static final Map<UUID, Long> LAST_DENY = new ConcurrentHashMap<>();

    private AE2Security() {
    }

    // ------------------------------------------------------------------ Einrichtung

    /**
     * Einmal beim Plugin-Start aufrufen (aus {@code HytaleBridge.setup}, nachdem die Komponente {@code AE2_Block}
     * registriert ist): Datenordner, Besitzer-Eintrag beim Platzieren, Namensspeicher.
     */
    public static void setup(JavaPlugin plugin) {
        HytaleLogger log = plugin.getLogger();
        logger = log;
        BiConsumer<String, Throwable> warn = (msg, e) -> log.at(Level.WARNING).withCause(e).log("%s", msg);
        java.nio.file.Path dir = plugin.getDataDirectory().resolve("access");
        players = new KnownPlayers(dir.resolve("players.json"), warn);
        access = new AccessStore(dir, warn);
        try {
            plugin.getChunkStoreRegistry().registerSystem(new OwnerPlacedSystem(AE2BlockComponent.getComponentType()));
        } catch (RuntimeException | LinkageError e) {
            // Ohne das System gilt jeder Block als Altbestand: Besitzer kommt beim ersten Bedarf aus PlacedBy (ohne Zeit)
            log.at(Level.WARNING).withCause(e).log("AE2: recording the owner on placement is not available");
        }
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, AE2Security::onPlayerReady);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, AE2Security::onPlayerDisconnect);
        log.at(Level.INFO).log("AE2: access protection enabled (lists in %s)", dir);
    }

    private static void onPlayerReady(PlayerReadyEvent event) {
        try {
            Ref<EntityStore> ref = event.getPlayerRef();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (ref.isValid()) {
                    remember(store.getComponent(ref, PlayerRef.getComponentType()));
                }
            });
        } catch (RuntimeException ignored) {
            // nur Namensspeicher
        }
    }

    private static void onPlayerDisconnect(PlayerDisconnectEvent event) {
        PlayerRef p = event.getPlayerRef();
        remember(p);
        LAST_DENY.remove(p.getUuid());
        KnownPlayers k = players;
        if (k != null) {
            k.flush();
        }
    }

    // ------------------------------------------------------------------ Prüfungen

    /**
     * Für {@code UseHandlers.handle}: darf der Spieler den benutzten AE2-Block verwenden? Wenn nicht, bekommt er
     * die Meldung „Dieses ME-Netz gehört X“ (Benachrichtigung, entprellt) und der Aufruf liefert false.
     */
    public static boolean checkUse(AE2UseBlockInteraction.UseContext ctx) {
        if (FREE_KINDS.contains(ctx.block().kind())) {
            return true;
        }
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        PlayerRef player = ctx.playerRef();
        remember(player);
        if (node != null) {
            return check(nets, node, player);
        }
        // Noch nicht angemeldet (gerade platziert/geladen): Besitzer direkt aus der Komponente
        BlockOwners.Info info = BlockOwners.read(ctx.block().config());
        Owner owner = info == null ? null : NetworkOwners.owner(info.owner(), info.name());
        if (allowed(owner, player.getUuid())) {
            return true;
        }
        deny(player, owner, false);
        return false;
    }

    /** Wie {@link #checkUse(AE2UseBlockInteraction.UseContext)}, für Block-Koordinaten (Terminals, Seiten). */
    public static boolean checkUse(WorldNetworks nets, int x, int y, int z, PlayerRef player) {
        WorldNetworks.Node node = nets.node(x, y, z);
        return node == null || check(nets, node, player);
    }

    private static boolean check(WorldNetworks nets, WorldNetworks.Node node, PlayerRef player) {
        Owner owner = NetworkOwners.of(nets, node);
        if (allowed(owner, player.getUuid())) {
            return true;
        }
        deny(player, owner, NetworkOwners.networked(node));
        return false;
    }

    /** Darf {@code player} den Block {@code node} bzw. sein ME-Netz benutzen? (ohne Meldung) */
    public static boolean canUse(WorldNetworks nets, WorldNetworks.Node node, PlayerRef player) {
        return canUse(nets, node, player.getUuid());
    }

    public static boolean canUse(WorldNetworks nets, WorldNetworks.Node node, UUID player) {
        return allowed(NetworkOwners.of(nets, node), player);
    }

    /** Wie {@link #canUse(WorldNetworks, WorldNetworks.Node, UUID)}; unbekannter Block = erlaubt (nichts zu schützen). */
    public static boolean canUse(WorldNetworks nets, int x, int y, int z, UUID player) {
        WorldNetworks.Node node = nets.node(x, y, z);
        return node == null || canUse(nets, node, player);
    }

    /**
     * Darf ein Gerät des Blocks {@code from} (Import-/Export-/Storage-Bus …) in den Block bei x/y/z greifen?
     * Fremde AE2-Blöcke (Laufwerk, ME-Truhe, Interface, Pattern Provider …) nur, wenn der Besitzer des Geräte-Netzes
     * dort eingetragen ist; Nicht-AE2-Blöcke und AE2-Blöcke ohne Besitzer immer (die schützen Gebietsschutz-Plugins).
     * Ein Netz ohne Besitzer greift nie in Blöcke mit Besitzer.
     */
    public static boolean deviceMayAccess(WorldNetworks nets, WorldNetworks.Node from, int x, int y, int z) {
        WorldNetworks.Node target = nets.node(x, y, z);
        if (target == null) {
            return true;
        }
        Owner targetOwner = NetworkOwners.of(nets, target);
        if (targetOwner == null) {
            return true;
        }
        Owner fromOwner = NetworkOwners.of(nets, from);
        if (fromOwner == null) {
            return false;
        }
        if (fromOwner.uuid().equals(targetOwner.uuid())) {
            return true;
        }
        AccessStore a = access;
        return a != null && a.isTrusted(targetOwner.uuid(), fromOwner.uuid());
    }

    /** Besitzer des Netzes (bzw. Blocks) bei x/y/z; null = niemand. */
    @Nullable
    public static Owner ownerAt(WorldNetworks nets, int x, int y, int z) {
        WorldNetworks.Node node = nets.node(x, y, z);
        return node == null ? null : NetworkOwners.of(nets, node);
    }

    /** Besitzer des Netzes (bzw. Blocks); null = niemand. */
    @Nullable
    public static Owner ownerOf(WorldNetworks nets, WorldNetworks.Node node) {
        return NetworkOwners.of(nets, node);
    }

    /** Text für die Ablehnung, z. B. „Dieses ME-Netz gehört Steve.“ (übersetzt beim Client). */
    public static Message denyMessage(WorldNetworks nets, WorldNetworks.Node node) {
        Owner o = NetworkOwners.of(nets, node);
        return Message.translation(NetworkOwners.networked(node) ? "ae2access.denied.network" : "ae2access.denied.block")
                .param("owner", o == null ? "?" : o.name());
    }

    /** Ablehnung als Text in der Sprache {@code locale} (für Terminals); null, wenn der Spieler darf. */
    @Nullable
    public static String deniedText(WorldNetworks nets, int x, int y, int z, UUID player, String locale) {
        WorldNetworks.Node node = nets.node(x, y, z);
        if (node == null) {
            return null;
        }
        Owner o = NetworkOwners.of(nets, node);
        if (allowed(o, player)) {
            return null;
        }
        return TerminalTexts.tr(locale, "ae2access.terminal.denied", "owner", o == null ? "?" : o.name());
    }

    /** Darf {@code actor} die Zugriffsliste von {@code owner} bearbeiten (er selbst oder Admin)? */
    public static boolean mayManage(UUID owner, UUID actor) {
        return owner.equals(actor) || isAdmin(actor);
    }

    /** Darf {@code actor} die Zugriffsliste des Netzes bei x/y/z bearbeiten? (Netz braucht einen Besitzer) */
    public static boolean canManage(WorldNetworks nets, int x, int y, int z, UUID actor) {
        Owner o = ownerAt(nets, x, y, z);
        return o != null && mayManage(o.uuid(), actor);
    }

    public static boolean isAdmin(UUID player) {
        try {
            PermissionsModule pm = PermissionsModule.get();
            return pm != null && pm.hasPermission(player, ADMIN_PERMISSION);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean allowed(@Nullable Owner owner, UUID player) {
        if (owner == null || owner.uuid().equals(player)) {
            return true;
        }
        AccessStore a = access;
        return (a != null && a.isTrusted(owner.uuid(), player)) || isAdmin(player);
    }

    // ------------------------------------------------------------------ Meldungen

    /** Benachrichtigung „Kein Zugriff – Dieses ME-Netz gehört X“, höchstens alle {@value #DENY_COOLDOWN_MS} ms. */
    static void deny(PlayerRef player, @Nullable Owner owner, boolean network) {
        long now = System.currentTimeMillis();
        Long last = LAST_DENY.get(player.getUuid());
        if (last != null && now - last < DENY_COOLDOWN_MS) {
            return;
        }
        LAST_DENY.put(player.getUuid(), now);
        Message detail = Message.translation(network ? "ae2access.denied.network" : "ae2access.denied.block")
                .param("owner", owner == null ? "?" : owner.name());
        notify(player, Message.translation("ae2access.denied.title"), detail, NotificationStyle.Danger);
    }

    static void notify(PlayerRef player, Message title, @Nullable Message detail, NotificationStyle style) {
        try {
            NotificationUtil.sendNotification(player.getPacketHandler(), title, detail, style);
        } catch (RuntimeException e) {
            // Benachrichtigung ging nicht (Verbindung weg) – dann eben ohne
        }
    }

    // ------------------------------------------------------------------ Zugriffsliste

    /**
     * Öffnet die Zugriffsverwaltung des Netzes bei x/y/z (nur Besitzer/Admin).
     *
     * @param locale Sprache der Texte (wie im Terminal)
     * @param back   öffnet das Terminal wieder (Knopf „Zurück“); null = kein Knopf
     * @return true, wenn die Seite offen ist
     */
    public static boolean openAccessPage(Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                         Store<EntityStore> store, WorldNetworks nets, int x, int y, int z,
                                         String locale, @Nullable AccessPage.Reopener back) {
        Owner owner = ownerAt(nets, x, y, z);
        if (owner == null) {
            notify(playerRef, Message.translation("ae2access.page.title"),
                    Message.translation("ae2access.noOwner"), NotificationStyle.Warning);
            return false;
        }
        if (!mayManage(owner.uuid(), playerRef.getUuid())) {
            deny(playerRef, owner, true);
            return false;
        }
        remember(playerRef);
        player.getPageManager().openCustomPage(ref, store, new AccessPage(playerRef, owner, locale, back));
        return true;
    }

    @Nullable
    static AccessStore store() {
        return access;
    }

    // ------------------------------------------------------------------ Namen

    /** Merkt sich Name/UUID (für Auswahlliste und Anzeige). */
    static void remember(@Nullable PlayerRef p) {
        KnownPlayers k = players;
        if (p == null || k == null) {
            return;
        }
        try {
            k.remember(p.getUuid(), p.getUsername(), System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // nur Komfort
        }
    }

    /** Aktueller Name: online, sonst aus dem Namensspeicher; null, wenn unbekannt. */
    @Nullable
    public static String knownName(UUID uuid) {
        try {
            Universe u = Universe.get();
            PlayerRef p = u == null ? null : u.getPlayer(uuid);
            if (p != null) {
                return p.getUsername();
            }
        } catch (RuntimeException ignored) {
            // Universe noch nicht bereit
        }
        KnownPlayers k = players;
        return k == null ? null : k.name(uuid);
    }

    static List<PlayerSearch.Candidate> onlinePlayers() {
        List<PlayerSearch.Candidate> out = new ArrayList<>();
        try {
            Universe u = Universe.get();
            if (u != null) {
                for (PlayerRef p : u.getPlayers()) {
                    remember(p);
                    out.add(new PlayerSearch.Candidate(p.getUuid(), p.getUsername(), true));
                }
            }
        } catch (RuntimeException ignored) {
            // keine Online-Liste
        }
        return out;
    }

    static List<PlayerSearch.Candidate> knownPlayers() {
        List<PlayerSearch.Candidate> out = new ArrayList<>();
        KnownPlayers k = players;
        if (k != null) {
            for (KnownPlayers.Known p : k.all()) {
                out.add(new PlayerSearch.Candidate(p.uuid(), p.name(), false));
            }
        }
        return out;
    }

    static boolean isOnline(UUID uuid) {
        try {
            Universe u = Universe.get();
            return u != null && u.getPlayer(uuid) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Entzug durchsetzen

    /**
     * Nach dem Austragen: schließt beim (online) Spieler alle offenen Block-Fenster von AE2-Blöcken, die er nicht
     * mehr benutzen darf (Laufwerk, ME-Truhe, Maschinen …). Offene Terminals zeigen „kein Zugriff“ und lehnen jede
     * Aktion ab (siehe {@code NetworkTerminalBackend}).
     */
    static void enforce(UUID playerId) {
        try {
            Universe u = Universe.get();
            PlayerRef pr = u == null ? null : u.getPlayer(playerId);
            Ref<EntityStore> ref = pr == null ? null : pr.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            World world = ref.getStore().getExternalData().getWorld();
            world.execute(() -> closeDeniedWindows(world, pr));
        } catch (RuntimeException e) {
            HytaleLogger l = logger;
            if (l != null) {
                l.at(Level.FINE).withCause(e).log("AE2: could not close window after access was revoked");
            }
        }
    }

    private static void closeDeniedWindows(World world, PlayerRef pr) {
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        WindowManager wm = player == null ? null : player.getWindowManager();
        if (wm == null) {
            return;
        }
        WorldNetworks nets = WorldNetworks.of(world);
        boolean closed = false;
        for (Window w : new ArrayList<>(wm.getWindows())) {
            if (w instanceof BlockWindow bw) {
                WorldNetworks.Node n = nets.node(bw.getX(), bw.getY(), bw.getZ());
                if (n != null && !canUse(nets, n, pr.getUuid())) {
                    wm.closeWindow(ref, w.getId(), store);
                    closed = true;
                }
            }
        }
        if (closed) {
            player.getPageManager().closePage(ref, store);
        }
    }
}
