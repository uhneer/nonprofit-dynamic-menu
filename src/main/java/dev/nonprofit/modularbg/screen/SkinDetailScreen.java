package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.SkinHub;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * A hub listing opened full-page: the skin's name, author, upload date, favorite count with the
 * pink ♥ button, the green ＋ Add button (installs without leaving the hub), and a scrolling
 * spread of every asset the skin ships (fonts, icons, music, video) by name.
 */
public class SkinDetailScreen extends Screen {

    private final Screen parent;
    private final SkinDatabaseScreen.Entry entry;
    private final Consumer<SkinDatabaseScreen.Entry> onAdd;
    private double scroll = 0;
    private String status;

    public SkinDetailScreen(Screen parent, SkinDatabaseScreen.Entry entry,
                            Consumer<SkinDatabaseScreen.Entry> onAdd) {
        super(Text.literal(entry.name()));
        this.parent = parent;
        this.entry = entry;
        this.onAdd = onAdd;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close())
                .dimensions(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        int cx = this.width / 2, w = Math.min(this.width - 24, 420), x = cx - w / 2;
        int y = 18 - (int) scroll;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, y);
        m.scale(1.5f, 1.5f);
        ctx.drawTextWithShadow(tr, entry.name(), -tr.getWidth(entry.name()) / 2, 0, 0xFFFFFFFF);
        m.popMatrix();
        y += 18;
        ctx.drawCenteredTextWithShadow(tr, Text.literal("§7by " + entry.author()
                + (entry.date().isEmpty() ? "" : " §8• uploaded " + entry.date())), cx, y, 0xFFFFFFFF);
        y += 14;
        ctx.drawCenteredTextWithShadow(tr, Text.literal("§8" + String.join(", ", entry.cats())), cx, y, 0xFFFFFFFF);
        y += 18;

        // ♥ favorite + count, ＋ Add — same controls as the list, bigger.
        boolean fav = SkinHub.isFavorite(entry.id());
        int count = entry.votes() + (fav ? 1 : 0);
        int hx = cx - 64, hy = y;
        boolean hhov = mouseX >= hx && mouseX < hx + 60 && mouseY >= hy && mouseY < hy + 18;
        ctx.fill(hx, hy, hx + 60, hy + 18, hhov ? 0xFFE9579C : fav ? 0xF0D63384 : 0xB060223C);
        ctx.drawCenteredTextWithShadow(tr, Text.literal("♥ " + count), hx + 30, hy + 5, 0xFFFFFFFF);
        int ax = cx + 4, ay = y;
        boolean ahov = mouseX >= ax && mouseX < ax + 60 && mouseY >= ay && mouseY < ay + 18;
        ctx.fill(ax, ay, ax + 60, ay + 18, ahov ? 0xFF3BAF5C : 0xE62E8C49);
        ctx.drawCenteredTextWithShadow(tr, Text.literal("＋ Add"), ax + 30, ay + 5, 0xFFFFFFFF);
        favRect = new int[]{ hx, hy, 60, 18 };
        addRect = new int[]{ ax, ay, 60, 18 };
        y += 30;

        ctx.drawTextWithShadow(tr, Text.literal("§7What's inside"), x, y, 0xFFFFFFFF);
        y += 14;
        if (entry.assets().isEmpty()) {
            ctx.drawTextWithShadow(tr, Text.literal("§8(asset list arrives with the hub launch)"), x, y, 0xFFFFFFFF);
            y += 12;
        } else {
            for (String a : entry.assets()) {
                ctx.fill(x, y, x + w, y + 14, 0x44000000);
                ctx.drawTextWithShadow(tr, Text.literal("§f" + a), x + 6, y + 3, 0xFFFFFFFF);
                y += 16;
            }
        }
        if (status != null)
            ctx.drawCenteredTextWithShadow(tr, Text.literal(status), cx, this.height - 42, 0xFFFFFFFF);
    }

    private int[] favRect, addRect;

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        if (favRect != null && mx >= favRect[0] && mx < favRect[0] + favRect[2]
                && my >= favRect[1] && my < favRect[1] + favRect[3]) {
            boolean now = SkinHub.toggleFavorite(entry.id());
            status = now ? "§d♥ added to favorites" : "§7removed from favorites";
            return true;
        }
        if (addRect != null && mx >= addRect[0] && mx < addRect[0] + addRect[2]
                && my >= addRect[1] && my < addRect[1] + addRect[3]) {
            status = "§einstalling...";
            onAdd.accept(entry);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int contentH = 120 + entry.assets().size() * 16;
        scroll = Math.max(0, Math.min(Math.max(0, contentH - (this.height - 60)), scroll - vertical * 24));
        return true;
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
