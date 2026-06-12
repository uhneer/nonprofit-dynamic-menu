package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.IconStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

/**
 * Backgrounds carousel: Default → ◄/► through a live preview of each background → a final black "＋"
 * slot (click it to add an image/MP4). EVERY action button under the preview applies to the
 * background being PREVIEWED (not the selected one): Edit opens the in-place edit overlay, plus
 * Replace / Rename / Export / Delete right here. "Shuffle" picks a random background each launch.
 * "Save & Exit" selects whatever you're looking at and leaves.
 */
public class BackgroundCarouselScreen extends Screen {

    private final Screen parent;
    private List<String> files;
    private int idx;
    private int px, py, pw, ph;
    private int deleteStage = 0;
    private ButtonWidget deleteBtn;

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
        deleteStage = 0;

        // Every per-background edit anywhere in this flow targets the PREVIEWED background.
        IconStore.setEditTarget(isAdd() ? null : currentName());

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
                addDrawableChild(ButtonWidget.builder(Text.literal("✎ Edit"),
                        b -> this.client.setScreen(new EditOverlayScreen(this, "")))
                        .dimensions(cx - 55, row1, 110, 20).build());
            } else {
                int bw = 66, gap = 4;
                int x0 = cx - (bw * 5 + gap * 4) / 2;
                var edit = ButtonWidget.builder(Text.literal("✎ Edit"),
                        b -> this.client.setScreen(new EditOverlayScreen(this, currentName())))
                        .dimensions(x0, row1, bw, 20).build();
                edit.setTooltip(Tooltip.of(Text.literal(
                        "Edit this skin in place: drag elements to move them, click an icon or a text to change it")));
                addDrawableChild(edit);

                var rep = ButtonWidget.builder(Text.literal("Replace"), b -> {
                    String picked = NonprofitBackgrounds.openFilePicker();
                    if (picked != null) {
                        String n = NonprofitBackgrounds.replaceImage(currentName(), picked);
                        files = NonprofitBackgrounds.available();
                        int i = n == null ? -1 : files.indexOf(n);
                        idx = i >= 0 ? i + 1 : idx;
                        clearAndInit();
                    }
                }).dimensions(x0 + (bw + gap), row1, bw, 20).build();
                rep.setTooltip(Tooltip.of(Text.literal(
                        "Swap the image/video and keep all the icons, fonts and music")));
                addDrawableChild(rep);

                addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), b -> {
                    String name = currentName();
                    String nn = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_inputBox(
                            "Rename background", "New name (the file extension is kept):",
                            name.replaceAll("\\.[^.]+$", ""));
                    if (nn != null && !nn.isBlank()) {
                        String renamed = NonprofitBackgrounds.renameBackground(name, nn.trim());
                        files = NonprofitBackgrounds.available();
                        int i = renamed == null ? -1 : files.indexOf(renamed);
                        idx = i >= 0 ? i + 1 : idx;
                        clearAndInit();
                    }
                }).dimensions(x0 + (bw + gap) * 2, row1, bw, 20).build());

                addDrawableChild(ButtonWidget.builder(Text.literal("Export"), b -> {
                    String dest = BackgroundPackage.pickExportZip(currentName().replaceAll("\\.[^.]+$", ""));
                    if (dest != null) BackgroundPackage.export(currentName(), dest);
                }).dimensions(x0 + (bw + gap) * 3, row1, bw, 20).build());

                boolean canDelete = files.size() >= 2;
                deleteBtn = ButtonWidget.builder(Text.literal("§cDelete"), b -> onDelete())
                        .dimensions(x0 + (bw + gap) * 4, row1, bw, 20).build();
                deleteBtn.active = canDelete;
                if (!canDelete)
                    deleteBtn.setTooltip(Tooltip.of(Text.literal(
                            "Add a second background first — you can't delete your only one")));
                addDrawableChild(deleteBtn);
            }
        }

        int by = this.height - 26;
        var impF = ButtonWidget.builder(Text.literal("Import File"), b -> {
            String zip = BackgroundPackage.pickImportZip();
            if (zip != null) {
                String n = BackgroundPackage.importZip(zip);
                files = NonprofitBackgrounds.available();
                int i = n == null ? -1 : files.indexOf(n);
                idx = i >= 0 ? i + 1 : 0;
                clearAndInit();
            }
        }).dimensions(cx - 196, by, 92, 20).build();
        impF.setTooltip(Tooltip.of(Text.literal("Import a shared skin from a .zip on your computer")));
        addDrawableChild(impF);

        var db = ButtonWidget.builder(Text.literal("🌐 Skin Hub"),
                b -> this.client.setScreen(new SkinDatabaseScreen(this, isAdd() || isDefault() ? null : currentName())))
                .dimensions(cx - 100, by, 92, 20).build();
        db.setTooltip(Tooltip.of(Text.literal(
                "Browse, vote on, and share community skins online — and upload your own")));
        addDrawableChild(db);

        var shuffle = ButtonWidget.builder(shuffleLabel(), b -> {
            NonprofitBackgrounds.toggleShuffle();
            b.setMessage(shuffleLabel());
        }).dimensions(cx - 4, by, 92, 20).build();
        shuffle.setTooltip(Tooltip.of(Text.literal(
                "Pick a random background every time the game starts")));
        addDrawableChild(shuffle);

        addDrawableChild(ButtonWidget.builder(Text.literal("Save & Exit"), b -> {
            if (!isAdd()) NonprofitBackgrounds.select(currentName());
            this.close();
        }).dimensions(cx + 92, by, 104, 20).build());
    }

    private static Text shuffleLabel() {
        return Text.literal("Shuffle: " + (NonprofitBackgrounds.isShuffle() ? "§aon" : "§7off"));
    }

    private void onDelete() {
        deleteStage++;
        if (deleteStage == 1) deleteBtn.setMessage(Text.literal("§eSure? (1/2)"));
        else if (deleteStage == 2) deleteBtn.setMessage(Text.literal("§4Really? (2/2)"));
        else {
            NonprofitBackgrounds.deleteBackground(currentName());
            files = NonprofitBackgrounds.available();
            idx = Math.max(0, Math.min(idx, addIndex()));
            clearAndInit();
        }
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
            drawMockup(ctx, isDefault() ? "" : currentName(), px, py, pw, ph);
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

    /**
     * A faithful, scaled-down title screen for {@code name}: its background + gradients + brand +
     * every visible element with that skin's per-slot icon, font, label, sizes and drag positions —
     * all through {@link TitleLayout}, so it always matches the real thing.
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

        Identifier frame = (name == null || name.isEmpty()) ? null : NonprofitBackgrounds.previewFrame(name);
        if (frame != null) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0f, 0f, VW, VH, VW, VH, 0xFFFFFFFF);
        else ctx.fill(0, 0, VW, VH, 0xFF000000);

        String layout = FontStore.layoutFor(bg);
        if ("center".equals(layout)) {
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
        } else {
            int gw = Math.max(1, VW / 3);
            for (int x = 0; x < gw; x++) {
                int a = (int) ((1f - x / (float) gw) * 100f);
                if (a > 0) ctx.fill(x, 0, x + 1, VH, a << 24);
            }
        }

        Map<String, TitleLayout.Box> boxes = TitleLayout.compute(bg, VW, VH);
        for (var e : boxes.entrySet()) {
            String slot = e.getKey();
            if (FontStore.hiddenFor(bg, slot)) continue;
            TitleLayout.Box b = e.getValue();
            switch (slot) {
                case "version" -> {
                    Identifier brand = IconStore.resolved(bg, "version");
                    if (brand != null)
                        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, brand, b.x(), b.y(), 0f, 0f,
                                b.w(), b.h(), b.w(), b.h(), 0xFFFFFFFF);
                }
                case "versiontag" -> {
                    MutableText ver = Text.literal("Minecraft 1.21.11 © Mojang AB");
                    Identifier vf = FontStore.fontFor(bg, "versiontag");
                    if (vf != null) ver = ver.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(vf)));
                    float vsc = b.fontScale() * FontStore.sizeFor(bg, "versiontag");
                    m.pushMatrix();
                    m.translate((float) b.x(), (float) b.y());
                    m.scale(vsc, vsc);
                    ctx.drawTextWithShadow(tr, ver, 0, 0, 0xCCFFFFFF);
                    m.popMatrix();
                }
                default -> {
                    String label = FontStore.labelFor(bg, slot, TitleLayout.defaultLabel(slot, layout));
                    Identifier tex = IconStore.resolved(bg, slot);
                    int is = Math.max(4, Math.round(b.iconSize() * FontStore.iconSizeFor(bg, slot)));
                    int ix = label.isEmpty() ? b.x() + (b.w() - is) / 2 : b.x() + 4;
                    int iy = b.y() + (b.h() - is) / 2;
                    int iconOffset = 0;
                    if (tex != null) {
                        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, ix, iy, 0f, 0f, is, is, is, is, 0xFFFFFFFF);
                        iconOffset = is + 8;
                    }
                    if (!label.isEmpty())
                        mockText(ctx, tr, bg, slot, label, b.x() + 4 + iconOffset,
                                b.y() + (b.h() - (int) (8 * b.fontScale())) / 2, b.fontScale());
                }
            }
        }

        m.popMatrix();
        ctx.disableScissor();
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
        IconStore.setEditTarget(null);
        NonprofitBackgrounds.reload();
        this.client.setScreen(parent);
    }
}
