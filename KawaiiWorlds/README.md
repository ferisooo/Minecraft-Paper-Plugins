# KawaiiWorlds

Multi-world manager for Paper, inspired by PocketMine's MultiWorld.
Op-only commands to create, teleport, load, unload, delete, and inspect
worlds. Random `long` seed per world. World list persists across restarts.

## Commands

All commands gated by the `kawaiiworlds.admin` permission (default `op`).
Aliases: `/kawaiiworlds`, `/kw`, `/mw`.

Running `/kw` with no arguments (as a player) opens a chest GUI:

- **Top row** — `+ Create normal/flat/void/skyblock`, `⚙ Settings`, `🗑 Delete world`, `↻ Reload`, `✕ Close`. (Nether and End spawn alongside the primary world automatically and don't need a separate create button.)
- **World icons** — left-click teleports, right-click prints info in chat, shift-click unloads (folder stays — `/kw load <name>` brings it back).
- **`+ Create <type>`** — closes the menu and prompts in chat for a name. The next thing you type is the world name; type `cancel` to abort. 30s timeout.
- **`⚙ Settings`** opens a world picker → settings panel:
  - Toggle 9 game rules (keepInventory, doDaylightCycle, doMobSpawning, doFireTick, mobGriefing, doWeatherCycle, naturalRegeneration, doMobLoot, announceAdvancements) — lime dye = on, gray dye = off.
  - Toggle PvP, cycle Difficulty (peaceful → easy → normal → hard), cycle Time (day → noon → night → midnight), cycle Weather (clear → rain → thunder), toggle auto-load, set spawn to your current location (you must be in that world).
  - **Allow fly** (cycle: server default → force on → force off) — applied on world change and join.
  - **Force gamemode** (cycle: none → survival → creative → adventure → spectator) — applied on world change and join.
  - **Default join world** — toggle this world as the first-time-joiner spawn.
  - **Game-rule preset** — cycle vanilla → creative-build → adventure → hardcore (applies a stack of rules at once).
  - **Spawn protection** — cycle off → 8 → 16 → 32 → 64 blocks (column-shaped around world spawn; ops + `kawaiiworlds.bypass-protection` ignore it).
- **`📂 Import existing`** opens a picker of world folders found in the server directory but not yet registered. Click one to load it as `normal` type.

## Per-world inventories

When `per-world-inventory: true` (default), each world (or world group) has
its own inventory, XP, health, food, and saturation. Crossing between groups
saves the source group's snapshot and loads the destination's. First time
entering a group = clean slate.

Worlds named like `X`, `X_nether`, `X_the_end` are treated as **one** group
(so vanilla portal travel inside the primary set keeps your items). Any
other world is its own isolated group. Snapshots live under
`plugins/KawaiiWorlds/playerdata/<group>/<player-uuid>.yml`.
- **`🗑 Delete world`** opens a world picker → yes/no confirm chest. Only the explicit ✓ click actually deletes.

| Command | What it does |
|---|---|
| `/kw` (no args) | Open the chest GUI (players only). Console gets text help. |
| `/kw gui` | Same as `/kw` — explicit form. |
| `/kw create <name> [type]` | Create a new world. `type` ∈ `normal, nether, end, void, flat, skyblock`. Seed is random. |
| `/kw tp <world> [player]` | Teleport you (or the named player) to a world's spawn. |
| `/kw list` | List all loaded worlds with type, environment, and player count. |
| `/kw info <world>` | Print env, seed, spawn, time, players, entities, pvp. |
| `/kw load <name>` | Load an existing world folder. |
| `/kw unload <name>` | Save & unload a world. Players get bounced to the primary world's spawn. |
| `/kw delete <name> --confirm` | Unload + permanently delete the world folder. Two-step on purpose. |
| `/kw setspawn` | Set this world's spawn to where the running player stands. |
| `/kw help` | Print the command list. |

## Generators

- **normal / nether / end** — vanilla Paper environments.
- **flat** — `WorldType.FLAT`.
- **void** — every chunk is empty air. Players fall forever unless something is built. Spawn defaults to (0.5, 64, 0.5).
- **skyblock** — empty world with a 5x5 grass / 3x3 dirt / 1x1 cobble starter island stamped at chunk (0, 0), with an oak sapling. `doMobSpawning` defaults off.

## Persistence

`plugins/KawaiiWorlds/config.yml` keeps the list. Each entry stores `type`, `seed`, and `auto-load`. The primary world from `server.properties` is **not** listed here; Paper loads it itself.

```yaml
worlds:
  myskyblock:
    type: skyblock
    seed: 5102837461928374615
    auto-load: true
```

## Build

Java 21, Maven 3.6+:

```bash
mvn clean package
```

Drop `kawaiiworlds-1.0.0.jar` into `plugins/`. Restart.

## Compatibility

- Paper 1.21.11 (api-version `1.21.11`)
- Java 21+
