/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.implementations.menuobjects.ItemMenuHost, appeng.menu.AEBaseMenu#checkGuiItemNotInSlot).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.wireless.LinkStatus;
import de.fellurion.ae2.core.wireless.PortableTerminalHost;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.PoweredItems;

import javax.annotation.Nullable;

/**
 * Ein offenes Terminal, das in einem Item steckt (Wireless Terminal, tragbare Zelle): merkt sich, wo das Item liegt,
 * führt dessen Akku und schreibt das Item zurück.
 *
 * <p><b>Nichts wird verdoppelt, nichts geht verloren:</b> Geschrieben wird ausschließlich auf den Platz, an dem
 * gerade genau das Item liegt, das diese Sitzung zuletzt gesehen bzw. geschrieben hat ({@link #known} – gleiche Id,
 * Menge und Metadaten). Hat der Spieler es verschoben, wird es in seinem Inventar wiedergefunden; liegt es nirgends
 * mehr (weggeworfen, eingelagert, Spieler gestorben), wird nichts geschrieben und das Terminal schließt.
 *
 * <p><b>Akku:</b> Solange die Sitzung läuft, zählt die Ladung im Speicher ({@link #power}). Der Leerlaufverbrauch
 * wird höchstens alle {@value #WRITE_INTERVAL} Ticks ins Item geschrieben (jede Änderung geht als Inventar-Update an
 * den Client), spätestens beim Schließen.
 */
abstract class HeldItemSession implements PortableTerminalHost.Battery {

    /** Der Leerlaufverbrauch wird höchstens so oft ins Item geschrieben (Ticks). */
    static final int WRITE_INTERVAL = 20;
    /** Länger als so viele Ticks wird nach einer Pause nicht nachgerechnet. */
    private static final int MAX_CATCH_UP = 40;

    /** Der Spieler des Terminals; null bei Sitzungen ohne Spieler (Tests über die Konsole). */
    @Nullable
    final PlayerRef playerRef;
    private final ItemPlace place;
    private int section;
    private short slot;
    /** Das Item, wie es zuletzt an seinem Platz lag bzw. von dieser Sitzung geschrieben wurde. */
    protected ItemStack known;
    /** Ladung in AE; maßgeblich, solange die Sitzung läuft. */
    protected double power;
    /** Akkugröße in AE (mit den Energiekarten des Items; ändert sich nicht, solange das Terminal offen ist). */
    private final double capacity;
    private boolean powerDirty;
    private long lastWriteTick = Long.MIN_VALUE / 2;
    private long lastTick = Long.MIN_VALUE;
    private boolean open;
    private boolean closing;
    /** Takte in Folge, in denen der Spieler keine Seite offen hatte. */
    private int runsWithoutPage;

    HeldItemSession(@Nullable PlayerRef playerRef, ItemPlace place, int section, short slot, ItemStack stack) {
        this.playerRef = playerRef;
        this.place = place;
        this.section = section;
        this.slot = slot;
        this.known = stack;
        this.capacity = PoweredItems.capacity(stack);
        this.power = Math.min(capacity, PoweredItems.stored(stack));
    }

    /** Der Kern-Host dieses Terminals (Leerlaufverbrauch, Verbindungszustand). */
    abstract PortableTerminalHost host();

    /** Das Item mit dem aktuellen Stand der Sitzung (Ladung, bei Zellen der Inhalt), gebaut aus {@link #known}. */
    abstract ItemStack render();

    /** Vor jedem Takt: wo steht der Spieler, in welcher Welt (Wireless Terminal). */
    void beforeTick(Ref<EntityStore> ref, Store<EntityStore> store, World world) {
    }

    /**
     * Weitere Bedingungen, solange das Terminal offen ist (Wireless Terminal: Access Point aktiv, Zugriff auf das
     * Netz).
     *
     * @return Schlüssel der Meldung, mit der das Terminal schließt, oder null
     */
    @Nullable
    String stillAllowed() {
        return null;
    }

    /** Schlüssel der Meldung zu einem Verbindungszustand, mit dem das Terminal nicht (mehr) benutzbar ist. */
    String failureKey(LinkStatus status) {
        return messageKey(status);
    }

    // ------------------------------------------------------------------ Platz des Items

    private boolean same(@Nullable ItemStack stack) {
        return stack != null && (stack == known || stack.equals(known));
    }

