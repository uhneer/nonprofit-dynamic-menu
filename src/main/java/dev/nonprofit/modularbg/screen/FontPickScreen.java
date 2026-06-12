package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Font picker for one text slot. Lists every font the game has loaded and previews the slot's sample
 * text in each; click one to apply it (per-background). Paginated so it never overflows.
 */
public class FontPickScreen extends Screen {

    private static final Identifier DEFAULT = Identifier.of("minecraft", "default");

    private final Screen parent;
    private final String slot, sample;
    private List<Identifier> fonts;
    private int page = 0, perPage, rowH = 26, listTop = 40;
    private ButtonWidget dbBtn;
    private String status = null;

    public FontPickScreen(Screen parent, String slot, String sample) {
        super(Text.literal("Choose font — " + slot));
        this.parent = parent;
        this.slot = slot;
        this.sample = (sample == null || sample.isEmpty()) ? "Sample" : sample;
    }

    @Override
    protected void init() {
        fonts = FontStore.availableFonts();
        perPage = Math.max(1, (this.height - listTop - 40) / rowH);
        int cx = this.width / 2, by = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("◄ Prev"), b -> { if (page > 0) page--; })
                .dimensions(cx - 170, by, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Next ►"), b -> {
            if ((page + 1) * perPage < fonts.size()) page++;
        }).dimensions(cx - 107, by, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("＋ Add Font"), b -> {
            String picked = NonprofitBackgrounds.openFontPicker();
            if (picked != null) {
                Identifier added = FontStore.addFontFromFile(picked);
                // Surface failures instead of silently doing nothing (broken/odd font files).
                status = added != null ? "§a✔ font installed — click it in the list to use it"
                        : "§cthat font could not be installed — the file may be damaged (details in the log)";
                this.clearAndInit();
            }
        }).dimensions(cx - 44, by, 80, 20).build());
        dbBtn = ButtonWidget.builder(Text.literal("🔍 Search Fonts"), b -> {
                    dev.nonprofit.modularbg.background.Hints.markSeen("fontdb");
                    this.client.setScreen(new FontDatabaseScreen(this, slot));
                })
                .dimensions(cx + 39, by, 96, 20).build();
        dbBtn.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                "Browse a database of 10,000+ free font styles (Google Fonts, all open source) and import any in one click")));
        addDrawableChild(dbBtn);
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + 139, by, 56, 20).build());
    }

    private int rowAt(int my) {
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < fonts.size(); i++) {
            int ry = listTop + i * rowH;
            if (my >= ry && my < ry + rowH - 2) return start + i;
        }
        return -1;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        ctx.drawCenteredTextWithShadow(tr, this.title, this.width / 2, 16, 0xFFFFFFFF);

        Identifier current = FontStore.fontFor(slot);
        if (current == null) current = DEFAULT;
        int x0 = this.width / 2 - 170, x1 = this.width / 2 + 170;
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < fonts.size(); i++) {
            Identifier f = fonts.get(start + i);
            int ry = listTop + i * rowH;
            boolean hov = mouseX >= x0 && mouseX <= x1 && mouseY >= ry && mouseY < ry + rowH - 2;
            boolean sel = f.equals(current);
            ctx.fill(x0, ry, x1, ry + rowH - 2, sel ? 0x6633CC55 : hov ? 0x44FFFFFF : 0x33000000);
            // left: font id (default font, readable); right: sample rendered in this font
            ctx.drawTextWithShadow(tr, f.equals(DEFAULT) ? "Default" : f.toString(),
                    x0 + 6, ry + (rowH - 2 - 8) / 2, sel ? 0xFF8CFFA8 : 0xFFFFFFFF);
            MutableText preview = Text.literal(sample).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(f)));
            int pw = tr.getWidth(preview);
            ctx.drawTextWithShadow(tr, preview, x1 - 6 - pw, ry + (rowH - 2 - 8) / 2, 0xFFE8E8E8);
        }
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal(status != null ? status
                        : "§8page " + (page + 1) + " / " + Math.max(1, (fonts.size() + perPage - 1) / perPage)),
                this.width / 2, this.height - 40, 0xFFFFFFFF);

        // First-run discoverability: breathe around the Database button until it's clicked once.
        if (dbBtn != null && dev.nonprofit.modularbg.background.Hints.shouldShow("fontdb"))
            dev.nonprofit.modularbg.background.Hints.breathe(ctx, dbBtn.getX(), dbBtn.getY(),
                    dbBtn.getWidth(), dbBtn.getHeight());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical < 0 && (page + 1) * perPage < fonts.size()) page++;
        else if (vertical > 0 && page > 0) page--;
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int row = rowAt((int) click.y());
        if (row >= 0) {
            Identifier f = fonts.get(row);
            FontStore.setFont(slot, f);   // setFont treats minecraft:default as "clear"
            this.close();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
