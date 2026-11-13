package de.lennard.jarvis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Lokale deutsche Sprachausgabe. Ein Piper-Prozess bleibt offen und laedt seine Stimme nur einmal,
 * darum kommt die Antwort ohne Netz und ohne Limit in Sekundenbruchteilen.
 */
final class Piper {
	record VoiceInfo(String id, String name, String description) {
	}

	static final List<VoiceInfo> VOICES = List.of(
			new VoiceInfo("de_DE-thorsten-high", "Thorsten", "männlich, klar"),
			new VoiceInfo("de_DE-thorsten_emotional-medium", "Thorsten Emotional", "männlich, lebhaft"),
			new VoiceInfo("de_DE-karlsson-low", "Karlsson", "männlich, tief"),
			new VoiceInfo("de_DE-kerstin-low", "Kerstin", "weiblich, ruhig"),
			new VoiceInfo("de_DE-ramona-low", "Ramona", "weiblich, hell"));

	private static final Path ROOT = JarvisPaths.data().resolve("piper");
	private static final Path EXE = ROOT.resolve("piper").resolve("piper.exe");
	private static final Path OUTPUT = ROOT.resolve("out");

	private Process process;
	private BufferedWriter input;
	private BufferedReader output;
	private String loadedVoice;
	private int loadedSpeed;

	Piper() {
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Jarvis-Piper-Ende"));
	}

	static boolean installed(String voice) {
		return Files.exists(EXE) && Files.exists(model(voice));
	}

	static List<VoiceInfo> installedVoices() {
		return VOICES.stream().filter(v -> installed(v.id())).toList();
	}

	private static Path model(String voice) {
		return ROOT.resolve("voices").resolve(voice + ".onnx");
	}

	/** Startet die Stimme vorab, damit schon die erste Antwort schnell kommt. */
	synchronized void warmUp(String voice, int speedPercent) throws IOException {
		if (installed(voice)) {
			ensureRunning(voice, speedPercent);
		}
	}

	synchronized byte[] synthesize(String voice, int speedPercent, String text) throws IOException {
		ensureRunning(voice, speedPercent);
		input.write(clean(text));
		input.newLine();
		input.flush();
		String file = output.readLine();
		if (file == null) {
			stop();
			throw new IOException("Piper wurde beendet.");
		}
		Path wav = Path.of(file.trim());
		try {
			return Files.readAllBytes(wav);
		} finally {
			Files.deleteIfExists(wav);
		}
	}

	private static String clean(String text) {
		// Deutsche Stimmen lesen englische Woerter deutsch, also steht hier, wie sie klingen sollen.
		return text.replaceAll("[\\r\\n]+", " ").replaceAll("[*_#`~]", "")
				.replaceAll("(?i)\\bj\\.?a\\.?r\\.?v\\.?i\\.?s\\b\\.?", "Dschahwiss")
				.replaceAll("\\bSir\\b", "Sör")
				.trim();
	}

	private void ensureRunning(String voice, int speedPercent) throws IOException {
		if (process != null && process.isAlive() && voice.equals(loadedVoice) && speedPercent == loadedSpeed) {
			return;
		}
		stop();
		Files.createDirectories(OUTPUT);
		// Piper rechnet in Dauer: 0,8 heisst schneller. Tempo 125 % ergibt also 0,8.
		String lengthScale = String.format(Locale.ROOT, "%.2f", 100.0 / Math.max(speedPercent, 50));
		process = new ProcessBuilder(EXE.toString(), "--model", model(voice).toString(), "--output_dir", OUTPUT.toString(),
				"--length_scale", lengthScale, "--sentence_silence", "0.1")
				.directory(EXE.getParent().toFile())
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
		loadedVoice = voice;
		loadedSpeed = speedPercent;
	}

	synchronized void stop() {
		if (process != null) {
			process.destroy();
			process = null;
		}
	}
}
