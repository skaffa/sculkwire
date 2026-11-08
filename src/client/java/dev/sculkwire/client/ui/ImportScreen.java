package dev.sculkwire.client.ui;

import dev.sculkwire.client.ClientRuntime;
import dev.sculkwire.core.config.ImportExport;
import dev.sculkwire.core.model.NotifierConfig;
import dev.sculkwire.core.model.Rule;
import dev.sculkwire.core.model.SculkwireConfig;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Imports events from clipboard JSON; the user maps the export's notifier ids to their own. */
final class ImportScreen extends FormScreen {
	private final ClientRuntime rt = ClientRuntime.get();
	private final ImportExport.Imported imported;
	private final Map<String, String> mapping = new LinkedHashMap<>();

	ImportScreen(Screen parent, String clipboard) {
		super(Component.literal("Import events"), parent);
		imported = ImportExport.parse(clipboard);
		for (String ref : imported.notifierRefs()) {
			mapping.put(ref, rt.config().notifier(ref) != null ? ref : "");
		}
	}

	@Override
	protected void buildFixed() {
		if (imported.rules().isEmpty()) {
			footer(button("Back", this::onClose));
		} else {
			footer(button("Import", this::doImport), button("Cancel", this::onClose));
		}
	}

	@Override
	protected void buildRows() {
		if (imported.rules().isEmpty()) {
			text("Copy a Sculkwire export (JSON) to the clipboard first, then press Import again.", GRAY);
			for (String p : imported.problems()) {
				text("✖ " + p, BAD);
			}
			return;
		}
		header(imported.rules().size() + " event(s)");
		for (Rule r : imported.rules()) {
			text("• " + (r.name == null || r.name.isBlank() ? r.id : r.name) + "  (" + r.trigger.kind
					+ (r.trigger.event != null ? " " + r.trigger.event : "") + ")", WHITE);
		}
		text("They arrive disabled. Review and enable them on the Events tab.", GRAY);
		if (!mapping.isEmpty()) {
			header("Send to");
			List<String> options = new ArrayList<>();
			options.add("");
			for (NotifierConfig n : rt.config().notifiers) {
				options.add(n.id);
			}
			for (String ref : mapping.keySet()) {
				row("'" + ref + "' →").add(cycle(options, () -> mapping.get(ref), v -> mapping.put(ref, v),
						v -> v.isEmpty() ? "(skip)" : rt.config().notifier(v).displayName()), -1);
			}
		}
		if (!imported.variables().isEmpty()) {
			text(imported.variables().size() + " variable(s) will be added if you do not have them yet.", GRAY);
		}
		for (String p : imported.problems()) {
			text("⚠ " + p, WARN);
		}
	}

	private void doImport() {
		SculkwireConfig cfg = rt.config();
		Map<String, String> m = new LinkedHashMap<>();
		mapping.forEach((k, v) -> {
			if (!v.isEmpty()) {
				m.put(k, v);
			}
		});
		List<Rule> added = ImportExport.apply(cfg, imported, m);
		rt.apply(cfg);
		ClientRuntime.info("Imported " + added.size() + " event(s), disabled. Enable them on the Events tab.");
		onClose();
	}
}
