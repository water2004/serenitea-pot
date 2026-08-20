<p align="center">
  <img src="src/main/resources/assets/serenitea_pot/icon.png" width="128" alt="Serenitea Pot icon">
</p>

<h1 align="center">Serenitea Pot</h1>

<p align="center">
  Personal, isolated three-dimension worlds for Minecraft 26.2 Fabric servers.
</p>

<p align="center">
  <strong>English</strong> | <a href="README.zh-CN.md">简体中文</a>
</p>

> [!WARNING]
> Serenitea Pot is under active development and has not reached a stable release. Data formats and behavior may still change. Do not use it on a production world unless you can tolerate data loss.

Serenitea Pot lets each player extract a full-height chunk region from the public server world into a personal Overworld, Nether, and End. Pot worlds use the server's existing registries, mods, and game logic while keeping their world data and player state isolated. Players are always in Creative mode inside a pot, and the complete three-dimension bundle unloads as soon as its owner leaves.

The mod is server-side only. Clients joining the server do not need Serenitea Pot installed; other server mods may still have their own client requirements.

## Features

- One personal Serenitea Pot per player, containing an isolated Overworld, Nether, and End.
- Full-height extraction aligned to chunk boundaries, using a chunk radius rather than a block or cube radius.
- A local coordinate system: the source center chunk becomes chunk `(0, 0)` inside the pot.
- Per-dimension replacement: creating from the public Overworld, Nether, or End replaces only the matching pot dimension.
- Separate public and pot inventories, Ender Chest, experience, health, effects, abilities, game mode, respawn data, death location, dimension, position, and rotation.
- Immediate owner-driven lifecycle: when the owner leaves or disconnects, all occupants are evacuated and all three dimensions unload.
- Temporary 60-second visit requests with clickable accept and deny actions. Level-4 operators bypass approval, but the owner must still be present.
- Per-owner and global performance budgets shared by dimension ticks and region-copy work.
- Automatic freezing after a dangerous tick instead of deleting or disabling the pot, allowing the owner to enter and repair it.
- Level-4 operator controls for enable/disable, maximum radius, performance budgets, status, diagnostics, trimming, and permanent deletion.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.158.0+26.2 |
| Fabric Language Kotlin | 1.13.12+kotlin.2.4.0 or newer |

Arcade Dimensions `0.13.0-beta.6+26.2` and its supporting modules are embedded in the built mod. Do not install a second copy or remove the embedded Arcade modules from the JAR.

## Installation

There is no stable release yet. Build the current source with Java 25:

```powershell
.\gradlew.bat build
```

Copy these files into the server's `mods` directory:

- `build/libs/serenitea-pot-0.1.0.jar`
- Fabric API
- Fabric Language Kotlin

Then start a Minecraft 26.2 Fabric server with Java 25. No client installation or separate configuration file is required; limits are managed through in-game operator commands.

## How extraction works

`/sereniteapot create <radius>` extracts the complete build height of the player's current dimension around the current chunk. The radius is measured in chunks:

| Radius | Extracted columns |
| ---: | ---: |
| 0 | 1 × 1 chunks |
| 1 | 3 × 3 chunks |
| 2 | 5 × 5 chunks |

The width is always `2 × radius + 1`. The Overworld range is `[-64, 320)` (block Y values -64 through 319); the Nether and End use their own complete build heights.

The source center chunk maps to pot chunk `(0, 0)`, so a pot does not inherit the public world's absolute X/Z coordinates. The world border is geometrically centered at `(8, 8)` to contain the entire center chunk. Portal coordinates are consequently resolved within the same owner's local three-dimension bundle.

Creation uses a staged generation. The active generation changes only after the replacement has been completely saved; the superseded generation is then permanently deleted. Reducing an owner's maximum radius uses the same staged transaction to trim every existing pot dimension beyond the new radius. The mod does not retain implicit backups.

## Player state and lifecycle

Crossing between the public realm and a pot first captures the source state, then applies the destination state. Leaving returns the player to the exact public dimension, position, and rotation from which they entered. Entering again returns them to their last valid position inside that pot.

Disconnecting inside a pot follows the same leave transaction before Vanilla saves player data. The pot position remains in the pot snapshot, while the public dimension and position are written to normal playerdata for the next login.

Only a real owner standing inside their own pot can keep the bundle loaded. When the owner leaves or disconnects, the lifecycle transaction:

1. Blocks new admission.
2. Returns visitors and operators to their respective public locations.
3. Verifies that no player still references a custom level.
4. Saves and unloads the Overworld, Nether, and End together.

