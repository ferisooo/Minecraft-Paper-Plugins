# KawaiiSparkles ✨

Purely cosmetic, **server-side** visual flair, driven from a clickable in-game
**GUI**. Nothing here needs a resource pack, and everything is Geyser/Bedrock-safe.

## The control panel

Run `/ksparkles` (no args) to open the panel. It's an animated chest GUI: the
border tiles flow through a colour palette while the middle row holds clickable
**ON/OFF toggle buttons**:

- 🟢 **Footsteps** — toggle your walking / running / swimming particle trails
- ✦ **Effects** — opens the **Effects menu** where you choose a *different* particle effect for each action: **footstep trail, attack, mining, jump, crouch and swim**. Each opens its own picker.
- 🟢 **Action Bar** — toggle your animated action bar
- 🟪 **Chest Sparkles** — shows the global state (config-controlled)
- ⭐ **Reload** — re-read `config.yml` (admins only)

Every action's effect is **per-player and independent** — your attack can be
Crit while your jump is a Firework and your trail is Hearts. Each action's
options live in `config.yml` (the footstep trail under `trails.options:`, the
rest under `effects.<action>.options:`), where you set an `icon`, `particle`(s)
and `count`. A built-in **None** choice lets a player switch any single effect
off. The menus resize to fit however many options you define. You can jump
straight in with `/ksparkles effects` (or `/ksparkles trail`).

Actions covered: walking/running (trail) · attacking (`EntityDamageByEntity`) ·
mining (`BlockBreak`) · jumping (Paper `PlayerJump`) · crouching
(`PlayerToggleSneak`) · swimming (`isSwimming` while moving).

### Bedrock

Bedrock (Geyser) players are detected through the **Floodgate API by reflection**
(a soft dependency — no Floodgate means everyone is treated as Java). For Bedrock
viewers, the kawaii glyphs that Bedrock's font renders as tofu boxes (✿ ✦ ✧ ✨ ⟳
» …) are swapped for ASCII look-alikes in every menu title, button name, lore line
and action-bar frame. Java players keep the full flair. The GUI itself is a plain
9-wide chest inventory, which Geyser renders natively.

## Features

| Effect | What it does | Server-side reality |
|--------|--------------|---------------------|
| **Chest sparkles** | Particle ring + a chime when a chest, trapped chest, **ender chest**, barrel or shulker box is opened (and a soft sound on close). | The lid-open animation is already vanilla; we layer particles + sound on top. ✅ Fully doable. |
| **Footsteps** | Throttled particles + a soft step sound as you walk, with a bigger puff + louder sound while **sprinting**. Stays quiet while sneaking. | ✅ Fully doable via movement events. |
| **Animated "hotbar"** | A looping, colour-cycling **action bar** above the hotbar. | ⚠️ The real 9 hotbar slots can't be repainted by the server — that needs a client resource pack. The action bar is the honest server-side equivalent. Opt-in per player. |
| **Animated menu** | `/ksparkles menu` opens a GUI whose border tiles **flow through a colour palette** every few ticks — a genuine animated inventory. | ✅ Fully doable by re-painting an inventory on a timer. |

## Commands

`/ksparkles` (aliases: `/ksparkle`, `/sparkles`, `/kspark`)

| Sub-command | Effect |
|-------------|--------|
| *(none)* / `menu` | Open the animated decorative menu |
| `footsteps` | Toggle your footstep particles/sound |
| `hotbar` | Toggle your animated action bar |
| `chest` | Reminder that chest sparkles are a global (config) setting |
| `info` | Show which features are enabled |
| `reload` | Reload `config.yml` (perm: `kawaiisparkles.admin`) |

## Permissions

- `kawaiisparkles.use` — use the toggles and menu (default: everyone)
- `kawaiisparkles.admin` — `/ksparkles reload` (default: op)

## Config highlights

- Per-effect on/off switches, particle types, counts, sounds and pitches.
- Separate walk vs. sprint intervals/particles/sounds.
- Custom action-bar frames (with `&` colour codes) and frame speed.
- Animated menu rows, wave speed and stained-glass colour palette.
- `defaults:` controls whether footsteps/hotbar start ON for new players
  (the action bar defaults to **off** so it never surprises anyone).

Sounds are resolved to namespaced keys (e.g. `block.note_block.bell`) and played
through the `String` overload of `playSound`, which sidesteps the
`org.bukkit.Sound` enum→interface split between 1.21.x builds.

## Ideas / what more could be added

These weren't built (to keep it focused), but are all server-side feasible:

- **Trail themes** — per-player particle trail styles unlockable/selectable
  (hearts, notes, snow), with a picker in the menu.
- **Elytra / jump / land** effects — a puff on landing, a spiral while gliding.
- **Ambient idle sparkles** — a slow halo when a player stands still.
- **Combat flair** — crit stars on hit, a small shockwave on kill.
- **Time/biome reactive** — fireflies at night, petals in flower biomes.
- **Per-world or region toggles**, and a global particle budget so big servers
  stay smooth.
- **PlaceholderAPI** + persistence of per-player toggles across restarts.

> Anything that repaints the **actual** inventory/hotbar slots, item models, or
> the chest mesh itself (beyond particles/sound) requires a **resource pack** or
> client mod — that's a client-rendering limit, not a plugin one.
