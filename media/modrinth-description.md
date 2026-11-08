**Get a notification on your phone, Discord or any webhook when something happens in Minecraft.**
Someone says your name, night falls, you are low on health while AFK, you get kicked, a friend comes
online: you decide what counts and where it goes.

Sculkwire is **client-side**: it works on any server (and in singleplayer), and it sends nothing to the
game server.

## What it does

- **Send anywhere**: ntfy (phone push), Discord webhooks, any webhook (Slack, Home Assistant, n8n, your
  own server), Telegram, Gotify, Pushover, a log file, or in-game toasts.
- **Trigger on anything**:
  - Chat: keywords, regex, your name, messages from your friends list.
  - Things that happen: death, damage, a totem, dimension changes, level ups, broken items, titles and
    boss bars, players joining, disconnects, sounds.
  - Any of about 100 tracked values: health, hunger, time of day, weather, mobs nearby, ping, TPS, AFK
    time, held item durability, free inventory slots and more.
- **No spam**: a notification fires when a condition *starts* being true. You can add re-arm thresholds,
  "must hold for N seconds", cooldowns, hourly limits, quiet hours and a mute button.
- **Your own messages**: `{player.health|hearts} hearts left at {player.block_pos}`, using any value,
  with filters.
- **Editor in game**: `/sw` (or Mod Menu's Configure button) opens it. It has a live inspector of every value, message previews, test
  buttons and a delivery history.
- **19 presets** and import/export to share setups. Exports never include your secrets.

## Getting started

1. Install [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft **26.3** and put Sculkwire in
   your `mods` folder.
2. Install the ntfy app on your phone and subscribe to a long random topic.
3. In game: `/sw`, then **Notifiers → + Add notifier → ntfy**. Enter the topic and press **Save & test**.
4. **Events → + Preset → Low health** (or *Mentioned in chat*), then **Save**.

## Example events

- *You can sleep now* when night falls at your AFK farm.
- *Taking damage while AFK!* below 3 hearts (re-arms above 5).
- *Alex is online* when someone from your friends list joins.
- *Disconnected from play.example.net: Server closed* with how long you played.
- *Raid started* when the raid horn sounds.

## Privacy

Notifications can contain chat text, coordinates and names, and they leave your computer when you send
them to an online service. On the public ntfy.sh, anyone who knows a topic can read it, so use a long
random topic (the editor makes one) or a token. Webhook URLs and tokens are kept in a separate
`secrets.json`.

## Compatibility

- Minecraft 26.3, Fabric Loader 0.20.1+, Fabric API, Java 25.
- Client only.
- Other mods can add their own values, events and notifier types through the Sculkwire API.

Source and issues: [github.com/skaffa/sculkwire](https://github.com/skaffa/sculkwire)
