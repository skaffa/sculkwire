# Sculkwire — implementation plan

> Name: **Sculkwire** (mod id `sculkwire`, package `dev.sculkwire`, command `/sculkwire` with
> alias `/sw`). The Modrinth slug `sculkwire` was free on 2026-11-08. The sculk sensor picks up the
> vibration and the wire carries it to your phone.

A client-side Fabric mod that watches what the game knows — chat, player state, world state,
connection, HUD — and sends notifications to endpoints you configure (ntfy, Discord, any
webhook, and more) when rules you define are met.

---

## 1. Goals and non-goals

### Goals
- **Any number of notifiers** (configured endpoint instances) of any supported type.
- **Any number of events (rules)**: a trigger, optional conditions, one or more target notifiers,
  and a message template.
- **As dynamic as possible**: every value the game tracks is exposed through one *signal
  registry*. The UI builds its pickers, comparators and value inputs from that registry, so adding
  a signal makes it usable everywhere (triggers, conditions, templates, inspector) with no extra
  UI work.
- **Variables**: built-in (`{player.name}`), event-scoped (`{msg.sender}`), and user-defined
  (strings and lists, e.g. `friends`).
- **No spam**: edge detection, hysteresis/re-arm, debounce ("true for N seconds"), cooldowns,
  rate limits, global mute, quiet hours.
- **Works on any server**: client-only, reads state, sends no game packets.
- **Fully usable in-game** through a GUI, with JSON config underneath for power users and sharing.
- **Extensible**: other mods can register signals, events and notifier types.

### Non-goals (v1)
- Server-side variant (keep the core MC-free so it stays possible later).
- Automating gameplay (no movement, no auto-reply). An opt-in "run command" action is a
  possible later extra, see §17.
- Two-way control from the phone (possible later via ntfy subscribe; out of scope).
- Forge/NeoForge in v1 (core stays loader-agnostic to keep the door open).

---

## 2. Tech stack and project setup

Reuse Ghost Mason's proven setup (`C:\Users\henk\Documents\ghost-mason`).

| Item | Value |
|---|---|
| Minecraft | 26.3 (unobfuscated, Mojang names; no mappings line) |
| Loader | Fabric Loader `0.20.1` |
| Loom | `net.fabricmc.fabric-loom` `1.19-SNAPSHOT` |
| Fabric API | `0.165.2+26.3` |
| Java | 25 (`C:\Users\henk\.jdks\jdk-25.0.5+8`; system Java is 17) |
| JSON | Gson (bundled with Minecraft) |
| HTTP | `java.net.http.HttpClient` (JDK, no extra dependency) |
| Tests | JUnit 5 in `src/test` (core only) |
| Optional | ModMenu (`modCompileOnly`/`modLocalRuntime`) for the config button |

`fabric.mod.json`: `"environment": "client"`, entrypoints `client` → `dev.sculkwire.client.SculkwireClient`,
plus a custom `"sculkwire"` entrypoint for plugins (§13). Depends: `fabricloader >=0.20.1`,
`minecraft ~26.3`, `java >=25`, `fabric-api *`. Suggests: `modmenu`.

Loom: `splitEnvironmentSourceSets()`, client run with `-Xms256M -Xmx2G` (8 GB laptop).

Build:
```bash
JAVA_HOME=/c/Users/henk/.jdks/jdk-25.0.5+8 ./gradlew build
```

`.gitignore`: `run/`, `build/`, `.gradle/`, and **any** `config/sculkwire/secrets.json` copies.

### Source layout

Rule: **`src/main` never imports Minecraft classes.** It holds the engine, model, templating,
notifiers, dispatch and config, so it is unit-testable with plain JUnit. Everything that touches
the game lives in `src/client`.

```
src/main/java/dev/sculkwire/
  api/            SculkwirePlugin, SculkwireApi (public, stable)
  core/model/     Rule, Trigger, Condition, Operand, Target, MessageTemplate,
                  NotifierConfig, UserVariable, Scope, Throttle, SculkwireConfig
  core/value/     Value, ValueType, Ops (comparators per type)
  core/signal/    SignalSpec, ParamSpec, SignalRef, SignalRegistry, SignalSource (interface)
  core/event/     EventSpec, EventPayload, EventRegistry, EventBus
  core/engine/    RuleEngine, RuleState, EvalContext, ThrottleState, Clock (injectable)
  core/template/  Template, TemplateParser, Filters, Escaper, OutputContext
  core/notify/    Notifier, NotifierType, FieldSpec, Notification, Priority,
                  types/{ntfy,discord,webhook,telegram,gotify,pushover,file}
  core/dispatch/  Dispatcher, HttpTransport, DeliveryResult, History
  core/config/    ConfigStore, Secrets, Migrations, ImportExport, Presets
  core/util/      SafeRegex (timeout), Redact
src/client/java/dev/sculkwire/client/
  SculkwireClient            entrypoint: wires registries, hooks, engine, UI, commands
  signals/                PlayerSignals, WorldSignals, NearbySignals, ServerSignals,
                          SessionSignals, HudSignals, ClientSignals
  events/                 ChatHooks, CombatHooks, ConnectionHooks, HudHooks, EntityHooks,
                          SoundHooks, ScreenHooks
  tracking/               AfkTracker, TpsEstimator, TabListTracker, HudTracker, SessionStats
  notify/                 InGameNotifier (toast/chat/sound/title — needs MC)
  ui/                     SculkwireScreen + tabs, widgets (see §12)
  command/                SculkwireCommands
  mixin/                  see §6
src/client/resources/
  sculkwire.client.mixins.json, assets/sculkwire/lang/en_us.json, assets/sculkwire/icon.png,
  assets/sculkwire/presets/*.json
src/test/java/dev/sculkwire/core/...
```

