# KawaiiDragon 🐉

The **Ender Dragon grows stronger every time it's defeated.** A persistent
global *defeats* counter sets the dragon's **tier**; each tier raises its **max
health** and the **damage it deals**. Beat it, and the next one comes back
harder.

## How it works
- **Tier** = defeats + 1. Tier 1 is the base dragon (~200 HP); after each kill
  the next dragon gains `health-per-tier` HP and `damage-per-tier` extra damage.
- Scaling is applied when a dragon **spawns**, and **lazily on first hit** — so
  the very first dragon, and any that loaded before the plugin, get buffed too.
- On defeat: the counter saves, dropped XP scales up, and chat announces the
  next tier.

## Commands
- `/dragon` — show current defeats / next tier (aliases `/kdragon`, `/enderdragon`).
- `/dragon setdefeats <n>` — admin set the counter (`kawaiidragon.admin`).
- `/dragon reload` — reload config.

## Config
`plugins/KawaiiDragon/config.yml`: `base-health`, `health-per-tier`,
`health-cap`, `damage-per-tier`, `bonus-xp-per-tier`, `max-tier`, `announce`.

## Extending it ("more detail later")
Tier-gated **special abilities** are stubbed in `onTierAbilities(dragon, tier)`
— that's the hook to add adds/minions, breath buffs, glow, extra crystals,
phases, themed loot, etc. Health + damage + XP scaling are the v1 framework.

## Build
Java 21 + Maven: `mvn clean package`.
