# KawaiiNoGrief

Explosions still damage entities and knock them around — they just stop breaking blocks.

## Why this exists

`/gamerule mobGriefing false` only covers **mob-driven** block changes (creeper detonations, ghast fireballs landing, enderman block pickups, …). Player-placed primed TNT, beds in the nether, end crystals, and a few others use a different code path that never checks `mobGriefing`. So `/gamerule mobGriefing false` + a player-placed TNT = still a crater.

This plugin clears the explosion's block list at the event level. Damage and knockback to entities are computed before the event fires, so emptying the block list doesn't disturb them.

## Install

Drop `kawaiinogrief-1.0.0.jar` in `plugins/`, restart. Defaults: every explosion source has block damage off. To re-enable for a specific source, edit `plugins/KawaiiNoGrief/config.yml`:

```yaml
block-damage:
  tnt: true          # let TNT break blocks again
  creeper: false     # but creepers still won't
```

`/knogrief reload` (op-only) reloads without a restart.

## Common source keys

| Source | Key |
|---|---|
| TNT | `tnt` |
| TNT minecart | `minecart_tnt` |
| Creeper | `creeper` |
| Wither (passive damage) | `wither` |
| Wither skull projectile | `wither_skull` |
| Ghast fireball | `fireball` |
| Blaze small fireball | `small_fireball` |
| End crystal | `ender_crystal` or `end_crystal` |
| Breeze wind-charge | `breeze_wind_charge` / `wind_charge` |
| Bed in nether/end | `bed` |
| Respawn anchor in overworld/end | `respawn_anchor` |

Anything not listed falls back to `default-block-damage` (default: `false`).

## Build

Java 21, Maven 3.6+:

```bash
mvn clean package
```
