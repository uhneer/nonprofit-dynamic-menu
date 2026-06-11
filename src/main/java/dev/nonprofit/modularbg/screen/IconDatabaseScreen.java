package dev.nonprofit.modularbg.screen;

import com.google.gson.JsonParser;
import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.IconStore;
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

/**
 * In-game icon database: 6,000+ open source icons (Tabler, MIT) rendered white-on-transparent and
 * served from nDM's own free CDN (jsDelivr over the uhneer/ndm-assets repo). Searchable grid with
 * outline/filled filters; thumbnails stream in as you scroll; clicking one installs it as the icon
 * for the slot being edited. The search box is prefilled with the slot's name. No API keys.
 */
public class IconDatabaseScreen extends Screen {

    private static final String CDN = "https://cdn.jsdelivr.net/gh/uhneer/ndm-assets@main/icons/";
    private static final String[] SETS = { "All", "Outline", "Filled" };
    private static final int CELL = 36, PAD = 6;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    private final Screen parent;
    private final String slot;
    private volatile List<String> all = null;        // "outline/home" entries; null = loading
    private volatile String error = null;
    private List<String> shown = new ArrayList<>();
    private final Map<String, Identifier> thumbs = new HashMap<>();
    private final Set<String> thumbPending = new HashSet<>();
    private final Set<String> thumbFailed = new HashSet<>();
    private final ConcurrentLinkedQueue<Object[]> readyThumbs = new ConcurrentLinkedQueue<>(); // [name, NativeImage]
    private volatile String status = null;
    private volatile boolean importing = false;

    private TextFieldWidget search;
    private int setFilter = 0;
    private double scroll = 0;
    private final List<int[]> chipBoxes = new ArrayList<>();

    public IconDatabaseScreen(Screen parent, String slot) {
        super(Text.literal("Icon Database"));
        this.parent = parent;
        this.slot = slot;
        loadIndexAsync();
    }

    private void loadIndexAsync() {
        Thread t = new Thread(() -> {
            try {
                Path cache = NonprofitBackgrounds.getFolder().resolve(".cache").resolve("icons-index.json");
                String json = null;
                try {
                    if (Files.exists(cache) && System.currentTimeMillis()
                            - Files.getLastModifiedTime(cache).toMillis() < 7L * 24 * 3600 * 1000)
                        json = new String(Files.readAllBytes(cache), StandardCharsets.UTF_8);
                } catch (Throwable ignored) { }
                if (json == null) {
                    json = HTTP.send(HttpRequest.newBuilder(URI.create(CDN + "index.json"))
                                    .timeout(Duration.ofSeconds(20)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).body();
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, json.getBytes(StandardCharsets.UTF_8));
                }
                List<String> list = new ArrayList<>();
                for (var el : JsonParser.parseString(json).getAsJsonArray()) list.add(el.getAsString());
                all = list;
                MinecraftClient.getInstance().execute(this::refilter);
            } catch (Throwable t2) {
                error = "Could not load the icon index (offline?) — " + t2.getClass().getSimpleName();
                ModularBackgrounds.LOGGER.warn("[IconDB] index load failed", t2);
            }
        }, "ndm-icondb-index");
        t.setDaemon(true);
        t.start();
    }

