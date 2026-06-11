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

    // Left layout geometry.
    private static final int LEFT = 20, COL_W = 120, GAP = 4;
    private static final int H_VER = 50, H_PLAY = 45, H_ROW = 20;

    private String layout = "left";
    private int brandX, brandY, brandW, brandH;
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
        layout = FontStore.layout();
        if ("center".equals(layout)) initCenter();
        else initLeft();
    }

    private void initLeft() {
        var c = this.client;
        int total = H_VER + H_PLAY + H_ROW * 3 + GAP * 4;
        int top = (this.height - total) / 2;

        brandX = LEFT;
        brandY = top;
        brandW = COL_W;
        brandH = H_VER;
        int yPlay = top + H_VER + GAP;
        int yMp   = yPlay + H_PLAY + GAP;
        int yOpt  = yMp + H_ROW + GAP;
        int yMods = yOpt + H_ROW + GAP;

        addDrawableChild(new MenuButton(LEFT, yPlay, COL_W, H_PLAY,
                () -> "PLAY", null, MenuButton.Icon.PLAY, "play", 35, 1.8f, "play",
                () -> c.setScreen(new SelectWorldScreen(this)), false));
        addDrawableChild(new MenuButton(LEFT, yMp, COL_W, H_ROW,
                () -> "Multiplayer", null, MenuButton.Icon.MULTIPLAYER, "multiplayer", 12, 1.0f, "multiplayer",
                () -> c.setScreen(new MultiplayerScreen(this)), false));
        addDrawableChild(new MenuButton(LEFT, yOpt, COL_W, H_ROW,
                () -> "Options", null, MenuButton.Icon.OPTIONS, "options", 12, 1.0f, "options",
                () -> c.setScreen(new OptionsScreen(this, c.options)), false));
        addDrawableChild(new MenuButton(LEFT, yMods, COL_W, H_ROW,
                () -> "Mods", () -> String.valueOf(modCount()),
                MenuButton.Icon.MODS, "mods", 12, 1.0f, "mods", this::openMods, false));

        addDrawableChild(new MenuButton(this.width - 24, 8, 16, 16,
                () -> "", null, MenuButton.Icon.CLOSE, "close", 16, 1.0f, null, c::scheduleStop, true));
    }

    private void initCenter() {
        var c = this.client;
        // Showcase the brand larger in this layout (same 12:5 aspect as the brand art).
        brandW = 192;
        brandH = 80;
        int playW = 150, playH = H_PLAY, colW = 110, colGap = 12;
        int total = brandH + 12 + playH + 10 + H_ROW * 2 + GAP;
        int top = Math.max(16, (this.height - total) / 2 - 10);
        int cx = this.width / 2;

        brandX = cx - brandW / 2;
        brandY = top;
        int yPlay = top + brandH + 12;
        int yGrid = yPlay + playH + 10;

        addDrawableChild(new MenuButton(cx - playW / 2, yPlay, playW, playH,
                () -> "PLAY", null, MenuButton.Icon.PLAY, "play", 35, 1.8f, "play",
                () -> c.setScreen(new SelectWorldScreen(this)), false));

        int lx = cx - colGap / 2 - colW, rx = cx + colGap / 2;
        addDrawableChild(new MenuButton(lx, yGrid, colW, H_ROW,
                () -> "Multiplayer", null, MenuButton.Icon.MULTIPLAYER, "multiplayer", 12, 1.0f, "multiplayer",
                () -> c.setScreen(new MultiplayerScreen(this)), false));
        addDrawableChild(new MenuButton(lx, yGrid + H_ROW + GAP, colW, H_ROW,
                () -> "Options", null, MenuButton.Icon.OPTIONS, "options", 12, 1.0f, "options",
                () -> c.setScreen(new OptionsScreen(this, c.options)), false));
        addDrawableChild(new MenuButton(rx, yGrid, colW, H_ROW,
                () -> "Mods", () -> String.valueOf(modCount()),
                MenuButton.Icon.MODS, "mods", 12, 1.0f, "mods", this::openMods, true));
        addDrawableChild(new MenuButton(rx, yGrid + H_ROW + GAP, colW, H_ROW,
                () -> "Quit", null, MenuButton.Icon.CLOSE, "close", 12, 1.0f, "mods",
                c::scheduleStop, true));
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
        if ("center".equals(layout)) {
            // Top + bottom readability shadows for the centered layout.
            int th = Math.max(1, this.height / 4);
            for (int y = 0; y < th; y++) {
                int a = (int) ((1f - y / (float) th) * 95f);
                if (a > 0) ctx.fill(0, y, this.width, y + 1, a << 24);
            }
            int bh = Math.max(1, this.height / 3);
            for (int y = 0; y < bh; y++) {
                int a = (int) ((y / (float) bh) * 110f);
                if (a > 0) ctx.fill(0, this.height - bh + y, this.width, this.height - bh + y + 1, a << 24);
            }
        } else {
            int gw = Math.max(1, this.width / 3);            // left readability gradient
            for (int x = 0; x < gw; x++) {
                int a = (int) ((1f - x / (float) gw) * 100f);
                if (a > 0) ctx.fill(x, 0, x + 1, this.height, a << 24);
            }
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

        // Minecraft version, bottom-left, 0.75 scale, with the per-background version-tag font.
        float vs = 0.75f * FontStore.sizeFor("versiontag");
        MutableText ver = Text.literal("Minecraft 1.21.11 © Mojang AB");
        Identifier vf = FontStore.fontFor("versiontag");
        if (vf != null) ver = ver.setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(vf)));
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(vs, vs);
        ctx.drawTextWithShadow(this.textRenderer, ver,
                (int) (10 / vs), (int) ((this.height - 10) / vs), 0xCCFFFFFF);
        m.popMatrix();
    }

    private void drawBrand(DrawContext ctx) {
        int a = (int) (brandOpacity * 255f);
        if (a <= 2) return;
        var m = ctx.getMatrices();
        boolean slid = Math.abs(brandOffsetX) > 0.5f;
        if (slid) {
            m.pushMatrix();
            // The centered layout slides the brand down from above instead of in from the left.
            if ("center".equals(layout)) m.translate(0f, brandOffsetX);
            else m.translate(brandOffsetX, 0f);
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
