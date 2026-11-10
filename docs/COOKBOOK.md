# Sculkwire cookbook

Recipes for common events. Each one can be built in the editor (`/sw`), or pasted into the
`rules` list of `config/sculkwire/config.json` (then `/sw reload`). Replace `phone` with your
notifier's id.

## Wait for night (or day)

You are AFK at a farm and want to know when you can sleep.

- **Trigger:** A condition becomes true: `world.can_sleep` is true
- **Message:** `You can sleep now` / `It is {world.clock}`

```json
{"id": "can-sleep", "name": "Can sleep now",
 "trigger": {"kind": "condition", "check_every_ticks": 20,
   "condition": {"type": "compare", "left": {"signal": "world.can_sleep"}, "op": "is_true"}},
 "message": {"title": "You can sleep now", "body": "It is {world.clock}"},
 "targets": [{"notifier": "phone"}]}
```

To get a warning one minute before dusk, use `world.ticks_until_night` ≤ `1200` instead.

## Low health while AFK, with hysteresis

Fires once when health drops below 3 hearts, and not again until you are back above 5 hearts.

```json
{"id": "afk-danger", "name": "Danger while AFK",
 "trigger": {"kind": "condition", "check_every_ticks": 2,
   "condition": {"type": "compare", "left": {"signal": "player.health"}, "op": "lt", "right": {"value": 6}},
   "rearm": {"type": "compare", "left": {"signal": "player.health"}, "op": "gte", "right": {"value": 10}}},
 "conditions": {"type": "compare", "left": {"signal": "session.afk_seconds"}, "op": "gte", "right": {"value": 60}},
 "message": {"title": "Taking damage while AFK!", "body": "{player.health|hearts} ❤ at {player.block_pos}", "priority": "urgent"},
 "targets": [{"notifier": "phone"}]}
```

## Friends come online

Make a list variable `friends` (Variables tab, or `/sw var add friends Alex`), then:

- **Trigger:** `server.player_joined`, *Only these players:* `{friends}`

## Someone types a keyword

- **Trigger:** `chat.message`, *Match:* `regex`, *Text or regex:* `(?i)\b(diamonds?|help)\b`
- **Message:** `{msg.sender}: {msg.text}`

To get only messages from players (not "Set own game mode", deaths or other server lines), add the
condition `msg.has_sender` is true, or start from the *Player chat* preset.

On servers that send player chat as system messages (many plugin servers), set *Source* to `both` and
*Sender regex* to the server's chat format, e.g. `^\[\w+\] (?<name>\w+): (?<msg>.*)$`. Sculkwire then
fills in `{msg.sender}` and `{msg.text}` from it, and *Only from* and *Ignore my own messages* work again.

## Kicked or disconnected

- **Trigger:** `connection.disconnected` (leaving yourself does not count unless you tick *Also when I
  leave myself*)
- **Message:** `Disconnected from {server.address}` / `{reason}` and `Played {session.playtime_seconds|duration}`

## Mobs around your AFK spot

```json
{"id": "mobs", "name": "Mobs nearby",
 "trigger": {"kind": "condition", "check_every_ticks": 20,
   "condition": {"type": "compare", "left": {"signal": "nearby.hostiles", "params": {"radius": 8}}, "op": "gte", "right": {"value": 3}}},
 "throttle": {"cooldown_seconds": 120},
 "message": {"title": "Mobs around you", "body": "{nearby.hostiles(radius=8)} within 8 blocks"},
 "targets": [{"notifier": "phone"}]}
```

## Server lag alert

- **Trigger:** A condition becomes true: `server.tps_estimate` < `15`, must hold for `30` seconds
- **Re-arm** when `server.tps_estimate` ≥ `19`

## A raid started

- **Trigger:** `sound.played`, *Sounds:* `minecraft:event.raid.horn`
- Add a cooldown: the horn plays several times.

## A boss bar shows up

- **Trigger:** `hud.bossbar_added`, *Name matches:* `(?i)dragon|wither`

## Minigame round starts (titles)

- **Trigger:** `hud.title`, *Text matches:* `(?i)round (\d+)`
- **Message:** `Round {match.1} started`

## Remind me to take a break

- **Trigger:** On a schedule, every `3600` seconds
- **Message:** `You have played {session.playtime_seconds|duration}`

## A friend gets an advancement

- **Trigger:** `chat.advancement` (*A player's advancement is announced in chat*), *Only these players:*
  `{friends}`, optionally *Only types:* `challenge`
- **Message:** `{other.name} got {advancement.title}`

## Count something: every 100 diamonds mined

- **Trigger:** A value changes: `player.stat` with *Category* `mined` and *Statistic* `diamond_ore`
- **Only if:** in the block editor, `player.stat` … is a multiple you care about, or simply send on every
  change and use *Limits → Cooldown*.
- Statistics refresh about once a minute.

## Webhook bodies

The generic webhook can send any format. Inside the body, use `{notification.title}`,
`{notification.body}`, `{notification.priority}`, `{notification.tags}`, `{rule.name}` and any other
value. With content type `application/json`, values are escaped for you, so this is always valid JSON:

```json
{"event": "{rule.name}", "text": "{notification.body}", "hp": "{player.health}", "where": "{player.dimension|pretty}"}
```

Home Assistant (`https://<your-ha>/api/webhook/<id>`):

```json
{"title": "{notification.title}", "message": "{notification.body}"}
```

Slack incoming webhook:

```json
{"text": "*{notification.title}*\n{notification.body}"}
```

## Quiet hours and focus

- **Settings → Quiet hours** holds everything back during the night. Events with *Ignore quiet hours*
  still send, which suits *Died* or *Disconnected*.
- **Only when alt-tabbed** (per event, or as the default in Settings) skips notifications while you are
  looking at the game, since you will see it anyway.
