package dev.nonprofit.modularbg.background;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.w3c.dom.NodeList;

/**
 * nonprofit's Background Engine.
 *
 * Lets the user drop a PNG / JPG / GIF into {@code config/nonprofit-backgrounds/}
 * and select it live as the menu background — no resource pack, no restart. The title screen and
 * the menu mixins draw {@link #currentFrame()}; when nothing is selected it returns null and the
 * caller falls back to its default (black).
 *
 * Safety: every public entry point is wrapped. A decode/upload failure for one file is cached as
 * "failed" and the engine returns null (no background). Nothing here can crash the game; the worst
 * case is "background didn't change."
 *
 * Threading: all GL texture work (ensureLoaded) happens only from the render thread —
 * currentFrame() is called during rendering, and select()/reload() are called from the
 * picker screen (also render thread). Folder IO in init() touches no GL state.
 */
public final class NonprofitBackgrounds {

    public static final String NS = "nonprofit";
    public static final String DEFAULT_LABEL = "Default (none)";

    private static final int MAX_FRAMES = 120; // hard cap so a huge GIF can't run away

    private static Path folder;
    private static Path selectedFile;

    /** "" / "default" means: no custom background (engine returns null → caller draws black). */
    private static volatile String selected = "";

    private static final Map<String, Loaded> loaded = new HashMap<>();
    private static final Set<String> failed = new HashSet<>();

    private static volatile int  curFrame    = 0;
    private static volatile long lastFrameMs = 0L;

    // Separate animation cursor for carousel previews (only one preview shows at a time).
    private static volatile String previewName = null;
    private static volatile int  previewIdx = 0;
    private static volatile long previewMs  = 0L;

    // MP4 playback via our own self-contained engine (JCodec bake + clock-indexed frame cache).
    private static dev.nonprofit.modularbg.background.video.Mp4Background activeVideo = null;
    private static volatile String activeVideoName = null;

    private NonprofitBackgrounds() {}

    private static final class Loaded {
        Identifier[] frames;
        int[]        delaysMs;
        boolean      animated;
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    public static void init() {
        try {
            Path gameDir = MinecraftClient.getInstance().runDirectory.toPath();
            folder = gameDir.resolve("config").resolve("nonprofit-backgrounds");
            Files.createDirectories(folder);
            selectedFile = folder.resolve(".selected");
            if (Files.exists(selectedFile)) {
                selected = new String(Files.readAllBytes(selectedFile), StandardCharsets.UTF_8).trim();
            } else {
                installDefaultSetup();   // fresh instance → the bundled grass video + ndm brand bar
            }
            writeReadme();
            ModularBackgrounds.LOGGER.info("[Backgrounds] Engine ready. Folder: {} (selected: '{}')",
                    folder, selected.isEmpty() ? "default" : selected);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] init failed — custom backgrounds disabled this session", t);
        }
    }

