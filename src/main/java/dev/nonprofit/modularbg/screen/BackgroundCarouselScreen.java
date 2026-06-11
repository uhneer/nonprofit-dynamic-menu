package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.IconStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Backgrounds carousel: Default → ◄/► through a live preview of each background → a final black "＋"
 * slot (click it to add an image/MP4; it can't be selected until you do). "Edit" opens the unified
 * per-background menu (icons / fonts / music / export / delete); "Replace Image" swaps the picture
 * keeping everything else; "Import .zip" adds a shared package; "Save & Exit" selects whatever you're
 * focused on and leaves (all per-background edits are already saved as you make them).
 */
public class BackgroundCarouselScreen extends Screen {

    private final Screen parent;
    private List<String> files;
    private int idx;
    private int px, py, pw, ph;

    public BackgroundCarouselScreen(Screen parent) {
        super(Text.literal("Backgrounds"));
        this.parent = parent;
        files = NonprofitBackgrounds.available();
        String sel = NonprofitBackgrounds.getSelected();
        if (sel == null || sel.isEmpty()) idx = 0;
        else { int i = files.indexOf(sel); idx = i >= 0 ? i + 1 : 0; }
    }

    private int addIndex()       { return files.size() + 1; }
    private boolean isAdd()      { return idx == addIndex(); }
    private boolean isDefault()  { return idx == 0; }
    private String currentName() { return idx == 0 ? "" : (idx <= files.size() ? files.get(idx - 1) : null); }
    private String displayName() { return isAdd() ? "Add background" : isDefault() ? "Default" : currentName(); }

    @Override
    protected void init() {
        files = NonprofitBackgrounds.available();
        idx = Math.max(0, Math.min(idx, addIndex()));

        pw = Math.max(220, (int) (this.width * 0.5f));
        ph = pw * 9 / 16;
        px = (this.width - pw) / 2;
        py = 44;
        int cx = this.width / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal("◄"), b -> { if (idx > 0) { idx--; clearAndInit(); } })
                .dimensions(px - 30, py + ph / 2 - 12, 24, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("►"), b -> { if (idx < addIndex()) { idx++; clearAndInit(); } })
                .dimensions(px + pw + 6, py + ph / 2 - 12, 24, 24).build());

        int row1 = py + ph + 10;
        if (!isAdd()) {
            if (isDefault()) {
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit"),
                        b -> this.client.setScreen(new BackgroundEditMenuScreen(this, "")))
                        .dimensions(cx - 55, row1, 110, 20).build());
            } else {
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit"),
                        b -> this.client.setScreen(new BackgroundEditMenuScreen(this, currentName())))
                        .dimensions(cx - 115, row1, 110, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Replace Image"), b -> {
                    String picked = NonprofitBackgrounds.openFilePicker();
                    if (picked != null) {
                        String n = NonprofitBackgrounds.replaceImage(currentName(), picked);
                        files = NonprofitBackgrounds.available();
                        int i = n == null ? -1 : files.indexOf(n);
                        idx = i >= 0 ? i + 1 : idx;
                        clearAndInit();
                    }
                }).dimensions(cx + 5, row1, 110, 20).build());
            }
        }

        int by = this.height - 26;
        addDrawableChild(ButtonWidget.builder(Text.literal("Import Skin"), b -> {
            String zip = BackgroundPackage.pickImportZip();
            if (zip != null) {
                String n = BackgroundPackage.importZip(zip);
                files = NonprofitBackgrounds.available();
                int i = n == null ? -1 : files.indexOf(n);
                idx = i >= 0 ? i + 1 : 0;
                clearAndInit();
            }
        }).dimensions(cx - 115, by, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Save & Exit"), b -> {
            if (!isAdd()) NonprofitBackgrounds.select(currentName());
            this.close();
        }).dimensions(cx + 5, by, 110, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(this.width / 2f, 16f);
        m.scale(1.3f, 1.3f);
        String name = displayName();
        ctx.drawTextWithShadow(tr, name, -tr.getWidth(name) / 2, 0, 0xFFFFFFFF);
        m.popMatrix();

        ctx.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF2A2A30);
        if (isAdd()) {
            ctx.fill(px, py, px + pw, py + ph, 0xFF000000);
            int cx = px + pw / 2, cy = py + ph / 2, r = 18, th = 5;
            ctx.fill(cx - r, cy - th / 2, cx + r, cy + th / 2 + 1, 0xFFB0B0B0);
            ctx.fill(cx - th / 2, cy - r, cx + th / 2 + 1, cy + r, 0xFFB0B0B0);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("§7click to add background (image, gif, mp4)"),
                    px + pw / 2, py + ph - 14, 0xFFFFFFFF);
        } else {
            // Full title-screen mockup for this skin: its background + gradient + every button with
            // that skin's own font and icon. (MP4s only play live once selected, so an unselected
            // video previews with a black backdrop but the rest of the UI still shows.)
            drawMockup(ctx, isDefault() ? "" : currentName(), px, py, pw, ph);
            // One-time MP4 bake progress / failure, drawn over the preview while it runs.
            String vs = NonprofitBackgrounds.videoStatusFor(currentName());
            if (vs != null)
                ctx.drawCenteredTextWithShadow(tr, Text.literal("§e⏳ " + vs),
                        px + pw / 2, py + ph - 16, 0xFFFFFFFF);
        }
        boolean selectedHere = !isAdd() && (currentName().equals(NonprofitBackgrounds.getSelected())
                || (isDefault() && NonprofitBackgrounds.isDefault()));
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal((selectedHere ? "§a✔ selected   " : "") + "§8" + (idx + 1) + " / " + (addIndex() + 1)),
                this.width / 2, py + ph + 4 + 60, 0xFFFFFFFF);
    }

    // Layout mirrors ModularTitleScreen (measurements aren't copyrightable; code is original).
    private static final int M_LEFT = 20, M_COL_W = 120, M_GAP = 4, M_H_VER = 50, M_H_PLAY = 45, M_H_ROW = 20;

    /**
     * Draws a faithful, scaled-down title screen for {@code name} into the rect: the skin's
     * background + readability gradients + brand art + every menu row with that skin's per-slot icon
     * and font, in that skin's chosen layout ("left" column or "center"). The virtual canvas is the
     * live window size, uniformly scaled to the rect width and scissor-clipped.
     */
    private void drawMockup(DrawContext ctx, String name, int rx, int ry, int rw, int rh) {
        TextRenderer tr = this.textRenderer;
        String bg = IconStore.keyFor(name);
        int VW = Math.max(1, this.width), VH = Math.max(1, this.height);
        float s = (float) rw / VW;

        ctx.enableScissor(rx, ry, rx + rw, ry + rh);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) rx, (float) ry);
        m.scale(s, s);

        // Background frame (or black for default / not-yet-playing video).
        Identifier frame = (name == null || name.isEmpty()) ? null : NonprofitBackgrounds.previewFrame(name);
        if (frame != null) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0f, 0f, VW, VH, VW, VH, 0xFFFFFFFF);
        else ctx.fill(0, 0, VW, VH, 0xFF000000);

        boolean center = "center".equals(FontStore.layoutFor(bg));
        if (center) {
            int th = Math.max(1, VH / 4);
            for (int y = 0; y < th; y++) {
                int a = (int) ((1f - y / (float) th) * 95f);
                if (a > 0) ctx.fill(0, y, VW, y + 1, a << 24);
            }
            int bh = Math.max(1, VH / 3);
            for (int y = 0; y < bh; y++) {
                int a = (int) ((y / (float) bh) * 110f);
                if (a > 0) ctx.fill(0, VH - bh + y, VW, VH - bh + y + 1, a << 24);
            }
            int brandW = 192, brandH = 80, playW = 150, colW = 110, colGap = 12;
            int total = brandH + 12 + M_H_PLAY + 10 + M_H_ROW * 2 + M_GAP;
            int top = Math.max(16, (VH - total) / 2 - 10);
            int cx = VW / 2;
            Identifier brand = IconStore.resolved(bg, "version");
            if (brand != null)
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, brand, cx - brandW / 2, top, 0f, 0f,
                        brandW, brandH, brandW, brandH, 0xFFFFFFFF);
            int yPlay = top + brandH + 12;
            mockRowAt(ctx, tr, bg, "play", "PLAY", cx - playW / 2, yPlay, M_H_PLAY, 35, 1.8f);
            int yGrid = yPlay + M_H_PLAY + 10;
            int lx = cx - colGap / 2 - colW, rxx = cx + colGap / 2;
            mockRowAt(ctx, tr, bg, "multiplayer", "Multiplayer", lx, yGrid, M_H_ROW, 12, 1.0f);
            mockRowAt(ctx, tr, bg, "options", "Options", lx, yGrid + M_H_ROW + M_GAP, M_H_ROW, 12, 1.0f);
            mockRowAt(ctx, tr, bg, "mods", "Mods", rxx, yGrid, M_H_ROW, 12, 1.0f);
            mockRowAt(ctx, tr, bg, "close", "Quit", rxx, yGrid + M_H_ROW + M_GAP, M_H_ROW, 12, 1.0f);
        } else {
            int gw = Math.max(1, VW / 3);
            for (int x = 0; x < gw; x++) {
                int a = (int) ((1f - x / (float) gw) * 100f);
                if (a > 0) ctx.fill(x, 0, x + 1, VH, a << 24);
            }
            int total = M_H_VER + M_H_PLAY + M_H_ROW * 3 + M_GAP * 4;
            int top = (VH - total) / 2;
            int yPlay = top + M_H_VER + M_GAP;
            int yMp = yPlay + M_H_PLAY + M_GAP;
            int yOpt = yMp + M_H_ROW + M_GAP;
            int yMods = yOpt + M_H_ROW + M_GAP;

            Identifier brand = IconStore.resolved(bg, "version");
            if (brand != null)
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, brand, M_LEFT, top, 0f, 0f, M_COL_W, M_H_VER, M_COL_W, M_H_VER, 0xFFFFFFFF);

            mockRowAt(ctx, tr, bg, "play", "PLAY", M_LEFT, yPlay, M_H_PLAY, 35, 1.8f);
            mockRowAt(ctx, tr, bg, "multiplayer", "Multiplayer", M_LEFT, yMp, M_H_ROW, 12, 1.0f);
            mockRowAt(ctx, tr, bg, "options", "Options", M_LEFT, yOpt, M_H_ROW, 12, 1.0f);
            mockRowAt(ctx, tr, bg, "mods", "Mods", M_LEFT, yMods, M_H_ROW, 12, 1.0f);

            // Quit ✕ top-right (left layout only).
            Identifier close = IconStore.resolved(bg, "close");
            if (close != null)
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, close, VW - 24, 8, 0f, 0f, 16, 16, 16, 16, 0xFFFFFFFF);
        }

        // Version tag, bottom-left, 0.75 scale, versiontag font + size.
        MutableText ver = Text.literal("Minecraft 1.21.11 © Mojang AB");
        Identifier vf = FontStore.fontFor(bg, "versiontag");
        if (vf != null) ver = ver.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(vf)));
        float vsc = 0.75f * FontStore.sizeFor(bg, "versiontag");
        m.pushMatrix();
        m.scale(vsc, vsc);
        ctx.drawTextWithShadow(tr, ver, (int) (10 / vsc), (int) ((VH - 10) / vsc), 0xCCFFFFFF);
        m.popMatrix();

        m.popMatrix();
        ctx.disableScissor();
    }

    /** One menu row in the mockup at an explicit x: per-skin icon + label in the skin's font/size. */
    private void mockRowAt(DrawContext ctx, TextRenderer tr, String bg, String slot, String label,
                           int x, int y, int h, int iconSize, float fontScale) {
        Identifier tex = IconStore.resolved(bg, slot);
        int iconOffset = 0;
        if (tex != null) {
            int is = Math.max(4, Math.round(iconSize * FontStore.iconSizeFor(bg, slot)));
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x + 4, y + (h - is) / 2,
                    0f, 0f, is, is, is, is, 0xFFFFFFFF);
            iconOffset = is + 8;
        }
        mockText(ctx, tr, bg, slot, label, x + 4 + iconOffset, y + (h - (int) (8 * fontScale)) / 2, fontScale);
    }

    /** Draws scaled label text in a skin's per-slot font + size (matches MenuButton's styling). */
    private void mockText(DrawContext ctx, TextRenderer tr, String bg, String slot, String label,
                          int x, int y, float fontScale) {
        MutableText t = Text.literal(label);
        Identifier f = FontStore.fontFor(bg, slot);
        if (f != null) t = t.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(f)));
        float fs = fontScale * FontStore.sizeFor(bg, slot);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) x, (float) y);
        m.scale(fs, fs);
        ctx.drawTextWithShadow(tr, t, 0, 0, 0xFFFFFFFF);
        m.popMatrix();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            if (isAdd()) {
                String picked = NonprofitBackgrounds.openFilePicker();
                if (picked != null) {
                    String name = NonprofitBackgrounds.importAndSelect(picked);
                    files = NonprofitBackgrounds.available();
                    int i = name == null ? -1 : files.indexOf(name);
                    idx = i >= 0 ? i + 1 : 0;
                    clearAndInit();
                }
            } else {
                NonprofitBackgrounds.select(currentName());
                clearAndInit();
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void close() {
        NonprofitBackgrounds.reload();
        this.client.setScreen(parent);
    }
}
