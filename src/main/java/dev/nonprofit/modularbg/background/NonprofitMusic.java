package dev.nonprofit.modularbg.background;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/**
 * nonprofit's menu-music engine — a per-background, looping OGG player.
 *
 * Each background can have an associated .ogg track (set via the ♪ button in the
 * Backgrounds screen). When that background is the active one and we're on a menu
 * screen, the track streams on a loop and vanilla menu music is suppressed.
 * Backgrounds with no associated track behave exactly as before (vanilla / resource-pack
 * music plays), so nothing regresses.
 *
 * Playback is fully self-contained and isolated from Minecraft's audio system:
 *   STB Vorbis (stream-decode OGG)  →  javax.sound SourceDataLine  →  daemon thread.
 * Streaming keeps memory tiny even for multi-hour tracks. Everything is wrapped; a
 * decode/device failure logs and falls back to silence — it can never crash the game.
 */
public final class NonprofitMusic {

    private static Path musicFolder;
    private static Path assocFile;
    private static final Properties assoc = new Properties(); // bgKey -> stored ogg filename

    private static volatile Thread  thread;
    private static volatile boolean running;
    private static volatile String  currentTrack;
    private static boolean loggedFailure = false;

    private NonprofitMusic() {}

    // ── lifecycle ────────────────────────────────────────────────────────────────

