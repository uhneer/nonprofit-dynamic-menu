package dev.nonprofit.modularbg.background;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * "nonprofit's Backgrounds" picker. Reached from Options → Backgrounds.
 *
 * Lists the grass default plus every image in config/nonprofit-backgrounds/.
 * Selecting one applies it instantly to every menu screen (via the
 * BackgroundBuilder hook). "Open Folder" pops the folder so you can drop files
 * in; "Reload" re-scans. This screen renders over the live background itself,
 * so you see the result immediately.
 */
public class NonprofitBackgroundsScreen extends Screen {

    private final Screen parent;

    public NonprofitBackgroundsScreen(Screen parent) {
        super(Text.literal("nonprofit's Backgrounds"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = 44;
        int bottomLimit = this.height - 40;

        // Default (grass) entry — not file-backed, so no edit button
        addEntry(cx, y, NonprofitBackgrounds.DEFAULT_LABEL, "", NonprofitBackgrounds.isDefault(), false);
        y += 24;

        List<String> files = NonprofitBackgrounds.available();
        int shown = 0, hidden = 0;
        for (String name : files) {
            if (y + 20 > bottomLimit) { hidden++; continue; }
            boolean sel = !NonprofitBackgrounds.isDefault()
                       && name.equalsIgnoreCase(NonprofitBackgrounds.getSelected());
            addEntry(cx, y, name, name, sel, true);
            y += 24;
            shown++;
        }
        this.hiddenCount = hidden;

        // Bottom row: Add Background | Customize Icons | Done
        int by = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add Background"), b -> pickAndAdd())
                .dimensions(cx - 154, by, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Customize Icons"), b ->
                this.client.setScreen(new dev.nonprofit.modularbg.screen.IconEditOverlayScreen(this)))
                .dimensions(cx - 50, by, 104, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx + 58, by, 96, 20).build());
    }

    /** Native file picker → game copies the file into the folder → selects it → refresh. */
    private void pickAndAdd() {
        String chosen = NonprofitBackgrounds.openFilePicker();
        if (chosen != null) NonprofitBackgrounds.importAndSelect(chosen);
        this.clearAndInit();
    }

    private int hiddenCount = 0;

    private void addEntry(int cx, int y, String label, String value, boolean selected, boolean editable) {
        // Background select button — shortened on the right to fit the ♪ and ✎ buttons (symmetric)
        String text = (selected ? "§a✔ §r" : "") + label;
        this.addDrawableChild(ButtonWidget.builder(Text.literal(text), b -> {
            NonprofitBackgrounds.select(value);
            this.clearAndInit();
        }).dimensions(cx - 150, y, 252, 20).build());

        String mkey  = value.isEmpty() ? "default" : value;

        // ♪ menu-music button. Click → OS file dialog (.ogg). Tooltip shows the current track.
        String music = NonprofitMusic.getMusicFor(mkey);
        ButtonWidget note = ButtonWidget.builder(Text.literal("♪"), b -> {
            String picked = NonprofitMusic.pickOgg();
            if (picked != null) NonprofitMusic.setMusicFor(mkey, picked);
            this.clearAndInit();
        }).dimensions(cx + 106, y, 20, 20).build();
        note.setTooltip(Tooltip.of(Text.literal("Menu music (" + NonprofitMusic.display(music) + ")")));
        this.addDrawableChild(note);

        // ✎ edit button (rename / delete) — only on real file-backed backgrounds, not the grass default
        if (editable) {
            ButtonWidget edit = ButtonWidget.builder(Text.literal("✎"), b ->
                    this.client.setScreen(new NonprofitBackgroundEditScreen(this, value)))
                    .dimensions(cx + 130, y, 20, 20).build();
            edit.setTooltip(Tooltip.of(Text.literal("Rename or delete this background")));
            this.addDrawableChild(edit);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // draws live background + widgets
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7Drop a .png / .jpg / .gif in the folder, then Reload"),
                this.width / 2, 30, 0xFFFFFFFF);
        if (hiddenCount > 0)
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("§8+" + hiddenCount + " more (resize window to see)"),
                    this.width / 2, this.height - 38, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        NonprofitBackgrounds.reload();   // auto-reload on Done so any new file is live
        this.client.setScreen(parent);
    }
}
