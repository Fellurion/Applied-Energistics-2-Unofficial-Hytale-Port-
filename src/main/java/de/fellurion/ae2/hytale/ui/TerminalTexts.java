/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.protocol.BoolParamValue;
import com.hypixel.hytale.protocol.DoubleParamValue;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.protocol.StringParamValue;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import de.fellurion.ae2.hytale.fluid.FluidConfig;
import de.fellurion.ae2.hytale.fluid.FluidContainers;
import de.fellurion.ae2.hytale.fluid.FluidText;
import de.fellurion.ae2.hytale.fluid.FluidTypes;
import de.fellurion.ae2.hytale.setsys.SetSysItems;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texte und Anzeige-Stacks der Terminal-Seiten.
 *
 * <p>Übersetzt wird auf dem Server (über das I18n-Modul, Sprachdateien {@code Server/Languages/<locale>/ae2ui.lang}),
 * damit die FTCore-Schale die dort gewählte Sprache des Spielers zeigt und Gitter-Beschreibungen (reine Zeichenketten)
 * übersetzt ankommen.
 *
 * <p>Gegenstände gehen <b>ohne Metadaten</b> an den Client: In einer eigenen Seite bricht der Client an
 * {@code ClientItemMetadata} und an übersteuerter Qualität die Verbindung ab (in FTCore nachgewiesen). Die Beschreibung
 * aus den Metadaten (z. B. Set-Block und Seelenbund von SetSys) kommt stattdessen als Beschreibung des Gitterfelds.
 */
public final class TerminalTexts {

    /** Farben wie in SetSys/FTCore, damit die Zusatzzeilen zum übrigen Tooltip passen. */
    static final String COLOR_INFO = "#9ec47a";
    static final String COLOR_SOUL = "#b06cff";
    static final String COLOR_MUTED = "#868a85";
    static final String COLOR_BAD = "#e0796b";

    private static final Map<String, Map<String, String>> NAMES = new ConcurrentHashMap<>();

    private TerminalTexts() {
    }

    /** Übersetzt {@code key} (voll, z. B. {@code ae2ui.terminal.empty}); Platzhalter {@code {name}} werden ersetzt. */
    public static String tr(String locale, String key, Object... params) {
        String text = null;
        try {
            I18nModule i18n = I18nModule.get();
            text = i18n == null ? null : i18n.getMessage(locale, key);
        } catch (RuntimeException ignored) {
            // I18n noch nicht bereit – Schlüssel zeigen
        }
        if (text == null) {
            text = key;
        }
        for (int i = 0; i + 1 < params.length; i += 2) {
            text = text.replace("{" + params[i] + "}", String.valueOf(params[i + 1]));
        }
        return text;
    }

    /** Übersetzter Itemname (für Suche und Namenssortierung), ersatzweise aus der ItemId abgeleitet. */
    public static String itemName(String locale, String itemId) {
        return NAMES.computeIfAbsent(locale, l -> new ConcurrentHashMap<>()).computeIfAbsent(itemId, id -> {
            try {
                Item item = Item.getAssetMap().getAsset(id);
                I18nModule i18n = I18nModule.get();
                if (item != null && i18n != null) {
                    String name = i18n.getMessage(locale, item.getTranslationKey());
                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                }
            } catch (RuntimeException ignored) {
                // Asset/I18n nicht verfügbar
            }
            int i = id.indexOf('_');
            return (i >= 0 ? id.substring(i + 1) : id).replace('_', ' ');
        });
    }

    /** Kopie für die Anzeige: ohne Metadaten und ohne übersteuerte Qualität, Haltbarkeit bleibt. */
    public static ItemStack displayStack(ItemStack stack, int quantity) {
        return new ItemStack(stack.getItemId(), Math.max(1, quantity), stack.getDurability(), stack.getMaxDurability(),
                null);
    }

    /** Max. Stapelgröße eines Items (mind. 1). */
    public static int maxStack(ItemStack stack) {
        try {
            Item item = stack.getItem();
            return item == null ? 64 : Math.max(1, item.getMaxStack());
        } catch (RuntimeException e) {
            return 64;
        }
    }

