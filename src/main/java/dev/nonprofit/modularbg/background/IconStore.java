package dev.nonprofit.modularbg.background;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-background custom button-icon overrides.
 *
 * Each selected background may define its own icon for any menu slot (play / multiplayer / options /
 * mods / profile / close) and the brand/version bar, stored at
 * {@code config/nonprofit-backgrounds/.icons/<bgKey>/<slot>.png}. A slot with no override falls back
 * to the bundled default. Any image size is accepted — it's scaled to fit the slot when drawn.
 */
public final class IconStore {

    public static final String MODID = "nonprofit-modular-backgrounds";
    /** Editable slots, top-to-bottom as they appear on the title screen. */
    public static final String[] SLOTS = { "version", "play", "multiplayer", "options", "mods", "close" };

    private static Path root;
    private static final Map<String, Identifier> texCache = new HashMap<>();
    private static final Map<String, int[]>      dimCache = new HashMap<>();
    private static final Set<String>             missing  = new HashSet<>();

    private IconStore() {}

    public static void init() {
        try {
            Path folder = NonprofitBackgrounds.getFolder();
            if (folder == null)
                folder = MinecraftClient.getInstance().runDirectory.toPath()
                        .resolve("config").resolve("nonprofit-backgrounds");
            root = folder.resolve(".icons");
            Files.createDirectories(root);
        } catch (Throwable ignored) { }
    }

    /** Bundled default icon for a slot, or null (profile falls back to a drawn vector symbol). */
    public static Identifier defaultIcon(String slot) {
        return switch (slot) {
            case "play"        -> Identifier.of(MODID, "textures/gui/play.png");
            case "multiplayer" -> Identifier.of(MODID, "textures/gui/multiplayer.png");
            case "options"     -> Identifier.of(MODID, "textures/gui/options.png");
            case "mods"        -> Identifier.of(MODID, "textures/gui/mods.png");
            case "close"       -> Identifier.of(MODID, "textures/gui/close.png");
            case "version"     -> Identifier.of(MODID, "textures/gui/version_info.png");
            default            -> null;
        };
    }

    /**
     * While the user is inside the carousel's edit flow this holds the PREVIEWED background's key, so
     * every editor (icons, fonts, sizes, labels, positions...) applies to the skin on screen — not to
     * whichever background happens to be selected. Null outside the edit flow.
     */
    private static String editTarget = null;

    /** Route all per-background edits at {@code name} (null = back to the selected background). */
    public static void setEditTarget(String name) {
        editTarget = name == null ? null : keyFor(name);
    }

    public static String editTarget() { return editTarget; }

    public static String currentBgKey() {
        if (editTarget != null) return editTarget;
        String sel = NonprofitBackgrounds.getSelected();
        if (sel == null || sel.isEmpty() || sel.equalsIgnoreCase("default")) return "default";
        return sanitize(sel);
    }

    private static String ck(String bg, String slot) { return bg + "/" + slot; }
    private static Path file(String bg, String slot) { return root == null ? null : root.resolve(bg).resolve(slot + ".png"); }

    /** The override icon for a slot under the current background, or null if none is set. */
    public static Identifier iconFor(String slot) {
        return iconFor(currentBgKey(), slot);
    }

    /** The override icon for a slot under an arbitrary background key, or null (for carousel previews). */
    public static Identifier iconFor(String bg, String slot) {
        try {
            if (root == null) return null;
            String key = ck(bg, slot);
            if (texCache.containsKey(key)) return texCache.get(key);
            if (missing.contains(key)) return null;
            Path f = file(bg, slot);
            if (f == null || !Files.exists(f)) { missing.add(key); return null; }
            NativeImage img;
            try (InputStream in = Files.newInputStream(f)) { img = NativeImage.read(in); }
            dimCache.put(key, new int[]{ img.getWidth(), img.getHeight() });
            Identifier id = Identifier.of(NonprofitBackgrounds.NS, "icon/" + bg + "/" + slot);
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(id, new NativeImageBackedTexture(() -> "nonprofit-icon-" + bg + "-" + slot, img));
            texCache.put(key, id);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolved icon for a slot: custom override if set, else the bundled default (may be null). */
    public static Identifier resolved(String slot) {
        Identifier custom = iconFor(slot);
        return custom != null ? custom : defaultIcon(slot);
    }

    /** Resolved icon for a slot under an arbitrary background key (custom → default). */
    public static Identifier resolved(String bg, String slot) {
        Identifier custom = iconFor(bg, slot);
        return custom != null ? custom : defaultIcon(slot);
    }

    /** [width, height] of the current override, or null if none is set. */
    public static int[] dimsFor(String slot) {
        iconFor(slot);
        return dimCache.get(ck(currentBgKey(), slot));
    }

    /** [width, height] of an arbitrary background's override for a slot, or null. */
    public static int[] dimsFor(String bg, String slot) {
        iconFor(bg, slot);
        return dimCache.get(ck(bg, slot));
    }

    public static boolean hasCustom(String slot) { return iconFor(slot) != null; }

    /** Copy a chosen file into the override slot for the current background. */
    public static boolean setIcon(String slot, String sourcePath) {
        try {
            if (root == null || sourcePath == null) return false;
            Path src = Paths.get(sourcePath);
            if (!Files.exists(src)) return false;
            String bg = currentBgKey();
            Path dst = file(bg, slot);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            invalidate(bg, slot);
            return iconFor(slot) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Remove a slot's override (revert to the bundled default). */
    public static void clearIcon(String slot) {
        try {
            String bg = currentBgKey();
            Path f = file(bg, slot);
            if (f != null) Files.deleteIfExists(f);
            invalidate(bg, slot);
        } catch (Throwable ignored) { }
    }

    private static void invalidate(String bg, String slot) {
        String key = ck(bg, slot);
        texCache.remove(key);
        dimCache.remove(key);
        missing.remove(key);
    }

    /** Forget all cached icon textures/dims (call after import/export rewrites files on disk). */
    public static void invalidateAll() {
        texCache.clear();
        dimCache.clear();
        missing.clear();
    }

    /** The sanitized per-background key for an arbitrary background name ("" → "default"). */
    public static String keyFor(String name) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase("default")) return "default";
        return sanitize(name);
    }

    /** The {@code .icons} root folder, or null if not initialised. */
    public static Path iconsRoot() { return root; }

    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        return b.toString();
    }
}
