# Herobrine

A full-scale **Herobrine** entity system for Paper 1.21.x. He stalks players from
the treeline, builds ominous structures, watches your every move through a
**threat** system, and — when conditions align — awakens as a multi-phase boss.

## What he is

Herobrine renders as a real **player NPC** (a Steve skin with a glowing outline
for the unnatural "white-eyed" aura) driven entirely by packets — no Citizens,
no NMS build dependency. Because he's packet-driven he has no server hitbox, so
his **500 HP is tracked logically** and your melee hits are detected with an
arm-swing ray-trace. He has full knockback resistance (no physics to knock back),
follow range of 128 blocks, and a movement speed of 0.45.

> Want a literal Herobrine skin (with the white eyes baked in)? Paste a base64
> texture value + signature from [mineskin.org](https://mineskin.org/) into
> `general.steve-skin-texture` / `steve-skin-signature`. Left blank he's the
> default Steve plus the glowing aura.

## Systems

- **Passive stalking** — random sightings 20–60 blocks away that stare for a few
  seconds and vanish. More frequent at night and during storms, and the higher
  your threat.
- **Threat (0–100)** — climbs as you mine, linger underground, sleep, die, and
  range far from spawn; decays slowly when you behave. Higher threat means more
  sightings, more anomalies, more aggression, and a higher boss chance.
- **Mysterious structures** — sand pyramids, netherrack crosses, redstone-torch
  tunnels, leafless trees, and hidden underground passages built near you.
- **Active hunting** — triggered by sleeping, deep caves, isolation,
  thunderstorms, or high threat. He closes in and uses his abilities.
- **Abilities** — Shadow Teleport (behind you, 15s cd), Lightning Strike
  (~8 hearts), Hallucinations (fake Herobrines + footsteps), Darkness Pulse
  (blindness/darkness/slowness), and Block Manipulation (torches, netherrack,
  warning signs, snuffed lights).
- **Boss encounter** — at midnight, during a thunderstorm, with threat over the
  threshold. A **"HEROBRINE HAS AWAKENED"** boss bar tracks three phases:
  1. basic attacks + teleportation,
  2. lightning, hallucinations, darkness pulse,
  3. faster, frequent teleports, and **Shadow Minions** (Wither Skeletons with
     custom stats, particle trails, and a temporary lifespan).
- **Re-targeting without reset** — if his target logs off or leaves mid-hunt or
  mid-fight, he picks the next victim and **keeps his progress** (the boss keeps
  its current health and phase). He only stops when he kills the target.
- **Ambient dread** — cave sounds, fog particles, panicking animals, doors that
  creak open on their own, and compass interference while he's near.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/herobrine spawn` | Spawn the stalker watching you | `herobrine.spawn` |
| `/herobrine despawn` | Remove the active Herobrine | `herobrine.spawn` |
| `/herobrine hunt [player]` | Begin an active hunt | `herobrine.spawn` |
| `/herobrine boss [player]` | Start the boss encounter | `herobrine.boss` |
| `/herobrine threat [player] [value]` | View or set threat / stats | `herobrine.admin` |
| `/herobrine reload` | Reload `config.yml` | `herobrine.reload` |

`herobrine.admin` (default: op) implies all of the above.

## Persistence

Threat levels, per-player statistics (mining time, underground time, sleep
count, deaths, max distance from spawn), and the active-target set are saved to
`data.yml` (autosaved every ~5 minutes and on disable).

## Integration (KawaiiCompanion)

Herobrine exposes a small public API, `com.ferisooo.herobrine.HerobrineService`
(`isActive()`, `getLocation()`, `getHealth()`, `isBoss()`,
`damage(amount, sourceName)`), so other plugins can detect and fight him even
though he has no real hitbox.

**KawaiiCompanion** uses it: when a live Herobrine appears within a companion's
guard radius (of the companion *or* its owner), the companion breaks off, walks
up, and whittles down his HP with its weapon until he's gone — announcing it in
its chat bubble. Toggle with `fight-herobrine` in KawaiiCompanion's `config.yml`
(default on). It's a soft dependency: harmless if either plugin is absent.

## Building

```
mvn -DskipTests package
```

Produces `target/herobrine-1.0.0.jar`. Requires the Paper API from
`repo.papermc.io` (same as every other plugin in this repo).

## Notes & limits

- The NPC is visible to Java clients; Bedrock (Geyser) clients can't render fake
  players, so they'll experience the sounds, structures, abilities, and boss bar
  but not see his body.
- Everything is configurable in `config.yml` — spawn chances, cooldowns, damage,
  threat thresholds, structure frequency, boss settings, and messages.
