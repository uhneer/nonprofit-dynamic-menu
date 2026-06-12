package dev.nonprofit.modularbg.background;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Client-side plumbing for the Skin Hub: the (future) API base, local favorites, and per-skin
 * share codes. Codes are short ids the hub assigns to accepted uploads — paste one into Import and
 * the skin installs. The backend itself is scaffolded under {@code hub/} in the repo (a Cloudflare
 * Worker: free tier, R2 storage, hard size limits and rate limits so nobody can rack up costs).
 */
public final class SkinHub {

    /** Future Cloudflare Worker endpoint (GET /skins, GET /skin/<code>, POST /upload|vote|favorite). */
    public static final String API = "https://ndm-hub.uhneer.workers.dev";

    private static Set<String> favs = null;

    private SkinHub() {}

    private static Path favsFile() {
        return NonprofitBackgrounds.getFolder().resolve(".hubfavs");
    }

    public static synchronized Set<String> favorites() {
        if (favs == null) {
            favs = new HashSet<>();
            try {
                if (Files.exists(favsFile()))
                    for (String l : Files.readAllLines(favsFile(), StandardCharsets.UTF_8))
                        if (!l.isBlank()) favs.add(l.trim());
            } catch (Throwable ignored) { }
        }
        return favs;
    }

    public static synchronized boolean toggleFavorite(String skinId) {
        Set<String> f = favorites();
        boolean now = !f.remove(skinId);
        if (now) f.add(skinId);
        try {
            Files.write(favsFile(), String.join("\n", f).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
        // Later: POST API + "/favorite/" + skinId keyed to the session username (no login needed).
        return now;
    }

    public static boolean isFavorite(String skinId) {
        return favorites().contains(skinId);
    }

    /** The hub share code for a local background's ACCEPTED upload, or null. */
    public static String codeFor(String bgName) {
        try {
            Path f = NonprofitBackgrounds.getFolder().resolve(".uploads")
                    .resolve(IconStore.keyFor(bgName) + ".properties");
            if (!Files.exists(f)) return null;
            Properties p = new Properties();
            try (var in = Files.newInputStream(f)) { p.load(in); }
            return "accepted".equals(p.getProperty("status")) ? p.getProperty("code") : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
