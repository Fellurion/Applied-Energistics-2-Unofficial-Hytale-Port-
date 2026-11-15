/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.helpers.WirelessTerminalMenuHost, appeng.items.tools.powered.WirelessTerminalItem#getLinkedGrid).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.wireless.LinkStatus;
import de.fellurion.ae2.core.wireless.WirelessTerminalHost;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.items.PoweredItems;
import de.fellurion.ae2.hytale.security.AE2Security;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Ein offenes Wireless (Crafting) Terminal: hält den Kern-Host ({@link WirelessTerminalHost}) mit dem Standort des
 * Spielers und der Verknüpfung des Items aktuell.
 *
 * <p>Wie in AE2 zählt die Reichweite zu <b>irgendeinem</b> aktiven Access Point des verknüpften Netzes in derselben
 * Welt; der nächste bestimmt den Verbrauch. Abweichung von AE2: Der verknüpfte Access Point selbst muss geladen und
 * aktiv sein (AE2 genügt, dass er ein Netz hat) – über ihn laufen hier Zugriffsprüfung und Autocrafting-Seiten.
 */
final class WirelessTerminalSession extends HeldItemSession implements WirelessTerminalHost.Holder {

    private final WirelessLink link;
    private World world;
    private double x, y, z;
    @Nullable
    private WirelessTerminalHost host;

    /**
     * @param link  Verknüpfung des Items (beim Öffnen gelesen; solange das Terminal offen ist, ändert sie sich nicht)
     * @param world Welt, in der der Halter steht
     */
    WirelessTerminalSession(@Nullable PlayerRef playerRef, ItemPlace place, int section, short slot, ItemStack stack,
                            WirelessLink link, World world, double x, double y, double z) {
        super(playerRef, place, section, slot, stack);
        this.link = link;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    WirelessLink link() {
        return link;
    }

    @Override
    WirelessTerminalHost host() {
        if (host == null) {
            // erst hier: der Host sieht sich beim Anlegen schon nach dem Access Point um
            host = new WirelessTerminalHost(this, this, () -> resolve(world, link));
        }
        return host;
    }

    @Override
    ItemStack render() {
        return PoweredItems.withStored(known, power);
    }

    /**
     * Das Netz hinter einer Verknüpfung (AE2: {@code getLinkedGrid}): der Access Point muss in {@code world} stehen,
     * geladen sein, noch derselbe sein und ein Netz haben.
     */
    static WirelessTerminalHost.Target resolve(World world, WirelessLink link) {
        if (!link.world().equals(world.getName())) {
            // Andere Welt: deren Netze gehören einem anderen Thread. Wie in AE2 gibt es über Welten hinweg keinen Empfang.
            return new WirelessTerminalHost.Target(null, LinkStatus.OUT_OF_RANGE);
        }
        WorldNetworks.Node node = accessPoint(WorldNetworks.of(world), link);
        GridNode g = node == null ? null : node.gridNode();
        return g == null ? WirelessTerminalHost.Target.notFound() : WirelessTerminalHost.Target.of(g.getGrid());
    }

    /** Der verknüpfte Access Point, wenn er geladen und noch derselbe ist; sonst null. */
    @Nullable
    static WorldNetworks.Node accessPoint(WorldNetworks nets, WirelessLink link) {
        WorldNetworks.Node node = nets.node(link.x(), link.y(), link.z());
        if (node == null || !(node.device() instanceof WirelessAccessPointDevice)
                || !link.id().equals(WirelessAccessPointDevice.linkId(node))) {
            return null;
        }
        return node;
    }

    /** Ist der verknüpfte Access Point aktiv (versorgt, Kanal, Netz hochgefahren)? */
    boolean accessPointActive() {
        if (!link.world().equals(world.getName())) {
            return false;
        }
        WorldNetworks.Node node = accessPoint(WorldNetworks.of(world), link);
        return node != null && node.gridNode() != null && node.gridNode().isActive();
    }

    @Override
    void beforeTick(Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        this.world = world;
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform != null) {
            Vector3d p = transform.getPosition();
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
        }
    }

    @Override
    @Nullable
    String stillAllowed() {
        if (!accessPointActive()) {
            return "ae2wireless.msg.networkOffline";
        }
        if (playerRef != null && !AE2Security.canUse(WorldNetworks.of(world), link.x(), link.y(), link.z(),
                playerRef.getUuid())) {
            return "ae2wireless.msg.noAccess";
        }
        return null;
    }

    /**
     * Steht der Access Point zwar noch, ist aber nicht aktiv, meldet der Kern wie AE2 „außer Reichweite“ (ein
     * inaktiver Access Point sendet nicht). Für den Spieler ist „Netz offline“ die hilfreichere Auskunft.
     */
    @Override
    String failureKey(LinkStatus status) {
        if (status == LinkStatus.OUT_OF_RANGE && link.world().equals(world.getName())
                && accessPoint(WorldNetworks.of(world), link) != null && !accessPointActive()) {
            return "ae2wireless.msg.networkOffline";
        }
        return messageKey(status);
    }

    /** Steht der Spieler (noch) in dieser Welt? Sitzungen ohne Spieler (Tests) gelten als dort. */
    boolean inWorld(World other) {
        if (playerRef == null) {
            return true;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        return ref != null && ref.isValid() && ref.getStore().getExternalData().getWorld() == other;
    }

    /** Verbrauch je Tick an der aktuellen Stelle (AE2: Entfernung zum nächsten Access Point), für die Anzeige. */
    double drainPerTick() {
        WirelessTerminalHost h = host();
        return h.isCreative() || h.getCurrentAccessPoint() == null ? 0 : de.fellurion.ae2.core.config.AEConfig.get()
                .wirelessDrainRate(h.getCurrentDistance());
    }

    // ------------------------------------------------------------------ Halter (Kern)

    @Override
    public MEWorld getWorld() {
        return WorldNetworks.of(world).me();
    }

    @Override
    public double getX() {
        return x;
    }

    @Override
    public double getY() {
        return y;
    }

    @Override
    public double getZ() {
        return z;
    }
}
