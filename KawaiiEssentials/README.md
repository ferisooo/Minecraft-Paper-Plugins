# KawaiiEssentials (✧)

A cute, self-contained all-in-one essentials plugin for Paper/Bukkit 1.21.

## Features

| Command | What it does |
| --- | --- |
| `/sethome [name]` | Set a named home (default name `home`). |
| `/home [name]` | Teleport to one of your homes. |
| `/homes` | List your homes. |
| `/delhome [name]` | Delete a home. |
| `/tpa <player>` | Send a teleport request (expires in 60s). |
| `/tpaccept` | Accept the latest pending request (requester teleports to you). |
| `/tpdeny` | Deny the pending request. |
| `/hub` | Teleport to the server hub. |
| `/sethub` | Set the hub to your location (`kawaiiessentials.sethub`, op). |
| `/back` | Teleport to where you last died. |
| `/kit` | Claim a starter kit (leather armor + stone sword). 30 min cooldown; blocked if you still have all kit items. |
| `/trash` | Open a 54-slot trash bin. Closing it discards the contents. |
| `/trash undo` | Recover the last trashed batch (once only — no duplication). Overflow drops at your feet. |

## Permissions

- `kawaiiessentials.use` — basic player commands (default `true`).
- `kawaiiessentials.sethub` — set the hub (default `op`).

## Storage

- `homes.yml` — named homes keyed by player UUID.
- `data.yml` — hub location, per-player death locations, kit cooldowns.

Data is saved after each mutation and on plugin disable.

## Notes

Built version-safe for a base Paper 1.21 server compiled against the 1.21.11 API:
sounds are played via the String overload, no `Attribute` enum is used, and
colors go through `ChatColor.translateAlternateColorCodes`.

## Build

```
mvn clean package
```

Output: `target/KawaiiEssentials-1.0.0.jar`.

Author: Ferris
