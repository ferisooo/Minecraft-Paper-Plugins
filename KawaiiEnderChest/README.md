# KawaiiEnderChest

A bigger ender chest — **54 slots (two chests combined)** per player, instead of the vanilla 27.

## Why this exists

The vanilla ender chest is locked to 27 slots and can't be resized. This plugin intercepts the ender chest open and shows a custom **54-slot** inventory instead, saved per-player on disk so it survives restarts and follows the player across worlds.

54 slots is Minecraft's largest single container (a double chest), so it also renders correctly for **Bedrock players through Geyser**.

## Install

Drop `kawaiienderchest-1.0.0.jar` in `plugins/`, restart.

- Right-click any ender chest block → opens the 54-slot chest.
- `/kec` (aliases: `/enderchest`, `/echest`) → opens it from anywhere.

On a player's **first** open, whatever was in their old 27-slot vanilla ender chest is copied across, so nothing is lost in the upgrade.

## Config

`plugins/KawaiiEnderChest/config.yml`:

```yaml
slots: 54                          # multiple of 9, max 54 (two chests)
title: '§d✿ Ender Chest ✿'         # GUI title (§ color codes)
import-vanilla-on-first-open: true # copy old 27-slot contents on first open
```

`/kec reload` (op-only) reloads without a restart.

## Storage

Each player's chest is stored at `plugins/KawaiiEnderChest/enderchests/<uuid>.yml` and saved when the chest is closed (and on server shutdown).
