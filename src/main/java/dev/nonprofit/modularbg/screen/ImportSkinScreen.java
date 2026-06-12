package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.SkinHub;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Import a skin: browse for a .zip, drag one onto the window, or paste a SKIN CODE — the short id
 * the hub gives every accepted upload, so people can share skins as a few characters in chat.
 */
public class ImportSkinScreen extends Screen {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    private final Screen parent;
    private final Consumer<String> onImported;
    private TextFieldWidget code;
    private volatile String status = "§8...or drag a .zip anywhere on this window";
    private volatile boolean busy = false;

    public ImportSkinScreen(Screen parent, Consumer<String> onImported) {
        super(Text.literal("Import skin"));
        this.parent = parent;
        this.onImported = onImported;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 40, 320);
        addDrawableChild(ButtonWidget.builder(Text.literal("📁 Import from .zip"), b -> {
            String zip = BackgroundPackage.pickImportZip();
            if (zip != null) finish(BackgroundPackage.importZip(zip));
        }).dimensions(cx - w / 2, 70, w, 20).build());

        code = new TextFieldWidget(this.textRenderer, cx - w / 2, 120, w - 78, 18, Text.literal("Skin code"));
        code.setPlaceholder(Text.literal("§8Paste a skin code..."));
        code.setMaxLength(24);
        addDrawableChild(code);
        addDrawableChild(ButtonWidget.builder(Text.literal("⬇ Fetch"), b -> fetchCode())
                .dimensions(cx + w / 2 - 72, 119, 72, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
                .dimensions(cx - 50, this.height - 30, 100, 20).build());
    }

    private void fetchCode() {
        if (busy) return;
        String c = code.getText().trim().toUpperCase(Locale.ROOT);
        if (c.isEmpty()) { status = "§cpaste a code first"; return; }
        busy = true;
        status = "§elooking up " + c + "...";
        Thread t = new Thread(() -> {
            try {
                HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(
                                URI.create(SkinHub.API + "/skin/" + c))
                        .timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) { status = "§cno skin with that code (or the hub isn't live yet)"; busy = false; return; }
                Path tmp = Files.createTempFile("ndm-skin", ".zip");
                Files.write(tmp, res.body());
                MinecraftClient.getInstance().execute(() -> {
                    finish(BackgroundPackage.importZip(tmp.toString()));
                    try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
                    busy = false;
                });
            } catch (Throwable t2) {
                ModularBackgrounds.LOGGER.warn("[SkinHub] code fetch failed", t2);
                status = "§ccouldn't reach the Skin Hub (offline? not launched yet?)";
                busy = false;
            }
        }, "ndm-skincode");
        t.setDaemon(true);
        t.start();
    }

    private void finish(String name) {
        if (name == null) { status = "§cimport failed (see log)"; return; }
        onImported.accept(name);
        this.client.setScreen(parent);
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        for (Path p : paths)
            if (p.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                finish(BackgroundPackage.importZip(p.toString()));
                return;
            }
        status = "§cdrop a .zip skin package";
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
                Text.literal("§7A skin code is the short id of any skin on the Skin Hub."), cx, 104, 0xFFFFFFFF);
        if (status != null)
            ctx.drawCenteredTextWithShadow(tr, Text.literal(status), cx, 150, 0xFFFFFFFF);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
