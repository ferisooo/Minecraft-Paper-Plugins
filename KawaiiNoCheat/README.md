# KawaiiNoCheat

Blocks "cheaty" commands for normal players and greets the attempt with a cute
title popup + message + sound. Operators (anyone with `kawaiinocheat.bypass`,
default op) are exempt.

## What it blocks

Default roots: `gamemode`, `give`, `time`, `weather`, `effect`, `enchant`,
`experience`/`xp`, `gamerule`, `setblock`, `fill`, `clone`, `summon`, `clear`,
`tp`/`teleport`, `kill`, `fly`, `speed`, `god`, `heal`, `feed`, `op`, `deop`,
`item`. Edit `blocked-commands` in `config.yml` to taste.

Matching is on the command root only and is namespace-insensitive, so `give`
also blocks `minecraft:give`.

> Only **player-typed** commands are intercepted. The console and command
> blocks are never blocked, so datapacks/automation keep working.

## Config (`config.yml`)

- `block-ops` — `false` (default): players with `kawaiinocheat.bypass` (default op) are exempt. `true`: nobody is exempt — ops get the popup too.
- `blocked-commands` — list of command roots to block.
- `title`, `subtitle`, `chat-message` — the popup text (`{command}` = what they tried). Supports `§`/`&` colour codes.
- `title-fade-in` / `title-stay` / `title-fade-out` — title timing in ticks.
- `play-sound` / `sound` — a `Sound` enum name (e.g. `ENTITY_VILLAGER_NO`).
- `particles` — puff an angry-villager + smoke "nope" cloud around the player on a blocked command (only they see it). Default `true`.

`/knc reload` re-reads the config (perm `kawaiinocheat.admin`, default op).

## Permissions

- `kawaiinocheat.bypass` (default op) — exempt; can run the commands.
- `kawaiinocheat.admin` (default op) — `/knc reload`.

## Build

Java 21 + Maven 3.6+: `mvn clean package` → `target/kawaiinocheat-1.0.0.jar`.
