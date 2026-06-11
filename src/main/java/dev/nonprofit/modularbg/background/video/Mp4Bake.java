package dev.nonprofit.modularbg.background.video;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.AWTUtil;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-time MP4 "bake": decodes the whole clip ONCE (pure-Java JCodec, GOP-parallel across a few
 * worker threads) into a disk cache of JPEG frames + raw PCM audio, so playback never touches an
 * H.264 decoder again. This is the design that makes looping mathematically reliable: playback is
 * just "read frame N", with N derived from a monotonic clock — nothing can race, drift, or flicker.
 *
 * Cache layout ({@code <backgrounds>/.video-cache/<key>/}):
 *   f00000.jpg …      one JPEG per frame, full source resolution (no downscaling)
 *   audio.pcm         16-bit little-endian interleaved PCM (only if the MP4 has a decodable AAC track)
 *   meta.properties   width/height/frames/duration/audio info + source size+mtime (staleness check)
 *
 * The source MP4 is only ever open during the bake — playback holds no file handles on it, so
 * deleting/replacing backgrounds can't hit Windows file locks. All failures are caught and surfaced
 * via {@link #isFailed()}; a failed bake never crashes the game.
 */
public final class Mp4Bake {

    static final String META = "meta.properties";
    static final String AUDIO = "audio.pcm";

    /** Parsed, validated cache metadata. Only returned for complete, non-stale caches. */
    public static final class Meta {
        public int width, height, frames;
        public double duration;
        public boolean audio;
        public int audioRate, audioChannels;

        public double fps() {
            return (duration > 0 && frames > 0) ? frames / duration : 30.0;
        }
    }

    private final Path src, dir;
    private final AtomicInteger done = new AtomicInteger();
    private volatile int total = 0;                 // 0 → indeterminate
    private volatile boolean cancelled, failed, finished;
    private volatile int width, height;

    Mp4Bake(Path src, Path dir) {
        this.src = src;
        this.dir = dir;
    }

    void start() {
        Thread t = new Thread(this::run, "nonprofit-video-bake");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    void cancel() { cancelled = true; }
    boolean isFinished() { return finished; }
    boolean isFailed() { return failed; }

    /** Human progress for the title screen, e.g. "preparing video 42%". */
    String progressText() {
        int t = total;
        if (t <= 0) return "preparing video " + done.get() + " frames";
        return "preparing video " + (int) (100.0 * done.get() / t) + "%";
    }

    static String frameName(int idx) { return String.format("f%05d.jpg", idx); }

    /** Reads the cache meta; null if missing, incomplete, or stale vs. the source file. */
    static Meta readMeta(Path dir, Path src) {
        try {
            Path mf = dir.resolve(META);
            if (!Files.exists(mf)) return null;
            Properties p = new Properties();
            try (var in = Files.newInputStream(mf)) { p.load(in); }
            if (!"true".equals(p.getProperty("complete"))) return null;
            long size = Long.parseLong(p.getProperty("srcSize", "-1"));
            long mtime = Long.parseLong(p.getProperty("srcMtime", "-1"));
            if (Files.exists(src)
                    && (Files.size(src) != size || Files.getLastModifiedTime(src).toMillis() != mtime))
                return null;                                   // source changed → stale
            Meta m = new Meta();
            m.width = Integer.parseInt(p.getProperty("width"));
            m.height = Integer.parseInt(p.getProperty("height"));
            m.frames = Integer.parseInt(p.getProperty("frames"));
            m.duration = Double.parseDouble(p.getProperty("duration"));
            m.audio = "true".equals(p.getProperty("audio"));
            m.audioRate = Integer.parseInt(p.getProperty("audioRate", "0"));
            m.audioChannels = Integer.parseInt(p.getProperty("audioChannels", "0"));
            if (m.frames <= 0 || m.width <= 0 || m.height <= 0) return null;
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Recursively deletes a background's frame cache (e.g. when the background is deleted). */
    public static void deleteCache(Path dir) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir);
        } catch (Throwable ignored) { }
    }

    // ── the bake ─────────────────────────────────────────────────────────────────

    private void run() {
        try {
            deleteCache(dir);                                  // clear any stale partial cache
            Files.createDirectories(dir);

            // Probe the video track for frame count / duration (used for worker split + fps).
            int totalFrames;
            double duration;
            try (SeekableByteChannel ch = NIOUtils.readableChannel(src.toFile())) {
                MP4Demuxer dx = MP4Demuxer.createMP4Demuxer(ch);
                DemuxerTrack vt = dx.getVideoTrack();
                if (vt == null) throw new IllegalStateException("no video track");
                DemuxerTrackMeta m = vt.getMeta();
                totalFrames = m != null ? m.getTotalFrames() : 0;
                duration = m != null ? m.getTotalDuration() : 0;
            }
            total = Math.max(totalFrames, 0);

            // Decode frame 0 synchronously first: gives the title screen an instant static frame,
            // tells us the real output dimensions, and anchors the display-timestamp base used to
            // index every other frame (H.264 B-frames decode out of display order; naming frames by
            // round((timestamp - base) × fps) bakes them into correct DISPLAY order regardless).
            double baseTs;
            try (SeekableByteChannel ch = NIOUtils.readableChannel(src.toFile())) {
                FrameGrab grab = FrameGrab.createFrameGrab(ch);
                PictureWithMetadata pm = grab.getNativeFrameWithMetadata();
                if (pm == null || pm.getPicture() == null) throw new IllegalStateException("no decodable frames");
                baseTs = pm.getTimestamp();
                BufferedImage bi = AWTUtil.toBufferedImage(pm.getPicture());
                width = bi.getWidth();
                height = bi.getHeight();
                writeJpeg(bi, dir.resolve(frameName(0)), newJpegWriter());
                done.incrementAndGet();
            }

            int frames;
            double fullDuration = duration;
            if (totalFrames > 1 && duration > 0) {
                double fps = totalFrames / duration;
                frames = bakeParallel(totalFrames, baseTs, fps);
                if (frames < totalFrames && frames > 0) {
                    // Truncated cache (a trailing gap) → scale duration so fps stays exact.
                    duration = fullDuration * frames / totalFrames;
                    ModularBackgrounds.LOGGER.warn("[Video] '{}' cache truncated to {} of {} frames",
                            src.getFileName(), frames, totalFrames);
                }
            } else {
                frames = 1 + bakeSequential(1);                // unknown meta → single pass, decode order
            }
            if (cancelled) return;
            if (frames <= 0) throw new IllegalStateException("decoded 0 frames");
            if (duration <= 0) duration = frames / 30.0;

            int[] audioInfo = bakeAudio();                     // {rate, channels} or null

            Properties p = new Properties();
            p.setProperty("width", String.valueOf(width));
            p.setProperty("height", String.valueOf(height));
            p.setProperty("frames", String.valueOf(frames));
            p.setProperty("duration", String.valueOf(duration));
            p.setProperty("audio", String.valueOf(audioInfo != null));
            if (audioInfo != null) {
                p.setProperty("audioRate", String.valueOf(audioInfo[0]));
                p.setProperty("audioChannels", String.valueOf(audioInfo[1]));
            }
            p.setProperty("srcSize", String.valueOf(Files.size(src)));
            p.setProperty("srcMtime", String.valueOf(Files.getLastModifiedTime(src).toMillis()));
            p.setProperty("complete", "true");
            try (OutputStream out = Files.newOutputStream(dir.resolve(META))) {
                p.store(out, "nonprofit video frame cache");
            }
            finished = true;
            ModularBackgrounds.LOGGER.info("[Video] baked '{}': {} frames {}x{}, {}s, audio={}",
                    src.getFileName(), frames, width, height,
                    String.format(java.util.Locale.ROOT, "%.1f", duration), audioInfo != null);
        } catch (Throwable t) {
            failed = true;
            ModularBackgrounds.LOGGER.warn("[Video] bake failed for '{}'", src.getFileName(), t);
        }
    }

    /**
     * GOP-parallel bake over a known frame count, frames named by DISPLAY index
     * ({@code round((timestamp - baseTs) × fps)}). Workers split the clip by decode position;
     * because B-frame reordering can straggle a couple of frames across a split point, each worker
     * decodes a few frames past its range and accepts indices within a ±3 window. Overlapping writes
     * are identical content (harmless); a final gapless check truncates at any hole.
     * Returns the contiguous frame count from 0.
     */
    private int bakeParallel(int totalFrames, double baseTs, double fps) throws Exception {
        final int OVERSHOOT = 4, WINDOW = 3;
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2 - 1));
        int per = (totalFrames + workers - 1) / workers;
        List<Thread> threads = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int wi = 0; wi < workers; wi++) {
            // Frame 0 is already on disk; worker 0 resumes at decode position 1.
            final int start = (wi == 0) ? 1 : wi * per;
            final int end = Math.min(totalFrames, (wi + 1) * per);
            if (start >= end) continue;
            final int winLo = Math.max(0, start - WINDOW);
            final int winHi = Math.min(totalFrames, end + WINDOW);
            Thread t = new Thread(() -> {
                try (SeekableByteChannel ch = NIOUtils.readableChannel(src.toFile())) {
                    FrameGrab grab = FrameGrab.createFrameGrab(ch);
                    if (start > 0) grab.seekToFramePrecise(start);
                    ImageWriter jw = newJpegWriter();
                    int toDecode = (end - start) + OVERSHOOT;
                    for (int n = 0; n < toDecode && !cancelled; n++) {
                        PictureWithMetadata pm = grab.getNativeFrameWithMetadata();
                        if (pm == null || pm.getPicture() == null) break;
                        int idx = (int) Math.round((pm.getTimestamp() - baseTs) * fps);
                        if (idx < winLo || idx >= winHi) continue;     // outside this worker's window
                        writeJpeg(AWTUtil.toBufferedImage(pm.getPicture()), dir.resolve(frameName(idx)), jw);
                        if (idx >= start && idx < end) done.incrementAndGet();
                    }
                    jw.dispose();
                } catch (Throwable t2) {
                    failures.incrementAndGet();
                    ModularBackgrounds.LOGGER.warn("[Video] bake worker failed ({}..{})", start, end, t2);
                }
            }, "nonprofit-video-bake-" + wi);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) t.join();
        if (cancelled) return -1;
        if (failures.get() > 0) throw new IllegalStateException("bake worker(s) failed");
        // Verify the cache is gapless — playback depends on every frame existing.
        for (int i = 0; i < totalFrames; i++)
            if (!Files.exists(dir.resolve(frameName(i)))) return i;   // contiguous prefix only
        return totalFrames;
    }

    /** Sequential fallback when the container doesn't report a frame count. */
    private int bakeSequential(int startIdx) throws Exception {
        int count = 0;
        try (SeekableByteChannel ch = NIOUtils.readableChannel(src.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(ch);
            if (startIdx > 0) grab.seekToFramePrecise(startIdx);
            ImageWriter jw = newJpegWriter();
            Picture pic;
            while (!cancelled && (pic = grab.getNativeFrame()) != null) {
                writeJpeg(AWTUtil.toBufferedImage(pic), dir.resolve(frameName(startIdx + count)), jw);
                count++;
                done.incrementAndGet();
            }
            jw.dispose();
        }
        return count;
    }

    /** Extract + decode the AAC track to raw PCM (JCodec demux + its bundled JAAD decoder). */
    private int[] bakeAudio() {
        try (SeekableByteChannel ch = NIOUtils.readableChannel(src.toFile())) {
            MP4Demuxer dx = MP4Demuxer.createMP4Demuxer(ch);
            for (DemuxerTrack at : dx.getAudioTracks()) {
                DemuxerTrackMeta m = at.getMeta();
                if (m == null || m.getCodec() != Codec.AAC) continue;
                ByteBuffer ascBuf = m.getCodecPrivate();
                if (ascBuf == null) continue;
                byte[] asc = new byte[ascBuf.remaining()];
                ascBuf.duplicate().get(asc);

                Decoder dec = new Decoder(asc);
                SampleBuffer sb = new SampleBuffer();
                ByteArrayOutputStream pcm = new ByteArrayOutputStream(1 << 20);
                int rate = 0, chn = 0;
                Packet pkt;
                while (!cancelled && (pkt = at.nextFrame()) != null) {
                    ByteBuffer d = pkt.getData();
                    byte[] aac = new byte[d.remaining()];
                    d.duplicate().get(aac);
                    try {
                        dec.decodeFrame(aac, sb);
                    } catch (Throwable e) {
                        continue;                              // skip one bad AAC frame
                    }
                    if (sb.isBigEndian()) sb.setBigEndian(false);
                    pcm.write(sb.getData());
                    rate = sb.getSampleRate();
                    chn = sb.getChannels();
                }
                if (pcm.size() > 0 && rate > 0 && chn >= 1 && chn <= 2) {
                    Files.write(dir.resolve(AUDIO), pcm.toByteArray());
                    ModularBackgrounds.LOGGER.info("[Video] extracted audio: {} Hz, {} ch, {} KB",
                            rate, chn, pcm.size() / 1024);
                    return new int[]{rate, chn};
                }
            }
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.info("[Video] no usable audio track in '{}' ({})",
                    src.getFileName(), t.toString());
        }
        return null;
    }

    // ── JPEG writing (quality 0.9 — visually transparent for video frames) ─────────

    private static ImageWriter newJpegWriter() {
        return ImageIO.getImageWritersByFormatName("jpg").next();
    }

    private static void writeJpeg(BufferedImage bi, Path out, ImageWriter writer) throws Exception {
        // JPEG can't carry alpha; JCodec output is opaque RGB but normalize defensively.
        if (bi.getType() != BufferedImage.TYPE_INT_RGB && bi.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage rgb = new BufferedImage(bi.getWidth(), bi.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(bi, 0, 0, null);
            bi = rgb;
        }
        ImageWriteParam pr = writer.getDefaultWriteParam();
        pr.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        pr.setCompressionQuality(0.9f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(bi, null, null), pr);
        }
        Files.write(out, bos.toByteArray());
    }
}
