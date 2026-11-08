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
- In-game editor with live inspector, message preview, delivery history, 19 presets and import/export.
- `/sculkwire` (`/sw`) commands.
- Plugin API for other mods (experimental).
