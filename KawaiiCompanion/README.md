# KawaiiCompanion

A per-player AI companion. Spawns a player-skinned NPC that follows you around, chats with you via DeepSeek's API, remembers your past conversations, and has a configurable personality.

## What it is (and isn't)

It **is** a small, self-contained companion: one NPC per player, rendered as a real player entity (NMS `ServerPlayer`) wearing pink leather armor and whatever skin you put in `skins/`. The companion follows you, glances at you, teleports if you outrun it, and replies when you `/kc say <something>`.

It **isn't** a fully physical player NPC. She glides between waypoints rather than running a real walk-cycle physics sim, and she can't place blocks or jump as part of physics. If you want that, install Citizens. Within those bounds, though, she's now reasonably alive — see the **Behavior** section below.

**Bedrock players** can't see the fake-player (Geyser doesn't render it), so they automatically get a **real, visible mob companion** instead — see [Bedrock & real-entity companion](#bedrock--real-entity-companion-feature-1). Your companion also **levels up and unlocks abilities** ([Leveling & abilities](#leveling--abilities-feature-2)) and can be **ridden** ([Mount mode](#mount-mode-feature-4)).

## Behavior

Out of the box she'll:

- **Sprint to catch up** when she falls more than ~7 blocks behind, with the proper sprint-particle animation. Catches her up across staircases or when you sprint-jump ahead.
- **Climb ladders / vines / scaffolding** when her target is above her — she steps vertically up the climbable column instead of bumping into it.
- **Stay on solid ground in scout mode** — wander targets that would drop her more than 4 blocks below the anchor are rejected, so she doesn't wander off cliffs.
- **Hop animation** — a small upward bob (a genuine little jump) on the frame she steps up onto a higher block (slab, stair, fence). Not an arm-swing — that's the attack animation, and reusing it made her look like she was punching the air.
- **Wave when you come back** — single arm-swing the moment you walk back into close range after being away.
- **Stretch + yawn before sleeping** — brief crouch + arm-swing right before the SLEEPING transition in STAY mode, so the doze-off reads as "yawn → settle in" instead of a hard pose snap.
- **"Hmm" crouch** — brief sneak when she has to repath out of being stuck, so the recompute reads as "let me find another way".
- **Watch nearby animals** — in STAY/SCOUT, she occasionally turns her head to track a nearby non-hostile mob (cow, fox, parrot, …).
- **Watch you** — in STAY mode, when you're within 12 blocks she'll head-track you most of the time instead of doing random looks.
- **Idle fidgets** — small arm swings layered onto the existing head-turns so she looks alive between them.

All of these are individually toggleable under the `behavior-extras:` section in `config.yml`. Leaving them all on is the recommended setup.

## Smarts

The `smarts:` section in `config.yml` controls three "she actually pays attention" features:

- **stuck-escape** — when she gets pinned in a hole, suffocated, or trapped where her A* can't path her out, she enters a small dig-out state machine. Three phases: detect (head/foot in solid, plus the existing stuck-tick counter), escape (carve upward through dirt/sand/cobble — never wood, ores, builds, or chests), then a fallback emergency-teleport to you with an apology bubble after ~6 seconds. Block-breaks fire real `BlockBreakEvent`s attributed to you, so GriefPrevention/WorldGuard still apply normally.
- **player-state monitor** — she watches your HP, hunger, fire ticks, and air. When one crosses a threshold she bubbles a short contextual line ("you're drowning! swim up!", "you're hurt!! eat something!"). Edge-triggered + per-companion cooldown so a long fight isn't a wall of bubbles.
- **deepseek-context** — every `/kc say` now includes a short situational summary (location, biome, time of day, weather, your vital stats, what's in her bag, nearby creatures) injected into the system prompt. She'll naturally reference real things — the actual cow next to you, that you're at half health — instead of making them up. Adds ~150-300 tokens per chat round.

All three are on by default and individually toggleable.

## Install

1. Drop `kawaiicompanion-1.0.0.jar` into `plugins/`, restart.
2. Open `plugins/KawaiiCompanion/config.yml` and either:
   - paste your DeepSeek API key into `api-key:` (don't commit it!), **or**
   - leave it blank and set `DEEPSEEK_API_KEY` as an environment variable on the server.
3. Drop a skin file into `plugins/KawaiiCompanion/skins/<name>.json` (see "Skins" below).
4. In-game: `/kc summon`.

## Commands

| Command | What it does |
|---|---|
| `/kc summon` | spawn your companion next to you |
| `/kc dismiss` | despawn it (memory + name are kept) |
| `/kc say <message>` | talk to it (or just `/kc <message>`) |
| `/kc rename <name>` | give it a new name |
| `/kc skin <fileName>` | switch to a skin from `skins/<fileName>.json` |
| `/kc skins` | open the animated appearances GUI (skins + every mob form, paginated) |
| `/kc form <mob\|human>` | morph into **any living mob** — each form fights hostiles in its own style |
| `/kc info` | show level, XP, and unlocked abilities |
| `/kc mount` / `/kc ride` | ride your companion (real-entity / Bedrock companion only) |
| `/kc reset` | wipe the conversation history |
| `/kc reload` | re-read config (op-only) |

Aliases: `/companion`, `/kwcompanion`.

## Bedrock & real-entity companion (FEATURE 1)

The default companion is an NMS fake-player. Geyser can't render that, so **Bedrock owners** (detected via their Floodgate UUID, where the most-significant 64 bits are 0) are instead given a **real Bukkit mob** companion that they can actually see. It is:

- a small friendly flyer (default **Allay**, set by `bedrock-companion-type`),
- persistent, invulnerable, silent, custom-named with its level,
- never targetable by hostile mobs, and won't pick up items.

It follows you with the same follow-distance / teleport-threshold tuning as the fake-player, teleporting to catch up when you outrun it or change worlds, and despawns cleanly on dismiss / quit.

Set `bedrock-real-entity: true` to give **every** owner (including Java) the real-entity companion — useful for testing or if you just prefer a visible mob. Java owners keep the fake-player otherwise. Either way `/companion summon|dismiss|skins|mount|info` work the same way for both.

## Leveling & abilities (FEATURE 2)

Your companion gains XP and **levels up** (config `leveling:`). XP comes from a slow passive trickle while she's summoned, plus a bonus whenever **you kill a mob near her**. Level + XP persist per owner in her memory file.

On level-up she announces with a **title + sound**, her name tag shows the new level, and abilities scale / unlock:

- **movement-speed** — she follows and pathfinds faster at higher levels.
- **heal-aura** — from `heal-aura-unlock-level` (default 3) on, she periodically tops up your HP when you're hurt and nearby, with heart particles and a soft chime.
- **combat-assist** — she re-targets mobs that hit you (and the real-entity companion can never itself be targeted by mobs).

Every ability is individually toggleable. `/companion info` shows your current level, XP toward the next level, and which abilities are active.

## Cosmetic skins GUI (FEATURE 3)

`/companion skins` opens an animated, paginated GUI (shimmering pink/purple border):

- **Java owners** see the skins in your `skins/` folder as head buttons — click one to (re-)skin the fake-player (this also returns her to human form if she's currently a mob).
- **Everyone** sees every living mob as a spawn-egg button — click one to morph the companion into that mob.

Your choice is persisted per owner. The GUI is also reachable from the **Appearances** button in the right-click control panel.

## Mob forms & form combat (FEATURE 5)

The companion can morph into **any living mob** — `/companion form wither`, `/companion form blaze`, `/companion form cat`, or pick from the `/companion skins` menu. Java owners switch back with `/companion form human`; Bedrock owners stay in mob forms (Geyser can't render the fake-player). The form persists across re-summons.

Each form fights with **its own attack style** (config `form-combat:`):

| form | style |
|---|---|
| wither | wither skulls |
| skeleton / stray / bogged / pillager / illusioner | arrows |
| blaze | fire bolts |
| ghast | fireballs |
| warden | sonic boom (slow, heavy) |
| enderman | blink strikes (teleports to the target) |
| creeper | boom bursts (AoE, **zero block damage**) |
| shulker | homing bullets |
| llama / trader llama | spit |
| snow golem | snowballs |
| drowned | tridents |
| witch / evoker / vex / allay | magic zaps |
| everything else | melee |

Targeting is **hostile mobs only** — a wither companion kills zombies, creepers and skeletons near you, but never players, pets, villagers, passive animals, or other players' companions. Guard rails enforce this at the event level too: companion-sourced damage against non-hostiles is cancelled, companion explosions never break blocks, and her own vanilla AI can only ever acquire hostile targets. Damage scales with her level, she prioritizes whatever just attacked you (combat-assist), and her own kills award the same XP as yours.

## Mount mode (FEATURE 4)

`/companion mount` (alias `/companion ride`) lets you **ride the real-entity companion** — she becomes your mount via `addPassenger`. Run it again to hop off. Gated behind `allow-mount: true`.

The NMS fake-player can't be ridden, so Java owners get a message pointing them at the real-entity companion (enable `bedrock-real-entity: true` if you want a rideable companion on Java).

## Skins

The simplest workflow: **drop your skin PNG into `plugins/KawaiiCompanion/skins/`**. On the next `/kc summon` (or server start), the plugin uploads the PNG to [mineskin.org](https://mineskin.org/), saves the result as `<name>.json` next to it, and picks it up. Re-run `/kc summon` once after a few seconds for the upload to finish.

If you'd rather skip the upload step, drop a JSON directly:

```json
{
  "value":     "<base64 textures property from mojang>",
  "signature": "<base64 signature from mojang>"
}
```

The plugin also accepts mineskin's nested response formats (current v2: `skin.texture.data.{value,signature}`; older: `data.texture.{value,signature}` or `texture.{value,signature}`) and the Mojang sessionserver format (`properties[].value` / `signature`), so you can paste a raw mineskin response without trimming.

To turn off auto-upload (e.g. on an offline-mode server or to keep the plugin from reaching out to a 3rd-party API), set `auto-upload-pngs: false` in `config.yml`.

**Auto-detection.** On every `/kc summon` the plugin scans `skins/` and picks a skin in this order:

1. The skin you picked with `/kc skin <name>` (if that file is still there and valid)
2. The `default-skin` named in `config.yml` (if present in the folder)
3. The first valid skin alphabetically
4. Nothing (blank head; outfit still shows)

So the simplest workflow is: drop one `<name>.json` into `skins/` and run `/kc summon`. It just shows up wearing that skin.

**Useful subcommands:**

- `/kc skin` — list every skin in the folder and mark which one is in use
- `/kc skin <name>` — pin to that one specifically
- `/kc skin auto` — clear the override and go back to auto-detection

A blank `skins/example.json` is created on first run. Files with an empty `value` are ignored by the auto-picker.

## Personality

Edit `personality:` in `config.yml`. The string `{name}` is replaced with the companion's current name at request time. Keep it short — long system prompts eat into the reply budget.

```yaml
personality: |
  You are {name}, a kawaii anime-style companion in a Minecraft world.
  Reply briefly (1–3 sentences). Stay in character.
```

After editing, `/kc reload` to apply without a restart.

## Memory

Conversation history is stored per-player at `plugins/KawaiiCompanion/memory/<player-uuid>.yml`. It rolls at `max-history-turns` turns (default 20). `/kc reset` clears it. Only the player's own memory is sent in subsequent requests — different players' companions don't share context.

## API key safety

Treat the key like a password:

- **Don't** commit `config.yml` to a public repo with the key in it.
- Prefer the env var: leave `api-key:` blank, set `DEEPSEEK_API_KEY` in the server's environment (e.g. systemd `Environment=DEEPSEEK_API_KEY=sk-...`).
- Rotate the key on DeepSeek's dashboard if it ever leaks.

If no key is configured, the plugin still loads and the companion still summons + follows — only `/kc say` errors out with "AI key not configured".

## Permissions

| Node | Default | What it does |
|---|---|---|
| `kawaiicompanion.use` | everyone | use `/kc *` for your own companion |
| `kawaiicompanion.admin` | op | `/kc reload` |

## Build

Java 21, Maven 3.6+:

```bash
mvn clean package
```

Or use the bundled `build-all.bat` / `build-all-text.bat` at the repo root.
