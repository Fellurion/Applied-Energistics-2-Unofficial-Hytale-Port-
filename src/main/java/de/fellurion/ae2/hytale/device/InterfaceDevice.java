/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.helpers.InterfaceLogic, appeng.blockentity.misc.InterfaceBlockEntity,
 * appeng.parts.misc.InterfacePart).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.automation.InterfaceLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.crafting.MultiCraftingTracker;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.ConfigHost;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ME-Interface (Block und Kabel-Part): hält in seinen 9 Plätzen den eingestellten Vorrat aus dem Netz bereit und
 * gibt alles andere, was hineingelegt wird, ins Netz zurück.
 *
 * <p><b>Vorrat = der Hytale-Container des Blocks.</b> Andere Blöcke und Spieler greifen also ganz normal darauf zu.
 * Der Kern ({@link InterfaceLogic}) führt denselben Vorrat als eigene Plätze; beide Seiten werden sofort gespiegelt:
 * ändert der Kern einen Platz, steht das im selben Moment im Container ({@link #push}), ändert jemand den Container,
 * im selben Moment im Kern ({@link #pull}). So sieht nie eine Seite einen veralteten Stand, und kein Item existiert
 * doppelt oder gar nicht. Beim Laden gilt der Container.
 *
 * <p>Einstellungen (Vorrat je Platz, Upgrade-Karte, Fuzzy-Modus) stehen als JSON in der Block-Komponente
 * ({@code Data}), die Priorität zusätzlich in {@code Priority}; der Spieler stellt sie in
 * {@code hytale.ui.DeviceConfigPage} ein, der das Interface als {@link ConfigHost} sagt, was sie zeigt (AE2:
 * InterfaceMenu / InterfaceScreen). Ein Storage-Bus eines anderen Netzes vor dem Interface sieht
 * {@link InterfaceLogic#getExposedStorage()} (siehe {@link BusDevice}).
 *
 * <p>Abweichung von AE2: Ein Platz fasst einen Stapel des Hytale-Items (AE2: höchstens 99, Minecrafts absolute
 * Stapelgrenze); Flüssigkeiten gibt es hier nicht.
 */
public final class InterfaceDevice implements Device, ConfigHost {

    /** AE2: 9 Konfigurations- und 9 Vorratsplätze. */
    public static final int SLOTS = 9;

    private final InterfaceLogic logic;
    private final MultiCraftingTracker crafting;
    @Nullable
    private ItemContainer container;
    @Nullable
    private com.hypixel.hytale.event.EventRegistration<?, ?> changeReg;
    /** Zuletzt gesehener Stack je Platz (ItemStacks sind unveränderlich: gleiches Objekt = nichts geändert). */
    private ItemStack[] known = new ItemStack[0];
    /** Schlüssel zu {@link #known} (spart das Umrechnen der Metadaten bei jedem Vergleich). */
    private AEItemKey[] knownKeys = new AEItemKey[0];
    /** Schutz gegen Rekursion: Spiegeln in die eine Richtung löst das Ereignis der anderen aus. */
    private boolean busy;
    private boolean saveRequested;
    @Nullable
    private WorldNetworks nets;
    @Nullable
    private WorldNetworks.Node node;
    private final Watchers watchers = new Watchers();

    /** @param part true = Kabel-Part ({@code AE2_Cable_Interface}), sonst der ganze Block */
    public InterfaceDevice(boolean part) {
        logic = new InterfaceLogic(part ? InterfaceLogic.PART_ITEM_ID : InterfaceLogic.BLOCK_ITEM_ID, SLOTS,
                ItemKeys::maxStack);
        // Ein Platz fasst einen Hytale-Stapel (die 99er-Grenze von AE2 stammt aus Minecraft)
        logic.getConfig().setCapacity(AEKeyType.ITEMS, Long.MAX_VALUE);
        logic.getStorage().setCapacity(AEKeyType.ITEMS, Long.MAX_VALUE);
        // Crafting-Karte: Fehlendes bestellen, das Ergebnis geht direkt in den Platz (AE2: MultiCraftingTracker)
        crafting = new MultiCraftingTracker(logic, SLOTS,
                (slot, what, amount, mode) -> logic.getStorage().insert(slot, what, amount, mode));
        logic.setCraftingHook(crafting);
    }

    public InterfaceLogic logic() {
        return logic;
    }

    /** Der Vorrat als Hytale-Container (9 Plätze) oder null, wenn der Block keinen hat. */
    @Nullable
    public ItemContainer container() {
        return container;
    }

    /** Offene Konfigurationsseiten dieses Interface. */
    @Override
    public Watchers watchers() {
        return watchers;
    }

    @Override
    public Object owner() {
        return logic;
    }

    /** Priorität setzen (Kern und Block-Komponente). */
    @Override
    public void setPriority(WorldNetworks.Node node, int priority) {
        logic.setPriority(priority);
        node.comp.setPriority(priority);
        node.markDirty();
    }

    // ------------------------------------------------------------------ Konfigurationsseite (ConfigHost)

    /** Vorrat: 9 Plätze mit Menge; dasselbe Item darf in mehreren Plätzen stehen. */
    @Override
    public GhostSlots ghostSlots(Context ctx) {
        ConfigInventory config = logic.getConfig();
        return new GhostSlots(config, Math.min(SLOTS, config.size()), true, "ae2bus.filter.interface",
                "ae2bus.tip.stock", "ae2bus.hint.interface");
    }

    @Override
    public UpgradeInventory upgradeCards() {
        return logic.getUpgrades();
    }

    /** „Online“ bzw. „Offline …“ und was ein Storage-Bus vor dem Interface sieht. */
    @Override
    public String info(Context ctx) {
        String state = ctx.tr(online(ctx.node()) ? "ae2bus.info.online" : "ae2bus.info.offline");
        return state + " · " + ctx.tr(logic.getConfig().isEmpty() ? "ae2bus.info.exposesNetwork"
                : "ae2bus.info.exposesStock");
    }

    @Override
    public List<Setting> settings(Context ctx) {
        return logic.getUpgrades().isInstalled(UpgradeCard.FUZZY)
                ? List.of(Setting.fuzzy(ctx, logic.getFuzzyMode(), logic::setFuzzyMode)) : List.of();
    }

    /** Das Interface hat wie in AE2 eine Einstellung: den Fuzzy-Modus ({@code Settings.FUZZY_MODE}). */
    @Override
    public Map<String, String> exportSettings() {
        return Map.of(BusDevice.SETTING_FUZZY_MODE, logic.getFuzzyMode().name());
    }

    @Override
    public boolean importSettings(Map<String, String> in) {
        return MemoryCardSettings.importEnum(in, BusDevice.SETTING_FUZZY_MODE, FuzzyMode.class, logic::setFuzzyMode);
    }

    @Override
    public boolean hasPriority() {
        return true;
    }

    @Override
    public int priority() {
        return logic.getPriority();
    }

    /** Die Vorratsplätze: der echte Container des Blocks. */
    @Override
    public ItemSection itemSection() {
        return new ItemSection(container, SLOTS, "ae2bus.storage.header", "ae2bus.tip.storage");
    }

    // ------------------------------------------------------------------ Gerät

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        this.nets = nets;
        this.node = node;
        if (node.comp.data() != null) {
            try {
                logic.fromJson(node.comp.data());
            } catch (RuntimeException ignored) {
                // kaputte Konfiguration: Standardwerte
            }
        }
        logic.setPriority(node.comp.priority());
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container != null) {
            int n = Math.min(SLOTS, container.getCapacity());
            known = new ItemStack[n];
            knownKeys = new AEItemKey[n];
            // Beim Laden gilt der Container: der Kern übernimmt, was wirklich daliegt
            pull(true);
            for (short s = 0; s < n; s++) {
                // Wie in AE2: ein konfigurierter Platz nimmt nur das eingestellte Item an, ein freier alles
                // (das Interface gibt es dann ans Netz weiter)
                container.setSlotFilter(FilterActionType.ADD, s, this::mayAdd);
            }
            changeReg = container.registerChangeEvent(e -> pull(false));
            logic.setStorageListener(this::push);
        }
        logic.setSaveListener(() -> saveRequested = true);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        pull(false); // Sicherheitsnetz, falls eine Änderung ohne Ereignis kam (9 Objektvergleiche)
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            saveRequested = false;
            String json = logic.toJson();
            if (!json.equals(node.comp.data())) {
                node.comp.setData(json);
                node.markDirty();
            }
        }
        watchers.tick(nets.tickCount());
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (changeReg != null) {
            try {
                changeReg.unregister();
            } catch (RuntimeException ignored) {
                // Container schon weg
            }
            changeReg = null;
        }
        logic.setStorageListener(null);
        if (broken) {
            // Abgebaut: laufende Bestellungen abbrechen, die Karte fällt heraus (den Vorrat lässt Hytale mit dem
            // Container fallen; AE2: InterfaceLogic.addDrops)
            crafting.cancelAll();
            Drops.upgrades(nets.world(), node.x(), node.y(), node.z(), logic.getUpgrades());
        } else {
            node.comp.setData(logic.toJson());
            node.markDirty();
        }
        watchers.run(); // offene Seiten merken, dass es das Interface nicht mehr gibt, und schließen sich
    }

    /**
     * Slot-Filter des Vorrats für Zugriffe von außen. Was schon im Platz liegt, darf immer weiter verändert werden
     * (Menge ändern beim Entnehmen oder Auffüllen) – auch wenn es nicht mehr zur Konfiguration passt: Würde der
     * Container eine verringerte Menge ablehnen, hätte ein Import-Bus die Items entnommen, ohne dass sie hier fehlen.
     */
    private boolean mayAdd(FilterActionType action, ItemContainer c, short slot, @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        AEItemKey key = ItemKeys.of(stack);
        if (key == null) {
            return false;
        }
        if (slot >= 0 && slot < c.getCapacity() && key.equals(ItemKeys.of(c.getItemStack(slot)))) {
            return true;
        }
        return logic.getStorage().isAllowedIn(slot, key);
    }

    // ------------------------------------------------------------------ Spiegel Container ↔ Kern

    /**
     * Hytale-Container → Kern: übernimmt Plätze, deren Stack sich geändert hat.
     *
     * @param all true: jeden Platz übernehmen (beim Laden), sonst nur geänderte
     */
    private void pull(boolean all) {
        ItemContainer c = container;
        if (c == null || busy) {
            return;
        }
        busy = true;
        try {
            ConfigInventory storage = logic.getStorage();
            for (short s = 0; s < known.length; s++) {
                ItemStack cur = c.getItemStack(s);
                if (!all && cur == known[s]) {
                    continue;
                }
                remember(s, cur);
                AEItemKey key = knownKeys[s];
                int quantity = key == null ? 0 : cur.getQuantity();
                GenericStack want = quantity <= 0 ? null : new GenericStack(key, quantity);
                if (!Objects.equals(storage.getStack(s), want)) {
                    storage.setStack(s, want);
                }
                // Mehr im Container als ein Platz des Kerns fasst (über der Stapelgröße): der Überschuss darf weder
                // verschwinden noch doppelt zählen – er fällt am Block heraus
                long kept = storage.getAmount(s);
                if (want != null && kept < quantity) {
                    ItemStack rest = cur.withQuantity((int) (quantity - kept));
                    ItemStack keep = kept <= 0 ? null : cur.withQuantity((int) kept);
                    write(c, s, keep);
                    if (rest != null && nets != null && node != null) {
                        Drops.at(nets.world(), node.x(), node.y(), node.z(), List.of(rest));
                    }
                }
            }
        } finally {
            busy = false;
        }
    }

    /** Kern → Hytale-Container: schreibt Plätze, die der Kern gerade geändert hat. */
    private void push() {
        ItemContainer c = container;
        if (c == null || busy) {
            return;
        }
        busy = true;
        try {
            ConfigInventory storage = logic.getStorage();
            for (short s = 0; s < known.length; s++) {
                GenericStack want = storage.getStack(s);
                ItemStack cur = c.getItemStack(s);
                if (cur != known[s]) {
                    remember(s, cur);
                }
                AEItemKey curKey = knownKeys[s];
                int curQuantity = curKey == null ? 0 : cur.getQuantity();
                AEItemKey wantKey = want != null && want.what() instanceof AEItemKey k && want.amount() > 0 ? k : null;
                int wantQuantity = wantKey == null ? 0 : (int) Math.min(Integer.MAX_VALUE, want.amount());
                if (Objects.equals(curKey, wantKey) && curQuantity == wantQuantity) {
                    continue;
                }
                try {
                    ItemStack next;
                    if (wantKey == null) {
                        next = null;
                    } else if (wantKey.equals(curKey)) {
                        next = cur.withQuantity(wantQuantity); // gleicher Gegenstand: nur die Menge ändert sich
                    } else {
                        next = ItemKeys.toStack(wantKey, wantQuantity);
                    }
                    write(c, s, next);
                } catch (RuntimeException e) {
                    // Der Gegenstand lässt sich nicht als Hytale-Stack anlegen (kaputte Metadaten): der Container
                    // bleibt maßgeblich, der Kern übernimmt wieder dessen Stand – nie umgekehrt, sonst gäbe es
                    // Items, die nur der Kern kennt
                    remember(s, cur);
                    storage.setStack(s, curKey == null ? null : new GenericStack(curKey, curQuantity));
                }
            }
        } finally {
            busy = false;
        }
    }

    /** Schreibt einen Platz am Slot-Filter vorbei (der gilt für Zugriffe von außen) und merkt sich den Stand. */
    private void write(ItemContainer c, short slot, @Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            ItemStack cur = c.getItemStack(slot);
            if (!ItemStack.isEmpty(cur)) {
                c.removeItemStackFromSlot(slot, false);
            }
        } else {
            c.setItemStackForSlot(slot, stack, false);
        }
        remember(slot, c.getItemStack(slot));
    }

    private void remember(short slot, @Nullable ItemStack stack) {
        known[slot] = stack;
        knownKeys[slot] = ItemKeys.of(stack);
    }
}