    /**
     * First run on a fresh instance: extract the bundled grass video + ndm brand bar and select them,
     * so the menu looks finished out of the box (the video bakes itself on first view, with progress
     * shown). Never overwrites anything the user already has; failures fall back to the black default.
     */
    private static void installDefaultSetup() {
        try {
            String name = "grass!.mp4";
            Path mp4 = folder.resolve(name);
            if (!Files.exists(mp4)) {
                try (InputStream in = NonprofitBackgrounds.class.getResourceAsStream("/ndm-defaults/grass.mp4")) {
                    if (in == null) return;
                    Files.copy(in, mp4);
                }
            }
            Path iconDir = folder.resolve(".icons").resolve(IconStore.keyFor(name));
            Path brand = iconDir.resolve("version.png");
            if (!Files.exists(brand)) {
                try (InputStream in = NonprofitBackgrounds.class.getResourceAsStream("/ndm-defaults/version.png")) {
                    if (in != null) {
                        Files.createDirectories(iconDir);
                        Files.copy(in, brand);
                    }
                }
            }
            selected = name;
            Files.write(selectedFile, selected.getBytes(StandardCharsets.UTF_8));
            ModularBackgrounds.LOGGER.info("[Backgrounds] installed default setup: {} + ndm brand bar", name);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] default setup install failed (using black default)", t);
        }
    }

    private static void writeReadme() {
        try {
            Path readme = folder.resolve("README.txt");
            if (!Files.exists(readme)) {
                String txt = "Drop a .png, .jpg, or .gif in this folder, then in-game open\n"
                           + "Options -> Backgrounds, click Reload, and select it.\n"
                           + "GIFs animate. The default (grass) is always available.\n";
                Files.write(readme, txt.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    public static Path getFolder() { return folder; }

    // ── selection / listing ─────────────────────────────────────────────────────

    /** File names available in the folder (png/jpg/jpeg/gif), sorted. */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        if (folder == null) return names;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) continue;
                String fn = p.getFileName().toString();
                String lo = fn.toLowerCase(Locale.ROOT);
                if (lo.endsWith(".png") || lo.endsWith(".jpg") || lo.endsWith(".jpeg")
                        || lo.endsWith(".gif") || lo.endsWith(".mp4"))
                    names.add(fn);
            }
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] folder scan failed", t);
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static String getSelected() { return selected; }

    public static boolean isDefault() { return selected == null || selected.isEmpty() || selected.equalsIgnoreCase("default"); }

    /** name == null / "" selects the grass default. Persists the choice. */
    public static void select(String name) {
        selected    = (name == null) ? "" : name;
        curFrame    = 0;
        lastFrameMs = 0L;
        try {
            if (selectedFile != null)
                Files.write(selectedFile, selected.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] could not save selection", t);
        }
        if (!isDefault()) {
            ensureLoaded(selected);               // render thread (called from picker)
            // Start the video engine right away so the one-time bake begins (and its progress shows)
            // while the user is still in the carousel — not only once the title screen draws.
            if (isVideo(selected)) ensureActiveVideo();
        }
    }

    /** Creates/switches the active video engine to match the current selection (render thread). */
    private static void ensureActiveVideo() {
        try {
            if (!selected.equals(activeVideoName)) {
                closeActiveVideo();
                activeVideo = new dev.nonprofit.modularbg.background.video.Mp4Background(
                        folder.resolve(selected),
                        Identifier.of(NS, "video/" + sanitize(selected)), selected);
                activeVideoName = selected;
            }
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] video engine start failed", t);
        }
    }

    /** Re-scan the folder; clears caches so newly dropped / changed files are picked up. */
    public static void reload() {
        loaded.clear();
        failed.clear();
        curFrame = 0;
        lastFrameMs = 0L;
        dev.nonprofit.modularbg.background.video.Mp4Background.clearPreviews();
        if (!isDefault()) ensureLoaded(selected);
    }

    /**
     * Opens a native OS file picker (LWJGL TinyFD) filtered to images.
     * Returns the chosen absolute path, or null if cancelled / unavailable.
     * Must be called on the main (render) thread — which is where button clicks run.
     */
    public static String openFilePicker() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.put(stack.UTF8("*.gif"));
            filters.put(stack.UTF8("*.mp4"));
            filters.flip();
            return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                    "Select a background image or MP4", "", filters, "Images & MP4 (*.png, *.jpg, *.gif, *.mp4)", false);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] file picker unavailable", t);
            return null;
        }
    }

    /**
     * Copies the picked file into the backgrounds folder and selects it.
     * Returns the stored file name, or null on failure.
     */
    public static String importAndSelect(String sourcePath) {
        try {
            if (folder == null || sourcePath == null || sourcePath.isEmpty()) return null;
            Path src = Paths.get(sourcePath);
            if (!Files.exists(src)) return null;
            String name = src.getFileName().toString();
            Path dst = folder.resolve(name);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            loaded.remove(name);
            failed.remove(name);
            select(name);                // persists choice + loads on this (render) thread
            ModularBackgrounds.LOGGER.info("[Backgrounds] imported '{}'", name);
            return name;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] import failed", t);
            return null;
        }
    }

    /** Native picker for a .ttf/.otf font file. */
    public static String openFontPicker() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer filters = stack.mallocPointer(2);
            filters.put(stack.UTF8("*.ttf"));
            filters.put(stack.UTF8("*.otf"));
            filters.flip();
            return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                    "Select a font", "", filters, "Fonts (*.ttf, *.otf)", false);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Replace a background's image with a newly-picked file, keeping its name (and its icons/fonts/
     * music). If the new file's extension differs, the per-background data is migrated to the new key.
     * Returns the resulting file name, or null on failure.
     */
    public static String replaceImage(String oldName, String sourcePath) {
        try {
            if (folder == null || oldName == null || oldName.isEmpty() || sourcePath == null) return null;
            Path src = Paths.get(sourcePath);
            if (!Files.exists(src)) return null;
            int od = oldName.lastIndexOf('.');
            String base = od > 0 ? oldName.substring(0, od) : oldName;
            String sn = src.getFileName().toString();
            int sd = sn.lastIndexOf('.');
            String newName = base + (sd > 0 ? sn.substring(sd).toLowerCase(Locale.ROOT) : "");

            if (oldName.equals(activeVideoName)) closeActiveVideo();   // release before overwrite
            Files.copy(src, folder.resolve(newName), StandardCopyOption.REPLACE_EXISTING);
            // Old/overwritten video content → its frame cache is stale; drop it (re-baked on demand).
            if (isVideo(oldName)) dev.nonprofit.modularbg.background.video.Mp4Background.deleteCacheFor(folder, oldName);
            if (isVideo(newName)) dev.nonprofit.modularbg.background.video.Mp4Background.deleteCacheFor(folder, newName);
            boolean wasSelected = oldName.equals(selected);
            if (!newName.equals(oldName)) {
                Files.deleteIfExists(folder.resolve(oldName));
                migrateBgData(oldName, newName);
            }
            loaded.remove(oldName); loaded.remove(newName);
            failed.remove(oldName); failed.remove(newName);
            reload();
            if (wasSelected) select(newName);
            ModularBackgrounds.LOGGER.info("[Backgrounds] replaced image of '{}' -> '{}'", oldName, newName);
            return newName;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] replace failed", t);
            return null;
        }
    }

    /** Move a background's per-background data (icons / fonts / music) from one name's key to another. */
    private static void migrateBgData(String oldName, String newName) {
        try {
            String ok = IconStore.keyFor(oldName), nk = IconStore.keyFor(newName);
            Path ir = IconStore.iconsRoot();
            if (ir != null) {
                Path o = ir.resolve(ok), n = ir.resolve(nk);
                if (Files.exists(o)) {
                    Files.createDirectories(n);
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(o)) {
                        for (Path p : ds) Files.move(p, n.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.deleteIfExists(o);
                }
            }
            Path fr = FontStore.fontsRoot();
            if (fr != null) {
                Path o = fr.resolve(ok + ".txt"), n = fr.resolve(nk + ".txt");
                if (Files.exists(o)) Files.move(o, n, StandardCopyOption.REPLACE_EXISTING);
            }
            NonprofitMusic.renameAssoc(oldName, newName);
            IconStore.invalidateAll();
            FontStore.invalidateAll();
        } catch (Throwable ignored) { }
    }

    /** Rename a background file (keeps its extension). Migrates selection + music association. */
    public static String renameBackground(String oldName, String newBase) {
        try {
            if (folder == null || oldName == null || newBase == null) return null;
            String ext = "";
            int dot = oldName.lastIndexOf('.');
            if (dot > 0) ext = oldName.substring(dot);
            String cleaned = newBase.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (cleaned.isEmpty()) return null;
            String newName = cleaned.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT))
                    ? cleaned : cleaned + ext;
            if (newName.equals(oldName)) return oldName;
            Path src = folder.resolve(oldName), dst = folder.resolve(newName);
            if (!Files.exists(src)) return null;
            if (oldName.equals(activeVideoName)) closeActiveVideo();   // release handles before move
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            // A rename keeps the same video content — MOVE the bake cache so no re-bake is needed.
            if (isVideo(oldName) && isVideo(newName)) {
                try {
                    Path oc = dev.nonprofit.modularbg.background.video.Mp4Background.cacheDir(folder, oldName);
                    Path nc = dev.nonprofit.modularbg.background.video.Mp4Background.cacheDir(folder, newName);
                    if (Files.isDirectory(oc)) {
                        dev.nonprofit.modularbg.background.video.Mp4Background.deleteCacheFor(folder, newName);
                        Files.createDirectories(nc.getParent());
                        Files.move(oc, nc);
                    }
                } catch (Throwable t) {
                    ModularBackgrounds.LOGGER.warn("[Backgrounds] cache move failed (will re-bake)", t);
                }
            }
            boolean wasSelected = oldName.equals(selected);
            migrateBgData(oldName, newName);   // icons + fonts + music move with the rename
            reload();
            if (wasSelected) select(newName);
            ModularBackgrounds.LOGGER.info("[Backgrounds] renamed '{}' -> '{}'", oldName, newName);
            return newName;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] rename failed", t);
            return null;
        }
    }

    /** Delete a background file. Reverts to grass if it was selected; clears its music association. */
    public static void deleteBackground(String name) {
        try {
            if (folder == null || name == null) return;
            boolean wasSelected = name.equals(selected);
            if (name.equals(activeVideoName)) closeActiveVideo();   // release before delete
            Files.deleteIfExists(folder.resolve(name));
            if (isVideo(name))
                dev.nonprofit.modularbg.background.video.Mp4Background.deleteCacheFor(folder, name);
            NonprofitMusic.removeMusicFor(name);
            if (wasSelected) select("");
            reload();
            ModularBackgrounds.LOGGER.info("[Backgrounds] deleted '{}'", name);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] delete failed", t);
        }
    }

    // ── frame supply (called from the BackgroundBuilder mixin, render thread) ─────

    public static boolean isVideo(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4");
    }

    /** Stop + free the active video engine (render thread). */
    public static void closeActiveVideo() {
        if (activeVideo != null) {
            try { activeVideo.close(); } catch (Throwable ignored) { }
            activeVideo = null;
            activeVideoName = null;
        }
    }

    /** Title-screen status line while a video's one-time bake runs, else null. */
    public static String videoStatus() {
        try {
            return activeVideo != null ? activeVideo.status() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bake/fail status for a specific background (carousel overlay), or null. */
    public static String videoStatusFor(String name) {
        try {
            return name != null && name.equals(activeVideoName) && activeVideo != null
                    ? activeVideo.status() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tick (render thread): stop video playback once we're in a world (no menu showing it). */
    public static void lifecycleTick() {
        try {
            if (activeVideo != null && MinecraftClient.getInstance().world != null) closeActiveVideo();
        } catch (Throwable ignored) { }
    }

    /** Current frame Identifier of the selected background, or null when none is set. */
    public static Identifier currentFrame() {
        try {
            if (isDefault()) { closeActiveVideo(); return null; }
            if (isVideo(selected)) {
                ensureActiveVideo();
                return activeVideo != null ? activeVideo.frame() : null;
            }
            closeActiveVideo();
            Loaded l = loaded.get(selected);
            if (l == null) {
                if (failed.contains(selected)) return null;
                l = ensureLoaded(selected);
                if (l == null) return null;
            }
            if (l.frames == null || l.frames.length == 0) return null;
            // Advance the animation only while the window is focused — an unfocused/idle menu sits
            // at 0 fps and resumes cleanly when you come back.
            if (l.animated && l.frames.length > 1 && MinecraftClient.getInstance().isWindowFocused()) {
                long now = System.currentTimeMillis();
                if (lastFrameMs == 0L) lastFrameMs = now;
                int idx = Math.min(curFrame, l.delaysMs.length - 1);
                int delay = l.delaysMs[idx];
                if (delay <= 0) delay = 100;
                if (now - lastFrameMs >= delay) {
                    curFrame = (curFrame + 1) % l.frames.length;
                    lastFrameMs = now;
                }
            } else if (l.animated) {
                lastFrameMs = 0L;
            }
            return l.frames[Math.min(curFrame, l.frames.length - 1)];
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] currentFrame failed; falling back to default", t);
            return null;
        }
    }

    /**
     * Current frame of an arbitrary background (for carousel previews), animating independently of
     * the selected background. {@code null}/empty name → null (caller draws the default).
     */
    public static Identifier previewFrame(String name) {
        try {
            if (name == null || name.isEmpty() || name.equalsIgnoreCase("default")) return null;
            if (isVideo(name)) {  // selected video previews live; others show their cached first frame
                if (name.equals(activeVideoName) && activeVideo != null) return activeVideo.frame();
                return dev.nonprofit.modularbg.background.video.Mp4Background.cachedPreview(folder, name);
            }
            if (!name.equals(previewName)) { previewName = name; previewIdx = 0; previewMs = 0L; }
            Loaded l = loaded.get(name);
            if (l == null) {
                if (failed.contains(name)) return null;
                l = ensureLoaded(name);
                if (l == null) return null;
            }
            if (l.frames == null || l.frames.length == 0) return null;
            if (l.animated && l.frames.length > 1 && MinecraftClient.getInstance().isWindowFocused()) {
                long now = System.currentTimeMillis();
                if (previewMs == 0L) previewMs = now;
                int delay = l.delaysMs[Math.min(previewIdx, l.delaysMs.length - 1)];
                if (delay <= 0) delay = 100;
                if (now - previewMs >= delay) { previewIdx = (previewIdx + 1) % l.frames.length; previewMs = now; }
            }
            return l.frames[Math.min(previewIdx, l.frames.length - 1)];
        } catch (Throwable t) {
            return null;
        }
    }

    // ── loading (render thread only) ─────────────────────────────────────────────

    private static Loaded ensureLoaded(String name) {
        if (isVideo(name)) return null;   // MP4 runs through the video engine, never frame-cached here
        Loaded existing = loaded.get(name);
        if (existing != null) return existing;
        if (failed.contains(name)) return null;
        try {
            Path file = folder.resolve(name);
            if (!Files.exists(file)) { failed.add(name); return null; }
            Loaded l = new Loaded();
            String lo = name.toLowerCase(Locale.ROOT);
            if (lo.endsWith(".gif")) loadGif(file, name, l);
            else                     loadStatic(file, name, l);
            if (l.frames == null || l.frames.length == 0) { failed.add(name); return null; }
            loaded.put(name, l);
            ModularBackgrounds.LOGGER.info("[Backgrounds] loaded '{}' ({} frame(s), animated={})",
                    name, l.frames.length, l.animated);
            return l;
        } catch (Throwable t) {
            failed.add(name);
            ModularBackgrounds.LOGGER.warn("[Backgrounds] failed to load '" + name + "' — using default", t);
            return null;
        }
    }

    private static Identifier register(String name, int idx, NativeImage img) {
        Identifier id = Identifier.of(NS, "bg/" + sanitize(name) + "/" + idx);
        final String label = "nonprofit-bg-" + sanitize(name) + "-" + idx;
        NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> label, img);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        return id;
    }

    private static void loadStatic(Path file, String name, Loaded l) throws IOException {
        NativeImage img;
        try (InputStream in = Files.newInputStream(file)) {
            img = NativeImage.read(in);                  // STB: PNG/JPG/BMP/TGA
        } catch (Throwable stbFail) {
            img = toNativeImage(ImageIO.read(file.toFile())); // fallback decoder
        }
        l.frames   = new Identifier[]{ register(name, 0, img) };
        l.delaysMs = new int[]{ 0 };
        l.animated = false;
    }

    private static void loadGif(Path file, String name, Loaded l) throws IOException {
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
        if (!it.hasNext()) { loadStatic(file, name, l); return; }
        ImageReader reader = it.next();
        try (ImageInputStream iis = ImageIO.createImageInputStream(Files.newInputStream(file))) {
            reader.setInput(iis);
            int count = reader.getNumImages(true);
            if (count <= 0) { loadStatic(file, name, l); return; }
            count = Math.min(count, MAX_FRAMES);

            int w = reader.getWidth(0), h = reader.getHeight(0);
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();

            List<Identifier> ids    = new ArrayList<>();
            List<Integer>    delays = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                BufferedImage frame = reader.read(i);
                int[] meta = gifFrameMeta(reader, i);   // [offX, offY, delayCs, disposal]
                g.drawImage(frame, meta[0], meta[1], null);
                ids.add(register(name, i, toNativeImage(canvas)));      // snapshot composited canvas
                delays.add(Math.max(20, meta[2] * 10));                 // centiseconds → ms, floor 20
                if (meta[3] == 2)                                       // restoreToBackgroundColor
                    g.clearRect(meta[0], meta[1], frame.getWidth(), frame.getHeight());
            }
            g.dispose();
            l.frames   = ids.toArray(new Identifier[0]);
            l.delaysMs = delays.stream().mapToInt(Integer::intValue).toArray();
            l.animated = l.frames.length > 1;
        } finally {
            reader.dispose();
        }
    }

    private static int[] gifFrameMeta(ImageReader reader, int i) {
        int offX = 0, offY = 0, delayCs = 10, disposal = 0;
        try {
            IIOMetadata md = reader.getImageMetadata(i);
            String fmt = md.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
            NodeList desc = root.getElementsByTagName("ImageDescriptor");
            if (desc.getLength() > 0) {
                IIOMetadataNode n = (IIOMetadataNode) desc.item(0);
                offX = parseAttr(n, "imageLeftPosition", 0);
                offY = parseAttr(n, "imageTopPosition", 0);
            }
            NodeList gce = root.getElementsByTagName("GraphicControlExtension");
            if (gce.getLength() > 0) {
                IIOMetadataNode n = (IIOMetadataNode) gce.item(0);
                delayCs = parseAttr(n, "delayTime", 10);
                String dm = n.getAttribute("disposalMethod");
                disposal = "restoreToBackgroundColor".equals(dm) ? 2
                         : "restoreToPrevious".equals(dm)        ? 3
                         : "doNotDispose".equals(dm)             ? 1 : 0;
            }
        } catch (Throwable ignored) {}
        return new int[]{ offX, offY, delayCs, disposal };
    }

    private static int parseAttr(IIOMetadataNode n, String attr, int def) {
        try { String v = n.getAttribute(attr); return (v == null || v.isEmpty()) ? def : Integer.parseInt(v); }
        catch (Throwable t) { return def; }
    }

    private static NativeImage toNativeImage(BufferedImage src) throws IOException {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(copy, "png", bos);
        return NativeImage.read(bos.toByteArray());
    }

    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == '/' ? c : '_');
        return b.toString();
    }
}
