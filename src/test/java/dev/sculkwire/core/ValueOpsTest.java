package dev.sculkwire.core;

import dev.sculkwire.core.util.Glob;
import dev.sculkwire.core.util.Redact;
import dev.sculkwire.core.util.SafeRegex;
import dev.sculkwire.core.value.Op;
import dev.sculkwire.core.value.Ops;
import dev.sculkwire.core.value.TagResolver;
import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueOpsTest {
	private static boolean t(Op op, Object l, Object a) {
		return Ops.test(op, Value.of(l), Value.of(a), null, false, TagResolver.NONE);
	}

	@Test
	void numberFormattingAndCoercion() {
		assertEquals("20", Value.num(20).asString());
		assertEquals("3.5", Value.num(3.5).asString());
		assertEquals("0.33", Value.num(1.0 / 3).asString());
		assertEquals(6.0, Value.str("6").coerce(ValueType.NUMBER).asNumber());
		assertEquals(List.of("a", "b"), Value.str("a, b").coerce(ValueType.LIST).asList());
		assertTrue(Value.str("yes").asBool());
		assertEquals("minecraft:the_nether", Value.id("THE_NETHER").asString());
		assertEquals("#minecraft:logs", Value.normalizeId("#logs"));
	}

	@Test
	void numericOperators() {
		assertTrue(t(Op.LT, 4, 6));
		assertFalse(t(Op.LT, 6, 6));
		assertTrue(t(Op.LTE, 6, 6));
		assertTrue(t(Op.GT, 7, "6"));
		assertTrue(t(Op.EQ, 6, "6.0"));
		assertTrue(t(Op.NEQ, 5, 6));
		assertTrue(Ops.test(Op.BETWEEN, Value.num(5), Value.num(1), Value.num(10), false, null));
		assertTrue(Ops.test(Op.BETWEEN, Value.num(5), Value.num(10), Value.num(1), false, null));
		assertTrue(Ops.test(Op.OUTSIDE, Value.num(11), Value.num(1), Value.num(10), false, null));
	}

	@Test
	void noneIsNeverEqualOrUnequal() {
		assertFalse(Ops.test(Op.EQ, Value.NONE, Value.num(1), null, false, null));
		assertFalse(Ops.test(Op.NEQ, Value.NONE, Value.num(1), null, false, null));
		assertFalse(Ops.test(Op.LT, Value.num(1), Value.NONE, null, false, null));
		assertFalse(Ops.test(Op.IS_AVAILABLE, Value.NONE, null, null, false, null));
		assertTrue(Ops.test(Op.IS_AVAILABLE, Value.num(0), null, null, false, null));
	}

	@Test
	void stringOperators() {
		assertTrue(Ops.test(Op.CONTAINS, Value.str("Hello Steve"), Value.str("steve"), null, true, null));
		assertFalse(Ops.test(Op.CONTAINS, Value.str("Hello Steve"), Value.str("steve"), null, false, null));
		assertTrue(t(Op.STARTS_WITH, "Hello", "He"));
		assertTrue(t(Op.ENDS_WITH, "Hello", "lo"));
		assertTrue(t(Op.MATCHES, "restarting in 5", "restart(ing)?"));
		assertFalse(t(Op.MATCHES, "abc", "(")); // bad regex is just false
		assertTrue(t(Op.IN, "Alex", List.of("Steve", "Alex")));
		assertTrue(t(Op.IN, "Alex", "Steve, Alex"));
		assertTrue(t(Op.NOT_IN, "Bob", List.of("Steve", "Alex")));
		assertTrue(Ops.test(Op.IS_EMPTY, Value.str(""), null, null, false, null));
	}

	@Test
	void idsAndTags() {
		TagResolver tags = (id, tag) -> tag.equals("#minecraft:logs") && id.endsWith("_log");
		assertTrue(Ops.test(Op.EQ, Value.id("minecraft:the_nether"), Value.str("the_nether"), null, false, tags));
		assertTrue(Ops.test(Op.EQ, Value.id("oak_log"), Value.str("#logs"), null, false, tags));
		assertTrue(Ops.test(Op.IN_TAG, Value.id("oak_log"), Value.str("logs"), null, false, tags));
		assertTrue(Ops.test(Op.IN, Value.id("minecraft:the_end"), Value.list(List.of("the_nether", "the_end")), null, false, tags));
	}

	@Test
	void listOperators() {
		Value l = Value.list(List.of("minecraft:speed", "minecraft:haste"));
		assertTrue(Ops.test(Op.CONTAINS, l, Value.str("speed"), null, false, null));
		assertTrue(Ops.test(Op.CONTAINS_ANY, l, Value.list(List.of("regeneration", "haste")), null, false, null));
		assertTrue(Ops.test(Op.SIZE_EQ, l, Value.num(2), null, false, null));
		assertTrue(Ops.test(Op.SIZE_GT, l, Value.num(1), null, false, null));
	}

	@Test
	void operatorsPerType() {
		assertTrue(Op.forType(ValueType.NUMBER).contains(Op.LT));
		assertFalse(Op.forType(ValueType.BOOL).contains(Op.LT));
		assertTrue(Op.forType(ValueType.ID).contains(Op.IN_TAG));
		assertEquals(Op.LTE, Op.parse("<="));
	}

	@Test
	void regexTimesOut() {
		Pattern evil = SafeRegex.compile("(a+)+b", false);
		long start = System.nanoTime();
		assertTrue(SafeRegex.find(evil, "a".repeat(40) + "c", 20).isEmpty());
		assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
	}

	@Test
	void globMatching() {
		assertTrue(Glob.matches("*.example.net", "mc.example.net"));
		assertTrue(Glob.matches("*", "anything"));
		assertFalse(Glob.matches("*.example.net", "example.org"));
		assertTrue(Glob.matches("Play.Example.NET", "play.example.net"));
	}

	@Test
	void redaction() {
		assertEquals("https://discord.com/api/webhooks/123/****",
				Redact.text("https://discord.com/api/webhooks/123/abcDEF-ghi_jkl"));
		assertEquals("https://api.telegram.org/bot****/sendMessage",
				Redact.text("https://api.telegram.org/bot123456:AAH-xyz/sendMessage"));
		assertEquals("token tk_****", Redact.text("token tk_abcdefghijkl"));
		assertEquals("x **** y", Redact.text("x hunter2secret y", List.of("hunter2secret")));
	}
}