Carpet fake players, portal loaders, chunk tickets, and machines cannot keep a pot loaded. Visit grants are temporary and never become a permanent guest list.

`disable` is an operator action that closes a player's pot and prevents future admission. `freeze` only stops world ticks: the owner, approved visitors, and operators may still enter for repairs, and the owner can run `unfreeze` afterward.

## Player commands

```text
/sereniteapot                         Show your pot status
/sereniteapot create <radius>         Extract the current dimension using a chunk radius
/sereniteapot enter                   Enter your own pot
/sereniteapot leave                   Leave the current pot
/sereniteapot unfreeze                Resume ticks after repairing your frozen pot
/sereniteapot request <owner>         Request temporary entry to another player's pot
/sereniteapot requests                List pending requests received by you
/sereniteapot approve <player>        Approve a request and teleport the requester
/sereniteapot deny <player>           Deny a request
/sereniteapot delete confirm          Permanently delete your pot
```

Level-4 operators may also use `/sereniteapot enter <owner>` to enter a currently loaded pot without requesting permission.

## Level-4 operator commands

```text
/sereniteapot admin enable <player>
/sereniteapot admin disable <player>
/sereniteapot admin max-radius <player> <chunk-radius>
/sereniteapot admin budget <player> <ms-per-second>
/sereniteapot admin global-budget <ms-per-second>
/sereniteapot admin status <player>
/sereniteapot admin perf [player]
/sereniteapot admin delete <player> confirm
```

The default maximum radius is 4 chunks per player. The hard maximum is 256.

## Performance model

All world work remains on Minecraft's server thread. Serenitea Pot does not move thread-unsafe world or mod code to background threads.

Each owner's three dimensions and active region-copy task share a millisecond-per-second token bucket. All pots also share a global bucket. When a budget is exhausted, complete `ServerLevel.tick` calls are skipped or creation work is deferred. Overspending creates token debt that throttles later work.

Extraction advances chunk by chunk. It clones chunk section palettes and separately copies block entities, block and fluid scheduled ticks, non-player entities and passenger trees, POI data, structures, lighting, and persistent Fabric chunk attachments. A slow chunk reduces later throughput rather than failing the creation plan.

A single dimension tick, synchronous chunk load, or third-party callback cannot be safely interrupted halfway through. If an already running pot records a single dimension tick of at least 200 ms, the pot is automatically marked `FROZEN`; world ticking stops from the next server tick so the owner can diagnose and repair it.

`/sereniteapot admin perf` reports states such as `RUNNING`, `COPYING`, `THROTTLED`, `FROZEN`, and `DISABLED`, together with recent copy time, average and maximum dimension tick time, executed and skipped ticks, and effective TPS.

## Mod compatibility boundaries

Serenitea Pot creates real server levels that share the server's registries and game logic. Arcade Dimensions provides the `VanillaLikeLevelsBuilder`, portal mixins, and `VanillaDimensionMapper` that keep players and other entities inside the same owner's Overworld/Nether/End bundle.

Mods that store machine state in block entities or standard persistent Fabric chunk attachments will usually copy with the region. There is no universal and safe region meaning for the following data, so compatibility is not promised for:

- Dimension-level `SavedData`
- Cross-region networks or private third-party storage
- Custom level or player attachments
- Private payloads containing unmarked absolute public-world coordinates

All project business code is Java 25. The final JAR still depends on Fabric Language Kotlin because embedded Arcade Dimensions is written in Kotlin; the Serenitea Pot source itself does not contain Kotlin business code.

## Screenshots

### Extracting a region

<p align="center">
  <img src="docs/images/create.png" width="49%" alt="Public Overworld region selected for extraction">
  <img src="docs/images/created.png" width="49%" alt="Serenitea Pot extraction completed">
</p>

### Public Overworld

<p align="center">
  <img src="docs/images/overworld.png" width="100%" alt="Players in the public Overworld">
</p>

## Development and verification

Open the repository root directly in IntelliJ IDEA as a Gradle project, or run:

```powershell
.\gradlew.bat check
```

`check` runs the JUnit suite and then starts a real Minecraft 26.2 GameTest server with Fabric, Arcade, every project mixin, and Serenitea Pot loaded. To run only the Minecraft integration tests:

```powershell
.\gradlew.bat runGameTest
```

Project layout:

```text
src/main/java       Mod implementation
src/main/resources  Fabric metadata, mixin config, translations, and icon
src/test/java       Pure Java/JUnit tests
src/gametest        Minecraft GameTests
```

Server data is stored below the active world's `serenitea_pot/` and `dimensions/serenitea_pot/` directories. Deletion commands are permanent and do not create backups.
