package de.lennard.jarvis;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Feste Befehle. Sie laufen ohne KI und springen ein, wenn das Modell ausfaellt. */
final class Rules {
	private static final Pattern COMMAND = Pattern.compile("(?:^|\\s)/(\\S.*)$");

	static final String HELP = "Sag zum Beispiel: Jarvis erzähl mir einen Witz, Jarvis wie wird das Wetter, "
			+ "Jarvis /home, Jarvis schick mich zum Spawn. Taste J: sprechen. Taste K: Menü.";

	private Rules() {
	}

	static String run(String input, Actions actions) {
		String text = input.toLowerCase(Locale.ROOT);
		Matcher command = COMMAND.matcher(input);
		if (command.find()) {
			return actions.runCommand(command.group(1));
		}
		if (text.isEmpty() || text.matches(".*\\b(hilfe|help|befehle)\\b.*")) {
			return HELP;
		}
		if (text.matches(".*\\b(inventar|inventory)\\b.*")) {
			return actions.inventory();
		}
		return "Das verstehe ich ohne KI nicht. " + HELP;
	}
}
