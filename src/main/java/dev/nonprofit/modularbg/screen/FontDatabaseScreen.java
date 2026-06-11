package dev.nonprofit.modularbg.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-game font database: the full Google Fonts catalog (1,800+ families, 10,000+ styles, all under
 * open source licenses), searchable with category filters, imported with one click straight into
 * nDM's font engine. No API key: the catalog comes from Google's public metadata endpoint and the
 * TTF files from fonts.gstatic.com. Index cached on disk for a week.
 */
public class FontDatabaseScreen extends Screen {

    private record Fam(String family, String category, int popularity) { }

    private static final String[] CATS = { "All", "Sans Serif", "Serif", "Display", "Handwriting", "Monospace" };
    private static final int ROW_H = 24;
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

    private TextFieldWidget search;
    private int cat = 0;
    private double scroll = 0;
    private final List<int[]> chipBoxes = new ArrayList<>();

    public FontDatabaseScreen(Screen parent, String slot) {
        super(Text.literal("Font Database"));
        this.parent = parent;
        this.slot = slot;
        loadIndexAsync();
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
                boolean hov = mouseY >= y && mouseY < y + ROW_H - 2 && mouseX >= x && mouseX < x + w;
                boolean done = imported.contains(f.family());
                boolean busy = f.family().equals(busyFamily);
                ctx.fill(x, y, x + w, y + ROW_H - 2, hov ? 0x66000000 : 0x44000000);
                ctx.drawTextWithShadow(tr, Text.literal((done ? "§a✔ " : "") + f.family()), x + 8, y + 7, 0xFFFFFFFF);
                String right = busy ? "§edownloading..." : done ? "§8imported" : "§7" + f.category();
                ctx.drawTextWithShadow(tr, Text.literal(right), x + w - 8 - tr.getWidth(right.replaceAll("§.", "")),
                        y + 7, 0xFFFFFFFF);
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

    private void importFamily(Fam f) {
        if (busyFamily != null || imported.contains(f.family())) return;
        busyFamily = f.family();
        status = "§edownloading " + f.family() + "...";
        Thread t = new Thread(() -> {
            try {
                // The css2 endpoint serves direct TTF urls to simple user agents. No key needed.
                String css = HTTP.send(HttpRequest.newBuilder(URI.create(
                                        "https://fonts.googleapis.com/css2?family="
                                                + f.family().replace(" ", "+")))
                                .header("User-Agent", "Wget/1.21").timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                Matcher m = Pattern.compile("url\\((https://fonts\\.gstatic\\.com/[^)]+\\.ttf)\\)").matcher(css);
                if (!m.find()) throw new IllegalStateException("no ttf url in css response");
                byte[] ttf = HTTP.send(HttpRequest.newBuilder(URI.create(m.group(1)))
                                .timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
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

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
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
