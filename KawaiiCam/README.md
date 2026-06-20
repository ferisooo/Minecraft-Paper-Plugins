# KawaiiCam 🎬

An **autonomous cinematic camera**. It puts you into a spectator view that an AI
"director" flies around a subject — picking its own shots (orbits, cranes,
dollies, low hero angles, over-the-shoulder tracking), easing the motion, and
keeping the camera from clipping into terrain. Start and stop with commands; the
camera rig is invisible to normal players (spectator view).

## ⚠️ About "recording" / MP4
A Minecraft **server has no renderer**, so a plugin can't produce a video file.
KawaiiCam instead records the **camera track** — every tick's position, rotation
and chosen shot — to a JSON file. You turn that into an MP4 by **replaying it and
screen-capturing**:

1. `/cam follow <you>` (from a 2nd account) or `/cam solo`, then `/cam record`.
2. `/cam record stop` saves the take to `plugins/KawaiiCam/recordings/<name>.json`.
3. `/cam play <name>` replays the exact camera path — capture that clean run in
   **OBS** (or Replay Mod) and you've got your video. `ffmpeg` to trim/encode.

## The one-viewpoint thing
A vanilla client has a single view, so to film **your body doing things** the
camera needs a viewpoint separate from your gameplay one:

- **Companion (2 accounts)** — keep playing on your main; from a second account
  run `/cam follow <yourMainName>`. That account's screen is the movie. This is
  the true "follows me around and films me while I play".
- **Solo (1 account)** — `/cam solo` flies a cinematic of your current spot
  (great for builds/landscapes). You're the camera, so your body isn't in shot.

## Commands
| Command | What it does |
| --- | --- |
| `/cam follow <player>` | Start filming a player (use a 2nd account to film yourself). |
| `/cam solo` | Cinematic of your current spot. |
| `/cam stop` | End the camera; restores your gamemode (and position). |
| `/cam style <chill\|action\|epic>` | Bias the shot picker (applies to the next start). |
| `/cam record [name]` | Start capturing the camera track. |
| `/cam record stop` | Save the take. |
| `/cam play <name>` | Replay a saved take (record it in OBS → MP4). |
| `/cam list` | List saved takes. |
| `/cam reload` | Reload config (`kawaiicam.admin`). |

Aliases: `/kcam`, `/camera`.

## How the director "thinks for itself"
Every tick it reads the subject's state and picks shots to match:
- **Combat** (hostile mobs near) → low hero angles, side tracking, fast orbits.
- **Moving fast** → over-the-shoulder + side tracking, pull-backs.
- **Idle** → slow orbits, crane-ups, dolly-ins.

Motion is eased (no robotic snapping), framing uses a rule-of-thirds nudge, and
a block raytrace pulls the camera in when terrain would block the shot.

## Config
`plugins/KawaiiCam/config.yml` — every setting is documented inline:
`default-style`, `position-smoothing` / `rotation-smoothing`,
`collision-avoidance`, `shot-min-seconds` / `shot-max-seconds`,
`restore-on-stop`, `max-record-seconds`.

## Permissions
- `kawaiicam.use` (default op) — use the camera (it grants spectator view).
- `kawaiicam.admin` (default op) — `/cam reload`.

## Build
Java 21 + Maven:
```bash
mvn clean package
```
