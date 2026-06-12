package dev.nonprofit.modularbg.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-game font database: the full Google Fonts catalog (1,800+ families, 10,000+ styles, all under
 * open source licenses), searchable with category filters, imported with one click straight into
 * nDM's font engine. Every row shows a LIVE preview rendered in that actual font: the TTF is
 * fetched lazily as you scroll, rasterized with AWT off-thread, and both the TTF and the preview
 * image are cached on disk so the list is instant next time. No API key anywhere.
 */
public class FontDatabaseScreen extends Screen {

    private record Fam(String family, String category, int popularity) { }

    private static final String[] CATS = { "All", "Sans Serif", "Serif", "Display", "Handwriting", "Monospace" };
    private static final int ROW_H = 30;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    private final Screen parent;
    private final String slot;                       // nullable: assign on import when set
    private volatile List<Fam> all = null;           // null = loading
    private volatile String error = null;
    private List<Fam> shown = new ArrayList<>();
    private final Set<String> imported = new HashSet<>();
    private volatile String busyFamily = null;       // family currently downloading
    private volatile String status = null;

    // Live previews: family → registered texture (+ its pixel size), streamed in as you scroll.
    private final Map<String, Identifier> previews = new HashMap<>();
    private final Map<String, int[]> previewDims = new HashMap<>();
    private final Set<String> previewPending = new HashSet<>();
    private final Set<String> previewFailed = new HashSet<>();
    private final ConcurrentLinkedQueue<Object[]> readyPreviews = new ConcurrentLinkedQueue<>(); // [family, NativeImage]

    private TextFieldWidget search;
    private int cat = 0;
    private double scroll = 0;
    private boolean barDrag = false;
    private final List<int[]> chipBoxes = new ArrayList<>();

    public FontDatabaseScreen(Screen parent, String slot) {
        super(Text.literal("Font Database"));
        this.parent = parent;
        this.slot = slot;
        loadIndexAsync();
    }

    private static Path cacheDir() {
        return NonprofitBackgrounds.getFolder().resolve(".cache").resolve("gfonts");
    }

