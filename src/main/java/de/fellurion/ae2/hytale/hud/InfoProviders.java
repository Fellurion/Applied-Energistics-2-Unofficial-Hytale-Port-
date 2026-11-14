/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Welche {@link InfoProvider} für welche Block-Art ({@code Kind}) zuständig ist.
 * Ohne eigenen Provider zeigt ein Netzblock Name und Zustand (online, kein Strom, kein Kanal …).
 *
 * <p>Neue Arten: {@code InfoProviders.register((ctx, node, out) -> { ... }, "level_emitter");}
 */
public final class InfoProviders {

    private static final Map<String, InfoProvider> BY_KIND = new ConcurrentHashMap<>();

    static {
        BuiltinProviders.registerAll();
    }

    private InfoProviders() {
    }

    /** Meldet {@code provider} für die Arten an (ersetzt eine vorhandene Anmeldung). */
    public static void register(InfoProvider provider, String... kinds) {
        for (String kind : kinds) {
            BY_KIND.put(kind, provider);
        }
    }

    /** Provider für eine Art (Standard: Name + Netzzustand). */
    public static InfoProvider get(String kind) {
        InfoProvider p = BY_KIND.get(kind);
        return p != null ? p : BuiltinProviders.GENERIC;
    }

    /**
     * Baut die Anzeige für einen Block, oder null, wenn er keine bekommt. Wirft nie: Fehler eines Providers lassen
     * nur Name und Symbol übrig.
     */
    @Nullable
    /** Fremdes Netz: nur Name und Besitzer, keine Netz-Details (Zugriff regelt der Besitzer im Terminal). */
    static LookInfo locked(HudContext ctx, WorldNetworks.Node node, @Nullable String ownerName) {
        ItemCatalog.Info item = node.item();
        String itemId = item == null ? null : item.id();
        String name = itemId == null ? node.kind() : ctx.itemName(itemId);
        return LookInfo.builder(itemId, name)
                .status(ctx.tr("owner.locked"), HudColors.WARN)
                .line(ctx.tr("owner.label"), ownerName == null ? "?" : ownerName, HudColors.TEXT)
                .build();
    }

    static LookInfo describe(HudContext ctx, WorldNetworks.Node node) {
        ItemCatalog.Info item = node.item();
        String itemId = item == null ? null : item.id();
        String name = itemId == null ? node.kind() : ctx.itemName(itemId);
        LookInfo.Builder out = LookInfo.builder(itemId, name);
        try {
            get(node.kind()).describe(ctx, node, out);
        } catch (RuntimeException e) {
            out = LookInfo.builder(itemId, name);
        }
        return out.suppressed() ? null : out.build();
    }
}
