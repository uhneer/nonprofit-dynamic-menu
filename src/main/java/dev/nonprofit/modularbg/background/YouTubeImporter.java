package dev.nonprofit.modularbg.background;

import dev.nonprofit.modularbg.ModularBackgrounds;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One-paste YouTube → MP4 background import, completely free and key-less, built on yt-dlp — the
 * actively-maintained successor of youtube-dl with a decade-plus track record. The official
 * binaries are fetched once from yt-dlp's own GitHub releases into {@code .tools/} (plus, on
 * Windows, the yt-dlp project's ffmpeg build so 1080p/1440p streams can be merged; on other
 * platforms a PATH ffmpeg is used when present). Limits: 10 minutes, 1440p, forced H.264+AAC in
 * an .mp4 container so nDM's own playback engine can decode the result.
 */
public final class YouTubeImporter {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private static final int MAX_SECONDS = 600;

    private YouTubeImporter() {}

    private static boolean win() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path toolsDir() {
        return NonprofitBackgrounds.getFolder().resolve(".tools");
    }

    /**
     * Download + prepare the video on a worker thread. {@code status} gets human progress lines
     * (any thread); {@code onDone} gets the downloaded mp4 path or null (called on the worker —
     * marshal to the render thread yourself).
     */
    public static void importUrl(String url, Consumer<String> status, Consumer<Path> onDone) {
        Thread t = new Thread(() -> onDone.accept(run(url.trim(), status)), "ndm-yt-import");
        t.setDaemon(true);
        t.start();
    }

    private static Path run(String url, Consumer<String> status) {
        try {
            if (!url.matches("https?://\\S+")) { status.accept("§cthat doesn't look like a link"); return null; }
            Path ytdlp = ensureYtDlp(status);
            if (ytdlp == null) return null;

            status.accept("checking the video...");
            // stderr is merged into the capture, so warnings/progress lines surround the print —
            // find OUR sentinel line instead of trusting the first line (the v1.4.0 bug).
            String meta = capture(ytdlp.toString(), "--no-playlist", "--skip-download",
                    "--print", "NDM::%(duration)s::%(title)s", url);
            String probe = null;
            if (meta != null)
                probe = meta.lines().filter(l -> l.startsWith("NDM::")).findFirst().orElse(null);
            if (probe == null) {
                ModularBackgrounds.LOGGER.warn("[YT] probe failed for {} — yt-dlp said:\n{}", url, meta);
                status.accept("§ccouldn't read that video (private? region-locked? see latest.log)");
                return null;
            }
            String[] parts = probe.substring(5).split("::", 2);
            double dur;
            try { dur = Double.parseDouble(parts[0].trim()); }
            catch (Throwable e) { dur = -1; }      // "NA" (live/premiere) → let the download filter decide
            if (dur > MAX_SECONDS) {
                status.accept("§cthat video is " + Math.round(dur / 60) + " min — the limit is 10 minutes");
                return null;
            }

            String ffmpeg = ensureFfmpeg(status);    // null = no merger → progressive fallback
            Path out = Files.createTempDirectory("ndm-yt");
            List<String> args = new ArrayList<>(List.of(ytdlp.toString(), "--no-playlist",
                    "--match-filter", "duration<=" + MAX_SECONDS,
                    "-P", out.toString(), "-o", "%(title).80s.%(ext)s"));
            if (ffmpeg != null) {
                // Full quality: H.264 video up to 1440p + AAC audio, merged into mp4.
                args.addAll(List.of("--ffmpeg-location", ffmpeg,
                        "-f", "bv*[vcodec^=avc1][height<=1440]+ba[acodec^=mp4a]/b[ext=mp4][height<=1440]",
                        "--merge-output-format", "mp4"));
            } else {
                // No ffmpeg available: best single-file H.264 mp4 (usually 720p).
                args.addAll(List.of("-f", "b[ext=mp4][height<=1440]/b[ext=mp4]"));
                status.accept("no ffmpeg found — importing at up to 720p");
            }
            args.add(url);

            status.accept("downloading...");
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            StringBuilder tail = new StringBuilder();        // kept for the log if anything fails
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.append(line).append('\n');
                    if (tail.length() > 8000) tail.delete(0, tail.length() - 6000);
                    if (line.contains("%")) {
                        int i = line.indexOf("[download]");
                        status.accept("downloading " + (i >= 0 ? line.substring(i + 10).trim() : line.trim()));
                    } else if (line.startsWith("[Merger]")) {
                        status.accept("merging video + audio...");
                    }
                }
            }
            if (p.waitFor() != 0) {
                ModularBackgrounds.LOGGER.warn("[YT] download failed for {} — yt-dlp said:\n{}", url, tail);
                status.accept("§cdownload failed (details in latest.log)");
                return null;
            }

            try (DirectoryStream<Path> ds = Files.newDirectoryStream(out, "*.mp4")) {
                for (Path f : ds) { status.accept("§a✔ downloaded"); return f; }
            }
            status.accept("§cno mp4 came out of the download");
            return null;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[YT] import failed", t);
            status.accept("§cimport failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** The official yt-dlp binary for this OS, downloaded once into .tools/. */
    private static Path ensureYtDlp(Consumer<String> status) {
        try {
            Files.createDirectories(toolsDir());
            String file = win() ? "yt-dlp.exe" : "yt-dlp";
            Path bin = toolsDir().resolve(file);
            if (!Files.exists(bin)) {
                status.accept("fetching yt-dlp (one time)...");
                byte[] b = HTTP.send(HttpRequest.newBuilder(URI.create(
                                "https://github.com/yt-dlp/yt-dlp/releases/latest/download/" + file))
                        .timeout(Duration.ofMinutes(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                Files.write(bin, b);
                if (!win()) bin.toFile().setExecutable(true);
            }
            return bin;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[YT] yt-dlp fetch failed", t);
            status.accept("§ccould not fetch yt-dlp (offline?)");
            return null;
        }
    }

    /** ffmpeg for merging: .tools copy, PATH, or (Windows) the yt-dlp project's build. */
    private static String ensureFfmpeg(Consumer<String> status) {
        try {
            String exe = win() ? "ffmpeg.exe" : "ffmpeg";
            Path local = toolsDir().resolve(exe);
            if (Files.exists(local)) return local.toString();
            try {                                              // PATH ffmpeg (common on Linux)
                Process p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) return exe;
            } catch (Throwable ignored) { }
            if (!win()) return null;
            status.accept("fetching ffmpeg for HD merging (one time, ~50 MB)...");
            byte[] zip = HTTP.send(HttpRequest.newBuilder(URI.create(
                            "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip"))
                    .timeout(Duration.ofMinutes(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
            try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.getName().endsWith("bin/ffmpeg.exe")) {
                        Files.copy((InputStream) zin, local);
                        return local.toString();
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[YT] ffmpeg fetch failed (falling back to 720p)", t);
            return null;
        }
    }

    private static String capture(String... args) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[YT] could not run {}", args.length > 0 ? args[0] : "?", t);
            return null;
        }
    }
}
