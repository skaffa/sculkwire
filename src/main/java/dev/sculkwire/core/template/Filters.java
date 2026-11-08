package dev.sculkwire.core.template;

import dev.sculkwire.core.value.Value;
import dev.sculkwire.core.value.ValueType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Template filters: {@code {value|filter|filter:arg}}. Unknown filters leave the value unchanged. */
public final class Filters {
	public static final Set<String> NAMES = Set.of(
			"upper", "lower", "title", "pretty", "trim", "int", "round", "fixed", "percent", "hearts", "clock",
			"duration", "date", "default", "truncate", "replace", "join", "count", "first", "last", "yesno",
			"coords", "strip", "abs", "add", "sub", "mul", "div", "raw");

	private Filters() {
	}

	public static Value apply(String name, List<String> args, Value v) {
		String a0 = args.isEmpty() ? null : args.get(0);
		switch (name) {
			case "upper":
				return v.isNone() ? v : Value.str(v.asString().toUpperCase(Locale.ROOT));
			case "lower":
				return v.isNone() ? v : Value.str(v.asString().toLowerCase(Locale.ROOT));
			case "title":
				return v.isNone() ? v : Value.str(title(v.asString()));
			case "pretty":
				return v.isNone() ? v : Value.str(pretty(v));
			case "trim":
				return v.isNone() ? v : Value.str(v.asString().trim());
			case "int":
				return num(v, d -> (double) (long) d);
			case "round":
				return num(v, d -> (double) Math.round(d));
			case "abs":
				return num(v, Math::abs);
			case "add":
				return num(v, d -> d + parse(a0, 0));
			case "sub":
				return num(v, d -> d - parse(a0, 0));
			case "mul":
				return num(v, d -> d * parse(a0, 1));
			case "div": {
				double by = parse(a0, 1);
				return by == 0 ? Value.NONE : num(v, d -> d / by);
			}
			case "fixed": {
				double d = v.asNumber();
				if (Double.isNaN(d)) {
					return Value.NONE;
				}
				int places = (int) Math.max(0, Math.min(6, parse(a0, 1)));
				return Value.str(String.format(Locale.ROOT, "%." + places + "f", d));
			}
			case "percent": {
				double d = v.asNumber();
				return Double.isNaN(d) ? Value.NONE : Value.str(Math.round(d * 100) + "%");
			}
			case "hearts":
				return num(v, d -> Math.round(d / 2 * 10) / 10.0);
			case "clock":
				return Double.isNaN(v.asNumber()) ? Value.NONE : Value.str(clock((long) v.asNumber()));
			case "duration":
				return Double.isNaN(v.asNumber()) ? Value.NONE : Value.str(duration((long) v.asNumber()));
			case "date": {
				double d = v.asNumber();
				long ms = Double.isNaN(d) ? System.currentTimeMillis() : (long) d;
				try {
					DateTimeFormatter f = DateTimeFormatter.ofPattern(a0 == null ? "yyyy-MM-dd HH:mm" : a0, Locale.ROOT);
					return Value.str(f.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())));
				} catch (IllegalArgumentException e) {
					return Value.str("(bad date pattern)");
				}
			}
			case "default":
				return v.isNone() || v.asString().isEmpty() ? Value.str(a0 == null ? "" : a0) : v;
			case "truncate": {
				int max = (int) parse(a0, 100);
				String s = v.asString();
				return s.length() <= max || max < 1 ? v : Value.str(s.substring(0, Math.max(0, max - 1)) + "…");
			}
			case "replace":
				return v.isNone() || a0 == null ? v : Value.str(v.asString().replace(a0, args.size() > 1 ? args.get(1) : ""));
			case "join":
				return v.isNone() ? v : Value.str(String.join(a0 == null ? ", " : a0, v.asList()));
			case "count":
				return Value.num(v.asList().size());
			case "first":
				return v.asList().isEmpty() ? Value.NONE : Value.str(v.asList().get(0));
			case "last":
				return v.asList().isEmpty() ? Value.NONE : Value.str(v.asList().get(v.asList().size() - 1));
			case "yesno":
				return v.isNone() ? v : Value.str(v.asBool() ? "yes" : "no");
			case "coords": {
				if (v.type() == ValueType.LIST) {
					List<String> out = new ArrayList<>();
					for (String s : v.asList()) {
						double d = Value.str(s).asNumber();
						out.add(Double.isNaN(d) ? s : Long.toString((long) Math.floor(d)));
					}
					return Value.str(String.join(" ", out));
				}
				double d = v.asNumber();
				return Double.isNaN(d) ? v : Value.str(Long.toString((long) Math.floor(d)));
			}
			case "strip":
				return v.isNone() ? v : Value.str(stripFormatting(v.asString()));
			default:
				return v;
		}
	}

	/** Removes Minecraft formatting codes (§ followed by a character). */
	public static String stripFormatting(String s) {
		return s.replaceAll("§.", "");
	}

	/** World ticks (0 = 06:00) to HH:MM. */
	public static String clock(long ticks) {
		long t = Math.floorMod(ticks, 24000L);
		long hours = (t / 1000 + 6) % 24;
		long minutes = (t % 1000) * 60 / 1000;
		return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
	}

	public static String duration(long seconds) {
		if (seconds < 0) {
			seconds = 0;
		}
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		StringBuilder sb = new StringBuilder();
		if (h > 0) {
			sb.append(h).append("h ");
		}
		if (h > 0 || m > 0) {
			sb.append(m).append("m ");
		}
		sb.append(s).append('s');
		return sb.toString();
	}

	private static String title(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		boolean start = true;
		for (char c : s.toCharArray()) {
			sb.append(start ? Character.toUpperCase(c) : c);
			start = Character.isWhitespace(c);
		}
		return sb.toString();
	}

	/** {@code minecraft:the_nether} becomes {@code The Nether}. */
	private static String pretty(Value v) {
		if (v.type() == ValueType.LIST) {
			List<String> out = new ArrayList<>();
			for (String s : v.asList()) {
				out.add(pretty(Value.str(s)));
			}
			return String.join(", ", out);
		}
		String s = v.asString();
		int colon = s.indexOf(':');
		if (colon >= 0 && s.indexOf(' ') < 0) {
			s = s.substring(colon + 1);
		}
		return title(s.replace('_', ' ').replace('/', ' '));
	}

	private interface D2D {
		double apply(double d);
	}

	private static Value num(Value v, D2D f) {
		double d = v.asNumber();
		return Double.isNaN(d) ? Value.NONE : Value.num(f.apply(d));
	}

	private static double parse(String s, double def) {
		if (s == null) {
			return def;
		}
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
