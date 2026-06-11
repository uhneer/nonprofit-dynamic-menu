package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundPackage;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import dev.nonprofit.modularbg.background.NonprofitMusic;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * One menu for everything you can do to a background after clicking "Edit": Customize Icons,
 * Customize Fonts, Customize Music (add/replace + remove), Export as a shareable package, and
 * Delete (confirms twice, and only when a second background exists so you're never left with none).
 */
public class BackgroundEditMenuScreen extends Screen {

    private final Screen parent;
    private final String name;          // "" = the Default (black) background
    private final boolean fileBacked;
    private int deleteStage = 0;        // 0 idle, 1 first confirm, 2 second confirm
    private ButtonWidget deleteBtn;

    public BackgroundEditMenuScreen(Screen parent, String name) {
        super(Text.literal("Edit — " + (name == null || name.isEmpty() ? "Default" : name)));
        this.parent = parent;
        this.name = name == null ? "" : name;
        this.fileBacked = !this.name.isEmpty();
    }

    private String musicKey() { return name.isEmpty() ? "default" : name; }

    private static Text layoutLabel(String bgKey) {
        String l = dev.nonprofit.modularbg.background.FontStore.layoutFor(bgKey);
        return Text.literal("Layout: " + ("center".equals(l) ? "Centered showcase" : "Left column"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2, w = 220, h = 20, gap = 22;
        int y = Math.max(40, this.height / 2 - 100);

        addDrawableChild(ButtonWidget.builder(Text.literal("Customize Icons"),
                b -> this.client.setScreen(new IconEditOverlayScreen(this)))
                .dimensions(cx - w / 2, y, w, h).build());
        y += gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Customize Fonts"),
                b -> this.client.setScreen(new FontEditOverlayScreen(this)))
                .dimensions(cx - w / 2, y, w, h).build());
        y += gap;

        // Title layout for this background: left column (classic) or centered showcase.
        String bgKey = dev.nonprofit.modularbg.background.IconStore.keyFor(name);
        addDrawableChild(ButtonWidget.builder(layoutLabel(bgKey), b -> {
            dev.nonprofit.modularbg.background.FontStore.cycleLayout(bgKey);
            b.setMessage(layoutLabel(bgKey));
        }).dimensions(cx - w / 2, y, w, h).build());
        y += gap;

        String music = NonprofitMusic.getMusicFor(musicKey());
        ButtonWidget mus = ButtonWidget.builder(Text.literal(music == null ? "♪ Add Music" : "♪ Replace Music"), b -> {
            String picked = NonprofitMusic.pickOgg();
            if (picked != null) NonprofitMusic.setMusicFor(musicKey(), picked);
            this.clearAndInit();
        }).dimensions(cx - w / 2, y, w, h).build();
        mus.setTooltip(Tooltip.of(Text.literal("Current: " + NonprofitMusic.display(music))));
        addDrawableChild(mus);
        y += gap;
        ButtonWidget rem = ButtonWidget.builder(Text.literal("Remove Music"), b -> {
            NonprofitMusic.removeMusicFor(musicKey());
            this.clearAndInit();
        }).dimensions(cx - w / 2, y, w, h).build();
        rem.active = music != null;
        addDrawableChild(rem);
        y += gap;

        if (fileBacked) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), b -> {
                String nn = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_inputBox(
                        "Rename background", "New name (the file extension is kept):",
                        name.replaceAll("\\.[^.]+$", ""));
                if (nn != null && !nn.isBlank()) {
                    NonprofitBackgrounds.renameBackground(name, nn.trim());
                    this.client.setScreen(parent);
                }
            }).dimensions(cx - w / 2, y, w, h).build());
            y += gap;
            addDrawableChild(ButtonWidget.builder(Text.literal("Export Skin"), b -> {
                String dest = BackgroundPackage.pickExportZip(name.replaceAll("\\.[^.]+$", ""));
                if (dest != null) BackgroundPackage.export(name, dest);
            }).dimensions(cx - w / 2, y, w, h).build());
            y += gap;

            boolean canDelete = NonprofitBackgrounds.available().size() >= 2;
            deleteBtn = ButtonWidget.builder(Text.literal("§cDelete Background"), b -> onDelete())
                    .dimensions(cx - w / 2, y, w, h).build();
            deleteBtn.active = canDelete;
            if (!canDelete)
                deleteBtn.setTooltip(Tooltip.of(Text.literal("Add a second background first — you can't delete your only one")));
            addDrawableChild(deleteBtn);
            y += gap;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - w / 2, y + 6, w, h).build());
    }

    private void onDelete() {
        deleteStage++;
        if (deleteStage == 1) deleteBtn.setMessage(Text.literal("§eClick again to confirm (1/2)"));
        else if (deleteStage == 2) deleteBtn.setMessage(Text.literal("§4Really delete? This is permanent (2/2)"));
        else {
            NonprofitBackgrounds.deleteBackground(name);
            this.client.setScreen(parent);   // back to the carousel, which rebuilds its list
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFFFF);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
