package dev.nonprofit.modularbg;

import dev.nonprofit.modularbg.background.FontStore;
import dev.nonprofit.modularbg.background.IconStore;
import dev.nonprofit.modularbg.background.NonprofitBackgrounds;
import dev.nonprofit.modularbg.background.NonprofitMusic;
import dev.nonprofit.modularbg.screen.BackgroundCarouselScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nonprofit's Dynamic Menu — entry point.
 *
 * An original, standalone menu mod: its own custom title screen
 * ({@link dev.nonprofit.modularbg.screen.ModularTitleScreen}), a per-background image/GIF/MP4
 * engine, custom fonts and icons per background, menu music, and transparent widget styling.
 */
public final class ModularBackgrounds implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ndm");

    @Override
    public void onInitializeClient() {
        NonprofitBackgrounds.init();
        IconStore.init();
        FontStore.init();
        NonprofitMusic.init();

        // Options → Backgrounds button → the carousel (preview/select/import + icons/fonts/music).
        ScreenEvents.AFTER_INIT.register((c, screen, sw, sh) -> {
            if (!(screen instanceof OptionsScreen)) return;
            var buttons = Screens.getButtons(screen);
            for (var w : buttons) if ("Backgrounds".equals(w.getMessage().getString())) return;
            buttons.add(ButtonWidget.builder(
                            Text.literal("Backgrounds"),
                            b -> c.setScreen(new BackgroundCarouselScreen(screen)))
                    .dimensions(6, sh - 26, 120, 20).build());
        });

        // Looping per-background OGG playback (runs in menus too) + stop video once in-world.
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            NonprofitMusic.tick(c);
            NonprofitBackgrounds.lifecycleTick();
        });

        LOGGER.info("Nonprofit's Dynamic Menu ready — custom title screen active.");
    }
}
