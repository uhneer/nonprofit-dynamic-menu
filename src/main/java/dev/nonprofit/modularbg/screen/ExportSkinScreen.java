package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.SkinHub;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Export a skin: save it as a shareable .zip, or copy its hub SKIN CODE. The code button is
 * disabled until this skin has been uploaded to the Skin Hub and accepted (that's where codes
 * come from), and says so.
 */
public class ExportSkinScreen extends Screen {

    private final Screen parent;
    private final String bgName;
    private String status;

    public ExportSkinScreen(Screen parent, String bgName) {
        super(Text.literal("Export — " + bgName));
        this.parent = parent;
        this.bgName = bgName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 40, 280);
        addDrawableChild(ButtonWidget.builder(Text.literal("💾 Save as .zip"), b -> {
            String dest = BackgroundPackage.pickExportZip(bgName.replaceAll("\\.[^.]+$", ""));
            if (dest != null)
                status = BackgroundPackage.export(bgName, dest)
                        ? "§a✔ exported" : "§cexport failed (see log)";
        }).dimensions(cx - w / 2, 80, w, 20).build());

        String code = SkinHub.codeFor(bgName);
        ButtonWidget copy = ButtonWidget.builder(
                Text.literal(code != null ? "⧉ Copy skin code (" + code + ")" : "⧉ Copy skin code"), b -> {
            if (code != null) {
                this.client.keyboard.setClipboard(code);
                status = "§a✔ code copied — share it anywhere";
            }
        }).dimensions(cx - w / 2, 106, w, 20).build();
        copy.active = code != null;
        copy.setTooltip(Tooltip.of(Text.literal(code != null
                ? "Anyone can paste this code in Import to get your skin"
                : "Codes come from the Skin Hub: upload this skin and once it's accepted it gets a share code")));
        addDrawableChild(copy);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - 50, this.height - 30, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 30, 0xFFFFFFFF);
        if (status != null)
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
                    this.width / 2, 140, 0xFFFFFFFF);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
