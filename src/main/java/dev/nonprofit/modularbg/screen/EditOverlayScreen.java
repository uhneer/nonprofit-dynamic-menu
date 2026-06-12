package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import dev.nonprofit.modularbg.background.FontStore;
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
 * Interaction model:
 *  - the ICON and the TEXT of an element are separate hover targets. Hovering one highlights ONLY
 *    its dotted outline (yellow) and shows a compact control strip for just that part — one strip
 *    at a time, drawn on top of everything, and it stays while your cursor is on it.
 *  - icon strip: − + (icon size) and 👁 hide; text strip: − + (text size), ✎ rename, 👁 hide.
 *    Every sub-button has a hover tooltip. The Options button can never be hidden.
 *  - hovering a part shows a click indicator; linger 2 seconds and a "click to change…" tip appears.
 *  - dragging is only possible in the CUSTOM layout (cycle the Layout button), with a toggleable
 *    snap-to-grid; positions save as you drop them and travel with exported skins.
 *
 * Everything writes to the background being PREVIEWED in the carousel, never to whichever
 * background happens to be globally selected.
 */
public class EditOverlayScreen extends Screen {

    private final Screen parent;
    private final String bgName;       // "" = Default
    private final String bgKey;

    private Map<String, TitleLayout.Box> boxes = new LinkedHashMap<>();
    // Per-part hit regions, rebuilt every frame: slot → [x,y,w,h].
    private final Map<String, int[]> iconHit = new LinkedHashMap<>();
    private final Map<String, int[]> labelHit = new LinkedHashMap<>();
    private final List<Object[]> controls = new ArrayList<>();   // [x,y,w,h, tooltip, action]

    // Active hover part ("slot|icon" / "slot|label"), its sticky strip rect, and linger timing.
    private String activePart = null;
    private int[] stripRect = null;
    private long hoverSince = 0L;

    // Drag state (custom layout only).
    private String dragSlot = null;
    private boolean dragged = false;
    private double dragOffX, dragOffY;
    private int dragX, dragY;

    public EditOverlayScreen(Screen parent, String bgName) {
        super(Text.literal("Edit — " + (bgName == null || bgName.isEmpty() ? "Default" : bgName)));
        this.parent = parent;
        this.bgName = bgName == null ? "" : bgName;
        this.bgKey = IconStore.keyFor(this.bgName);
        IconStore.setEditTarget(this.bgName);   // all store writes target the previewed skin
    }

    private boolean customLayout() { return "custom".equals(FontStore.layoutFor(bgKey)); }