---

## 3. Concepts

| Term | Meaning |
|---|---|
| **Signal** | A named, typed value the mod can read at any time, e.g. `player.health`. May take parameters, e.g. `nearby.hostiles(radius=16)`. |
| **Event** | Something that happens at a moment, with a payload, e.g. `chat.message` with `msg.sender`, `msg.text`. |
| **Rule** (UI: "Event") | Trigger + conditions + scope + throttle + message + targets. |
| **Trigger** | What makes a rule *consider* firing: an event, a condition edge, a value change, or a schedule. |
| **Condition** | A boolean tree over signals, payload values and variables. Must be true for the rule to fire. |
| **Notifier** | A configured endpoint instance: type + settings + secrets. |
| **Notification** | The rendered message object (title, body, priority, tags, …) handed to notifiers. |
| **Variable** | Anything usable as `{name}` in templates and as an operand in conditions. |

In the UI, rules are called **Events** (the user's word); in code and JSON they are `rules`
to avoid clashing with game events.

---

## 4. Value system

`Value` is an immutable tagged union:

| `ValueType` | Java | Notes |
|---|---|---|
| `NUMBER` | `double` | Units documented per signal. |
| `BOOL` | `boolean` | |
| `STRING` | `String` | Formatting codes (`§x`) stripped. |
| `LIST` | `List<String>` | e.g. player names, effects. |
| `ENUM` | `String` + option list | e.g. weather `clear/rain/thunder`. Picker shows options. |
| `ID` | `String` resource id + registry kind | item, block, entity_type, dimension, biome, effect, sound. Picker autocompletes from the registry; supports tags (`#minecraft:logs`). |
| `NONE` | — | Signal unavailable (e.g. not in a world). |

**Null rule:** any comparison involving `NONE` is `false` (except `is_available`). Templates
render `NONE` as empty string unless the `default` filter is used.

### Comparators (`Ops`)

| Type | Operators |
|---|---|
| NUMBER | `lt` `lte` `eq` `neq` `gte` `gt` `between(min,max)` `outside(min,max)` |
| STRING | `eq` `neq` `contains` `not_contains` `starts_with` `ends_with` `matches` (regex) `in(list)` `not_in(list)`; flag `ignore_case` |
| BOOL | `is_true` `is_false` |
| LIST | `contains` `not_contains` `contains_any(list)` `size_lt/gt/eq` `is_empty` |
| ENUM | `eq` `neq` `in` |
| ID | `eq` `neq` `in` `in_tag` |
| any | `is_available` `changed` (needs previous value; only valid in condition triggers) |

---

## 5. Signal registry

```java
record ParamSpec(String name, ValueType type, Value defaultValue, Value min, Value max, String registryKind) {}
record SignalSpec(String id, ValueType type, List<ParamSpec> params, String unit,
                  List<String> enumOptions, String registryKind, int minIntervalTicks,
                  Cost cost, String category, String descriptionKey) {}
interface SignalSource { Value read(Map<String, Value> params); }   // client thread only
record SignalRef(String id, Map<String, Value> params) {}            // what rules store
```

- Specs live in core (UI/validation/tests use them); sources are registered from `src/client`.
- `cost` (`CHEAP`, `MODERATE`, `EXPENSIVE`) + `minIntervalTicks` cap how often expensive
  signals (entity scans) are read. Within one tick, reads are memoized per `SignalRef`.
- Only signals referenced by enabled rules (and the open Inspector) are ever read.

### Catalog (v1 target; ★ = MVP in M3)

**Player**
| id | type | params | notes |
|---|---|---|---|
| ★ `player.name` / `player.uuid` | STRING | | own profile |
| ★ `player.health` | NUMBER | | HP points (20 = 10 hearts); `hearts` filter for display |
| ★ `player.max_health`, `player.health_percent`, `player.absorption` | NUMBER | | |
| ★ `player.food` (0–20), `player.saturation` | NUMBER | | |
| `player.air`, `player.air_percent` | NUMBER | | air ticks 0–300 |
| `player.armor` | NUMBER | | armor points |
| ★ `player.xp_level`, `player.xp_progress` (0–1), `player.xp_total` | NUMBER | | |
| ★ `player.x/y/z`, `player.block_pos` | NUMBER / STRING | | |
| `player.facing` | ENUM | | north/east/south/west |
| ★ `player.dimension` | ID(dimension) | | |
| ★ `player.biome` | ID(biome) | | |
| `player.light.block`, `player.light.sky` | NUMBER | | at feet |
| `player.on_ground` `in_water` `in_lava` `on_fire` `sneaking` `sprinting` `swimming` `flying` `gliding` `sleeping` `is_riding` | BOOL | | |
| `player.vehicle` | ID(entity_type) | | |
| `player.gamemode` | ENUM | | survival/creative/adventure/spectator |
| `player.fall_distance`, `player.speed` | NUMBER | | speed = blocks/s, derived |
| `player.effect.active` / `.level` / `.seconds` | BOOL / NUMBER | `effect` ID | |
| `player.effects` | LIST | | active effect ids |
| ★ `player.held.item`, `player.held.count` | ID / NUMBER | | main hand |
| ★ `player.held.durability`, `player.held.durability_percent` | NUMBER | | `NONE` for non-damageable |
| `player.offhand.item` | ID | | |
| `player.armor_slot.item` / `.durability_percent` | ID / NUMBER | `slot` ENUM head/chest/legs/feet | |
| ★ `player.inventory.free_slots`, `player.inventory.full` | NUMBER / BOOL | | main 36 slots |
| `player.inventory.count` | NUMBER | `item` ID | supports tags |
| `player.looking_at.block`, `player.looking_at.entity` | ID | | from crosshair hit result |

**World**
| id | type | notes |
|---|---|---|
| ★ `world.time_of_day` | NUMBER | 0–23999 (0 = 06:00) |
| ★ `world.clock` | STRING | `HH:MM` |
| ★ `world.day` | NUMBER | day count |
| ★ `world.is_day`, `world.is_night` | BOOL | night = `[13000, 23000)` |
| ★ `world.can_sleep` | BOOL | `[12542, 23460)` in clear weather, or thundering |
| `world.ticks_until_night`, `world.ticks_until_day` | NUMBER | for "remind me 1 min before night" |
| `world.moon_phase` | NUMBER | 0–7 |
| ★ `world.weather` | ENUM | clear/rain/thunder |
| `world.difficulty` | ENUM | |
| `world.hardcore` | BOOL | |
| ★ `world.name` | STRING | singleplayer world name, or server address |
| ★ `world.is_singleplayer` | BOOL | |

> **Verify in M3:** 26.x reworked day-time/clock handling. Confirm how the client reads day time
> on 26.3 before implementing the world group.

**Nearby** (EXPENSIVE, default min interval 20 ticks, radius clamped 1–128)
| id | type | params |
|---|---|---|
| ★ `nearby.hostiles` | NUMBER | `radius` (16) |
| ★ `nearby.players` | NUMBER | `radius` (64) |
| `nearby.player_names` | LIST | `radius` |
| `nearby.entities` | NUMBER | `radius`, `type` ID (supports tags) |
| `nearby.nearest_hostile_distance` | NUMBER | `radius` |

**Server / connection**
| id | type | notes |
|---|---|---|
| ★ `server.connected` | BOOL | |
| ★ `server.address`, `server.name` | STRING | name from server list entry |
| ★ `server.ping` | NUMBER | ms, from own tab-list entry |
| `server.tps_estimate` | NUMBER | from world-time packet cadence (TpsEstimator) |
| `server.player_count` | NUMBER | tab list size |
| `server.online_players` | LIST | tab list names |
| `server.brand` | STRING | |

**Session**
| id | type | notes |
|---|---|---|
| ★ `session.afk_seconds` | NUMBER | seconds since last key/mouse input (AfkTracker) |
| ★ `session.window_focused` | BOOL | `Minecraft.isWindowActive()` |
| `session.screen` | ENUM | none/chat/inventory/container/pause/death/other |
| `session.playtime_seconds`, `session.deaths` | NUMBER | since joining this server/world |
| ★ `session.real_hour`, `session.real_time`, `session.weekday` | NUMBER / STRING / ENUM | real-world clock, for quiet hours |

**HUD**
| id | type | notes |
|---|---|---|
| `hud.actionbar`, `hud.title`, `hud.subtitle` | STRING | last shown, cleared when they expire |
| `hud.bossbars` | LIST | boss bar names |
| `hud.bossbar.progress` | NUMBER | `name` STRING (regex) |
| `hud.sidebar` | LIST | scoreboard sidebar lines (minigame servers) |

**Client**: `client.fps`, `client.memory_percent` (NUMBER).

**User**: `var.<name>` for every user variable (§8).

---

## 6. Event catalog

Each event declares: id, payload variables (name + type), trigger params (as `FieldSpec`s, so the
UI renders them), and its hook.

| id | payload | trigger params | hook |
|---|---|---|---|
| ★ `chat.message` | `msg.text`, `msg.sender`, `msg.sender_uuid`, `msg.kind` (player/system), `msg.is_own`, `match.0..n`, `match.<name>` | source (player/system/both), match mode (any/contains/regex/equals/mentions_me), pattern, ignore_case, ignore_own, senders (list or `{var}`), sender_regex (for servers that send chat as system messages) | `ClientReceiveMessageEvents.CHAT` + `GAME` (overlay=false) |
| `hud.actionbar` | `text`, `match.*` | pattern | `ClientReceiveMessageEvents.GAME` (overlay=true) |
| `hud.title` / `hud.subtitle` | `text`, `match.*` | pattern | mixin `ClientPacketListener` title/subtitle handlers |
| ★ `player.death` | `death.message`, `death.x/y/z`, `death.dimension` | — | mixin player-combat-kill handler (fallback: death screen opens) |
| `player.respawn` | `dimension` | — | respawn packet |
| ★ `player.damaged` | `damage.amount`, `damage.source`, `damage.attacker`, `health.after` | min amount, source filter | health delta each tick + damage-event packet mixin for source |
| ★ `player.totem_used` | `health.after` | — | entity event 35 for local player (mixin `handleEntityEvent`) |
| ★ `player.dimension_changed` | `old`, `new` | from, to | level change on respawn/login |
| `player.biome_changed` | `old`, `new` | from, to | signal diff |
| `player.level_up` | `old`, `new` | min level | signal diff |
| `player.item_broken` | `item`, `slot` | item filter | entity break-equipment events (verify ids on 26.3) |
| `player.advancement` | `advancement.title`, `advancement.id` | filter | mixin advancement progress update |
| `player.item_picked_up` | `item`, `count` | item filter | take-item packet mixin |
| `player.container_opened` | `screen.title`, `container.type` | title pattern | `ScreenEvents.AFTER_INIT` |
| `entity.player_appeared` / `entity.player_left` | `other.name`, `other.distance` | names (list or var) | `ClientEntityEvents.ENTITY_LOAD/UNLOAD` for remote players |
| ★ `server.player_joined` / `server.player_left` | `other.name` | names | TabListTracker diff (works without join messages) |
| ★ `connection.joined` | `server.address`, `world.name` | — | `ClientPlayConnectionEvents.JOIN` |
| ★ `connection.disconnected` | `reason`, `server.address`, `session.playtime_seconds` | reason pattern | `ClientPlayConnectionEvents.DISCONNECT` + disconnected-screen mixin for reason text |
| `sound.played` | `sound.id`, `sound.x/y/z`, `sound.distance` | sound id/tag, max distance | mixin `SoundManager.play` (internally rate-limited) |
| `hud.bossbar_added` / `_removed` | `name` | pattern | boss-bar packet mixin |
| `client.started` / `client.stopping` | — | — | `ClientLifecycleEvents` (stopping flushes dispatcher, §10) |
| `custom` | anything | event name | `/sculkwire emit <name> [text]` and the API |

Events are posted on the client thread through `EventBus.post(id, payload)`. Hooks must never
throw into game code (wrap and log).

---

## 7. Rules: data model and semantics

### JSON shape

```json
{
  "id": "low-hp-afk",
  "name": "Low HP while AFK",
  "enabled": true,
  "trigger": {
    "kind": "condition",
    "condition": { "type": "compare", "left": {"signal": "player.health"}, "op": "lt", "right": {"value": 6} },
    "mode": "on_enter",
    "rearm": { "type": "compare", "left": {"signal": "player.health"}, "op": "gte", "right": {"value": 10} },
    "for_seconds": 0,
    "check_every_ticks": 5
  },
  "conditions": {
    "type": "group", "op": "all",
    "children": [
      { "type": "compare", "left": {"signal": "session.afk_seconds"}, "op": "gte", "right": {"value": 60} }
    ]
  },
  "scope": { "servers": ["*"], "singleplayer": true, "dimensions": [] },
  "throttle": { "cooldown_seconds": 60, "max_per_hour": 10, "once_per_session": false },
  "message": {
    "title": "{player.name} is in danger",
    "body": "HP {player.health|hearts} ❤ at {player.block_pos} in {player.dimension}",
    "priority": "high",
    "tags": ["warning", "skull"],
    "click": ""
  },
  "targets": [
    { "notifier": "phone-ntfy", "override": { "priority": "urgent" } },
    { "notifier": "discord-main" }
  ]
}
```

### Trigger kinds

| kind | fields | fires when |
|---|---|---|
| `event` | `event`, `params` | the event is posted and its params match |
| `condition` | `condition`, `mode`, `rearm?`, `for_seconds`, `check_every_ticks`, `repeat_seconds?` | see modes below |
| `change` | `signal` (SignalRef), `from?`, `to?`, `min_delta?` (numbers) | the signal's value changes (payload `old`, `new`) |
| `schedule` | `every_seconds` or `at_world_time` or `at_real_time` (`HH:MM`) | the time comes |

Condition modes:
- `on_enter`: false → true. Then disarmed until `rearm` is true (if given) or the condition is
  false again. This is the hysteresis that stops HP flicker spam.
- `on_exit`: true → false.
- `while_true`: fires every `repeat_seconds` while true.
- `for_seconds` (debounce): the condition must be true continuously for N seconds first.

### Conditions

A tree of `group` (`all` / `any` / `none`) and `compare` nodes. Operands:
`{"signal": id, "params": {...}}`, `{"payload": "msg.sender"}`, `{"var": "friends"}`,
`{"value": <literal>}`, `{"template": "{player.name}"}`. `compare` has an optional `ignore_case`.
The UI greys out operators that don't fit the left operand's type.

### Scope

`servers`: glob list against the server address (`*`, `*.hypixel.net`, `play.example.com`);
`singleplayer`: bool; `worlds`: glob list of singleplayer world names; `dimensions`: list. Empty
means any.

### Throttle

`cooldown_seconds`, `max_per_hour`, `once_per_session`. Plus global settings: mute until a time,
quiet hours (real-time window; rules may opt to `ignore_quiet_hours` for urgent ones), and
`only_when_unfocused` as a global default for new rules.

### Runtime state per rule (`RuleState`)

`lastCondition`, `armed`, `trueSinceTick`, `lastValue` (change triggers), `lastFiredAt`,
`firedTimestamps` (ring buffer for `max_per_hour`), `firedThisSession`.

**Join grace:** on world join and dimension change, condition/change rules are *primed* silently
for 2 s (`grace_seconds` setting). The first evaluation records state without firing, so
"is night" doesn't fire the instant you join at night. Per-rule opt-out: `fire_on_join: true`.

State resets on disconnect. `once_per_session` resets on join.

---

## 8. Variables and templating

### Sources, in resolution order
1. Event payload (`msg.text`, `old`, `new`, `match.1`, …)
2. Rule built-ins: `rule.name`, `rule.id`, `notifier.name`, `now` (epoch ms)
3. Signals (`player.health`, `nearby.hostiles(radius=32)`)
4. User variables (`var.friends`; also usable bare as `{friends}` if no clash)

### User variables
`{ "name": "friends", "type": "LIST", "value": ["Steve", "Alex"] }`. Types: STRING, NUMBER,
BOOL, LIST. Editable in the UI and via `/sculkwire var set|add|remove|clear`. Built-in variables
that are really signals (`player.name`, `server.address`) need no setup.

### Template syntax
- `{name}`, with parameters `{nearby.hostiles(radius=32)}`
- filters: `{name|filter|filter:arg}`
- literal braces: `{{` and `}}`
- usable inside regex patterns too: `(?i)\b{player.name}\b`. Values substituted into regex are
  `Pattern.quote`d automatically unless written as `{name|raw}`.

### Filters
`upper` `lower` `title` `trim` `int` `round` `fixed:N` `percent` `hearts` (HP/2, 1 decimal)
`clock` (world ticks → HH:MM) `duration` (s → `1h 2m 3s`) `date:'HH:mm'` `default:'x'`
`truncate:N` `replace:'a':'b'` `join:', '` (lists) `count` (lists) `yesno` `coords`
`strip` (formatting) `raw`.

### Output escaping (automatic, per field)
Each notifier field declares an `OutputContext`: `PLAIN`, `JSON_STRING`, `URL_COMPONENT`,
`HEADER` (no CR/LF, ASCII-safe), `DISCORD_MARKDOWN`, `TELEGRAM_HTML`. The renderer escapes
substituted values (never the literal template text) for that context. This is what makes
the generic webhook safe: chat text with quotes can't break the JSON body.

### Validation
The parser reports unknown variables, unknown filters and type mismatches. The editor shows these
inline, and a live preview renders the template with current values (or sample payloads for
event triggers).

---

## 9. Notifiers

### Common model

```java
record Notification(String title, String body, Priority priority, List<String> tags,
                    String clickUrl, Integer color, List<Field> fields, String ruleId) {}
enum Priority { MIN, LOW, DEFAULT, HIGH, URGENT }       // maps to each service
interface NotifierType {
  String id(); List<FieldSpec> fields();                 // drives the UI form
  Notifier create(NotifierConfig cfg, Secrets secrets);
  default List<String> validate(NotifierConfig cfg) {...}
}
interface Notifier { CompletableFuture<DeliveryResult> send(Notification n); }
record FieldSpec(String key, FieldKind kind, boolean secret, boolean required,
                 Object defaultValue, List<String> options, OutputContext ctx, String hintKey) {}
// FieldKind: TEXT, MULTILINE, URL, SECRET, INT, BOOL, ENUM, KEY_VALUE_LIST, STRING_LIST, COLOR
```

A notifier instance: `{ "id": "phone-ntfy", "name": "Phone", "type": "ntfy", "enabled": true,
"settings": {...} }`. Secret fields are stored in `secrets.json` under the instance id.

### Types

**ntfy** (M2)
- Fields: `server` (default `https://ntfy.sh`), `topic`, `auth` (none/token/basic), `token`🔒 /
  `username` + `password`🔒, `default_tags`, `markdown` (bool), `icon_url`.
- Request: JSON publish, `POST {server}` with body
  `{"topic","message","title","priority":1-5,"tags":[],"click","markdown","icon"}` (UTF-8 safe,
  avoids non-ASCII header problems). `Authorization: Bearer <token>` or Basic.
- Limits: message ≤ 4096 bytes (truncate with `…`). Public ntfy.sh rate-limits per IP; treat
  `429` as retry-later.
- Priority map: MIN=1, LOW=2, DEFAULT=3, HIGH=4, URGENT=5.

**Discord webhook** (M2)
- Fields: `url`🔒, `username`, `avatar_url`, `style` (text/embed), `mention` (e.g. `<@123>`,
  prefixed to content), `color` (default per priority).
- Request: `POST {url}?wait=true`. Text: `{"content": "**title**\nbody"}`. Embed:
  `{"embeds":[{"title","description","color","fields","timestamp","footer":{"text":"Sculkwire · rule"}}]}`.
- **Always** send `"allowed_mentions": {"parse": [], "users": [<ids from mention field>]}` so
  chat text containing `@everyone` cannot ping anyone.
- Limits: content 2000, embed title 256, description 4096, 25 fields, 6000 total (truncate).
- 429: honor `retry_after` (seconds, float) from the body or `Retry-After`.

**Generic webhook** (M2)
- Fields: `method` (POST/PUT/PATCH/GET), `url` (template, URL_COMPONENT escaping for
  substitutions), `headers` (KEY_VALUE_LIST, values may be secret), `content_type`
  (`application/json`, `text/plain`, `application/x-www-form-urlencoded`), `body` (MULTILINE
  template; escaping follows content type), `success_codes` (default 2xx), `timeout_seconds`.
- Body presets shipped as templates: Slack incoming webhook, Home Assistant webhook, n8n/Make,
  Matrix (via hookshot), plain JSON of the whole notification.

**In-game** (M2, client-side implementation)
- Fields: `mode` (toast/chat/actionbar/title), `sound` (sound id or none), `volume`, `pitch`.
- Mainly for testing rules and for "remind me" use without a phone.

**Telegram** (M7): `bot_token`🔒, `chat_id`, `parse_mode` (none/HTML), `silent`. `POST
https://api.telegram.org/bot<token>/sendMessage`.

**Gotify** (M7): `server`, `app_token`🔒, priority map (0–10). `POST {server}/message` with
`X-Gotify-Key`.

**Pushover** (M7): `app_token`🔒, `user_key`🔒, `device`, `sound`, priority map (−2..2;
no emergency priority in v1).

**File log** (M7): `path` (inside `config/sculkwire/logs/` only), `format` (text/jsonl).

**Desktop toast** (M7, research spike): check AWT `SystemTray` availability under LWJGL. Fall back
to `notify-send` on Linux and skip on unsupported platforms. Hide the type when it's unavailable.

### Testing a notifier
Each instance has a **Test** button and `/sculkwire test <notifier>`. They send a sample
notification and show the HTTP status/error in the UI and in chat.

---

## 10. Dispatch pipeline

1. **Client thread**: a rule fires, then the message is rendered per target (templates need live
   signal values, which are client-thread only). This produces immutable `Notification`s.
2. These are enqueued to the `Dispatcher` (bounded queue, default 200; when full, drop the oldest
   and record it in History).
3. **Worker** (`sculkwire-dispatch` single thread + async `HttpClient`, connect timeout 10 s, request
   timeout 15 s, redirects `NEVER`):
   - per-notifier token bucket (defaults: Discord 5 req / 2 s, ntfy 1 req / 2 s, webhook 2/s)
   - retry on IO errors and 5xx: 3 attempts, backoff 2 s / 8 s / 30 s
   - 429: wait for `Retry-After` (cap 60 s), then retry
   - 4xx (not 429): fail at once, no retry (bad URL/token)
4. A `DeliveryResult` (status, latency, error) goes to **History** (ring buffer of 200, persisted
   as `history.jsonl` capped at 1 MB) and to per-notifier stats (last success, last error, count)
   shown in the UI. Repeated failures show a one-time in-game warning toast.
5. **Shutdown**: on `client.stopping`, stop accepting non-stopping events and flush with a 3 s
   deadline, so a "game closed" or "disconnected" notification still goes out.

`HttpTransport` is an interface (prod: JDK client; tests: in-memory fake + a
`com.sun.net.httpserver` loopback server).

---

## 11. Engine loop and performance

- `ClientTickEvents.END_CLIENT_TICK` → `RuleEngine.tick()`:
  - new per-tick `EvalContext` (memoized signal reads)
  - for each enabled condition/change/schedule rule that is due (`tick % check_every_ticks`)
    and in scope: evaluate trigger → conditions → throttle → fire
- Event rules: `EventBus.post` → index of rules by event id → param match → conditions →
  throttle → fire.
- Index rules by trigger kind and event id at config load; rebuild on config change.
- Budget: < 0.2 ms/tick with 50 rules (measure in M3 with a debug overlay
  `/sculkwire debug perf`).
- Entity scans: one `level.getEntities(player, AABB)` per (radius, type) per due tick, shared by
  all rules.
- Regex: `SafeRegex` wraps the input in a deadline-checking `CharSequence` (50 ms) to stop
  catastrophic backtracking from imported patterns. Compile once per rule at load.
- Nothing runs off-thread except dispatch; no MC objects cross threads.

---

## 12. Config, storage and sharing

```
config/sculkwire/
  config.json       settings, notifiers (no secrets), rules, variables, schemaVersion
  secrets.json      { "<notifier id>": { "<field>": "<value>" } }
  history.jsonl     delivery history (capped)
  presets/          user-saved presets
```

- `schemaVersion` + `Migrations` (ordered `v → v+1` functions on `JsonObject`). Always back up
  `config.json.bak` before migrating.
- Atomic writes (temp file + move). Bad JSON at load → keep defaults in memory, keep the broken
  file as `config.json.broken-<ts>`, warn in-game. Never overwrite the user's file blindly.
- `/sculkwire reload` re-reads from disk (editing JSON by hand is supported).
- Secrets may reference env vars: `"${env:NTFY_TOKEN}"`.
- **Import/export**:
  - Export rules (one, a selection, or all) to the clipboard or a file as
    `{"sculkwire_export": 1, "rules": [...], "variables": [...], "notifier_refs": ["phone-ntfy"]}`.
    Secrets and notifier settings are never exported.
  - On import, the UI maps missing notifier refs to existing instances. Imported rules arrive
    **disabled**, with a summary of what they send and where.
- **Built-in presets** (`assets/sculkwire/presets/`), one click to add:
  - Mentioned in chat
  - Friend joined (tab list)
  - Low health
  - Low health while AFK
  - Died (with coordinates)
  - Totem popped
  - Disconnected / kicked
  - Night falls / can sleep now
  - Day breaks
  - Thunderstorm started
  - Hunger low
  - Inventory full
  - Tool about to break
  - Player came into view
  - Server restart warning (chat regex)
  - Entered the Nether / End

---

## 13. Public API

```java
public interface SculkwirePlugin {                       // entrypoint key "sculkwire"
  default void registerSignals(SignalRegistry r) {}
  default void registerEvents(EventRegistry r) {}
  default void registerNotifierTypes(NotifierRegistry r) {}
}
public final class SculkwireApi {
  public static void emit(String eventId, Map<String, Value> payload);   // client thread
  public static boolean isAvailable();
}
```

Ids from plugins must be namespaced (`mymod:signal`). The API is marked experimental until 1.0.
The built-in catalog is registered through the same API, which proves it works.

---

## 14. User interface

Own screens on vanilla widgets (no config library: the condition builder and dynamic forms need
custom widgets anyway). Opened by keybind (default unbound, set in Controls), `/sculkwire`, or
ModMenu.

```
┌ Sculkwire ─────────────────────────────────────────────────────────────┐
│ [Events] [Notifiers] [Variables] [Inspector] [History] [Settings]   │
├─────────────────────────────────────────────────────────────────────┤
│ Events                                   [+ New] [+ From preset]    │
│ ● Mentioned in chat        chat.message      → Phone, Discord   ✎ ⧉ │
│ ● Low HP while AFK         player.health<6   → Phone            ✎ ⧉ │
│ ○ Night falls              world.is_night    → In-game          ✎ ⧉ │
│                                       [Mute 30m]  [Import] [Export] │
└─────────────────────────────────────────────────────────────────────┘
```

**Event editor** (scrollable sections):
1. Name, enabled.
2. Trigger: kind picker → for `event`: category → event list → generated param form; for
   `condition`/`change`: condition builder + mode/rearm/for-seconds.
3. Conditions: tree builder. Each row is `[operand ▾] [operator ▾] [value input]`, plus
   `+ condition`, `+ group`, and an `all/any/none` toggle. The operand picker is a searchable
   list of signals grouped by category, then payload vars, then user vars. A signal with params
   shows inline param inputs. The value input fits the type: number field, toggle, enum dropdown,
   ID autocomplete, list chips.
4. Scope and throttle.
5. Message: title, body, priority, tags. A `{x}` button opens the variable picker. Live preview
   below.
6. Targets: notifier checklist, each with an expandable override.
7. Buttons: **Test fire** (renders with current/sample values and sends), Save, Cancel. Unsaved
   changes guard.

**Notifiers tab**: list with status dots (last delivery OK/failed). The editor is a form
generated from `FieldSpec`s. Secrets are masked with a reveal toggle. Test button.

**Variables tab**: user variables, with an editor per type.

**Inspector**: searchable live table of every signal and its current value (updated every 10
ticks). Click a row to start a new event with this signal prefilled. This is also the
discoverability tool.

**History**: last 200 deliveries (time, rule, notifier, status, error). Click to see the
rendered message.

**Settings**: global mute, quiet hours, join grace, default `only_when_unfocused`, history size,
privacy notice re-show.

UI implementation notes:
- One reusable `FieldForm` widget renders any `List<FieldSpec>` (notifier settings, event params,
  signal params).
- All strings go through `en_us.json` from the start.
- Must work at GUI scale 4 and 854×480: scrollable panels, no fixed widths.

---

## 15. Commands (client commands)

```
/sculkwire                         open UI
/sculkwire test <notifier>         send a test notification
/sculkwire fire <rule>             force-fire a rule (ignores throttle; marks as test)
/sculkwire list [rules|notifiers]  list with status
/sculkwire enable|disable <rule>
/sculkwire mute [minutes] | /sculkwire unmute
/sculkwire reload
/sculkwire signal <id> [k=v ...]   print a signal's current value
/sculkwire var set|add|remove|clear <name> [value]
/sculkwire emit <name> [text]      post a custom event
/sculkwire debug perf|events       toggle debug output
```

Argument suggestions come from the registries and the config (rule ids, notifier ids, signal ids).

---

## 16. Security and privacy

- **Data leaves the machine**: chat text, coordinates and names go to third parties. Show a
  one-time notice on first open. The notifier editor shows a warning on `ntfy.sh` topics without
  auth ("anyone who guesses the topic can read it"), and offers a generated random topic name.
- `secrets.json` is plaintext; say so in the UI and README. It is never logged, never exported,
  and never shown unmasked by default.
- `Redact` strips webhook tokens and bot tokens from every log line and History entry, e.g.
  Discord URL → `…/webhooks/123/****`.
- Discord `allowed_mentions` is locked down (§9).
- HTTPS required except `localhost`/LAN addresses or a per-notifier "allow insecure HTTP" toggle.
  No redirects.
- Imported rules arrive disabled; regex has a timeout.
- No telemetry, no update checks.
- Read-only toward the server: no packets sent, so it should be fine on servers that allow client
  mods. Still, mention in the README that server rules vary.

---

## 17. Milestones

Each milestone ends with a build that runs.

**M0 — Scaffold** (½ day)
- Gradle project copied from Ghost Mason; env `client`; empty entrypoint; `en_us.json`; icon
  placeholder; JUnit wired; README stub; git init.
- *Done when*: `./gradlew build` passes and `runClient` loads the mod.

**M1 — Core engine** (MC-free, fully unit-tested)
- `Value`/`Ops`, model classes + Gson (de)serialization, `ConfigStore` + `Secrets` + migrations
  skeleton, template parser/filters/escaping, condition evaluation, trigger modes (edge, rearm,
  debounce, while_true, change, schedule), throttle, scope, join grace, `SafeRegex`.
- Tests use a fake signal source and an injectable `Clock`.
- *Done when*: tests cover every operator, trigger mode, filter and escape context.

**M2 — Delivery**
- `Notification`, `NotifierType`/`FieldSpec`, ntfy, Discord, generic webhook, in-game notifier,
  `Dispatcher` (queue, rate limit, retry, 429, shutdown flush), History, `Redact`.
- `/sculkwire test`.
- *Done when*: loopback-server tests pass, and a real ntfy topic and Discord webhook receive tests
  from in-game.

**M3 — Game integration = MVP** (★ items)
- Signal sources for the ★ catalog, AfkTracker, TabListTracker.
- Events: chat, death, damaged, totem, dimension change, connection joined/disconnected,
  server player joined/left.
- Engine on the client tick; commands; JSON-authored rules work; presets loadable via command.
- Verify the 26.3 day-time API.
- *Done when*: the user's core scenarios work end-to-end on a dev world and a test server:
  - mentioned in chat → ntfy
  - night falls → ntfy
  - low HP while AFK → Discord
  - disconnected → both
  - and perf is within budget

**M4 — GUI**
- `SculkwireScreen`, all tabs, `FieldForm`, condition tree builder, variable picker, live preview,
  inspector, history, keybind, ModMenu.
- *Done when*: every rule from the presets can be created and edited without touching JSON.

**M5 — Full catalog**
- Remaining signals and events (HUD title/actionbar/bossbar/sidebar, sounds, advancements,
  items, containers, nearby, effects, armor, TPS estimate), all presets, import/export with
  mapping.
- *Done when*: Inspector lists the whole §5 catalog with live values.

**M6 — Release 0.1**
- Polish, docs (README with screenshots, a rules cookbook), Modrinth page, changelog.
- Tag `0.1.0+26.3`.

**M7 — Extras** (any order)
- Telegram, Gotify, Pushover, file log, desktop toast spike.
- Screenshot attachment (ntfy `PUT` attachment / Discord multipart).
- Stable plugin API.
- Stonecutter multi-version.
- Opt-in "run command" action (off by default, with a clear warning).

---

## 18. Testing strategy

- **Unit (src/test, no MC)**: operators, condition trees, every trigger mode with a fake clock,
  throttle math, template parsing/filters/escaping (incl. JSON-breaking chat text, `@everyone`,
  emoji, CR/LF in headers), config round-trip + migrations + broken-file recovery, notifier
  request building (golden JSON per type), dispatcher retry/429/backoff with a fake transport,
  regex timeout.
- **Loopback integration**: a `com.sun.net.httpserver` server records requests. Assert headers,
  bodies and retry behavior.
- **In-game checklist** (dev client + `runServer` test server, offline mode, disposable worlds):
  - `/time set 12900` → night rule fires once; `/time set 1000` re-arms it.
  - `/effect give @s instant_damage` / `/damage` → low-HP + damaged events; rearm after
    `/effect give @s instant_health`.
  - Second account says your name → mention rule.
  - `/kill` → death event with coordinates.
  - Totem in offhand + lethal damage → totem event.
  - Nether portal / `/execute in` → dimension change.
  - Stop the server → disconnect event delivered.
  - Join at night → no fire (grace).
  - Alt-tab → `window_focused` condition.
- **Manual delivery**: a private ntfy topic + a private Discord test channel.

---

## 19. Risks and open questions

| Item | Note |
|---|---|
| 26.x API churn | Day time, entity events and packet handlers may have moved. Verify each hook when implementing; keep all MC calls in `src/client` so ports touch one layer. |
| Chat sender on plugin servers | Many servers send player chat as system messages. Mitigation: `sender_regex` with presets for common formats (`<name> msg`, `[rank] name: msg`, `name » msg`). |
| Damage source on client | Only known via the damage-event packet; may be unknown. Payload falls back to `unknown`. |
| Desktop notifications | Unclear under LWJGL; spike in M7. |
| Server policies | Read-only, but some servers ban any "alert" mods; document it, no workaround. |
| Name | Claim the `sculkwire` slug on Modrinth early (create a draft project). Check CurseForge too. |
| Open | Should NeoForge be a v1 target? (Recommend no; the core layout keeps it cheap later.) |
| Open | Phone → game control via ntfy subscribe (e.g. "disconnect now")? Out of scope; revisit after 0.1. |

---

## 20. Glossary of file/class responsibilities (quick reference for coding sessions)

| Class | Responsibility |
|---|---|
| `SculkwireClient` | Bootstraps: load config, register built-in catalog via API, call plugin entrypoints, register hooks/commands/keybind, start dispatcher. |
| `SignalRegistry` | id → spec + source; lookup, listing by category, param validation. |
| `EventRegistry` / `EventBus` | id → spec; post events to the engine on the client thread. |
| `RuleEngine` | Holds compiled rules + state; `tick()`, `onEvent()`, `fire()`. |
| `EvalContext` | Per-tick memoized signal reads + payload + variables; resolves operands. |
| `TemplateParser` / `Template` | Parse once at load; `render(ctx, OutputContext)`. |
| `Dispatcher` | Queue, worker, rate limits, retry, history, flush. |
| `ConfigStore` | Load/save/migrate/backup; change listeners trigger engine recompile. |
| `FieldForm` | Generic form UI for any `List<FieldSpec>`. |
| `ConditionTreeWidget` | Visual editor for condition trees. |
