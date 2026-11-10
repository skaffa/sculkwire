# Changelog

## 0.1.0+26.3

First release.

- Notifiers: ntfy, Discord webhook, generic webhook, Telegram, Gotify, Pushover, log file and in-game.
- Events from game hooks:
  - chat (contains, equals, regex, mentions you, sender lists, sender extraction for plugin servers)
  - death, damage (with source and attacker), totem, respawn, level up, item broken, item picked up,
    advancement
  - dimension and biome changes, container opened
  - titles, action bar, boss bars
  - players joining or leaving the server or your view
  - connect and disconnect (with reason), sounds, custom events
- About 100 signals for conditions and messages: player, world, nearby entities, server (ping, TPS
  estimate, tab list), session (AFK time, window focus), HUD (scoreboard sidebar, boss bars) and client.
- Triggers: event, condition (on enter or exit, or repeat while true; re-arm condition; must hold for N
  seconds), value change (from/to, minimum change) and schedule (interval, world time, real time).
- Limits: cooldown, hourly limit, once per session, only when alt-tabbed, quiet hours, global mute, join
  grace, and scope by server, world or dimension.
- Templates with filters, escaped per destination (JSON, URL, header, Discord markdown, Telegram HTML).
- Scratch-like block editor for events: a *when* block, then a script of send blocks and if / else if /
  else blocks (several chains allowed, and nested inside each other to any depth) with and / or / not
  conditions. Each send block picks its own
  message and, through clickable notifier chips, its own notifiers. The palette shows every value with
  its live reading.
- In-game editor with message preview, delivery history, 20 presets and import/export.
- `session.actively_playing` value, to hold notifications while you are at the keyboard.
- `msg.has_sender` on chat messages (true for player chat, or when the sender regex found a name), and a
  *Player chat* preset that skips server messages such as game mode changes.
- Advancements: `player.advancement` now has the type (task, goal, challenge); new `chat.advancement`
  event for any player's announced advancement, read from the message's translation key.
- Statistics: `player.stat` with a category dropdown and a searchable statistic picker. Sculkwire asks the
  server for statistics about once a minute while an event uses one (can be turned off).
- Searchable pickers for ids (items, blocks, mobs, effects, sounds, biomes, dimensions) in event and value
  settings.
- `/sculkwire` (`/sw`) commands, and a Configure button in Mod Menu (optional).
- Plugin API for other mods (experimental).
