# KawaiiMobChat

Mobs respond to player chat using the DeepSeek AI API. Insulting them turns mood angry → setTarget(player). Kindness flips them friendly → un-targets you.

## Features

- AsyncChatEvent listener detects when player is looking at a mob (within 12 blocks)
- Async DeepSeek API call with per-mob persona system prompt (~50 mob types)
- Structured JSON output (mood + reply) with response_format and lenient parser
- 7-mood vocabulary (angry, scared, sad, neutral, curious, friendly, excited) — each with its own color, particle, sound pitch, and behavior, instead of a forced 3-way analysis
- Mood-based behavior: angry hostile → mob.setTarget(player); angry/scared passive → flee with knockback + speed boost; friendly/excited → clear target; sad/curious → cosmetic only
- Awareness context: every reply is given the mob's HP, time of day, biome, what the player is holding, distance, current target, and how many of its kind the player has killed before
- Player reputation: per-player kill counts per mob type, persisted to `reputation.yml` and fed to the AI so a pig that has watched you murder its kin reacts accordingly
- Vanilla-sound cues: each reply plays the mob's appropriate sound (angry/hurt/ambient) at a mood-specific pitch
- Mob-to-mob banter: a nearby fellow of the same type may chime in with a one-line reaction (configurable chance, radius, cooldown)
- Particle feedback per mood: ANGRY_VILLAGER, SMOKE, SPLASH, NOTE, HEART, HAPPY_VILLAGER — every mob reacts visibly regardless of class
- Per-mob conversation memory (last N turns, idle-expires after 10 min) sent to the API as prior messages so the mob remembers what was just said
- Floating chat bubbles above the mob's head (TextDisplay, follows the mob, auto-despawns)
- Per-player cooldown + in-flight gate to control API spend
- Thinking mode disabled, max-tokens 200, temperature 0.9
- /kmcreload command (kawaiimobchat.admin permission)

## Build

Requires **Java 21** and **Maven 3.6+**.

```bash
mvn clean package
```

The compiled jar will appear in `target/kawaiimobchat-1.5.0.jar`.

## Install

1. Drop `kawaiimobchat-1.5.0.jar` into your Paper server's `plugins/` directory
2. Restart the server (or `/reload confirm`)
3. The plugin will create `plugins/KawaiiMobChat/config.yml` on first run
4. Edit the config, then `/reload confirm` or restart

## Compatibility

- **Paper 1.21.11** (api-version `1.21.11`)
- **Java 21+**
- Bukkit/Spigot servers may work but are untested

## License

MIT — do whatever you want with this~ 💖
