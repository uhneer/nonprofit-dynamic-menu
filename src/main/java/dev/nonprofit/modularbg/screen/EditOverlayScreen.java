package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.Hints;
import dev.nonprofit.modularbg.background.IconStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import dev.nonprofit.modularbg.background.NonprofitMusic;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one edit screen for a background: a live, full-size preview of its title screen where every
 * element is directly editable in place.
 *
 *  - hover an element → dotted outlines around its icon and its text, plus a compact control strip
 *  - click the ICON → icon editor (choose image / icon database / reset)
 *  - click the TEXT → font picker (installed fonts / add file / font database)
 *  - DRAG an element anywhere → custom position, snapped to an 8 px grid (saved per background,
 *    travels with exported skins); "Reset positions" puts the layout back
 *  - control strip: A− A+ font size · I− I+ icon size · ✎ rename label · 👁 hide/show
 *  - bottom bar: music, layout toggle, reset positions, done
 *
 * Everything writes to the background being PREVIEWED in the carousel (the edit target), never to
 * whichever background happens to be globally selected.
 */
public class EditOverlayScreen extends Screen {

    private static final int GRID = 8;

    private final Screen parent;
    private final String bgName;       // "" = Default
    private final String bgKey;

    private Map<String, TitleLayout.Box> boxes = new LinkedHashMap<>();
    // Per-slot hit regions, rebuilt every frame: [x,y,w,h] keyed by purpose.
    private final Map<String, int[]> iconHit = new LinkedHashMap<>();
    private final Map<String, int[]> labelHit = new LinkedHashMap<>();
    private final List<Object[]> controls = new ArrayList<>();   // [x,y,w,h, slot, action]

    // Drag state.
    private String dragSlot = null;
    private boolean dragged = false;
    private double dragOffX, dragOffY;
    private int dragX, dragY;          // live top-left while dragging

    public EditOverlayScreen(Screen parent, String bgName) {
        super(Text.literal("Edit — " + (bgName == null || bgName.isEmpty() ? "Default" : bgName)));
        this.parent = parent;
        this.bgName = bgName == null ? "" : bgName;
        this.bgKey = IconStore.keyFor(this.bgName);
        IconStore.setEditTarget(this.bgName);   // all store writes target the previewed skin
    }

