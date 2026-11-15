/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;

/**
 * Markierung an einem Chunk: „mit AE2 generiert“. Wird mit dem Chunk gespeichert. Chunks ohne die Markierung gab es
 * schon vor dieser Funktion – was dort gebaut wurde, weiß AE2 nicht; die Bauschutz-Stufe „strict“ lässt deshalb
 * Einschläge nur in markierten Chunks zu.
 */
public final class ChunkInfo implements Component<ChunkStore> {

    public static final String ID = "AE2_ChunkInfo";
    public static final BuilderCodec<ChunkInfo> CODEC = BuilderCodec.builder(ChunkInfo.class, ChunkInfo::new).build();

    @Nullable
    private static volatile ComponentType<ChunkStore, ChunkInfo> type;

    public static void setComponentType(ComponentType<ChunkStore, ChunkInfo> componentType) {
        type = componentType;
    }

    /** Markiert einen frisch generierten Chunk (er ist noch nicht in der Welt). */
    static void markGenerated(Holder<ChunkStore> chunk) {
        ComponentType<ChunkStore, ChunkInfo> t = type;
        if (t != null && chunk.getComponent(t) == null) {
            chunk.addComponent(t, new ChunkInfo());
        }
    }

    /** Ob der geladene Chunk mit AE2 generiert wurde (Welt-Thread). */
    static boolean isGenerated(World world, long chunkIndex) {
        ComponentType<ChunkStore, ChunkInfo> t = type;
        return t != null && world.getChunkStore().getChunkComponent(chunkIndex, t) != null;
    }

    @Override
    public Component<ChunkStore> clone() {
        return new ChunkInfo();
    }
}
