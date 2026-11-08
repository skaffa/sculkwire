package dev.sculkwire.core.notify;

import java.net.URI;
import java.util.Locale;

/** HTTPS is required, except for local/LAN hosts or when the user allows insecure HTTP. */
public final class UrlPolicy {
	private UrlPolicy() {
	}

	/** Returns a problem ("must use https ...") or null when the URL is acceptable. Templates are allowed. */
	public static String check(String url, boolean allowInsecure) {
		if (url == null || url.isBlank()) {
			return "is empty";
		}
		String u = url.trim().replaceAll("\\{[^{}]*}", "x");
		URI uri;
		try {
			uri = URI.create(u);
		} catch (IllegalArgumentException e) {
			return "is not a valid URL";
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (uri.getHost() == null) {
			return "is not a valid URL";
		}
		if (scheme.equals("https")) {
			return null;
		}
		if (scheme.equals("http")) {
			return allowInsecure || isLocal(uri.getHost()) ? null : "must use https:// (or turn on 'Allow insecure HTTP')";
		}
		return "must start with https://";
	}

	public static boolean isLocal(String host) {
		String h = host.toLowerCase(Locale.ROOT);
		if (h.equals("localhost") || h.equals("[::1]") || h.equals("::1") || h.endsWith(".local") || h.endsWith(".lan")
				|| h.endsWith(".localhost") || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) {
			return true;
		}
		if (h.startsWith("172.")) {
			String[] p = h.split("\\.");
			try {
				int second = Integer.parseInt(p[1]);
				return second >= 16 && second <= 31;
			} catch (RuntimeException e) {
				return false;
			}
		}
		return false;
	}
}
