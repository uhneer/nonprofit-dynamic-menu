package dev.nonprofit.modularbg.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.IconStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The Skin Hub: community skins in the same browse style as the font and icon databases — search,
 * category chips, one-click get, and an upvote per player (keyed to the Minecraft session username,
 * no account or login needed).
 *
 * SCAFFOLD STATUS: the full backend (skin index + vote endpoint + Discord-bot moderation that DMs
 * the maintainer an embedded preview to accept or deny) comes later. This screen already speaks the
 * planned index format and degrades gracefully: no index online yet → a friendly empty state.
 * Uploading already works locally: it names + categorizes the skin, exports the package to
 * {@code config/nonprofit-backgrounds/.uploads/}, and marks it pending (yellow) until the
 * moderation flow exists to flip it to accepted (green) or denied (red).
 */
public class SkinDatabaseScreen extends Screen {

    private static final String INDEX_URL =
            "https://cdn.jsdelivr.net/gh/uhneer/ndm-assets@main/skins/index.json";
    public static final String[] CATEGORIES =
            { "Anime", "Gaming", "Landscape", "Nature", "Minimal", "Abstract", "Tech", "Other" };
    private static final int ROW_H = 28;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    record Entry(String id, String name, String author, List<String> cats, int votes, String zip,
                 String date, List<String> assets) { }

    private final Screen parent;
    private final String uploadSkin;                 // the carousel's previewed skin, "" / null = none
    private volatile List<Entry> all = null;
    private volatile boolean offline = false;
    private List<Entry> shown = new ArrayList<>();
    private volatile String status = null;

    private TextFieldWidget search;
    private int cat = -1;                            // -1 = All
    private double scroll = 0;
    private final List<Object[]> chipBoxes = new ArrayList<>();
    private final List<int[]> favBoxes = new ArrayList<>();   // [x,y,w,h, rowIdx]
    private final List<int[]> addBoxes = new ArrayList<>();
    private boolean barDrag = false;

    public SkinDatabaseScreen(Screen parent) { this(parent, null); }

    public SkinDatabaseScreen(Screen parent, String uploadSkin) {
        super(Text.literal("Skin Hub"));
        this.parent = parent;
        this.uploadSkin = uploadSkin;
        loadIndexAsync();
    }

    private void loadIndexAsync() {
        Thread t = new Thread(() -> {
            try {
                String json = HTTP.send(HttpRequest.newBuilder(URI.create(INDEX_URL))
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                List<Entry> list = new ArrayList<>();
                for (var el : JsonParser.parseString(json).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    List<String> cats = new ArrayList<>();
                    if (o.has("categories"))
                        for (var c : o.getAsJsonArray("categories")) cats.add(c.getAsString());
                    List<String> assets = new ArrayList<>();
                    if (o.has("assets"))
                        for (var a : o.getAsJsonArray("assets")) assets.add(a.getAsString());
                    list.add(new Entry(
                            o.has("id") ? o.get("id").getAsString() : o.get("name").getAsString(),
                            o.get("name").getAsString(),
                            o.has("author") ? o.get("author").getAsString() : "unknown",
                            cats,
                            o.has("votes") ? o.get("votes").getAsInt() : 0,
                            o.has("zip") ? o.get("zip").getAsString() : null,
                            o.has("date") ? o.get("date").getAsString() : "",
                            assets));
                }
                list.sort((a, b) -> Integer.compare(b.votes(), a.votes()));
                all = list;
                MinecraftClient.getInstance().execute(this::refilter);
            } catch (Throwable t2) {
                offline = true;          // 404 today: the hub hasn't launched yet → empty state
                all = new ArrayList<>();
            }
        }, "ndm-skinhub-index");
        t.setDaemon(true);
        t.start();
    }

    private void refilter() {
        if (all == null) return;
        String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        shown = new ArrayList<>();
        for (Entry e : all) {
            if (cat == -2 && !dev.nonprofit.modularbg.background.SkinHub.isFavorite(e.id())) continue;
            if (cat >= 0 && !e.cats().contains(CATEGORIES[cat])) continue;
            if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q)
                    && !e.author().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
        }
        scroll = 0;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 24, 440);
        search = new TextFieldWidget(this.textRenderer, cx - w / 2, 26, w, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("§8Search community skins..."));
        search.setChangedListener(s -> refilter());
        addDrawableChild(search);

