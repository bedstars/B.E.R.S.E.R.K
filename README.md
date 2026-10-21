<div align="center">

```
 ____    _____  ____    ____    _____  ____    _  __
| __ )  | ____||  _ \  / ___|  | ____||  _ \  | |/ /
|  _ \  |  _|  | |_) | \___ \  |  _|  | |_) | | ' /
| |_) | | |___ |  _ <   ___) | | |___ |  _ <  | . \
|____/  |_____||_| \_\ |____/  |_____||_| \_\ |_|\_\
```

# B.E.R.S.E.R.K

### Record everything. Replay it flawlessly. Draw a route and walk it forever.

![Fabric](https://img.shields.io/badge/Loader-Fabric-dbd0b4?style=for-the-badge)
![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2%20%7C%20newer-e5243b?style=for-the-badge)
![Side](https://img.shields.io/badge/Side-Client%20only-ff8a1f?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-25-34d17a?style=for-the-badge)

</div>

---

## What is it?

**B.E.R.S.E.R.K** is a fully **client-side macro suite** for Minecraft. Hit record, play the game like you normally would,
hit stop - and the mod replays *everything*: key presses, mouse clicks, scrolling, typing, inventories, chat,
opening and closing menus, **and your camera**.

Outside macro tools fall apart in a 3D game because they replay raw mouse *movements*. B.E.R.S.E.R.K never does. It samples your
exact camera angle every tick and glides the camera through those angles on playback, so a recording made at 400 DPI
replays identically on any machine, at any FPS, any GUI scale and any window size.

> Nothing is ever sent to the server on its own. B.E.R.S.E.R.K only produces the same input you would have produced with your own hands.

Two tools in one jar: the **macro recorder** and the **Path editor** (a CAD-style route planner that walks the route with W/A/S/D only).
They share one menu, one install and one set of key binds, and they never run at the same time.

## Features

| | |
|---|---|
| **Total recording** | Keys, mouse buttons, scroll wheel, typed characters, cursor position inside menus, and menu open / close timing |
| **Camera-accurate playback** | Absolute per-tick yaw / pitch, shortest-path interpolation (no 360 degree spins), no dependence on mouse DPI or FPS |
| **5 macro slots** | Record five different macros, each saved to its own slot and kept between sessions. REC on a slot records straight into it |
| **Randomizer** | Tick any slots and turn Randomize on: every time a macro finishes, a random ticked one plays next. Off = only the selected slot runs |
| **Loops** | Endless or a fixed count, with a configurable delay between runs |
| **Failsafes** | Presses `ESC` to leave stray menus, waits for expected screens, detects setbacks, drifting and damage, retries, then gives up cleanly |
| **Stop-and-run-a-command** | After too many failed attempts the macro halts and runs a command you set in the menu (`/home`, `/spawn`, `/hub` ...) |
| **Free mouse** | Press `Left Alt` to release your cursor - real mouse input is ignored by the game so the macro keeps running while you use other windows |
| **Background run** | The game does not pause when the window loses focus during playback |
| **Cool menu** | Lunar-style card menu with animated accents, module toggles, macro manager, settings and keybinds |
| **Chat buttons** | `MENU`, `REC` and `PLAY` buttons appear whenever the chat box is open |
| **QoL modules** | Status HUD, coordinates HUD, auto sprint, and more |
| **Path editor** | Draw a route on a 1000 x 1000 block grid, 5 route slots, commands at points, closed loops, ortho mode. See *Path editor* below |
| **Fully rebindable** | Every hotkey lives in *Options > Controls* |

## Controls

| Action | Default key |
|---|---|
| Open / close the menu | `Right Shift` |
| Record / stop recording | `F7` (or the **REC** button) |
| Play / stop macro | `F8` (or the **PLAY** button) |
| Free mouse | `Left Alt` |
| Open the path editor | `K` |
| Start / stop walking the route | `J` |

All of them can be changed in *Options > Controls > B.E.R.S.E.R.K* (macros) and *B.E.R.S.E.R.K Path* (path).

## Path editor

Open it with `K` or from the **PATH** tab of the menu. Click to lay down points, watch every line show its length and
coordinates, then hit **RUN** (or press `J`). The player walks the route using only `W` `A` `S` `D` and sprint - **the camera is never touched**.

| Action | How |
|---|---|
| Add a point | `DRAW` tool (`D`), left click |
| Finish the line (set END) | Right click or `Enter` |
| Close the loop | Click the START point |
| Axis lock | `F8` (or the ORTHO button) |
| Select / move a point | `SELECT` tool (`S`), click, drag to move |
| Edit coordinates / command | Select a point, open **PROPERTIES** (`P`), click a field, type, `Enter` |
| Insert a point into a line | `SELECT` tool, `Shift` + click the line |
| Delete a point | `Del` |
| Pan / zoom | Middle mouse drag or arrow keys / mouse wheel |
| Fit route / centre on you | `F` / `C` |
| Leave | `Esc` |

Five route slots, optional commands that run when the player stands on a point, sprint and repeat toggles. At a command point the
player stops dead, runs it, waits `cmdDelay` ticks and carries on. Opening any screen pauses the walk. If START is more than
60 blocks away the run is refused. The walker does not jump, mine or avoid obstacles - draw routes through open ground.
Routes are stored in `.minecraft/config/berserk-path/`.

> `F8` is *Play / Stop* for macros and *Ortho* inside the path editor. The editor sees keys first, so there is no clash.

## How the failsafe works

```
 start run ──► replay tick by tick ──► finished ──► wait loop delay ──► start run ...
                    │
                    ├─ wrong screen for too long ─┐
                    ├─ setback / rubber-band      │
                    ├─ drifted off the path       ├─► failure ► release keys ► ESC out of menus ► retry
                    ├─ took damage (optional)     │
                    └─ not at the start spot ─────┘
                                                        │
                                   more than N failures ▼
                                      STOP  +  run your failure command
```

## Installing

1. Install **Fabric Loader** and **Fabric API** for your Minecraft version.
2. Download the jar that matches your version from the *Releases* / *Actions* tab.
3. Drop it into `.minecraft/mods`.

Macros are stored as plain JSON in `.minecraft/config/berserk/macros/`, routes in `.minecraft/config/berserk-path/`.

## Building

Requires **JDK 25**. The GitHub workflow builds every supported version automatically, or locally:

```bash
gradle build -Pminecraft_version=26.2 -Pfabric_api_version=0.155.2+26.2
```

The jar lands in `build/libs/`. To support a newer Minecraft version, add a row to the matrix in `.github/workflows/build.yml`.
Pushing a tag like `v1.0.0` publishes a GitHub release with every jar attached.

## Responsible use

Many servers forbid automation. Using macros there can get your account punished, and that is on you.
Check the rules of any server before you run a macro on it.

## License

MIT
