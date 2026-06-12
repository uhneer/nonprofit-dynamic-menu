package dev.nonprofit.modularbg.screen;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.IconStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * Nonprofit's Dynamic Menu — original from-scratch title screen.
 *
 * Two per-background layouts (chosen in the background's edit menu, travels with exported skins):
 *   "left"   — vertically-centred left column: brand bar, PLAY, Multiplayer, Options, Mods,
 *              ✕ quit top-right, left-edge readability gradient (the classic).
 *   "center" — brand showcased large at top-center, big PLAY under it, then Multiplayer/Options
 *              stacked left of center and Mods/Quit stacked right of center, with top+bottom
 *              readability gradients.
 * Both slide-in + fade on appear. Backgrounds come from {@link BackgroundRenderer}.
 */
public class ModularTitleScreen extends Screen {

    private String layout = "left";
    private int brandX, brandY, brandW, brandH;
    private boolean brandHidden, versionHidden;
    private TitleLayout.Box versionBox;
    private float brandOffsetX = -30f, brandOpacity = 0f;

    private static int modCount = -1;
    /** Top-level mod count (excludes jar-in-jar / nested mods) — the standard "how many mods" figure. */
    private static int modCount() {
        if (modCount < 0) {
            int n = 0;
            try {
                for (var mc : FabricLoader.getInstance().getAllMods())
                    if (mc.getContainingMod().isEmpty()) n++;
            } catch (Throwable t) {
                n = FabricLoader.getInstance().getAllMods().size();
            }
            modCount = n;
        }
        return modCount;
    }

    public ModularTitleScreen() {
        super(Text.literal("Menu"));
    }

    @Override
    protected void init() {
        var c = this.client;
        layout = FontStore.layout();
        String bg = IconStore.currentBgKey();
        var boxes = TitleLayout.compute(bg, this.width, this.height);

        TitleLayout.Box vb = boxes.get("version");
        brandX = vb.x(); brandY = vb.y(); brandW = vb.w(); brandH = vb.h();
        brandHidden = FontStore.hiddenFor(bg, "version");
        versionBox = boxes.get("versiontag");
        versionHidden = FontStore.hiddenFor(bg, "versiontag");

        record Act(MenuButton.Icon icon, Runnable run) {}
        Map<String, Act> acts = Map.of(
                "play",        new Act(MenuButton.Icon.PLAY, () -> c.setScreen(new SelectWorldScreen(this))),
                "multiplayer", new Act(MenuButton.Icon.MULTIPLAYER, () -> c.setScreen(new MultiplayerScreen(this))),
                "options",     new Act(MenuButton.Icon.OPTIONS, () -> c.setScreen(new OptionsScreen(this, c.options))),
                "mods",        new Act(MenuButton.Icon.MODS, this::openMods),
                "close",       new Act(MenuButton.Icon.CLOSE, c::scheduleStop));

        for (var e : boxes.entrySet()) {
            String slot = e.getKey();
            Act act = acts.get(slot);
            if (act == null) continue;                            // version / versiontag drawn directly
            if (FontStore.hiddenFor(bg, slot)) continue;          // user hid this button
            TitleLayout.Box b = e.getValue();
            String def = TitleLayout.defaultLabel(slot, layout);
            addDrawableChild(new MenuButton(b.x(), b.y(), b.w(), b.h(),
                    () -> FontStore.labelFor(slot, def),
                    slot.equals("mods") ? () -> String.valueOf(modCount()) : null,
                    act.icon(), slot, b.iconSize(), b.fontScale(), slot,
                    act.run(), b.x() > this.width / 2));
        }
    }

    /** ModMenu's screen when installed; our built-in mod list otherwise (fully standalone). */
    private void openMods() {
        try {
            Class<?> cls = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
            this.client.setScreen((Screen) cls.getConstructor(Screen.class).newInstance(this));
        } catch (Throwable t) {
            this.client.setScreen(new SimpleModsScreen(this));
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!BackgroundRenderer.draw(ctx)) {
            ctx.fill(0, 0, this.width, this.height, 0xFF000000); // default: black
        }
        int gw = Math.max(1, this.width / 3);            // left readability gradient
        for (int x = 0; x < gw; x++) {
            int a = (int) ((1f - x / (float) gw) * 100f);
            if (a > 0) ctx.fill(x, 0, x + 1, this.height, a << 24);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta); // background + menu buttons

        brandOpacity = Math.min(brandOpacity + delta * 0.1f, 1f);
        brandOffsetX *= 0.85f;
        if (Math.abs(brandOffsetX) < 0.5f) brandOffsetX = 0f;
        drawBrand(ctx);

        // One-time MP4 bake progress, bottom-right (only visible while a new video is being prepared).
        String vs0 = dev.nonprofit.modularbg.background.NonprofitBackgrounds.videoStatus();
        if (vs0 != null)
            ctx.drawTextWithShadow(this.textRenderer, vs0,
                    this.width - this.textRenderer.getWidth(vs0) - 10, this.height - 20, 0xCCFFFFFF);

        // Minecraft version, by default bottom-left at 0.75 scale, with the per-background
        // version-tag font / size / drag position; hideable like any other element.
        if (!versionHidden && versionBox != null) {
            float vs = 0.75f * FontStore.sizeFor("versiontag");
            MutableText ver = Text.literal("Minecraft 1.21.11 © Mojang AB");
            Identifier vf = FontStore.fontFor("versiontag");
            if (vf != null) ver = ver.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(vf)));
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate((float) versionBox.x(), (float) versionBox.y());
            m.scale(vs, vs);
            ctx.drawTextWithShadow(this.textRenderer, ver, 0, 0, 0xCCFFFFFF);
            m.popMatrix();
        }
    }

    private void drawBrand(DrawContext ctx) {
        if (brandHidden) return;
        int a = (int) (brandOpacity * 255f);
        if (a <= 2) return;
        var m = ctx.getMatrices();
        boolean slid = Math.abs(brandOffsetX) > 0.5f;
        if (slid) {
            m.pushMatrix();
            m.translate(brandOffsetX, 0f);
        }

        // The brand bar: the per-background custom image if one is set, else the default version_info
        // art, drawn into its layout box (the whole brand is baked into the image), tinted by alpha.
        Identifier brand = IconStore.resolved("version");
        if (brand != null)
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, brand, brandX, brandY, 0f, 0f,
                    brandW, brandH, brandW, brandH, (a << 24) | 0xFFFFFF);
        if (slid) m.popMatrix();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