        if (uploadSkin != null && !uploadSkin.isEmpty()) {
            String st = uploadStatus(uploadSkin);
            Text label = switch (st) {
                case "pending"  -> Text.literal("§e⏳ Pending review");
                case "accepted" -> Text.literal("§a✔ Accepted");
                case "denied"   -> Text.literal("§c✘ Denied");
                default          -> Text.literal("⬆ Upload '" + trim(uploadSkin, 14) + "'");
            };
            ButtonWidget up = ButtonWidget.builder(label, b -> {
                if ("none".equals(uploadStatus(uploadSkin)))
                    this.client.setScreen(new SkinUploadScreen(this, uploadSkin));
            }).dimensions(cx - w / 2, this.height - 26, 150, 20).build();
            up.setTooltip(Tooltip.of(Text.literal(switch (st) {
                case "pending" -> "Submitted — it shows up in the hub once a moderator accepts it";
                case "accepted" -> "This skin is live in the hub";
                case "denied" -> "This submission was denied";
                default -> "Share the skin you're previewing with everyone: name it, pick categories, submit for review";
            })));
            up.active = "none".equals(st) || "pending".equals(st);
            addDrawableChild(up);
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + w / 2 - 100, this.height - 26, 100, 20).build());
    }

    private static String trim(String s, int n) {
        String base = s.replaceAll("\\.[^.]+$", "");
        return base.length() <= n ? base : base.substring(0, n - 1) + "…";
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
        ctx.drawCenteredTextWithShadow(tr, Text.literal("Skin Hub §8— community skins, vote for your favorites"),
                cx, 10, 0xFFFFFFFF);

        chipBoxes.clear();
        int chipX = x, chipY = 50;
        List<Object[]> chips = new ArrayList<>();
        chips.add(new Object[]{ -1, "All" });
        chips.add(new Object[]{ -2, "♥ Favorites" });
        for (int i = 0; i < CATEGORIES.length; i++) chips.add(new Object[]{ i, CATEGORIES[i] });
        for (Object[] chip : chips) {
            int idx = (int) chip[0];
            String label = (String) chip[1];
            int cw = tr.getWidth(label) + 12;
            if (chipX + cw > x + w) break;             // narrow window: drop trailing chips
            boolean sel = idx == cat;
            int bg = sel ? (idx == -2 ? 0xCC8C2E5C : 0xCC2E6B3A) : 0x66222230;
            ctx.fill(chipX, chipY, chipX + cw, chipY + 14, bg);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(label), chipX + cw / 2, chipY + 3, 0xFFFFFFFF);
            chipBoxes.add(new Object[]{ chipX, chipY, cw, idx });
            chipX += cw + 6;
        }

        int top = 72, bottom = this.height - 34;
        if (all == null) {
            ctx.drawCenteredTextWithShadow(tr, Text.literal("§7loading skins..."), cx, (top + bottom) / 2, 0xFFFFFFFF);
            return;
        }
        if (shown.isEmpty()) {
            String l1 = offline ? "The Skin Hub hasn't launched yet (or you're offline)."
                                : "No skins match that search.";
            ctx.drawCenteredTextWithShadow(tr, Text.literal("§7" + l1), cx, (top + bottom) / 2 - 8, 0xFFFFFFFF);
            if (offline)
                ctx.drawCenteredTextWithShadow(tr,
                        Text.literal("§8Be ready for day one: upload your skin below and it'll be reviewed at launch."),
                        cx, (top + bottom) / 2 + 6, 0xFFFFFFFF);
            return;
        }

        favBoxes.clear();
        addBoxes.clear();
        ctx.enableScissor(0, top, this.width, bottom);
        int y = top - (int) scroll;
        for (int i = 0; i < shown.size(); i++) {
            Entry e = shown.get(i);
            if (y + ROW_H > top && y < bottom) {
                boolean hov = mouseY >= y && mouseY < y + ROW_H - 2 && mouseX >= x && mouseX < x + w
                           && mouseY >= top && mouseY < bottom;
                ctx.fill(x, y, x + w, y + ROW_H - 2, hov ? 0x66000000 : 0x44000000);
                ctx.drawTextWithShadow(tr, Text.literal(e.name() + " §8by " + e.author()), x + 8, y + 4, 0xFFFFFFFF);
                ctx.drawTextWithShadow(tr, Text.literal("§8" + String.join(", ", e.cats())), x + 8, y + 15, 0xFFFFFFFF);

                // Corner buttons: green ＋ installs, pink ♥ favorites (count shown beside it).
                boolean fav = dev.nonprofit.modularbg.background.SkinHub.isFavorite(e.id());
                int count = e.votes() + (fav ? 1 : 0);
                int ax = x + w - 20, ay = y + 2;
                boolean ahov = mouseX >= ax && mouseX < ax + 16 && mouseY >= ay && mouseY < ay + 16;
                ctx.fill(ax, ay, ax + 16, ay + 16, ahov ? 0xFF3BAF5C : 0xE62E8C49);
                ctx.drawCenteredTextWithShadow(tr, Text.literal("＋"), ax + 8, ay + 4, 0xFFFFFFFF);
                addBoxes.add(new int[]{ ax, ay, 16, 16, i });

                int hx = ax - 20, hy = ay;
                boolean hhov = mouseX >= hx && mouseX < hx + 16 && mouseY >= hy && mouseY < hy + 16;
                ctx.fill(hx, hy, hx + 16, hy + 16, hhov ? 0xFFE9579C : fav ? 0xF0D63384 : 0xB060223C);
                ctx.drawCenteredTextWithShadow(tr, Text.literal("♥"), hx + 8, hy + 4, 0xFFFFFFFF);
                favBoxes.add(new int[]{ hx, hy, 16, 16, i });
                String cnt = "§7" + count;
                ctx.drawTextWithShadow(tr, Text.literal(cnt), hx - 6 - tr.getWidth(count + ""), y + 9, 0xFFFFFFFF);
            }
            y += ROW_H;
        }
        ctx.disableScissor();

        int contentH = shown.size() * ROW_H, viewH = bottom - top;
        if (contentH > viewH) {
            int barH = Math.max(16, viewH * viewH / contentH);
            int barY = top + (int) ((viewH - barH) * (scroll / Math.max(1, contentH - viewH)));
            ctx.fill(x + w + 4, top, x + w + 8, bottom, 0x44000000);
            ctx.fill(x + w + 4, barY, x + w + 8, barY + barH, 0xAAFFFFFF);
        }

        if (status != null)
            ctx.drawCenteredTextWithShadow(tr, Text.literal(status), cx, this.height - 40, 0xFFFFFFFF);
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
        for (Object[] c : chipBoxes) {
            int x = (int) c[0], y = (int) c[1], w = (int) c[2];
            if (mx >= x && mx < x + w && my >= y && my < y + 14) {
                cat = (int) c[3];
                refilter();
                return true;
            }
        }
        // Pink ♥ favorites (local now; synced to the hub per session-username later).
        for (int[] v : favBoxes) {
            if (mx >= v[0] && mx < v[0] + v[2] && my >= v[1] && my < v[1] + v[3]) {
                boolean now = dev.nonprofit.modularbg.background.SkinHub
                        .toggleFavorite(shown.get(v[4]).id());
                status = now ? "§d♥ added to favorites" : "§7removed from favorites";
                if (cat == -2) refilter();
                return true;
            }
        }
        // Green ＋ installs straight to your carousel without leaving the hub.
        for (int[] v : addBoxes) {
            if (mx >= v[0] && mx < v[0] + v[2] && my >= v[1] && my < v[1] + v[3]) {
                getSkin(shown.get(v[4]));
                return true;
            }
        }
        // Anywhere else on a row opens the listing detail.
        int x = this.width / 2 - Math.min(this.width - 24, 440) / 2, w = Math.min(this.width - 24, 440);
        int top = 72, bottom = this.height - 34;
        if (all != null && !shown.isEmpty() && mx >= x && mx < x + w && my >= top && my < bottom) {
            int idx = (int) ((my - top + scroll) / ROW_H);
            if (idx >= 0 && idx < shown.size()) {
                this.client.setScreen(new SkinDetailScreen(this, shown.get(idx), this::getSkin));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

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

    /** Download + import a hub skin (already functional for when the index goes live). */
    void getSkin(Entry e) {
        if (e.zip() == null) { status = "§7downloads open when the hub launches"; return; }
        status = "§edownloading " + e.name() + "...";
        Thread t = new Thread(() -> {
            try {
                byte[] zip = HTTP.send(HttpRequest.newBuilder(URI.create(e.zip()))
                                .timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                Path tmp = Files.createTempFile("ndm-skin", ".zip");
                Files.write(tmp, zip);
                MinecraftClient.getInstance().execute(() -> {
                    String n = BackgroundPackage.importZip(tmp.toString());
                    try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
                    status = n != null ? "§a✔ '" + e.name() + "' installed and selected" : "§cimport failed";
                });
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.warn("[SkinHub] download failed for {}", e.name(), t2);
                status = "§cdownload failed for " + e.name();
            }
        }, "ndm-skinhub-dl");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int contentH = shown.size() * ROW_H, viewH = this.height - 34 - 72;
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - vertical * 28));
        return true;
    }

    // ── local upload records (.uploads/<bgKey>.properties: name, categories, status) ────────

    static Path uploadsDir() {
        return NonprofitBackgrounds.getFolder().resolve(".uploads");
    }

    /** "none" / "pending" / "accepted" / "denied" for a background's hub submission. */
    static String uploadStatus(String bgName) {
        try {
            Path f = uploadsDir().resolve(IconStore.keyFor(bgName) + ".properties");
            if (!Files.exists(f)) return "none";
            Properties p = new Properties();
            try (var in = Files.newInputStream(f)) { p.load(in); }
            return p.getProperty("status", "pending");
        } catch (Throwable t) {
            return "none";
        }
    }

    static void recordUpload(String bgName, String displayName, List<String> cats) {
        try {
            Files.createDirectories(uploadsDir());
            Properties p = new Properties();
            p.setProperty("name", displayName);
            p.setProperty("categories", String.join(",", cats));
            p.setProperty("status", "pending");
            p.setProperty("author", MinecraftClient.getInstance().getSession().getUsername());
            try (var out = Files.newOutputStream(
                    uploadsDir().resolve(IconStore.keyFor(bgName) + ".properties"))) {
                p.store(out, "nDM Skin Hub submission");
            }
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[SkinHub] could not record upload", t);
        }
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
