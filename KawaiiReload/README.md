# KawaiiReload

In-game plugin reloader so you don't have to /stop the server every time you ship a new build of one of your plugins.

## Use

`/kreload <plugin>` does a **live config re-read** on the named plugin via `reloadConfig()` — it re-reads `config.yml` from disk without disturbing the running plugin, which covers most "tweaked the config and want it live" cases.

It does **not** disable+enable the plugin. On modern Paper, `disablePlugin()` closes the plugin's classloader (its JAR) and `enablePlugin()` reuses that same closed loader, so the plugin comes back "enabled" but throws `IllegalStateException: zip file closed` the next time it loads a class — an earlier version did this and bricked plugins until a restart.

It also can't re-read the plugin's JAR: modern Paper (≥1.20.5) only registers plugin providers during server boot, so a runtime `loadPlugin(file)` errors out with *"Plugin didn't load any plugin providers?"*. **For new code on disk, use `/kreload server`** to do a full restart.

| Subcommand | What it does |
| --- | --- |
| `/kreload <name>` | re-read that plugin's `config.yml` (live) |
| `/kreload all` | re-read every plugin's config (KawaiiReload itself is skipped) |
| `/kreload config <name>` | same as `<name>` — calls `reloadConfig()` on the plugin |
| `/kreload data` | `Bukkit.reloadData()` — recipes, advancements, loot tables |
| `/kreload server` | full server restart via `Bukkit.spigot().restart()` (needs a `restart-script` in `spigot.yml`) — required to pick up new code |
| `/kreload list` | show every plugin and its enabled state |
| `/kreload self` | reload KawaiiReload's own config |

Aliases: `/kr`, `/kawaiireload`.

## Caveats

- A config re-read only reloads `getConfig()`. Plugins that cache config values into fields at `onEnable` (most do) won't fully pick up the change unless they expose their own reload command (e.g. `/kc reload`, `/kmcreload`) that re-applies it — run that, or `/kreload server` for a clean restart.
- KawaiiReload can't reload itself. Use `/kreload self` for its config or `/kreload server` for code.

## Permission

`kawaiireload.use` (default: op) is required for every subcommand.

## Config

`plugins/KawaiiReload/config.yml`:
- `allow-server-restart` — set to `false` to disable `/kreload server`
- `allow-mass-reload` — set to `false` to disable `/kreload all`
- `restart-delay-ticks` — ticks between the chat reply and the restart command (default 20 = 1s)

## Build

Java 21 + Maven 3.6+:
```bash
mvn clean package
```
