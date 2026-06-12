package dev.nonprofit.modularbg.background;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Per-background font overrides for the title-screen text slots (the five menu labels + the bottom
 * version tag). Stored at {@code config/nonprofit-backgrounds/.fonts/<bgKey>.txt} as
 * {@code slot=namespace:font} lines. A slot with no override uses the default font. Per-background,
 * exactly like icons and music.
 */
public final class FontStore {

    /** Editable text slots. */
    public static final String[] SLOTS = { "play", "multiplayer", "options", "mods", "versiontag" };

    private static Path root;
    private static final Map<String, Map<String, String>> cache = new HashMap<>();
    private static List<Identifier> available;

    private FontStore() {}

    /** MC 1.21.11 resource-pack major format. Newer-than-64 formats REQUIRE min/max_format fields. */
    private static final int PACK_FORMAT = 75;

    public static void init() {
        try {
            Path folder = NonprofitBackgrounds.getFolder();
            if (folder == null)
                folder = MinecraftClient.getInstance().runDirectory.toPath()
                        .resolve("config").resolve("nonprofit-backgrounds");
            root = folder.resolve(".fonts");
            Files.createDirectories(root);
            repairFonts();   // self-heal stale pack metadata + ttf->caxton (fixes OTF/CFF fonts)
        } catch (Throwable ignored) { }
    }

    /** Every font the game has loaded (vanilla + resource packs), discovered from font/*.json. */
    public static List<Identifier> availableFonts() {
        if (available == null) {
            LinkedHashSet<Identifier> set = new LinkedHashSet<>();
            set.add(Identifier.of("minecraft", "default"));
            try {
                var found = MinecraftClient.getInstance().getResourceManager()
                        .findResources("font", id -> id.getPath().endsWith(".json"));
                for (Identifier id : found.keySet()) {
                    String p = id.getPath();
                    if (!p.startsWith("font/")) continue;
                    String name = p.substring("font/".length(), p.length() - ".json".length());
                    if (name.contains("/")) continue;          // skip subfolders (font providers)
                    set.add(Identifier.of(id.getNamespace(), name));
                }
            } catch (Throwable ignored) { }
            // Also list fonts in the managed user-font pack so a just-uploaded font shows immediately,
            // before the async resource reload that registers it has finished.
            try {
                Path pf = fontPackRoot().resolve("assets").resolve("nonprofit").resolve("font");
                if (Files.isDirectory(pf))
                    try (var ds = Files.newDirectoryStream(pf, "*.json")) {
                        for (Path p : ds) {
                            String fn = p.getFileName().toString();
                            set.add(Identifier.of("nonprofit", fn.substring(0, fn.length() - 5)));
                        }
                    }
            } catch (Throwable ignored) { }
            available = new ArrayList<>(set);
        }
        return available;
    }

    /** Forget the cached font list (call after a resource reload). */
    public static void invalidateFontList() { available = null; }

