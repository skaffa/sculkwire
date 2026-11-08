package dev.sculkwire.core;

import dev.sculkwire.core.template.OutputContext;
import dev.sculkwire.core.template.Snapshot;
import dev.sculkwire.core.template.Template;
import dev.sculkwire.core.template.VariableResolver;
import dev.sculkwire.core.value.Value;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateTest {
	private final Map<String, Value> vars = new HashMap<>();
	private final VariableResolver resolver = (name, params) -> {
		if (name.equals("nearby.hostiles")) {
			return Value.num(Double.parseDouble(params.getOrDefault("radius", "16")) / 2);
		}
		return vars.getOrDefault(name, Value.NONE);
	};

	private String r(String tpl) {
		return Template.parse(tpl).render(resolver, OutputContext.PLAIN);
	}

	@Test
	void substitutesAndKeepsLiterals() {
		vars.put("player.name", Value.str("Steve"));
		vars.put("player.health", Value.num(7));
		assertEquals("Steve has 7 HP", r("{player.name} has {player.health} HP"));
		assertEquals("{literal} braces", r("{{literal}} braces"));
		assertEquals("missing: ", r("missing: {nope}"));
	}

	@Test
	void regexQuantifiersStayLiteral() {
		Template t = Template.parse("\\d{2,3} and {player.name}");
		assertEquals(1, t.expressions().size());
		vars.put("player.name", Value.str("Al.ex"));
		assertEquals("\\d{2,3} and \\QAl.ex\\E", t.render(resolver, OutputContext.REGEX));
	}

	@Test
	void params() {
		assertEquals("4", r("{nearby.hostiles(radius=8)}"));
		assertEquals("nearby.hostiles(radius=8)", Template.parse("{nearby.hostiles(radius=8)|int}").expressions().get(0).key());
	}

	@Test
	void filters() {
		vars.put("hp", Value.num(7));
		vars.put("t", Value.num(13000));
		vars.put("s", Value.num(3725));
		vars.put("dim", Value.id("minecraft:the_nether"));
		vars.put("list", Value.list(List.of("a", "b", "c")));
		vars.put("x", Value.num(-12.7));
		vars.put("p", Value.num(0.456));
		vars.put("txt", Value.str("§aGreen§r text"));
		assertEquals("3.5", r("{hp|hearts}"));
		assertEquals("19:00", r("{t|clock}"));
		assertEquals("1h 2m 5s", r("{s|duration}"));
		assertEquals("The Nether", r("{dim|pretty}"));
		assertEquals("a / b / c", r("{list|join:' / '}"));
		assertEquals("3", r("{list|count}"));
		assertEquals("-13", r("{x|coords}"));
		assertEquals("-12.70", r("{x|fixed:2}"));
		assertEquals("46%", r("{p|percent}"));
		assertEquals("Green text", r("{txt|strip}"));
		assertEquals("fallback", r("{none|default:'fallback'}"));
		assertEquals("STEVE", r("{n|default:'steve'|upper}"));
		assertEquals("ab…", r("{long|default:'abcdef'|truncate:3}"));
		assertEquals("14", r("{hp|mul:2}"));
	}

	@Test
	void escapingAppliesToValuesOnly() {
		vars.put("msg", Value.str("say \"hi\"\n@everyone *bold*"));
		Template t = Template.parse("{\"text\": \"{msg}\"}");
		assertEquals("{\"text\": \"say \\\"hi\\\"\\n@everyone *bold*\"}", t.render(resolver, OutputContext.JSON_STRING));
		String d = Template.parse("**{msg}**").render(resolver, OutputContext.DISCORD_MARKDOWN);
		assertTrue(d.startsWith("**"));
		assertTrue(d.contains("\\*bold\\*"));
		assertFalse(d.contains("@everyone"));
		assertEquals("a%20b", Template.parse("{v|default:'a b'}").render(resolver, OutputContext.URL_COMPONENT));
		vars.put("nl", Value.str("x\ny"));
		assertEquals("x y", Template.parse("{nl}").render(resolver, OutputContext.HEADER));
		vars.put("raw", Value.str("a.b"));
		assertEquals("a.b", Template.parse("{raw|raw}").render(resolver, OutputContext.REGEX));
	}

	@Test
	void problemsReportUnknownNamesAndFilters() {
		Set<String> known = Set.of("player.name");
		List<String> p = Template.parse("{player.name|upper} {foo} {player.name|bogus}").problems(known::contains);
		assertEquals(2, p.size());
		assertTrue(p.get(0).contains("{foo}"));
		assertTrue(p.get(1).contains("bogus"));
	}

	@Test
	void snapshotCapturesValuesForLater() {
		vars.put("player.name", Value.str("Steve"));
		Template t = Template.parse("hi {player.name}");
		Snapshot s = Snapshot.capture(List.of(t), resolver);
		vars.put("player.name", Value.str("Changed"));
		assertEquals("hi Steve", t.render(s, OutputContext.PLAIN));
	}
}
