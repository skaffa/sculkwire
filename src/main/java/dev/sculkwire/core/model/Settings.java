package dev.sculkwire.core.model;

/** Global settings. */
public final class Settings {
	/** Epoch millis until which all notifications are muted (0 = not muted). */
	public long muteUntil;
	public QuietHours quietHours = new QuietHours();
	/** After joining (or changing dimension), condition rules are primed silently for this long. */
	public double joinGraceSeconds = 2;
	public boolean defaultOnlyWhenUnfocused;
	public int historySize = 200;
	public boolean privacyNoticeShown;
	public int inspectorIntervalTicks = 10;
	/**
	 * Ask the server for your statistics (the same request the vanilla Statistics screen makes) while an
	 * event uses player.stat. This is the only thing Sculkwire sends to the server.
	 */
	public boolean requestStats = true;
	public int statsIntervalSeconds = 60;

	public static final class QuietHours {
		public boolean enabled;
		public String start = "23:00";
		public String end = "07:00";
	}
}
