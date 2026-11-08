package dev.sculkwire.client.notify;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.core.field.FieldKind;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.notify.HttpTransport;
import dev.sculkwire.core.notify.Notification;
import dev.sculkwire.core.notify.Notifier;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.notify.SecretSource;
import dev.sculkwire.core.notify.SendResult;
import dev.sculkwire.core.template.OutputContext;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Shows the notification inside the game (toast, chat, action bar or title) with an optional sound. */
public final class InGameNotifierType implements NotifierType {
	public static final String ID = "ingame";

	/** True while Sculkwire itself sets a title/action bar, so its own output does not trigger events. */
	public static boolean showing;

	private static final List<FieldSpec> FIELDS = List.of(
			FieldSpec.of("mode", FieldKind.ENUM, "Show as").withOptions("toast", "chat", "actionbar", "title").withDefault("toast"),
			FieldSpec.of("sound", FieldKind.ID, "Sound").withRegistry("sound_event").withDefault("minecraft:block.note_block.bell")
					.withHint("Sound id; empty = silent"),
			FieldSpec.of("volume", FieldKind.NUMBER, "Volume").withDefault(1.0),
			FieldSpec.of("pitch", FieldKind.NUMBER, "Pitch").withDefault(1.0));

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "In-game";
	}

	@Override
	public String description() {
		return "Shows it in the game as a toast, chat line, action bar or title. Nothing leaves your computer.";
	}

	@Override
	public List<FieldSpec> fields() {
		return FIELDS;
	}

	@Override
	public long minIntervalMillis() {
		return 0;
	}

	@Override
	public Notifier create(NotifierConfig cfg, SecretSource secrets, HttpTransport http) {
		String mode = FIELDS.get(0).string(cfg.settings);
		String sound = FIELDS.get(1).string(cfg.settings).trim();
		float volume = (float) FIELDS.get(2).number(cfg.settings, 1);
		float pitch = (float) FIELDS.get(3).number(cfg.settings, 1);
		return n -> {
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> show(mc, n, mode, sound, volume, pitch));
			return CompletableFuture.completedFuture(SendResult.ok(0));
		};
	}

	private static void show(Minecraft mc, Notification n, String mode, String sound, float volume, float pitch) {
		String title = n.title(OutputContext.PLAIN).strip();
		String body = n.body(OutputContext.PLAIN).strip();
		Component t = Component.literal(title.isEmpty() ? "Sculkwire" : title);
		Component b = Component.literal(body);
		showing = true;
		try {
			switch (mode) {
				case "chat" -> ClientRuntime.chat(ClientRuntime.prefix()
						.append(Component.literal(title).withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE))
						.append(Component.literal(body.isEmpty() ? "" : (title.isEmpty() ? "" : " ") + body).withStyle(ChatFormatting.GRAY)));
				case "actionbar" -> mc.gui.hud.setOverlayMessage(Component.literal(title.isEmpty() ? body : title + (body.isEmpty() ? "" : ": " + body)), false);
				case "title" -> {
					mc.gui.hud.setTitle(t);
					mc.gui.hud.setSubtitle(b);
				}
				default -> SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(), t, b);
			}
		} finally {
			showing = false;
		}
		if (!sound.isEmpty()) {
			Identifier id = Identifier.tryParse(sound);
			if (id != null) {
				BuiltInRegistries.SOUND_EVENT.get(id).ifPresent(h -> mc.getSoundManager().play(
						SimpleSoundInstance.forUI(h.value(), Math.max(0.5f, Math.min(2f, pitch)), Math.max(0f, Math.min(1f, volume)))));
			}
		}
	}
}
