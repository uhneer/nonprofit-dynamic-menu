package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.IconStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

/**
 * A flat title-screen menu entry: icon + scaled label (+ optional dimmed suffix).
 *
 * The icon resolves through {@link IconStore} by {@code iconKey}; the label's font resolves through
 * {@link FontStore} by {@code fontKey} (per-background override → default). Hover paints a
 * translucent highlight; on appear it slides in (30px offset, ×0.85/frame decay, +0.1·delta opacity).
 */
public class MenuButton extends ClickableWidget {

    public enum Icon { PLAY, MULTIPLAYER, OPTIONS, MODS, POWER, CLOSE, EDIT, NONE }

    private final Supplier<String> label;
    private final Supplier<String> suffix;   // nullable (e.g. mod count)
    private final Icon icon;                  // vector fallback when no texture resolves
    private final String iconKey;             // nullable: IconStore slot key
    private final String fontKey;             // nullable: FontStore slot key
    private final int iconSize;
    private final float fontScale;
    private final Runnable action;
    private boolean centerLabel = false;

    private float offsetX;
    private float opacity = 0f;
    private float hoverAlpha = 0f;

    public MenuButton(int x, int y, int w, int h, Supplier<String> label, Supplier<String> suffix,
                      Icon icon, String iconKey, int iconSize, float fontScale, String fontKey,
                      Runnable action, boolean fromRight) {
        super(x, y, w, h, Text.literal(""));
        this.label = label;
        this.suffix = suffix;
        this.icon = icon;
        this.iconKey = iconKey;
        this.fontKey = fontKey;
        this.iconSize = iconSize;
        this.fontScale = fontScale;
        this.action = action;
        this.offsetX = fromRight ? 30f : -30f;
    }

    public MenuButton centered() { this.centerLabel = true; return this; }

    private MutableText styled(String s) {
        MutableText t = Text.literal(s);
        if (fontKey != null) {
            Identifier f = FontStore.fontFor(fontKey);
            if (f != null) t = t.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(f)));
        }
        return t;
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        opacity = Math.min(opacity + delta * 0.1f, 1f);
        offsetX *= 0.85f;
        if (Math.abs(offsetX) < 0.5f) offsetX = 0f;
        if (opacity <= 0.01f) return;

        var tr = MinecraftClient.getInstance().textRenderer;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        var m = ctx.getMatrices();
        boolean slid = Math.abs(offsetX) > 0.5f;
        if (slid) { m.pushMatrix(); m.translate(offsetX, 0f); }

        float target = isHovered() ? 0.6f : 0f;
        hoverAlpha += (target - hoverAlpha) * 0.1f;
        if (hoverAlpha > 0.02f) ctx.fill(x, y, x + w, y + h, ((int) (hoverAlpha * 255f)) << 24);

        int alpha = (int) (opacity * 255f);
        int col = (alpha << 24) | 0xFFFFFF;

        String s = label.get();
        boolean iconOnly = (s == null || s.isEmpty());
        Identifier tex = (iconKey != null) ? IconStore.resolved(iconKey) : null;
        int iconOffset = 0;
        if (tex != null || icon != Icon.NONE) {
            // Per-background user icon size multiplier on top of the slot's base size.
            int is = Math.max(4, Math.round(iconSize
                    * (iconKey != null ? FontStore.iconSizeFor(iconKey) : 1.0f)));
            int ix = iconOnly ? x + (w - is) / 2 : x + 4;          // centered on icon-only buttons
            int iconY = y + (h - is) / 2;
            if (tex != null)
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, ix, iconY, 0f, 0f,
                        is, is, is, is, col);
            else
                Icons.draw(ctx, icon, ix, iconY, is, col);
            iconOffset = is + 8;
        }

        if (!iconOnly) {
            MutableText t = styled(s);
            // Per-background user size multiplier on top of the slot's base scale.
            float fs = fontScale * (fontKey != null ? FontStore.sizeFor(fontKey) : 1.0f);
            int textW = (int) (tr.getWidth(t) * fs);
            int textX = centerLabel ? x + Math.round((w - textW) / 2f) : x + 4 + iconOffset;
            int textY = y + (h - (int) (8 * fs)) / 2;
            m.pushMatrix();
            m.translate(textX, textY);
            m.scale(fs, fs);
            ctx.drawTextWithShadow(tr, t, 0, 0, col);
            m.popMatrix();

            if (suffix != null) {
                String suf = suffix.get();
                if (suf != null && !suf.isEmpty()) {
                    int sx = textX + textW + 10;
                    ctx.drawTextWithShadow(tr, suf, sx, y + (h - 8) / 2, (alpha << 24) | 0x9A9A9A);
                }
            }
        }

        if (slid) m.popMatrix();
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        try { action.run(); } catch (Throwable ignored) { }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}
