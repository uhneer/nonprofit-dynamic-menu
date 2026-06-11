package dev.nonprofit.modularbg.mixin;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla panorama with the selected custom background on the main-menu screens that
 * use the DEFAULT Screen.renderBackground — world select, multiplayer, options, mods,
 * world-creation ProgressScreen, etc. (Our own title screen draws its background directly.)
 *
 * Screens that OVERRIDE renderBackground (MessageScreen, LevelLoadingScreen) are handled separately
 * by {@link MixinLoadingScreens}.
 *
 * Guard: client.world == null (menu context). Fully guarded — if drawing fails, the vanilla
 * background is left intact.
 */
@Environment(EnvType.CLIENT)
@Mixin(Screen.class)
public class MixinSelectWorldScreen {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void drawCustomBackground(DrawContext context, int mouseX, int mouseY,
                                      float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null) return;
        if (BackgroundRenderer.drawMenu(context)) ci.cancel();   // blur + light darken on non-home menus
    }
}
