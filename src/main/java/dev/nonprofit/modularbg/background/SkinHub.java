package dev.nonprofit.modularbg.background;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nonprofit.modularbg.ModularBackgrounds;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The whole client side of the Skin Hub: favorites, the local upload queue, status polling, and
 * voting — all against one Cloudflare Worker API (scaffolded under {@code hub/}). Everything is
 * best-effort and offline-tolerant by design, so the mod ships TODAY and lights up the moment the
 * backend is deployed:
 *
 *  - Uploads always record locally first (the carousel shows "pending" immediately) and are
 *    submitted to the API when it's reachable. If it isn't, they queue and {@link #pollAsync()}
 *    auto-submits them later — a user who uploads now will simply see it go live once the hub is up.
 *  - {@link #pollAsync()} (run at launch) also checks every submitted-pending skin's status and
 *    flips it to accepted (storing the share code) or denied on its own.
 *  - Favorites/votes are one-per-player keyed to the Minecraft username+UUID the client sends; no
 *    account, no login. They persist locally and sync to the API best-effort.
 */
public final class SkinHub {

    /** Future Cloudflare Worker endpoint (GET /skins, GET /skin/<code>, GET /status/<id>,
     *  POST /upload|/favorite/<code>|/moderate). Set this to your deployed route. */
    public static final String API = "https://ndm-hub.uhneer.workers.dev";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(8)).build();

    private static Set<String> favs = null;

    private SkinHub() {}

    // ── identity (no login) ────────────────────────────────────────────────────────────────

    public static String username() {
        try { return MinecraftClient.getInstance().getSession().getUsername(); }
        catch (Throwable t) { return "player"; }
    }

    public static String uuid() {
        try {
            UUID u = MinecraftClient.getInstance().getSession().getUuidOrNull();
            return u == null ? "" : u.toString();
        } catch (Throwable t) { return ""; }
    }

    // ── favorites ──────────────────────────────────────────────────────────────────────────

    private static Path favsFile() { return NonprofitBackgrounds.getFolder().resolve(".hubfavs"); }

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
        favoriteRemoteAsync(skinId, now);     // best-effort sync (one per player, server-deduped)
        return now;
    }

    public static boolean isFavorite(String skinId) { return favorites().contains(skinId); }

    private static void favoriteRemoteAsync(String code, boolean on) {
        Thread t = new Thread(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("username", username());
                body.addProperty("uuid", uuid());
                body.addProperty("on", on);
                HTTP.send(HttpRequest.newBuilder(URI.create(API + "/favorite/" + code))
                                .header("content-type", "application/json")
                                .timeout(Duration.ofSeconds(8))
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Throwable ignored) { }   // offline → favorite stays local, no harm
        }, "ndm-hub-fav");
        t.setDaemon(true);
        t.start();
    }

    // ── local upload records (.uploads/<key>.properties + <key>.zip) ────────────────────────

    public static Path uploadsDir() { return NonprofitBackgrounds.getFolder().resolve(".uploads"); }

    // All .properties record access goes through this lock + atomic temp-then-move writes, because
    // submit()'s upload thread and pollAsync()'s poll thread can touch the same file at once.
    private static final Object RECORD_LOCK = new Object();

    private static Properties readByKey(String key) {
        synchronized (RECORD_LOCK) {
            Properties p = new Properties();
            try {
                Path f = uploadsDir().resolve(key + ".properties");
                if (Files.exists(f)) try (var in = Files.newInputStream(f)) { p.load(in); }
            } catch (Throwable ignored) { }
            return p;
        }
    }

    private static void writeByKey(String key, Properties p) {
        synchronized (RECORD_LOCK) {
            try {
                Files.createDirectories(uploadsDir());
                Path f = uploadsDir().resolve(key + ".properties");
                Path tmp = Files.createTempFile(uploadsDir(), key + ".", ".tmp");
                try (var out = Files.newOutputStream(tmp)) { p.store(out, "nDM Skin Hub submission"); }
                try {
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (Throwable atomicUnsupported) {
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable t) {
                ModularBackgrounds.LOGGER.warn("[SkinHub] could not write record", t);
            }
        }
    }

    private static Properties readRecord(String bgName) { return readByKey(IconStore.keyFor(bgName)); }
    private static void writeRecord(String bgName, Properties p) { writeByKey(IconStore.keyFor(bgName), p); }

    /** "none" / "pending" / "accepted" / "denied". */
    public static String uploadStatus(String bgName) {
        Properties p = readRecord(bgName);
        return p.isEmpty() ? "none" : p.getProperty("status", "pending");
    }

    /** The hub share code for a local background's ACCEPTED upload, or null. */
    public static String codeFor(String bgName) {
        Properties p = readRecord(bgName);
        return "accepted".equals(p.getProperty("status")) ? p.getProperty("code") : null;
    }

    /**
     * Record an upload locally (status=pending) and try to submit it to the hub now; if the hub
     * isn't reachable it stays queued and {@link #pollAsync()} submits it later. {@code zip} is the
     * already-exported package. {@code status} gets human progress lines.
     */
    public static void submit(String bgName, String displayName, List<String> cats, Path zip,
                              Consumer<String> status) {
        String key = IconStore.keyFor(bgName);
        Properties p = readByKey(key);
        p.setProperty("name", displayName);
        p.setProperty("categories", String.join(",", cats));
        p.setProperty("author", username());
        p.setProperty("status", "pending");
        p.setProperty("submitted", "false");
        writeByKey(key, p);
        status.accept("§esubmitting...");
        Thread t = new Thread(() -> {
            Upload u = doUpload(zip, displayName, cats);
            synchronized (RECORD_LOCK) {
                Properties q = readByKey(key);
                if (u.pendingId != null) {
                    q.setProperty("submitted", "true");
                    q.setProperty("pendingId", u.pendingId);
                    writeByKey(key, q);
                    status.accept("§a✔ submitted for review");
                } else if (permanentReject(u.httpStatus)) {
                    q.setProperty("status", "denied");
                    q.setProperty("reason", rejectReason(u.httpStatus));
                    writeByKey(key, q);
                    status.accept("§c" + rejectReason(u.httpStatus));
                } else {
                    status.accept("§ehub offline — your upload is saved and will submit automatically when it's live");
                }
            }
        }, "ndm-hub-upload");
        t.setDaemon(true);
        t.start();
    }

    private record Upload(int httpStatus, String pendingId) {}   // httpStatus 0 = network failure

    /** POST the zip once; returns the HTTP status and the pending id (if 200). */
    private static Upload doUpload(Path zip, String displayName, List<String> cats) {
        try {
            if (zip == null || !Files.exists(zip)) return new Upload(415, null);
            byte[] body = Files.readAllBytes(zip);
            String q = "?name=" + enc(displayName) + "&author=" + enc(username())
                    + "&cats=" + enc(String.join(",", cats))
                    + "&assets=" + enc(String.join(",", assetsOf(zip)));
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(API + "/upload" + q))
                            .header("content-type", "application/zip")
                            .timeout(Duration.ofMinutes(2))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                ModularBackgrounds.LOGGER.info("[SkinHub] upload HTTP {}", res.statusCode());
                return new Upload(res.statusCode(), null);
            }
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            return new Upload(200, o.has("pending") ? o.get("pending").getAsString() : null);
        } catch (Throwable t) {
            return new Upload(0, null);   // offline / not deployed yet → caller queues it
        }
    }

    /** 4xx that won't change on retry (bad/oversized payload) — stop re-submitting these. 429 is transient. */
    private static boolean permanentReject(int s) { return s >= 400 && s < 500 && s != 429; }

    private static String rejectReason(int s) {
        return switch (s) {
            case 413 -> "skin is too large for the hub (30 MB max)";
            case 415 -> "the skin package was not a valid zip";
            default  -> "the hub rejected this skin (code " + s + ")";
        };
    }

    /**
     * Launch task: auto-submit any queued (not-yet-submitted) uploads, and poll every submitted
     * pending upload's status, flipping it to accepted (with its code) or denied. Safe to call at
     * startup; does nothing visible when the hub is offline.
     */
    public static void pollAsync() {
        Thread t = new Thread(() -> {
            try {
                Path dir = uploadsDir();
                if (!Files.isDirectory(dir)) return;
                java.util.List<String> keys = new java.util.ArrayList<>();
                try (var ds = Files.newDirectoryStream(dir, "*.properties")) {
                    for (Path f : ds) keys.add(f.getFileName().toString().replaceAll("\\.properties$", ""));
                }
                for (String key : keys) {
                    synchronized (RECORD_LOCK) {
                        Properties p = readByKey(key);
                        if (!"pending".equals(p.getProperty("status"))) continue;

                        // queued offline → try to submit now
                        if (!"true".equals(p.getProperty("submitted"))) {
                            Path zip = dir.resolve(key + ".zip");
                            String name = p.getProperty("name", key);
                            List<String> cats = List.of(p.getProperty("categories", "").split(","));
                            Upload u = doUpload(zip, name, cats);
                            if (u.pendingId != null) {
                                p.setProperty("submitted", "true");
                                p.setProperty("pendingId", u.pendingId);
                                writeByKey(key, p);
                            } else if (permanentReject(u.httpStatus)) {
                                p.setProperty("status", "denied");
                                p.setProperty("reason", rejectReason(u.httpStatus));
                                writeByKey(key, p);
                            }
                            continue;
                        }
                        // already submitted → poll its status
                        String pid = p.getProperty("pendingId");
                        if (pid == null) continue;
                        JsonObject st = getStatus(pid);
                        if (st == null) continue;
                        String s = st.has("status") ? st.get("status").getAsString() : "pending";
                        if ("accepted".equals(s)) {
                            p.setProperty("status", "accepted");
                            if (st.has("code")) p.setProperty("code", st.get("code").getAsString());
                            writeByKey(key, p);
                        } else if ("denied".equals(s)) {
                            p.setProperty("status", "denied");
                            writeByKey(key, p);
                        }
                    }
                }
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.info("[SkinHub] poll skipped: {}", t2.toString());
            }
        }, "ndm-hub-poll");
        t.setDaemon(true);
        t.start();
    }

    private static JsonObject getStatus(String pendingId) {
        try {
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(
                            URI.create(API + "/status/" + pendingId))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            return JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Human-readable asset list inside a skin zip, for the moderation embed (max ~20 names). */
    private static List<String> assetsOf(Path zip) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements() && out.size() < 20) {
                String n = en.nextElement().getName();
                if (n.endsWith("/") || n.equals("manifest.properties")) continue;
                out.add(n);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