    public static void init() {
        try {
            Path base = NonprofitBackgrounds.getFolder();
            if (base == null) {
                base = MinecraftClient.getInstance().runDirectory.toPath()
                        .resolve("config").resolve("nonprofit-backgrounds");
            }
            musicFolder = base.resolve("music");
            Files.createDirectories(musicFolder);
            assocFile = base.resolve(".music");
            if (Files.exists(assocFile)) {
                try (var in = Files.newInputStream(assocFile)) { assoc.load(in); }
            }
            ModularBackgrounds.LOGGER.info("[Music] Engine ready. Folder: {}", musicFolder);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Music] init failed — menu music disabled this session", t);
        }
    }

    // ── associations ─────────────────────────────────────────────────────────────

    private static String key(String bgKey) { return (bgKey == null || bgKey.isEmpty()) ? "default" : bgKey; }

    /** Stored ogg filename associated with a background key, or null. */
    public static String getMusicFor(String bgKey) {
        return assoc.getProperty(key(bgKey));
    }

    /** Display name (filename without extension) for tooltips. */
    public static String display(String storedName) {
        if (storedName == null) return "none";
        int dot = storedName.lastIndexOf('.');
        return dot > 0 ? storedName.substring(0, dot) : storedName;
    }

    /**
     * Install a picked audio file and associate it with the background. OGG and WAV are copied
     * as-is; MP4/M4A get their AAC track decoded ONCE into a 16-bit WAV next to them (this is how
     * "use this video's audio as the menu music" works — pick the .mp4 itself).
     */
    public static String setMusicFor(String bgKey, String sourcePath) {
        try {
            if (musicFolder == null || sourcePath == null || sourcePath.isEmpty()) return null;
            Path src = Paths.get(sourcePath);
            if (!Files.exists(src)) return null;
            String name = src.getFileName().toString();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".m4a") || lower.endsWith(".aac")) {
                String wavName = name.replaceAll("\\.[^.]+$", "") + ".wav";
                if (!aacToWav(src, musicFolder.resolve(wavName))) {
                    ModularBackgrounds.LOGGER.warn("[Music] no decodable AAC audio in {}", name);
                    return null;
                }
                name = wavName;
            } else {
                Files.copy(src, musicFolder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            assoc.setProperty(key(bgKey), name);
            persist();
            // if this background is the active one, force a restart so it plays now
            if (currentTrack != null) stop();
            ModularBackgrounds.LOGGER.info("[Music] set '{}' for background '{}'", name, key(bgKey));
            return name;
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Music] setMusicFor failed", t);
            return null;
        }
    }

    private static void persist() {
        try (var out = Files.newOutputStream(assocFile)) {
            assoc.store(out, "nonprofit menu-music associations (backgroundKey = music file)");
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Music] could not save associations", t);
        }
    }

    /** Move a background's music association to a new key (used when a background is renamed). */
    public static void renameAssoc(String oldKey, String newKey) {
        try {
            String v = assoc.getProperty(key(oldKey));
            if (v != null) {
                assoc.remove(key(oldKey));
                assoc.setProperty(key(newKey), v);
                persist();
            }
        } catch (Throwable ignored) {}
    }

    /** Clear a background's music association and stop playback so it re-evaluates. */
    public static void removeMusicFor(String bgKey) {
        try {
            assoc.remove(key(bgKey));
            persist();
            stop();
        } catch (Throwable ignored) {}
    }

    // ── native ogg file picker ─────────────────────────────────────────────────────

    public static String pickOgg() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.ogg"));
            filters.put(stack.UTF8("*.wav"));
            filters.put(stack.UTF8("*.mp4"));
            filters.put(stack.UTF8("*.m4a"));
            filters.put(stack.UTF8("*.aac"));
            filters.flip();
            return TinyFileDialogs.tinyfd_openFileDialog(
                    "Select menu music (ogg / wav / mp4 / m4a)", "", filters,
                    "Audio (*.ogg, *.wav, *.mp4, *.m4a)", false);
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Music] file picker unavailable", t);
            return null;
        }
    }

    /** Decode an MP4/M4A's AAC track to a 16-bit little-endian WAV (JCodec demux + JAAD). */
    private static boolean aacToWav(Path src, Path dstWav) {
        try (var ch = org.jcodec.common.io.NIOUtils.readableChannel(src.toFile())) {
            var dx = org.jcodec.containers.mp4.demuxer.MP4Demuxer.createMP4Demuxer(ch);
            for (var at : dx.getAudioTracks()) {
                var m = at.getMeta();
                if (m == null || m.getCodec() != org.jcodec.common.Codec.AAC) continue;
                java.nio.ByteBuffer ascBuf = m.getCodecPrivate();
                if (ascBuf == null) continue;
                byte[] asc = new byte[ascBuf.remaining()];
                ascBuf.duplicate().get(asc);
                var dec = new net.sourceforge.jaad.aac.Decoder(asc);
                var sb = new net.sourceforge.jaad.aac.SampleBuffer();
                var pcm = new java.io.ByteArrayOutputStream(1 << 20);
                int rate = 0, chn = 0;
                org.jcodec.common.model.Packet pkt;
                while ((pkt = at.nextFrame()) != null) {
                    java.nio.ByteBuffer d = pkt.getData();
                    byte[] aac = new byte[d.remaining()];
                    d.duplicate().get(aac);
                    try { dec.decodeFrame(aac, sb); } catch (Throwable e) { continue; }
                    if (sb.isBigEndian()) sb.setBigEndian(false);
                    pcm.write(sb.getData());
                    rate = sb.getSampleRate();
                    chn = sb.getChannels();
                }
                if (pcm.size() > 0 && rate > 0 && chn >= 1 && chn <= 2) {
                    writeWav(dstWav, pcm.toByteArray(), rate, chn);
                    ModularBackgrounds.LOGGER.info("[Music] extracted {} Hz {} ch audio from {}",
                            rate, chn, src.getFileName());
                    return true;
                }
            }
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Music] aac extract failed for {}", src.getFileName(), t);
        }
        return false;
    }

    /** Minimal RIFF/WAVE writer for 16-bit LE PCM. */
    private static void writeWav(Path dst, byte[] pcm, int rate, int channels) throws Exception {
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
         .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        try (var out = Files.newOutputStream(dst)) {
            out.write(h.array());
            out.write(pcm);
        }
    }

    // ── per-tick driver (called from the client tick, both in menus and in game) ─────

    public static void tick(MinecraftClient client) {
        try {
            if (client == null) return;

            // In a world → release control; let vanilla game music play normally.
            if (client.world != null) {
                if (isPlaying()) stop();
                return;
            }

            // In the menus WE are the sole source of menu music. Always silence vanilla menu
            // music so a background with no assigned track is genuinely silent — no default
            // (resource-pack or vanilla) ever carries over.
            try { client.getMusicTracker().stop(); } catch (Throwable ignored) {}

            String activeBg = NonprofitBackgrounds.isDefault() ? "default" : NonprofitBackgrounds.getSelected();
            String desired  = getMusicFor(activeBg);

            if (desired == null) {                 // this background has no music → silence
                if (isPlaying()) stop();
                return;
            }
            if (musicFolder == null) return;
            Path file = musicFolder.resolve(desired);
            if (!Files.exists(file)) { if (isPlaying()) stop(); return; }

            if (!desired.equals(currentTrack) || !isPlaying()) start(file, desired);
        } catch (Throwable t) {
            if (!loggedFailure) { ModularBackgrounds.LOGGER.warn("[Music] tick failed", t); loggedFailure = true; }
        }
    }

    // ── playback (daemon thread) ─────────────────────────────────────────────────────

    public static boolean isPlaying() {
        Thread t = thread;
        return running && t != null && t.isAlive();
    }

    private static synchronized void start(Path file, String trackName) {
        stop();
        running = true;
        currentTrack = trackName;
        thread = new Thread(() -> playLoop(file), "nonprofit-menu-music");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void stop() {
        running = false;
        currentTrack = null;
        thread = null; // daemon loop sees running=false and exits, closing its own line
    }

    private static void playLoop(Path file) {
        if (file.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
            playWavLoop(file);
            return;
        }
        playOggLoop(file);
    }

    /** Looping playback for WAV (javax.sound streaming; reopened at EOF for a gapless-ish loop). */
    private static void playWavLoop(Path file) {
        SourceDataLine line = null;
        try {
            AudioFormat fmt;
            try (var in = AudioSystem.getAudioInputStream(file.toFile())) { fmt = in.getFormat(); }
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, 1 << 15);
            line.start();
            FloatControl gain = line.isControlSupported(FloatControl.Type.MASTER_GAIN)
                    ? (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN) : null;
            byte[] buf = new byte[1 << 14];
            while (running) {
                try (var in = AudioSystem.getAudioInputStream(file.toFile())) {
                    int n;
                    while (running && (n = in.read(buf)) > 0) {
                        if (gain != null) applyVolume(gain);
                        line.write(buf, 0, n);
                    }
                }
            }
        } catch (Throwable t) {
            warnOnce("wav playback error: " + t);
        } finally {
            try { if (line != null) { line.stop(); line.flush(); line.close(); } } catch (Throwable ignored) {}
        }
    }

    private static void playOggLoop(Path file) {
        long handle = 0L;
        SourceDataLine line = null;
        try {
            handle = STBVorbis.stb_vorbis_open_filename(file.toString(), new int[1], null);
            if (handle == 0L) { warnOnce("could not open ogg: " + file.getFileName()); return; }

            int channels, rate;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                STBVorbis.stb_vorbis_get_info(handle, info);
                channels = info.channels();
                rate     = info.sample_rate();
            }
            if (channels < 1 || channels > 2 || rate <= 0) { warnOnce("unsupported ogg format"); return; }

            AudioFormat fmt = new AudioFormat((float) rate, 16, channels, true, false); // signed, little-endian
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, 1 << 15);
            line.start();
            FloatControl gain = line.isControlSupported(FloatControl.Type.MASTER_GAIN)
                    ? (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN) : null;

            short[] samples = new short[4096 * channels];
            byte[]  bytes   = new byte[samples.length * 2];

            while (running) {
                int perChannel = STBVorbis.stb_vorbis_get_samples_short_interleaved(handle, channels, samples);
                if (perChannel <= 0) { STBVorbis.stb_vorbis_seek_start(handle); continue; } // loop at EOF
                int n = perChannel * channels;
                for (int i = 0; i < n; i++) {
                    short s = samples[i];
                    bytes[i * 2]     = (byte) (s & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                }
                if (gain != null) applyVolume(gain);
                line.write(bytes, 0, n * 2);
            }
        } catch (Throwable t) {
            warnOnce("playback error: " + t);
        } finally {
            try { if (line != null) { line.stop(); line.flush(); line.close(); } } catch (Throwable ignored) {}
            try { if (handle != 0L) STBVorbis.stb_vorbis_close(handle); } catch (Throwable ignored) {}
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
        try { gain.setValue(dB); } catch (Throwable ignored) {}
    }

    private static void warnOnce(String msg) {
        if (!loggedFailure) { ModularBackgrounds.LOGGER.warn("[Music] {}", msg); loggedFailure = true; }
    }
}
