# KawaiiControlPanel

An in-game chest-GUI control panel for editing **every other Kawaii plugin's
config live** — no file editing, no restart.

## Usage

`/kpanel` (aliases: `/kcp`, `/kconfig`, `/kawaiipanel`) — requires
`kawaiicontrolpanel.admin` (default: op).

- **Hub menu** — one icon per loaded `com.ferisooo` plugin that has an
  editable config. Click a plugin to open its settings.
- **Settings menu** — every config value the plugin exposes, auto-discovered
  from its live config so paths and current values are always correct:
  - **Booleans** → click to toggle (lime = on, gray = off).
  - **Numbers** → left-click `+`, right-click `-`, hold **Shift** for ×10.
  - **Text / lists** → shown read-only (edit these in `config.yml`). Secrets
    (api-key, token, webhook, …) are masked.
  - Paginated when a plugin has more than 45 settings.

## How "live" works

On each edit the value is written to the target plugin's config and saved,
then a **debounced reload** applies it. To apply, we dispatch the plugin's own
reload command (e.g. `/kc reload`, `/kmcreload`); plugins without one get a
safe `reloadConfig()` re-read. Rapid edits to one plugin collapse into a single
reload.

> **We never `disablePlugin` + `enablePlugin`.** On modern Paper that closes
> the plugin's classloader (its JAR), and re-enabling reuses the closed loader,
> so the plugin throws `IllegalStateException: zip file closed` the next time
> it loads a class. A config change never needs a new classloader, so we only
> ever re-read config — which is safe and leaves the running plugin intact.

## Config (`config.yml`)

| Key | Default | Meaning |
| --- | --- | --- |
| `auto-reload` | `true` | Apply edits live via the plugin's reload command / safe config re-read. |
| `reload-delay-ticks` | `40` | Debounce window (20 = 1s). |
| `no-auto-reload` | `[]` | Editable but not auto-reloaded; saved for a manual reload. Plugins without a reload command only get a config re-read, so field-cached settings there may need a restart anyway. |

`/kpanel reload` re-reads this plugin's own config.

## Notes

- Discovers plugins by `com.ferisooo` main package, so only the Kawaii family
  is exposed — not arbitrary server plugins.
- FiveHearts has no `config.yml` (hardcoded), so it doesn't appear.
- Saving a config rewrites it via the Bukkit API; on modern Paper comments are
  retained.

## Build

Requires **Java 21** and **Maven 3.6+**.

```bash
mvn clean package
```

Jar appears at `target/kawaiicontrolpanel-1.0.0.jar`.
