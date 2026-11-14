/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Block-Entity-Komponente {@code AE2_Block}.
 *
 * <p>Statische Felder ({@code Kind}, {@code CableType}, ...) kommen aus dem Item-JSON,
 * Laufzeitfelder ({@code Energy}, {@code Config}, {@code Data}) werden vom Plugin geschrieben und
 * mit dem Chunk gespeichert.
 */
public final class AE2BlockComponent implements Component<ChunkStore> {

    public static final String ID = "AE2_Block";

    public static final BuilderCodec<AE2BlockComponent> CODEC = BuilderCodec
            .builder(AE2BlockComponent.class, AE2BlockComponent::new)
            .append(new KeyedCodec<>("Kind", Codec.STRING), (o, v) -> o.kind = v, o -> o.kind).add()
            .append(new KeyedCodec<>("CableType", Codec.STRING), (o, v) -> o.cableType = v, o -> o.cableType).add()
            .append(new KeyedCodec<>("Color", Codec.STRING), (o, v) -> o.color = v, o -> o.color).add()
            .append(new KeyedCodec<>("Bytes", Codec.INTEGER), (o, v) -> o.bytes = v, o -> o.bytes).add()
            .append(new KeyedCodec<>("TunnelType", Codec.STRING), (o, v) -> o.tunnelType = v, o -> o.tunnelType).add()
            .append(new KeyedCodec<>("Grade", Codec.STRING), (o, v) -> o.grade = v, o -> o.grade).add()
            .append(new KeyedCodec<>("Energy", Codec.DOUBLE), (o, v) -> o.energy = v, o -> o.energy).add()
            .append(new KeyedCodec<>("Priority", Codec.INTEGER), (o, v) -> o.priority = v, o -> o.priority).add()
            .append(new KeyedCodec<>("Config", new MapCodec<>(Codec.STRING, HashMap::new, false)),
                    (o, v) -> o.config = v == null ? new HashMap<>() : new HashMap<>(v), o -> o.config).add()
            .append(new KeyedCodec<>("Data", Codec.STRING), (o, v) -> o.data = v, o -> o.data).add()
            .build();

    private static ComponentType<ChunkStore, AE2BlockComponent> componentType;

    public static ComponentType<ChunkStore, AE2BlockComponent> getComponentType() {
        return componentType;
    }

    static void setComponentType(ComponentType<ChunkStore, AE2BlockComponent> type) {
        componentType = type;
    }

    // --- aus dem Item-JSON ---
    private String kind = "";
    private String cableType;
    private String color;
    private Integer bytes;
    private String tunnelType;
    private String grade;

    // --- Laufzeitdaten (persistiert) ---
    private Double energy;
    private Integer priority;
    private Map<String, String> config = new HashMap<>();
    private String data;

    public AE2BlockComponent() {
    }

    private AE2BlockComponent(AE2BlockComponent o) {
        this.kind = o.kind;
        this.cableType = o.cableType;
        this.color = o.color;
        this.bytes = o.bytes;
        this.tunnelType = o.tunnelType;
        this.grade = o.grade;
        this.energy = o.energy;
        this.priority = o.priority;
        this.config = new HashMap<>(o.config);
        this.data = o.data;
    }

    @Override
    public Component<ChunkStore> clone() {
        return new AE2BlockComponent(this);
    }

    public String kind() {
        return kind == null ? "" : kind;
    }

    public String cableType() {
        return cableType;
    }

    public String color() {
        return color == null ? "fluix" : color;
    }

    public int bytes() {
        return bytes == null ? 0 : bytes;
    }

    public String tunnelType() {
        return tunnelType;
    }

    public String grade() {
        return grade;
    }

    public double energy() {
        return energy == null ? 0.0 : energy;
    }

    public void setEnergy(double value) {
        this.energy = value;
    }

    public int priority() {
        return priority == null ? 0 : priority;
    }

    public void setPriority(int value) {
        this.priority = value;
    }

    public Map<String, String> config() {
        return config;
    }

    public String data() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}
