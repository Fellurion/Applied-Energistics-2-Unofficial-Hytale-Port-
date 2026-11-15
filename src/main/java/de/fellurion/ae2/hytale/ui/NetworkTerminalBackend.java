/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.common.MEStorageMenu).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.StorageHelper;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.crafting.CraftingAccess;
import de.fellurion.ae2.hytale.fluid.FluidTypes;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.security.AccessPage;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Terminal-Zugriff auf das ME-Netz eines Terminal-Blocks (Energie wie in AE2: 1 AE pro Item, 1 AE je 125 mB
 * Flüssigkeit). Herstellbare Items (Pattern im Netz) erscheinen wie in AE2 auch mit Menge 0. Flüssigkeiten des
 * Lagers liefert {@link #fluids()}; bewegt werden sie mit {@link #extractFluid} und {@link #insertFluid}.
 *
 * <p>Abgeleitete Terminals tauschen aus, was AE2s {@code MEStorageMenu} vom Host bekommt: das Lager
 * ({@link #storage}, {@link #available}), die Energiequelle ({@link #energy}) und wann das Terminal erreichbar ist
 * ({@link #isOnline}) – etwa die ME Chest (nur ihre eigene Zelle) oder das Wireless Terminal (Akku des Items,
 * Reichweite). Besitz, Zugriff und Autocrafting gelten immer für das Netz des Blocks bei x/y/z.
 */
public class NetworkTerminalBackend implements TerminalBackend {

    protected final WorldNetworks nets;
    protected final int x, y, z;
    private final Object player;
    private final Map<String, AEItemKey> keys = new HashMap<>();

    public NetworkTerminalBackend(WorldNetworks nets, int x, int y, int z, Object player) {
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
        this.player = player;
    }

    /** Spieler des Terminals (UseHandlers übergibt die UUID); null = unbekannt, dann keine Prüfung. */
    @Nullable
    private UUID playerId() {
        return player instanceof UUID u ? u : null;
    }

    // ------------------------------------------------------------------ Zugriffsschutz

    @Override
    public boolean accessAllowed() {
        UUID u = playerId();
        return u == null || AE2Security.canUse(nets, x, y, z, u);
    }

    @Override
    @Nullable
    public String accessDeniedText(String locale) {
        UUID u = playerId();
        return u == null ? null : AE2Security.deniedText(nets, x, y, z, u, locale);
    }

    @Override
    public boolean checkAccess(PlayerRef playerRef) {
        return AE2Security.checkUse(nets, x, y, z, playerRef);
    }

    @Override
    public boolean canManageAccess() {
        UUID u = playerId();
        return u != null && AE2Security.canManage(nets, x, y, z, u);
    }

    @Override
    public boolean openAccess(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                              String locale, @Nullable AccessPage.Reopener back) {
        return AE2Security.openAccessPage(player, playerRef, ref, store, nets, x, y, z, locale, back);
    }

    // ------------------------------------------------------------------ Netz

    @Nullable
    protected final GridNode node() {
        WorldNetworks.Node n = nets.node(x, y, z);
        return n == null ? null : n.gridNode();
    }

    @Nullable
    protected final Grid grid() {
        GridNode g = node();
        return g == null ? null : g.getGrid();
    }

    /** Woraus Ein- und Auslagern bezahlt werden (AE2: {@code MEStorageMenu.energySource}); Standard: das Netz. */
    protected IEnergySource energy(Grid grid) {
        return grid.getEnergyService();
    }

    /** Das Lager hinter dem Terminal (AE2: {@code ITerminalHost.getInventory}); Standard: der ganze Netzinhalt. */
    protected MEStorage storage(Grid grid) {
        return grid.getStorageService().getInventory();
    }

    /** Momentaufnahme des Lagerinhalts (nur lesen); Standard: die des Netzes, einmal je Tick gebaut. */
    protected KeyCounter available(Grid grid) {
        return grid.getStorageService().getCachedInventory();
    }

    /** Wer handelt (für Kern-Aufrufe). */
    protected final IActionSource source() {
        return IActionSource.ofPlayer(player);
    }

    @Override
    public boolean isOnline() {
        GridNode g = node();
        return g != null && g.isActive() && g.getGrid() != null;
    }

    @Override
    public String statusText() {
        Grid grid = grid();
        if (grid == null || !isOnline() || !accessAllowed()) {
            return "Offline";
        }
        var e = grid.getEnergyService();
        return String.format(Locale.ROOT, "Online · %s / %s AE · %.1f AE/t", TerminalTexts.formatAmount((long) e.getStoredPower()),
                TerminalTexts.formatAmount((long) e.getMaxStoredPower()), e.getAvgPowerUsage());
    }

    @Override
    public String storageText() {
        Status s = status();
        if (s == null || !s.online()) {
            return "";
        }
        return s.types() + " types · " + TerminalTexts.formatAmount(s.items()) + " Items";
    }

    @Override
    @Nullable
    public Status status() {
        Grid grid = grid();
        if (grid == null || !isOnline() || !accessAllowed()) {
            return new Status(false, 0, 0, 0, 0, 0);
        }
        var e = grid.getEnergyService();
        KeyCounter inv = available(grid);
        long total = 0;
        long fluid = 0;
        for (KeyCounter.Entry entry : inv) {
            if (entry.getKey() instanceof AEFluidKey) {
                fluid += entry.getLongValue(); // mB, nicht mit Items zusammenzählen
            } else {
                total += entry.getLongValue();
            }
        }
        return new Status(true, e.getStoredPower(), e.getMaxStoredPower(), e.getAvgPowerUsage(), inv.size(), total,
                fluid);
    }

    @Override
    public List<Entry> entries() {
        Grid grid = grid();
        List<Entry> out = new ArrayList<>();
        keys.clear();
        if (grid == null || !accessAllowed()) {
            return out;
        }
        Set<AEItemKey> craftables = craftables();
        for (KeyCounter.Entry e : available(grid)) {
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEItemKey key)) {
                continue;
            }
            String id = keyId(key);
            keys.put(id, key);
            try {
                out.add(new Entry(id, ItemKeys.toStack(key, 1), e.getLongValue(), craftables.contains(key)));
            } catch (RuntimeException ignored) {
                // Item existiert nicht mehr (z. B. Mod entfernt) – nicht anzeigen
            }
        }
        // Herstellbares ohne Bestand: wie in AE2 mit Menge 0 ("Craft")
        for (AEItemKey key : craftables) {
            String id = keyId(key);
            if (keys.containsKey(id)) {
                continue;
            }
            keys.put(id, key);
            try {
                out.add(new Entry(id, ItemKeys.toStack(key, 1), 0, true));
            } catch (RuntimeException ignored) {
                // Pattern auf ein unbekanntes Item – nicht anzeigen
            }
        }
        return out;
    }

    @Override
    public List<Entry> fluids() {
        Grid grid = grid();
        List<Entry> out = new ArrayList<>();
        if (grid == null || !accessAllowed()) {
            return out;
        }
        for (KeyCounter.Entry e : available(grid)) {
            if (e.getLongValue() <= 0 || !(e.getKey() instanceof AEFluidKey key)) {
                continue;
            }
            try {
                out.add(Entry.ofFluid(TerminalBackend.fluidKey(key.fluidId()), key.fluidId(),
                        FluidTypes.displayStack(key.fluidId()), e.getLongValue()));
            } catch (RuntimeException ignored) {
                // kein Item zum Anzeigen – nicht anzeigen
            }
        }
        return out;
    }

    @Override
    public long extractFluid(String keyId, long amount, boolean simulate) {
        Grid grid = grid();
        String fluidId = TerminalBackend.fluidIdOf(keyId);
        // Zugriff bei jeder Aktion neu prüfen, wie bei Items
        if (grid == null || fluidId == null || amount <= 0 || !isOnline() || !accessAllowed()) {
            return 0;
        }
        long got = StorageHelper.poweredExtraction(energy(grid), storage(grid), AEFluidKey.of(fluidId), amount, source(),
                simulate ? Actionable.SIMULATE : Actionable.MODULATE);
        if (got > 0 && !simulate) {
            changed(grid);
        }
        return got;
    }

    @Override
    public long insertFluid(String fluidId, long amount, boolean simulate) {
        Grid grid = grid();
        AEFluidKey key = FluidTypes.key(fluidId); // nur lagerbare Flüssigkeiten
        if (grid == null || key == null || amount <= 0 || !isOnline() || !accessAllowed()) {
            return 0;
        }
        long inserted = StorageHelper.poweredInsert(energy(grid), storage(grid), key, amount, source(),
                simulate ? Actionable.SIMULATE : Actionable.MODULATE);
        if (inserted > 0 && !simulate) {
            changed(grid);
        }
        return inserted;
    }

    /** Items, die das Terminal als herstellbar anbietet; Standard: alle mit Pattern im Netz. */
    protected Set<AEItemKey> craftables() {
        try {
            return CraftingAccess.craftables(nets, x, y, z);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    @Override
    public long insert(ItemStack stack) {
        Grid grid = grid();
        AEItemKey key = ItemKeys.of(stack);
        // Zugriff bei jeder Aktion neu prüfen: die Seite kann offen sein, während der Zugriff entzogen wird
        if (grid == null || key == null || !isOnline() || !accessAllowed()) {
            return 0;
        }
        long inserted = StorageHelper.poweredInsert(energy(grid), storage(grid), key, stack.getQuantity(), source());
        if (inserted > 0) {
            changed(grid);
        }
        return inserted;
    }

    /**
     * Nach einer Aktion des Spielers: Die Momentaufnahme des Netzinhalts ({@code getCachedInventory}) entsteht nur
     * einmal je Tick. Wurde sie in diesem Tick schon gelesen (Planung im Crafting-Terminal, regelmäßige
     * Aktualisierung, Blickanzeige), zeigte das Terminal sonst bis zur nächsten Aktualisierung die alten Mengen.
     */
    private static void changed(Grid grid) {
        grid.getStorageService().invalidateCache();
    }

    @Override
    @Nullable
    public ItemStack extract(String keyId, int amount) {
        Grid grid = grid();
        AEItemKey key = keys.get(keyId);
        if (grid == null || key == null || amount <= 0 || !isOnline() || !accessAllowed()) {
            return null;
        }
        long got = StorageHelper.poweredExtraction(energy(grid), storage(grid), key, amount, source());
        if (got > 0) {
            changed(grid);
        }
        return got <= 0 ? null : ItemKeys.toStack(key, (int) got);
    }

    @Override
    public boolean canExtract(Map<String, Long> amounts) {
        Grid grid = grid();
        if (grid == null || !isOnline() || !accessAllowed()) {
            return false;
        }
        IActionSource src = source();
        MEStorage storage = storage(grid);
        double total = 0;
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            AEItemKey key = keys.get(e.getKey());
            long amount = e.getValue() == null ? 0 : e.getValue();
            if (amount <= 0) {
                continue;
            }
            if (key == null || storage.extract(key, amount, Actionable.SIMULATE, src) < amount) {
                return false;
            }
            total += amount / Math.max(1.0, key.getAmountPerOperation());
        }
        // Energie für alle Entnahmen zusammen (jede einzelne kostet wie im Terminal 1 AE je Item)
        return total <= 0 || energy(grid).extractAEPower(total, Actionable.SIMULATE, PowerMultiplier.CONFIG)
                >= total - 0.01;
    }

    @Override
    public long revision() {
        Grid grid = grid();
        if (grid == null) {
            return Long.MIN_VALUE;
        }
        long h = isOnline() ? 7 : 3;
        // Zugriff entzogen/erteilt: offene Terminals zeichnen neu (Inhalt bzw. Hinweis „kein Zugriff“)
        if (!accessAllowed()) {
            return h * 0x9E3779B97F4A7C15L;
        }
        for (KeyCounter.Entry e : available(grid)) {
            h += e.getKey().hashCode() * 31L + e.getLongValue();
        }
        // neue/entfernte Pattern ändern die Liste der herstellbaren Items
        for (AEItemKey k : craftables()) {
            h += k.hashCode() * 17L + 1;
        }
        return h;
    }

    @Override
    public boolean requestCraft(String keyId, Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                Store<EntityStore> store) {
        AEItemKey key = keys.get(keyId);
        if (key == null || !isOnline() || !checkAccess(playerRef)) {
            return false;
        }
        CraftingAccess.openCraftRequest(player, playerRef, ref, store, nets, x, y, z, key);
        return true;
    }

    @Override
    public boolean hasCraftingStatus() {
        return true;
    }

    @Override
    public boolean openCraftingStatus(Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                      Store<EntityStore> store) {
        if (!isOnline() || !checkAccess(playerRef)) {
            return false;
        }
        CraftingAccess.openStatus(player, playerRef, ref, store, nets, x, y, z);
        return true;
    }

    private static String keyId(AEKey key) {
        return key.toString();
    }
}
