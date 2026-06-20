# KawaiiPoem 📜

Plays a **custom End-Poem-style** scrolling credits message on a player's
screen. Whatever you type into `config.yml` is revealed one line at a time,
drifting up the chat like the vanilla end-of-game poem, with two narrator
colours alternating. No NMS, no resource pack — works on any 1.21.x server.

## Commands
- `/kpoem` — play the poem for yourself.
- `/kpoem <player>` — play it for someone else (`kawaiipoem.others`).
- `/kpoem stop` — stop your currently-playing poem.
- `/kpoem reload` — reload the config (`kawaiipoem.admin`).

## Writing your poem
Edit the `lines:` list in `plugins/KawaiiPoem/config.yml`. Each entry is one
line; use `""` for a blank spacer. Lines support `&` colour codes
(e.g. `&bhello`). Lines **without** a colour code auto-alternate between
`color-a` and `color-b`.

```yaml
line-delay-ticks: 30      # ticks between lines (20 = 1s)
clear-screen: true        # blank the screen before starting
alternate-colors: true
color-a: GREEN
color-b: LIGHT_PURPLE
center-width: 0           # >0 roughly centres each line
start-title: "&d✧ The End ✧"
start-subtitle: "&7...for now"
start-sound: "minecraft:ui.toast.challenge_complete"
lines:
  - "I see the player you mean."
  - "&7Billy?"
  - ""
  - "&dwww.your-server.net"
```

## Build
Java 21 + Maven: `mvn clean package`.