    private static Map<String, String> load(String bg) {
        return cache.computeIfAbsent(bg, k -> {
            Map<String, String> m = new HashMap<>();
            try {
                Path f = root.resolve(bg + ".txt");
                if (Files.exists(f))
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        int i = line.indexOf('=');
                        if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                    }
            } catch (Throwable ignored) { }
            return m;
        });
    }

    private static void save(String bg, Map<String, String> m) {
        try {
            StringBuilder sb = new StringBuilder();
            m.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            Files.write(root.resolve(bg + ".txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    /** The font override for a slot under the current background, or null (= default font). */
    public static Identifier fontFor(String slot) {
        return fontFor(IconStore.currentBgKey(), slot);
    }

    /** The font override for a slot under an arbitrary background key, or null (for carousel previews). */
    public static Identifier fontFor(String bgKey, String slot) {
        try {
            if (root == null) return null;
            String v = load(bgKey).get(slot);
            return (v == null || v.isEmpty()) ? null : Identifier.tryParse(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Per-slot user size multiplier (default 1.0), stored as {@code <slot>.size} next to the font. */
    public static float sizeFor(String bgKey, String slot) {
        try {
            String v = load(bgKey).get(slot + ".size");
            if (v == null) return 1.0f;
            float f = Float.parseFloat(v);
            return (f >= 0.5f && f <= 2.5f) ? f : 1.0f;
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    public static float sizeFor(String slot) {
        return sizeFor(IconStore.currentBgKey(), slot);
    }

    /** Nudge a slot's size multiplier by delta (clamped 0.5–2.5) for the current background. */
    public static float adjustSize(String slot, float delta) {
        String bg = IconStore.currentBgKey();
        float f = Math.max(0.5f, Math.min(2.5f, sizeFor(bg, slot) + delta));
        Map<String, String> m = load(bg);
        if (Math.abs(f - 1.0f) < 0.01f) m.remove(slot + ".size");
        else m.put(slot + ".size", String.format(Locale.ROOT, "%.2f", f));
        save(bg, m);
        return f;
    }

    /** Per-slot ICON size multiplier (default 1.0), stored as {@code <slot>.iconsize}. */
    public static float iconSizeFor(String bgKey, String slot) {
        try {
            String v = load(bgKey).get(slot + ".iconsize");
            if (v == null) return 1.0f;
            float f = Float.parseFloat(v);
            return (f >= 0.5f && f <= 2.5f) ? f : 1.0f;
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    public static float iconSizeFor(String slot) {
        return iconSizeFor(IconStore.currentBgKey(), slot);
    }

    /** Nudge a slot's icon size multiplier by delta (clamped 0.5–2.5) for the current background. */
    public static float adjustIconSize(String slot, float delta) {
        String bg = IconStore.currentBgKey();
        float f = Math.max(0.5f, Math.min(2.5f, iconSizeFor(bg, slot) + delta));
        Map<String, String> m = load(bg);
        if (Math.abs(f - 1.0f) < 0.01f) m.remove(slot + ".iconsize");
        else m.put(slot + ".iconsize", String.format(Locale.ROOT, "%.2f", f));
        save(bg, m);
        return f;
    }

    // ── per-background title layout (stored alongside fonts, so it travels with skins) ────

    /**
     * "left" (column, the classic), "center" (brand + PLAY centered, grid below), or "custom"
     * (left base + the user's dragged positions; dragging is only allowed in this mode).
     */
    public static String layoutFor(String bgKey) {
        try {
            String v = load(bgKey).get("layout");
            return ("center".equals(v) || "custom".equals(v)) ? v : "left";
        } catch (Throwable t) {
            return "left";
        }
    }

    public static String layout() { return layoutFor(IconStore.currentBgKey()); }

    /** Cycle the current background's layout left → center → custom; returns the new value. */
    public static String cycleLayout(String bgKey) {
        String cur = layoutFor(bgKey);
        String next = switch (cur) { case "left" -> "center"; case "center" -> "custom"; default -> "left"; };
        Map<String, String> m = load(bgKey);
        if ("left".equals(next)) m.remove("layout");
        else m.put("layout", next);
        save(bgKey, m);
        return next;
    }

    // ── per-slot custom labels / visibility / positions (same file → travel with skins) ────

    /** The user's custom label for a slot, or {@code def} when none is set. */
    public static String labelFor(String bgKey, String slot, String def) {
        try {
            String v = load(bgKey).get(slot + ".label");
            return (v == null || v.isEmpty()) ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    public static String labelFor(String slot, String def) {
        return labelFor(IconStore.currentBgKey(), slot, def);
    }

    /** Rename a button (null/blank = back to the default label). */
    public static void setLabel(String slot, String label) {
        String bg = IconStore.currentBgKey();
        Map<String, String> m = load(bg);
        if (label == null || label.isBlank()) m.remove(slot + ".label");
        else m.put(slot + ".label", label.trim());
        save(bg, m);
    }

    /** Whether the user hid this button on the title screen. Options is never hideable. */
    public static boolean hiddenFor(String bgKey, String slot) {
        if ("options".equals(slot)) return false;   // the way back into settings must always exist
        return "true".equals(load(bgKey).get(slot + ".hidden"));
    }

    public static boolean hiddenFor(String slot) {
        return hiddenFor(IconStore.currentBgKey(), slot);
    }

    public static boolean toggleHidden(String slot) {
        String bg = IconStore.currentBgKey();
        Map<String, String> m = load(bg);
        boolean now = !"true".equals(m.get(slot + ".hidden"));
        if (now) m.put(slot + ".hidden", "true");
        else m.remove(slot + ".hidden");
        save(bg, m);
        return now;
    }

    /**
     * Custom drag position for a slot as permille of the window ([xPermille, yPermille] of the
     * widget's top-left), or null = the layout's own position. Permille keeps positions stable
     * across window sizes and GUI scales.
     */
    public static int[] posFor(String bgKey, String slot) {
        try {
            String v = load(bgKey).get(slot + ".pos");
            if (v == null) return null;
            int i = v.indexOf(',');
            return new int[]{ Integer.parseInt(v.substring(0, i).trim()),
                              Integer.parseInt(v.substring(i + 1).trim()) };
        } catch (Throwable t) {
            return null;
        }
    }

    public static int[] posFor(String slot) {
        return posFor(IconStore.currentBgKey(), slot);
    }

    public static void setPos(String slot, int xPermille, int yPermille) {
        String bg = IconStore.currentBgKey();
        Map<String, String> m = load(bg);
        m.put(slot + ".pos", xPermille + "," + yPermille);
        save(bg, m);
    }

    public static void clearPos(String slot) {
        String bg = IconStore.currentBgKey();
        Map<String, String> m = load(bg);
        m.remove(slot + ".pos");
        save(bg, m);
    }

    /** Snap-to-grid while dragging (default on), per background. */
    public static boolean snapFor(String bgKey) {
        return !"false".equals(load(bgKey).get("snap"));
    }

    public static boolean toggleSnap(String bgKey) {
        Map<String, String> m = load(bgKey);
        boolean now = !snapFor(bgKey);
        if (now) m.remove("snap");
        else m.put("snap", "false");
        save(bgKey, m);
        return now;
    }

    /** True if any slot has a drag position (enables the "Reset layout" button). */
    public static boolean hasCustomPositions(String bgKey) {
        for (String k : load(bgKey).keySet()) if (k.endsWith(".pos")) return true;
        return false;
    }

    public static void clearAllPositions(String bgKey) {
        Map<String, String> m = load(bgKey);
        m.keySet().removeIf(k -> k.endsWith(".pos"));
        save(bgKey, m);
    }

    public static void setFont(String slot, Identifier font) {
        if (root == null) return;
        String bg = IconStore.currentBgKey();
        Map<String, String> m = load(bg);
        if (font == null || font.equals(Identifier.of("minecraft", "default"))) m.remove(slot);
        else m.put(slot, font.toString());
        save(bg, m);
    }

    public static void clearFont(String slot) { setFont(slot, null); }

    /** Forget cached per-background assignments (call after import rewrites .fonts files). */
    public static void invalidateAll() { cache.clear(); }

    public static Path fontsRoot() { return root; }

    /** The managed user-font resource pack folder (resourcepacks/nonprofit-fonts). */
    public static Path fontPackRoot() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("resourcepacks").resolve("nonprofit-fonts");
    }

    /**
     * Install a user-picked .ttf/.otf as a usable Minecraft font (namespace "nonprofit"), writing it
     * into the managed resource pack, enabling that pack, and reloading resources. Returns the new
     * font Identifier, or null on failure.
     */
    public static Identifier addFontFromFile(String sourcePath) {
        try {
            if (sourcePath == null) return null;
            Path src = java.nio.file.Paths.get(sourcePath);
            if (!Files.exists(src)) return null;
            String fileName = src.getFileName().toString();
            String base = fileName.replaceAll("\\.[^.]+$", "");
            String key = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            if (key.isEmpty()) key = "font";
            boolean otf = fileName.toLowerCase(Locale.ROOT).endsWith(".otf");

            Identifier fontId = installFont(key, Files.readAllBytes(src), otf);
            enablePackAndReload();
            invalidateFontList();
            ModularBackgrounds.LOGGER.info("[Fonts] added '{}' as {}", fileName, fontId);
            return fontId;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Fonts] add failed", t);
            return null;
        }
    }

    /**
     * Install a font from raw bytes (font database downloads, skin imports). {@code source} is an
     * optional attribution line ("Roboto — Google Fonts, Open Font License") shown as a tooltip in
     * the font editor. Reload is the caller's choice so batch imports reload once.
     */
    public static Identifier addFontFromBytes(String rawName, byte[] bytes, boolean otf,
                                              String source, boolean reload) {
        try {
            String key = rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
            if (key.isEmpty()) key = "font";
            Identifier id = installFont(key, bytes, otf);
            if (source != null && !source.isEmpty()) {
                Path fd = fontPackRoot().resolve("assets").resolve("nonprofit").resolve("font");
                Files.write(fd.resolve(key + ".about"), source.getBytes(StandardCharsets.UTF_8));
            }
            if (reload) {
                enablePackAndReload();
                invalidateFontList();
            }
            ModularBackgrounds.LOGGER.info("[Fonts] installed '{}' as {} ({})", rawName, id,
                    source == null ? "user file" : source);
            return id;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Fonts] install from bytes failed for {}", rawName, t);
            return null;
        }
    }

    /** Attribution line for a nonprofit:* font (from its .about sidecar), or null. */
    public static String aboutFor(Identifier font) {
        try {
            if (font == null || !"nonprofit".equals(font.getNamespace())) return null;
            Path f = fontPackRoot().resolve("assets").resolve("nonprofit").resolve("font")
                    .resolve(font.getPath() + ".about");
            return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Always-correct pack metadata (1.21.11 rejects formats >64 that omit min/max_format). */
    private static void writePackMeta(Path pack) throws IOException {
        Files.createDirectories(pack);
        String meta = "{\n  \"pack\": {\n"
                + "    \"description\": \"nonprofit user fonts\",\n"
                + "    \"pack_format\": " + PACK_FORMAT + ",\n"
                + "    \"min_format\": " + PACK_FORMAT + ",\n"
                + "    \"max_format\": " + PACK_FORMAT + "\n  }\n}\n";
        Files.write(pack.resolve("pack.mcmeta"), meta.getBytes(StandardCharsets.UTF_8));
    }

    // Bitmap-font rasterization geometry: a COLS-wide grid of CELL×CELL glyph cells, downscaled in-game
    // to MC_HEIGHT px. The big cell = heavy supersample → crisp even when the title screen scales text up.
    private static final int CELL = 64, COLS = 16, MC_HEIGHT = 8;

    /**
     * Install a user-picked font as {@code nonprofit:<key>} by rasterizing it (via AWT, which reads
     * BOTH TrueType and OpenType/CFF) into a high-res bitmap glyph atlas + a MC {@code bitmap} font
     * provider. This is self-contained (no font mod), addressable via {@code Style.withFont}, accepts
     * .otf, and is crisp at any size. The raw font is kept under {@code font/src/} so {@link #repairFonts}
     * can rebuild it after updates. Falls back to the vanilla ttf provider only if rasterization fails.
     */
    private static Identifier installFont(String key, byte[] bytes, boolean otf) throws IOException {
        Path pack = fontPackRoot();
        writePackMeta(pack);
        Path fontDir = pack.resolve("assets").resolve("nonprofit").resolve("font");
        Files.createDirectories(fontDir);
        Path srcDir = fontDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve(key + (otf ? ".otf" : ".ttf")), bytes);   // keep source for rebuilds
        try {
            writeBitmapFont(key, bytes, pack);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Fonts] bitmap rasterize failed for '{}'; using ttf fallback", key, t);
            Path ttfDir = fontDir.resolve("ttf");
            Files.createDirectories(ttfDir);
            String file = key + (otf ? ".otf" : ".ttf");
            Files.write(ttfDir.resolve(file), bytes);
            String json = "{\n  \"providers\": [\n    { \"type\": \"ttf\", \"file\": \"nonprofit:ttf/" + file
                    + "\", \"size\": 11.0, \"oversample\": 8.0 }\n  ]\n}\n";
            Files.write(fontDir.resolve(key + ".json"), json.getBytes(StandardCharsets.UTF_8));
        }
        return Identifier.of("nonprofit", key);
    }

    /** Bump to force a one-time rebuild of every installed font on next launch. */
    private static final String FONT_VERSION = "2";

    /**
     * Rasterize a TTF/OTF into a bitmap glyph atlas (printable ASCII + Latin-1) + a bitmap provider.
     *
     * <p>Visual size is normalized by MEASURED CAP HEIGHT: the provider's height/ascent are computed
     * per font so capital letters render exactly 7 px tall on an 8 px line — the same as Minecraft's
     * own font. Without this, fonts with airy line metrics (large internal leading) come out tiny.
     * The grid deliberately excludes U+0020: space is provided by the {@code space} provider only
     * (a bitmap glyph for space conflicts with it and can sink the whole font).
     */
    private static void writeBitmapFont(String key, byte[] fontBytes, Path pack) throws Exception {
        List<Integer> cps = new ArrayList<>();
        for (int c = 33; c <= 126; c++) cps.add(c);          // printable ASCII (no space)
        for (int c = 161; c <= 255; c++) cps.add(c);         // Latin-1 supplement (no NBSP)
        while (cps.size() % COLS != 0) cps.add(0);           // pad to whole rows (  = empty)
        int rows = cps.size() / COLS;

        Font base = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(fontBytes));
        BufferedImage img = new BufferedImage(COLS * CELL, rows * CELL, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        // Fill the cell: size the font so ascent+descent ≈ 94% of the cell (max supersampling),
        // then back off if the widest test glyph would overflow the cell width.
        Font font = base.deriveFont((float) CELL);
        FontMetrics fm = g.getFontMetrics(font);
        float fit = CELL * 0.94f / Math.max(1, fm.getAscent() + fm.getDescent());
        font = base.deriveFont(CELL * fit);
        fm = g.getFontMetrics(font);
        int wide = Math.max(Math.max(fm.charWidth('W'), fm.charWidth('M')), fm.charWidth('@'));
        if (wide > CELL - 6) {
            font = base.deriveFont(font.getSize2D() * (CELL - 6) / (float) wide);
            fm = g.getFontMetrics(font);
        }
        g.setFont(font);
        g.setColor(Color.WHITE);
        int top = Math.max(0, (CELL - (fm.getAscent() + fm.getDescent())) / 2);
        int baseline = Math.min(CELL - 1, top + fm.getAscent());
        for (int i = 0; i < cps.size(); i++) {
            int cp = cps.get(i);
            if (cp == 0 || !font.canDisplay((char) cp)) continue;
            g.drawString(String.valueOf((char) cp), (i % COLS) * CELL + 3, (i / COLS) * CELL + baseline);
        }
        g.dispose();

        // Measure the real rendered cap height (rows of 'H' with alpha), the ground truth for sizing.
        int capPx = measureCapHeight(img, cps.indexOf((int) 'H'));
        if (capPx <= 0) capPx = Math.round(fm.getAscent() * 0.72f);   // fallback estimate

        Path texDir = pack.resolve("assets").resolve("nonprofit").resolve("textures").resolve("font");
        Files.createDirectories(texDir);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        Files.write(texDir.resolve(key + ".png"), bos.toByteArray());

        // Provider geometry: rendered caps must be 7 px like MC's font → height = 7 / (capPx/CELL).
        int height = Math.max(8, Math.min(20, Math.round(7f * CELL / capPx)));
        int ascent = Math.min(height, Math.max(1, Math.round(baseline / (float) CELL * height)));
        StringBuilder rowsJson = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            if (r > 0) rowsJson.append(",\n        ");
            rowsJson.append('"');
            for (int c = 0; c < COLS; c++) rowsJson.append(String.format("\\u%04X", cps.get(r * COLS + c)));
            rowsJson.append('"');
        }
        String json = "{\n  \"providers\": [\n"
                + "    { \"type\": \"space\", \"advances\": { \"\\u0020\": 4 } },\n"
                + "    {\n      \"type\": \"bitmap\",\n"
                + "      \"file\": \"nonprofit:font/" + key + ".png\",\n"
                + "      \"ascent\": " + ascent + ",\n      \"height\": " + height + ",\n"
                + "      \"chars\": [\n        " + rowsJson + "\n      ]\n    }\n  ]\n}\n";
        Path fontDir = pack.resolve("assets").resolve("nonprofit").resolve("font");
        Files.write(fontDir.resolve(key + ".json"), json.getBytes(StandardCharsets.UTF_8));
        Files.write(fontDir.resolve(key + ".v"), FONT_VERSION.getBytes(StandardCharsets.UTF_8));
        ModularBackgrounds.LOGGER.info("[Fonts] '{}' rasterized: cap {}px/cell → provider height {} ascent {}",
                key, capPx, height, ascent);
    }

    /** Pixel height of the glyph in cell {@code index} (alpha-scan), or -1. */
    private static int measureCapHeight(BufferedImage img, int index) {
        if (index < 0) return -1;
        int cx = (index % COLS) * CELL, cy = (index / COLS) * CELL;
        int minY = -1, maxY = -1;
        for (int y = 0; y < CELL; y++)
            for (int x = 0; x < CELL; x++)
                if ((img.getRGB(cx + x, cy + y) >>> 24) > 16) {
                    if (minY < 0) minY = y;
                    maxY = y;
                    break;
                }
        return minY < 0 ? -1 : maxY - minY + 1;
    }

    /**
     * Startup self-heal: fix stale pack metadata and rebuild any font def that isn't already a bitmap
     * font (older Caxton/ttf defs) into the bitmap atlas, reusing the stored source font — so existing
     * fonts gain OTF support + crispness without re-importing.
     */
    public static void repairFonts() {
        try {
            Path pack = fontPackRoot();
            if (!Files.isDirectory(pack)) return;
            try { writePackMeta(pack); } catch (Throwable ignored) { }
            Path fontDir = pack.resolve("assets").resolve("nonprofit").resolve("font");
            if (!Files.isDirectory(fontDir)) return;
            Path srcDir = fontDir.resolve("src");
            Path ttfDir = fontDir.resolve("ttf");
            Path cxDir = pack.resolve("assets").resolve("caxton").resolve("textures").resolve("font");

            List<Path> defs = new ArrayList<>();
            try (var ds = Files.newDirectoryStream(fontDir, "*.json")) { for (Path p : ds) defs.add(p); }
            for (Path def : defs) {
                try {
                    String fn = def.getFileName().toString();
                    String key = fn.substring(0, fn.length() - ".json".length());
                    String txt = new String(Files.readAllBytes(def), StandardCharsets.UTF_8);
                    // Skip only defs that are current-version bitmap fonts; rebuild everything else.
                    String ver = "";
                    try { ver = new String(Files.readAllBytes(fontDir.resolve(key + ".v")), StandardCharsets.UTF_8).trim(); }
                    catch (Throwable ignored) { }
                    if (txt.contains("\"bitmap\"") && FONT_VERSION.equals(ver)) continue;
                    Path fontFile = firstMatch(srcDir, key + ".*");
                    if (fontFile == null) fontFile = firstMatch(ttfDir, key + ".*");
                    if (fontFile == null) fontFile = firstMatch(cxDir, "nonprofit_" + key + ".*");
                    if (fontFile == null) continue;
                    boolean otf = fontFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".otf");
                    installFont(key, Files.readAllBytes(fontFile), otf);      // → bitmap atlas
                    ModularBackgrounds.LOGGER.info("[Fonts] rebuilt '{}' as a hi-res bitmap font (OTF-capable)", key);
                } catch (Throwable t) {
                    ModularBackgrounds.LOGGER.warn("[Fonts] repair failed for {}", def.getFileName(), t);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static Path firstMatch(Path dir, String glob) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (var ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) return p;
        } catch (Throwable ignored) { }
        return null;
    }

    /** Enables the nonprofit-fonts pack (if not already) and reloads resources. */
    public static void enablePackAndReload() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            var rpm = mc.getResourcePackManager();
            rpm.scanPacks();
            String id = "file/nonprofit-fonts";
            if (!rpm.getIds().contains(id)) return;
            java.util.List<String> enabled = new java.util.ArrayList<>(rpm.getEnabledIds());
            if (!enabled.contains(id)) enabled.add(id);
            rpm.setEnabledProfiles(enabled);
            mc.options.refreshResourcePacks(rpm);   // syncs options + reloads resources
        } catch (Throwable ignored) { }
    }
}
