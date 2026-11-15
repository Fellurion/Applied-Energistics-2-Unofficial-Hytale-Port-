/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.server.core.prefab.selection.buffer.BinaryPrefabBufferCodec;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.PrefabBuffer;
import de.fellurion.ae2.core.spatial.SpatialStoragePlot;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Datei eines Plots: {@code plot-<id>.dat}.
 *
 * <pre>
 *   "AE2SPLOT"            8 Bytes Kennung
 *   int                   Formatversion (1)
 *   int n, n Bytes        Kopf als JSON (Kennung, Größe, Besitzer, letzter Transfer – wie in plots.json)
 *   gzip-Strom            die Blöcke im Binärformat von Hytales Prefabs ({@link BinaryPrefabBufferCodec})
 * </pre>
 *
 * <p>Der Inhalt nutzt bewusst Hytales eigenes Prefab-Format statt eines eigenen: Blöcke und Flüssigkeiten stehen mit
 * ihrem Namen darin (Indizes ändern sich mit den installierten Mods), umbenannte Blöcke werden beim Lesen über
 * Hytales Block-Migrationen angepasst, und Block-Entities werden mit dem Codec der Engine geschrieben – mit allen
 * Bauteilen, auch denen anderer Plugins und solchen, deren Plugin gerade fehlt. Koordinaten sind relativ zur
 * Ecke des Plots (0 .. Größe-1); Luft ohne Flüssigkeit wird nicht gespeichert.
 */
final class PlotFile {

    private static final byte[] MAGIC = "AE2SPLOT".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int MAX_HEADER = 1 << 20;

    private PlotFile() {
    }

    /**
     * Schreibt den Plot vollständig in {@code file} und zwingt ihn auf die Platte. Der Aufrufer benennt danach um.
     *
     * @return Größe der Datei in Bytes
     */
    static long write(Path file, SpatialStoragePlot plot, PrefabBuffer content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            OutputStream raw = new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16);
            DataOutputStream head = new DataOutputStream(raw);
            byte[] json = plot.toJson().getBytes(StandardCharsets.UTF_8);
            head.write(MAGIC);
            head.writeInt(VERSION);
            head.writeInt(json.length);
            head.write(json);
            head.flush();
            GZIPOutputStream gzip = new GZIPOutputStream(raw, 1 << 16) {
                {
                    def.setLevel(Deflater.BEST_SPEED);
                }
            };
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(gzip, 1 << 16));
            BinaryPrefabBufferCodec.INSTANCE.serialize(content, out);
            out.flush();
            gzip.finish();
            raw.flush();
            channel.force(true);
            return channel.size();
        }
    }

    /** Liest nur den Kopf; null, wenn die Datei keine Plot-Datei ist. */
    @Nullable
    static SpatialStoragePlot readHeader(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return header(in);
        }
    }

    @Nullable
    private static SpatialStoragePlot header(DataInputStream in) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            return null;
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported plot file version " + version);
        }
        int length = in.readInt();
        if (length < 0 || length > MAX_HEADER) {
            throw new IOException("damaged plot file header");
        }
        byte[] json = new byte[length];
        in.readFully(json);
        return SpatialStoragePlot.fromJson(new String(json, StandardCharsets.UTF_8));
    }

    /**
     * Liest den Inhalt eines Plots.
     *
     * @throws IOException wenn die Datei fehlt, beschädigt ist oder nicht zu {@code expected} gehört
     */
    static PrefabBuffer read(Path file, SpatialStoragePlot expected) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            SpatialStoragePlot head = header(new DataInputStream(raw));
            if (head == null) {
                throw new IOException("not a spatial plot file");
            }
            if (head.getId() != expected.getId()
                    || !head.hasSize(expected.getSizeX(), expected.getSizeY(), expected.getSizeZ())) {
                throw new IOException("plot file holds plot " + head.getId() + " (" + head.getSizeX() + "x"
                        + head.getSizeY() + "x" + head.getSizeZ() + "), expected plot " + expected.getId());
            }
            byte[] data;
            try (GZIPInputStream gzip = new GZIPInputStream(raw, 1 << 16)) {
                data = gzip.readAllBytes();
            }
            try {
                return BinaryPrefabBufferCodec.INSTANCE.deserialize(ByteBuffer.wrap(data));
            } catch (RuntimeException e) {
                throw new IOException("damaged plot content", e);
            }
        }
    }
}
