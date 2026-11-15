/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.storage.SpatialStorageCellItem#appendHoverText).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.spatial.SpatialPlotInfo;
import de.fellurion.ae2.core.spatial.SpatialStorageCell;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import javax.annotation.Nullable;

/**
 * Spatial-Zellen als Hytale-Items: die Plot-Kennung und die gespeicherte Größe ({@link SpatialPlotInfo}) stehen als
 * JSON in den Item-Metadaten unter {@link #KEY}. Der Tooltip zeigt wie in AE2 die Seriennummer und die gespeicherte
 * Größe, bei einer unbenutzten Zelle „unformatiert“ und das Fassungsvermögen.
 */
final class SpatialCells {

    /** Metadaten-Schlüssel der Plot-Bindung. */
    static final String KEY = "AE2Spatial";

    private static final String COLOR_VALUE = "#9ec47a";
    private static final String COLOR_MUTED = "#868a85";

    private SpatialCells() {
    }

    static boolean isCell(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && SpatialStorageCell.isSpatialCell(stack.getItemId());
    }

    /** Plot-Bindung der Zelle, oder null (unformatiert). */
    @Nullable
    static SpatialPlotInfo info(ItemStack stack) {
        return SpatialPlotInfo.fromJson(stack.getFromMetadataOrNull(KEY, Codec.STRING));
    }

    /** Die Zelle mit eingetragener Plot-Bindung und passendem Tooltip (AE2: {@code setStoredDimension}). */
    static ItemStack withInfo(ItemStack stack, SpatialPlotInfo info) {
        return ItemTooltips.refresh(stack.withMetadata(KEY, Codec.STRING, info.toJson()));
    }

    /** Zusatzzeilen des Tooltips ({@link ItemTooltips#addSection}). */
    @Nullable
    static Message tooltip(ItemStack stack) {
        int max = SpatialStorageCell.getMaxStoredDim(stack.getItemId());
        if (max <= 0) {
            return null;
        }
        SpatialPlotInfo info = info(stack);
        if (info == null) {
            return Message.empty()
                    .insert(Message.translation("ae2spatial.cell.unformatted").color(COLOR_MUTED))
                    .insert(Message.raw("\n"))
                    .insert(Message.translation("ae2spatial.cell.capacity").param("size", max).color(COLOR_VALUE));
        }
        return Message.empty()
                .insert(Message.translation("ae2spatial.cell.serial").param("serial", info.serialNumber())
                        .color(COLOR_VALUE))
                .insert(Message.raw("\n"))
                .insert(Message.translation("ae2spatial.cell.stored").param("x", info.sizeX())
                        .param("y", info.sizeY()).param("z", info.sizeZ()).color(COLOR_VALUE));
    }
}
