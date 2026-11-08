package dev.sculkwire.core.model;

import java.util.ArrayList;
import java.util.List;

/** Where a rule applies. Empty lists mean "any". */
public final class Scope {
	public boolean singleplayer = true;
	public boolean multiplayer = true;
	/** Server address globs, e.g. {@code *.example.net}. */
	public List<String> servers = new ArrayList<>();
	/** Singleplayer world name globs. */
	public List<String> worlds = new ArrayList<>();
	/** Dimension ids. */
	public List<String> dimensions = new ArrayList<>();
}
