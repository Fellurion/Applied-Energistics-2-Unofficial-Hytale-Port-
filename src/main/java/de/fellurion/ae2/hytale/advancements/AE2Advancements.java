/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.core.stats.AdvancementTriggers, appeng.init.InitAdvancementTriggers).
 */
package de.fellurion.ae2.hytale.advancements;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Level;

/**
 * AE2-Erfolge (Minecraft-„Advancements“) für Hytale.
 *
 * <p>Hytale hat kein Erfolgssystem, in das sich ein Plugin einklinken kann („Memories“ sind weltweit geteilte
 * NPC-Sammlungen, „Objectives“ sind Quests mit HUD-Anzeige). Deshalb eigenes System:
 * <ul>
 *   <li>Definitionen: {@code ae2data/advancements.json} (die 26 AE2-Erfolge, Texte in {@code ae2adv.lang}),</li>
 *   <li>Fortschritt je Spieler: {@code <Datenordner>/advancements/<uuid>.json},</li>
 *   <li>Item-Erfolge: Hytales {@code InventoryChangeEvent} (+ Komplettprüfung beim Betreten der Welt),</li>
 *   <li>Netz-Erfolge: {@link #onNetworkTick(WorldNetworks)} aus dem AE2-Takt,</li>
 *   <li>beim Erzielen: Hytale-Benachrichtigung mit Item-Symbol + Ton, Chatmeldung an alle (wie AE2),</li>
 *   <li>Übersicht: {@code /ae2 erfolge}.</li>
 * </ul>
 */
public final class AE2Advancements {

    /** Ton beim Erzielen (Vanilla: Freischalten an der Erinnerungs-Werkbank). */
    static final String UNLOCK_SOUND = "SFX_Memories_Unlock_Local";

    @Nullable
    private static volatile AdvancementStore store;
    @Nullable
    private static volatile HytaleLogger logger;

    private AE2Advancements() {
    }

