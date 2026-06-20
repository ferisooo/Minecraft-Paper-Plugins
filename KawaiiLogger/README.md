# KawaiiLogger

Discord webhook event logger with ~40 event types, batching, kawaii emoji, persistent player state, and per-day local log files.

## Features

- Discord webhook for all events (join/quit, deaths, advancements, boss kills, PvP, mob kills, fishing, taming, breeding, shearing, food, potions, elytra, enchanting, rare drops, trades, world changes, structures, biomes, sleep, milestones, birthdays)
- Batching (mining/chopping/crafting/damage/kills/trades/fish/breed/shear) with configurable window
- Per-day local log files at plugins/KawaiiLogger/logs/YYYY-MM-DD.log (UTF-8)
- Persistent player state: first-join dates, milestone tracking, last-known names
- Anti-spam cooldowns per event type (30s damage/consume/enchant, 60s elytra/diamond, 5min structure)
- Material/mob-specific kawaii emojis injected inline (~150 mappings)

## Build

Requires **Java 21** and **Maven 3.6+**.

```bash
mvn clean package
```

The compiled jar will appear in `target/kawaiilogger-1.3.1.jar`.

## Install

1. Drop `kawaiilogger-1.3.1.jar` into your Paper server's `plugins/` directory
2. Restart the server (or `/reload confirm`)
3. The plugin will create `plugins/KawaiiLogger/config.yml` on first run
4. Edit the config, then `/reload confirm` or restart

## Compatibility

- **Paper 1.21.11** (api-version `1.21.11`)
- **Java 21+**
- Bukkit/Spigot servers may work but are untested

## License

MIT — do whatever you want with this~ 💖