    private static String keyOf(String family) {
        return family.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private void loadIndexAsync() {
        Thread t = new Thread(() -> {
            try {
                Path cache = NonprofitBackgrounds.getFolder().resolve(".cache").resolve("gfonts.json");
                String json = null;
                try {
                    if (Files.exists(cache) && System.currentTimeMillis()
                            - Files.getLastModifiedTime(cache).toMillis() < 7L * 24 * 3600 * 1000)
                        json = new String(Files.readAllBytes(cache), StandardCharsets.UTF_8);
                } catch (Throwable ignored) { }
                if (json == null) {
                    json = HTTP.send(HttpRequest.newBuilder(URI.create("https://fonts.google.com/metadata/fonts"))
                                    .timeout(Duration.ofSeconds(20)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).body();
                    if (json.startsWith(")]}'")) json = json.substring(json.indexOf('\n') + 1);
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, json.getBytes(StandardCharsets.UTF_8));
                }
                JsonArray fams = JsonParser.parseString(json).getAsJsonObject()
                        .getAsJsonArray("familyMetadataList");
                List<Fam> list = new ArrayList<>(fams.size());
                for (var el : fams) {
                    JsonObject o = el.getAsJsonObject();
                    list.add(new Fam(o.get("family").getAsString(),
                            o.has("category") ? o.get("category").getAsString() : "",
                            o.has("popularity") ? o.get("popularity").getAsInt() : Integer.MAX_VALUE));
                }
                list.sort((a, b) -> Integer.compare(a.popularity(), b.popularity()));
                all = list;
                MinecraftClient.getInstance().execute(this::refilter);
            } catch (Throwable t2) {
                error = "Could not load the font index (offline?) — " + t2.getClass().getSimpleName();
                ModularBackgrounds.LOGGER.warn("[FontDB] index load failed", t2);
            }
        }, "ndm-fontdb-index");
        t.setDaemon(true);
        t.start();
    }

    private void refilter() {
        if (all == null) return;
        String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        shown = new ArrayList<>();
        String want = cat == 0 ? null : CATS[cat];
        for (Fam f : all) {
            if (want != null && !want.equalsIgnoreCase(f.category())) continue;
            if (!q.isEmpty() && !f.family().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(f);
        }
        scroll = 0;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 24, 440);
        search = new TextFieldWidget(this.textRenderer, cx - w / 2, 26, w, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("§8Search 1,800+ font families..."));
        search.setChangedListener(s -> refilter());
        addDrawableChild(search);
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - 50, this.height - 26, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        int cx = this.width / 2, w = Math.min(this.width - 24, 440), x = cx - w / 2;
        ctx.drawCenteredTextWithShadow(tr, Text.literal("Font Database §8— Google Fonts, all open source"),
                cx, 10, 0xFFFFFFFF);

        // Register previews decoded on workers (must happen on the render thread).
        Object[] rp;
        while ((rp = readyPreviews.poll()) != null) {
            String fam = (String) rp[0];
            NativeImage img = (NativeImage) rp[1];
            Identifier id = Identifier.of("nonprofit", "fontdb/" + keyOf(fam));
            previewDims.put(fam, new int[]{ img.getWidth(), img.getHeight() });
            MinecraftClient.getInstance().getTextureManager().registerTexture(id,
                    new NativeImageBackedTexture(() -> "ndm-fontdb-" + keyOf(fam), img));
            previews.put(fam, id);
            previewPending.remove(fam);
        }

        // Category chips.
        chipBoxes.clear();
        int chipX = x, chipY = 50;
        for (int i = 0; i < CATS.length; i++) {
            int cw = tr.getWidth(CATS[i]) + 12;
            boolean selC = i == cat;
            boolean hov = mouseX >= chipX && mouseX < chipX + cw && mouseY >= chipY && mouseY < chipY + 14;
            ctx.fill(chipX, chipY, chipX + cw, chipY + 14, selC ? 0xCC2E6B3A : hov ? 0x88333344 : 0x66222230);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(CATS[i]), chipX + cw / 2, chipY + 3, 0xFFFFFFFF);
            chipBoxes.add(new int[]{ chipX, chipY, cw, i });
            chipX += cw + 6;
        }

        int top = 72, bottom = this.height - 34;
        if (all == null) {
            ctx.drawCenteredTextWithShadow(tr, Text.literal(error != null ? "§c" + error : "§7loading font index..."),
                    cx, (top + bottom) / 2, 0xFFFFFFFF);
            return;
        }

        ctx.enableScissor(0, top, this.width, bottom);
        int y = top - (int) scroll;
        for (Fam f : shown) {
            if (y + ROW_H > top && y < bottom) {
                boolean hov = mouseY >= y && mouseY < y + ROW_H - 2 && mouseX >= x && mouseX < x + w
                           && mouseY >= top && mouseY < bottom;
                boolean done = imported.contains(f.family());
                boolean busy = f.family().equals(busyFamily);
                ctx.fill(x, y, x + w, y + ROW_H - 2, hov ? 0x66000000 : 0x44000000);

                // Live preview in the actual font (streams in); the name in the UI font as fallback.
                Identifier pv = previews.get(f.family());
                if (pv != null) {
                    int[] d = previewDims.get(f.family());
                    int dh = 16, dw = Math.min(w - 120, Math.round(d[0] * (dh / (float) d[1])));
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, pv, x + 8, y + (ROW_H - 2 - dh) / 2,
                            0f, 0f, dw, dh, dw, dh, 0xFFFFFFFF);
                } else {
                    requestPreview(f.family());
                    ctx.drawTextWithShadow(tr, Text.literal((done ? "§a✔ " : "§7") + f.family()),
                            x + 8, y + 11, 0xFFFFFFFF);
                }
                String right = busy ? "§edownloading..." : done ? "§a✔ imported" : "§8" + f.category();
                ctx.drawTextWithShadow(tr, Text.literal(right), x + w - 8 - tr.getWidth(right.replaceAll("§.", "")),
                        y + 11, 0xFFFFFFFF);
            }
            y += ROW_H;
        }
        ctx.disableScissor();

        // Scrollbar.
        int contentH = shown.size() * ROW_H, viewH = bottom - top;
        if (contentH > viewH) {
            int barH = Math.max(16, viewH * viewH / contentH);
            int barY = top + (int) ((viewH - barH) * (scroll / Math.max(1, contentH - viewH)));
            ctx.fill(x + w + 4, top, x + w + 8, bottom, 0x44000000);
            ctx.fill(x + w + 4, barY, x + w + 8, barY + barH, 0xAAFFFFFF);
        }

