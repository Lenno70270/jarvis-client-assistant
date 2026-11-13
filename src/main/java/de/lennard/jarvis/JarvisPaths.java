package de.lennard.jarvis;

import java.nio.file.Path;

/**
 * Ordner fuer Piper und Whisper. Bewusst nicht unter AppData\Local: Dorthin geschriebene Dateien eines
 * Windows-Pakets landen in einem privaten Ordner, den das Spiel nicht sieht.
 */
final class JarvisPaths {
	private JarvisPaths() {
	}

	static Path data() {
		return Path.of(System.getProperty("user.home"), ".jarvis");
	}
}