    private void refilter() {
        if (all == null) return;
        String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        shown = new ArrayList<>();
        for (String s : all) {
            if (setFilter == 1 && !s.startsWith("outline/")) continue;
            if (setFilter == 2 && !s.startsWith("filled/")) continue;
            if (!q.isEmpty() && !s.substring(s.indexOf('/') + 1).contains(q)) continue;
            shown.add(s);
        }
        scroll = 0;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 24, 440);
        search = new TextFieldWidget(this.textRenderer, cx - w / 2, 26, w, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("§8Search 6,000+ icons..."));
        // The slot being edited prefills the query (retype to search for anything else).
        if (slot != null && search.getText().isEmpty()) search.setText(slot.equals("version") ? "badge" : slot);
        search.setChangedListener(s -> refilter());
        addDrawableChild(search);
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - 50, this.height - 26, 100, 20).build());
        refilter();
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
        ctx.drawCenteredTextWithShadow(tr, Text.literal("Icon Database §8— Tabler Icons, MIT"),
                cx, 10, 0xFFFFFFFF);

        // Register thumbnails decoded on the worker (must happen on the render thread).
        Object[] rt;
        while ((rt = readyThumbs.poll()) != null) {
            String name = (String) rt[0];
            Identifier id = Identifier.of("nonprofit", "icondb/" + name.replace('/', '_'));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id,
                    new NativeImageBackedTexture(() -> "ndm-icondb-" + name, (NativeImage) rt[1]));
            thumbs.put(name, id);
            thumbPending.remove(name);
        }

        chipBoxes.clear();
        int chipX = x, chipY = 50;
        for (int i = 0; i < SETS.length; i++) {
            int cw = tr.getWidth(SETS[i]) + 12;
            boolean sel = i == setFilter;
            ctx.fill(chipX, chipY, chipX + cw, chipY + 14, sel ? 0xCC2E6B3A : 0x66222230);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(SETS[i]), chipX + cw / 2, chipY + 3, 0xFFFFFFFF);
            chipBoxes.add(new int[]{ chipX, chipY, cw, i });
            chipX += cw + 6;
        }

        int top = 72, bottom = this.height - 34;
        if (all == null) {
            ctx.drawCenteredTextWithShadow(tr, Text.literal(error != null ? "§c" + error : "§7loading icon index..."),
                    cx, (top + bottom) / 2, 0xFFFFFFFF);
            return;
        }

        int cols = Math.max(1, w / (CELL + PAD));
        int gridX = cx - (cols * (CELL + PAD) - PAD) / 2;
        ctx.enableScissor(0, top, this.width, bottom);
        String hover = null;
        for (int i = 0; i < shown.size(); i++) {
            int gx = gridX + (i % cols) * (CELL + PAD);
            int gy = top + (i / cols) * (CELL + PAD) - (int) scroll;
            if (gy + CELL <= top || gy >= bottom) continue;
            String name = shown.get(i);
            boolean hov = mouseX >= gx && mouseX < gx + CELL && mouseY >= gy && mouseY < gy + CELL;
            ctx.fill(gx, gy, gx + CELL, gy + CELL, hov ? 0x88333344 : 0x55000000);
            Identifier th = thumbs.get(name);
            if (th != null) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, th, gx + 6, gy + 6, 0f, 0f, 24, 24, 24, 24, 0xFFFFFFFF);
            } else {
                requestThumb(name);
                ctx.drawCenteredTextWithShadow(tr, Text.literal("§8·"), gx + CELL / 2, gy + CELL / 2 - 4, 0xFFFFFFFF);
            }
            if (hov) hover = name;
        }
        ctx.disableScissor();

        int rows = (shown.size() + cols - 1) / cols;
        int contentH = rows * (CELL + PAD), viewH = bottom - top;
        if (contentH > viewH) {
            int barH = Math.max(16, viewH * viewH / contentH);
            int barY = top + (int) ((viewH - barH) * (scroll / Math.max(1, contentH - viewH)));
            ctx.fill(x + w + 4, top, x + w + 8, bottom, 0x44000000);
            ctx.fill(x + w + 4, barY, x + w + 8, barY + barH, 0xAAFFFFFF);
        }

        String st = status != null ? status
                : hover != null ? "§7" + hover.substring(hover.indexOf('/') + 1) + "  §8click to use for '" + slot + "'"
                : "§8" + shown.size() + " icons — click one to use it";
        ctx.drawCenteredTextWithShadow(tr, Text.literal(st), cx, this.height - 40, 0xFFFFFFFF);
    }

    /** Stream a thumbnail in the background (decode off-thread, register on the render thread). */
    private void requestThumb(String name) {
        if (thumbPending.contains(name) || thumbFailed.contains(name) || thumbPending.size() > 24) return;
        thumbPending.add(name);
        Thread t = new Thread(() -> {
            try {
                byte[] png = HTTP.send(HttpRequest.newBuilder(URI.create(CDN + name + ".png"))
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                readyThumbs.add(new Object[]{ name, NativeImage.read(png) });
            } catch (Throwable t2) {
                thumbFailed.add(name);
                thumbPending.remove(name);
            }
        }, "ndm-icondb-thumb");
        t.setDaemon(true);
        t.start();
    }

    private void importIcon(String name) {
        if (importing || slot == null) return;
        importing = true;
        status = "§einstalling " + name + "...";
        Thread t = new Thread(() -> {
            try {
                byte[] png = HTTP.send(HttpRequest.newBuilder(URI.create(CDN + name + ".png"))
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                Path tmp = Files.createTempFile("ndm-icon", ".png");
                Files.write(tmp, png);
                MinecraftClient.getInstance().execute(() -> {
                    boolean ok = IconStore.setIcon(slot, tmp.toString());
                    if (ok) {
                        try {   // attribution sidecar, shown on hover in the icon editor
                            Path about = IconStore.iconsRoot().resolve(IconStore.currentBgKey())
                                    .resolve(slot + ".about");
                            Files.write(about, (name.substring(name.indexOf('/') + 1)
                                    + " — Tabler Icons, MIT").getBytes(StandardCharsets.UTF_8));
                        } catch (Throwable ignored) { }
                    }
                    try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
                    status = ok ? "§a✔ '" + name.substring(name.indexOf('/') + 1) + "' set for '" + slot + "'"
                                : "§cinstall failed (see log)";
                    importing = false;
                });
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.warn("[IconDB] import failed for {}", name, t2);
                status = "§cdownload failed for " + name;
                importing = false;
            }
        }, "ndm-icondb-dl");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        for (int[] c : chipBoxes) {
            if (mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + 14) {
                setFilter = c[3];
                refilter();
                return true;
            }
        }
        int w = Math.min(this.width - 24, 440), cx = this.width / 2;
        int cols = Math.max(1, w / (CELL + PAD));
        int gridX = cx - (cols * (CELL + PAD) - PAD) / 2;
        int top = 72, bottom = this.height - 34;
        if (all != null && my >= top && my < bottom) {
            int col = (mx - gridX) / (CELL + PAD), row = (int) ((my - top + scroll) / (CELL + PAD));
            if (col >= 0 && col < cols && mx >= gridX && (mx - gridX) % (CELL + PAD) < CELL) {
                int idx = row * cols + col;
                if (idx >= 0 && idx < shown.size()) {
                    importIcon(shown.get(idx));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int w = Math.min(this.width - 24, 440);
        int cols = Math.max(1, w / (CELL + PAD));
        int rows = (shown.size() + cols - 1) / cols;
        int contentH = rows * (CELL + PAD), viewH = this.height - 34 - 72;
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - vertical * 30));
        return true;
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
