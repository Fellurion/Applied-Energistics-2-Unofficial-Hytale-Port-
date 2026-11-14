/*
 * Applied Energistics 2 für Hytale – portiert von Fellurion aus Applied Energistics 2
 * (https://github.com/AppliedEnergistics/Applied-Energistics-2).
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.hytale.AE2BlockComponent;
import de.fellurion.ae2.hytale.AE2BlockLifecycle;
import de.fellurion.ae2.hytale.AE2TickSystem;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction;
import de.fellurion.ae2.hytale.HytaleBridge;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/** Einstiegspunkt des Hytale-Plugins (manifest.json → Main). */
public final class AE2Plugin extends JavaPlugin {

    private static AE2Plugin instance;

    public AE2Plugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static AE2Plugin get() {
        return instance;
    }

    @Override
    protected void setup() {
        super.setup();

        // Block-Komponente "AE2_Block"
        ComponentType<ChunkStore, AE2BlockComponent> type = getChunkStoreRegistry()
                .registerComponent(AE2BlockComponent.class, AE2BlockComponent.ID, AE2BlockComponent.CODEC);
        HytaleBridge.registerComponentType(type);

        // Lebenszyklus (Platzieren/Abbauen/Laden) und AE2-Takt
        getChunkStoreRegistry().registerSystem(new AE2BlockLifecycle(type));
        getChunkStoreRegistry().registerSystem(new AE2TickSystem(getLogger()));

        // Interaktion "AE2UseBlock" (RootInteraction AE2_Use_Block im Asset-Pack)
        getCodecRegistry(Interaction.CODEC).register(AE2UseBlockInteraction.TYPE_ID, AE2UseBlockInteraction.class,
                AE2UseBlockInteraction.CODEC);

        // ME-Logik, Rezeptdaten, Befehle
        HytaleBridge.setup(this);

        getLogger().at(Level.INFO).log("Applied Energistics 2 (Hytale-Portierung von Fellurion) geladen");
    }
}
