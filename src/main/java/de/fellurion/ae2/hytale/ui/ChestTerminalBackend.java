/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.storage.MEChestBlockEntity als ITerminalHost, appeng.menu.me.items.BasicCellChestMenu).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.NullStorage;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Terminal der ME Chest: zeigt und bedient wie in AE2 nur die Zelle, die in der Truhe steckt – nicht den Rest des
 * Netzes. Die Zelle bleibt daneben mit der Priorität der Truhe im Netz eingehängt; was hier ein- oder ausgelagert
 * wird, sieht das Netz sofort.
 * <ul>
 *   <li>Lager: die eingehängte Zelle der Truhe ({@code MEChestBlockEntity.getInventory()}).</li>
 *   <li>Energie: aus dem Netz der Truhe, 1 AE je Item ({@code MEChestBlockEntity.extractAEPower}).</li>
 *   <li>Bedienbar, sobald eine Zelle drin ist und das Netz Strom hat – ein Kanal ist dafür nicht nötig („The Chest
 *       GUI works independently of the grid the chest may be connected to“); im Netz sichtbar ist die Zelle dagegen
 *       nur mit Kanal.</li>
 * </ul>
 * Abweichungen von AE2: Die Truhe hat keinen eigenen Puffer von 500 AE und nimmt keine Fremdenergie an – sie läuft
 * nur an einem versorgten Netz (ein Energiespeicher oder Generator direkt daneben genügt). Herstellbare Items des
 * Netzes und der Crafting-Status erscheinen in der Truhe nicht.
 */
public final class ChestTerminalBackend extends NetworkTerminalBackend {

    public ChestTerminalBackend(WorldNetworks nets, int x, int y, int z, Object player) {
        super(nets, x, y, z, player);
    }

    /** Die eingehängte Zelle der Truhe, oder null (keine Zelle, Truhe abgebaut). */
    @Nullable
    private MEStorage cell() {
        WorldNetworks.Node n = nets.node(x, y, z);
        if (n == null || !(n.device() instanceof CellHostDevice host) || host.logic().getSlots() < 1) {
            return null;
        }
        return host.logic().getCellInventory(0);
    }

    @Override
    public boolean isOnline() {
        GridNode g = node();
        return g != null && g.getGrid() != null && g.isPowered() && cell() != null;
    }

    @Override
    protected MEStorage storage(Grid grid) {
        MEStorage cell = cell();
        return cell == null ? NullStorage.of() : cell;
    }

    @Override
    protected KeyCounter available(Grid grid) {
        MEStorage cell = cell();
        return cell == null ? new KeyCounter() : cell.getAvailableStacks();
    }

    @Override
    protected Set<AEItemKey> craftables() {
        return Set.of();
    }

    @Override
    public boolean requestCraft(String keyId, Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                Store<EntityStore> store) {
        return false;
    }

    @Override
    public boolean hasCraftingStatus() {
        return false;
    }

    @Override
    public boolean openCraftingStatus(Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                      Store<EntityStore> store) {
        return false;
    }
}
