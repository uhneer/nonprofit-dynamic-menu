package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import dev.nonprofit.modularbg.background.YouTubeImporter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Add background" popup: choose a file, DRAG one onto the window, or paste a YouTube link — the
 * link path downloads the clip (up to 10 minutes, up to 1440p, free via yt-dlp) and imports it as
 * an MP4 background like any other.
 */
public class AddBackgroundScreen extends Screen {

    private final Screen parent;
    private final Consumer<String> onImported;   // imported background name (render thread)
    private TextFieldWidget url;
    private volatile String status = "§8...or drag and drop a file anywhere on this window";
    private volatile boolean busy = false;

    public AddBackgroundScreen(Screen parent, Consumer<String> onImported) {
        super(Text.literal("Add background"));
        this.parent = parent;
        this.onImported = onImported;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 40, 320);
        addDrawableChild(ButtonWidget.builder(Text.literal("📁 Import from file"), b -> {
            String picked = NonprofitBackgrounds.openFilePicker();
            if (picked != null) finish(NonprofitBackgrounds.importAndSelect(picked));
        }).dimensions(cx - w / 2, 70, w, 20).build());

        url = new TextFieldWidget(this.textRenderer, cx - w / 2, 120, w - 78, 18, Text.literal("YouTube link"));
        url.setPlaceholder(Text.literal("§8Paste a YouTube link..."));
        url.setMaxLength(300);
        addDrawableChild(url);
        addDrawableChild(ButtonWidget.builder(Text.literal("⬇ Import"), b -> startYoutube())
                .dimensions(cx + w / 2 - 72, 119, 72, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
                .dimensions(cx - 50, this.height - 30, 100, 20).build());
    }

    private void startYoutube() {
        if (busy) return;
        String link = url.getText().trim();
        if (link.isEmpty()) { status = "§cpaste a link first"; return; }
        busy = true;
        YouTubeImporter.importUrl(link, s -> status = s, mp4 -> {
            busy = false;
            if (mp4 != null)
                MinecraftClient.getInstance().execute(() ->
                        finish(NonprofitBackgrounds.importAndSelect(mp4.toString())));
        });
    }

    private void finish(String name) {
        if (name == null) { status = "§cimport failed (see log)"; return; }
        onImported.accept(name);
        this.client.setScreen(parent);
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        if (busy || paths.isEmpty()) return;
        finish(NonprofitBackgrounds.importAndSelect(paths.get(0).toString()));
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
        ctx.drawCenteredTextWithShadow(tr, this.title, cx, 24, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§7Images, GIFs and MP4s all work."), cx, 38, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§7From YouTube: up to 10 minutes, up to 1440p, free."), cx, 104, 0xFFFFFFFF);
        if (status != null)
            ctx.drawCenteredTextWithShadow(tr, Text.literal(status), cx, 150, 0xFFFFFFFF);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
