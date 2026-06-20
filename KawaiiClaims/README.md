# KawaiiClaims

A lightweight, **multi-chunk** land-claim and protection plugin for Paper/Bukkit **1.21**.
Claim connected chunks with a golden shovel, trust your friends with role-based permissions,
toggle per-claim protection flags, and keep your builds safe from grief.

Part of the Kawaii plugin suite. Java 21 / Maven. Package `com.ferisooo.kawaiiclaims`.

---

## How claiming works

- A **claim is a connected SET of 16x16 chunks** owned by one player (much like
  SimpleClaimSystem). Each claim has a stable id, an owner, a chunk set, trust,
  flags, an optional home, greeting/farewell, and timestamps.
- **Auto-merge:** when you claim a chunk next to chunks you already own, they merge
  into a single claim. Claiming a chunk that bridges two of your separate claims
  merges all of them into one (oldest claim's id/trust/flags/home are kept).
- **Auto-split:** when you unclaim a chunk that disconnects a claim, it splits into
  separate claims (largest component keeps the original id/settings; the rest become
  new claims with the same owner/trust/flags).
- Each chunk is keyed `world;chunkX;chunkZ`; the manager keeps a `byId` map plus a
  reverse `chunkToClaimId` index, both persisted to / rebuilt from `claims.yml`.
- **Legacy migration:** an old single-chunk `claims.yml` is detected on load, wrapped
  into one-chunk claims, then run through the auto-merge pass so previously-adjacent
  same-owner chunks become single multi-chunk claims.
- **Only claimed chunks are protected.** Unclaimed chunks are open wilderness.

## Getting started

1. The first time you join, you are automatically given a named **✦ Claim Tool**
   (a golden shovel). This is configurable via `give-tool-on-join` and tracked
   per-player so you only receive it once. Lost it? Run `/claim tool` (or click
   **Get Claim Tool** in the menu) for a replacement.
2. **Right-click** any block while standing in a chunk to claim that chunk.
3. **Left-click** to inspect a chunk: it shows the owner and draws a glowing particle
   border around the chunk for ~5 seconds (visible only to you).

Or use the commands below.

---

## Commands

Base command: `/claim` (aliases: `/claims`, `/kc`, `/kclaims`). Running `/claim` with no
arguments opens the management GUI for the claim you are standing in.

| Command | Description |
|---|---|
| `/claim claim` | Claim the chunk you are standing in (auto-merges with adjacent same-owner chunks). |
| `/claim radius <n>` | Claim a `(2n+1)x(2n+1)` square of chunks around you (free chunks only), respecting your limit. |
| `/claim unclaim` / `/claim abandon` | Remove only the chunk you're standing in (splits the claim if it disconnects). |
| `/claim unclaim all` | Delete **all** of your claims. |
| `/claim list` | List your claims, each with its chunk count, plus your total usage/limit. |
| `/claim info` | Show info about the claim you're standing in (chunk count, your usage + border). |
| `/claim trust <player> <access\|container\|build\|manage>` | Trust a player at a level. |
| `/claim untrust <player>` | Remove a player's trust. |
| `/claim flag <flag> <on\|off>` | Toggle a world/environment protection flag (16 flags). |
| `/claim flags` | Open the **paged** Flag editor GUI. |
| `/claim preset <name>` | Apply a config-defined flag preset to this claim (owner/MANAGE only). |
| `/claim show` (alias `/claim border`) | Toggle a particle border around the claim you're in. |
| `/claim perm <role> <permission> <on\|off>` | Set whether a role (incl. `visitor`) is granted a member permission. |
| `/claim perms` / `/claim roles` | Open the **Role Permissions** editor GUI. |
| `/claim tool` / `/claim wand` | Get a replacement golden **✦ Claim Tool** shovel. |
| `/claim sethome` | Set the claim home (must stand in your own claim). |
| `/claim home` | Teleport to your claim home (safe, loads the chunk). |
| `/claim transfer <player>` | Give the claim to another player (owner only). |
| `/claim greeting <text...>` | Set the message shown when someone enters. |
| `/claim farewell <text...>` | Set the message shown when someone leaves. |
| `/claim menu` | Open the management GUI. |
| `/claim bypass` | (admin) Toggle ignoring all protection. |
| `/claim admin delete` | (admin) Force-delete the claim you're standing in. |
| `/claim admin list` | (admin) Show total claims/chunks and a per-owner chunk breakdown. |
| `/claim admin givechunks <player> <amount>` | (admin) Add to a player's bonus chunk grant (may be negative). |
| `/claim admin setchunks <player> <amount>` | (admin) Set a player's bonus chunk grant. |

Greeting/farewell text supports `&` color codes.

---

## Trust, roles & member permissions

Per-claim, role-based trust with four ordered levels, plus the implicit **visitor** role
for untrusted players:

```
visitor  <  ACCESS  <  CONTAINER  <  BUILD  <  MANAGE
```

A player's **role** is their trust level (or `visitor` if untrusted). Roles map to a curated,
**data-driven member-permission set** (38 keys) such as
`destroy_block`, `place_block`, `interact_chest`, `use_bucket`, `breed_entity`,
`damage_entity`, and more.

- **Effective allowance** for a player = owner (all) / admin-bypass, OR the player's role
  grants the permission.
- **Defaults are data-driven** via the `roles:` block in `config.yml`: admins choose which
  role (and `visitor`) gets which permission. Unlisted keys fall back to built-in cumulative
  defaults (`access < container < build < manage`; `visitor` gets only harmless self-affecting
  actions like `eat`, `use_elytra`).
- **Per-claim overrides:** owners/managers tune grants per claim with
  `/claim perm <role> <permission> <on|off>` or the **Role Permissions** GUI. Overrides are
  stored per claim under `perms.<role>.<permission>` in `claims.yml`.

The **owner** always has full rights. Only the **owner** can `transfer` or `abandon` the claim.
Players with `kawaiiclaims.admin` who have toggled `/claim bypass` ignore all protection.
Trusted players are notified in chat when they are online.

### Permission keys (member permissions)

`destroy_block`, `place_block`, `interact_door`, `interact_trapdoor`, `interact_fence_gate`,
`interact_button`, `interact_lever`, `interact_pressure_plate`, `interact_chest`,
`interact_barrel`, `interact_shulker_box`, `interact_furnace`, `interact_hopper`,
`interact_anvil`, `interact_enchanting_table`, `interact_crafting_table`,
`interact_brewing_stand`, `interact_beacon`, `interact_bed`, `interact_item_frame`,
`interact_armor_stand`, `interact_vehicle`, `interact_villager`, `interact_entity`,
`destroy_item_frame`, `destroy_armor_stand`, `destroy_vehicle`, `damage_entity`,
`breed_entity`, `tame_entity`, `shear_entity`, `trample_crops`, `pickup_item`, `drop_item`,
`use_bucket`, `use_ender_pearl`, `use_elytra`, `eat`.

---

## Protection (only inside claimed chunks)

Protection splits into two systems:

**Member permissions** (gated by the acting player's role — see *Trust, roles & member permissions*):

- **Block break/place** → `destroy_block` / `place_block`.
- **Right-click a block** → mapped via `Material`/`Tag` to the matching `interact_*` permission
  (doors, trapdoors, fence gates, buttons, levers, pressure plates, every container, anvils,
  tables, lecterns, beds, candles, signs, spawners, vaults, …). Containers are also gated on
  `InventoryOpenEvent` via `interact_chest`.
- **Item-in-hand use** → mapped via the in-hand `Material` to `use_*` (bucket, bow/crossbow,
  fishing rod, ender pearl, elytra, firework, potion, egg, snowball, trident, shield, fire
  charge, wind charge, spyglass, map, brush…); flint & steel → `ignite_block`.
- **Entity right-click / damage** → entity type → `interact_*` / `damage_*` / `destroy_*`
  (item frames, paintings, armor stands, vehicles, villagers, animals…). Projectile shooters
  and area-effect-cloud sources are resolved to the responsible player.
- **Pickup/drop** → `pickup_item` / `drop_item`; **sleep** → `sleep`; **portal use** →
  `use_portal`; **crop trample** on farmland → `trample_crops`; **vehicle enter** →
  `interact_vehicle`.

**World/environment flags** (per-claim, see *Flags* below):

- **Explosions**: `EntityExplodeEvent`/`BlockExplodeEvent` block lists are filtered per claim,
  split by source into `creeper_explosions`, `charged_creeper_explosions`, `tnt_explosions`,
  `minecart_tnt_explosions`, `wither_explosions`, `wither_skull_explosions`,
  `ender_crystal_explosions`, `fireball_explosions`, `wind_charge_explosions`, `bed_explosions`,
  `respawn_anchor_explosions`, plus the master gates `entity_explode` and `explosion_block_damage`.
- **Fire**: `BlockBurnEvent`→`fire_burn`, `BlockIgniteEvent`→`fire_ignite`/`fire_spread` by cause
  (a player with `ignite_block` may still light fire).
- **Spread/form/fade/grow**: `BlockSpreadEvent`→`fire_spread`/`sculk_spread`/`mushroom_spread`/
  `mycelium_spread`/`vine_growth`/`flower_spread`; `BlockFormEvent`→`snow_form`/`block_form`;
  `BlockFadeEvent`→`snow_melt`/`ice_melt`/`block_fade`; `BlockGrowEvent`→`crop_growth`/`block_grow`;
  `LeavesDecayEvent`→`leaf_decay`.
- **Spawns**: `CreatureSpawnEvent` with reason `BREEDING` → `natural_breeding`. All other spawns
  are vanilla.
- **Damage by cause**: `EntityDamageEvent` with cause `FALL` → `fall_damage`. All other damage
  causes are vanilla.
- **Misc**: `LightningStrikeEvent`→`lightning_strike`; `WeatherChangeEvent`→`weather_change`;
  `BlockFromToEvent`→`liquids_flow` (cross-boundary always blocked);
  `EntityChangeBlockEvent`→`entity_grief`; `PortalCreateEvent`→`portal_create`.
- **PvP**: with `pvp=false` (default = safe zone), player-vs-player damage (including player
  projectiles) is cancelled.
- **Pistons** that push/pull blocks across a claim boundary are cancelled.

---

## Flags (data-driven custom flag system, 16 keys)

World/environment flags are defined by the `flag-defaults` block in `config.yml`. The set is
fully **data-driven** — adding/removing a key there changes what appears everywhere (command
tab-complete, `/claim flag`, and the **paged** Flags GUI) automatically. Any flag NOT in the
recognised set has no effect — that behaviour stays vanilla.

**Default convention:** protective/destructive behaviour defaults OFF (`pvp`, `tnt_explosions`,
`creeper_explosions`, `explosion_block_damage`, fire spread/burn/ignite, `entity_grief`,
`lightning_strike`, `weather_change`, `portal_create`). Ordinary damage and natural growth
default ON (`fall_damage`, `crop_growth`, `leaf_decay`, `liquids_flow`, `natural_breeding`).

The curated 16-key set: `pvp`, `tnt_explosions`, `creeper_explosions`, `explosion_block_damage`,
`fire_spread`, `fire_burn`, `fire_ignite`, `entity_grief`, `lightning_strike`, `leaf_decay`,
`crop_growth`, `liquids_flow`, `fall_damage`, `natural_breeding`, `weather_change`,
`portal_create`.

### Flag presets

Config-defined **presets** bundle a set of flag values under a name. Apply one to the claim you're
standing in (owner/MANAGE only) with `/claim preset <name>`, or via the **Flag Presets** button in
the claim menu (a Nether Star). Only the flags a preset lists are changed; omitted flags keep their
current value. Shipped presets: `private` (locked down), `public-farm` (builds protected, growth
open), `pvp-arena` (PvP on, explosions off), and `open` (mostly vanilla). Edit/add presets in the
`presets:` block of `config.yml`.

### Claim borders on demand

`/claim show` (alias `/claim border`) toggles a repeating particle outline (~10s) of the claim
you're standing in, shown only to you; run it again to hide it early. The claim menu has a
**Show Border** button too. With `show-border-on-enter: true` (default), the border briefly
flashes whenever you walk into one of your **own** claims.

The Flags GUI is a **54-slot, paged** menu (**Flags 1/N**): up to 25 flags per page in the inner
slots, with **« Previous / « Back / Next »** navigation in the bottom interior row so the animated
border is never covered.

### Entry & exit

When a player crosses a chunk boundary, the plugin shows the entered claim's **greeting** and the
left claim's **farewell** (action bar by default, configurable to chat). Entry/exit are gated by the
`enter` / `leave` member permissions: a player whose role lacks `enter` is teleported back; lacking
`leave` keeps them in.

---

## Visualization

Claiming and inspecting draw a temporary glowing **particle border** (`END_ROD`) around the
chunk edges for ~5 seconds, shown only to the acting player.

---

## Limits & worlds

- `max-chunks-per-player` (default **16**) caps the **total** chunks each player may
  claim across all their claims. A player's effective limit = base + their per-player
  bonus (`bonus-chunks.<uuid>` in `claims.yml`, granted via `/claim admin givechunks|setchunks`).
  Players with `kawaiiclaims.admin` are exempt (unlimited).
- `max-radius` (default **5**) caps `/claim radius <n>`. The `kawaiiclaims.radius`
  permission (default true) gates the command.
- `enabled-worlds` whitelists worlds where claiming is allowed. **Empty = all worlds.**

---

## Claim expiration (OFF by default)

`expiration-days` controls automatic cleanup of abandoned claims. **Default `0` = disabled**,
because deleting builds is destructive. When enabled:

- A claim only expires if its owner is **offline** and inactive beyond the window.
- `lastActive` is bumped on owner login and whenever the owner performs claim actions.
- The sweep runs every `expiration-check-hours` (default 12).
- Claims owned by the configured `server-uuid`, and owners with `kawaiiclaims.exempt`
  (approximated by op-status while offline), are never expired.

---

## Server / admin-owned claims (optional)

Set `server-uuid` in `config.yml` to a UUID to mark those claims as server-owned: they are
exempt from the per-player limit and from expiration.

---

## Notifications

- Greeting/farewell on chunk crossing.
- Permission-denial messages are **rate-limited** to one per player per ~3 seconds so
  spam-clicking doesn't flood chat.
- Trust grant/removal and claim transfer notify the affected online player.

---

## GUIs

All GUIs feature an **animated shimmer border** — the perimeter panes cycle through
pink/magenta/purple every few ticks while the menu is open. Buttons sit in the inner
(non-border) slots so the animation never covers them. A background task repaints the
border of every open KawaiiClaims menu and calls `updateInventory()`.

- **Management GUI** (`/claim menu` or bare `/claim`): claim info (owner, world, the claim's
  chunk count, and your chunk usage/limit) plus buttons to open the Flags GUI, Trust GUI,
  **Role Permissions** editor, set home, **Get Claim Tool** (golden shovel), a **Claim Radius**
  hint, **Show Border**, **Flag Presets**, and **Abandon Chunk** (shift-click the barrier to
  confirm).
- **Flag editor GUI** (**paged**): one dye per flag (green = ON / gray = OFF), click to flip,
  with **« Previous / « Back / Next »** navigation for the 16 flags.
- **Flag Presets GUI**: one chest per config-defined preset (lore lists the flags it sets);
  clicking one applies the preset and reopens the Flags view. Includes a **« Back** arrow.
- **Trust GUI**: a head per trusted member showing their level; left-click cycles the level,
  shift-click removes them; includes a hint to use `/claim trust` and a **« Back** arrow.
- **Role Permissions GUIs**: a **role picker** (visitor/access/container/build/manage, each
  showing how many of the 38 permissions it currently grants), then a **paged** per-role
  permission editor (**Perms 1/N**) toggling each permission key for that role, with the same
  Previous/Back/Next navigation. Back from the editor returns to the role picker.

All GUIs are identified via `InventoryHolder` marker classes (the holder also carries the current
page and, for the permission editor, which role is being edited) and cancel their own clicks. The
paging buttons live in non-border interior slots so the shimmer animation never covers them.

---

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `kawaiiclaims.use` | true | Use claim commands and the golden shovel. |
| `kawaiiclaims.radius` | true | Use `/claim radius` to claim a square of chunks at once. |
| `kawaiiclaims.admin` | op | Bypass protection, force-delete, grant bonus chunks, admin lists, unlimited chunks. |
| `kawaiiclaims.exempt` | op | Owner's claims are exempt from expiration. |

---

## Configuration (`config.yml`)

```yaml
enabled-worlds: []
max-chunks-per-player: 16
max-radius: 5
expiration-days: 0
expiration-check-hours: 12
server-uuid: ""
messages-on-actionbar: true
give-tool-on-join: true

show-border-on-enter: true

# 16 world/environment flag defaults (full list & comments in the bundled config.yml)
flag-defaults:
  pvp: false
  explosion_block_damage: false
  fire_spread: false
  entity_grief: false
  fall_damage: true
  crop_growth: true
  leaf_decay: true
  # ...all 16 keys, see the bundled config.yml

# Flag presets: named bundles applied with /claim preset <name> or the GUI.
presets:
  private: { pvp: false, explosion_block_damage: false, entity_grief: false }
  public-farm: { crop_growth: true, natural_breeding: true, entity_grief: false }
  pvp-arena: { pvp: true, explosion_block_damage: false }
  open: { pvp: true, tnt_explosions: true, explosion_block_damage: false }
  # ...full presets in the bundled config.yml

# Per-role member-permission defaults (38 keys). Unlisted keys inherit built-in
# cumulative defaults (access < container < build < manage; visitor = harmless only).
roles:
  visitor:
    enter: true
    interact_ender_chest: true
  access:
    interact_door: true
    interact_chest: false
  container:
    interact_chest: true
    pickup_item: true
  build:
    destroy_block: true
    place_block: true
  manage:
    fly: true
```

Data is stored in `claims.yml` (YAML — no external database), saved on every mutation and on
disable. Per-claim permission overrides are stored under `claims.<id>.perms.<role>.<permission>`.

---

## Building

```
mvn -q -pl KawaiiClaims package
```

Produces `target/KawaiiClaims-1.0.0.jar`. Requires the paper-api `1.21.11-R0.1-SNAPSHOT`
dependency (provided scope) and Java 21.

> Version-safety note: the server may run base Paper 1.21 while compiling against 1.21.11.
> To avoid enum-mismatch crashes, this plugin never uses the `Sound` enum (sounds play via
> the String overload), never uses `Attribute`, and uses only stable, non-renamed particles
> (`END_ROD`).

---

## Roadmap / not yet implemented (v1)

These are intentionally **not** in v1 and are documented here rather than half-stubbed:

- Map integrations: Dynmap / BlueMap / Pl3xMap / minimap.
- PlaceholderAPI support.
- Rollback / block-logging / grief-detection / auto-restore.
- Clan / guild / team ownership.
- SQL databases (MySQL/PostgreSQL/SQLite) — KawaiiClaims uses YAML.
- A public API / custom events / webhooks / addon system.
- Sub-chunk / block-precise selections (KawaiiClaims is chunk-granular; claims are
  multi-chunk sets with auto-merge/split).
- Inheriting inactive ownership beyond the optional expiration cleanup.
```