    /**
     * Beschreibung eines Gegenstands wie im Inventar: eigene Beschreibung aus den Metadaten (SetSys-Set-Block,
     * Seelenwerte …) oder die des Item-Assets. Leer, wenn es keine gibt.
     */
    public static String itemDescription(ItemStack stack, String locale) {
        try {
            ItemDisplayMetadata display = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
            if (display != null && display.getDescription() != null) {
                return flatten(display.getDescription(), locale);
            }
            Item item = stack.getItem();
            I18nModule i18n = I18nModule.get();
            if (item != null && i18n != null) {
                String d = i18n.getMessage(locale, item.getDescriptionTranslationKey());
                return d == null ? "" : d;
            }
        } catch (RuntimeException ignored) {
            // kaputte Metadaten: ohne Beschreibung anzeigen
        }
        return "";
    }

    /** Anzeigename eines Netz-Eintrags (Item oder Flüssigkeit) für Suche und Sortierung. */
    public static String entryName(TerminalBackend.Entry e, String locale) {
        return e.fluid() && e.fluidId() != null ? FluidTypes.name(locale, e.fluidId())
                : itemName(locale, e.display().getItemId());
    }

    /**
     * Beschreibung einer Flüssigkeit im Netz: Name, Menge in Eimern und wie man sie abfüllt (AE2: Klick mit einem
     * Behälter füllt ihn) – oder dass es für sie keinen Behälter gibt.
     */
    public static String fluidDescription(TerminalBackend.Entry e, String locale) {
        String fluidId = e.fluidId() == null ? "" : e.fluidId();
        StringBuilder sb = new StringBuilder();
        line(sb, COLOR_INFO, tr(locale, "ae2fluid.terminal.tooltip.fluid", "fluid", FluidTypes.name(locale, fluidId)));
        line(sb, COLOR_INFO, tr(locale, "ae2fluid.terminal.tooltip.stored", "amount", FluidText.full(e.amount(), locale),
                "mb", grouped(e.amount(), locale)));
        FluidConfig.ContainerSpec container = FluidContainers.forFluid(fluidId);
        line(sb, COLOR_MUTED, container == null ? tr(locale, "ae2fluid.terminal.tooltip.noContainer")
                : tr(locale, "ae2fluid.terminal.tooltip.fillHint", "container", itemName(locale, container.emptyItem())));
        return sb.toString();
    }

    /**
     * Zusatzzeile für einen gefüllten Behälter im Inventar (AE2: Rechtsklick mit dem Behälter leert ihn ins Netz);
     * leer, wenn {@code stack} kein gefüllter Behälter ist.
     */
    public static String containerHint(ItemStack stack, String locale) {
        FluidContainers.Content content = FluidContainers.contentOf(stack);
        if (content == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        line(sb, COLOR_MUTED, tr(locale, "ae2fluid.terminal.tooltip.emptyHint", "fluid",
                FluidTypes.name(locale, content.fluid().fluidId()), "amount", FluidText.full(content.amount(), locale)));
        return sb.toString();
    }

    /** Beschreibung eines Netz-Eintrags: Item-Beschreibung, darunter Menge, Seelenbund und Herstellbarkeit. */
    public static String entryDescription(TerminalBackend.Entry e, String locale) {
        if (e.fluid()) {
            return fluidDescription(e, locale);
        }
        StringBuilder sb = new StringBuilder(itemDescription(e.display(), locale));
        StringBuilder extra = new StringBuilder();
        if (e.amount() > 0) {
            line(extra, COLOR_INFO, tr(locale, "ae2ui.terminal.tooltip.stored", "amount", grouped(e.amount(), locale)));
        }
        SetSysItems.Bond bond = SetSysItems.bond(e.display());
        if (bond != null) {
            line(extra, COLOR_SOUL, bond.ownerName().isEmpty() ? tr(locale, "ae2ui.terminal.tooltip.soulbound")
                    : tr(locale, "ae2ui.terminal.tooltip.soulboundTo", "owner", bond.ownerName()));
        }
        if (e.craftable()) {
            line(extra, COLOR_INFO, tr(locale, "ae2ui.terminal.tooltip.craftable"));
        }
        line(extra, COLOR_MUTED, tr(locale, e.amount() > 0 ? "ae2ui.terminal.tooltip.takeHint"
                : "ae2ui.terminal.tooltip.craftHint"));
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        return sb.append(extra).toString();
    }

    /** Beschreibung eines Inventarfelds: wie im Inventar plus Seelenbund-Hinweis. */
    public static String slotDescription(ItemStack stack, String locale) {
        StringBuilder sb = new StringBuilder(itemDescription(stack, locale));
        SetSysItems.Bond bond = SetSysItems.bond(stack);
        if (bond != null && !bond.ownerName().isEmpty() && !sb.toString().contains(bond.ownerName())) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            line(sb, COLOR_SOUL, tr(locale, "ae2ui.terminal.tooltip.soulboundTo", "owner", bond.ownerName()));
        }
        return sb.toString();
    }

