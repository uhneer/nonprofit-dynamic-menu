package dev.nonprofit.modularbg.mixin;

import dev.nonprofit.modularbg.background.BackgroundRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 2 — puts the selected background (grass / custom) behind the loading screens,
 * replacing the opaque panorama+blur+darkening:
 *
 *   • MessageScreen       — generic "Saving world…" etc. AND FastQuit's WaitingScreen
 *                           (it extends MessageScreen), so the FastQuit banner is covered.
 *   • LevelLoadingScreen  — the "Loading terrain" chunk-map / progress screen.
 *
 * Both override renderBackground, so they need their own hook (the default-Screen hook in
 * MixinSelectWorldScreen does not reach them). Cancelling skips the dark panorama/blur so
 * the background shows crisp, like the menus. Fully guarded, require=0 — if the draw fails,
 * vanilla rendering is left intact.
 */
@Environment(EnvType.CLIENT)
@Mixin({MessageScreen.class, LevelLoadingScreen.class})
public class MixinLoadingScreens {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void nonprofit$customLoadingBackground(DrawContext context, int mouseX, int mouseY,
                                                   float delta, CallbackInfo ci) {
        if (BackgroundRenderer.drawMenu(context)) ci.cancel();   // blur + light darken, like the other menus
    }
}
