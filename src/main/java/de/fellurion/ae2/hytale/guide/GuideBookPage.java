/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.guide;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.machines.Ingredient;
import de.fellurion.ae2.core.machines.InscriberRecipe;
import de.fellurion.ae2.hytale.device.MachineDevice;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Das aufgeschlagene Buch „AE2 Basics“: zwei Seiten nebeneinander, Blättern mit den Pfeilen, Inhaltsverzeichnis mit
 * Sprüngen in die Kapitel. Jedes Umblättern raschelt (Papiergeräusch) und baut die Seite neu auf.
 *
 * <p>Alle Texte sind fertige Zeichenketten in der AE2-Sprache des Spielers – eine Meldung ({@code Message}) in
 * {@code .Text} würde den Client zum Absturz bringen. Das Buch merkt sich je Spieler die zuletzt offene Doppelseite
 * (bis zum Neustart des Servers), wie ein Lesezeichen.
 */
final class GuideBookPage extends InteractiveCustomUIPage<GuideBookPage.Payload> {

    private static final String PAGE = "Pages/AE2/Book.ui";
    private static final String TITLE = "Pages/AE2/BookTitle.ui";
    private static final String HEADING = "Pages/AE2/BookHeading.ui";
    private static final String SUBHEADING = "Pages/AE2/BookSubheading.ui";
    private static final String TEXT = "Pages/AE2/BookText.ui";
    private static final String PICTURE = "Pages/AE2/BookPicture.ui";
    private static final String RECIPE = "Pages/AE2/BookRecipe.ui";
    private static final String LINK = "Pages/AE2/BookLink.ui";
    private static final String TURN_SOUND = "SFX_Drag_Items_Paper";
    private static final int PICTURE_CELL = 80;
    private static final int PICTURE_CELLS = 5;

    /** Zuletzt offene Doppelseite je Spieler (Lesezeichen). */
    private static final Map<UUID, Integer> BOOKMARKS = new ConcurrentHashMap<>();

    private int spread;

    GuideBookPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.spread = BOOKMARKS.getOrDefault(playerRef.getUuid(), 0);
    }

    private String lang() {
        return AE2Language.of(playerRef);
    }

    private GuideBook.Layout layout(String l) {
        return GuideBook.layout(GuideBook.chapters(MachineDevice.inscriberRecipes()), key -> TerminalTexts.tr(l, key));
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        String l = lang();
        GuideBook.Layout book = layout(l);
        spread = Math.max(0, Math.min(spread, book.spreads() - 1));
        BOOKMARKS.put(playerRef.getUuid(), spread);

        cmd.append(PAGE);
        cmd.set("#Contents.Text", TerminalTexts.tr(l, "ae2guide.book.contentsButton"));
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Prev", EventData.of("Action", "Prev"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Next", EventData.of("Action", "Next"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Contents", EventData.of("Action", "Contents"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Close", EventData.of("Action", "Close"), false);
        cmd.set("#Prev.Visible", spread > 0);
        cmd.set("#Next.Visible", spread < book.spreads() - 1);
        cmd.set("#Contents.Visible", spread > 0);

        int left = spread * 2;
        fillPage(cmd, evt, "#LeftPage", book.page(left), book, l);
        fillPage(cmd, evt, "#RightPage", book.page(left + 1), book, l);
        cmd.set("#LeftNumber.Text", left == 0 ? "" : Integer.toString(left + 1));
        cmd.set("#RightNumber.Text", left + 1 < book.pages().size() ? Integer.toString(left + 2) : "");
    }

    private void fillPage(UICommandBuilder cmd, UIEventBuilder evt, String page, List<GuideBook.Block> blocks,
                          GuideBook.Layout book, String l) {
        for (int i = 0; i < blocks.size(); i++) {
            String sel = page + "[" + i + "]";
            switch (blocks.get(i)) {
                case GuideBook.Title t -> {
                    cmd.append(page, TITLE);
                    cmd.set(sel + " #Title.Text", TerminalTexts.tr(l, t.titleKey()));
                    cmd.set(sel + " #Subtitle.Text", TerminalTexts.tr(l, t.subtitleKey()));
                }
                case GuideBook.Heading h -> {
                    cmd.append(page, HEADING);
                    cmd.set(sel + " #Text.Text", TerminalTexts.tr(l, h.key()));
                }
                case GuideBook.Subheading h -> {
                    cmd.append(page, SUBHEADING);
                    cmd.set(sel + " #Text.Text", TerminalTexts.tr(l, h.key()));
                }
                case GuideBook.Text t -> {
                    cmd.append(page, TEXT);
                    cmd.set(sel + " #Text.Text", TerminalTexts.tr(l, t.key()));
                    Anchor a = new Anchor();
                    a.setHeight(Value.of(t.height(key -> TerminalTexts.tr(l, key))));
                    cmd.setObject(sel + ".Anchor", a);
                }
                case GuideBook.Picture p -> picture(cmd, page, sel, p, l);
                case GuideBook.Recipe r -> recipe(cmd, page, sel, r.recipe(), l);
                case GuideBook.Link link -> {
                    cmd.append(page, LINK);
                    int target = book.chapterPages().get(link.chapter());
                    cmd.set(sel + " #Link.Text", TerminalTexts.tr(l, book.chapters().get(link.chapter()).titleKey()));
                    cmd.set(sel + " #Page.Text", Integer.toString(target + 1));
                    evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Link",
                            new EventData().append("Action", "Goto").append("Page", Integer.toString(target)), false);
                }
            }
        }
    }

    private static void picture(UICommandBuilder cmd, String page, String sel, GuideBook.Picture p, String l) {
        cmd.append(page, PICTURE);
        List<String> ids = p.itemIds();
        Anchor pad = new Anchor();
        pad.setWidth(Value.of((PICTURE_CELLS - ids.size()) * PICTURE_CELL / 2));
        cmd.setObject(sel + " #Pad.Anchor", pad);
        for (int i = 0; i < ids.size(); i++) {
            cmd.set(sel + " #Cell" + i + ".Visible", true);
            cmd.set(sel + " #Icon" + i + ".ItemId", ids.get(i));
            cmd.set(sel + " #Caption" + i + ".Text", TerminalTexts.itemName(l, ids.get(i)));
        }
    }

    private static void recipe(UICommandBuilder cmd, String page, String sel, InscriberRecipe r, String l) {
        cmd.append(page, RECIPE);
        slot(cmd, sel + " #Top", r.top());
        slot(cmd, sel + " #Middle", r.middle());
        slot(cmd, sel + " #Bottom", r.bottom());
        slot(cmd, sel + " #Output", r.output());
        String out = TerminalTexts.itemName(l, r.output().itemId());
        cmd.set(sel + " #Name.Text", r.output().quantity() > 1 ? r.output().quantity() + "× " + out : out);
        cmd.set(sel + " #Line1.Text", TerminalTexts.tr(l, "ae2guide.inscriber.top", "item", name(r.top(), l)));
        cmd.set(sel + " #Line2.Text", TerminalTexts.tr(l, "ae2guide.inscriber.middle", "item", name(r.middle(), l)));
        cmd.set(sel + " #Line3.Text", TerminalTexts.tr(l, "ae2guide.inscriber.bottom", "item", name(r.bottom(), l)));
        cmd.set(sel + " #Note.Text", TerminalTexts.tr(l, r.mode() == InscriberRecipe.ProcessType.INSCRIBE
                ? (r.top() == null && r.bottom() == null ? "ae2guide.inscriber.noPress" : "ae2guide.inscriber.kept")
                : "ae2guide.inscriber.consumed"));
    }

    private static void slot(UICommandBuilder cmd, String selector, @Nullable Ingredient ingredient) {
        if (ingredient != null && ingredient.itemId() != null) {
            cmd.set(selector + ".ItemId", ingredient.itemId());
        }
    }

    private static String name(@Nullable Ingredient ingredient, String l) {
        if (ingredient == null) {
            return "–";
        }
        String name = ingredient.itemId() != null ? TerminalTexts.itemName(l, ingredient.itemId()) : ingredient.resourceTypeId();
        return ingredient.quantity() > 1 ? ingredient.quantity() + "× " + name : name;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (p.action == null) {
            return;
        }
        int before = spread;
        switch (p.action) {
            case "Prev" -> spread--;
            case "Next" -> spread++;
            case "Contents" -> spread = 0;
            case "Goto" -> {
                try {
                    spread = Integer.parseInt(p.page) / 2;
                } catch (RuntimeException ignored) {
                    return;
                }
            }
            case "Close" -> {
                close();
                return;
            }
            default -> {
                return;
            }
        }
        int max = layout(lang()).spreads() - 1;
        spread = Math.max(0, Math.min(spread, max));
        if (spread == before) {
            return;
        }
        BOOKMARKS.put(playerRef.getUuid(), spread);
        try {
            SoundUtil.playSoundEvent2dToPlayer(playerRef, TURN_SOUND, SoundCategory.UI);
        } catch (RuntimeException ignored) {
            // ohne Geräusch weiterblättern
        }
        rebuild();
    }

    /** Daten eines Klicks im Buch. */
    public static final class Payload {
        String action;
        String page;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Page", Codec.STRING), (o, v) -> o.page = v, o -> o.page).add()
                .build();
    }
}
