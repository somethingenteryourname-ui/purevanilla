# PureVanilla (Fabric, Minecraft 1.21.11)

One key switches between your exact PvP/Performance settings and normal vanilla visuals.
Press it again and everything goes back exactly as it was. No loading screens, no world reload.

## Install

1. Build the jar (see below) or download it from the GitHub Actions run.
2. Put `purevanilla-1.0.0.jar` in your `.minecraft/mods` folder (same folder as Sodium / Sodium Extra).
3. Requires Fabric Loader 0.17+ and Fabric API. Sodium and Sodium Extra are optional.
4. Launch. Your current settings are captured as your Performance profile.

## Build the jar with GitHub (no setup on your PC)

1. Create a new GitHub repo (e.g. `purevanilla`) and upload everything in this folder, including the hidden `.github` folder.
2. Open the **Actions** tab → **Build PureVanilla** → the latest run.
3. Download the **PureVanilla-jar** artifact at the bottom, unzip it, and use `purevanilla-1.0.0.jar` (not the `-sources` jar).

Local build instead: install Java 21, then run `gradlew build` in this folder. The jar lands in `build/libs/`.

## Using it

| Action | How |
|---|---|
| Toggle | **O** by default (P is vanilla's Social Interactions key). Rebind in Options → Controls → Key Binds → PureVanilla → *Toggle PureVanilla Mode* |
| Toggle by command | `/purevanilla` |
| See current mode and what is switched | `/purevanilla status` |
| Re-read `config/purevanilla/purevanilla.json` | `/purevanilla reload` |

Holding the key only switches once. A small action-bar message shows *PureVanilla Enabled* / *Performance Mode Enabled*.

## What gets switched

### Minecraft video options
| Setting | PureVanilla Mode | Applies |
|---|---|---|
| Clouds | Fancy | instantly |
| Smooth Lighting | On | terrain re-meshes in the background (see below) |
| Entity Shadows | On | instantly |
| Vignette | On | instantly |
| Particles | All | instantly |

Never touched: resolution, GUI scale, render distance, simulation distance, FPS limit, graphics preset,
Improved Transparency, Cutout Leaves, weather radius, chunk fade, cloud distance, biome blend, FOV,
and every non-visual option.

### Sodium Extra (if installed)
All of these are changed in memory and take effect instantly:

- **Animations:** master switch, water, lava, fire, portal, block animations, sculk sensor
- **Particles:** master switch, rain splash, block break, block breaking, and the whole per-particle list (explosions, crits, totem, potion/effect, enchant, etc.) all enabled
- **Details:** sky, sun, moon, stars, rain & snow, sky colors, biome colors (biome colors re-mesh terrain like smooth lighting)
- **Render:** light updates, item frames, armor stands, paintings, piston animation, beacon beam (height limit off), enchanting table book, item frame and player name tags
- **Extras:** cloud height override off, instant sneak off (vanilla sneak animation), panini projection off

Not touched: fog, toasts, FPS/coords overlay, shader prevention, and everything in Sodium's own settings (those are performance options, not visuals).
Any setting your Sodium Extra version doesn't have is skipped and listed in the log.

### Sodium
Sodium itself has no visual options left to switch in 1.21.11 (its video options use Minecraft's, covered above), so nothing in Sodium's config is touched.

## Limitations (being straight with you)

- **Totem size / totem pop size is not switched yet.** Minecraft has no totem-size option; it comes from whatever mod or resource pack you use for the small totem. Tell me which one and it can be wired in (an adapter is one small class). Resource packs can't be switched without a resource reload (that's a loading screen), so a mod is the instant option.
- **Smooth Lighting and Biome Colors** are baked into terrain. PureVanilla does NOT use Minecraft's normal renderer reload (that blanks the world). It marks loaded chunks for rebuild, so the old terrain keeps showing and the new lighting sweeps in over a moment. If you want every switch to be 100% instant, set `"switchSmoothLighting": false` in `config/purevanilla/purevanilla.json` and run `/purevanilla reload`.
- The key is read once per game tick, so a switch lands within 50 ms of the key press.
- If you change one of the switched settings by hand **while PureVanilla Mode is on**, switching back still restores your Performance values. Change your Performance settings while in Performance Mode; they're re-captured every time you switch.

## Safety and crash recovery

- Your settings files on disk always contain your **Performance** values. PureVanilla Mode only exists in memory.
- Normal quit while PureVanilla Mode is on: Performance values are saved first, and PureVanilla Mode is switched back on at next launch.
- Crash while PureVanilla Mode is on: next launch restores your Performance settings from `config/purevanilla/state.json` and tells you in chat.
- First launch writes `config/purevanilla/performance-backup-first-run.json`, a copy of your original settings.
- If switching fails partway, everything already changed is rolled back and you stay in Performance Mode.
- All files are written atomically (temp file + rename), so a crash mid-save can't corrupt them. PureVanilla never deletes or resets your configs.

## Files

```
config/purevanilla/purevanilla.json                 what to switch (editable)
config/purevanilla/state.json                       current mode + saved Performance profile
config/purevanilla/performance-backup-first-run.json
```

`purevanilla.json` defaults:
```json
{
  "showNotification": true,
  "switchSmoothLighting": true,
  "sodiumExtra": { "animations": true, "particles": true, "details": true, "render": true, "extras": true }
}
```

## How it stays light

- One accessor mixin (no per-frame hooks, no rendering injections).
- No polling: work only happens when you press the key.
- One idle background thread writes `state.json` so the frame you press the key on never waits on disk.
- Sodium/Sodium Extra are not compile dependencies, so a missing or updated mod can't crash the game.

## Test checklist

1. PureVanilla appears in the mod list (Mod Menu if installed).
2. Controls → Key Binds shows *Toggle PureVanilla Mode* under PureVanilla.
3. Press the key in a world: clouds, shadows, vignette, particles, animations come on.
4. Press again: your exact Performance settings are back (`/purevanilla status`, video settings, Sodium Extra screen).
5. Toggle 10+ times while moving, fighting and around explosions; settings stay consistent.
6. Quit with PureVanilla on and relaunch: it comes back on and your options.txt still holds Performance values.
7. Check `logs/latest.log` for lines starting with `[PureVanilla]`.
