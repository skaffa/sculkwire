package dev.sculkwire.core.value;

/** Answers whether a resource id is in a tag, e.g. {@code minecraft:oak_log} in {@code #minecraft:logs}. */
@FunctionalInterface
public interface TagResolver {
	TagResolver NONE = (id, tag) -> false;

	/** @param id normalized id; @param tag normalized tag, starting with {@code #} */
	boolean inTag(String id, String tag);
}
