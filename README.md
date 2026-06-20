# Kawaii Minecraft Plugins ✿

A collection of **32 cute-themed Paper/Spigot plugins** for Minecraft **1.21**.
Each plugin lives in its own folder as a self-contained Maven project, so you can
build only the ones you want and drop the resulting `.jar` files into your
server's `plugins/` folder.

> ### ✨ Only made possible by **[feris](https://mez.ink/ferisooo)** ✨
> Every plugin here was created by **feris** — find more at
> **[mez.ink/ferisooo](https://mez.ink/ferisooo)**.

> Looking for the project background? See [ABOUT.md](ABOUT.md).

---

## Requirements

Before you build or run these plugins you'll need:

| Requirement      | Version / Notes                                                        |
| ---------------- | ---------------------------------------------------------------------- |
| **Java JDK**     | **21** or newer (the plugins compile to Java 21)                       |
| **Apache Maven** | **3.9+** (a bundled copy lives in `apache-maven-3.9.16/`)              |
| **Minecraft server** | **Paper** (recommended) or Spigot, **1.21.x**, API version `1.21` |
| **Git**          | Any recent version, to clone the repository                            |
| **Internet**     | Maven downloads the Paper API and other dependencies on first build    |

Optional, only for the AI-powered plugins (`KawaiiQuests`, `KawaiiMobChat`,
`KawaiiCompanion`):

- A **DeepSeek API key** from <https://platform.deepseek.com/>. Paste it into the
  plugin's `config.yml` (or set the `DEEPSEEK_API_KEY` environment variable where
  supported). **Never commit a real key** — the configs ship with placeholders only.

Check your tools are installed:

```bash
java -version     # should print 21 or higher
mvn -version      # should print 3.9 or higher
git --version
```

---

## Getting the code (clone / pull the GitHub repo)

Clone a fresh copy:

```bash
# Clone over HTTPS
git clone https://github.com/ferisooo/minecraft-plugins.git
cd minecraft-plugins
```

Or, if you use SSH:

```bash
git clone git@github.com:ferisooo/minecraft-plugins.git
cd minecraft-plugins
```

Already have a clone? Pull the latest changes:

```bash
cd minecraft-plugins
git pull origin main
```

---

## Tutorial: building & installing a plugin

Each top-level folder (e.g. `KawaiiHearts/`, `KawaiiShop/`) is an independent
Maven project. To build one:

```bash
# 1. Move into the plugin you want
cd KawaiiHearts

# 2. Build it (skip tests for a faster build)
mvn clean package

# 3. The finished jar appears in target/
#    e.g. target/KawaiiHearts.jar
```

Then install it on your server:

```bash
# 4. Copy the jar into your server's plugins folder
cp target/KawaiiHearts.jar /path/to/your/server/plugins/

# 5. Restart the server (or use /reload) to load it
```

On first launch the plugin writes a `config.yml` into
`plugins/<PluginName>/`. Edit it, then run `/reload` or restart to apply.

### Build all plugins at once

There's no parent aggregator POM, so loop over the folders. From the repo root:

```bash
# macOS / Linux
for d in Kawaii* Herobrine FiveHearts-source; do
  (cd "$d" && mvn -q clean package)
done
```

```powershell
# Windows PowerShell
Get-ChildItem -Directory | Where-Object { Test-Path "$($_.FullName)\pom.xml" } |
  ForEach-Object { Push-Location $_.FullName; mvn -q clean package; Pop-Location }
```

> Windows users: `kawaii-start.bat` launches a helper GUI/menu
> (`kawaii-gui.ps1`) that can auto-update and build the plugins for you.

---

## The plugins

| Plugin | What it does |
| ------ | ------------ |
| **FiveHearts-source** | Forcefully locks all players to 5 hearts. |
| **Herobrine** | Full Herobrine entity system — stalking AI, threat tracking, mysterious structures, and a multi-phase boss encounter. |
| **KawaiiBlockHP** | Shows a depleting HP bar while a block is being mined. |
| **KawaiiCam** | An autonomous cinematic camera that flies intelligent shots and records a replayable track. |
| **KawaiiClaims** | Multi-chunk land claim & protection — claim with a golden shovel, trust friends, toggle flags. |
| **KawaiiCompanion** | AI companion that follows you and chats via DeepSeek, with personality + memory. |
| **KawaiiControlPanel** | In-game chest-GUI control panel to configure every Kawaii plugin live. |
| **KawaiiDragon** | The Ender Dragon grows stronger each time it's defeated. |
| **KawaiiDungeons** | Instanced dungeons with loot, tokens and per-dungeon progression. |
| **KawaiiEnderChest** | Bigger ender chest — 54 slots, per-player, saved to disk. |
| **KawaiiEssentials** | All-in-one essentials — homes, tpa, hub, back, starter kit, and a trash bin. |
| **KawaiiFurnace** | Speeds up furnaces, blast furnaces and smokers, with optional fuel efficiency. |
| **KawaiiGroups** | Online-only player groups with roles, invites, GUIs, group chat and shared hearts. |
| **KawaiiHearts** | Floats a cute pink health bar above every mob's head. ✿ |
| **KawaiiLogger** | Discord webhook logger for Minecraft events. ✨ |
| **KawaiiMobChat** | Mobs respond to player chat via DeepSeek — insult them and they fight back! |
| **KawaiiNights** | Extra hostile mobs spawn any time/weather/biome (more at night); hostiles don't burn in daylight. |
| **KawaiiNoCheat** | Blocks cheat commands for non-bypass players with a cute popup. |
| **KawaiiNoGrief** | Explosions damage & push entities, but blocks survive. |
| **KawaiiPoem** | Plays a custom End-Poem-style scrolling credits message from config. |
| **KawaiiQuests** | AI-generated quests with an animated loot crate reward. Java + Bedrock (Geyser). |
| **KawaiiRTP** | Random teleport that lands on dry, breathable ground a configurable distance away. |
| **KawaiiRecipes** | Unlocks every recipe so the recipe book shows all items. |
| **KawaiiReload** | Reload plugins or restart the server without leaving the game. |
| **KawaiiScoreboard** | Sidebar with online count, world, coords, per-world playtime and edition. |
| **KawaiiSeasons** | In-game-time seasons that change the player, crops and environment. |
| **KawaiiShop** | A skyblock buy/sell shop GUI with a coin economy, priced by rarity. |
| **KawaiiSigns** | Classic `[command]` signs — right-click to run as the clicking player. |
| **KawaiiSkyblock** | Skyblock void world with per-player islands and island management. |
| **KawaiiSparkles** | Server-side visual flair — chest sparkles, footstep particles, animated menus. |
| **KawaiiThirst** | A thirst stat (boss bar) that drains over time and is restored by drinking. Bedrock via Geyser. |
| **KawaiiWorlds** | Multi-world manager — create, teleport, load, unload, delete worlds (op-only). |

Many plugins also ship their own `README.md` with more detail — check inside the
folder.

---

## License & contributing

These plugins are shared as-is for the community. Open an issue or pull request
on GitHub if you find a bug or want to contribute. Have fun and stay kawaii~ ✿
