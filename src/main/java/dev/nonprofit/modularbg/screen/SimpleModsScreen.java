package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * nDM's built-in mod manager: scrollable list with mod icons, names, versions, descriptions, a
 * search bar, an All / Enabled / Disabled filter, and per-mod Enable/Disable. Disabling renames the
 * mod's jar to {@code .jar.disabled} (and back), which takes effect on the next launch — a banner
 * counts pending changes. Core mods (loader, Fabric API, this mod, Minecraft itself) are protected.
 * Used as the Mods button target when ModMenu isn't installed, so nDM is fully standalone.
 */
public class SimpleModsScreen extends Screen {

    private static final Set<String> PROTECTED = Set.of(
            "minecraft", "java", "fabricloader", "fabric", "fabric-api",
            "nonprofit-modular-backgrounds");
    private static final int ROW_H = 28;

    private record Entry(String id, String name, String version, String desc,
                         boolean enabled, Path jar, ModContainer container) { }

    private final Screen parent;
    private final List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();
    private final Map<String, Identifier> iconCache = new HashMap<>();
    private final Set<String> iconFailed = new HashSet<>();
    private final Set<String> pending = new HashSet<>();   // ids with un-restarted enable/disable

    private TextFieldWidget search;
    private boolean barDrag = false;
    private int filter = 0;                                // 0 all, 1 enabled, 2 disabled
    private double scroll = 0;
    private final List<int[]> rowButtons = new ArrayList<>(); // [x,y,entryIndexInShown]

    public SimpleModsScreen(Screen parent) {
        super(Text.literal("Mods"));
        this.parent = parent;
        scan();
    }