    @Override
    protected void init() {
        boxes = TitleLayout.compute(bgKey, this.width, this.height);
        int cx = this.width / 2, by = this.height - 26;

        String music = NonprofitMusic.getMusicFor(musicKey());
        ButtonWidget mus = ButtonWidget.builder(Text.literal(music == null ? "♪ Music" : "♪ Replace"), b -> {
            String picked = NonprofitMusic.pickOgg();
            if (picked != null) NonprofitMusic.setMusicFor(musicKey(), picked);
            this.clearAndInit();
        }).dimensions(cx - 175, by, 70, 20).build();
        mus.setTooltip(Tooltip.of(Text.literal("Menu music for this background. Current: "
                + NonprofitMusic.display(music))));
        addDrawableChild(mus);

        ButtonWidget rem = ButtonWidget.builder(Text.literal("♪✕"), b -> {
            NonprofitMusic.removeMusicFor(musicKey());
            this.clearAndInit();
        }).dimensions(cx - 103, by, 24, 20).build();
        rem.active = music != null;
        rem.setTooltip(Tooltip.of(Text.literal("Remove this background's music")));
        addDrawableChild(rem);

        addDrawableChild(ButtonWidget.builder(layoutLabel(), b -> {
            FontStore.cycleLayout(bgKey);
            b.setMessage(layoutLabel());
            this.clearAndInit();
        }).dimensions(cx - 75, by, 110, 20).build());

        ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset positions"), b -> {
            FontStore.clearAllPositions(bgKey);
            this.clearAndInit();
        }).dimensions(cx + 39, by, 100, 20).build();
        reset.active = FontStore.hasCustomPositions(bgKey);
        reset.setTooltip(Tooltip.of(Text.literal("Put every element back where the layout has it")));
        addDrawableChild(reset);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + 143, by, 50, 20).build());
    }

    private String musicKey() { return bgName.isEmpty() ? "default" : bgName; }

    private Text layoutLabel() {
        return Text.literal("Layout: " + ("center".equals(FontStore.layoutFor(bgKey)) ? "Centered" : "Left"));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // The PREVIEWED background, full screen: live (video and all) when it's also the selected
        // one, else its static preview frame.
        boolean live = bgName.equals(NonprofitBackgrounds.getSelected())
                || (bgName.isEmpty() && NonprofitBackgrounds.isDefault());
        boolean drawn = false;
        if (live) drawn = BackgroundRenderer.draw(ctx);
        if (!drawn && !bgName.isEmpty()) {
            Identifier frame = NonprofitBackgrounds.previewFrame(bgName);
            if (frame != null) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0f, 0f,
                        this.width, this.height, this.width, this.height, 0xFFFFFFFF);
                drawn = true;
            }
        }
        if (!drawn) ctx.fill(0, 0, this.width, this.height, 0xFF000000);
        ctx.fill(0, 0, this.width, this.height, 0x55000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        var m = ctx.getMatrices();

        // Snap grid while dragging.
        if (dragSlot != null && dragged) {
            for (int gx = 0; gx < this.width; gx += GRID * 2)
                for (int gy = 0; gy < this.height; gy += GRID * 2)
                    ctx.fill(gx, gy, gx + 1, gy + 1, 0x40FFFFFF);
        }

        String hdr = this.title.getString();
        m.pushMatrix();
        m.translate(this.width / 2f, 10f);
        m.scale(1.4f, 1.4f);
        ctx.drawTextWithShadow(tr, hdr, -tr.getWidth(hdr) / 2, 0, 0xFFFFFFFF);
        m.popMatrix();
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§7drag to move §8• §7click an icon or a text to change it"),
                this.width / 2, 26, 0xFFFFFFFF);

        iconHit.clear();
        labelHit.clear();
        controls.clear();

        String layout = FontStore.layoutFor(bgKey);
        for (var e : boxes.entrySet()) {
            String slot = e.getKey();
            TitleLayout.Box b = e.getValue();
            int x = b.x(), y = b.y();
            if (slot.equals(dragSlot) && dragged) { x = dragX; y = dragY; }
            boolean hidden = FontStore.hiddenFor(bgKey, slot);
            boolean hov = mouseX >= x - 2 && mouseX < x + b.w() + 26 && mouseY >= y - 14
                       && mouseY < y + b.h() + 14;
            int alpha = hidden ? 0x59FFFFFF : 0xFFFFFFFF;

            if (slot.equals("version")) {
                Identifier brand = IconStore.resolved(bgKey, "version");
                if (brand != null)
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, brand, x, y, 0f, 0f,
                            b.w(), b.h(), b.w(), b.h(), alpha);
                iconHit.put(slot, new int[]{ x, y, b.w(), b.h() });
                dashed(ctx, x - 2, y - 2, b.w() + 4, b.h() + 4, hov ? 0xFFFFFF66 : 0x88FFFFFF);
            } else if (slot.equals("versiontag")) {
                MutableText ver = styled(slot, "Minecraft 1.21.11 © Mojang AB");
                float vs = b.fontScale() * FontStore.sizeFor(bgKey, slot);
                int w = (int) (tr.getWidth(ver) * vs), h = (int) (8 * vs);
                m.pushMatrix();
                m.translate((float) x, (float) y);
                m.scale(vs, vs);
                ctx.drawTextWithShadow(tr, ver, 0, 0, hidden ? 0x59FFFFFF : 0xCCFFFFFF);
                m.popMatrix();
                labelHit.put(slot, new int[]{ x, y, w, h });
                dashed(ctx, x - 2, y - 2, w + 4, h + 4, hov ? 0xFFFFFF66 : 0x88FFFFFF);
            } else {
                // A menu button: icon + (maybe) label, exactly like the title screen draws it.
                String label = FontStore.labelFor(bgKey, slot, TitleLayout.defaultLabel(slot, layout));
                boolean iconOnly = label.isEmpty();
                Identifier tex = IconStore.resolved(bgKey, slot);
                int is = Math.max(4, Math.round(b.iconSize() * FontStore.iconSizeFor(bgKey, slot)));
                int ix = iconOnly ? x + (b.w() - is) / 2 : x + 4;
                int iy = y + (b.h() - is) / 2;
                if (tex != null)
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, ix, iy, 0f, 0f, is, is, is, is, alpha);
                iconHit.put(slot, new int[]{ ix, iy, is, is });
                dashed(ctx, ix - 2, iy - 2, is + 4, is + 4, hov ? 0xFFFFFF66 : 0x66FFFFFF);

                if (!iconOnly) {
                    MutableText t = styled(slot, label);
                    float fs = b.fontScale() * FontStore.sizeFor(bgKey, slot);
                    int textW = (int) (tr.getWidth(t) * fs), textH = (int) (8 * fs);
                    int tx = x + 4 + is + 8, ty = y + (b.h() - textH) / 2;
                    m.pushMatrix();
                    m.translate((float) tx, (float) ty);
                    m.scale(fs, fs);
                    ctx.drawTextWithShadow(tr, t, 0, 0, alpha);
                    m.popMatrix();
                    labelHit.put(slot, new int[]{ tx, ty, textW, textH });
                    dashed(ctx, tx - 2, ty - 2, textW + 4, textH + 4, hov ? 0xFFFFFF66 : 0x66FFFFFF);
                }
            }

            if (hidden) {
                ctx.drawTextWithShadow(tr, Text.literal("§8(hidden)"), x + b.w() + 6, y + b.h() / 2 - 4,
                        0xFFFFFFFF);
            }

            // Compact control strip under the element while hovered (or while dragging it).
            if ((hov && dragSlot == null) || slot.equals(dragSlot)) drawControls(ctx, slot, b, x, y);
        }
    }

    /** A− A+ (font) · I− I+ (icon) · ✎ rename · 👁 hide — only the ones the slot supports. */
    private void drawControls(DrawContext ctx, String slot, TitleLayout.Box b, int x, int y) {
        var tr = this.textRenderer;
        List<Object[]> strip = new ArrayList<>();
        boolean isText = labelHit.containsKey(slot);
        boolean isIcon = iconHit.containsKey(slot) && !slot.equals("version");
        if (isText) {
            strip.add(new Object[]{ "A−", "text smaller", (Runnable) () -> FontStore.adjustSize(slot, -0.1f) });
            strip.add(new Object[]{ "A+", "text bigger", (Runnable) () -> FontStore.adjustSize(slot, +0.1f) });
        }
        if (isIcon) {
            strip.add(new Object[]{ "I−", "icon smaller", (Runnable) () -> FontStore.adjustIconSize(slot, -0.1f) });
            strip.add(new Object[]{ "I+", "icon bigger", (Runnable) () -> FontStore.adjustIconSize(slot, +0.1f) });
        }
        if (isText && !slot.equals("versiontag"))
            strip.add(new Object[]{ "✎", "rename this button", (Runnable) () -> rename(slot) });
        strip.add(new Object[]{ "👁", FontStore.hiddenFor(bgKey, slot) ? "show" : "hide",
                (Runnable) () -> FontStore.toggleHidden(slot) });

        int sy = y + b.h() + 6;
        if (sy + 14 > this.height - 30) sy = y - 20;          // flip above near the bottom bar
        int sx = Math.max(2, Math.min(x, this.width - strip.size() * 22 - 2));
        StringBuilder hint = new StringBuilder();
        for (Object[] c : strip) {
            String lbl = (String) c[0];
            int wBtn = 20;
            ctx.fill(sx, sy, sx + wBtn, sy + 13, 0xCC101018);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(lbl), sx + wBtn / 2, sy + 3, 0xFFFFFFFF);
            controls.add(new Object[]{ sx, sy, wBtn, 13, slot, c[2] });
            sx += wBtn + 2;
        }
        // Live size readout + attribution, drawn after the strip.
        String info = "§7×" + String.format(Locale.ROOT, "%.2f", FontStore.sizeFor(bgKey, slot))
                + (iconHit.containsKey(slot) && !slot.equals("version")
                    ? " §8icon ×" + String.format(Locale.ROOT, "%.2f", FontStore.iconSizeFor(bgKey, slot)) : "");
        ctx.drawTextWithShadow(tr, Text.literal(info), sx + 4, sy + 3, 0xFFFFFFFF);
        String about = FontStore.aboutFor(FontStore.fontFor(bgKey, slot));
        if (about != null)
            ctx.drawTextWithShadow(tr, Text.literal("§8" + about), sx + 4, sy + 14, 0xFFFFFFFF);
        if (hint.length() > 0) { /* reserved */ }
    }

    private void rename(String slot) {
        String cur = FontStore.labelFor(bgKey, slot, TitleLayout.defaultLabel(slot, FontStore.layoutFor(bgKey)));
        String nn = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_inputBox(
                "Rename button", "New label (leave empty for the default):", cur);
        if (nn != null) FontStore.setLabel(slot, nn);
    }

    private MutableText styled(String slot, String s) {
        MutableText t = Text.literal(s);
        Identifier f = FontStore.fontFor(bgKey, slot);
        if (f != null) t = t.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(f)));
        return t;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        // Control strip first.
        for (Object[] c : controls) {
            int x = (int) c[0], y = (int) c[1], w = (int) c[2], h = (int) c[3];
            if (mx >= x && mx < x + w && my >= y && my < y + h) {
                ((Runnable) c[5]).run();
                return true;
            }
        }
        // Then the elements: press starts a potential drag; release decides click vs drag.
        for (var e : boxes.entrySet()) {
            TitleLayout.Box b = e.getValue();
            int[] lh = labelHit.get(e.getKey());
            boolean inBody = (mx >= b.x() - 2 && mx < b.x() + b.w() + 2
                           && my >= b.y() - 2 && my < b.y() + b.h() + 2)
                    || (lh != null && mx >= lh[0] - 2 && mx < lh[0] + lh[2] + 2
                                   && my >= lh[1] - 2 && my < lh[1] + lh[3] + 2);
            if (inBody) {
                dragSlot = e.getKey();
                dragged = false;
                dragOffX = click.x() - b.x();
                dragOffY = click.y() - b.y();
                dragX = b.x();
                dragY = b.y();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragSlot != null) {
            int nx = (int) Math.round(click.x() - dragOffX);
            int ny = (int) Math.round(click.y() - dragOffY);
            if (!dragged && (Math.abs(nx - boxes.get(dragSlot).x()) > 3
                          || Math.abs(ny - boxes.get(dragSlot).y()) > 3)) dragged = true;
            if (dragged) {
                TitleLayout.Box b = boxes.get(dragSlot);
                dragX = Math.max(0, Math.min(this.width - b.w(), Math.round(nx / (float) GRID) * GRID));
                dragY = Math.max(0, Math.min(this.height - b.h(), Math.round(ny / (float) GRID) * GRID));
            }
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragSlot != null) {
            String slot = dragSlot;
            boolean moved = dragged;
            dragSlot = null;
            dragged = false;
            if (moved) {
                FontStore.setPos(slot, Math.round(dragX * 1000f / Math.max(1, this.width)),
                                       Math.round(dragY * 1000f / Math.max(1, this.height)));
                boxes = TitleLayout.compute(bgKey, this.width, this.height);
                this.clearAndInit();   // refresh "Reset positions" enabled state
            } else {
                openEditorFor(slot, (int) click.x(), (int) click.y());
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    /** A plain click on an element: the text region opens the font picker, the icon the icon editor. */
    private void openEditorFor(String slot, int mx, int my) {
        int[] lh = labelHit.get(slot);
        int[] ih = iconHit.get(slot);
        boolean onLabel = lh != null && mx >= lh[0] - 2 && mx < lh[0] + lh[2] + 2
                       && my >= lh[1] - 2 && my < lh[1] + lh[3] + 2;
        boolean onIcon = ih != null && mx >= ih[0] - 2 && mx < ih[0] + ih[2] + 2
                      && my >= ih[1] - 2 && my < ih[1] + ih[3] + 2;
        if (onLabel || (lh != null && !onIcon)) {
            String sample = slot.equals("versiontag") ? "Minecraft 1.21.11 © Mojang AB"
                    : FontStore.labelFor(bgKey, slot, TitleLayout.defaultLabel(slot, FontStore.layoutFor(bgKey)));
            this.client.setScreen(new FontPickScreen(this, slot, sample));
        } else if (ih != null) {
            TitleLayout.Box b = boxes.get(slot);
            String label = slot.equals("version") ? "Brand bar"
                    : FontStore.labelFor(bgKey, slot, TitleLayout.defaultLabel(slot, FontStore.layoutFor(bgKey)));
            int dw = slot.equals("version") ? b.w() : b.iconSize();
            int dh = slot.equals("version") ? b.h() : b.iconSize();
            this.client.setScreen(new IconEditScreen(this, slot,
                    label.isEmpty() ? slot : label, dw, dh));
        }
    }

    private static void dashed(DrawContext c, int x, int y, int w, int h, int col) {
        int d = 3, g = 3;
        for (int i = 0; i < w; i += d + g) { int e = Math.min(i + d, w); c.fill(x + i, y, x + e, y + 1, col); c.fill(x + i, y + h - 1, x + e, y + h, col); }
        for (int i = 0; i < h; i += d + g) { int e = Math.min(i + d, h); c.fill(x, y + i, x + 1, y + e, col); c.fill(x + w - 1, y + i, x + w, y + e, col); }
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
