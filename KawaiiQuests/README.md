# KawaiiQuests ✿

AI-generated quests with an animated loot crate reward. Players open a cute
menu, pick **Easy / Medium / Hard / Brutal**, and an AI (DeepSeek) writes them a
quest **and** picks a fitting reward for it (validated so it can't hand out junk;
falls back to the config loot table if the AI's pick is invalid). The AI is also
told not to reuse recent targets, so quests stay varied per tier.
Finish the objective and a CS:GO-style loot crate spins up the reward — the
harder the quest, the better the loot.

**Bedrock-friendly:** every screen is a normal chest GUI, so
[Geyser](https://geysermc.org/) translates it for Bedrock players automatically
— no extra setup.

## How it plays

1. `/kquest` opens the quest menu.
2. Pick a difficulty. A **crate roll** opens straight away and spins while the
   AI (DeepSeek) writes your quest in the background; once it's ready the reel
   eases to a stop on your new quest scroll and the "Your Quest" menu opens.
3. The quest has a trackable objective — **mine**, **kill**, or **collect** a
   set amount of something. Progress shows on your action bar.
4. Finish it and a loot crate opens, spins, and lands on your reward.

Your active quest and its progress also show on the **KawaiiScoreboard** sidebar
(if that plugin is installed) — KawaiiQuests mirrors them onto the player, so no
extra wiring is needed.

Only one quest at a time per player. **Abandon** the current one to pick a new
one — either the barrier button on the "Your Quest" screen (`/kquest` while you
have a quest) or the `/kquest abandon` command.

Ongoing quests are **saved to disk**, so they survive relogs, restarts, and
crashes. The loot crate also grants its reward up-front, so quitting mid-spin
never costs you the loot.

## AI setup (optional but recommended)

Put your DeepSeek API key in `config.yml`:

```yaml
deepseek:
  api-key: "sk-..."        # from https://platform.deepseek.com/
```

**A valid key is REQUIRED.** Quests are AI-only — there are no built-in
fallback quests. Without a working key (or if a request fails / returns an
invalid quest), `/kquest` shows an error and gives no quest.

> The server makes an outbound HTTPS call to `api.deepseek.com` when a quest is
> requested. The call runs off the main thread, so the server never blocks.

## Commands & permissions

| Command            | Description                   | Permission           | Default |
|--------------------|-------------------------------|----------------------|---------|
| `/kquest`          | Open the quest menu           | `kawaiiquests.use`   | all     |
| `/kquest abandon`  | Abandon your current quest    | `kawaiiquests.use`   | all     |
| `/kquest reload`   | Reload config + loot tables   | `kawaiiquests.admin` | op      |

Aliases: `/kq`, `/kquests`, `/quest`.

## Configuration highlights

- **`difficulty.<tier>.min/max-amount`** — clamps how big an objective the AI
  may request per tier.
- **`cooldown-seconds`** — rest period after finishing a quest (0 = off).
- **`request-cooldown-seconds`** — minimum seconds between quest *requests* per
  player. Caps how often the AI/API is hit (protects your DeepSeek bill) even if
  someone spams pick → abandon → pick.
- **`anti-exploit.*`** — anti-farming guards (see below).
- **`blocked-targets`** — Materials/EntityTypes the AI may never use
  (e.g. `BEDROCK`, `ENDER_DRAGON`).
- **`crate.spin-steps`** — minimum reel length for both the pick-a-quest roll
  and the finish-a-quest loot reel.
- **`effects`** — particle bursts (hearts/fireworks/totem sparkles) on quest
  completion and crate reveals. Titles and sounds always play; this only gates
  particles. Set `false` on performance-sensitive servers.
- **`loot.<tier>`** — weighted **fallback** reward tables (the AI normally picks
  the reward; these are used only if its choice is missing/invalid). Each entry is
  `{ material, amount, weight }`; higher `weight` = more common. Harder tiers
  list better items.
- **`messages.*`** — all player-facing text, with `&` color codes and
  `%placeholder%` tokens.

See [`config.yml`](src/main/resources/config.yml) for the fully commented
defaults.

## Anti-exploit

Because quests pay out loot, progress is guarded against the obvious farms:

- **MINE** ignores breaking blocks the player placed (no place-and-break), and
  the items those blocks drop don't count toward COLLECT either.
- **COLLECT** ignores items a player manually dropped, so drop/re-pickup loops
  and item-passing between players earn nothing.
- **KILL** ignores mobs spawned from spawn eggs, dispensers, or commands
  (configurable via `anti-exploit.ignore-kill-spawn-reasons`).
- The loot-crate GUI cancels every click/drag, so rewards can't be lifted out
  mid-animation, and a quest can only complete (and pay out) once.

All of these are toggleable under `anti-exploit` in the config.

## Building

```bash
mvn -q clean package
```

The jar lands in `target/KawaiiQuests.jar`. Requires Java 21 and a Paper
(or compatible) 1.21 server. For Bedrock support, run Geyser + Floodgate.
