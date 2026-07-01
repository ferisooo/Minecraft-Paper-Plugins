# Kawaii Minecraft Plugins ✿

**32 cute-themed Paper/Spigot plugins** for Minecraft **1.21**.

> ✨ Imagined & directed by **[feris](https://mez.ink/ferisooo)** ・ 🤖 code generated with **Claude** (Anthropic) under feris's direction.

📖 [ABOUT](ABOUT.md) ・ ⚖️ [TERMS](TERMS.md) ・ 🔒 [PRIVACY](PRIVACY.md) ・ 📄 [LICENSE](LICENSE)

---

## TL;DR — get running

1. **Install [Java 21](https://adoptium.net/)** (Temurin 21 LTS). Check with `java -version`.
2. **Get the code** — download the ZIP from GitHub, or `git clone`.
3. **Build** — Windows: double-click `kawaii-start.bat`. Mac/Linux:
   ```bash
   for d in Kawaii* Herobrine FiveHearts-source; do
     (cd "$d" && ../apache-maven-3.9.16/bin/mvn -q clean package) && echo "✓ $d"
   done
   ```
   (First build is slow — Maven downloads the Paper API once.)
4. **Install** — copy the `.jar`s you want from each `<Plugin>/target/` into your
   [Paper 1.21](https://papermc.io/downloads/paper) server's `plugins/` folder.
5. **Run** the server, then **Direct Connect** to `localhost` in Minecraft. ✿

Each plugin makes a `plugins/<Plugin>/config.yml` on first run — edit it and `/reload`.

> **AI plugins** (`KawaiiQuests`, `KawaiiMobChat`, `KawaiiCompanion`) optionally use
> [DeepSeek](https://platform.deepseek.com/) — paste your own API key into their
> `config.yml`. They work without a key too. Treat keys like passwords; never commit them.

---

## The plugins

| Plugin | What it does |
| ------ | ------------ |
| **FiveHearts-source** | Locks all players to 5 hearts. |
| **Herobrine** | Full Herobrine entity — stalking AI, structures, multi-phase boss fight. |
| **KawaiiBlockHP** | Depleting HP bar while mining a block. |
| **KawaiiCam** | Autonomous cinematic camera with replay recording. |
| **KawaiiClaims** | Multi-chunk land claim & protection with trust and flags. |
| **KawaiiCompanion** | AI companion that follows you and chats via DeepSeek. |
| **KawaiiControlPanel** | In-game chest-GUI to configure every Kawaii plugin live. |
| **KawaiiDragon** | The Ender Dragon grows stronger each time it's defeated. |
| **KawaiiDungeons** | Instanced dungeons with loot, tokens and progression. |
| **KawaiiEnderChest** | 54-slot per-player ender chest, saved to disk. |
| **KawaiiEssentials** | Homes, tpa, hub, back, starter kit, trash bin. |
| **KawaiiFurnace** | Speeds up furnaces, blast furnaces and smokers. |
| **KawaiiGroups** | Player groups with roles, invites, group chat, shared hearts. |
| **KawaiiHearts** | Cute pink health bar above every mob. ✿ |
| **KawaiiLogger** | Discord webhook logger for ~40 event types. ✨ |
| **KawaiiMobChat** | Mobs reply to chat via DeepSeek — insult them and they fight back! |
| **KawaiiNights** | Extra hostile mobs; hostiles don't burn in daylight. |
| **KawaiiNoCheat** | Blocks cheat commands with a cute popup. |
| **KawaiiNoGrief** | Explosions hurt entities but blocks survive. |
| **KawaiiPoem** | Custom End-Poem-style scrolling credits. |
| **KawaiiQuests** | AI-generated quests with an animated loot crate (Java + Bedrock). |
| **KawaiiRTP** | Random teleport onto safe, dry ground. |
| **KawaiiRecipes** | Unlocks every recipe in the recipe book. |
| **KawaiiReload** | Reload plugins / restart the server in-game. |
| **KawaiiScoreboard** | Sidebar: online count, world, coords, playtime, edition. |
| **KawaiiSeasons** | In-game seasons affecting player, crops and environment. |
| **KawaiiShop** | Skyblock buy/sell shop GUI with a coin economy. |
| **KawaiiSigns** | `[command]` signs — right-click to run as the clicker. |
| **KawaiiSkyblock** | Void world with per-player islands. |
| **KawaiiSparkles** | Chest sparkles, footstep particles, visual flair. |
| **KawaiiThirst** | Thirst boss bar restored by drinking (Bedrock via Geyser). |
| **KawaiiWorlds** | Multi-world manager — create, tp, load, unload, delete. |

Most plugins ship their own `README.md` with more detail.

---

## Credits & legal

- **feris** — [mez.ink/ferisooo](https://mez.ink/ferisooo) — every idea, theme and creative direction.
- **Claude** (Anthropic) — generated the Java code under feris's direction.

**License:** MIT ([LICENSE](LICENSE)). Forks welcome — please credit **both** feris and Claude ([TERMS](TERMS.md)). feris collects **no** user data ([PRIVACY](PRIVACY.md)). Not affiliated with Mojang, Microsoft, Paper, Spigot, DeepSeek or Anthropic.

Stay kawaii~ ✿
