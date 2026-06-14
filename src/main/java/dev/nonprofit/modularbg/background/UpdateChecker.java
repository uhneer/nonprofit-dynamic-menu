package dev.nonprofit.modularbg.background;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nonprofit.modularbg.ModularBackgrounds;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-updater. On launch it asks GitHub for the latest {@code uhneer/nonprofit-dynamic-menu}
 * release; if its tag is newer than the running mod's version, the title screen shows an update
 * pill. Clicking it downloads that release's {@code .jar} asset straight into the mods folder and
 * schedules the OLD jar for deletion at game exit (via {@link ExitOps}, so Windows file locks
 * don't matter). The user restarts once and they're on the new version — no manual download.
 *
 * Everything is best-effort and wrapped: no network, a private repo, a rate-limit, a weird tag —
 * all just mean "no update offered", never a crash or a hang on the render thread.
 */
public final class UpdateChecker {

    public static final String MOD_ID = "nonprofit-modular-backgrounds";
    private static final String API =
            "https://api.github.com/repos/uhneer/nonprofit-dynamic-menu/releases/latest";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    /** State machine the title screen reads each frame. */
    public enum State { IDLE, AVAILABLE, DOWNLOADING, DONE, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile String latestTag;      // e.g. "v1.5.0"
    private static volatile String downloadUrl;    // the .jar asset's browser_download_url
    private static volatile String assetName;      // e.g. "nDM-1.5.0+1.21.11.jar"
    private static volatile int progress;          // 0..100 while downloading
    private static volatile boolean dismissed = false;
    private static final AtomicReference<String> currentVersion = new AtomicReference<>(null);

    private UpdateChecker() {}

    public static State state() { return state; }
    public static String latestTag() { return latestTag; }
    public static int progress() { return progress; }
    public static boolean dismissed() { return dismissed; }
    public static void dismiss() { dismissed = true; }

    /** The running mod's version string, or "0" if it can't be read. */
    public static String currentVersion() {
        String v = currentVersion.get();
        if (v == null) {
            v = FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
            currentVersion.set(v);
        }
        return v;
    }

    /** Kick off the check on a daemon thread (call once at init). */
    public static void checkAsync() {
        Thread t = new Thread(() -> {
            try {
                HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(API))
                                .header("Accept", "application/vnd.github+json")
                                .header("User-Agent", "nDM-updater")
                                .timeout(Duration.ofSeconds(15)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    ModularBackgrounds.LOGGER.info("[Update] check skipped (HTTP {})", res.statusCode());
                    return;
                }
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                if (o.has("prerelease") && o.get("prerelease").getAsBoolean()) return;
                if (o.has("draft") && o.get("draft").getAsBoolean()) return;
                String tag = o.has("tag_name") ? o.get("tag_name").getAsString() : null;
                if (tag == null) return;

                if (compareVersions(stripV(tag), stripV(currentVersion())) <= 0) {
                    ModularBackgrounds.LOGGER.info("[Update] up to date ({})", currentVersion());
                    return;
                }
                // Find the .jar asset (skip the -sources jar).
                String url = null, name = null;
                if (o.has("assets"))
                    for (var el : o.getAsJsonArray("assets")) {
                        JsonObject a = el.getAsJsonObject();
                        String n = a.get("name").getAsString();
                        if (n.toLowerCase(Locale.ROOT).endsWith(".jar")
                                && !n.toLowerCase(Locale.ROOT).contains("source")) {
                            url = a.get("browser_download_url").getAsString();
                            name = n;
                            break;
                        }
                    }
                if (url == null) return;
                latestTag = tag;
                downloadUrl = url;
                assetName = name;
                state = State.AVAILABLE;
                ModularBackgrounds.LOGGER.info("[Update] {} available (you have {})", tag, currentVersion());
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.info("[Update] check failed: {}", t2.toString());
            }
        }, "ndm-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Retry after a failed download (the asset url is still known). */
    public static void retry() {
        if (state == State.FAILED && downloadUrl != null) {
            state = State.AVAILABLE;
            downloadAsync();
        }
    }

    /** Download the new jar into mods/ and queue the old one for deletion at exit. */
    public static void downloadAsync() {
        if (state != State.AVAILABLE || downloadUrl == null) return;
        state = State.DOWNLOADING;
        progress = 0;
        Thread t = new Thread(() -> {
            Path tmp = null;
            try {
                Path modsDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("mods");
                Files.createDirectories(modsDir);
                Path dest = modsDir.resolve(assetName);
                tmp = Files.createTempFile(modsDir, assetName + ".", ".part");   // unique per attempt

                HttpResponse<java.io.InputStream> res = HTTP.send(
                        HttpRequest.newBuilder(URI.create(downloadUrl))
                                .header("User-Agent", "nDM-updater")
                                .timeout(Duration.ofMinutes(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                long total = res.headers().firstValueAsLong("content-length").orElse(-1L);
                long read = 0;
                try (var in = res.body(); var out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        read += n;
                        if (total > 0) progress = (int) (read * 100 / total);
                    }
                }
                // Don't promote a truncated jar: if the server advertised a length, it must match.
                if (total > 0 && read != total)
                    throw new IllegalStateException("incomplete download: " + read + "/" + total);
                if (read < 1024) throw new IllegalStateException("downloaded file is too small");
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp = null;   // promoted; nothing to clean up

                // Remove the currently-running jar at exit so there's no duplicate mod id next launch
                // (skip if the new asset overwrote the same filename).
                Path running = ownJar();
                if (running != null && !running.equals(dest)) ExitOps.scheduleDelete(running);

                progress = 100;
                state = State.DONE;
                ModularBackgrounds.LOGGER.info("[Update] downloaded {} → restart to apply", assetName);
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.warn("[Update] download failed", t2);
                state = State.FAILED;
            } finally {
                if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
            }
        }, "ndm-update-dl");
        t.setDaemon(true);
        t.start();
    }

    /** The mods/*.jar this mod is loaded from, or null (dev env / nested). */
    private static Path ownJar() {
        try {
            Path modsDir = MinecraftClient.getInstance().runDirectory.toPath().resolve("mods");
            for (Path p : FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow()
                    .getOrigin().getPaths()) {
                if (p.toString().toLowerCase(Locale.ROOT).endsWith(".jar") && p.startsWith(modsDir))
                    return p;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String stripV(String tag) {
        String t = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        int plus = t.indexOf('+');                 // drop build metadata like "+1.21.11"
        return plus > 0 ? t.substring(0, plus) : t;
    }

    /** Dotted numeric compare: returns &gt;0 if a is newer than b. Non-numeric parts compared lexically. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.-]"), pb = b.split("[.-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0", sb = i < pb.length ? pb[i] : "0";
            try {
                int cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
                if (cmp != 0) return cmp;
            } catch (NumberFormatException e) {
                int cmp = sa.compareToIgnoreCase(sb);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }
}