    static void line(StringBuilder sb, String color, String text) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append("<color is=\"").append(color).append("\">").append(escape(text)).append("</color>");
    }

    /** Spitze Klammern aus Spielernamen/Texten würden als Markup gelesen. */
    private static String escape(String s) {
        return s.replace("<", "‹").replace(">", "›");
    }

    /** AE2-Stil: max. 4 Zeichen (appeng.util.ReadableNumberConverter): 9999, 10k, 12.5k, 1M. */
    public static String formatAmount(long n) {
        if (n < 10_000) {
            return Long.toString(n);
        }
        String[] units = {"k", "M", "G", "T", "P", "E"};
        double v = n;
        int u = -1;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        String num = v >= 100 ? String.format(Locale.ROOT, "%.0f", Math.floor(v))
                : String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10).replace(".0", "");
        return num + units[u];
    }

    /** 1234567 -> "1,234,567" bzw. "1.234.567" (Tausendertrennung nach Sprache). */
    public static String grouped(long n, String locale) {
        Locale l = locale == null || locale.isBlank() ? Locale.ROOT : Locale.forLanguageTag(locale);
        return String.format(l, "%,d", n);
    }

    /**
     * Macht aus einer Nachricht Text mit dem Markup des Clients ({@code <color is="#…">}), wie FTCores ItemTooltip:
     * Übersetzungsschlüssel werden über das I18n-Modul aufgelöst.
     */
    static String flatten(@Nullable Message message, String locale) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, message, locale, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Message message, String locale, int depth) {
        if (depth > 16) {
            return;
        }
        String color = message.getColor();
        if (color != null) {
            sb.append("<color is=\"").append(color).append("\">");
        }
        String raw = message.getRawText();
        if (raw != null) {
            sb.append(raw);
        } else if (message.getMessageId() != null) {
            I18nModule i18n = I18nModule.get();
            String t = i18n == null ? null : i18n.getMessage(locale, message.getMessageId());
            sb.append(withParams(t == null ? message.getMessageId() : t, message, locale, depth));
        }
        for (Message child : message.getChildren()) {
            append(sb, child, locale, depth + 1);
        }
        if (color != null) {
            sb.append("</color>");
        }
    }

    /**
     * Setzt die Platzhalter einer übersetzten Nachricht ein ({@code {used}}, {@code {item}} …) – so, wie es sonst der
     * Client tut. Der Tooltip einer Zelle („{used} von {total} Bytes“, ihre Inhalte) besteht aus solchen Nachrichten.
     */
    private static String withParams(String text, Message message, String locale, int depth) {
        FormattedMessage f = text.indexOf('{') < 0 ? null : message.getFormattedMessage();
        if (f == null) {
            return text;
        }
        if (f.params != null) {
            for (Map.Entry<String, ParamValue> e : f.params.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", paramText(e.getValue()));
            }
        }
        if (f.messageParams != null) {
            for (Map.Entry<String, FormattedMessage> e : f.messageParams.entrySet()) {
                StringBuilder sub = new StringBuilder();
                if (e.getValue() != null) {
                    append(sub, new Message(e.getValue()), locale, depth + 1);
                }
                text = text.replace("{" + e.getKey() + "}", sub);
            }
        }
        return text;
    }

    private static String paramText(@Nullable ParamValue value) {
        return switch (value) {
            case StringParamValue v -> v.value == null ? "" : v.value;
            case IntParamValue v -> Integer.toString(v.value);
            case LongParamValue v -> Long.toString(v.value);
            case DoubleParamValue v -> Double.toString(v.value);
            case BoolParamValue v -> Boolean.toString(v.value);
            case null, default -> "";
        };
    }
}
