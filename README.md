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
- Scoped building-tool access: a pot owner receives full WorldEdit and Axiom permissions only while inside their own pot; public-world authorization is untouched.
- Command blocks and command-block minecarts never execute inside pot dimensions.
- Level-4 operator controls for enable/disable, maximum radius, performance budgets, status, diagnostics, trimming, and permanent deletion.
- Pot owners can use `/fill`, `/fillbiome`, `/place`, `/setblock`, and `/summon` inside their own pot without receiving OP outside it.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Java | 25 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.158.0+26.2 |
| Fabric Language Kotlin | 1.13.12+kotlin.2.4.0 or newer |
| WorldEdit (optional) | 7.2.2 through 7.4.5 |
| Axiom (optional) | 5.0.0 through 5.5.0 |
| Worldthreader (optional) | 3.1.0 |

Arcade Dimensions `0.13.0-beta.6+26.2`, its supporting modules, and Fabric Permissions API v0 `0.7.0` are embedded in the built mod. Do not install duplicate copies or remove the embedded modules from the JAR.

Current snapshot: `1.0.0-snapshot.3-26.2`

## Installation

1. Install Minecraft 26.2 with Fabric Loader 0.19.3 or newer on the server.
2. Download `serenitea-pot-1.0.0-snapshot.3-26.2.jar` from [GitHub Releases](https://github.com/water2004/serenitea-pot/releases).
3. Download Fabric API `0.158.0+26.2` and Fabric Language Kotlin `1.13.12+kotlin.2.4.0` or newer.
4. Place all three JAR files in the server's `mods` directory and start the server with Java 25.

Serenitea Pot has no client component and needs no separate configuration file. Limits are managed through in-game level-4 operator commands. Arcade Dimensions is already nested inside the Serenitea Pot JAR and must not be installed separately.

Optionally install exactly WorldThreader 3.1.0 on the server. Other installed WorldThreader versions are rejected by Fabric Loader because this integration targets its internal threading protocol. Removing it restores Vanilla's serial dimension ticking without changing pot data or configuration.

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

Immediately before entering a pot, Vanilla saves the exact public playerdata. While the player is inside a pot, ordinary playerdata writes are suppressed and only the existing isolated pot snapshot is updated. Normal playerdata therefore remains at the last public dimension, position, and game mode without moving the live in-pot player.

Only a real owner standing inside their own pot can keep the bundle loaded. When the owner leaves or disconnects, the lifecycle transaction:

1. Blocks new admission.
2. Returns visitors and operators to their respective public locations.
3. Verifies that no player still references a custom level.
4. Saves and unloads the Overworld, Nether, and End together.

Carpet fake players, portal loaders, chunk tickets, and machines cannot keep a pot loaded. Visit grants are temporary and never become a permanent guest list.

`disable` is an operator action that closes a player's pot and prevents future admission. `freeze` only stops world ticks: the owner, approved visitors, and operators may still enter for repairs, and the owner can run `unfreeze` afterward.

While physically inside their own pot, its owner receives full WorldEdit and Axiom permissions, plus the world-local Vanilla commands `/difficulty`, `/fill`, `/fillbiome`, `/place`, `/setblock`, and `/summon`. These grants disappear immediately on leaving. Pot entry restarts Axiom's client handshake so repeated leave/enter cycles receive the current grant; WorldEdit checks the current player on every command. Permissions in public dimensions, other players' pots, and for visitors remain unchanged. Command blocks remain inert in pot dimensions even when command blocks are globally enabled.

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
/sereniteapot admin default-max-radius <chunk-radius>
/sereniteapot admin budget <player> <ms-per-tick>
/sereniteapot admin default-budget <ms-per-tick>
/sereniteapot admin global-budget <ms-per-tick>
/sereniteapot admin status <player>
/sereniteapot admin perf [player]
/sereniteapot admin delete <player> confirm
```

Each pot has one persistent difficulty shared by its Overworld, Nether, and End; it defaults to `normal`. Inside a pot, Vanilla's `/difficulty` queries and changes that pot only. In public dimensions it retains its original global behavior and authorization. The default maximum radius is 4 chunks per player and the hard maximum is 256. The default per-pot budget is 2 ms/tick; the global default is 20 ms/tick. Default commands affect player records created afterward, while `max-radius` and `budget` target one existing player.

## Performance model

Without WorldThreader, all world work remains on Minecraft's server thread. With optional WorldThreader 3.1.0 installed, pots do not create more worker threads: every pot Overworld joins the public Overworld lane, every pot Nether joins the public Nether lane, and every pot End joins the public End lane. The three lanes run in parallel, while different pots remain sequential within a lane.

Budgets reset every server tick and never accumulate debt. Loaded pots are shuffled, then whole pots run in that order until their measured current-tick cost exhausts the global budget. With WorldThreader the cost of one pot is the slowest of its three parallel dimension lanes; without it the three serial dimension costs are added. Region-copy work uses the remaining per-pot and global time in the same tick.

Extraction advances chunk by chunk. It clones chunk section palettes and separately copies block entities, block and fluid scheduled ticks, non-player entities and passenger trees, POI data, structures, lighting, and persistent Fabric chunk attachments. A slow chunk reduces later throughput rather than failing the creation plan.

A single dimension tick, synchronous chunk load, or third-party callback cannot be safely interrupted halfway through. If an already running pot records a single dimension tick of at least 200 ms, the pot is automatically marked `FROZEN`; world ticking stops from the next server tick so the owner can diagnose and repair it.

`/sereniteapot admin perf` reports states such as `RUNNING`, `COPYING`, `THROTTLED`, `FROZEN`, and `DISABLED`, together with recent copy time, average and maximum dimension tick time, executed and skipped ticks, and effective TPS.

## Mod compatibility boundaries

Serenitea Pot creates real server levels that share the server's registries and game logic. Arcade Dimensions provides the `VanillaLikeLevelsBuilder`, portal mixins, and `VanillaDimensionMapper` that keep players and other entities inside the same owner's Overworld/Nether/End bundle.

WorldThreader 3.1.0 is supported through an optional, version-locked Mixin integration. It attaches pot work to the three vanilla dimension-family threads and preserves WorldThreader's world-tick, teleport-receive, post-teleport, and recovery barriers. The integration is inactive when WorldThreader is absent.

Mods that store machine state in block entities or standard persistent Fabric chunk attachments will usually copy with the region. There is no universal and safe region meaning for the following data, so compatibility is not promised for:

- Dimension-level `SavedData`
- Cross-region networks or private third-party storage
- Custom level or player attachments
- Private payloads containing unmarked absolute public-world coordinates

All project business code is Java 25. The final JAR still depends on Fabric Language Kotlin because embedded Arcade Dimensions is written in Kotlin; the Serenitea Pot source itself does not contain Kotlin business code.

## Screenshots

### Public Overworld

<p align="center">
  <img src="docs/images/overworld.png" width="100%" alt="Players in the public Overworld">
</p>

### Extracting a region

<p align="center">
  <img src="docs/images/create.png" width="100%" alt="Public Overworld region selected for extraction">
</p>
<p align="center">
  <img src="docs/images/created.png" width="100%" alt="Serenitea Pot extraction completed">
</p>

### Entering and leaving a pot

<p align="center">
  <img src="docs/images/enter.png" width="100%" alt="Entering a Serenitea Pot from the public world">
</p>
<p align="center">
  <img src="docs/images/entered.png" width="100%" alt="The extracted region inside a Serenitea Pot's local world border">
</p>
<p align="center">
  <img src="docs/images/leave.png" width="100%" alt="Leaving a Serenitea Pot">
</p>
<p align="center">
  <img src="docs/images/left.png" width="100%" alt="Returned to the saved public-world position">
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
