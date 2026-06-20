# KawaiiThirst 💧

A survival **thirst** stat that sits *above hunger* in importance — it drains a
little faster than hunger and, when empty, hurts more.

> Minecraft can't add a real bar above the hunger row without a resource
> pack/client mod, so thirst shows as a **boss bar** at the top of the screen
> (Geyser renders it for Bedrock players too).

## How it works
- Thirst drains over time — **faster when sprinting** or in **hot biomes**
  (desert, savanna, badlands, the Nether…).
- Refill by **drinking a water bottle**, **standing in water**, or eating
  **juicy foods** (apples, melon, berries, carrots…).
- **Low** thirst → Slowness. **Critical** → Slowness II + Mining Fatigue.
  **Empty** → dehydration damage until you drink.
- Creative/Spectator players are ignored. Thirst persists per player.

## Commands
- `/thirst` — check your thirst (aliases `/kthirst`, `/water`).
- `/thirst set <amount> [player]` — admin set (`kawaiithirst.admin`).
- `/thirst reload` — reload config.

## Config
`plugins/KawaiiThirst/config.yml` — drain rates, multipliers, restore amounts,
low/critical thresholds, dehydration damage, and the hot-biome list are all
documented inline.

## Build
Java 21 + Maven: `mvn clean package`.
