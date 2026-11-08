package dev.sculkwire.client.ui;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.core.dispatch.History;
import dev.sculkwire.core.field.FieldSpec;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.notify.NotifierType;
import dev.sculkwire.core.notify.types.NtfyType;
import dev.sculkwire.core.notify.types.WebhookType;
import dev.sculkwire.core.util.Json;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Edits one notifier: name, enabled, and the type's own fields (secrets kept apart). */
public final class NotifierEditScreen extends FormScreen {
	private final ClientRuntime rt = ClientRuntime.get();
	private final NotifierConfig cfg;
	private final NotifierType type;
	private final boolean isNew;
	private final String originalId;
	private final Map<String, String> pendingSecrets = new HashMap<>();
	private String lastTest = "";

	public NotifierEditScreen(Screen parent, NotifierConfig cfg, boolean isNew) {
		super(Component.literal((isNew ? "New " : "Edit ") + "notifier"), parent);
		this.cfg = cfg;
		this.isNew = isNew;
		this.originalId = cfg.id;
		this.type = rt.notifiers.get(cfg.type);
		if (type != null) {
			for (FieldSpec f : type.fields()) {
				if (f.secret()) {
					pendingSecrets.put(f.key(), rt.secrets.raw(originalId, f.key()));
				}
			}
		}
	}

	@Override
	protected void buildFixed() {
		if (isNew) {
			footer(button("Save", () -> save(false)), button("Save & test", () -> save(true)), button("Cancel", this::onClose));
		} else {
			footer(button("Save", () -> save(false)), button("Save & test", () -> save(true)), button("Delete", this::delete),
					button("Cancel", this::onClose));
		}
	}

	@Override
	protected void buildRows() {
		if (type == null) {
			text("Unknown notifier type '" + cfg.type + "'.", BAD);
			return;
		}
		header(type.displayName());
		text(Component.literal(type.description()), GRAY, 0);
		row("Name").add(box(cfg.name, 60, v -> cfg.name = v, "e.g. Phone"), -1);
		row("Enabled").add(toggle(() -> cfg.enabled, v -> cfg.enabled = v, "Yes", "No"), 60);
		header("Settings");
		FieldForm.build(this, type.fields(), cfg.settings, new FieldForm.SecretStore() {
			@Override
			public String get(String key) {
				return pendingSecrets.getOrDefault(key, "");
			}

			@Override
			public void set(String key, String value) {
				pendingSecrets.put(key, value);
			}
		}, () -> { });
		if (type instanceof NtfyType) {
			row(null).add(button("Random topic", "Fill in a long random topic name that nobody can guess", () -> {
				cfg.settings.addProperty("topic", "sculkwire_" + randomToken(16));
				rebuild();
			}), 110).live = () -> NtfyType.isPublicUnauthenticated(cfg)
					? List.of(Component.literal("Public ntfy.sh without a token: anyone who knows the topic can read your notifications.")
							.withStyle(ChatFormatting.GOLD))
					: List.of();
		}
		if (type instanceof WebhookType) {
			List<String> presets = new ArrayList<>(WebhookType.PRESETS.keySet());
			row("Body preset").add(cycle(presets, () -> currentPreset(presets), name -> {
				String[] p = WebhookType.PRESETS.get(name);
				cfg.settings.addProperty("content_type", p[0]);
				cfg.settings.addProperty("body", p[1]);
				rebuild();
			}, v -> v), -1);
		}
		header("Check");
		live(null, this::problems, GRAY);
	}

	private String currentPreset(List<String> presets) {
		String body = Json.str(cfg.settings, "body", WebhookType.DEFAULT_BODY);
		for (String name : presets) {
			if (WebhookType.PRESETS.get(name)[1].equals(body)) {
				return name;
			}
		}
		return presets.get(0);
	}

	private List<Component> problems() {
		List<Component> out = new ArrayList<>();
		if (type == null) {
			return out;
		}
		for (String p : type.validate(cfg, (id, key) -> pendingSecrets.getOrDefault(key, ""))) {
			out.add(Component.literal("✖ " + p).withStyle(ChatFormatting.RED));
		}
		if (out.isEmpty()) {
			out.add(Component.literal("✔ Ready").withStyle(ChatFormatting.GREEN));
		}
		if (!lastTest.isEmpty()) {
			out.add(Component.literal(lastTest).withStyle(ChatFormatting.GRAY));
		}
		return out;
	}

	private void save(boolean test) {
		SculkwireConfig all = rt.config();
		if (cfg.name == null || cfg.name.isBlank()) {
			cfg.name = type == null ? "Notifier" : type.displayName();
		}
		if (isNew) {
			cfg.id = all.uniqueId(cfg.name);
			all.notifiers.add(cfg);
		} else {
			for (int i = 0; i < all.notifiers.size(); i++) {
				if (all.notifiers.get(i).id.equals(originalId)) {
					all.notifiers.set(i, cfg);
				}
			}
		}
		pendingSecrets.forEach((k, v) -> rt.secrets.set(cfg.id, k, v));
		rt.saveSecrets();
		rt.apply(all);
		if (!test) {
			onClose();
			return;
		}
		lastTest = "Sending a test…";
		String name = cfg.displayName();
		rt.sendTest(cfg.id).thenAccept(e -> Minecraft.getInstance().execute(() -> {
			lastTest = e.status() == History.Status.SENT
					? "Test sent" + (e.httpStatus() > 0 ? " (HTTP " + e.httpStatus() + ", " + e.latencyMs() + " ms)" : "")
					: "Test failed: " + e.error();
			ClientRuntime.info(name + ": " + lastTest);
		}));
		if (isNew) {
			// reopen as an existing notifier so further saves update it
			minecraft.gui.setScreen(new NotifierEditScreen(parent, Json.copy(cfg, NotifierConfig.class), false));
		}
	}

	private void delete() {
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				SculkwireConfig all = rt.config();
				all.notifiers.removeIf(n -> n.id.equals(originalId));
				rt.secrets.removeNotifier(originalId);
				rt.saveSecrets();
				rt.apply(all);
				minecraft.gui.setScreen(parent);
			} else {
				minecraft.gui.setScreen(this);
			}
		}, Component.literal("Delete notifier '" + cfg.displayName() + "'?"),
				Component.literal("Events that send to it will skip it. Its secrets are removed too.")));
	}

	static String randomToken(int len) {
		String chars = "abcdefghijkmnopqrstuvwxyz23456789";
		SecureRandom r = new SecureRandom();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < len; i++) {
			sb.append(chars.charAt(r.nextInt(chars.length())));
		}
		return sb.toString();
	}
}
