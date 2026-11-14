/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.ImportBusPart / ExportBusPart / StorageBusPart).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.automation.ExportBusLogic;
import de.fellurion.ae2.core.automation.ExternalStorageFacade;
import de.fellurion.ae2.core.automation.IOBusLogic;
import de.fellurion.ae2.core.automation.ImportBusLogic;
import de.fellurion.ae2.core.automation.StorageBusLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.HytaleSlots;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;

/**
 * Import-, Export- und Storage-Bus. Ziel ist der Container des Blocks, auf den die Vorderseite zeigt
 * (Truhe, AE2-Maschine, Hytale-Werkbank mit Slots ...). Das Ziel wird jede Sekunde neu gesucht.
 */
public final class BusDevice implements Device {

    private final String kind;
    @Nullable
    private final IOBusLogic ioBus;
    @Nullable
    private final StorageBusLogic storageBus;
    @Nullable
    private ItemContainer target;
    private boolean saveRequested;

    public BusDevice(String kind) {
        this.kind = kind;
        switch (kind) {
            case "import_bus" -> {
                ioBus = new ImportBusLogic();
                storageBus = null;
            }
            case "export_bus" -> {
                ioBus = new ExportBusLogic();
                storageBus = null;
            }
            default -> {
                ioBus = null;
                storageBus = new StorageBusLogic();
            }
        }
    }

    @Override
    public Object owner() {
        return ioBus != null ? ioBus : storageBus;
    }

    @Nullable
    public IOBusLogic ioBus() {
        return ioBus;
    }

    @Nullable
    public StorageBusLogic storageBus() {
        return storageBus;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        String json = node.comp.data();
        try {
            if (json != null && ioBus != null) {
                ioBus.fromJson(json);
            } else if (json != null && storageBus != null) {
                storageBus.fromJson(json);
            }
        } catch (RuntimeException ignored) {
            // kaputte Konfiguration: Standardwerte
        }
        if (ioBus != null) {
            ioBus.setSaveListener(() -> saveRequested = true);
        } else if (storageBus != null) {
            storageBus.setSaveListener(() -> saveRequested = true);
            storageBus.setPriority(node.comp.priority());
        }
        refreshTarget(nets, node);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (nets.tickCount() % 20 == (node.pos & 15)) {
            refreshTarget(nets, node);
        }
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            saveRequested = false;
            node.comp.setData(ioBus != null ? ioBus.toJson() : storageBus.toJson());
            node.markDirty();
        }
    }

    private void refreshTarget(WorldNetworks nets, WorldNetworks.Node node) {
        BlockAccess.Dir f = node.facing();
        ItemContainer c = null;
        if (f != null) {
            int tx = node.x() + f.dx, ty = node.y() + f.dy, tz = node.z() + f.dz;
            c = BlockAccess.container(nets.world(), tx, ty, tz);
            // Busse dürfen nicht in fremde AE2-Blöcke greifen (sonst zöge ein fremder Import-Bus Zellen aus dem Drive)
            if (c != null && !de.fellurion.ae2.hytale.security.AE2Security.deviceMayAccess(nets, node, tx, ty, tz)) {
                c = null;
            }
        }
        if (c == target) {
            return;
        }
        target = c;
        if (ioBus != null) {
            ioBus.setTarget(c == null ? null : ExternalStorageFacade.of(new HytaleSlots(c)));
        } else if (storageBus != null) {
            if (c == null) {
                storageBus.clearTarget();
            } else {
                storageBus.setTarget(ExternalStorageFacade.of(new HytaleSlots(c)));
            }
        }
    }

    public String kind() {
        return kind;
    }
}
