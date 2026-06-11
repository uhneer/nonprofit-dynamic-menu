package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.IconStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Per-icon editor: the chosen slot's icon shown exactly as it appears on the title screen (drawn at
 * the slot's real aspect — the brand bar is wide, menu icons are square — not forced to a square),
 * with the slot's target shape + the source image's canvas size, and buttons to choose a new image
 * (any size — scaled to fill the slot) or reset to the bundled default.
 */
public class IconEditScreen extends Screen {

    private final Screen parent;
    private final String slot;
    private final String label;
    private final int slotW, slotH;   // the shape this slot is drawn at on the title screen

    public IconEditScreen(Screen parent, String slot, String label, int slotW, int slotH) {
        super(Text.literal("Edit icon — " + label));
        this.parent = parent;
        this.slot = slot;
        this.label = label;
        this.slotW = Math.max(1, slotW);
        this.slotH = Math.max(1, slotH);
    }

    @Override
    protected void init() {
        int cx = this.width / 2, by = this.height - 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Choose Image…"), b -> choose())
                .dimensions(cx - 210, by, 100, 20).build());
        var db = ButtonWidget.builder(Text.literal("🔍 Icon Database"),
                b -> this.client.setScreen(new IconDatabaseScreen(this, slot)))
                .dimensions(cx - 105, by, 104, 20).build();
        db.setTooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(
                "Browse a database of 6,000+ free open source icons and use any in one click — the search starts on '"
                        + slot + "'")));
        addDrawableChild(db);
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset to Default"), b -> IconStore.clearIcon(slot))
                .dimensions(cx + 4, by, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + 109, by, 100, 20).build());
    }

    private void choose() {
        String picked = NonprofitBackgrounds.openFilePicker();
        if (picked != null) IconStore.setIcon(slot, picked);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        ctx.drawCenteredTextWithShadow(tr, this.title, this.width / 2, 16, 0xFFFFFFFF);

        // Preview box at the slot's real aspect — the icon is drawn stretched to fill it, exactly
        // as it appears on the title screen (so a wide brand looks wide, a square icon looks square).
        int area = Math.min(170, this.height - 132);
        float sc = Math.min(area / (float) slotW, area / (float) slotH);
        int pw = Math.max(8, Math.round(slotW * sc)), ph = Math.max(8, Math.round(slotH * sc));
        int px = this.width / 2 - pw / 2, py = 44 + (area - ph) / 2;
        ctx.fill(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF2A2A30);
        ctx.fill(px, py, px + pw, py + ph, 0xC0101013);

        Identifier tex = IconStore.resolved(slot);
        if (tex != null)
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, px, py, 0f, 0f, pw, ph, pw, ph, 0xFFFFFFFF);
        else
            ctx.drawCenteredTextWithShadow(tr, Text.literal("§7(drawn icon — no image)"),
                    this.width / 2, py + ph / 2 - 4, 0xFFFFFFFF);

        int iy = 44 + area + 10;
        int g = Math.max(1, gcd(slotW, slotH));
        String slotShape = (slotW == slotH) ? "square (1:1)" : (slotW / g) + ":" + (slotH / g);
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§fSlot shape: " + slotW + " × " + slotH + "  §7(" + slotShape + ")"),
                this.width / 2, iy, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§8Images are scaled to fill the slot — match this shape to avoid stretching."),
                this.width / 2, iy + 12, 0xFFFFFFFF);

        if (IconStore.hasCustom(slot)) {
            int[] d = IconStore.dimsFor(slot);
            if (d != null) {
                int dg = Math.max(1, gcd(d[0], d[1]));
                boolean match = (long) d[0] * slotH == (long) d[1] * slotW;
                ctx.drawCenteredTextWithShadow(tr,
                        Text.literal("§fYour image: " + d[0] + " × " + d[1] + "  §7(" + (d[0] / dg) + ":" + (d[1] / dg) + ")"
                                + (match ? "  §amatches ✓" : "  §ewill be stretched")),
                        this.width / 2, iy + 26, 0xFFFFFFFF);
            }
        } else {
            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("§7Using the default icon — choose an image to set a custom one."),
                    this.width / 2, iy + 26, 0xFFFFFFFF);
        }
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    @Override
    public void close() { this.client.setScreen(parent); }
}
