package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.ModularBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * Crop an image to a slot's aspect ratio before installing it: the crop window is locked to the
 * target shape, dragged to choose the visible area, and resized with the scroll wheel. Decoding
 * and the final crop run through AWT (PNG/JPEG/GIF/BMP all fine); the result is saved as a PNG and
 * handed to the callback.
 */
public class ImageCropScreen extends Screen {

    private final Screen parent;
    private final Path source;
    private final int aspectW, aspectH;
    private final Consumer<Path> onCropped;     // temp PNG path (render thread)

    private BufferedImage img;
    private Identifier tex;
    private int imgW, imgH;
    private String error;

    // Crop rect in IMAGE pixels (w/h locked to aspect).
    private double cropX, cropY, cropW, cropH;
    private boolean dragging = false;
    private double dragOffX, dragOffY;
    // Preview rect on screen.
    private int pvX, pvY, pvW, pvH;

    public ImageCropScreen(Screen parent, Path source, int aspectW, int aspectH, Consumer<Path> onCropped) {
        super(Text.literal("Crop image"));
        this.parent = parent;
        this.source = source;
        this.aspectW = Math.max(1, aspectW);
        this.aspectH = Math.max(1, aspectH);
        this.onCropped = onCropped;
        load();
    }

    private void load() {
        try {
            img = ImageIO.read(source.toFile());
            if (img == null) throw new IllegalStateException("unreadable image");
            imgW = img.getWidth();
            imgH = img.getHeight();
            // Largest aspect-true crop, centered.
            double ar = aspectH / (double) aspectW;
            cropW = imgW;
            cropH = cropW * ar;
            if (cropH > imgH) { cropH = imgH; cropW = cropH / ar; }
            cropX = (imgW - cropW) / 2;
            cropY = (imgH - cropH) / 2;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BufferedImage rgba = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            rgba.createGraphics().drawImage(img, 0, 0, null);
            ImageIO.write(rgba, "png", bos);
            NativeImage ni = NativeImage.read(bos.toByteArray());
            tex = Identifier.of("nonprofit", "cropsrc");
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(tex, new NativeImageBackedTexture(() -> "ndm-crop", ni));
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Crop] load failed", t);
            error = "could not read that image";
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("✔ Use this crop"), b -> save())
                .dimensions(cx - 104, this.height - 28, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
                .dimensions(cx + 4, this.height - 28, 100, 20).build());
    }

    private void save() {
        try {
            BufferedImage crop = img.getSubimage((int) cropX, (int) cropY,
                    (int) Math.min(cropW, imgW - cropX), (int) Math.min(cropH, imgH - cropY));
            // Cap the output so a 4K source doesn't become a giant icon file.
            int maxW = Math.min(crop.getWidth(), 1024);
            int outH = Math.max(1, Math.round(maxW * aspectH / (float) aspectW));
            BufferedImage out = new BufferedImage(maxW, outH, BufferedImage.TYPE_INT_ARGB);
            var g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(crop, 0, 0, maxW, outH, null);
            g.dispose();
            Path tmp = Files.createTempFile("ndm-crop", ".png");
            ImageIO.write(out, "png", tmp.toFile());
            onCropped.accept(tmp);
            this.close();
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[Crop] save failed", t);
            error = "crop failed (see log)";
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(tr, this.title, cx, 12, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§7drag the bright window to choose what's visible — scroll to grow / shrink it"),
                cx, 24, 0xFFFFFFFF);
        if (error != null) {
            ctx.drawCenteredTextWithShadow(tr, Text.literal("§c" + error), cx, this.height / 2, 0xFFFFFFFF);
            return;
        }

        // Fit the source image into the available area.
        int areaW = this.width - 60, areaH = this.height - 80;
        float sc = Math.min(areaW / (float) imgW, areaH / (float) imgH);
        pvW = Math.max(1, Math.round(imgW * sc));
        pvH = Math.max(1, Math.round(imgH * sc));
        pvX = cx - pvW / 2;
        pvY = 38 + (areaH - pvH) / 2;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, pvX, pvY, 0f, 0f, pvW, pvH, pvW, pvH, 0xFFFFFFFF);

        // Darken everything outside the crop, outline the crop.
        int rx = pvX + (int) (cropX * sc), ry = pvY + (int) (cropY * sc);
        int rw = (int) (cropW * sc), rh = (int) (cropH * sc);
        ctx.fill(pvX, pvY, pvX + pvW, ry, 0xA0000000);
        ctx.fill(pvX, ry + rh, pvX + pvW, pvY + pvH, 0xA0000000);
        ctx.fill(pvX, ry, rx, ry + rh, 0xA0000000);
        ctx.fill(rx + rw, ry, pvX + pvW, ry + rh, 0xA0000000);
        ctx.fill(rx, ry, rx + rw, ry + 1, 0xFFFFFF66);
        ctx.fill(rx, ry + rh - 1, rx + rw, ry + rh, 0xFFFFFF66);
        ctx.fill(rx, ry, rx + 1, ry + rh, 0xFFFFFF66);
        ctx.fill(rx + rw - 1, ry, rx + rw, ry + rh, 0xFFFFFF66);

        ctx.drawCenteredTextWithShadow(tr, Text.literal("§8" + (int) cropW + " × " + (int) cropH
                        + "  →  saved at the slot's " + aspectW + ":" + aspectH + " shape"),
                cx, this.height - 42, 0xFFFFFFFF);
    }

    private float scale() {
        return Math.min((this.width - 60) / (float) imgW, (this.height - 80) / (float) imgH);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (error == null) {
            float sc = scale();
            double ix = (click.x() - pvX) / sc, iy = (click.y() - pvY) / sc;
            if (ix >= 0 && ix < imgW && iy >= 0 && iy < imgH) {
                dragging = true;
                dragOffX = ix - cropX;
                dragOffY = iy - cropY;
                if (dragOffX < 0 || dragOffX > cropW || dragOffY < 0 || dragOffY > cropH) {
                    dragOffX = cropW / 2;     // clicked outside the window → jump it under the cursor
                    dragOffY = cropH / 2;
                }
                moveTo(ix, iy);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging) {
            float sc = scale();
            moveTo((click.x() - pvX) / sc, (click.y() - pvY) / sc);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    private void moveTo(double ix, double iy) {
        cropX = Math.max(0, Math.min(imgW - cropW, ix - dragOffX));
        cropY = Math.max(0, Math.min(imgH - cropH, iy - dragOffY));
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (error != null) return true;
        double factor = vertical > 0 ? 1.08 : 1 / 1.08;
        double ar = aspectH / (double) aspectW;
        double nw = Math.max(16, Math.min(cropW * factor, Math.min(imgW, imgH / ar)));
        double midX = cropX + cropW / 2, midY = cropY + cropH / 2;
        cropW = nw;
        cropH = nw * ar;
        cropX = Math.max(0, Math.min(imgW - cropW, midX - cropW / 2));
        cropY = Math.max(0, Math.min(imgH - cropH, midY - cropH / 2));
        return true;
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
