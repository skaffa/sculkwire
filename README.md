<p align="center"><img src="media/icon.png" width="128" alt="Sculkwire icon"></p>

# Sculkwire

A client-side Fabric mod for Minecraft 26.3 that turns what happens in your game into
notifications on your phone, Discord, or any webhook.

- **Notifiers**: set up as many endpoints as you like: ntfy, Discord webhooks, generic webhooks
  (more later).
- **Events**: pick a trigger (chat message, your name mentioned, low health, night falls,
  dimension change, disconnect, and much more), add conditions, and choose which notifiers get it.
- **Variables**: use `{player.name}`, `{msg.text}`, `{player.health}` and anything else the game
  tracks in messages and conditions.

> **Status:** early development. Nothing to download yet. See [PLAN.md](PLAN.md) for the design
> and roadmap.

## Building

Needs JDK 25.

```bash
./gradlew build
```

The jar ends up in `build/libs/`.

## License

MIT
