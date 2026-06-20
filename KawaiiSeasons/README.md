# KawaiiSeasons 🍃

Seasons driven by **in-game time** (not real-world time). The calendar reads the
primary world's monotonic game time, so seasons advance as the world is actually
played — Spring → Summer → Autumn → Winter, each lasting `days-per-season`
in-game days, then repeating.

## What each season changes
- **Player**
  - *Winter* — being exposed to the sky chills you: a frost vignette (no freeze
    damage) plus Slowness.
  - *Summer* — heat burns energy faster (hunger drops quicker). Pairs with
    KawaiiThirst.
- **Crops** — growth ticks are randomly skipped, so farming **slows in Autumn**
  and **nearly freezes in Winter**.
- **Environment**
  - Seasonal ambient particles (cherry petals in Spring, falling blossoms in
    Autumn, snowflakes in Winter).
  - **Winter forces a storm** in all applicable worlds, **and simulates snow** by
    laying thin snow around players — so **it snows in every biome, even
    deserts**, with nearby water freezing to ice.

## 🛡️ Build-friendly by default
- **Snow only settles on natural ground** (grass, dirt, sand, stone…), so it
  never coats your wood/concrete/brick builds (`snow-on-natural-ground-only`).
- **`freeze-water` is off by default** — it breaks ponds, water elevators and
  item-stream farms, so opt in only if you want it.
- **Summer heat only affects players in the open sky**, gently — shade/indoors
  is a refuge.
- **Crops only slow under open sky**, so a roofed/lit **greenhouse keeps growing**
  through winter.
- The current season is announced **on join** and shown on the **KawaiiScoreboard**
  sidebar (if installed) via a no-dependency PDC bridge.

## ❄ About "snow in every biome"
Clients decide whether to render rain/snow from **biome temperature**, so a
server can't make a desert *render* snowfall directly. KawaiiSeasons works around
this by **placing real snow layers around each player** during winter. Caveat:
in hot biomes the game may melt placed snow quickly (it can flicker) — the forced
storm + snowflake particles keep the wintry feel there regardless. Tune
`winter.snow-radius` for coverage vs. performance.

## Commands
- `/season` — show the current season and day (aliases `/kseason`, `/seasons`).
- `/season set <spring|summer|autumn|winter>` — admin set (`kawaiiseasons.admin`).
- `/season reload` — reload config.

## Config
`plugins/KawaiiSeasons/config.yml` — `days-per-season`, update rate, per-season
player/crop effects, and the winter weather/snow options are documented inline.
The Nether and the End are always excluded; `enabled-worlds` (empty = all
overworld-type worlds) limits where seasons apply.

## Build
Java 21 + Maven: `mvn clean package`.
