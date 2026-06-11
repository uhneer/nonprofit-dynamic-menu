package dev.nonprofit.modularbg.mixin;

import dev.nonprofit.modularbg.screen.ModularTitleScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the vanilla title screen for our own. Only the exact vanilla {@link TitleScreen} is
 * replaced (not subclasses or other mods' menus), so it's a clean, self-contained takeover.
 * Re-entrant-safe: the replacement screen isn't a vanilla TitleScreen, so it passes straight through.
 */
@Mixin(MinecraftClient.class)
public class MixinTitleScreenReplace {

    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void modularbg$replaceTitle(Screen screen, CallbackInfo ci) {
        if (screen != null && screen.getClass() == TitleScreen.class) {
            ((MinecraftClient) (Object) this).setScreen(new ModularTitleScreen());
            ci.cancel();
        }
    }
}
