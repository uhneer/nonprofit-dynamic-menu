package dev.nonprofit.modularbg.background;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Shared full-screen background drawing for every screen we restyle (title, menus, loading).
 *
 * The current frame comes from {@link NonprofitBackgrounds} — whatever image/GIF the user has
 * selected — or {@code null} when no custom background is set (the caller then keeps its default).
 * Fully guarded: never throws.
 */
public final class BackgroundRenderer {

    private BackgroundRenderer() {}

    /** The selected background's current frame, or null when none is set. */
    public static Identifier currentFrame() {
        try {
            return NonprofitBackgrounds.currentFrame();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Draws the current background full-screen. Returns true if it drew (caller should skip its own). */
    public static boolean draw(DrawContext context) {
        try {
            Identifier frame = currentFrame();
            if (frame == null) return false;
            MinecraftClient c = MinecraftClient.getInstance();
            int w = c.getWindow().getScaledWidth();
            int h = c.getWindow().getScaledHeight();
            context.drawTexture(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0f, 0f, w, h, w, h);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Background for non–title-screen menus (options, world select, loading, …): the selected
     * background softened with a cheap multi-tap blur and dimmed with a light darken overlay, so menu
     * widgets stay readable. Returns true if it drew (caller should skip its own background).
     *
     * <p>The "blur" is the background re-drawn at a ring of small offsets at low opacity — not a true
     * Gaussian, but it reads as a soft defocus and costs only a handful of full-screen quads.
     */
    public static boolean drawMenu(DrawContext context) {
        try {
            Identifier frame = currentFrame();
            if (frame == null) return false;
            MinecraftClient c = MinecraftClient.getInstance();
            int w = c.getWindow().getScaledWidth();
            int h = c.getWindow().getScaledHeight();
            int scale = Math.max(1, (int) c.getWindow().getScaleFactor());
            int off = 2 * scale;                                  // offset in scaled GUI px

            context.drawTexture(RenderPipelines.GUI_TEXTURED, frame, 0, 0, 0f, 0f, w, h, w, h, 0xFFFFFFFF);
            // Two rings of offset copies at low opacity → soft defocus.
            int[][] taps = {
                {-off, 0}, {off, 0}, {0, -off}, {0, off},
                {-off, -off}, {off, -off}, {-off, off}, {off, off},
                {-2 * off, 0}, {2 * off, 0}, {0, -2 * off}, {0, 2 * off},
            };
            for (int[] t : taps)
                context.drawTexture(RenderPipelines.GUI_TEXTURED, frame, t[0], t[1], 0f, 0f, w, h, w, h, 0x30FFFFFF);
            // Light darken so foreground UI is legible.
            context.fill(0, 0, w, h, 0x73000000);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
