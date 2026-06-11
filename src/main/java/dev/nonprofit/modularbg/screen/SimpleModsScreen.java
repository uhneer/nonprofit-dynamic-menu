package dev.nonprofit.modularbg.screen;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in fallback for the Mods button when ModMenu isn't installed: a plain scrollable list of
 * loaded mods (name + version + a one-line description). Keeps the menu fully standalone.
 */
public class SimpleModsScreen extends Screen {

    private final Screen parent;
    private final List<String[]> mods = new ArrayList<>();   // [name, version, description]
    private double scroll = 0;

    public SimpleModsScreen(Screen parent) {
        super(Text.literal("Mods"));
        this.parent = parent;
        try {
            for (var mc : FabricLoader.getInstance().getAllMods()) {
                if (mc.getContainingMod().isPresent()) continue;   // skip jar-in-jar libs
                ModMetadata m = mc.getMetadata();
                String desc = m.getDescription() == null ? "" : m.getDescription().replace('\n', ' ');
                if (desc.length() > 110) desc = desc.substring(0, 107) + "...";
                mods.add(new String[]{ m.getName(), m.getVersion().getFriendlyString(), desc });
            }
            mods.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        } catch (Throwable ignored) { }
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xF00C0C10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        var tr = this.textRenderer;
        ctx.drawCenteredTextWithShadow(tr, Text.literal("Mods (" + mods.size() + ")"),
                this.width / 2, 12, 0xFFFFFFFF);

        int top = 32, bottom = this.height - 34, rowH = 22;
        int x = Math.max(12, this.width / 2 - 220), w = Math.min(this.width - 24, 440);
        ctx.enableScissor(0, top, this.width, bottom);
        int y = top - (int) scroll;
        for (String[] m : mods) {
            if (y + rowH > top && y < bottom) {
                ctx.fill(x, y, x + w, y + rowH - 2, 0x55000000);
                ctx.drawTextWithShadow(tr, Text.literal(m[0] + " §8" + m[1]), x + 6, y + 3, 0xFFFFFFFF);
                ctx.drawTextWithShadow(tr, Text.literal("§7" + m[2]), x + 6, y + 12, 0xFFAAAAAA);
            }
            y += rowH;
        }
        ctx.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int contentH = mods.size() * 22, viewH = this.height - 66;
        scroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), scroll - vertical * 24));
        return true;
    }

    @Override
    public void close() { this.client.setScreen(parent); }
}
