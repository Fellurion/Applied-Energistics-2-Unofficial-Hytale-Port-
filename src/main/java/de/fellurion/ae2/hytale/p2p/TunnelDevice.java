/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.p2p.P2PTunnelPart, appeng.api.features.P2PTunnelAttunement).
 */
package de.fellurion.ae2.hytale.p2p;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.p2p.MEP2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PFrequency;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PTunnelType;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.items.MemoryCardTarget;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * P2P-Tunnel aller sechs Arten. Die Rückseite hängt am tragenden ME-Netz (dort braucht jeder Tunnel einen Kanal
 * und Strom), die Vorderseite zeigt auf das, was getunnelt wird.
 *
 * <p><b>Verknüpfen</b> wie in AE2 mit der Memory Card ({@link MemoryCardTarget}, Kartenart {@value #CARD_TYPE}):
 * Speichern macht den Tunnel zum Eingang einer neuen Frequenz, Übertragen macht einen anderen Tunnel zum Ausgang
 * dieser Frequenz – und, falls er von anderer Art ist, vorher zu einem Tunnel der Art auf der Karte. Frequenz und
 * Rolle stehen in der Block-Komponente ({@code Config}: {@value #KEY_FREQ}, {@value #KEY_OUTPUT}).
 *
 * <p><b>Abstimmen</b> (AE2: Attunement): den Tunnel mit einem passenden Item in der Hand benutzen (F) macht aus ihm
 * die zugehörige Tunnelart, siehe {@link TunnelAttunement}. Das Item wird nicht verbraucht.
 *
 * <p>Abweichungen von AE2: Ein Tunnel anderer Art ist in Hytale ein anderer Block. Beim Abstimmen und beim
 * Übertragen einer Karte anderer Art wird der Block deshalb ersetzt; Ausrichtung, Frequenz, Rolle und Besitzer gehen
 * auf den neuen Block über. Was die Arten transportieren, steht bei {@link TunnelBehaviour#forType}.
 */
public final class TunnelDevice implements Device, MemoryCardTarget {

    /** {@code MemoryCardData.type()} einer Karte, die an einem P2P-Tunnel beschrieben wurde. */
    public static final String CARD_TYPE = "p2p";
    static final String KEY_FREQ = "p2pFreq";
    static final String KEY_OUTPUT = "p2pOutput";

    private final P2PTunnelType type;
    private final P2PTunnelLogic logic;
    private final TunnelBehaviour behaviour;
    private boolean saveRequested;
    /** Der Block wird gerade gegen eine andere Tunnelart getauscht. */
    private boolean converting;

    private TunnelDevice(P2PTunnelType type, short freq, boolean output) {
        this.type = type;
        this.logic = P2PTunnelLogic.create(type);
        this.logic.restore(freq, output);
        this.behaviour = TunnelBehaviour.forType(type);
    }

    static Device create(WorldNetworks nets, WorldNetworks.Node node) {
        // Wurde der Block gerade aus einer anderen Tunnelart getauscht, bringt der Vorgänger seine Einstellungen mit
        Map<String, String> handed = P2P.takeHandoff(nets, node.pos);
        Map<String, String> config = node.comp.config();
        if (handed != null) {
            config.putAll(handed);
            node.markDirty();
        }
        P2PTunnelType type = P2PTunnelType.fromId(node.comp.tunnelType());
        short freq = P2PFrequency.fromHexString(config.get(KEY_FREQ));
        boolean output = freq != 0 && Boolean.parseBoolean(config.get(KEY_OUTPUT));
        return new TunnelDevice(type == null ? P2PTunnelType.ME : type, freq, output);
    }

    public P2PTunnelType type() {
        return type;
    }

    /** Frequenz, Rolle und Gegenstellen (Kern). */
    public P2PTunnelLogic logic() {
        return logic;
    }

    TunnelBehaviour behaviour() {
        return behaviour;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public NodeType nodeType(WorldNetworks nets, WorldNetworks.Node node) {
        return type.mainNodeType();
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        if (logic instanceof MEP2PTunnelLogic me && logic.getMainNode() != null) {
            // Zweiter Knoten an der Vorderseite: das Ende des getunnelten Netzes
            me.createOuterNode();
        }
        logic.setSaveListener(() -> saveRequested = true);
        behaviour.onAttached(this, nets, node);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        behaviour.tick(this, nets, node);
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            save(node);
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        behaviour.onRemoved(this, nets, node, broken);
        if (!broken && saveRequested) {
            save(node);
        }
    }

    @Nullable
    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        return behaviour.visualState(this, nets, node);
    }

    private void save(WorldNetworks.Node node) {
        saveRequested = false;
        Map<String, String> config = node.comp.config();
        short freq = logic.getFrequency();
        if (freq == 0) {
            config.remove(KEY_FREQ);
            config.remove(KEY_OUTPUT);
        } else {
            config.put(KEY_FREQ, P2PFrequency.toHexString(freq));
            config.put(KEY_OUTPUT, String.valueOf(logic.isOutput()));
        }
        node.markDirty();
    }

    // ------------------------------------------------------------------ Memory Card

    /** Inhalt einer P2P-Karte: Tunnelart und Frequenz (0 = keine). */
    record Link(P2PTunnelType type, short freq) {

        String toPayload() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type.id());
            o.addProperty("freq", P2PFrequency.toHexString(freq));
            return o.toString();
        }

        @Nullable
        static Link parse(@Nullable String payload) {
            try {
                JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                P2PTunnelType type = P2PTunnelType.fromId(o.get("type").getAsString());
                if (type == null) {
                    return null;
                }
                return new Link(type, o.has("freq") ? P2PFrequency.fromHexString(o.get("freq").getAsString()) : 0);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Speichern (AE2: geduckt + Rechtsklick): Der Tunnel wird Eingang – einer neuen Frequenz, wenn er Ausgang oder
     * noch unverknüpft war. Auf die Karte kommen Tunnelart und Frequenz.
     */
    @Override
    public MemoryCardData saveToCard(WorldNetworks nets, WorldNetworks.Node node) {
        logic.saveToMemoryCard();
        save(node);
        return new MemoryCardData(CARD_TYPE, new Link(type, logic.getFrequency()).toPayload(),
                "ae2.items." + P2P.itemId(type) + ".name");
    }

    /**
     * Übertragen (AE2: Rechtsklick): Der Tunnel wird Ausgang der Frequenz auf der Karte; ist er von anderer Art als
     * die Karte, wird er zuvor zu deren Art. Trägt er die Frequenz schon, ändert sich nichts (sonst machte man einen
     * Eingang mit seiner eigenen Karte unbrauchbar).
     */
    @Override
    public Result loadFromCard(WorldNetworks nets, WorldNetworks.Node node, MemoryCardData data) {
        if (!CARD_TYPE.equals(data.type())) {
            return Result.WRONG_TYPE;
        }
        Link link = Link.parse(data.payload());
        if (link == null) {
            return Result.FAILED;
        }
        if (link.type() != type) {
            // AE2 ersetzt das Part und lädt dann die Karte: der neue Tunnel ist Ausgang der Frequenz
            return convert(nets, node, link.type(), link.freq(), link.freq() != 0) ? Result.LOADED : Result.FAILED;
        }
        if (link.freq() != 0) {
            logic.loadFromMemoryCard(link.freq());
            save(node);
        }
        return Result.LOADED;
    }

    // ------------------------------------------------------------------ Abstimmen / Art wechseln

    /** Benutzen (F): mit einem Abstimm-Item in der Hand wird der Tunnel zur passenden Art. */
    static void use(UseContext ctx) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (node == null || !(node.device() instanceof TunnelDevice device)) {
            return; // im Tick des Platzierens ist das Gerät noch nicht angemeldet
        }
        ItemStack held = ctx.itemInHand();
        if (ItemStack.isEmpty(held)) {
            return;
        }
        P2PTunnelType wanted = TunnelAttunement.byTriggerItem(held.getItemId());
        if (wanted != null && wanted != device.type && device.attune(nets, node, wanted)) {
            ctx.playerRef().sendMessage(Message.translation("ae2p2p.msg.attuned")
                    .param("tunnel", Message.translation("ae2.items." + P2P.itemId(wanted) + ".name")));
        }
    }

    /** Macht den Tunnel zu einem der Art {@code newType}; Frequenz und Rolle bleiben (AE2: Attunement). */
    boolean attune(WorldNetworks nets, WorldNetworks.Node node, P2PTunnelType newType) {
        return newType != type && convert(nets, node, newType, logic.getFrequency(), logic.isOutput());
    }

    /**
     * Ersetzt den Block durch den Tunnel der Art {@code newType} mit der angegebenen Frequenz und Rolle. Getauscht
     * wird gleich danach auf dem Welt-Thread, außerhalb laufender Interaktionen und des AE2-Takts.
     */
    private boolean convert(WorldNetworks nets, WorldNetworks.Node node, P2PTunnelType newType, short freq,
                            boolean output) {
        if (converting || newType == type) {
            return false;
        }
        converting = true;
        long pos = node.pos;
        nets.world().execute(() -> {
            converting = false;
            swap(nets, pos, newType, freq, output);
        });
        return true;
    }

    private void swap(WorldNetworks nets, long pos, P2PTunnelType newType, short freq, boolean output) {
        World world = nets.world();
        int x = BlockAccess.unpackX(pos), y = BlockAccess.unpackY(pos), z = BlockAccess.unpackZ(pos);
        WorldNetworks.Node node = nets.node(x, y, z);
        if (node == null || node.device() != this) {
            return; // inzwischen abgebaut
        }
        // Alles aus der Komponente geht mit (auch der Besitzer), Frequenz und Rolle wie verlangt
        Map<String, String> config = new HashMap<>(node.comp.config());
        if (freq == 0) {
            config.remove(KEY_FREQ);
            config.remove(KEY_OUTPUT);
        } else {
            config.put(KEY_FREQ, P2PFrequency.toHexString(freq));
            config.put(KEY_OUTPUT, String.valueOf(output));
        }
        int rotation = BlockAccess.rotationIndex(world, x, y, z);
        P2P.handoff(nets, pos, config);
        // Ohne Drops: der alte Tunnel wird zum neuen, es fällt nichts heraus
        if (!BlockAccess.setBlock(world, x, y, z, P2P.itemId(newType), rotation, BlockAccess.QUIET)) {
            P2P.clearHandoff(nets, pos);
        }
    }
}