    /**
     * Sucht das Item: erst an seinem Platz, sonst (verschoben) im übrigen Inventar.
     *
     * @return false, wenn es nirgends mehr liegt – dann darf nichts geschrieben werden
     */
    final boolean locate() {
        ItemContainer c = place.container(section);
        if (c != null && slot >= 0 && slot < c.getCapacity()) {
            ItemStack there = c.getItemStack(slot);
            if (same(there)) {
                known = there;
                return true;
            }
        }
        for (int s : place.sections()) {
            ItemContainer other = place.container(s);
            if (other == null) {
                continue;
            }
            for (short i = 0; i < other.getCapacity(); i++) {
                ItemStack there = other.getItemStack(i);
                if (same(there)) {
                    section = s;
                    slot = i;
                    known = there;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Schreibt den aktuellen Stand ins Item – nur, wenn es noch an seinem Platz (oder im Inventar) liegt.
     *
     * @return false, wenn nichts geschrieben wurde; der Stand im Item ist dann weiter {@link #known}
     */
    final boolean commit() {
        if (!locate()) {
            return false;
        }
        ItemStack updated = render();
        if (updated == known || updated.equals(known)) {
            powerDirty = false;
            return true;
        }
        ItemContainer c = place.container(section);
        if (c == null || !c.setItemStackForSlot(slot, updated).succeeded()) {
            return false;
        }
        known = updated;
        powerDirty = false;
        return true;
    }

    /** Schreibt verbrauchte Ladung ins Item: sofort ({@code force}) oder höchstens alle {@value #WRITE_INTERVAL} Ticks. */
    final void flush(boolean force, long nowTick) {
        if (!powerDirty || (!force && nowTick - lastWriteTick < WRITE_INTERVAL)) {
            return;
        }
        if (commit()) {
            lastWriteTick = nowTick;
        }
    }

    // ------------------------------------------------------------------ Akku (Kern)

    @Override
    public final double getAECurrentPower() {
        return power;
    }

    @Override
    public final double extractAEPower(double amount, Actionable mode) {
        double out = Math.max(0, Math.min(power, amount));
        if (mode == Actionable.MODULATE && out > 0) {
            power -= out;
            powerDirty = true;
        }
        return out;
    }

    /** Akkugröße des Items in AE. */
    final double capacity() {
        return capacity;
    }

    // ------------------------------------------------------------------ Lebenszyklus

    final boolean isOpen() {
        return open && !closing;
    }

    /**
     * Beginnt die Sitzung (die Ansicht öffnet gleich).
     *
     * @return false, wenn das Item nicht (mehr) an seinem Platz liegt
     */
    final boolean open() {
        if (!locate()) {
            return false;
        }
        open = true;
        closing = false;
        runsWithoutPage = 0;
        lastTick = Long.MIN_VALUE;
        if (playerRef != null) {
            HeldSessions.add(this);
        }
        return true;
    }

    /** Beendet die Sitzung (die Ansicht ist zu) und schreibt die verbrauchte Ladung ins Item. Darf mehrfach kommen. */
    final void close() {
        if (!open) {
            return;
        }
        open = false;
        if (playerRef != null) {
            HeldSessions.remove(this);
        }
        flush(true, 0);
    }

    /** Die Sitzung ohne Schreiben verwerfen (Spieler hat den Server verlassen). */
    final void abandon() {
        open = false;
    }

    /**
     * Darf über dieses Terminal gerade gehandelt werden? Prüft bei jeder Aktion, dass das Item noch da ist und die
     * Verbindung steht.
     */
    final boolean live() {
        return isOpen() && locate() && host().getLinkStatus().connected();
    }

    /**
     * Ein Takt der Sitzung (etwa alle 10 Ticks, auf dem Welt-Thread des Spielers): Item noch da, Leerlaufverbrauch,
     * Reichweite und Zugriff. Schlägt etwas fehl, schließt das Terminal mit einer Meldung.
     */
    final void tick(Ref<EntityStore> ref, Store<EntityStore> store, Player player, World world) {
        if (!isOpen()) {
            return;
        }
        if (player.getPageManager().getCustomPage() == null) {
            // Keine Seite offen: ohne Abmeldung verschwunden – Sitzung still beenden. Einen Takt Geduld, falls die
            // Seite erst nach dem Beginn der Sitzung beim Spieler ankommt.
            if (++runsWithoutPage >= 2) {
                close();
            }
            return;
        }
        runsWithoutPage = 0;
        if (!locate()) {
            fail(ref, world, "ae2wireless.msg.itemGone");
            return;
        }
        long now = WorldNetworks.of(world).tickCount();
        int elapsed = lastTick == Long.MIN_VALUE ? 1 : (int) Math.max(1, Math.min(MAX_CATCH_UP, now - lastTick));
        lastTick = now;
        beforeTick(ref, store, world);
        PortableTerminalHost host = host();
        host.setCreative(player.getGameMode() == GameMode.Creative);
        host.tick(elapsed);
        LinkStatus status = host.getLinkStatus();
        String reason = status.connected() ? stillAllowed() : failureKey(status);
        if (reason != null) {
            fail(ref, world, reason);
            return;
        }
        flush(false, now);
    }

    /** Meldung an den Spieler, dann die Seite schließen (außerhalb des laufenden System-Takts). */
    private void fail(Ref<EntityStore> ref, World world, String messageKey) {
        closing = true;
        if (playerRef != null) {
            playerRef.sendMessage(Message.translation(messageKey));
        }
        world.execute(() -> {
            try {
                if (ref.isValid() && open) {
                    Store<EntityStore> store = ref.getStore();
                    Player player = store.getComponent(ref, Player.getComponentType());
                    if (player != null && player.getPageManager().getCustomPage() != null) {
                        // schließt die Seite; deren Abmeldung beendet die Sitzung (close)
                        player.getPageManager().setPage(ref, store, Page.None);
                    }
                }
            } finally {
                close();
            }
        });
    }

    /** Meldung zu einem Verbindungszustand (AE2: PlayerMessages / GuiText). */
    static String messageKey(LinkStatus status) {
        return switch (status) {
            case OUT_OF_POWER -> "ae2wireless.msg.outOfPower";
            case NOT_LINKED -> "ae2wireless.msg.notLinked";
            case NETWORK_NOT_FOUND -> "ae2wireless.msg.networkNotFound";
            case OUT_OF_RANGE -> "ae2wireless.msg.outOfRange";
            case CONNECTED -> "ae2wireless.msg.connected";
        };
    }

    /** Für Tests über die Konsole: {@code ticks} Ticks Leerlauf, danach die Ladung ins Item schreiben. */
    final LinkStatus advance(int ticks) {
        host().tick(Math.max(1, ticks));
        flush(true, 0);
        return host().getLinkStatus();
    }
}
