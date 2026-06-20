# KawaiiSkyblock 🏝

A **skyblock core with full island management**. Each player gets their **own
island world**, created by **copying a saved template world folder** into a
fresh world named `kawaii_isle_<id>`, where `<id>` is the owner's UUID with the
dashes removed (32 chars), e.g. `kawaii_isle_6631d98403a53fb89268995ebde56ee8`.
`/island create` clones the template and warps you there; `/island delete
confirm` unloads and wipes it.

On top of that core it adds a granular trust system, anti-grief flags, a PvP
toggle, an upgradable cobblestone generator, island visiting, warps (including
warp signs) and island-level leaderboards.

## Setup
1. Build a template island in any world, then copy that world folder into the
   server root (the Bukkit world container, next to your `world/` folder).
2. Name the folder `SKYBLOCK ISLAND ADVANCED` (or change `template-world` in
   `config.yml` to match your folder's name).
3. The plugin copies this folder for every new island, stripping `uid.dat` and
   `session.lock` from the copy so Bukkit assigns a fresh world UID.

## Commands
All commands work under `/island` (aliases `/is`, `/sb`, `/skyblock`).

**Core**
- `/is` — open the **animated island menu** (shimmering border), laid out
  symmetrically: a centred row of primary actions (Create, Home/Go, Spawn, Info,
  Delete) above two centred rows of feature buttons (Flags, Members, Warps,
  Generator, Visit, Permissions, Leaderboard, Island Value, Kick).
- `/is create` / `/is home` (`go`,`tp`) / `/is spawn` / `/is delete confirm`.
- `/is reload` — reload config (`kawaiiskyblock.admin`).

**Trust / members**
- `/is trust <player> <leader|coop|member|visitor>` — assign a role.
- `/is untrust <player>` — remove a member.
- `/is members` — open the Members GUI (left-click cycles role, shift-click kicks).
- `/is invite <player>` / `/is accept` — co-op invite (in-memory, 2-min timeout;
  accepting joins as **COOP**).
- `/is kick <player>` (alias `/is eject <player>`) / `/is promote <player>` /
  `/is demote <player>` — team management. **Kick / eject** behaviour:
  - If the target is a trusted **MEMBER/COOP**, their trust is removed (set to
    **VISITOR**); if they're currently standing in the island world they are also
    teleported to the **main world spawn**.
  - If the target is an **untrusted VISITOR standing on the island**, they are
    **ejected** (teleported to the main spawn) without a trust change.
  - The **owner can't be kicked**, and **only the LEADER may kick a COOP**. The
    kicked/ejected player is notified.
  - In the **Members GUI**, a normal click **cycles role**; a **SHIFT-click kicks/
    ejects** (documented in the head's lore). A tab-completer suggests online
    player names for the `<player>` argument.

**Flags & PvP**
- `/is flags` — **paged** Flags GUI (Flags 1/2/3…), one toggle per flag with
  Prev/Next/Back navigation. Toggling flips the per-island value.
- `/is perms` — **Permissions editor**: first pick a role (COOP/MEMBER/VISITOR),
  then a **paged** GUI toggles that role's individual member-permissions.
- `/is pvp` — toggle the PvP flag.

**Generator**
- `/is generator` — Generator GUI showing the level + an Upgrade button (the
  button lore shows the current level, the next level, and the item cost).
- `/is generator upgrade` — raise the generator level (LEADER/MANAGE). Upgrading
  **costs items** paid from your inventory: cost for level `L → L+1` is
  `generator.upgrade-cost-base + generator.upgrade-cost-per-level * L` of
  `generator.upgrade-cost-item`. With the defaults (`DIAMOND`, base 2,
  per-level 2): `L1→2 = 4`, `L2→3 = 6`, `L3→4 = 8`, `L4→5 = 10` diamonds. If you
  don't have enough the upgrade is refused with a message showing need vs. have.

**Visiting**
- `/is visit <player>` — teleport to that player's island (subject to policy).
- `/is setvisit <public|invite|trust>` — set who may visit (LEADER).

**Warps**
- `/is setwarp <name>` / `/is delwarp <name>` (MANAGE) — manage warps.
- `/is warp <name>` — warp on your own island.
- `/is warps` — Warps GUI.
- **Warp signs**: place a sign with first line `[warp]` and a warp name on the
  second line (only a member with MANAGE may create one). Right-clicking it
  teleports the clicker to that warp, subject to visit policy.

**Leaderboard**
- `/is top` — top islands by island level (member count as secondary sort).
- `/is level` — recompute and show your island's level.
- `/is value` (`worth`) — open the **animated Island Value GUI**: a throttled
  block scan listing the top value-contributing block types
  (icon = the block, lore = `count × per-block value = subtotal`), plus the total
  island level/value and your leaderboard rank.

## Roles & permissions
Roles (weakest → strongest): **VISITOR**, **MEMBER**, **COOP**, **LEADER**.
The island owner is always LEADER. Players with no membership are treated as
VISITOR. Abilities:

| Role    | BUILD | CONTAINERS | SPAWN_MOBS | MANAGE |
|---------|:-----:|:----------:|:----------:|:------:|
| LEADER  |  ✔    |    ✔       |    ✔       |   ✔    |
| COOP    |  ✔    |    ✔       |    ✔       |   ✔    |
| MEMBER  |  ✔    |    ✔       |            |        |
| VISITOR |       |            |            |        |

Only LEADER/COOP (MANAGE) may change trust, flags, warps and settings; only the
LEADER may demote/kick a COOP. `kawaiiskyblock.admin` bypasses protection.

Beyond the coarse 4 perms (used for build/containers/manage gating of warps,
generator and the menus), the plugin adds a full **data-driven member-permission
system** (see below) so admins can tune, per role, exactly which of ~107
fine-grained actions each role may perform.

Every sub-GUI (Flags, Members, Warps, Generator, Permissions) has a **« Back**
button (an arrow in a fixed interior slot, clear of the animated border) that
returns to the main island menu (the Permissions editor's Back returns to the
role picker). The animated shimmer border is preserved on every screen, and the
paged GUIs add **Prev/Next** arrows plus a page indicator in the bottom interior
row, clear of the border.

## Environment flags (16, data-driven; enforced only inside `kawaii_isle_*`)
Per-island booleans, edited via the **paged** `/is flags` GUI. A flag being
**true** means the action is **allowed**; **false** suppresses it. Defaults come
from `flag-defaults:` in `config.yml` (any omitted key uses a built-in default).
Convention: protective/destructive flags default **off** (`pvp`, `fire_*`,
`*_explosions`, `entity_grief`, `lightning_strike`); natural growth/spread default
**on**; damage-cause flags default **on**.

**Only these 16 flags have any in-game effect — everything else is vanilla.**
The curated key set:
`pvp, tnt_explosions, creeper_explosions, explosion_block_damage, fire_spread,
fire_burn, fire_ignite, entity_grief, lightning_strike, leaf_decay, crop_growth,
liquids_flow, fall_damage, natural_breeding, weather_change, portal_create`.

Enforcement is **generic**, keyed by event + cause/source rather than one handler
per flag, e.g. `EntityExplodeEvent`/`BlockExplodeEvent` → `creeper_explosions` /
`tnt_explosions` / `explosion_block_damage`; `BlockIgniteEvent` →
`fire_ignite`/`fire_spread`; `BlockBurnEvent` → `fire_burn`; `BlockSpreadEvent` →
`fire_spread`; `BlockGrowEvent` → `crop_growth`; `LeavesDecayEvent` → `leaf_decay`;
`CreatureSpawnEvent` (BREEDING) → `natural_breeding`; `EntityDamageEvent` (FALL) →
`fall_damage`; `EntityDamageByEntityEvent` → `pvp`; plus `lightning_strike`,
`weather_change`, `liquids_flow`, `entity_grief` and `portal_create`.

## Member permissions (38, data-driven, per-role)
A second registry, **separate from flags**: per-island booleans keyed by
(role, permission). The effective allowance for a player is: the island **owner**
and `kawaiiskyblock.admin` always pass; otherwise the player's **role** on that
island must grant the permission. Role→permission defaults live under `roles:` in
`config.yml` so admins can tune which role (or VISITOR) gets which permission;
those defaults can be overridden **per island** via the in-game `/is perms`
editor, which stores overrides under `islands.<owner>.permissions.<role>.<key>`.

The curated permission keys:
`destroy_block, place_block, interact_door, interact_trapdoor,
interact_fence_gate, interact_button, interact_lever, interact_pressure_plate,
interact_chest, interact_barrel, interact_shulker_box, interact_furnace,
interact_hopper, interact_anvil, interact_enchanting_table,
interact_crafting_table, interact_brewing_stand, interact_beacon, interact_bed,
interact_item_frame, interact_armor_stand, interact_vehicle, interact_villager,
interact_entity, destroy_item_frame, destroy_armor_stand, destroy_vehicle,
damage_entity, breed_entity, tame_entity, shear_entity, trample_crops,
pickup_item, drop_item, use_bucket, use_ender_pearl, use_elytra, eat`.

Enforcement uses **generic lookup-table listeners** (not one per key):
`PlayerInteractEvent` right-clicks map the clicked block via `Material`/`Tag` to
an `interact_*` key, and the in-hand item's `Material` to a `use_*` key;
`BlockBreakEvent`/`BlockPlaceEvent` → `destroy_block`/`place_block`; physical
farmland → `trample_crops`; `PlayerInteractEntityEvent` /
`EntityDamageByEntityEvent` map the entity (and held item) to
`interact_`/`damage_`/`destroy_`/`shear_`/`tame_`/`lead_`/`name_tag_`/`milk_`/
`feed_` keys; `EntityPickupItemEvent` → `pickup_item`; `PlayerDropItemEvent` →
`drop_item`; `PlayerBedEnterEvent` → `sleep`. A denied action is cancelled with a
short message.

## Upgradable cobblestone generator
When lava + water forms cobblestone/stone inside an island, the plugin rolls a
chance (per `generator.levels.<level>` in `config.yml`) to instead form a rarer
block (coal/iron/gold/diamond/emerald ore, obsidian, glowstone, mycelium…).
Higher level = better ores; `generator.max-level` caps it. Each chance is 0..1
and the list is cumulative; the leftover probability stays plain cobblestone.

## Island level & leaderboard
Island level = sum of `leaderboard.block-values` over a capped bounding box
around the island spawn, divided by `points-per-level`. Scanning is throttled:
`/is level` recomputes on demand, and a periodic task recomputes only **loaded**
islands at most once every `recompute-minutes`. The scan is bounded by
`scan-radius` and `scan-min-y`/`scan-max-y` so it can't lag the server. There is
**no economy** — the leaderboard ranks by island level, then member count.

## Data model (`islands.yml`)
Per owner UUID under `islands.<owner>`: `world` (folder), `members.<uuid>: ROLE`,
`flags.<flag>: bool`, `visitPolicy`, `generatorLevel`, `warps.<name>:
"world,x,y,z,yaw,pitch"`, cached `level`/`levelComputed`, and `lastSeen` (epoch
millis, used by the inactive-island purge). Backward compatible
with the original `islands.<uuid>.world`. A worldName→owner reverse map is rebuilt
in memory on enable for O(1) listener lookups; co-op invites are in-memory only.

## Keeping the server lean
Island worlds accumulate as folders in the server root, so KawaiiSkyblock
bounds them in three ways:

- **Compact folder names.** New islands are `kawaii_isle_<uuid-no-dashes>` (32
  chars), e.g. `kawaii_isle_6631d98403a53fb89268995ebde56ee8`. Existing islands
  keep the folder name recorded in `islands.yml`, so nothing is renamed and they
  keep working.
- **Idle-world unloading.** When the last player leaves an island world (changing
  worlds or logging out), it is unloaded from memory a few seconds later (after a
  re-check that it's still empty). The folder stays on disk — this only frees
  RAM/ticking. The main world is never unloaded.
- **Inactive-island auto-purge (opt-in).** A periodic task can permanently delete
  islands whose owner has been offline and unseen for too long. It is **disabled
  by default** because it destroys player builds. Configure under `purge:` in
  `config.yml`:
  - `purge.inactive-days` — `0` disables it (default); e.g. `30` deletes islands
    whose owner hasn't logged in for 30 days.
  - `purge.check-hours` — how often (hours) to scan (default `6`).

  Online owners are never purged, each island's purge is wrapped in try/catch so
  one failure doesn't abort the sweep, and every purge is logged. Pre-existing
  islands with no recorded `lastSeen` are treated as "seen now" on first load, so
  enabling the purge won't instantly wipe them.

## How it works
- **One island per player.** The file copy runs async; the world is loaded and
  you are teleported on the main thread.
- Player → world-folder mappings are saved in `islands.yml` and reloaded on
  startup so `/is home` keeps working after a restart.
- In the GUI, Delete requires a SHIFT-click on the TNT to avoid accidental wipes.
- These islands are named `kawaii_isle_*`; the KawaiiWorlds hub hides them (and
  the template) from its world listing.

## Build
Java 21 + Maven: `mvn clean package`.
