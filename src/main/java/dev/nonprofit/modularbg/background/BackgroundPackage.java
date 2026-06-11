package dev.nonprofit.modularbg.background;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.minecraft.client.MinecraftClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Import / export a whole background as a self-contained {@code .nbg.zip} (a plain ZIP with a
 * readable {@code manifest.properties}). Bundles every component that makes the background: the
 * image/MP4, its per-background icons, font assignments + any custom uploaded fonts (TTF), and its
 * menu music — so it is trivial to share. Easy to decode: any unzip tool reveals the structure.
 */
public final class BackgroundPackage {

    private BackgroundPackage() {}

    // ── pickers ──────────────────────────────────────────────────────────────────
    public static String pickImportZip() {
        try (org.lwjgl.system.MemoryStack s = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer f = s.mallocPointer(1);
            f.put(s.UTF8("*.zip")); f.flip();
            return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                    "Import a background package", "", f, "Background package (*.zip)", false);
        } catch (Throwable t) { return null; }
    }

    public static String pickExportZip(String suggested) {
        try (org.lwjgl.system.MemoryStack s = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer f = s.mallocPointer(1);
            f.put(s.UTF8("*.zip")); f.flip();
            return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                    "Export background package", suggested + ".zip", f, "Background package (*.zip)");
        } catch (Throwable t) { return null; }
    }

    // ── export ───────────────────────────────────────────────────────────────────
    public static boolean export(String name, String destZip) {
        try {
            if (name == null || name.isEmpty() || destZip == null) return false;
            Path folder = NonprofitBackgrounds.getFolder();
            Path image = folder.resolve(name);
            if (!Files.exists(image)) return false;
            String key = IconStore.keyFor(name);
            String music = NonprofitMusic.getMusicFor(name);

            Properties man = new Properties();
            man.setProperty("name", name);
            man.setProperty("image", name);
            if (music != null) man.setProperty("music", music);

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(java.nio.file.Paths.get(destZip)))) {
                ByteArrayOutputStream mb = new ByteArrayOutputStream();
                man.store(mb, "nonprofit background package");
                put(zos, "manifest.properties", mb.toByteArray());
                putFile(zos, "image/" + name, image);

                Path iconDir = IconStore.iconsRoot() == null ? null : IconStore.iconsRoot().resolve(key);
                if (iconDir != null && Files.isDirectory(iconDir))
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(iconDir)) {
                        for (Path p : ds) putFile(zos, "icons/" + p.getFileName(), p);
                    }

                Path fonts = FontStore.fontsRoot() == null ? null : FontStore.fontsRoot().resolve(key + ".txt");
                if (fonts != null && Files.exists(fonts)) {
                    putFile(zos, "fonts.txt", fonts);
                    bundleCustomFonts(zos, fonts);
                }
                if (music != null) {
                    Path mf = folder.resolve("music").resolve(music);
                    if (Files.exists(mf)) putFile(zos, "music/" + music, mf);
                }
            }
            return true;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] export failed", t);
            return false;
        }
    }

    /** Include any custom (namespace "nonprofit") fonts referenced by the assignments file. */
    private static void bundleCustomFonts(ZipOutputStream zos, Path fontsTxt) {
        try {
            Path packFont = FontStore.fontPackRoot().resolve("assets").resolve("nonprofit").resolve("font");
            for (String line : Files.readAllLines(fontsTxt, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i < 0) continue;
                String id = line.substring(i + 1).trim();
                if (!id.startsWith("nonprofit:")) continue;
                String fk = id.substring("nonprofit:".length());
                Path json = packFont.resolve(fk + ".json");
                if (!Files.exists(json)) continue;
                putFile(zos, "customfonts/" + fk + ".json", json);
                String txt = new String(Files.readAllBytes(json), StandardCharsets.UTF_8);
                int fi = txt.indexOf("nonprofit:ttf/");
                if (fi >= 0) {
                    int end = txt.indexOf('"', fi);
                    String ttf = txt.substring(fi + "nonprofit:ttf/".length(), end);
                    Path tf = packFont.resolve("ttf").resolve(ttf);
                    if (Files.exists(tf)) putFile(zos, "customfonts/ttf/" + ttf, tf);
                }
            }
        } catch (Throwable ignored) { }
    }

    // ── import ───────────────────────────────────────────────────────────────────
    public static String importZip(String srcZip) {
        try {
            if (srcZip == null) return null;
            Path folder = NonprofitBackgrounds.getFolder();
            boolean customFonts = false;
            String image = null, music = null;
            try (ZipFile zf = new ZipFile(srcZip)) {
                ZipEntry me = zf.getEntry("manifest.properties");
                if (me == null) return null;
                Properties man = new Properties();
                try (InputStream in = zf.getInputStream(me)) { man.load(in); }
                image = man.getProperty("image");
                music = man.getProperty("music");
                if (image == null) return null;
                String key = IconStore.keyFor(image);

                Files.createDirectories(folder);
                extract(zf, "image/" + image, folder.resolve(image));

                Path iconDir = IconStore.iconsRoot().resolve(key);
                Path fontsRoot = FontStore.fontsRoot();
                Path packFont = FontStore.fontPackRoot().resolve("assets").resolve("nonprofit").resolve("font");
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String n = e.getName();
                    if (e.isDirectory()) continue;
                    if (n.startsWith("icons/")) {
                        Files.createDirectories(iconDir);
                        extract(zf, n, iconDir.resolve(n.substring("icons/".length())));
                    } else if (n.equals("fonts.txt") && fontsRoot != null) {
                        Files.createDirectories(fontsRoot);
                        extract(zf, n, fontsRoot.resolve(key + ".txt"));
                    } else if (n.startsWith("customfonts/ttf/")) {
                        Files.createDirectories(packFont.resolve("ttf"));
                        extract(zf, n, packFont.resolve("ttf").resolve(n.substring("customfonts/ttf/".length())));
                        customFonts = true;
                    } else if (n.startsWith("customfonts/")) {
                        Files.createDirectories(packFont);
                        extract(zf, n, packFont.resolve(n.substring("customfonts/".length())));
                        customFonts = true;
                    }
                }
                if (music != null) {
                    ZipEntry mEntry = zf.getEntry("music/" + music);
                    if (mEntry != null) {
                        Path tmp = Files.createTempFile("nonprofit-music", music.replaceAll("[^a-zA-Z0-9._-]", "_"));
                        try (InputStream in = zf.getInputStream(mEntry)) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                        NonprofitMusic.setMusicFor(image, tmp.toString());
                        Files.deleteIfExists(tmp);
                    }
                }
            }
            if (customFonts) {
                ensureFontPackMcmeta();
                FontStore.enablePackAndReload();
            }
            IconStore.invalidateAll();
            FontStore.invalidateAll();
            NonprofitBackgrounds.reload();
            NonprofitBackgrounds.select(image);
            ModularBackgrounds.LOGGER.info("[Backgrounds] imported package '{}'", image);
            return image;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Backgrounds] import package failed", t);
            return null;
        }
    }

    private static void ensureFontPackMcmeta() throws Exception {
        Path mcmeta = FontStore.fontPackRoot().resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            Files.createDirectories(mcmeta.getParent());
            Files.write(mcmeta, "{\"pack\":{\"pack_format\":80,\"description\":\"nonprofit user fonts\"}}"
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private static void put(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, String name, Path file) throws Exception {
        if (!Files.exists(file)) return;
        zos.putNextEntry(new ZipEntry(name));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static void extract(ZipFile zf, String entry, Path dest) throws Exception {
        ZipEntry e = zf.getEntry(entry);
        if (e == null) return;
        Files.createDirectories(dest.getParent());
        try (InputStream in = zf.getInputStream(e); OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }
}
