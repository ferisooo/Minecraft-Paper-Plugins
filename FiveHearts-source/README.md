# FiveHearts

Caps players at 5 hearts (10 HP) and 5 drumsticks (food level 10). Locks via attribute + scheduled enforcement.

## Features

- Sets max health to 10 HP (5 hearts) on join, respawn, and twice per second
- Caps food level at 10 (5 drumsticks)
- Listens to FoodLevelChangeEvent at HIGHEST priority to prevent overshoot
- Custom regeneration: a fed player (food at the 5-drumstick cap) heals back to 5 hearts at ~0.5 heart/sec. Food is capped below vanilla's regen threshold, so this replaces natural regen. Healing is not blocked when the saturation buffer runs out, so you always recover fully after a mob hit.
- Uses the version-proof `setMaxHealth()` so it works across all 1.21.x builds (avoids the `MAX_HEALTH`/`GENERIC_MAX_HEALTH` attribute rename)

## Build

Requires **Java 21** and **Maven 3.6+**.

```bash
mvn clean package
```

The compiled jar will appear in `target/fivehearts-1.0.0.jar`.

## Install

1. Drop `fivehearts-1.0.0.jar` into your Paper server's `plugins/` directory
2. Restart the server (or `/reload confirm`)


## Compatibility

- **Paper 1.21–1.21.11** (api-version `1.21`)
- **Java 21+**
- Bukkit/Spigot servers may work but are untested

## License

MIT — do whatever you want with this~ 💖
