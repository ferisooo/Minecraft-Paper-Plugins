# KawaiiNights 🌙

The world is always dangerous. KawaiiNights spawns **extra hostile mobs around
each player** — on top of normal spawns — at **any time, weather, or biome**,
with **more at night**. And because *zombies don't burn in real life*, hostile
mobs **no longer catch fire in daylight**, so daytime spawns stick around.
Vanilla spawn rates are **left untouched**; this just adds pressure.

## Safe by design
- **Lit areas are safe** — mobs only spawn where **block light ≤ `max-block-light`**
  (7 by default), so a torch/lantern-lit base won't get swarmed.
- **Not in your face** — spawns happen in a ring (`min-radius`..`max-radius`)
  away from you, on solid ground with headroom.
- **No runaway swarms** — skips a player who already has `nearby-hostile-cap`
  hostiles around them.
- **More at night** — the baseline count is multiplied by `night-multiplier`
  after dusk (and during thunderstorms).
- **No sunlight burning** — `prevent-sunlight-burning` stops hostiles igniting in
  daylight; fire, lava and flaming arrows still hurt them.
- **Left alone:** Peaceful difficulty, creative/spectator players, the Nether and
  the End, and unloaded chunks.

## ⚔ Raids
Occasionally at night a **single-species horde** descends on everyone in the
world — *all* zombies, *all* skeletons, etc. Each night rolls a random number of
raids (`raids.per-night-min`..`per-night-max`), fired at random points through
the night. Fully tunable:
- **How many per night** — `per-night-min` / `per-night-max`.
- **How big** — `mob-count-per-player`.
- **How close** — `min-radius` / `max-radius`.
- **Which species** — `raids.mobs` (each raid is all of one).
- **Reach** — `ignore-light` (raids can reach lit bases by default).
- **Drama** — `announce` title + `sound`.
- **No escape** — `block-sleep`: you can't sleep while a raid rages, and if one
  is scheduled tonight, trying to sleep **summons it now** instead of skipping
  the night — so the raid happens whether you sleep or not. Survive it, then rest.
- **Reaches caves** — raids spawn near each player's own level, so hiding
  underground doesn't save you.

> **Nether / End:** raids (and the trickle spawner) are overworld-only, so
> they don't fire there — and you can't sleep there anyway. A scheduled
> overworld raid still strikes everyone who's *in* the overworld; ducking into
> the Nether/End is the one way to sit a raid out.

## Commands
- `/knights` — info.
- `/knights raid [mob]` — admin: trigger a raid now (random species, or the one
  you name, e.g. `/knights raid skeleton`).
- `/knights reload` — reload config (`kawaiinights.admin`).

## Config
`plugins/KawaiiNights/config.yml` — cadence, mobs-per-player, spawn ring, light
threshold, nearby cap, the mob list, and `enabled-worlds` are documented inline.

> Tip: pairs naturally with KawaiiSeasons — if you later want winter nights to be
> *extra* deadly, this is the plugin to tune.

## Build
Java 21 + Maven: `mvn clean package`.
