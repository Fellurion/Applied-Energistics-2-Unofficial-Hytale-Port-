/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Besitzer eines einzelnen AE2-Blocks, gespeichert in der Block-Komponente ({@code AE2_Block.Config}):
 * {@value #OWNER} (UUID), {@value #OWNER_NAME} (Name beim Platzieren), {@value #OWNER_SINCE} (Platzierzeit, ms).
 *
 * <p>Eingetragen wird beim Platzieren ({@link OwnerPlacedSystem}: Hytale hängt nach dem Platzieren durch einen
 * Spieler die {@link PlacedByInteractionComponent} an die Block-Entity). Blöcke von vor dieser Funktion haben diese
 * Hytale-Komponente meist schon (sie wird mit dem Chunk gespeichert) – {@link #of} übernimmt sie beim ersten Bedarf
 * ohne Zeitstempel (= „schon immer da“). Per Befehl/Weltgenerierung gesetzte Blöcke haben keinen Besitzer.
 */
public final class BlockOwners {

    public static final String OWNER = "owner";
    public static final String OWNER_NAME = "ownerName";
    public static final String OWNER_SINCE = "ownerSince";

    private BlockOwners() {
    }

    /** Gespeicherter Besitzer. */
    record Info(UUID owner, String name, long since) {
    }

    @Nullable
    static Info read(@Nullable Map<String, String> config) {
        if (config == null) {
            return null;
        }
        UUID owner = JsonFiles.uuid(config.get(OWNER));
        if (owner == null) {
            return null;
        }
        long since = 0L;
        String s = config.get(OWNER_SINCE);
        if (s != null) {
            try {
                since = Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                since = 0L;
            }
        }
        String name = config.get(OWNER_NAME);
        return new Info(owner, name == null ? "" : name, Math.max(0L, since));
    }

    static void write(Map<String, String> config, UUID owner, @Nullable String name, long since) {
        config.put(OWNER, owner.toString());
        if (name != null && !name.isBlank()) {
            config.put(OWNER_NAME, name);
        } else {
            config.remove(OWNER_NAME);
        }
        if (since > 0) {
            config.put(OWNER_SINCE, Long.toString(since));
        } else {
            config.remove(OWNER_SINCE);
        }
    }

    /**
     * Besitzer des Blocks; bei Altbestand aus Hytales {@link PlacedByInteractionComponent} nachgetragen (nur auf
     * dem Welt-Thread). null = niemand (alle dürfen).
     */
    @Nullable
    static Info of(WorldNetworks nets, WorldNetworks.Node node) {
        Info info = read(node.comp.config());
        if (info != null) {
            return info;
        }
        UUID placer = placedBy(nets, node.ref);
        if (placer == null) {
            return null;
        }
        String name = AE2Security.knownName(placer);
        write(node.comp.config(), placer, name, 0L);
        node.markDirty();
        return new Info(placer, name == null ? "" : name, 0L);
    }

    @Nullable
    private static UUID placedBy(WorldNetworks nets, Ref<ChunkStore> ref) {
        try {
            if (!nets.world().isInThread() || ref == null || !ref.isValid()) {
                return null;
            }
            Store<ChunkStore> store = ref.getStore();
            PlacedByInteractionComponent c = store.getComponent(ref, PlacedByInteractionComponent.getComponentType());
            return c == null ? null : c.getWhoPlacedUuid();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
