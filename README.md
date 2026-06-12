# Nonprofit's Dynamic Menu (nDM)

A complete menu overhaul for Fabric 1.21.11 that replaces the title screen with something you actually design yourself. Every background is a full skin: its own image, GIF, or MP4 video, its own fonts, its own button icons, its own brand art, its own music, and its own layout. All of it is editable in game and all of it ships in one jar with zero dependency mods.

Out of the box you get an animated grass field video with the nDM brand bar. From there, everything is yours to change.

## What it does

- **Custom title screen**, built from scratch. Two base layouts per background (left column or centered showcase), and on top of that you can drag any element anywhere you want, with snap-to-grid. Rename any button, hide any button, resize any text or icon. Slide-in animations, hover highlights, readability gradients.
- **Edit everything in place.** Click Edit in the carousel and you are standing on a live preview of that skin: drag things to move them, click an icon to swap it, click a text to change its font. No settings tree to dig through.
- **A built-in font database and icon database.** Browse the full Google Fonts catalog (1,800+ families, all open source) with live previews rendered in each actual font, and 15,000+ open source icons (Tabler, Lucide, Material Design Icons) served from nDM's own free CDN. One click imports and assigns. No accounts, no API keys. Attribution shows as a hover tooltip on the slot.
- **MP4 video backgrounds with a built-in video engine.** This is the headline feature. nDM ships its own pure-Java video pipeline: the clip is decoded once into a frame cache, then playback derives the frame to show from a monotonic clock, which makes looping seamless and the speed exact by construction. Full source resolution, no downscaling. If the MP4 has an audio track, it gets extracted and looped automatically, respecting your music volume slider. No ffmpeg binaries, no WaterMedia, no native libraries.
- **A built-in font engine.** Drop in any .ttf or .otf and nDM rasterizes it itself into a high-resolution glyph atlas, normalized so your font renders at the same visual size as Minecraft's own. Per-slot font assignment (PLAY, Multiplayer, Options, Mods, version tag) with a size nudge on every slot. OpenType/CFF fonts work, which vanilla's own font loader rejects.
- **Per-background icons and brand art.** Every button icon and the brand bar can be swapped per background, with a click-to-edit overlay that shows the real title screen.
- **Menu music.** Assign a looping track to any background — OGG, WAV, or the audio ripped straight out of any MP4/M4A (pick the video file itself and nDM extracts the AAC once). The MP4's own audio plays when no track is assigned.
- **Import backgrounds from YouTube.** Paste a link and nDM downloads it as an MP4 background (up to 10 minutes, up to 1440p), free and key-less via yt-dlp's official binaries, fetched once on first use.
- **The Skin Hub.** Share skins as 8-character codes, browse community uploads with favorites and categories, and submit your own for review — all without an account. (Backend scaffolded; launching soon.)
- **Skins.** Export any background with all its icons, fonts (the actual font files), music, labels, positions, and layout as one zip. Import someone else's with one click. The carousel previews each skin as a full miniature title screen, not just a wallpaper thumbnail.
- **Shuffle mode.** One toggle and you get a different background every launch.
- **A real mods screen built in.** Searchable, filterable mod list with icons, descriptions, and enable/disable per mod, even without ModMenu installed.
- **Whole-menu theming.** Translucent buttons and sliders, your background (blurred and dimmed for readability) behind the options screen, world selection, world creation, and loading screens. The menus feel like one continuous place instead of a skin taped onto the front door.
- **GIF and APNG-style animation** for backgrounds, with focus throttling: when the window loses focus, animation and audio pause to zero cost.

## Why this instead of FancyMenu, NekoUI, or HorizonUI

These are good projects and credit where due, FancyMenu in particular is a powerful general-purpose tool. But they solve a different problem than nDM does.

**FancyMenu** is a menu construction kit: a visual editor, conditional logic, placeholders, and support for animated GIF and APNG images. It is open source (DSMSL v3). It is also a big system to learn, and its video playback story depends on the separate WaterMedia ecosystem with large native ffmpeg binaries. nDM is the opposite trade: no editor to learn, one opinionated title screen, and video that just works out of one jar because the engine is built in.

**NekoUI** restyled the main menu and HUD with customizable backgrounds, but it is All Rights Reserved, has been discontinued, and its own page points users to its successor. Animated backgrounds were a separate download.

**HorizonUI** (NekoUI's successor) is a polished modern UI with in-game configuration, but it is also All Rights Reserved and not open source, animated backgrounds are distributed separately as resource packs, it targets 1.21 to 1.21.1, and it leans on other mods for font customization.

nDM's position: MIT licensed and fully open, one self-contained jar, native MP4 backgrounds with audio, its own font rasterizer, per-background skins you can export and share, and two title layouts. If you want a general menu scripting toolkit, use FancyMenu. If you want a finished, fast, fully-yours main menu, this is it.

## Install

1. Fabric Loader 0.15+ with Fabric API on Minecraft 1.21.11.
2. Drop `nDM-1.2.0+1.21.11.jar` into your mods folder.
3. Launch. The default grass setup appears on the title screen. Open Options, then the Backgrounds button (bottom left) to start customizing.

Adding a background: click the + slot in the carousel and pick a .png, .jpg, .gif, or .mp4. MP4s do a one-time "bake" with a progress readout, then play instantly forever after (the cache lives next to your backgrounds in `config/nonprofit-backgrounds/.video-cache`).

## Honest notes

- This mod was vibecoded: it was built iteratively with an AI coding assistant, with a human directing, testing, and shipping every build. Read the source with that in mind. It is MIT licensed, so do whatever the license allows, including laughing at the code.
- Client-side only.
- The bundled JCodec (pure-Java H.264/AAC) does the one-time MP4 decode. Very long or very high-bitrate clips mean a longer first bake and a larger frame cache on disk.
- One mixin replaces the title screen and two restyle menu and loading backgrounds. Mods that also replace the title screen will fight with it; pick one.

## Building

```
./gradlew build
```

Java 21, Fabric Loom. The jar lands in `build/libs`.

## License

MIT. See [LICENSE](LICENSE).