    /**
     * Einmal beim Plugin-Start aufrufen (aus {@code HytaleBridge.setup}): lädt die Definitionen und registriert
     * Inventar-System und Spieler-Ereignisse. Den Befehl liefert {@link #createCommand()}.
     */
    public static void setup(JavaPlugin plugin) {
        AdvancementRegistry registry = AdvancementRegistry.get();
        logger = plugin.getLogger();
        store = new AdvancementStore(plugin.getDataDirectory().resolve("advancements"), plugin.getLogger());
        plugin.getEntityStoreRegistry().registerSystem(new AdvancementInventorySystem());
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, AE2Advancements::onPlayerReady);
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, AE2Advancements::onPlayerDisconnect);
        plugin.getLogger().at(Level.INFO).log("AE2: loaded %d achievements", registry.size());
    }

    /** Unterbefehl {@code erfolge} (Übersicht; {@code erfolge reset} für Admins) – in {@code AE2Command} einhängen. */
    public static AbstractCommand createCommand() {
        return new AdvancementCommand();
    }

    // ------------------------------------------------------------------ Auslöser von außen

    /**
     * Aus {@code WorldNetworks.tick()} (Welt-Thread, 20×/s) aufrufen; prüft alle 2 s Kanal-Erfolge
     * (Spieler im Umkreis von 32 Blöcken eines Netzknotens) und „Rekursives Netzwerken“.
     */
    public static void onNetworkTick(WorldNetworks nets) {
        if (store == null) {
            return;
        }
        try {
            NetworkAdvancementTriggers.tick(nets);
        } catch (RuntimeException e) {
            HytaleLogger l = logger;
            if (l != null) {
                l.at(Level.WARNING).withCause(e).log("AE2 achievements: network check in %s failed", nets.world().getName());
            }
        }
    }

    /**
     * Kanal-Erfolge (AE2 {@code PathingService.achievementPost}): vergibt jede Stufe (8/128/2048), die
     * {@code usedChannels} erreicht, an die Spieler. Anders als AE2 auch übersprungene niedrigere Stufen.
     */
    public static void onNetworkChannels(Iterable<UUID> players, int usedChannels) {
        for (UUID p : players) {
            onNetworkChannels(p, usedChannels);
        }
    }

    static void onNetworkChannels(UUID player, int usedChannels) {
        for (Advancement.Criterion c : AdvancementRegistry.get().criteria(Trigger.NETWORK_CHANNELS)) {
            if (usedChannels >= c.channels()) {
                award(player, c);
            }
        }
    }

    /** Erfüllt alle Kriterien mit diesem Auslöser, z. B. {@link Trigger#SPATIAL_EXPLORER} (für spätere Portierungen). */
    public static void trigger(UUID player, Trigger trigger) {
        for (Advancement.Criterion c : AdvancementRegistry.get().criteria(trigger)) {
            award(player, c);
        }
    }

    /** Vergibt einen Erfolg komplett (alle Kriterien). */
    public static void grant(UUID player, String advancementId) {
        Advancement adv = AdvancementRegistry.get().byId(advancementId);
        if (adv == null) {
            throw new IllegalArgumentException("Unbekannter AE2-Erfolg: " + advancementId);
        }
        for (Advancement.Criterion c : adv.criteria().values()) {
            award(player, c);
        }
    }

    /** Hat der Spieler den Erfolg? */
    public static boolean has(UUID player, String advancementId) {
        AdvancementStore s = store;
        return s != null && s.get(player).isDone(advancementId);
    }

    // ------------------------------------------------------------------ Inventar

    /** Prüft einen geänderten Container (Inventar-Ereignis) auf Erfolgs-Items. */
    static void onContainerChanged(PlayerRef player, ItemContainer container) {
        AdvancementStore s = store;
        if (s == null) {
            return;
        }
        AdvancementRegistry registry = AdvancementRegistry.get();
        PlayerAdvancements progress = s.get(player.getUuid());
        if (progress.completedCount() >= registry.size()) {
            return;
        }
        short cap = container.getCapacity();
        for (short slot = 0; slot < cap; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            for (Advancement.Criterion c : registry.criteriaForItem(stack.getItemId())) {
                if (!progress.isDone(c.advancementId())) {
                    award(player.getUuid(), c);
                }
            }
        }
    }

    /** Prüft das ganze Inventar (Hotbar, Rucksack, Rüstung …); auf dem Welt-Thread aufrufen. */
    static void scanInventory(Ref<EntityStore> ref, Store<EntityStore> entityStore) {
        if (!ref.isValid()) {
            return;
        }
        PlayerRef player = entityStore.getComponent(ref, PlayerRef.getComponentType());
        if (player == null) {
            return;
        }
        for (ComponentType<EntityStore, ? extends InventoryComponent> type : InventoryComponent.EVERYTHING) {
            InventoryComponent inv = entityStore.getComponent(ref, type);
            if (inv != null) {
                onContainerChanged(player, inv.getInventory());
            }
        }
    }

    private static void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (!ref.isValid()) {
            return;
        }
        Store<EntityStore> entityStore = ref.getStore();
        World world = entityStore.getExternalData().getWorld();
        world.execute(() -> scanInventory(ref, entityStore));
    }

    private static void onPlayerDisconnect(PlayerDisconnectEvent event) {
        AdvancementStore s = store;
        if (s != null) {
            s.unload(event.getPlayerRef().getUuid());
        }
        AdvancementsPage.forget(event.getPlayerRef().getUuid());
    }

    // ------------------------------------------------------------------ Vergabe

    /** Fortschritt eines Spielers (leer, wenn {@link #setup} fehlt). */
    static PlayerAdvancements progress(UUID player) {
        AdvancementStore s = store;
        return s == null ? new PlayerAdvancements() : s.get(player);
    }

    /** Setzt den Fortschritt eines Spielers zurück. */
    static void reset(UUID player) {
        AdvancementStore s = store;
        if (s != null) {
            PlayerAdvancements p = s.get(player);
            p.clear();
            s.save(player, p);
            AdvancementsPage.refreshFor(player);
        }
    }

    /** Vermerkt ein Kriterium; meldet einen dadurch erzielten Erfolg. */
    static void award(UUID player, Advancement.Criterion criterion) {
        AdvancementStore s = store;
        Advancement adv = AdvancementRegistry.get().byId(criterion.advancementId());
        if (s == null || adv == null) {
            return;
        }
        PlayerAdvancements progress = s.get(player);
        PlayerAdvancements.Result result = progress.award(adv, criterion.id(), System.currentTimeMillis());
        if (result == PlayerAdvancements.Result.NOTHING) {
            return;
        }
        s.save(player, progress);
        if (result == PlayerAdvancements.Result.COMPLETED) {
            announce(player, adv);
        }
        AdvancementsPage.refreshFor(player);
    }

    /** Benachrichtigung + Ton für den Spieler, Chatmeldung an alle (AE2: show_toast / announce_to_chat). */
    private static void announce(UUID uuid, Advancement adv) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(uuid);
        if (player == null) {
            return;
        }
        Message title = Message.translation(adv.titleKey());
        if (adv.toast()) {
            NotificationUtil.sendNotification(player.getPacketHandler(),
                    Message.translation("ae2adv.toast").param("title", title),
                    Message.translation(adv.descKey()), iconPacket(adv.icon()), NotificationStyle.Success);
            SoundUtil.playSoundEvent2dToPlayer(player, UNLOCK_SOUND, SoundCategory.UI);
        }
        if (adv.announce()) {
            Message msg = Message.translation("ae2adv.announce").param("player", player.getUsername())
                    .param("title", Message.translation(adv.titleKey()).color("#55FF55"));
            for (PlayerRef p : universe.getPlayers()) {
                p.sendMessage(msg);
            }
        }
    }

    @Nullable
    private static ItemWithAllMetadata iconPacket(String itemId) {
        try {
            return new ItemStack(itemId, 1).toPacket();
        } catch (RuntimeException e) {
            return null; // Item fehlt im Asset-Pack – Benachrichtigung dann ohne Symbol
        }
    }
}
