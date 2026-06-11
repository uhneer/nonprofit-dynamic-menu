package dev.nonprofit.modularbg.background;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Small editor for a single custom background: rename, delete, or remove its music.
 * Reached from the ✎ button next to a background in {@link NonprofitBackgroundsScreen}.
 */
public class NonprofitBackgroundEditScreen extends Screen {

    private final Screen parent;
    private final String name;          // full file name (with extension)
    private TextFieldWidget field;

    public NonprofitBackgroundEditScreen(Screen parent, String name) {
        super(Text.literal("Edit Background"));
        this.parent = parent;
        this.name   = name;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        field = new TextFieldWidget(this.textRenderer, cx - 150, 64, 300, 20, Text.literal("name"));
        field.setMaxLength(128);
        field.setText(baseName(name));
        this.addDrawableChild(field);
        this.setInitialFocus(field);

        int y = 100;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), b -> doRename())
                .dimensions(cx - 150, y, 147, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("§cDelete Background"), b -> {
            NonprofitBackgrounds.deleteBackground(name);
            this.close();
        }).dimensions(cx + 3, y, 147, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove Music From This Background"), b -> {
            NonprofitMusic.removeMusicFor(name);
            this.close();
        }).dimensions(cx - 150, y + 24, 300, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.close())
                .dimensions(cx - 150, y + 52, 300, 20).build());
    }

    private void doRename() {
        String nn = field.getText().trim();
        if (!nn.isEmpty()) NonprofitBackgrounds.renameBackground(name, nn);
        this.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 30, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7Editing: " + name), this.width / 2, 50, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private static String baseName(String fn) {
        int dot = fn.lastIndexOf('.');
        return dot > 0 ? fn.substring(0, dot) : fn;
    }
}
