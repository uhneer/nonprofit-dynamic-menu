package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import dev.nonprofit.modularbg.background.FontStore;
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
import java.util.function.Supplier;

/**
 * Sibling of the icon editor, but for fonts: a title-screen preview with dotted outlines around the
 * TEXT of the five menu buttons + the bottom version tag. Click a label to open {@link FontPickScreen}
 * and swap that slot's font (per-background).
 */
public class FontEditOverlayScreen extends Screen {

    private record Slot(String key, Supplier<String> sample, int anchorX, int anchorY, float scale) {}

    private final Screen parent;
    private final List<Slot> slots = new ArrayList<>();
    private final List<int[]> boxes = new ArrayList<>();   // [x,y,w,h] computed each frame, for clicks

    public FontEditOverlayScreen(Screen parent) {
        super(Text.literal("Customize Fonts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        slots.clear();
        int left = 20, gap = 4, hVer = 50, hPlay = 45, hRow = 20;
        int total = hVer + hPlay + hRow * 3 + gap * 4;
        int top = (this.height - total) / 2;
        int yPlay = top + hVer + gap, yMp = yPlay + hPlay + gap, yOpt = yMp + hRow + gap;
        int yMods = yOpt + hRow + gap;

        slots.add(new Slot("play", () -> "PLAY", left + 4 + 35 + 8, yPlay + (hPlay - (int) (8 * 1.8f)) / 2, 1.8f));
        slots.add(new Slot("multiplayer", () -> "Multiplayer", left + 4 + 12 + 8, yMp + 6, 1.0f));
        slots.add(new Slot("options", () -> "Options", left + 4 + 12 + 8, yOpt + 6, 1.0f));
        slots.add(new Slot("mods", () -> "Mods", left + 4 + 12 + 8, yMods + 6, 1.0f));
        slots.add(new Slot("versiontag", () -> "Minecraft 1.21.11 © Mojang AB", 10, this.height - 10, 0.75f));

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    private MutableText styled(String key, String s) {
        MutableText t = Text.literal(s);
        Identifier f = FontStore.fontFor(key);
        if (f != null) t = t.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(f)));
        return t;
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

        String hdr = "Click to change a font";
        m.pushMatrix();
        m.translate(this.width / 2f, 12f);
        m.scale(1.6f, 1.6f);
        ctx.drawTextWithShadow(tr, hdr, -tr.getWidth(hdr) / 2, 0, 0xFFFFFFFF);
        m.popMatrix();

        boxes.clear();
        sizeBoxes.clear();
        for (Slot s : slots) {
            MutableText t = styled(s.key(), s.sample().get());
            float fs = s.scale() * FontStore.sizeFor(s.key());     // live per-slot size multiplier
            int w = (int) (tr.getWidth(t) * fs);
            int h = (int) (8 * fs);
            int bx = s.anchorX(), by = s.anchorY();
            boxes.add(new int[]{ bx, by, w, h });
            boolean hov = mouseX >= bx - 2 && mouseX < bx + w + 2 && mouseY >= by - 2 && mouseY < by + h + 2;

            m.pushMatrix();
            m.translate(bx, by);
            m.scale(fs, fs);
            ctx.drawTextWithShadow(tr, t, 0, 0, 0xFFFFFFFF);
            m.popMatrix();

            dashed(ctx, bx - 2, by - 2, w + 4, h + 4, hov ? 0xFFFFFF66 : 0xAAFFFFFF);

            // Size nudge buttons [−] [+] just right of the label, always visible.
            int sx = bx + w + 8, sy = by + h / 2 - 5;
            ctx.fill(sx, sy, sx + 10, sy + 10, 0xAA000000);
            ctx.fill(sx + 13, sy, sx + 23, sy + 10, 0xAA000000);
            ctx.drawTextWithShadow(tr, "-", sx + 3, sy + 1, 0xFFFFFFFF);
            ctx.drawTextWithShadow(tr, "+", sx + 15, sy + 1, 0xFFFFFFFF);
            sizeBoxes.add(new int[]{ sx, sy });                      // x,y of the [-] box
            if (hov)
                ctx.drawTextWithShadow(tr, Text.literal("§e✎ font  §7size ×"
                                + String.format(java.util.Locale.ROOT, "%.2f", FontStore.sizeFor(s.key()))),
                        sx + 28, by + h / 2 - 4, 0xFFFFFF66);
        }
    }

    private final List<int[]> sizeBoxes = new ArrayList<>();

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        // Size nudges take priority over opening the font picker.
        for (int i = 0; i < sizeBoxes.size() && i < slots.size(); i++) {
            int[] sb = sizeBoxes.get(i);
            if (my >= sb[1] && my < sb[1] + 10) {
                if (mx >= sb[0] && mx < sb[0] + 10) { FontStore.adjustSize(slots.get(i).key(), -0.1f); return true; }
                if (mx >= sb[0] + 13 && mx < sb[0] + 23) { FontStore.adjustSize(slots.get(i).key(), +0.1f); return true; }
            }
        }
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            if (mx >= b[0] - 2 && mx < b[0] + b[2] + 2 && my >= b[1] - 2 && my < b[1] + b[3] + 2) {
                Slot s = slots.get(i);
                this.client.setScreen(new FontPickScreen(this, s.key(), s.sample().get()));
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
