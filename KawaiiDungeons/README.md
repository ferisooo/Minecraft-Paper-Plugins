# KawaiiDungeons

Instanced party dungeons for Paper/Bukkit **1.21** (Java 21). Each run **clones a
per-dungeon template world** into a fresh folder so any number of parties can run
the same dungeon simultaneously, fully isolated. On completion / failure / timeout
/ empty, the instance world is unloaded and its folder deleted.

Part of the `com.ferisooo` Kawaii plugin suite. Author: **Ferris**.

---

## ⚠️ You MUST provide template worlds

Every dungeon in `dungeons.yml` references a `template-world`. **An admin must
create that world and place its folder in the server root** (the Bukkit world
container, next to `world/`).

The shipped example dungeon (`crypt`) references a template world named
**`DUNGEON_CRYPT`**. To use it:

1. Create/build a world named `DUNGEON_CRYPT` (any way you like — a flat world,
   a custom build, etc.). Build the arena: a spawn area, mob areas, and a boss
   room roughly matching the coordinates in the `crypt` entry of `dungeons.yml`.
2. Make sure the folder `DUNGEON_CRYPT/` (containing `level.dat`) sits in the
   server root alongside `world/`.
3. The plugin never modifies the template — it copies it per run into
   `kawaii_dgn_crypt_<runid>` and deletes that copy when the run ends.

If the template is missing, `/dg start` reports the problem and does nothing.

> The clone walk **skips** `session.lock`, `uid.dat`, and `*.lock` so the live
> template can be cloned safely while loaded. Each clone is loaded on the main
> thread via `WorldCreator` after an async file copy.

---

## Build

```
mvn package
```

Produces `target/KawaiiDungeons-1.0.0.jar`. Drop it in `plugins/`.
(`paper-api:1.21.11-R0.1-SNAPSHOT` is a `provided` dependency.)

---

## Commands

### `/party` (alias `/p`)
- `create` — make a party (you become leader)
- `invite <player>` — invite (expires ~120s, configurable)
- `accept` — accept a pending invite
- `leave` — leave your party (leadership passes on / disbands if empty)
- `kick <player>` — leader removes a member
- `disband` — leader dissolves the party
- `list` — show members
- `tp` — leader summons all members to themselves

The whole party enters the **same instance** when the leader starts a dungeon.

### `/dungeon` (alias `/dg`)
- `list` — list configured dungeons
- `info <id>` — details for a dungeon
- `menu` — open the dungeon/difficulty picker GUI
- `start <id> <normal|hard|nightmare|mythic> [--speedrun] [--deathless] [--hardcore]`
  — leader starts a run (checks **level** + **gear score** for all members)
- `leave` — leave the current run
- `tokens` — show your tokens + dungeon level
- `weekly` — claim weekly reward (once / 7 days)
- `rep` — show your per-dungeon reputation
- `leaderboard <id> <difficulty>` — speedrun leaderboard for that combo
- `reload` — (admin) reload config / dungeons.yml / loot.yml

---

## Features

- **Party system** with invites, roles (leader/member), kick/disband, summon.
- **Instanced dungeons** via async template clone → per-run world → cleanup.
- **Difficulty tiers** (NORMAL/HARD/NIGHTMARE/MYTHIC) scaling health / damage /
  loot via `config.yml` multipliers.
- **Custom mobs** (tiers ELITE / MINIBOSS / BOSS): boosted health (deprecated
  `setMaxHealth`), tier-coloured names, optional glow, persistent, PDC-tagged
  with instance + tier + damage multiplier. **Damage scaled in
  `EntityDamageByEntityEvent`** by multiplying `event.getDamage()`.
- **Boss phases** at 100–70% / 70–40% / 40–0% health, triggering configurable
  abilities: `summon_adds`, `aoe_burst` (no-block-damage `createExplosion`),
  `speed_self` (velocity nudge — no potion enums), `message`. Phase changes
  announced with title + sound.
- **All 8 objectives**: `KILL_BOSS`, `COLLECT_RELICS`, `ACTIVATE_SHRINES`,
  `DEFEND_NPC`, `ESCORT_NPC`, `DESTROY_CRYSTALS`, `SURVIVE_WAVES`,
  `TIMED_CHALLENGE`. Progress shown via boss bar + action bar.
- **Party revive**: a lethal hit puts a member into a **DOWNED** state
  (spectator or frozen) for `revive-seconds` (default 30). A teammate revives
  them (right-click in frozen mode; sneak-proximity in spectator mode). Timer
  expiry → out of the run. All down/out → run fails.
- **Loot**: weighted rarity tables (`common`→`mythic`), `boss-exclusive` drops,
  loot multiplier from difficulty/hardcore. Boss-kill rolls drop at the boss;
  completion rolls go into inventories.
- **Progression**: dungeon level (from completions), gear score (armor + main
  hand base value + enchant levels), tokens, per-dungeon reputation, weekly
  rewards, achievements (first clear, per-difficulty clears, deathless,
  speedrun PB). Persisted to `playerdata.yml` on mutation + disable.
- **Endgame modes**: `--speedrun` (best-time tracking + leaderboard),
  `--deathless` (reward if zero party deaths), `--hardcore` (double boss HP,
  mobs explode on death with no block damage, **no healing** via cancelled
  `EntityRegainHealthEvent`, increased loot).

---

## Version safety (important)

The server runs base Paper 1.21 but compiles against 1.21.11; enum/method
mismatches crash on load. This plugin therefore:

- Plays sounds **only** via the `String` overload `playSound(loc, "minecraft:...", ...)`.
  No `Sound` enum / `Sound.valueOf`.
- **Never** uses the `Attribute` enum. Mob health uses deprecated
  `setMaxHealth` / `setHealth`.
- **Never** scales damage via attributes or potion-effect enums. No
  `PotionEffectType`. Mob damage is multiplied in `EntityDamageByEntityEvent`.
- Uses only stable particles/explosions (`createExplosion(loc, power, false, false)`).
- Colours via `ChatColor.translateAlternateColorCodes('&', ...)`.

---

## Config files

- `config.yml` — difficulty/tier multipliers, party/revive/hardcore/weekly
  settings, global toggles, tick rate.
- `dungeons.yml` — dungeon definitions (one full example: `crypt`).
- `loot.yml` — named loot tables.
- `playerdata.yml` — generated; per-player persistence.

---

## Roadmap / simplifications (v1)

This is a **complete, buildable framework**; some pieces are intentionally
simple and are good extension points:

- **ESCORT_NPC** moves the NPC by teleporting one block/second toward the goal
  rather than using real pathfinding.
- **Relics/shrines** are spawned as `ARMOR_STAND` entities you right-click; for
  a polished look, swap to display entities or interactable blocks placed in
  the template and resolve them by location.
- **DESTROY_CRYSTALS** counts breaking marked blocks (`END_CRYSTAL`,
  `BEACON`, `DIAMOND_BLOCK`) — adjust `InstanceManager#isCrystalBlock` or
  pre-tag blocks in the template for stricter matching.
- **Boss abilities** are a small starter set; add new ids in
  `InstanceManager#triggerPhase`.
- **`speed_self`** nudges the boss's velocity instead of applying a speed potion
  (deliberately avoiding potion enums).
- **Gear score** is a simple heuristic (material tier + enchant levels).
- **Leaderboards** are per-player best times stored locally (no cross-server
  sync).
- Party data is **in-memory** (cleared on restart), as specified.
