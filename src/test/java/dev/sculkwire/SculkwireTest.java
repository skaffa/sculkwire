package dev.sculkwire;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SculkwireTest {
	@Test
	void modIdMatchesFabricModJson() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/fabric.mod.json")) {
			assertNotNull(in, "fabric.mod.json not on the test classpath");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"id\": \"" + Sculkwire.MOD_ID + "\""),
					"fabric.mod.json id must equal Sculkwire.MOD_ID");
		}
	}
}
