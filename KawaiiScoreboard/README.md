# KawaiiScoreboard

Per-player sidebar with the basics:

- **Players** — current online count / server max
- **World** — the player's current world name
- **X / Y / Z** — block-aligned coordinates (toggleable)
- **Time here** — per-world playtime (toggleable)
- **Quest / Goal** — your active KawaiiQuest and its progress, shown only while you have one (toggleable)
- **Edition** — Java or Bedrock (auto-detected via Geyser/Floodgate UUID, no hard dependency)

The quest rows read straight off the player (KawaiiQuests mirrors the active quest
into the player's persistent data), so there's **no hard dependency** — if
KawaiiQuests isn't installed, those rows simply never appear.

## Use

`/kscoreboard` (alias `/ksb`, `/kawaiiscoreboard`) toggles your sidebar on/off. Subcommands:

| Subcommand | Effect |
| --- | --- |
| `/ksb on` | show your sidebar |
| `/ksb off` | hide your sidebar (preference sticks across joins) |
| `/ksb toggle` | flip whichever state you're in (this is the default with no args) |
| `/ksb reload` | re-read config.yml (ops/`kawaiiscoreboard.admin`) |

## Bedrock detection

No Floodgate dependency required. We treat a player as Bedrock if either:
- their UUID has zero in the most-significant 64 bits (Floodgate's offline-UUID format), or
- their name starts with `.` (Floodgate's optional linked-player prefix).

If you're using a different bridge, edit `isBedrock` in the source.

## Config

`plugins/KawaiiScoreboard/config.yml`:

- `update-ticks` — refresh interval in ticks (default 10 = 2×/sec)
- `show-on-join` — auto-attach the sidebar on join (players can `/ksb off` to opt out)
- `show-coords` — set to `false` for a 4-row sidebar without X/Y/Z
- `show-playtime` — show/hide the per-world "Time here" row
- `show-quest` — show/hide the active KawaiiQuest rows (default `true`)
- `show-season` — show/hide the current KawaiiSeasons season (default `true`)
- `title` — sidebar title, supports `§` color codes
- `animated-title` — sweep a pink→purple→white shimmer across the title (default `true`)

## Permissions

- `kawaiiscoreboard.use` — toggle your own sidebar (default: everyone)
- `kawaiiscoreboard.admin` — `/ksb reload` (default: op)

## Build

Java 21 + Maven 3.6+:
```bash
mvn clean package
```
