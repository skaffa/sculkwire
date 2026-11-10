package dev.sculkwire.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.sculkwire.core.engine.CompiledRule;
import dev.sculkwire.core.model.Condition;
import dev.sculkwire.core.model.MessageTemplate;
import dev.sculkwire.core.model.Operand;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.Trigger;
import dev.sculkwire.core.model.UserVariable;
import dev.sculkwire.core.notify.Priority;
import dev.sculkwire.core.value.Value;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {
	private static Rule healthRule(boolean withRearm) {
		Rule r = new Rule();
		r.name = "low hp";
		r.trigger = Trigger.condition(Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6)));
		r.trigger.checkEveryTicks = 1;
		if (withRearm) {
			r.trigger.rearm = Condition.compare(Operand.signal("player.health"), "gte", Operand.value(10));
		}
		r.message = MessageTemplate.of("Low HP", "{player.health|hearts} hearts");
		return r;
	}

	private static Rule chatRule(String mode, String pattern) {
		Rule r = new Rule();
		r.name = "chat " + mode;
		r.trigger = Trigger.event("chat.message");
		r.trigger.params.addProperty("mode", mode);
		if (pattern != null) {
			r.trigger.params.addProperty("pattern", pattern);
		}
		r.message = MessageTemplate.of("{msg.sender}", "{msg.text} / {match.1}");
		return r;
	}

	private static Map<String, Value> chat(String sender, String text, boolean own) {
		return Map.of("msg.text", Value.str(text), "msg.sender", Value.str(sender), "msg.kind", Value.enumOf("player"),
				"msg.is_own", Value.bool(own));
	}

	@Test
	void onEnterFiresOnceAndRearmsWithHysteresis() {
		TestWorld w = new TestWorld().set("player.health", 20).add(healthRule(true)).join();
		w.set("player.health", 4).ticks(5);
		assertEquals(1, w.count());
		assertEquals("2 hearts", w.lastBody());
		w.set("player.health", 7).ticks(5).set("player.health", 4).ticks(5);
		assertEquals(1, w.count(), "7 HP is below the re-arm threshold");
		w.set("player.health", 12).ticks(5).set("player.health", 3).ticks(5);
		assertEquals(2, w.count());
	}

	@Test
	void conditionAlreadyTrueOnJoinIsPrimedNotFired() {
		TestWorld w = new TestWorld().set("player.health", 4).add(healthRule(false)).join();
		assertEquals(0, w.count());
		w.set("player.health", 15).ticks(3).set("player.health", 4).ticks(3);
		assertEquals(1, w.count());
	}

	@Test
	void fireOnJoinSkipsPriming() {
		Rule r = healthRule(false);
		r.throttle.fireOnJoin = true;
		TestWorld w = new TestWorld().set("player.health", 4).add(r).join();
		assertEquals(1, w.count());
	}

	@Test
	void debounceRequiresConditionToHold() {
		Rule r = healthRule(false);
		r.trigger.forSeconds = 2;
		TestWorld w = new TestWorld().set("player.health", 20).add(r).join();
		w.set("player.health", 4).seconds(1).set("player.health", 20).seconds(1);
		assertEquals(0, w.count());
		w.set("player.health", 4).seconds(2.5);
		assertEquals(1, w.count());
	}

	@Test
	void whileTrueRepeats() {
		Rule r = healthRule(false);
		r.trigger.mode = "while_true";
		r.trigger.repeatSeconds = 5;
		TestWorld w = new TestWorld().set("player.health", 20).add(r).join();
		w.set("player.health", 4).seconds(11);
		assertEquals(3, w.count());
	}

	@Test
	void onExitFiresWhenConditionEnds() {
		Rule r = healthRule(false);
		r.trigger.mode = "on_exit";
		TestWorld w = new TestWorld().set("player.health", 20).add(r).join();
		w.set("player.health", 4).ticks(3);
		assertEquals(0, w.count());
		w.set("player.health", 20).ticks(3);
		assertEquals(1, w.count());
	}

	@Test
	void changeTriggerWithToFilterAndOldNew() {
		Rule r = new Rule();
		r.name = "storm";
		r.trigger = Trigger.change("world.weather");
		r.trigger.to = new JsonPrimitive("thunder");
		r.trigger.checkEveryTicks = 1;
		r.message = MessageTemplate.of("Storm", "{old} -> {new}");
		TestWorld w = new TestWorld().set("world.weather", Value.enumOf("clear")).add(r).join();
		w.set("world.weather", Value.enumOf("rain")).ticks(2);
		assertEquals(0, w.count());
		w.set("world.weather", Value.enumOf("thunder")).ticks(2);
		assertEquals(1, w.count());
		assertEquals("rain -> thunder", w.lastBody());
	}

	@Test
	void changeTriggerMinDelta() {
		Rule r = new Rule();
		r.name = "moved";
		r.trigger = Trigger.change("player.y");
		r.trigger.minDelta = 10;
		r.trigger.checkEveryTicks = 1;
		TestWorld w = new TestWorld().set("player.y", 64).add(r).join();
		w.set("player.y", 60).ticks(2).set("player.y", 57).ticks(2);
		assertEquals(0, w.count());
		w.set("player.y", 53).ticks(2);
		assertEquals(1, w.count(), "drifts add up to 11");
	}

	@Test
	void scheduleEverySecondsAndWorldTime() {
		Rule every = new Rule();
		every.name = "tick";
		every.trigger = Trigger.every(10);
		TestWorld w = new TestWorld().add(every).join();
		w.seconds(25);
		assertEquals(2, w.count());

		Rule night = new Rule();
		night.name = "night";
		night.trigger = new Trigger();
		night.trigger.kind = Trigger.SCHEDULE;
		night.trigger.atWorldTime = 13000;
		TestWorld w2 = new TestWorld().set("world.time_of_day", 12900).add(night).join();
		w2.set("world.time_of_day", 12990).ticks(10).set("world.time_of_day", 13010).ticks(10);
		assertEquals(1, w2.count());
		w2.set("world.time_of_day", 23990).ticks(10).set("world.time_of_day", 10).ticks(10);
		assertEquals(1, w2.count());
	}

	@Test
	void throttles() {
		Rule r = healthRule(false);
		r.throttle.cooldownSeconds = 30;
		TestWorld w = new TestWorld().set("player.health", 20).add(r).join();
		for (int i = 0; i < 3; i++) {
			w.set("player.health", 4).ticks(2).set("player.health", 20).ticks(2);
		}
		assertEquals(1, w.count());
		assertEquals("cooldown", w.engine.status(r.id).outcome());
		w.seconds(31).set("player.health", 4).ticks(2);
		assertEquals(2, w.count());

		Rule once = healthRule(false);
		once.name = "once";
		once.throttle.oncePerSession = true;
		TestWorld w2 = new TestWorld().set("player.health", 20).add(once).join();
		w2.set("player.health", 4).ticks(2).set("player.health", 20).ticks(2).set("player.health", 4).ticks(2);
		assertEquals(1, w2.count());
		w2.join().set("player.health", 20).ticks(2).set("player.health", 4).ticks(2);
		assertEquals(2, w2.count(), "new session after re-join");

		Rule hourly = healthRule(false);
		hourly.name = "hourly";
		hourly.throttle.maxPerHour = 2;
		TestWorld w3 = new TestWorld().set("player.health", 20).add(hourly).join();
		for (int i = 0; i < 5; i++) {
			w3.set("player.health", 4).ticks(2).set("player.health", 20).ticks(2);
		}
		assertEquals(2, w3.count());
	}

	@Test
	void muteQuietHoursAndFocus() {
		Rule r = healthRule(false);
		TestWorld w = new TestWorld().set("player.health", 20).add(r).join();
		w.config.settings.muteUntil = w.clock.t + 60_000;
		w.set("player.health", 4).ticks(2);
		assertEquals(0, w.count());
		assertEquals("muted", w.engine.status(r.id).outcome());

		Rule f = healthRule(false);
		f.name = "unfocused";
		f.throttle.onlyWhenUnfocused = true;
		TestWorld w2 = new TestWorld().set("player.health", 20).add(f).join();
		w2.set("player.health", 4).ticks(2);
		assertEquals(0, w2.count());
		w2.set("player.health", 20).set("session.window_focused", false).ticks(2).set("player.health", 4).ticks(2);
		assertEquals(1, w2.count());

		TestWorld w3 = new TestWorld();
		w3.config.settings.quietHours.enabled = true;
		w3.config.settings.quietHours.start = "00:00";
		w3.config.settings.quietHours.end = "23:59";
		w3.set("player.health", 20).add(healthRule(false)).join();
		w3.set("player.health", 4).ticks(2);
		assertEquals(0, w3.count());
	}

	@Test
	void scopeByServerAndDimension() {
		Rule r = healthRule(false);
		r.scope.servers = List.of("*.example.net");
		r.scope.dimensions = List.of("the_nether");
		TestWorld w = new TestWorld().set("world.is_singleplayer", false).set("server.address", "mc.other.org")
				.set("player.dimension", Value.id("the_nether")).set("player.health", 20).add(r).join();
		w.set("player.health", 4).ticks(2);
		assertEquals(0, w.count());
		w.set("server.address", "mc.example.net").set("player.health", 20).ticks(2).set("player.health", 4).ticks(2);
		assertEquals(1, w.count());
	}

	@Test
	void chatMentionsAndRegexGroups() {
		TestWorld w = new TestWorld().add(chatRule("mentions_me", null)).join();
		w.engine.post("chat.message", chat("Alex", "hey steve, look", false));
		w.engine.post("chat.message", chat("Alex", "steveo is not you", false));
		w.engine.post("chat.message", chat("Steve", "I am steve", true));
		assertEquals(1, w.count());
		assertEquals("Alex", w.lastTitle());

		TestWorld w2 = new TestWorld().add(chatRule("regex", "restart in (\\d+)")).join();
		w2.engine.post("chat.message", chat("Server", "Restart in 5 minutes", false));
		assertEquals(1, w2.count());
		assertEquals("Restart in 5 minutes / 5", w2.lastBody());
	}

	@Test
	void chatSendersFromListVariableAndSenderRegex() {
		Rule r = chatRule("any", null);
		JsonArray senders = new JsonArray();
		senders.add("{friends}");
		r.trigger.params.add("senders", senders);
		r.trigger.params.addProperty("sender_regex", "^\\[\\w+\\] (?<name>\\w+): (?<msg>.*)$");
		TestWorld w = new TestWorld();
		w.config.variables.add(UserVariable.of("friends", Value.list(List.of("Alex", "Notch"))));
		w.add(r).join();
		Map<String, Value> sys = Map.of("msg.text", Value.str("[VIP] Notch: hello all"), "msg.kind", Value.enumOf("system"),
				"msg.is_own", Value.FALSE);
		w.engine.post("chat.message", sys);
		w.engine.post("chat.message", chat("Bob", "hi", false));
		assertEquals(1, w.count());
		assertEquals("Notch", w.lastTitle());
		assertTrue(w.lastBody().startsWith("hello all"));
	}

	@Test
	void hasSenderSkipsServerMessages() {
		Rule r = chatRule("any", null);
		r.trigger.params.addProperty("sender_regex", "^\\[\\w+\\] (?<name>\\w+): (?<msg>.*)$");
		Operand hasSender = new Operand();
		hasSender.payload = "msg.has_sender";
		r.conditions = Condition.compare(hasSender, "is_true");
		TestWorld w = new TestWorld().add(r).join();
		assertTrue(w.engine.rule(r.id).errors.isEmpty(), w.engine.rule(r.id).errors.toString());
		w.engine.post("chat.message", Map.of("msg.text", Value.str("Set own game mode to Creative Mode"), "msg.sender", Value.str(""),
				"msg.has_sender", Value.FALSE, "msg.kind", Value.enumOf("system"), "msg.is_own", Value.FALSE));
		assertEquals(0, w.count());
		w.engine.post("chat.message", chat("Alex", "hi", false));
		assertEquals("Alex", w.lastTitle());
		w.engine.post("chat.message", Map.of("msg.text", Value.str("[VIP] Notch: hello"), "msg.sender", Value.str(""),
				"msg.has_sender", Value.FALSE, "msg.kind", Value.enumOf("system"), "msg.is_own", Value.FALSE));
		assertEquals(2, w.count());
		assertEquals("Notch", w.lastTitle());
	}

	@Test
	void eventParamBindings() {
		Rule r = new Rule();
		r.name = "nether";
		r.trigger = Trigger.event("player.dimension_changed");
		JsonArray to = new JsonArray();
		to.add("the_nether");
		r.trigger.params.add("to", to);
		TestWorld w = new TestWorld().add(r).join();
		w.engine.post("player.dimension_changed", Map.of("old", Value.id("overworld"), "new", Value.id("the_end")));
		w.engine.post("player.dimension_changed", Map.of("old", Value.id("overworld"), "new", Value.id("the_nether")));
		assertEquals(1, w.count());

		Rule dmg = new Rule();
		dmg.name = "big hit";
		dmg.trigger = Trigger.event("player.damaged");
		dmg.trigger.params.addProperty("min_amount", 5);
		TestWorld w2 = new TestWorld().add(dmg).join();
		w2.engine.post("player.damaged", Map.of("damage.amount", Value.num(2)));
		w2.engine.post("player.damaged", Map.of("damage.amount", Value.num(6)));
		assertEquals(1, w2.count());
	}

	@Test
	void conditionsGroupsAndChangedOperator() {
		Rule r = new Rule();
		r.name = "group";
		r.trigger = Trigger.condition(Condition.group("all",
				Condition.compare(Operand.signal("player.health"), "lt", Operand.value(10)),
				Condition.group("any",
						Condition.compare(Operand.signal("player.dimension"), "eq", Operand.value("the_nether")),
						Condition.compare(Operand.signal("player.on_fire"), "is_true"))));
		r.trigger.checkEveryTicks = 1;
		TestWorld w = new TestWorld().set("player.health", 5).set("player.dimension", Value.id("overworld"))
				.set("player.on_fire", false).add(r).join();
		w.set("player.on_fire", true).ticks(2);
		assertEquals(1, w.count());

		Rule c = new Rule();
		c.name = "changed";
		c.trigger = Trigger.condition(Condition.compare(Operand.signal("player.biome"), "changed"));
		c.trigger.checkEveryTicks = 1;
		TestWorld w2 = new TestWorld().set("player.biome", Value.id("plains")).add(c).join();
		w2.set("player.biome", Value.id("forest")).ticks(1);
		assertEquals(1, w2.count());
	}

	@Test
	void validationErrors() {
		TestWorld w = new TestWorld();
		Rule bad = new Rule();
		bad.name = "bad";
		bad.trigger = Trigger.condition(Condition.compare(Operand.signal("player.nope"), "lt", Operand.value(1)));
		Rule typeMismatch = new Rule();
		typeMismatch.name = "mismatch";
		typeMismatch.trigger = Trigger.condition(Condition.compare(Operand.signal("player.on_fire"), "lt", Operand.value(1)));
		Rule badRegex = chatRule("regex", "x");
		badRegex.name = "regex";
		badRegex.conditions = Condition.compare(Operand.payload("msg.text"), "matches", Operand.value("(unclosed"));
		Rule noEvent = new Rule();
		noEvent.name = "noevent";
		noEvent.trigger = Trigger.event("does.not.exist");
		w.add(bad).add(typeMismatch).add(badRegex).add(noEvent);
		for (CompiledRule cr : w.engine.rules()) {
			assertFalse(cr.errors.isEmpty(), cr.rule.name + " should have errors");
			assertFalse(cr.active());
		}
		Rule warn = healthRule(false);
		warn.name = "warn";
		warn.message = MessageTemplate.of("{unknown.var}", "");
		w.add(warn);
		CompiledRule cw = w.engine.rule(warn.id);
		assertTrue(cw.errors.isEmpty());
		assertTrue(cw.warnings.stream().anyMatch(s -> s.contains("unknown.var")));
	}

	@Test
	void testFireUsesSamplesAndOverridesAndDisabledNotifiers() {
		Rule r = chatRule("contains", "x");
		dev.sculkwire.core.model.Target t2 = dev.sculkwire.core.model.Target.of("discord");
		t2.override = new MessageTemplate();
		t2.override.priority = "urgent";
		r.targets.add(dev.sculkwire.core.model.Target.of("phone"));
		r.targets.add(t2);
		TestWorld w = new TestWorld();
		w.config.notifiers.add(dev.sculkwire.core.model.NotifierConfig.of("discord", "Discord", "discord"));
		w.add(r).join();
		assertTrue(w.engine.testFire(r.id));
		assertEquals(2, w.count());
		assertEquals("Alex", w.sent.get(0).notification().title(dev.sculkwire.core.template.OutputContext.PLAIN));
		assertTrue(w.sent.get(0).notification().test());
		assertEquals(Priority.URGENT, w.sent.get(1).notification().priority());

		w.config.notifier("discord").enabled = false;
		w.engine.load(w.config);
		w.engine.post("chat.message", chat("Alex", "x marks", false));
		assertEquals(3, w.count(), "disabled notifier gets nothing");
	}

	@Test
	void variablesInConditions() {
		Rule r = new Rule();
		r.name = "threshold";
		r.trigger = Trigger.condition(Condition.compare(Operand.signal("player.health"), "lt", Operand.var("min_hp")));
		r.trigger.checkEveryTicks = 1;
		TestWorld w = new TestWorld();
		w.config.variables.add(UserVariable.of("min_hp", Value.num(8)));
		w.set("player.health", 20).add(r).join();
		w.set("player.health", 7).ticks(2);
		assertEquals(1, w.count());
		w.engine.setVariable("min_hp", Value.num(3));
		w.set("player.health", 20).ticks(2).set("player.health", 7).ticks(2);
		assertEquals(1, w.count());
	}

	private static dev.sculkwire.core.model.Send send(String title) {
		return dev.sculkwire.core.model.Send.of(MessageTemplate.of(title, "{player.health}"), "phone");
	}

	@Test
	void ifElseIfElseSendsFirstMatchingBranch() {
		Rule r = new Rule();
		r.name = "branches";
		r.trigger = Trigger.event("custom");
		r.branches = new java.util.ArrayList<>(List.of(
				dev.sculkwire.core.model.Branch.of(Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6)), send("low")),
				dev.sculkwire.core.model.Branch.of(Condition.compare(Operand.signal("player.health"), "lt", Operand.value(12)), send("mid")),
				dev.sculkwire.core.model.Branch.of(null, send("ok"))));
		TestWorld w = new TestWorld().add(r).join();
		assertTrue(w.engine.rule(r.id).errors.isEmpty(), w.engine.rule(r.id).errors.toString());
		for (int hp : new int[] {4, 10, 20}) {
			w.set("player.health", hp).ticks(1); // signal reads are cached per tick
			w.engine.post("custom", Map.of("name", Value.str("x")));
		}
		assertEquals(3, w.count());
		assertEquals("low", w.sent.get(0).notification().title(dev.sculkwire.core.template.OutputContext.PLAIN));
		assertEquals("mid", w.sent.get(1).notification().title(dev.sculkwire.core.template.OutputContext.PLAIN));
		assertEquals("ok", w.lastTitle());
		w.set("player.health", 5).ticks(1);
		assertTrue(w.engine.testFire(r.id));
		assertEquals("low", w.lastTitle());
		assertEquals(0, w.engine.previewBranch(w.engine.rule(r.id)));
	}

	@Test
	void noMatchingBranchSendsNothingAndElseMustBeLast() {
		Rule r = new Rule();
		r.name = "only-if";
		r.trigger = Trigger.event("custom");
		r.branches = new java.util.ArrayList<>(List.of(
				dev.sculkwire.core.model.Branch.of(Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6)), send("low"))));
		TestWorld w = new TestWorld().set("player.health", 20).add(r).join();
		w.engine.post("custom", Map.of());
		assertEquals(0, w.count());
		assertEquals("no block matched", w.engine.status(r.id).outcome());

		Rule bad = new Rule();
		bad.name = "bad-else";
		bad.trigger = Trigger.event("custom");
		bad.branches = new java.util.ArrayList<>(List.of(dev.sculkwire.core.model.Branch.of(null, send("a")),
				dev.sculkwire.core.model.Branch.of(Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6)), send("b"))));
		w.add(bad);
		assertFalse(w.engine.rule(bad.id).errors.isEmpty());
	}

	@Test
	void sendBlocksAndSeveralChainsAllRun() {
		Rule r = new Rule();
		r.name = "script";
		r.trigger = Trigger.event("custom");
		Condition low = Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6));
		Condition night = Condition.compare(Operand.signal("world.is_night"), "is_true");
		r.branches = new java.util.ArrayList<>(List.of(
				dev.sculkwire.core.model.Branch.typed("do", null, send("first")),
				dev.sculkwire.core.model.Branch.typed("if", low, send("low")),
				dev.sculkwire.core.model.Branch.typed("else", null, send("fine")),
				dev.sculkwire.core.model.Branch.typed("if", night, send("night")),
				dev.sculkwire.core.model.Branch.typed("do", null, send("last"))));
		TestWorld w = new TestWorld().set("player.health", 4).set("world.is_night", false).add(r).join();
		assertTrue(w.engine.rule(r.id).errors.isEmpty(), w.engine.rule(r.id).errors.toString());
		w.engine.post("custom", Map.of());
		List<String> titles = w.sent.stream().map(d -> d.notification().title(dev.sculkwire.core.template.OutputContext.PLAIN)).toList();
		assertEquals(List.of("first", "low", "last"), titles);
		assertEquals(List.of(0, 1, 4), w.engine.previewBlocks(w.engine.rule(r.id)));

		Rule bad = new Rule();
		bad.name = "orphan else";
		bad.trigger = Trigger.event("custom");
		bad.branches = new java.util.ArrayList<>(List.of(dev.sculkwire.core.model.Branch.typed("do", null, send("a")),
				dev.sculkwire.core.model.Branch.typed("else", null, send("b"))));
		w.add(bad);
		assertFalse(w.engine.rule(bad.id).errors.isEmpty());
	}

	@Test
	void nestedBlocksRunOnlyInsideTheirParent() {
		Condition low = Condition.compare(Operand.signal("player.health"), "lt", Operand.value(6));
		Condition night = Condition.compare(Operand.signal("world.is_night"), "is_true");
		dev.sculkwire.core.model.Branch outer = dev.sculkwire.core.model.Branch.typed("if", low, send("low"));
		outer.blocks = new java.util.ArrayList<>(List.of(
				dev.sculkwire.core.model.Branch.typed("if", night, send("low at night")),
				dev.sculkwire.core.model.Branch.typed("else", null, send("low by day")),
				dev.sculkwire.core.model.Branch.typed("do", null, send("low done"))));
		Rule r = new Rule();
		r.name = "nested";
		r.trigger = Trigger.event("custom");
		r.branches = new java.util.ArrayList<>(List.of(outer,
				dev.sculkwire.core.model.Branch.typed("else", null, send("fine"))));
		TestWorld w = new TestWorld().set("player.health", 4).set("world.is_night", true).add(r).join();
		assertTrue(w.engine.rule(r.id).errors.isEmpty(), w.engine.rule(r.id).errors.toString());
		w.engine.post("custom", Map.of());
		w.set("world.is_night", false).ticks(1);
		w.engine.post("custom", Map.of());
		w.set("player.health", 20).ticks(1);
		w.engine.post("custom", Map.of());
		List<String> titles = w.sent.stream().map(d -> d.notification().title(dev.sculkwire.core.template.OutputContext.PLAIN)).toList();
		assertEquals(List.of("low", "low at night", "low done", "low", "low by day", "low done", "fine"), titles);
		assertEquals(List.of(r.branches.get(1)), w.engine.previewRunning(w.engine.rule(r.id)));
		assertEquals(List.of("phone"), r.notifierIds());

		Rule bad = new Rule();
		bad.name = "nested orphan else";
		bad.trigger = Trigger.event("custom");
		dev.sculkwire.core.model.Branch parent = dev.sculkwire.core.model.Branch.typed("if", low);
		parent.blocks = new java.util.ArrayList<>(List.of(dev.sculkwire.core.model.Branch.typed("else", null, send("b"))));
		bad.branches = new java.util.ArrayList<>(List.of(parent));
		w.add(bad);
		assertFalse(w.engine.rule(bad.id).errors.isEmpty());
	}

	@Test
	void stateRulesOnlyRunInAWorld() {
		Rule every = new Rule();
		every.name = "every";
		every.trigger = Trigger.every(5);
		TestWorld w = new TestWorld().add(every);
		w.seconds(20);
		assertEquals(0, w.count(), "not joined yet");
		w.join().seconds(12);
		assertEquals(2, w.count());
		w.engine.onDisconnect();
		w.seconds(20);
		assertEquals(2, w.count(), "disconnected");
	}

	@Test
	void eventRulesIgnoreParamsTheyDoNotHave() {
		Rule r = new Rule();
		r.name = "custom";
		r.trigger = Trigger.event("custom");
		r.trigger.params = new JsonObject();
		TestWorld w = new TestWorld().add(r).join();
		w.engine.post("custom", Map.of("name", Value.str("anything")));
		assertEquals(1, w.count());
	}
}