    private void scan() {
        all.clear();
        Path modsDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("mods");
        try {
            for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
                if (mc.getContainingMod().isPresent()) continue;     // skip jar-in-jar libs
                var m = mc.getMetadata();
                Path jar = null;
                try {
                    for (Path p : mc.getOrigin().getPaths())
                        if (p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                                && p.startsWith(modsDir)) { jar = p; break; }
                } catch (Throwable ignored) { }
                all.add(new Entry(m.getId(), m.getName(), m.getVersion().getFriendlyString(),
                        oneLine(m.getDescription()), true, jar, mc));
            }
        } catch (Throwable ignored) { }
        // Disabled jars: mods/*.jar.disabled — read their fabric.mod.json for identity.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar.disabled")) {
            for (Path p : ds) {
                String id = null, name = null, ver = "?", desc = "";
                try (ZipFile zf = new ZipFile(p.toFile())) {
                    ZipEntry e = zf.getEntry("fabric.mod.json");
                    if (e != null) {
                        String json = new String(zf.getInputStream(e).readAllBytes());
                        var o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                        id = o.has("id") ? o.get("id").getAsString() : null;
                        name = o.has("name") ? o.get("name").getAsString() : id;
                        ver = o.has("version") ? o.get("version").getAsString() : "?";
                        desc = o.has("description") && o.get("description").isJsonPrimitive()
                                ? oneLine(o.get("description").getAsString()) : "";
                    }
                } catch (Throwable ignored) { }
                if (id == null) {
                    String fn = p.getFileName().toString();
                    id = fn.substring(0, fn.length() - ".jar.disabled".length());
                    name = id;
                }
                all.add(new Entry(id, name, ver, desc, false, p, null));
            }
        } catch (Throwable ignored) { }
        all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        refilter();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 95 ? s.substring(0, 92) + "..." : s;
    }

    private void refilter() {
        String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        shown = new ArrayList<>();
        for (Entry e : all) {
            if (filter == 1 && !e.enabled()) continue;
            if (filter == 2 && e.enabled()) continue;
            if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q)
                    && !e.id().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
        }
        scroll = 0;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 24, 460);
        search = new TextFieldWidget(this.textRenderer, cx - w / 2, 28, w - 110, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("§8Search mods..."));
        search.setChangedListener(s -> refilter());
        addDrawableChild(search);

        addDrawableChild(ButtonWidget.builder(filterLabel(), b -> {
            filter = (filter + 1) % 3;
            b.setMessage(filterLabel());
            refilter();
        }).dimensions(cx + w / 2 - 104, 27, 104, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - 50, this.height - 26, 100, 20).build());
    }

    private Text filterLabel() {
        return Text.literal(filter == 0 ? "Show: All" : filter == 1 ? "Show: Enabled" : "Show: Disabled");
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        int cx = this.width / 2, w = Math.min(this.width - 24, 460), x = cx - w / 2;
        ctx.drawCenteredTextWithShadow(tr, Text.literal("Mods §8(" + shown.size() + "/" + all.size() + ")"),
                cx, 10, 0xFFFFFFFF);

        int top = 54;
        int bottom = this.height - (pending.isEmpty() ? 34 : 48);
        rowButtons.clear();
        ctx.enableScissor(0, top, this.width, bottom);
        int y = top - (int) scroll;
        for (int i = 0; i < shown.size(); i++, y += ROW_H) {
            if (y + ROW_H <= top || y >= bottom) continue;
            Entry e = shown.get(i);
            boolean hov = mouseY >= y && mouseY < y + ROW_H - 2 && mouseX >= x && mouseX < x + w;
            ctx.fill(x, y, x + w, y + ROW_H - 2, hov ? 0x66000000 : 0x55000000);
            if (!e.enabled()) ctx.fill(x, y, x + 2, y + ROW_H - 2, 0xFFCC4444);

            drawModIcon(ctx, e, x + 4, y + 4);
            int tx = x + 28;
            int textW = w - 28 - 66;   // keep clear of the Enable/Disable button — no bleed
            String nm = (e.enabled() ? "" : "§8") + e.name() + " §8" + e.version()
                    + (queued.contains(e.id()) ? " §e(disables on quit)"
                       : pending.contains(e.id()) ? " §e(restart)" : "");
            ctx.drawTextWithShadow(tr, Text.literal(tr.trimToWidth(nm, textW)), tx, y + 4, 0xFFFFFFFF);
            ctx.drawTextWithShadow(tr, Text.literal("§7" + tr.trimToWidth(e.desc(), textW)),
                    tx, y + 15, 0xFFAAAAAA);

            // Enable/Disable pseudo-button at the right (protected core mods get a tag instead).
            boolean prot = PROTECTED.contains(e.id()) || (e.enabled() && e.jar() == null);
            if (!prot) {
                int bx = x + w - 58, by = y + 5, bw = 52, bh = 16;
                boolean bHov = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
                boolean q = queued.contains(e.id()) || pending.contains(e.id());
                ctx.fill(bx, by, bx + bw, by + bh, bHov ? 0xCC333344 : 0xAA22222C);
                String lbl = q ? "§eUndo" : e.enabled() ? "§cDisable" : "§aEnable";
                ctx.drawCenteredTextWithShadow(tr, Text.literal(lbl), bx + bw / 2, by + 4, 0xFFFFFFFF);
                rowButtons.add(new int[]{ bx, by, i });
            } else {
                ctx.drawTextWithShadow(tr, Text.literal("§8core"), x + w - 34, y + 9, 0xFFFFFFFF);
            }
        }
        ctx.disableScissor();

        // Scrollbar.
        int contentH = shown.size() * ROW_H, viewH = bottom - top;
        if (contentH > viewH) {
            int barH = Math.max(16, viewH * viewH / contentH);
            int barY = top + (int) ((viewH - barH) * (scroll / (contentH - viewH)));
            ctx.fill(x + w + 4, top, x + w + 8, bottom, 0x44000000);
            ctx.fill(x + w + 4, barY, x + w + 8, barY + barH, 0xAAFFFFFF);
        }

        if (!pending.isEmpty())
            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("§e⚠ " + pending.size() + " change(s) pending — restart the game to apply"),
                    cx, this.height - 42, 0xFFFFFFFF);
    }

    /** The mod's own icon (from its jar metadata) or a letter placeholder; cached per id. */
    private void drawModIcon(DrawContext ctx, Entry e, int ix, int iy) {
        Identifier id = iconCache.get(e.id());
        if (id == null && !iconFailed.contains(e.id())) {
            id = tryLoadIcon(e);
            if (id == null) iconFailed.add(e.id());
            else iconCache.put(e.id(), id);
        }
        if (id != null) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, ix, iy, 0f, 0f, 20, 20, 20, 20, 0xFFFFFFFF);
        } else {
            int col = 0xFF000000 | (0x40 + (e.id().hashCode() & 0x3F)) << 16
                    | (0x40 + ((e.id().hashCode() >> 6) & 0x3F)) << 8
                    | (0x40 + ((e.id().hashCode() >> 12) & 0x3F));
            ctx.fill(ix, iy, ix + 20, iy + 20, col);
            String letter = e.name().isEmpty() ? "?" : e.name().substring(0, 1).toUpperCase(Locale.ROOT);
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(letter), ix + 10, iy + 6, 0xFFFFFFFF);
        }
    }

    private Identifier tryLoadIcon(Entry e) {
        try {
            byte[] png = null;
            if (e.container() != null) {
                var iconPath = e.container().getMetadata().getIconPath(64);
                if (iconPath.isPresent()) {
                    var p = e.container().findPath(iconPath.get());
                    if (p.isPresent()) png = Files.readAllBytes(p.get());
                }
            } else if (e.jar() != null) {                       // disabled jar: read icon from the zip
                try (ZipFile zf = new ZipFile(e.jar().toFile())) {
                    ZipEntry me = zf.getEntry("fabric.mod.json");
                    if (me != null) {
                        var o = com.google.gson.JsonParser
                                .parseString(new String(zf.getInputStream(me).readAllBytes())).getAsJsonObject();
                        if (o.has("icon") && o.get("icon").isJsonPrimitive()) {
                            ZipEntry ie = zf.getEntry(o.get("icon").getAsString());
                            if (ie != null) try (InputStream in = zf.getInputStream(ie)) { png = in.readAllBytes(); }
                        }
                    }
                }
            }
            if (png == null) return null;
            NativeImage img = NativeImage.read(png);
            Identifier id = Identifier.of("nonprofit", "modicon/" + e.id().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_.-]", "_"));
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(id, new NativeImageBackedTexture(() -> "ndm-modicon-" + e.id(), img));
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    private void toggle(Entry e) {
        try {
            // Clicking again on a queued-at-exit disable cancels it.
            if (queued.contains(e.id())) {
                String j = e.jar() == null ? "" : e.jar().toString();
                synchronized (SimpleModsScreen.class) { exitRenames.removeIf(r -> r[0].equals(j)); }
                queued.remove(e.id());
                pending.remove(e.id());
                scan();
                return;
            }
            Path modsDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("mods");
            if (e.enabled()) {
                if (e.jar() == null) return;
                Path to = e.jar().resolveSibling(e.jar().getFileName() + ".disabled");
                try {
                    Files.move(e.jar(), to);
                } catch (Throwable locked) {
                    // Windows: the JVM holds loaded jars open, so the rename fails with a sharing
                    // violation. Queue it to run right AFTER the game process exits instead — this
                    // is what makes Disable actually work standalone on Windows.
                    scheduleExitRename(e.jar(), to);
                    queued.add(e.id());
                }
            } else {
                String fn = e.jar().getFileName().toString();
                Files.move(e.jar(), modsDir.resolve(fn.substring(0, fn.length() - ".disabled".length())));
            }
            if (!pending.remove(e.id())) pending.add(e.id());   // toggling back cancels the pending change
            scan();
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Mods] toggle failed for {}", e.id(), t);
        }
    }

    // ── deferred renames for jars the running JVM has locked (Windows) ──────────────────────
    private static final List<String[]> exitRenames = new ArrayList<>();
    private static final Set<String> queued = new HashSet<>();   // ids renamed at exit
    private static boolean hookInstalled = false;

    private static synchronized void scheduleExitRename(Path from, Path to) {
        exitRenames.add(new String[]{ from.toString(), to.toString() });
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                if (win) {
                    // A detached batch file waits for the JVM to die, then renames and self-deletes.
                    StringBuilder sb = new StringBuilder("@echo off\r\ntimeout /t 2 /nobreak >nul\r\n");
                    for (String[] r : exitRenames)
                        sb.append("move /Y \"").append(r[0]).append("\" \"").append(r[1]).append("\"\r\n");
                    sb.append("del \"%~f0\"\r\n");
                    Path bat = Files.createTempFile("ndm-mod-toggle", ".bat");
                    Files.write(bat, sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    new ProcessBuilder("cmd", "/c", "start", "", "/min", bat.toString()).start();
                } else {
                    for (String[] r : exitRenames)                 // POSIX renames open files fine
                        Files.move(Path.of(r[0]), Path.of(r[1]),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable t) {
                ModularBackgrounds.LOGGER.warn("[Mods] exit rename failed", t);
            }
        }, "ndm-mod-toggle"));
    }

    private void barScrollTo(double my) {
        int top = 54, bottom = this.height - (pending.isEmpty() ? 34 : 48), viewH = bottom - top;
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
        int bx = this.width / 2 + Math.min(this.width - 24, 460) / 2;
        if (mx >= bx + 2 && mx < bx + 12 && my >= 54 && my < this.height - 34) {
            barDrag = true;
            barScrollTo(my);
            return true;
        }
        for (int[] b : rowButtons) {
            if (mx >= b[0] && mx < b[0] + 52 && my >= b[1] && my < b[1] + 16) {
                if (b[2] < shown.size()) toggle(shown.get(b[2]));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int contentH = shown.size() * ROW_H;
        int viewH = this.height - 54 - (pending.isEmpty() ? 34 : 48);
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - vertical * 28));
        return true;
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
