/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Die Blockinfo als eigenes HUD ({@link AE2Hud#HUD_KEY}, Dokument {@value #DOCUMENT}). Liegt neben den HUDs anderer
 * Plugins (CoreSys, FTCore-Leiste …), fängt keine Eingaben ab.
 *
 * <p>Gesendet wird nur, was sich gegenüber dem letzten Stand geändert hat: Blick auf denselben Block mit gleichen
 * Werten kostet kein Paket, Ausblenden ist ein einziges {@code Visible}.
 */
final class LookAtHud extends CustomUIHud {

    static final String DOCUMENT = "Hud/AE2/LookAt.ui";

    private static final String ROOT = "#AE2Look";
    private static final String PANEL = "#AE2LookPanel";
    private static final String STRIPE = "#AE2LookStripe";
    private static final String ICON = "#AE2LookIcon";
    private static final String NAME = "#AE2LookName";
    private static final String DOT = "#AE2LookDot";
    private static final String STATUS = "#AE2LookStatus";
    private static final String LINE = "#AE2LookLine";
    private static final String BAR = "#AE2LookBar";
    private static final String BAR_FILL = "#AE2LookBarFill";

    /** Stand beim Client (nach {@link #build} leer). */
    private boolean visible;
    @Nullable
    private LookInfo rendered;
    @Nullable
    private HudLayout layout;

    LookAtHud(@Nonnull PlayerRef playerRef) {
        super(playerRef, AE2Hud.HUD_KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(DOCUMENT);
        visible = false;
        rendered = null;
        layout = null;
    }

    boolean isVisible() {
        return visible;
    }

    /** Blendet die Anzeige aus (ein Paket, nur wenn sie zu sehen war). */
    void hide() {
        if (!visible) {
            return;
        }
        visible = false;
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set(ROOT + ".Visible", false);
        update(false, cmd);
    }

    /** Zeigt {@code info}; schickt nur die Unterschiede zum letzten Stand. */
    void show(LookInfo info) {
        UICommandBuilder cmd = new UICommandBuilder();
        boolean changed = write(cmd, info);
        if (!visible) {
            cmd.set(ROOT + ".Visible", true);
            visible = true;
            changed = true;
        }
        if (changed) {
            update(false, cmd);
        }
    }

    private boolean write(UICommandBuilder cmd, LookInfo next) {
        LookInfo prev = rendered;
        boolean changed = false;

        if (prev == null || !Objects.equals(prev.itemId(), next.itemId())) {
            boolean icon = next.itemId() != null;
            cmd.set(ICON + ".Visible", icon);
            if (icon) {
                cmd.set(ICON + ".ItemId", next.itemId());
            }
            changed = true;
        }
        if (prev == null || !prev.name().equals(next.name())) {
            cmd.set(NAME + ".Text", next.name());
            changed = true;
        }

        LookInfo.Span status = next.status();
        if (prev == null || !Objects.equals(prev.status(), status)) {
            if (status != null) {
                cmd.set(STATUS + ".Text", status.text());
                cmd.set(STATUS + ".Style.TextColor", status.color());
                cmd.setObject(DOT + ".Background", color(status.color()));
            }
            if (prev == null || prev.status() == null || status == null
                    || !prev.status().color().equals(status.color())) {
                cmd.setObject(STRIPE + ".Background", color(status == null ? HudColors.ACCENT : status.color()));
            }
            changed = true;
        }

        for (int i = 0; i < LookInfo.MAX_LINES; i++) {
            LookInfo.Line line = next.line(i);
            if (line != null && (prev == null || !line.equals(prev.line(i)))) {
                cmd.set(LINE + i + ".TextSpans", line.toMessage());
                changed = true;
            }
        }

        HudLayout nextLayout = HudLayout.of(next);
        HudLayout prevLayout = layout;
        if (!nextLayout.equals(prevLayout)) {
            applyLayout(cmd, nextLayout, prevLayout);
            layout = nextLayout;
            changed = true;
        }

        if (next.hasBar()) {
            boolean widthChanged = prevLayout == null || prevLayout.barWidth() != nextLayout.barWidth();
            if (prev == null || !prev.hasBar() || prev.bar() != next.bar() || widthChanged) {
                int w = (int) Math.round(next.bar() * nextLayout.barWidth());
                cmd.setObject(BAR_FILL + ".Anchor", anchor(0, 0, w, HudLayout.BAR_HEIGHT));
                changed = true;
            }
            if (prev == null || !prev.hasBar() || !prev.barColor().equals(next.barColor())) {
                cmd.setObject(BAR_FILL + ".Background", color(next.barColor()));
                changed = true;
            }
        }

        rendered = next;
        return changed;
    }

    /** Größe der Platte und Lage der Zeilen; Sichtbarkeit nur für Teile, die sich geändert haben. */
    private static void applyLayout(UICommandBuilder cmd, HudLayout l, @Nullable HudLayout prev) {
        Anchor panel = new Anchor();
        panel.setTop(Value.of(0));
        panel.setWidth(Value.of(l.width()));
        panel.setHeight(Value.of(l.height()));
        cmd.setObject(PANEL + ".Anchor", panel);

        if (prev == null || prev.status() != l.status()) {
            cmd.set(DOT + ".Visible", l.status());
            cmd.set(STATUS + ".Visible", l.status());
        }
        int prevLines = prev == null ? -1 : prev.lines();
        boolean shifted = prev == null || prev.status() != l.status();
        for (int i = 0; i < LookInfo.MAX_LINES; i++) {
            boolean show = i < l.lines();
            boolean shown = i < prevLines;
            if (show != shown || prev == null) {
                cmd.set(LINE + i + ".Visible", show);
            }
            if (show && (shifted || !shown)) {
                cmd.setObject(LINE + i + ".Anchor", row(l.lineTop(i)));
            }
        }
        if (prev == null || prev.bar() != l.bar()) {
            cmd.set(BAR + ".Visible", l.bar());
        }
        if (l.bar() && (prev == null || !prev.bar() || prev.barTop() != l.barTop())) {
            Anchor bar = new Anchor();
            bar.setLeft(Value.of(HudLayout.TEXT_LEFT));
            bar.setRight(Value.of(HudLayout.RIGHT_PAD));
            bar.setTop(Value.of(l.barTop()));
            bar.setHeight(Value.of(HudLayout.BAR_HEIGHT));
            cmd.setObject(BAR + ".Anchor", bar);
        }
    }

    /** Zeile über die ganze Textbreite. */
    private static Anchor row(int top) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(HudLayout.TEXT_LEFT));
        a.setRight(Value.of(HudLayout.RIGHT_PAD));
        a.setTop(Value.of(top));
        a.setHeight(Value.of(HudLayout.ROW_HEIGHT));
        return a;
    }

    private static Anchor anchor(int left, int top, int width, int height) {
        Anchor a = new Anchor();
        a.setLeft(Value.of(left));
        a.setTop(Value.of(top));
        a.setWidth(Value.of(width));
        a.setHeight(Value.of(height));
        return a;
    }

    /** Einfarbige Fläche (wie die Farbfelder der Trigger-Volume-Seite des Spiels). */
    private static PatchStyle color(String hex) {
        return new PatchStyle().setColor(Value.of(hex));
    }
}
