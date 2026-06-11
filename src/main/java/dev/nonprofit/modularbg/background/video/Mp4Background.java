package dev.nonprofit.modularbg.background.video;

import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.NonprofitMusic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * nonprofit's own MP4 background engine — no WaterMedia, no native ffmpeg, no external mods.
 *
 * Design: {@link Mp4Bake} decodes the clip ONCE into a JPEG-frame + PCM-audio disk cache; playback
 * then derives the frame to show from a monotonic clock —
 * {@code index = floor(elapsed × fps) mod frameCount} — and a single worker thread decodes that JPEG
 * (MC's stb decoder) one frame ahead. The render thread swaps it into one persistent
 * {@link NativeImageBackedTexture} via {@code setImage + upload}. Because the shown frame is a pure
 * function of the clock, playback cannot race, drift, speed up, or tear — the exact failure modes
 * WaterMedia's free-running ffmpeg pipeline had. Looping is the mod-N in the index math: seamless by
 * construction. Frames stay at full source resolution.
 *
 * Audio: if the MP4 had a decodable AAC track, its PCM loops on a daemon thread through
 * {@code javax.sound} (same proven path as {@link NonprofitMusic}), respecting MC's MASTER×MUSIC
 * volume, pausing with the video, and yielding to an explicitly-assigned music track.
 *
 * Steady-state cost: one background thread doing one JPEG decode per video frame + one texture
 * upload — and zero when the window is unfocused or a world is loaded. The source MP4 is never held
 * open during playback (no Windows file locks).
 */
public final class Mp4Background {

    private record DecodedFrame(int idx, NativeImage img) { }

    private final Path mp4;
    private final Path dir;
    private final String bgName;
    private final Identifier texId;

    private Mp4Bake bake;
    private volatile Mp4Bake.Meta meta;

    // Render-thread state.
    private NativeImageBackedTexture tex;
    private boolean frame0Shown = false;
    private int shownIdx = -1;
    private long anchorNanos = -1;
    private long pauseStart = -1;

    // Decode pipeline (worker thread ↔ render thread).
    private final AtomicInteger requested = new AtomicInteger(-1);
    private final AtomicReference<DecodedFrame> ready = new AtomicReference<>();
    private volatile boolean running = true;
    private Thread decoder;
    private AudioLoop audio;
    private volatile boolean dead;

    public Mp4Background(Path mp4, Identifier texId, String bgName) {
        this.mp4 = mp4;
        this.texId = texId;
        this.bgName = bgName;
        this.dir = cacheDir(mp4.getParent(), bgName);
        try {
            this.meta = Mp4Bake.readMeta(dir, mp4);
            if (meta == null) {
                bake = new Mp4Bake(mp4, dir);
                bake.start();
                ModularBackgrounds.LOGGER.info("[Video] '{}' has no frame cache — baking (one-time)", bgName);
            } else {
                startPlayback();
            }
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Video] engine init failed for '{}'", bgName, t);
            dead = true;
        }
    }

    public boolean alive() { return !dead; }

    /** Status line for the title screen while the one-time bake runs, else null. */
    public String status() {
        if (dead) return "video failed — see log";
        Mp4Bake b = bake;
        return (meta == null && b != null && !b.isFinished()) ? b.progressText() : null;
    }

    private void startPlayback() {
        running = true;
        decoder = new Thread(this::decodeLoop, "nonprofit-video-decode");
        decoder.setDaemon(true);
        decoder.start();
        if (meta.audio) {
            try {
                byte[] pcm = Files.readAllBytes(dir.resolve(Mp4Bake.AUDIO));
                audio = new AudioLoop(pcm, meta.audioRate, meta.audioChannels, bgName);
            } catch (Throwable t) {
                ModularBackgrounds.LOGGER.warn("[Video] audio start failed for '{}'", bgName, t);
            }
        }
    }

    /** Render thread: returns the texture to draw this frame, or null (caller falls back to black). */
    public Identifier frame() {
        try {
            if (dead) return null;

            if (meta == null) {                                // still baking
                if (bake.isFailed()) { dead = true; return null; }
                if (bake.isFinished()) {
                    meta = Mp4Bake.readMeta(dir, mp4);
                    if (meta == null) { dead = true; return null; }
                    bake = null;
                    startPlayback();
                } else {
                    return staticFrame0();                     // instant static frame while baking
                }
            }

            long now = System.nanoTime();
            boolean focused = MinecraftClient.getInstance().isWindowFocused();
            if (!focused) {                                    // freeze: 0 fps, audio paused
                if (pauseStart < 0) pauseStart = now;
                if (audio != null) audio.setPaused(true);
                return shownIdx >= 0 || frame0Shown ? texId : null;
            }
            if (pauseStart >= 0) {                             // resume exactly where we froze
                if (anchorNanos >= 0) anchorNanos += now - pauseStart;
                pauseStart = -1;
            }
            if (audio != null) audio.setPaused(false);
            if (anchorNanos < 0) anchorNanos = now;

            int target = (int) ((long) ((now - anchorNanos) / 1_000_000_000.0 * meta.fps()) % meta.frames);
            DecodedFrame f = ready.getAndSet(null);
            if (f != null) {
                present(f.img());
                shownIdx = f.idx();
            }
            if (shownIdx != target) requested.set(target);     // worker decodes it; we show it next call

            return shownIdx >= 0 || frame0Shown ? texId : null;
        } catch (Throwable t) {
            // GL hiccup (e.g. a resource reload destroyed our texture): rebuild on the next frame.
            tex = null;
            frame0Shown = false;
            shownIdx = -1;
            return null;
        }
    }

    /** Swap a decoded image into the persistent texture (render thread only). */
    private void present(NativeImage img) {
        if (tex == null) {
            tex = new NativeImageBackedTexture(() -> "nonprofit-video-" + bgName, img);
            MinecraftClient.getInstance().getTextureManager().registerTexture(texId, tex);
        } else {
            tex.setImage(img);   // setImage closes the previous NativeImage itself (verified bytecode)
            tex.upload();
        }
    }

    /** While baking: show frame 0 as soon as the bake writes it (it writes it first). */
    private Identifier staticFrame0() {
        if (frame0Shown) return texId;
        try {
            Path f0 = dir.resolve(Mp4Bake.frameName(0));
            if (!Files.exists(f0)) return null;
            present(decodeJpeg(Files.readAllBytes(f0)));
            frame0Shown = true;
            return texId;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Decode a JPEG via LWJGL stb directly. MC 1.21.11's {@code NativeImage.read} validates a PNG
     * signature and rejects everything else ("Bad PNG Signature"), so we call stb ourselves and wrap
     * the stb-allocated pixel buffer in a NativeImage ({@code stbAllocated=true} → its close() frees
     * via stbi_image_free, matching the allocation). Pure CPU; safe on any thread.
     */
    private static NativeImage decodeJpeg(byte[] bytes) throws java.io.IOException {
        java.nio.ByteBuffer mem = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
        try {
            mem.put(bytes).flip();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), c = stack.mallocInt(1);
                java.nio.ByteBuffer px = org.lwjgl.stb.STBImage.stbi_load_from_memory(mem, w, h, c, 4);
                if (px == null)
                    throw new java.io.IOException("stb decode failed: " + org.lwjgl.stb.STBImage.stbi_failure_reason());
                return new NativeImage(NativeImage.Format.RGBA, w.get(0), h.get(0), true,
                        org.lwjgl.system.MemoryUtil.memAddress(px));
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(mem);
        }
    }

    /** Worker thread: decode the requested JPEG one frame ahead of the render thread. */
    private void decodeLoop() {
        int lastDecoded = -1;
        int errors = 0;
        while (running) {
            int want = requested.get();
            if (want < 0 || want == lastDecoded) {
                LockSupport.parkNanos(2_000_000L);             // ~2 ms idle poll
                continue;
            }
            try {
                byte[] jpg = Files.readAllBytes(dir.resolve(Mp4Bake.frameName(want)));
                NativeImage img = decodeJpeg(jpg);             // stb: fast native JPEG decode
                DecodedFrame stale = ready.getAndSet(new DecodedFrame(want, img));
                if (stale != null) {
                    try { stale.img().close(); } catch (Throwable ignored) { }
                }
                if (!running) {                                // closed while we decoded → self-drain
                    DecodedFrame mine = ready.getAndSet(null);
                    if (mine != null) {
                        try { mine.img().close(); } catch (Throwable ignored) { }
                    }
                    return;
                }
                lastDecoded = want;
                errors = 0;
            } catch (Throwable t) {
                lastDecoded = want;                            // don't spin on a bad frame
                if (++errors > 60) {
                    ModularBackgrounds.LOGGER.warn("[Video] too many decode failures for '{}' — stopping", bgName, t);
                    dead = true;
                    AudioLoop a = audio;                       // don't leave audio looping over black
                    if (a != null) a.close();
                    return;
                }
            }
        }
    }

    /** Stop playback + audio and release everything (render thread). */
    public void close() {
        dead = true;
        running = false;
        try { if (bake != null) bake.cancel(); } catch (Throwable ignored) { }
        try { if (decoder != null) decoder.interrupt(); } catch (Throwable ignored) { }
        try { if (audio != null) audio.close(); } catch (Throwable ignored) { }
        DecodedFrame leftover = ready.getAndSet(null);
        if (leftover != null) {
            try { leftover.img().close(); } catch (Throwable ignored) { }
        }
        try { MinecraftClient.getInstance().getTextureManager().destroyTexture(texId); } catch (Throwable ignored) { }
        tex = null;
        audio = null;
        decoder = null;
    }

    // ── static helpers (cache paths, carousel previews) ───────────────────────────

    public static Path cacheDir(Path backgroundsFolder, String bgName) {
        return backgroundsFolder.resolve(".video-cache").resolve(sanitize(bgName));
    }

    /** Delete a background's bake cache (call when the background is deleted/replaced/renamed). */
    public static void deleteCacheFor(Path backgroundsFolder, String bgName) {
        if (backgroundsFolder != null) Mp4Bake.deleteCache(cacheDir(backgroundsFolder, bgName));
    }

    private static final Map<String, Identifier> previews = new HashMap<>();

    /** Forget cached preview textures (call from the engine's reload — re-registered on demand). */
    public static void clearPreviews() {
        try {
            for (Identifier id : previews.values())
                MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
        } catch (Throwable ignored) { }
        previews.clear();
    }

    /**
     * Static first frame of a baked (not currently playing) video for carousel previews, or null if
     * that video has never been baked. Render thread only.
     */
    public static Identifier cachedPreview(Path backgroundsFolder, String bgName) {
        try {
            String key = sanitize(bgName);
            Identifier cached = previews.get(key);
            if (cached != null) return cached;
            Path f0 = cacheDir(backgroundsFolder, bgName).resolve(Mp4Bake.frameName(0));
            if (!Files.exists(f0)) return null;
            NativeImage img = decodeJpeg(Files.readAllBytes(f0));
            Identifier id = Identifier.of("nonprofit", "videoprev/" + key);
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(id, new NativeImageBackedTexture(() -> "nonprofit-videoprev-" + key, img));
            previews.put(key, id);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ? c : '_');
        return b.toString();
    }

    // ── looping PCM audio (same javax.sound path NonprofitMusic proved out) ────────

    private static final class AudioLoop {
        private final byte[] pcm;
        private final int rate, channels;
        private final String bgName;
        private volatile boolean running = true;
        private volatile boolean paused = false;

        AudioLoop(byte[] pcm, int rate, int channels, String bgName) {
            this.pcm = pcm;
            this.rate = rate;
            this.channels = channels;
            this.bgName = bgName;
            Thread t = new Thread(this::loop, "nonprofit-video-audio");
            t.setDaemon(true);
            t.start();
        }

        void setPaused(boolean p) { paused = p; }

        void close() { running = false; }

        private void loop() {
            SourceDataLine line = null;
            try {
                AudioFormat fmt = new AudioFormat(rate, 16, channels, true, false); // signed, LE
                line = AudioSystem.getSourceDataLine(fmt);
                line.open(fmt, 1 << 15);
                line.start();
                FloatControl gain = line.isControlSupported(FloatControl.Type.MASTER_GAIN)
                        ? (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN) : null;

                int pos = 0;
                boolean lineRunning = true;
                int chunk = 8192 - (8192 % (2 * channels));    // keep writes frame-aligned
                while (running) {
                    // Yield to an explicitly-assigned music track and pause with the video.
                    boolean play = !paused && NonprofitMusic.getMusicFor(bgName) == null;
                    if (!play) {
                        if (lineRunning) { line.stop(); lineRunning = false; }
                        Thread.sleep(60);
                        continue;
                    }
                    if (!lineRunning) { line.start(); lineRunning = true; }
                    if (gain != null) applyVolume(gain);
                    int n = Math.min(chunk, pcm.length - pos);
                    line.write(pcm, pos, n);
                    pos += n;
                    if (pos >= pcm.length) pos = 0;            // seamless loop
                }
            } catch (Throwable t) {
                ModularBackgrounds.LOGGER.warn("[Video] audio loop ended for '{}': {}", bgName, t.toString());
            } finally {
                try { if (line != null) { line.stop(); line.flush(); line.close(); } } catch (Throwable ignored) { }
            }
        }

        private static void applyVolume(FloatControl gain) {
            float vol;
            try {
                MinecraftClient c = MinecraftClient.getInstance();
                vol = c.options.getSoundVolume(SoundCategory.MUSIC) * c.options.getSoundVolume(SoundCategory.MASTER);
            } catch (Throwable t) { vol = 0.5f; }
            vol = Math.max(0f, Math.min(1f, vol));
            float dB = (vol <= 0.0001f) ? gain.getMinimum() : (float) (20.0 * Math.log10(vol));
            dB = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB));
            try { gain.setValue(dB); } catch (Throwable ignored) { }
        }
    }
}
