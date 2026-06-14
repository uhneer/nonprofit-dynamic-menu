package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.ModularBackgrounds;
import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.IconStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Submit a skin to the Skin Hub: pick a display name and one or more categories, hit Upload.
 * The package (.zip with everything the skin needs) is exported to
 * {@code config/nonprofit-backgrounds/.uploads/} and marked pending. When the hub's moderation
 * flow goes live (a Discord bot DMs the maintainer an embedded preview with every asset named, to
 * accept or deny), the pending state flips to accepted or denied. Until then submissions just wait
 * locally — nothing is lost.
 */
public class SkinUploadScreen extends Screen {

    private final Screen parent;
    private final String bgName;
    private TextFieldWidget nameField;
    private final Set<String> picked = new LinkedHashSet<>();
    private final List<Object[]> chipBoxes = new ArrayList<>();
    private String error = null;

    public SkinUploadScreen(Screen parent, String bgName) {
        super(Text.literal("Upload to the Skin Hub"));
        this.parent = parent;
        this.bgName = bgName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = Math.min(this.width - 40, 320);
        nameField = new TextFieldWidget(this.textRenderer, cx - w / 2, 60, w, 18, Text.literal("Name"));
        nameField.setPlaceholder(Text.literal("§8Skin name (what everyone will see)"));
        nameField.setText(bgName.replaceAll("\\.[^.]+$", "").replace('_', ' ').replace("!", ""));
        addDrawableChild(nameField);

        addDrawableChild(ButtonWidget.builder(Text.literal("⬆ Upload"), b -> submit())
                .dimensions(cx - 104, this.height - 30, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
                .dimensions(cx + 4, this.height - 30, 100, 20).build());
    }

    private void submit() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) { error = "give your skin a name first"; return; }
        if (picked.isEmpty()) { error = "pick at least one category"; return; }
        try {
            java.nio.file.Path uploads = dev.nonprofit.modularbg.background.SkinHub.uploadsDir();
            java.nio.file.Files.createDirectories(uploads);
            java.nio.file.Path zip = uploads.resolve(IconStore.keyFor(bgName) + ".zip");
            if (!BackgroundPackage.export(bgName, zip.toString())) {
                error = "could not package the skin (see log)";
                return;
            }
            // Records locally as pending and submits to the hub now (or queues for when it's live).
            dev.nonprofit.modularbg.background.SkinHub.submit(
                    bgName, name, new ArrayList<>(picked), zip, s -> { /* shown back on the hub screen */ });
            this.close();
        } catch (Throwable t) {
            ModularBackgrounds.LOGGER.warn("[SkinHub] upload failed", t);
            error = "upload failed (see log)";
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
        int cx = this.width / 2, w = Math.min(this.width - 40, 320);
        ctx.drawCenteredTextWithShadow(tr, this.title, cx, 20, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(tr,
                Text.literal("§7Your skin gets reviewed before it appears in the hub."), cx, 34, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, Text.literal("§7Categories §8(pick all that fit)"), cx - w / 2, 92, 0xFFFFFFFF);

        chipBoxes.clear();
        int chipX = cx - w / 2, chipY = 106;
        for (String c : SkinDatabaseScreen.CATEGORIES) {
            int cw = tr.getWidth(c) + 14;
            if (chipX + cw > cx + w / 2) { chipX = cx - w / 2; chipY += 20; }
            boolean sel = picked.contains(c);
            boolean hov = mouseX >= chipX && mouseX < chipX + cw && mouseY >= chipY && mouseY < chipY + 16;
            ctx.fill(chipX, chipY, chipX + cw, chipY + 16, sel ? 0xCC2E6B3A : hov ? 0x88333344 : 0x66222230);
            ctx.drawCenteredTextWithShadow(tr, Text.literal((sel ? "§a✔ " : "") + c),
                    chipX + cw / 2, chipY + 4, 0xFFFFFFFF);
            chipBoxes.add(new Object[]{ chipX, chipY, cw, c });
            chipX += cw + 6;
        }

        if (error != null)
            ctx.drawCenteredTextWithShadow(tr, Text.literal("§c" + error), cx, this.height - 46, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x(), my = (int) click.y();
        for (Object[] c : chipBoxes) {
            int x = (int) c[0], y = (int) c[1], w = (int) c[2];
            if (mx >= x && mx < x + w && my >= y && my < y + 16) {
                String cat = (String) c[3];
                if (!picked.remove(cat)) picked.add(cat);
                error = null;
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
