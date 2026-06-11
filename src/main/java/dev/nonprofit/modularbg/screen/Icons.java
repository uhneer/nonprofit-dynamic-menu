package dev.nonprofit.modularbg.screen;

import net.minecraft.client.gui.DrawContext;

/**
 * Default monochrome vector icons for the title-screen buttons, drawn with plain fills so they
 * need no texture assets. These are intentionally simple — each background can override any of
 * them with a custom PNG via the icon editor, so these are just clean fallbacks.
 *
 * All coordinates are within a {@code size x size} box anchored at (x, y); {@code col} is ARGB.
 */
public final class Icons {
    private Icons() {}

    static void rect(DrawContext c, int x, int y, int w, int h, int col) {
        if (w > 0 && h > 0) c.fill(x, y, x + w, y + h, col);
    }

    static void disc(DrawContext c, int cx, int cy, float r, int col) {
        int b = (int) Math.ceil(r);
        for (int dy = -b; dy <= b; dy++)
            for (int dx = -b; dx <= b; dx++)
                if (dx * dx + dy * dy <= r * r) c.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
    }

    static void ring(DrawContext c, int cx, int cy, float ro, float ri, int col) {
        int b = (int) Math.ceil(ro);
        for (int dy = -b; dy <= b; dy++)
            for (int dx = -b; dx <= b; dx++) {
                double d2 = dx * dx + dy * dy;
                if (d2 <= ro * ro && d2 >= ri * ri) c.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
            }
    }

    /** Thick line by stepping along the segment and stamping small squares. */
    static void line(DrawContext c, float x1, float y1, float x2, float y2, float th, int col) {
        float dx = x2 - x1, dy = y2 - y1;
        int n = (int) Math.ceil(Math.max(1, Math.sqrt(dx * dx + dy * dy)));
        int t = Math.max(1, Math.round(th / 2f));
        for (int i = 0; i <= n; i++) {
            float f = i / (float) n;
            int px = Math.round(x1 + dx * f), py = Math.round(y1 + dy * f);
            c.fill(px - t, py - t, px + t, py + t, col);
        }
    }

    public static void draw(DrawContext c, MenuButton.Icon icon, int x, int y, int s, int col) {
        switch (icon) {
            case PLAY -> { // sword pointing up-right
                line(c, x + 0.40f * s, y + 0.60f * s, x + 0.86f * s, y + 0.14f * s, 0.17f * s, col); // blade
                line(c, x + 0.16f * s, y + 0.84f * s, x + 0.40f * s, y + 0.60f * s, 0.17f * s, col); // grip
                line(c, x + 0.30f * s, y + 0.44f * s, x + 0.56f * s, y + 0.70f * s, 0.13f * s, col); // guard
            }
            case MULTIPLAYER -> { // ascending bars
                int bw = Math.max(2, (int) (0.20f * s));
                int bottom = y + (int) (0.88f * s);
                int lx = x + (int) (0.12f * s);
                int step = (int) (0.30f * s);
                rect(c, lx,            bottom - (int) (0.40f * s), bw, (int) (0.40f * s), col);
                rect(c, lx + step,     bottom - (int) (0.64f * s), bw, (int) (0.64f * s), col);
                rect(c, lx + 2 * step, bottom - (int) (0.88f * s), bw, (int) (0.88f * s), col);
            }
            case OPTIONS -> { // cog
                int cx = x + s / 2, cy = y + s / 2;
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    int tx = (int) (cx + Math.cos(a) * 0.46f * s);
                    int ty = (int) (cy + Math.sin(a) * 0.46f * s);
                    int hw = Math.max(1, (int) (0.08f * s));
                    rect(c, tx - hw, ty - hw, hw * 2, hw * 2, col);
                }
                ring(c, cx, cy, 0.40f * s, 0.22f * s, col);
                disc(c, cx, cy, 0.11f * s, col);
            }
            case MODS -> { // isometric cube outline
                int cx = x + s / 2;
                int top = y + (int) (0.12f * s), midY = y + (int) (0.40f * s);
                int botMid = y + (int) (0.60f * s), bot = y + (int) (0.86f * s);
                int lft = x + (int) (0.14f * s), rgt = x + (int) (0.86f * s);
                float th = 0.10f * s;
                line(c, cx, top, rgt, midY, th, col);
                line(c, rgt, midY, cx, botMid, th, col);
                line(c, cx, botMid, lft, midY, th, col);
                line(c, lft, midY, cx, top, th, col);
                line(c, lft, midY, lft, bot - (int) (0.20f * s), th, col);
                line(c, rgt, midY, rgt, bot - (int) (0.20f * s), th, col);
                line(c, cx, botMid, cx, bot, th, col);
                line(c, lft, bot - (int) (0.20f * s), cx, bot, th, col);
                line(c, rgt, bot - (int) (0.20f * s), cx, bot, th, col);
            }
            case POWER -> { // power symbol: open ring + vertical bar
                int cx = x + s / 2, cy = y + s / 2;
                float r = 0.40f * s, th = Math.max(2f, 0.13f * s);
                int b = (int) Math.ceil(r) + 1;
                double gap = Math.toRadians(42);
                for (int dy = -b; dy <= b; dy++)
                    for (int dx = -b; dx <= b; dx++) {
                        double d = Math.sqrt(dx * dx + dy * dy);
                        if (d < r - th || d > r) continue;
                        double a = Math.atan2(dy, dx) + Math.PI / 2;
                        while (a > Math.PI) a -= 2 * Math.PI;
                        while (a < -Math.PI) a += 2 * Math.PI;
                        if (Math.abs(a) < gap) continue;
                        c.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
                    }
                int bw = Math.max(1, (int) (th / 2f));
                rect(c, cx - bw, y + (int) (0.10f * s), bw * 2, (int) (0.42f * s), col);
            }
            case CLOSE -> { // X
                line(c, x + 0.22f * s, y + 0.22f * s, x + 0.78f * s, y + 0.78f * s, 0.14f * s, col);
                line(c, x + 0.78f * s, y + 0.22f * s, x + 0.22f * s, y + 0.78f * s, 0.14f * s, col);
            }
            case EDIT -> { // pencil
                line(c, x + 0.24f * s, y + 0.76f * s, x + 0.70f * s, y + 0.30f * s, 0.17f * s, col); // body
                line(c, x + 0.60f * s, y + 0.20f * s, x + 0.80f * s, y + 0.40f * s, 0.17f * s, col); // head
                rect(c, x + (int) (0.20f * s), y + (int) (0.72f * s),
                        Math.max(1, (int) (0.12f * s)), Math.max(1, (int) (0.12f * s)), col);   // tip
            }
            default -> { }
        }
    }
}