        String st = status != null ? status
                : "§8click a font to import it" + (slot != null ? " and use it for '" + slot + "'" : "");
        ctx.drawCenteredTextWithShadow(tr, Text.literal(st), cx, this.height - 40, 0xFFFFFFFF);
    }

    /**
     * Stream a row's live preview: fetch the family TTF (disk-cached), rasterize the family name in
     * it with AWT, hand the image to the render thread. Both the TTF and the PNG are cached under
     * {@code .cache/gfonts/} so reopening the screen costs no network at all.
     */
    private void requestPreview(String family) {
        if (previewPending.contains(family) || previewFailed.contains(family)
                || previews.containsKey(family) || previewPending.size() > 4) return;
        previewPending.add(family);
        Thread t = new Thread(() -> {
            try {
                Path dir = cacheDir();
                Files.createDirectories(dir);
                Path png = dir.resolve(keyOf(family) + ".png");
                byte[] pngBytes;
                if (Files.exists(png)) {
                    pngBytes = Files.readAllBytes(png);
                } else {
                    byte[] ttf = fetchTtf(family);
                    pngBytes = renderPreview(family, ttf);
                    Files.write(png, pngBytes);
                }
                readyPreviews.add(new Object[]{ family, NativeImage.read(pngBytes) });
            } catch (Throwable t2) {
                previewFailed.add(family);
                previewPending.remove(family);
            }
        }, "ndm-fontdb-preview");
        t.setDaemon(true);
        t.start();
    }

    /** The family's regular TTF, disk-cached (also reused by the import click). */
    private static byte[] fetchTtf(String family) throws Exception {
        Path dir = cacheDir();
        Files.createDirectories(dir);
        Path f = dir.resolve(keyOf(family) + ".ttf");
        if (Files.exists(f)) return Files.readAllBytes(f);
        // The css2 endpoint serves direct TTF urls to simple user agents; some families only
        // resolve through the legacy css endpoint, so try both before giving up. No key needed.
        String url = null;
        for (String endpoint : new String[]{
                "https://fonts.googleapis.com/css2?family=" + family.replace(" ", "+"),
                "https://fonts.googleapis.com/css?family=" + family.replace(" ", "+") }) {
            try {
                String css = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint))
                                .header("User-Agent", "Wget/1.21").timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                Matcher m = Pattern.compile("url\\((https://fonts\\.gstatic\\.com/[^)]+\\.(?:ttf|otf))\\)")
                        .matcher(css);
                if (m.find()) { url = m.group(1); break; }
            } catch (Throwable ignored) { }
        }
        if (url == null) throw new IllegalStateException("no ttf url for " + family);
        byte[] ttf = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
        Files.write(f, ttf);
        return ttf;
    }

    /** White-on-transparent PNG of the family name set in its own font (2× for crispness). */
    private static byte[] renderPreview(String family, byte[] ttf) throws Exception {
        Font base = Font.createFont(Font.TRUETYPE_FONT, new java.io.ByteArrayInputStream(ttf));
        Font font = base.deriveFont(26f);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm = pg.getFontMetrics(font);
        String sample = family;
        // Fonts that can't show their own (Latin) name get a generic displayable sample.
        if (font.canDisplayUpTo(sample) != -1) sample = "AaBbGg 123";
        int tw = Math.max(8, fm.stringWidth(sample)), th = fm.getAscent() + fm.getDescent();
        pg.dispose();

        BufferedImage img = new BufferedImage(tw + 8, Math.max(8, th), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString(sample, 4, fm.getAscent());
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private void importFamily(Fam f) {
        if (busyFamily != null || imported.contains(f.family())) return;
        busyFamily = f.family();
        status = "§edownloading " + f.family() + "...";
        Thread t = new Thread(() -> {
            try {
                byte[] ttf = fetchTtf(f.family());
                MinecraftClient.getInstance().execute(() -> {
                    Identifier id = FontStore.addFontFromBytes(f.family(), ttf, false,
                            f.family() + " — Google Fonts, open source license", true);
                    if (id != null) {
                        imported.add(f.family());
                        if (slot != null) FontStore.setFont(slot, id);
                        status = "§a✔ " + f.family() + " imported" + (slot != null ? " and assigned to '" + slot + "'" : "");
                    } else {
                        status = "§c" + f.family() + " could not be installed (see log)";
                    }
                    busyFamily = null;
                });
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.warn("[FontDB] import failed for {}", f.family(), t2);
                status = "§cdownload failed for " + f.family();
                busyFamily = null;
            }
        }, "ndm-fontdb-dl");
        t.setDaemon(true);
        t.start();
    }

    /** Jump/drag the scrollbar thumb to a mouse y. */
    private void barScrollTo(double my) {
        int top = 72, bottom = this.height - 34, viewH = bottom - top;
        int contentH = shown.size() * ROW_H;
        if (contentH <= viewH) return;
        scroll = Math.max(0, Math.min(contentH - viewH, (my - top) / viewH * contentH - viewH / 2.0));
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (barDrag) { barScrollTo(click.y()); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        barDrag = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        int bx = this.width / 2 + Math.min(this.width - 24, 440) / 2;
        if (mx >= bx + 2 && mx < bx + 12 && my >= 72 && my < this.height - 34) {
            barDrag = true;
            barScrollTo(my);
            return true;
        }
        for (int[] c : chipBoxes) {
            if (mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + 14) {
                cat = c[3];
                refilter();
                return true;
            }
        }
        int x = this.width / 2 - Math.min(this.width - 24, 440) / 2, w = Math.min(this.width - 24, 440);
        int top = 72, bottom = this.height - 34;
        if (all != null && mx >= x && mx < x + w && my >= top && my < bottom) {
            int idx = (int) ((my - top + scroll) / ROW_H);
            if (idx >= 0 && idx < shown.size()) {
                importFamily(shown.get(idx));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int contentH = shown.size() * ROW_H, viewH = this.height - 34 - 72;
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - vertical * 28));
        return true;
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
