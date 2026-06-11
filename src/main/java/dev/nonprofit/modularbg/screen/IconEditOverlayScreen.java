package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.IconStore;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * A live preview of the title screen where every icon is an editable slot: each is outlined with a
 * dotted border, and clicking one opens {@link IconEditScreen}. {@code dispW}/{@code dispH} are the
 * shape that slot is drawn at on the title screen, passed through so the editor previews the icon at
 * its true aspect. Layout mirrors {@link ModularTitleScreen}.
 */
public class IconEditOverlayScreen extends Screen {

    private record Slot(String key, String label, int x, int y, int w, int h,
                        int iconSize, boolean label2, int dispW, int dispH) {}

    private final Screen parent;
    private final List<Slot> slots = new ArrayList<>();

    public IconEditOverlayScreen(Screen parent) {
        super(Text.literal("Customize Icons"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        slots.clear();
        int left = 20, colW = 120, gap = 4, hVer = 50, hPlay = 45, hRow = 20;
        int total = hVer + hPlay + hRow * 3 + gap * 4;
        int top = (this.height - total) / 2;
        int yVer = top, yPlay = top + hVer + gap, yMp = yPlay + hPlay + gap;
        int yOpt = yMp + hRow + gap, yMods = yOpt + hRow + gap;

        slots.add(new Slot("version", "Brand bar", left, yVer, colW, hVer, 0, false, 120, 50));
        slots.add(new Slot("play", "Play", left, yPlay, colW, hPlay, 35, true, 35, 35));
        slots.add(new Slot("multiplayer", "Multiplayer", left, yMp, colW, hRow, 12, true, 12, 12));
        slots.add(new Slot("options", "Options", left, yOpt, colW, hRow, 12, true, 12, 12));
        slots.add(new Slot("mods", "Mods", left, yMods, colW, hRow, 12, true, 12, 12));
        slots.add(new Slot("close", "Close (X)", this.width - 24, 8, 16, 16, 16, false, 16, 16));

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!BackgroundRenderer.draw(ctx)) ctx.fill(0, 0, this.width, this.height, 0xFF000000);
        ctx.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        var m = ctx.getMatrices();

        String hdr = "Click to edit icons";
        m.pushMatrix();
        m.translate(this.width / 2f, 12f);
        m.scale(1.6f, 1.6f);
        ctx.drawTextWithShadow(tr, hdr, -tr.getWidth(hdr) / 2, 0, 0xFFFFFFFF);
        m.popMatrix();

        sizeBoxes.clear();
        for (Slot s : slots) {
            boolean hov = mouseX >= s.x() - 2 && mouseX < s.x() + s.w() + 2
                       && mouseY >= s.y() - 2 && mouseY < s.y() + s.h() + 2;

            if (s.key().equals("version")) {
                Identifier v = IconStore.resolved("version");
                if (v != null)
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, v, s.x(), s.y(), 0f, 0f,
                            s.w(), s.h(), s.w(), s.h(), 0xFFFFFFFF);
            } else {
                Identifier tex = IconStore.resolved(s.key());
                int ix = s.label2() ? s.x() + 4 : s.x() + (s.w() - s.iconSize()) / 2; // centered if icon-only
                int iconY = s.y() + (s.h() - s.iconSize()) / 2;
                if (tex != null)
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, ix, iconY, 0f, 0f,
                            s.iconSize(), s.iconSize(), s.iconSize(), s.iconSize(), 0xFFFFFFFF);

                if (s.label2()) {
                    float fs = s.key().equals("play") ? 1.8f : 1.0f;
                    int tx = s.x() + 4 + s.iconSize() + 8;
                    int ty = s.y() + (s.h() - (int) (8 * fs)) / 2;
                    // Render the label in this background's per-slot font, matching the title screen.
                    MutableText label = Text.literal(s.label());
                    Identifier f = FontStore.fontFor(s.key());
                    if (f != null) label = label.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(f)));
                    m.pushMatrix();
                    m.translate(tx, ty);
                    m.scale(fs, fs);
                    ctx.drawTextWithShadow(tr, label, 0, 0, 0xFFFFFFFF);
                    m.popMatrix();
                }
            }

            dashed(ctx, s.x() - 2, s.y() - 2, s.w() + 4, s.h() + 4, hov ? 0xFFFFFF66 : 0xAAFFFFFF);

            // Icon size nudge buttons [−] [+] beside the slot (not for the brand bar — it has a box).
            if (!s.key().equals("version")) {
                int sx = s.x() + s.w() + 8, sy = s.y() + (s.h() - 10) / 2;
                ctx.fill(sx, sy, sx + 10, sy + 10, 0xAA000000);
                ctx.fill(sx + 13, sy, sx + 23, sy + 10, 0xAA000000);
                ctx.drawTextWithShadow(tr, "-", sx + 3, sy + 1, 0xFFFFFFFF);
                ctx.drawTextWithShadow(tr, "+", sx + 15, sy + 1, 0xFFFFFFFF);
                sizeBoxes.add(new int[]{ sx, sy, slots.indexOf(s) });
                if (hov)
                    ctx.drawTextWithShadow(tr, Text.literal("§e✎ " + s.label() + "  §7size ×"
                                    + String.format(java.util.Locale.ROOT, "%.2f",
                                            dev.nonprofit.modularbg.background.FontStore.iconSizeFor(s.key()))),
                            sx + 28, s.y() + (s.h() - 8) / 2, 0xFFFFFF66);
            } else if (hov) {
                ctx.drawTextWithShadow(tr, Text.literal("§e✎ " + s.label()),
                        s.x() + s.w() + 8, s.y() + (s.h() - 8) / 2, 0xFFFFFF66);
            }
        }
    }

    private final List<int[]> sizeBoxes = new ArrayList<>();

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        for (int[] sb : sizeBoxes) {
            if (my >= sb[1] && my < sb[1] + 10) {
                String key = slots.get(sb[2]).key();
                if (mx >= sb[0] && mx < sb[0] + 10) {
                    dev.nonprofit.modularbg.background.FontStore.adjustIconSize(key, -0.1f);
                    return true;
                }
                if (mx >= sb[0] + 13 && mx < sb[0] + 23) {
                    dev.nonprofit.modularbg.background.FontStore.adjustIconSize(key, +0.1f);
                    return true;
                }
            }
        }
        for (Slot s : slots) {
            if (mx >= s.x() - 2 && mx < s.x() + s.w() + 2 && my >= s.y() - 2 && my < s.y() + s.h() + 2) {
                this.client.setScreen(new IconEditScreen(this, s.key(), s.label(), s.dispW(), s.dispH()));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private static void dashed(DrawContext c, int x, int y, int w, int h, int col) {
        int d = 3, g = 3;
        for (int i = 0; i < w; i += d + g) { int e = Math.min(i + d, w); c.fill(x + i, y, x + e, y + 1, col); c.fill(x + i, y + h - 1, x + e, y + h, col); }
        for (int i = 0; i < h; i += d + g) { int e = Math.min(i + d, h); c.fill(x, y + i, x + 1, y + e, col); c.fill(x + w - 1, y + i, x + w, y + e, col); }
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