    @Override
    protected void init() {
        boxes = TitleLayout.compute(bgKey, this.width, this.height);
        boolean custom = customLayout();
        int cx = this.width / 2, by = this.height - 26;

        String music = NonprofitMusic.getMusicFor(musicKey());
        ButtonWidget mus = ButtonWidget.builder(Text.literal(music == null ? "♪ Music" : "♪ Replace"), b -> {
            String picked = NonprofitMusic.pickOgg();
            if (picked != null) NonprofitMusic.setMusicFor(musicKey(), picked);
            this.clearAndInit();
        }).dimensions(cx - 199, by, 70, 20).build();
        mus.setTooltip(Tooltip.of(Text.literal("Menu music for this background. Current: "
                + NonprofitMusic.display(music))));
        addDrawableChild(mus);

        ButtonWidget rem = ButtonWidget.builder(Text.literal("♪✕"), b -> {
            NonprofitMusic.removeMusicFor(musicKey());
            this.clearAndInit();
        }).dimensions(cx - 127, by, 24, 20).build();
        rem.active = music != null;
        rem.setTooltip(Tooltip.of(Text.literal("Remove this background's music")));
        addDrawableChild(rem);

        ButtonWidget lay = ButtonWidget.builder(layoutLabel(), b -> {
            FontStore.cycleLayout(bgKey);
            this.clearAndInit();
        }).dimensions(cx - 99, by, 110, 20).build();
        lay.setTooltip(Tooltip.of(Text.literal(
                "Left column or Custom — in Custom you can drag every element wherever you want")));
        addDrawableChild(lay);

        if (custom) {
            ButtonWidget snap = ButtonWidget.builder(snapLabel(), b -> {
                FontStore.toggleSnap(bgKey);
                b.setMessage(snapLabel());
            }).dimensions(cx + 15, by, 70, 20).build();
            snap.setTooltip(Tooltip.of(Text.literal(
                    "Snap dragged elements to an " + TitleLayout.GRID + " px grid so everything lines up")));
            addDrawableChild(snap);

            ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset"), b -> {
                FontStore.clearAllPositions(bgKey);
                this.clearAndInit();
            }).dimensions(cx + 89, by, 50, 20).build();
            reset.active = FontStore.hasCustomPositions(bgKey);
            reset.setTooltip(Tooltip.of(Text.literal("Put every element back where the layout has it")));
            addDrawableChild(reset);
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + 143, by, 56, 20).build());
    }

    private String musicKey() { return bgName.isEmpty() ? "default" : bgName; }

    private Text layoutLabel() {
        return Text.literal("Layout: " + ("custom".equals(FontStore.layoutFor(bgKey)) ? "Custom" : "Left"));
    }

    private Text snapLabel() {
        return Text.literal("Snap: " + (FontStore.snapFor(bgKey) ? "§aon" : "§7off"));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
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
        boolean custom = customLayout();

        // Snap grid while dragging.
        if (dragSlot != null && dragged && FontStore.snapFor(bgKey)) {
            for (int gx = 0; gx < this.width; gx += TitleLayout.GRID * 2)
                for (int gy = 0; gy < this.height; gy += TitleLayout.GRID * 2)
                    ctx.fill(gx, gy, gx + 1, gy + 1, 0x40FFFFFF);
        }

        String hdr = this.title.getString();
        m.pushMatrix();
        m.translate(this.width / 2f, 10f);
        m.scale(1.4f, 1.4f);
        ctx.drawTextWithShadow(tr, hdr, -tr.getWidth(hdr) / 2, 0, 0xFFFFFFFF);
        m.popMatrix();
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal(custom ? "§7click an icon or a text to change it §8• §7drag to move"
                                    : "§7click an icon or a text to change it"),
                this.width / 2, 26, 0xFFFFFFFF);

        iconHit.clear();
        labelHit.clear();

        // ── pass 1: draw every element + neutral outlines, collect part rects ────────────────
        String layout = FontStore.layoutFor(bgKey);
        for (var e : boxes.entrySet()) {
            String slot = e.getKey();
            TitleLayout.Box b = e.getValue();
            int x = b.x(), y = b.y();
            if (slot.equals(dragSlot) && dragged) { x = dragX; y = dragY; }
            boolean hidden = FontStore.hiddenFor(bgKey, slot);
            int alpha = hidden ? 0x59FFFFFF : 0xFFFFFFFF;

            if (slot.equals("version")) {
                Identifier brand = IconStore.resolved(bgKey, "version");
                if (brand != null)
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, brand, x, y, 0f, 0f,
                            b.w(), b.h(), b.w(), b.h(), alpha);
                iconHit.put(slot, new int[]{ x, y, b.w(), b.h() });
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
            } else {
                String label = FontStore.labelFor(bgKey, slot, TitleLayout.defaultLabel(slot, layout));
                boolean iconOnly = label.isEmpty();
                Identifier tex = IconStore.resolved(bgKey, slot);
                int is = Math.max(4, Math.round(b.iconSize() * FontStore.iconSizeFor(bgKey, slot)));
                int ix = iconOnly ? x + (b.w() - is) / 2 : x + 4;
                int iy = y + (b.h() - is) / 2;
                if (tex != null)
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, ix, iy, 0f, 0f, is, is, is, is, alpha);
                iconHit.put(slot, new int[]{ ix, iy, is, is });

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
                }
            }
            if (hidden)
                ctx.drawTextWithShadow(tr, Text.literal("§8(hidden)"), x + b.w() + 6, y + b.h() / 2 - 4,
                        0xFFFFFFFF);
        }

        // ── decide the ONE active part: sticky on its strip, else precise part hover ─────────
        String hovered = null;
        if (dragSlot != null) {
            hovered = activePart;                       // keep while dragging
        } else if (activePart != null && stripRect != null && inUnion(mouseX, mouseY)) {
            hovered = activePart;                       // cursor between the part and its strip
        } else {
            for (var e : labelHit.entrySet())
                if (in(mouseX, mouseY, e.getValue())) hovered = e.getKey() + "|label";
            for (var e : iconHit.entrySet())
                if (in(mouseX, mouseY, e.getValue())) hovered = e.getKey() + "|icon";
        }
        if (hovered == null) { activePart = null; stripRect = null; }
        else if (!hovered.equals(activePart)) { activePart = hovered; hoverSince = System.currentTimeMillis(); }

        // ── pass 2: outlines (active part yellow), then the single strip ON TOP ──────────────
        for (var e : iconHit.entrySet()) {
            int[] r = e.getValue();
            boolean act = (e.getKey() + "|icon").equals(activePart);
            dashed(ctx, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, act ? 0xFFFFFF66 : 0x66FFFFFF);
        }
        for (var e : labelHit.entrySet()) {
            int[] r = e.getValue();
            boolean act = (e.getKey() + "|label").equals(activePart);
            dashed(ctx, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4, act ? 0xFFFFFF66 : 0x66FFFFFF);
        }

        controls.clear();
        if (activePart != null && dragSlot == null) {
            drawStrip(ctx, activePart, mouseX, mouseY);

            // Click indicator + linger tooltip on the part itself (not its sub-buttons).
            String slot = activePart.substring(0, activePart.indexOf('|'));
            boolean isIcon = activePart.endsWith("|icon");
            int[] r = (isIcon ? iconHit : labelHit).get(slot);
            if (r != null && in(mouseX, mouseY, r)) {
                if (System.currentTimeMillis() - hoverSince > 2000) {
                    String tip = isIcon ? "click to change this icon" : "click to change this font";
                    if (custom) tip += " — drag to move";
                    drawTip(ctx, mouseX + 18, mouseY - 2, tip);
                }
            }
        }
    }

    /** The compact control strip for one part, drawn last (on top of every neighboring element). */
    private void drawStrip(DrawContext ctx, String part, int mouseX, int mouseY) {
        var tr = this.textRenderer;
        String slot = part.substring(0, part.indexOf('|'));
        boolean icon = part.endsWith("|icon");
        int[] r = (icon ? iconHit : labelHit).get(slot);
        if (r == null) { activePart = null; stripRect = null; return; }

        List<Object[]> strip = new ArrayList<>();   // [label, tooltip, action]
        if (icon) {
            // The brand bar resizes too — its whole box follows the multiplier (aspect preserved).
            String what = slot.equals("version") ? "brand bar" : "icon";
            strip.add(new Object[]{ "−", "smaller " + what, (Runnable) () -> {
                FontStore.adjustIconSize(slot, -0.1f);
                boxes = TitleLayout.compute(bgKey, this.width, this.height);
            } });
            strip.add(new Object[]{ "＋", "bigger " + what, (Runnable) () -> {
                FontStore.adjustIconSize(slot, +0.1f);
                boxes = TitleLayout.compute(bgKey, this.width, this.height);
            } });
        } else {
            strip.add(new Object[]{ "−", "smaller text", (Runnable) () -> FontStore.adjustSize(slot, -0.1f) });
            strip.add(new Object[]{ "＋", "bigger text", (Runnable) () -> FontStore.adjustSize(slot, +0.1f) });
            if (!slot.equals("versiontag"))
                strip.add(new Object[]{ "✎", "rename this button", (Runnable) () -> rename(slot) });
        }
        // The Options button must stay reachable, so it can never be hidden.
        if (!slot.equals("options"))
            strip.add(new Object[]{ "👁", FontStore.hiddenFor(bgKey, slot)
                    ? "show this element again" : "hide this element", (Runnable) () -> FontStore.toggleHidden(slot) });

        int sy = r[1] + r[3] + 6;
        int stripW = strip.size() * 22 - 2 + 50;
        if (sy + 16 > this.height - 30) sy = r[1] - 20;
        int sx = Math.max(2, Math.min(r[0], this.width - stripW - 2));
        stripRect = new int[]{ sx, sy, stripW, 14 };

        String subTip = null;
        int bx = sx;
        for (Object[] c : strip) {
            int w = 20;
            boolean hov = mouseX >= bx && mouseX < bx + w && mouseY >= sy && mouseY < sy + 13;
            ctx.fill(bx, sy, bx + w, sy + 13, hov ? 0xE6303040 : 0xE6101018);
            ctx.drawCenteredTextWithShadow(tr, Text.literal((String) c[0]), bx + w / 2, sy + 3, 0xFFFFFFFF);
            controls.add(new Object[]{ bx, sy, w, 13, c[1], c[2] });
            if (hov) subTip = (String) c[1];
            bx += w + 2;
        }
        // Live size readout right of the strip.
        float size = icon ? FontStore.iconSizeFor(bgKey, slot) : FontStore.sizeFor(bgKey, slot);
        ctx.drawTextWithShadow(tr, Text.literal("§7×" + String.format(Locale.ROOT, "%.2f", size)),
                bx + 4, sy + 3, 0xFFFFFFFF);
        // Font attribution under the strip (text parts only).
        if (!icon) {
            String about = FontStore.aboutFor(FontStore.fontFor(bgKey, slot));
            if (about != null)
                ctx.drawTextWithShadow(tr, Text.literal("§8" + about), sx, sy + 16, 0xFFFFFFFF);
        }
        if (subTip != null) drawTip(ctx, mouseX + 10, mouseY + 8, subTip);
    }

    private void drawTip(DrawContext ctx, int x, int y, String text) {
        var tr = this.textRenderer;
        int w = tr.getWidth(text);
        x = Math.min(x, this.width - w - 8);
        ctx.fill(x - 3, y - 3, x + w + 3, y + 11, 0xF0141420);
        ctx.drawTextWithShadow(tr, Text.literal(text), x, y, 0xFFFFFFE0);
    }

    private static boolean in(int mx, int my, int[] r) {
        return mx >= r[0] - 2 && mx < r[0] + r[2] + 2 && my >= r[1] - 2 && my < r[1] + r[3] + 2;
    }

    /**
     * The sticky region for the active part: the BOUNDING BOX of the part and its strip, so the
     * cursor can travel from one to the other in any direction (strip above, below, offset to the
     * side) without the strip vanishing mid-way.
     */
    private boolean inUnion(int mx, int my) {
        if (activePart == null || stripRect == null) return false;
        String slot = activePart.substring(0, activePart.indexOf('|'));
        int[] part = (activePart.endsWith("|icon") ? iconHit : labelHit).get(slot);
        if (part == null) return false;
        int x0 = Math.min(part[0], stripRect[0]) - 3;
        int y0 = Math.min(part[1], stripRect[1]) - 3;
        int x1 = Math.max(part[0] + part[2], stripRect[0] + stripRect[2]) + 3;
        int y1 = Math.max(part[1] + part[3], stripRect[1] + stripRect[3]) + 3;
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
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
        // Control strip first (it's on top).
        for (Object[] c : controls) {
            int x = (int) c[0], y = (int) c[1], w = (int) c[2], h = (int) c[3];
            if (mx >= x && mx < x + w && my >= y && my < y + h) {
                ((Runnable) c[5]).run();
                return true;
            }
        }
        // A press on a part: maybe a click (open editor), maybe a drag (custom layout only).
        for (var e : boxes.entrySet()) {
            String slot = e.getKey();
            int[] ih = iconHit.get(slot), lh = labelHit.get(slot);
            if ((ih != null && in(mx, my, ih)) || (lh != null && in(mx, my, lh))) {
                dragSlot = slot;
                dragged = false;
                TitleLayout.Box b = e.getValue();
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
        if (dragSlot != null && customLayout()) {
            int nx = (int) Math.round(click.x() - dragOffX);
            int ny = (int) Math.round(click.y() - dragOffY);
            if (!dragged && (Math.abs(nx - boxes.get(dragSlot).x()) > 3
                          || Math.abs(ny - boxes.get(dragSlot).y()) > 3)) dragged = true;
            if (dragged) {
                TitleLayout.Box b = boxes.get(dragSlot);
                int g = FontStore.snapFor(bgKey) ? TitleLayout.GRID : 1;
                dragX = Math.max(0, Math.min(this.width - b.w(), Math.round(nx / (float) g) * g));
                dragY = Math.max(0, Math.min(this.height - b.h(), Math.round(ny / (float) g) * g));
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
                this.clearAndInit();   // refresh the "Reset" enabled state
            } else {
                openEditorFor(slot, (int) click.x(), (int) click.y());
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    /** A plain click on a part: the text opens the font picker, the icon the icon editor. */
    private void openEditorFor(String slot, int mx, int my) {
        int[] lh = labelHit.get(slot);
        int[] ih = iconHit.get(slot);
        boolean onLabel = lh != null && in(mx, my, lh);
        if (onLabel) {
            String sample = slot.equals("versiontag") ? "Minecraft 1.21.11 © Mojang AB"
                    : FontStore.labelFor(bgKey, slot, TitleLayout.defaultLabel(slot, FontStore.layoutFor(bgKey)));
            this.client.setScreen(new FontPickScreen(this, slot, sample));
        } else if (ih != null && in(mx, my, ih)) {
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
