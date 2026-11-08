package dev.sculkwire.core.model;

/** Anti-spam limits for one rule. */
public final class Throttle {
	public double cooldownSeconds;
	/** 0 = no limit. */
	public int maxPerHour;
	public boolean oncePerSession;
	public boolean ignoreQuietHours;
	/** null = use the global default from settings. */
	public Boolean onlyWhenUnfocused;
	/** Fire even if the condition is already true when joining (skips priming). */
	public boolean fireOnJoin;
}
