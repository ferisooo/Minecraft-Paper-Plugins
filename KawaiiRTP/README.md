# KawaiiRTP

`/krtp` — random teleport that lands the player on dry, breathable ground a configurable distance away. No spawning waist-deep in the ocean. No spawning in lava. No spawning in the void.

## Install

Drop `kawaiirtp-1.0.0.jar` into `plugins/`, restart. Players run `/krtp` (aliases: `/rtp`, `/kawaiirtp`, `/kwrtp`). Defaults teleport between 500 and 2000 blocks from the current position with a 30-second cooldown.

## What "safe" means

For every random candidate the plugin checks:

- the highest non-leaf block isn't water, lava, or any other liquid
- the standing block isn't on the unsafe list (lava, fire, magma, cactus, sweet berry bush, wither rose, campfires, powder snow, end/nether portals, end gateways, light blocks, pressure plates)
- the standing block is actually solid (no falling through grass / vines / snow layers)
- the next 2 blocks above the destination are clear and non-liquid (configurable via `required-headroom`)
- the biome isn't on the blocked list (deep dark + every ocean variant by default) and — if `avoid-ocean: true` — its name doesn't contain `ocean`
- the Y of the standing block is within the configured `min-y..max-y` (keeps you out of the void and off the nether ceiling)
- the spot is inside the world border

If a candidate fails any check, another roll is taken. After `max-attempts` (default 40) the player gets a "couldn't find a safe spot" message and stays put — they don't lose their cooldown on a failure.

## Config

`plugins/KawaiiRTP/config.yml` — every setting is documented inline. The interesting ones:

```yaml
min-distance: 500          # ring inner radius (blocks)
max-distance: 2000         # ring outer radius (blocks); set == min for fixed
max-attempts: 40           # how many candidates to roll before giving up
cooldown-seconds: 30       # 0 = no cooldown
avoid-ocean: true          # block any biome whose name contains "ocean"
avoid-water: true          # block landing on water surfaces (lakes, rivers)
allowed-worlds: [ world ]  # only these world names accept /krtp
blocked-worlds:            # never teleport into these even if asked
  - world_nether
  - world_the_end
min-y: 50                  # destination Y must be >= this
max-y: 250                 # destination Y must be <= this
required-headroom: 2       # clear blocks above the standing tile
teleport-effects: true     # portal puff + whoosh on leave; reverse-portal,
                           # firework, sound + "✦ Teleported ✦" title on arrival
```

`/krtp reload` (op-only via `kawaiirtp.admin`) re-reads the config without a restart.

## Permissions

| Node | Default | What it does |
|---|---|---|
| `kawaiirtp.use` | everyone | run `/krtp` |
| `kawaiirtp.admin` | op | `/krtp reload` |
| `kawaiirtp.bypass-cooldown` | op | skip the per-player cooldown |

## How it picks a spot

A random angle and distance are rolled inside the configured ring (uniform over the annulus area, so the outer ring isn't underweighted). The chunk at that x/z is loaded asynchronously via Paper's `World#getChunkAtAsync`, then the safety checks above run on the main thread. Failed candidates re-roll on the next tick instead of stalling the server.

Distance is uniform over the **area** of the ring, not over the radius, so spots near `max-distance` and spots near `min-distance` are equally likely per square block of land — no clustering at the inner edge.

## Build

Java 21, Maven 3.6+:

```bash
mvn clean package
```

Or use the bundled `build-all.bat` / `build-all-text.bat` at the repo root.
