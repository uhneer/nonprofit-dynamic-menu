package dev.nonprofit.modularbg.background;

import net.minecraft.client.gui.DrawContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * One-time discoverability hints: a breathing highlight drawn around a control until the user
 * clicks it once. Seen keys persist in {@code config/nonprofit-backgrounds/.hints} so the glow
 * only ever shows on a fresh install.
 */
public final class Hints {

    private static Set<String> seen = null;

    private Hints() {}

    private static Path file() {
        return NonprofitBackgrounds.getFolder().resolve(".hints");
    }

    private static Set<String> seen() {
        if (seen == null) {
            seen = new HashSet<>();
            try {
                Path f = file();
                if (Files.exists(f))
                    for (String l : Files.readAllLines(f, StandardCharsets.UTF_8))
                        if (!l.isBlank()) seen.add(l.trim());
            } catch (Throwable ignored) { }
        }
        return seen;
    }

    public static boolean shouldShow(String key) {
        return !seen().contains(key);
    }

    public static void markSeen(String key) {
        if (!seen().add(key)) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : seen) sb.append(s).append('\n');
            Files.write(file(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    /** Breathing glow around a rect (call every frame while the hint is active). */
    public static void breathe(DrawContext ctx, int x, int y, int w, int h) {
        float t = (System.currentTimeMillis() % 2000L) / 2000f;
        float pulse = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        int a = 40 + (int) (pulse * 120);             // 40..160 alpha breath
        int col = (a << 24) | 0x66CCFF;               // soft cyan
        int g = 1 + Math.round(pulse * 2);            // 1..3 px spread
        for (int i = 1; i <= g; i++) {
            int aa = Math.max(0, a - i * 45);
            if (aa <= 0) break;
            int c = (aa << 24) | 0x66CCFF;
            ctx.fill(x - i, y - i, x + w + i, y - i + 1, c);
            ctx.fill(x - i, y + h + i - 1, x + w + i, y + h + i, c);
            ctx.fill(x - i, y - i, x - i + 1, y + h + i, c);
            ctx.fill(x + w + i - 1, y - i, x + w + i, y + h + i, c);
        }
        ctx.fill(x, y, x + w, y + 1, col);
        ctx.fill(x, y + h - 1, x + w, y + h, col);
        ctx.fill(x, y, x + 1, y + h, col);
        ctx.fill(x + w - 1, y, x + w, y + h, col);
    }
}
