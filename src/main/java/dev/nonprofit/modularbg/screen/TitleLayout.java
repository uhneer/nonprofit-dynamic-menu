package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.FontStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single source of truth for where every title-screen element sits. Computes the base "left"
 * or "center" layout for a background, then applies the user's per-slot drag positions
 * ({@code <slot>.pos}, stored as permille of the window so they survive resolution and GUI-scale
 * changes). Used by {@link ModularTitleScreen}, the carousel mockup, and the edit overlay so all
 * three always agree.
 */
public final class TitleLayout {

    /** One positioned element. {@code labeled} = draws a text label (vs icon-only). */
    public record Box(int x, int y, int w, int h, int iconSize, float fontScale, boolean labeled) {
        public Box at(int nx, int ny) { return new Box(nx, ny, w, h, iconSize, fontScale, labeled); }
    }

    public static final int LEFT = 20, COL_W = 120, GAP = 4;
    public static final int H_VER = 50, H_PLAY = 45, H_ROW = 20;

    /** Default labels per slot (what {@code labelFor} falls back to). */
    public static String defaultLabel(String slot, String layout) {
        return switch (slot) {
            case "play"        -> "PLAY";
            case "multiplayer" -> "Multiplayer";
            case "options"     -> "Options";
            case "mods"        -> "Mods";
            case "close"       -> "center".equals(layout) ? "Quit" : "";
            default            -> "";
        };
    }

    private TitleLayout() {}

    /**
     * Slot → box for this background at this window size, in screen order:
     * version (brand bar), play, multiplayer, options, mods, close, versiontag.
     */
    /** The drag grid (px). Drag positions resolve back onto this so elements always line up. */
    public static final int GRID = 8;

    public static Map<String, Box> compute(String bgKey, int width, int height) {
        String layout = FontStore.layoutFor(bgKey);
        Map<String, Box> m = new LinkedHashMap<>();
        if ("center".equals(layout)) {
            int brandW = 192, brandH = 80, playW = 150, colW = 110, colGap = 12;
            int total = brandH + 12 + H_PLAY + 10 + H_ROW * 2 + GAP;
            int top = Math.max(16, (height - total) / 2 - 10);
            int cx = width / 2;
            m.put("version", new Box(cx - brandW / 2, top, brandW, brandH, 0, 1f, false));
            int yPlay = top + brandH + 12;
            m.put("play", new Box(cx - playW / 2, yPlay, playW, H_PLAY, 35, 1.8f, true));
            int yGrid = yPlay + H_PLAY + 10;
            int lx = cx - colGap / 2 - colW, rx = cx + colGap / 2;
            m.put("multiplayer", new Box(lx, yGrid, colW, H_ROW, 12, 1f, true));
            m.put("options", new Box(lx, yGrid + H_ROW + GAP, colW, H_ROW, 12, 1f, true));
            m.put("mods", new Box(rx, yGrid, colW, H_ROW, 12, 1f, true));
            m.put("close", new Box(rx, yGrid + H_ROW + GAP, colW, H_ROW, 12, 1f, true));
        } else {
            int total = H_VER + H_PLAY + H_ROW * 3 + GAP * 4;
            int top = (height - total) / 2;
            m.put("version", new Box(LEFT, top, COL_W, H_VER, 0, 1f, false));
            int yPlay = top + H_VER + GAP;
            m.put("play", new Box(LEFT, yPlay, COL_W, H_PLAY, 35, 1.8f, true));
            int yMp = yPlay + H_PLAY + GAP;
            m.put("multiplayer", new Box(LEFT, yMp, COL_W, H_ROW, 12, 1f, true));
            int yOpt = yMp + H_ROW + GAP;
            m.put("options", new Box(LEFT, yOpt, COL_W, H_ROW, 12, 1f, true));
            int yMods = yOpt + H_ROW + GAP;
            m.put("mods", new Box(LEFT, yMods, COL_W, H_ROW, 12, 1f, true));
            m.put("close", new Box(width - 24, 8, 16, 16, 16, 1f, false));
        }
        m.put("versiontag", new Box(10, height - 12, 150, 8, 0, 0.75f, true));

        // Drag positions apply only in the "custom" layout (permille of the window). The pixel
        // result is re-snapped to the grid so permille rounding can never knock elements out of
        // alignment with each other.
        if ("custom".equals(layout)) {
            for (var e : m.entrySet()) {
                int[] p = FontStore.posFor(bgKey, e.getKey());
                if (p != null) {
                    int x = Math.round(Math.round(p[0] * width / 1000f) / (float) GRID) * GRID;
                    int y = Math.round(Math.round(p[1] * height / 1000f) / (float) GRID) * GRID;
                    e.setValue(e.getValue().at(x, y));
                }
            }
        }
        return m;
    }
}
