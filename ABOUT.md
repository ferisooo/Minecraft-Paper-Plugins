# About Kawaii Minecraft Plugins

> ### ✨ Only made possible by **[feris](https://mez.ink/ferisooo)** ✨
> This whole project was created, designed, and built by **feris**.
> Find more at **[mez.ink/ferisooo](https://mez.ink/ferisooo)**.

## What is this?

**Kawaii Minecraft Plugins** is a growing collection of **32 small, focused
server plugins** for Minecraft **1.21** built on the **Paper** API. The theme is
"kawaii" (Japanese for *cute*) — the plugins lean into friendly visuals, pink
hearts, sparkles, and gentle messaging — but under the hood they cover a wide
range of practical server features.

The goal is simple: a library of **drop-in, single-purpose plugins** you can mix
and match. Want floating health bars over mobs? Add `KawaiiHearts`. Want land
protection? Add `KawaiiClaims`. Want an AI companion that walks beside you and
talks? Add `KawaiiCompanion`. Each one works on its own — there is no required
core plugin and no hard dependency between them.

## Philosophy

- **One job per plugin.** Every folder is its own Maven project producing one
  `.jar`. Install only what you need; skip the rest.
- **Configurable, not opinionated.** Plugins write a `config.yml` on first run so
  server owners can tune behavior without recompiling.
- **Cross-edition friendly.** Several plugins (e.g. `KawaiiQuests`,
  `KawaiiThirst`) are built to work for **Bedrock** players too, via Geyser.
- **Modern stack.** Targets Java 21 and Paper 1.21.x to take advantage of the
  current API.

## Categories at a glance

- **Gameplay & survival** — KawaiiHearts, KawaiiBlockHP, KawaiiNights,
  KawaiiSeasons, KawaiiThirst, KawaiiDragon, FiveHearts.
- **World & land** — KawaiiClaims, KawaiiWorlds, KawaiiSkyblock, KawaiiRTP,
  KawaiiNoGrief.
- **Quality of life** — KawaiiEssentials, KawaiiEnderChest, KawaiiFurnace,
  KawaiiRecipes, KawaiiReload, KawaiiScoreboard, KawaiiSigns, KawaiiControlPanel.
- **Social & economy** — KawaiiGroups, KawaiiShop, KawaiiDungeons, KawaiiQuests.
- **Cosmetic & flair** — KawaiiSparkles, KawaiiCam, KawaiiPoem.
- **AI-powered** — KawaiiCompanion, KawaiiMobChat, KawaiiQuests (optional DeepSeek
  API key).
- **Admin & moderation** — KawaiiNoCheat, KawaiiLogger.
- **Specials** — Herobrine (a full multi-phase boss/stalker system).

## A note on AI features

A few plugins can call the **DeepSeek** language model for generated text
(quests, mob chatter, companion conversation). These are entirely optional and
require your own API key. The repository ships **only placeholders** for keys —
no real credentials are stored here. Keep your key private and never commit it.

## Getting started

See [README.md](README.md) for the full list of requirements, how to clone the
repository, and a step-by-step tutorial for building and installing a plugin.

## Credits

**Only made possible by feris** — **[mez.ink/ferisooo](https://mez.ink/ferisooo)**.
None of this would exist without them. 💖

Stay kawaii~ ✿
