/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.core.tools.EntropyRecipes;
import de.fellurion.ae2.core.tools.MatterCannonAmmo;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.items.ItemUse;

import java.util.logging.Level;

/** Geräte mit Akku: Entropy Manipulator, Charged Staff, Matter Cannon, Color Applicator; dazu Tiny TNT. */
public final class Gadgets {

    private Gadgets() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        HytaleLogger log = plugin.getLogger();
        GadgetConfig.load(plugin.getDataDirectory(), (text, error) -> log.at(Level.WARNING).withCause(error).log(text));

        // Rezepte: fehlt eine Datei, bleibt das Werkzeug ohne Wirkung, alles andere läuft weiter
        EntropyRecipes entropy = new EntropyRecipes();
        MatterCannonAmmo ammo = new MatterCannonAmmo();
        try {
            entropy = EntropyRecipes.fromJson(ItemCatalog.resource("entropy.json"));
            ammo = MatterCannonAmmo.fromJson(ItemCatalog.resource("matter_cannon.json"));
        } catch (RuntimeException e) {
            log.at(Level.WARNING).withCause(e).log("AE2: entropy.json / matter_cannon.json are not readable");
        }
        EntropyManipulator.setRecipes(entropy);
        MatterCannon.setup(ammo);
        ColorApplicator.setup();

        // Rechtsklick mit dem Werkzeug in der Hand (Item-JSON: "Secondary": "AE2_Use_Item")
        ItemUse.register(EntropyManipulator.ITEM, EntropyManipulator::use);
        ItemUse.register(MatterCannon.ITEM, MatterCannon::use);
        ItemUse.register(ColorApplicator.ITEM, ColorApplicator::use);

        // Treffer mit dem Charged Staff / Entropy Manipulator
        try {
            plugin.getEntityStoreRegistry().registerSystem(new MeleeHits());
        } catch (RuntimeException | LinkageError e) {
            log.at(Level.WARNING).withCause(e).log("AE2: Charged Staff bonus damage is not available");
        }

        // Tiny TNT: Zündschnur je Block, „Benutzen“ zündet
        TinyTnt.setup(log);
        Devices.register(TinyTnt.KIND, (nets, node) -> new TinyTnt.TntDevice());
        UseHandlers.register(TinyTnt.KIND, ctx -> TinyTnt.ignite(ctx.world(), ctx.pos().x, ctx.pos().y, ctx.pos().z));

        AE2Command.registerSub(new GadgetCommands());
        log.at(Level.INFO).log("AE2: gadgets enabled (%d entropy recipes, %d kinds of matter cannon ammo)",
                entropy.getRecipes().size(), ammo.getEntries().size());
    }
}
