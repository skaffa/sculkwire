package dev.sculkwire.core.template;

import dev.sculkwire.core.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A parsed message template: literal text with {@code {name}}, {@code {name(k=v)}} and
 * {@code {name|filter|filter:arg}} expressions. {@code {{} and {@code }}} are literal braces.
 *
 * <p>Only {@code {...}} whose content starts like a variable name is an expression, so regex
 * quantifiers such as {@code \d{2,3}} stay literal.
 */
public final class Template {
	public static final Template EMPTY = new Template("", List.of());

	private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]*");
	private static final Pattern FILTER_NAME = Pattern.compile("[a-z_]+");
	private static final int CACHE_SIZE = 1024;
	private static final Map<String, Template> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Template> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	public sealed interface Part permits Lit, Expr {
	}

	public record Lit(String text) implements Part {
	}

	public record FilterCall(String name, List<String> args) {
	}

	public record Expr(String name, Map<String, String> params, List<FilterCall> filters, String source) implements Part {
		/** Identity of the looked-up value (name + params), used for snapshots. */
		public String key() {
			return Template.key(name, params);
		}
	}

	private final String source;
	private final List<Part> parts;

	private Template(String source, List<Part> parts) {
		this.source = source;
		this.parts = List.copyOf(parts);
	}

	public static Template parse(String src) {
		if (src == null || src.isEmpty()) {
			return EMPTY;
		}
		synchronized (CACHE) {
			Template t = CACHE.get(src);
			if (t != null) {
				return t;
			}
		}
		Template t = doParse(src);
		synchronized (CACHE) {
			CACHE.put(src, t);
		}
		return t;
	}

	/** A template that is only literal text (braces are not interpreted). */
	public static Template literal(String text) {
		return text == null || text.isEmpty() ? EMPTY : new Template(text, List.of(new Lit(text)));
	}

	public static String key(String name, Map<String, String> params) {
		if (params == null || params.isEmpty()) {
			return name;
		}
		StringBuilder sb = new StringBuilder(name).append('(');
		boolean first = true;
		for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.append(')').toString();
	}

	public String source() {
		return source;
	}

	public List<Part> parts() {
		return parts;
	}

	public boolean isEmpty() {
		return parts.isEmpty();
	}

	public boolean hasExpressions() {
		for (Part p : parts) {
			if (p instanceof Expr) {
				return true;
			}
		}
		return false;
	}

	public List<Expr> expressions() {
		List<Expr> out = new ArrayList<>();
		for (Part p : parts) {
			if (p instanceof Expr e) {
				out.add(e);
			}
		}
		return out;
	}

	public String render(VariableResolver vars, OutputContext ctx) {
		if (parts.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Part p : parts) {
			if (p instanceof Lit l) {
				sb.append(l.text());
			} else if (p instanceof Expr e) {
				Value v = vars.resolve(e.name(), e.params());
				if (v == null) {
					v = Value.NONE;
				}
				boolean raw = false;
				for (FilterCall f : e.filters()) {
					if (f.name().equals("raw")) {
						raw = true;
					} else {
						v = Filters.apply(f.name(), f.args(), v);
					}
				}
				String s = v.asString();
				sb.append(raw ? s : Escaper.escape(s, ctx));
			}
		}
		return sb.toString();
	}

	/** Unknown variables and filters, as readable messages. */
	public List<String> problems(Predicate<String> knownVariable) {
		List<String> out = new ArrayList<>();
		for (Expr e : expressions()) {
			if (knownVariable != null && !knownVariable.test(e.name())) {
				out.add("unknown variable {" + e.name() + "}");
			}
			for (FilterCall f : e.filters()) {
				if (!Filters.NAMES.contains(f.name())) {
					out.add("unknown filter '" + f.name() + "' in {" + e.source() + "}");
				}
			}
		}
		return out;
	}

	@Override
	public String toString() {
		return source;
	}

	// ---- parsing ----

	private static Template doParse(String src) {
		List<Part> parts = new ArrayList<>();
		StringBuilder lit = new StringBuilder();
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (c == '{' && i + 1 < n && src.charAt(i + 1) == '{') {
				lit.append('{');
				i += 2;
				continue;
			}
			if (c == '}' && i + 1 < n && src.charAt(i + 1) == '}') {
				lit.append('}');
				i += 2;
				continue;
			}
			if (c == '{') {
				int end = findClose(src, i + 1);
				if (end > 0) {
					Expr e = parseExpr(src.substring(i + 1, end));
					if (e != null) {
						if (!lit.isEmpty()) {
							parts.add(new Lit(lit.toString()));
							lit.setLength(0);
						}
						parts.add(e);
						i = end + 1;
						continue;
					}
				}
			}
			lit.append(c);
			i++;
		}
		if (!lit.isEmpty()) {
			parts.add(new Lit(lit.toString()));
		}
		return new Template(src, parts);
	}

	private static int findClose(String s, int from) {
		boolean quote = false;
		for (int j = from; j < s.length(); j++) {
			char c = s.charAt(j);
			if (c == '\'') {
				quote = !quote;
			} else if (!quote) {
				if (c == '}') {
					return j;
				}
				if (c == '{' || c == '\n') {
					return -1;
				}
			}
		}
		return -1;
	}

	private static Expr parseExpr(String content) {
		List<String> pieces = splitTop(content, '|');
		String head = pieces.get(0).trim();
		String name = head;
		Map<String, String> params = new LinkedHashMap<>();
		int paren = head.indexOf('(');
		if (paren >= 0) {
			if (!head.endsWith(")")) {
				return null;
			}
			name = head.substring(0, paren).trim();
			String inner = head.substring(paren + 1, head.length() - 1);
			for (String kv : splitTop(inner, ',')) {
				if (kv.isBlank()) {
					continue;
				}
				int eq = kv.indexOf('=');
				if (eq <= 0) {
					return null;
				}
				params.put(kv.substring(0, eq).trim(), unquote(kv.substring(eq + 1).trim()));
			}
		}
		if (!NAME.matcher(name).matches()) {
			return null;
		}
		List<FilterCall> filters = new ArrayList<>();
		for (int k = 1; k < pieces.size(); k++) {
			List<String> fa = splitTop(pieces.get(k).trim(), ':');
			String fname = fa.get(0).trim();
			if (!FILTER_NAME.matcher(fname).matches()) {
				return null;
			}
			List<String> args = new ArrayList<>();
			for (int a = 1; a < fa.size(); a++) {
				args.add(unquote(fa.get(a).trim()));
			}
			filters.add(new FilterCall(fname, Collections.unmodifiableList(args)));
		}
		return new Expr(name, Collections.unmodifiableMap(params), Collections.unmodifiableList(filters), content);
	}

	/** Splits on {@code sep} outside single quotes and parentheses. */
	private static List<String> splitTop(String s, char sep) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quote = false;
		int depth = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\'') {
				quote = !quote;
			} else if (!quote && c == '(') {
				depth++;
			} else if (!quote && c == ')') {
				depth--;
			}
			if (c == sep && !quote && depth == 0) {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(c);
			}
		}
		out.add(cur.toString());
		return out;
	}

	private static String unquote(String s) {
		if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
			return s.substring(1, s.length() - 1);
		}
		return s;
	}
}
