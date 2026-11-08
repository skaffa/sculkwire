package dev.sculkwire.core;

import dev.sculkwire.core.config.ConfigStore;
import dev.sculkwire.core.config.ImportExport;
import dev.sculkwire.core.config.Presets;
import dev.sculkwire.core.config.Secrets;
import dev.sculkwire.core.engine.CompiledRule;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;
import dev.sculkwire.core.model.Target;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.value.Value;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
	@TempDir
	Path dir;

	@Test
	void saveLoadRoundTripUsesSnakeCase() throws Exception {
		ConfigStore store = new ConfigStore(dir);
		ConfigStore.Loaded fresh = store.load();
		assertTrue(fresh.fresh());
		SculkwireConfig cfg = fresh.config();
		cfg.notifiers.add(NotifierConfig.of("phone", "Phone", "ntfy"));
		Presets.instantiate(Presets.find("low-health"), cfg, List.of("phone"));
		cfg.settings.joinGraceSeconds = 3;
		store.save(cfg);
		String json = Files.readString(dir.resolve("config.json"));
		assertTrue(json.contains("\"check_every_ticks\""));
		assertTrue(json.contains("\"join_grace_seconds\": 3"));
		ConfigStore.Loaded again = store.load();
		assertFalse(again.fresh());
		assertEquals(1, again.config().rules.size());
		assertEquals("phone", again.config().rules.get(0).targets.get(0).notifier);
		assertEquals(3.0, again.config().settings.joinGraceSeconds);
	}

	@Test
	void brokenFileIsKeptAside() throws Exception {
		Files.writeString(dir.resolve("config.json"), "{ not json", StandardCharsets.UTF_8);
		ConfigStore.Loaded l = new ConfigStore(dir).load();
		assertTrue(l.fresh());
		assertEquals(1, l.warnings().size());
		assertFalse(Files.exists(dir.resolve("config.json")));
		try (var files = Files.list(dir)) {
			assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("config.json.broken-")));
		}
	}

	@Test
	void hollowJsonIsSanitized() throws Exception {
		Files.writeString(dir.resolve("config.json"), "{\"rules\":[{\"name\":\"x\",\"trigger\":null}],\"settings\":null}");
		SculkwireConfig cfg = new ConfigStore(dir).load().config();
		assertNotNull(cfg.settings);
		assertEquals("x", cfg.rules.get(0).id);
		assertNotNull(cfg.rules.get(0).trigger);
	}

	@Test
	void secretsWithEnvReferences() throws Exception {
		Path f = dir.resolve("secrets.json");
		Secrets s = new Secrets(f).withEnv(Map.of("NTFY_TOKEN", "tk_secret123")::get);
		s.set("phone", "token", "${env:NTFY_TOKEN}");
		s.set("discord", "url", "https://discord.com/api/webhooks/1/abc");
		s.save();
		Secrets s2 = new Secrets(f).withEnv(Map.of("NTFY_TOKEN", "tk_secret123")::get);
		assertEquals(null, s2.load());
		assertEquals("tk_secret123", s2.get("phone", "token"));
		assertEquals("${env:NTFY_TOKEN}", s2.raw("phone", "token"));
		assertTrue(s2.allValues().contains("https://discord.com/api/webhooks/1/abc"));
		s2.set("phone", "token", "");
		assertFalse(s2.has("phone", "token"));
	}

	@Test
	void importExportRoundTripRemapsNotifiersAndDisables() {
		SculkwireConfig src = new SculkwireConfig();
		src.notifiers.add(NotifierConfig.of("my-phone", "Phone", "ntfy"));
		src.variables.add(UserVariable.of("friends", Value.list(List.of("Alex"))));
		Rule r = Presets.instantiate(Presets.find("mentioned"), src, List.of("my-phone"));
		String json = ImportExport.export(List.of(r), src.variables);
		assertFalse(json.contains("settings"), "no notifier settings in exports");

		ImportExport.Imported imp = ImportExport.parse(json);
		assertTrue(imp.problems().isEmpty());
		assertEquals(List.of("my-phone"), imp.notifierRefs());
		SculkwireConfig dst = new SculkwireConfig();
		dst.notifiers.add(NotifierConfig.of("their-phone", "Phone", "ntfy"));
		List<Rule> added = ImportExport.apply(dst, imp, Map.of("my-phone", "their-phone"));
		assertEquals(1, added.size());
		assertFalse(added.get(0).enabled);
		assertEquals("their-phone", added.get(0).targets.get(0).notifier);
		assertEquals(1, dst.variables.size());

		assertFalse(ImportExport.parse("nonsense").problems().isEmpty());
		assertEquals(1, ImportExport.parse("{\"name\":\"single\",\"trigger\":{\"kind\":\"event\",\"event\":\"custom\"}}").rules().size());
	}

	@Test
	void everyPresetCompilesWithoutErrorsOrUnknownVariables() {
		List<Presets.Preset> presets = Presets.builtin();
		assertTrue(presets.size() >= 15, "presets loaded: " + presets.size());
		TestWorld w = new TestWorld();
		for (Presets.Preset p : presets) {
			Presets.instantiate(p, w.config, List.of("phone"));
		}
		w.engine.load(w.config);
		for (CompiledRule cr : w.engine.rules()) {
			assertTrue(cr.errors.isEmpty(), cr.rule.id + ": " + cr.errors);
			assertTrue(cr.warnings.isEmpty(), cr.rule.id + ": " + cr.warnings);
		}
	}

	@Test
	void uniqueIds() {
		SculkwireConfig cfg = new SculkwireConfig();
		Rule a = new Rule();
		a.id = "low-hp";
		cfg.rules.add(a);
		assertEquals("low-hp-2", cfg.uniqueId("Low HP"));
		assertEquals("item", cfg.uniqueId("!!!"));
		cfg.rules.get(0).targets.add(Target.of("x"));
	}
}
