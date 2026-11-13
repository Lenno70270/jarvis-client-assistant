package de.lennard.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.google.gson.JsonParser;

/**
 * Lokale Spracherkennung. Stimmen anderer Spieler verlassen so nie den PC. Der Dienst laeuft nur,
 * solange "Freunden zuhören" an ist, und lauscht nur auf 127.0.0.1.
 */
final class Whisper {
	private static final Path ROOT = JarvisPaths.data().resolve("whisper");
	private static final Path EXE = ROOT.resolve("bin").resolve("Release").resolve("whisper-server.exe");
	private static final Path MODEL = ROOT.resolve("ggml-small-q5_1.bin");
	private static final int PORT = 18765;
	private static final int THREADS = 6;
	private static final long RESTART_PAUSE_MILLIS = 15000;
	/** Whisper rechnet sonst immer mit 30 Sekunden Ton. 384 reicht fuer gut 7 Sekunden und ist viermal schneller. */
	private static final int AUDIO_CONTEXT = 384;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private Process process;
	private long lastStart;

	Whisper() {
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Jarvis-Whisper-Ende"));
	}

	static boolean installed() {
		return Files.exists(EXE) && Files.exists(MODEL);
	}

	/** Ohne den Hinweis schluckt die Erkennung das Aktivierungswort am Satzanfang. */
	private static String hint(String wakeWord) {
		return wakeWord + ", " + wakeWord + ", hey " + wakeWord + ".";
	}

	synchronized void start(String wakeWord) throws IOException {
		if (process != null && process.isAlive()) {
			return;
		}
		// Stirbt der Dienst sofort, zum Beispiel weil der Port belegt ist, nicht im Sekundentakt neu starten.
		long now = System.currentTimeMillis();
		if (now - lastStart < RESTART_PAUSE_MILLIS) {
			return;
		}
		lastStart = now;
		process = new ProcessBuilder(EXE.toString(), "-m", MODEL.toString(), "-l", "de", "-t", String.valueOf(THREADS),
				"-ac", String.valueOf(AUDIO_CONTEXT), "-bo", "1", "-nf",
				"--host", "127.0.0.1", "--port", String.valueOf(PORT), "--prompt", hint(wakeWord))
				.directory(EXE.getParent().toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
	}

	synchronized void stop() {
		if (process != null) {
			process.destroy();
			process = null;
		}
	}

	synchronized boolean running() {
		return process != null && process.isAlive();
	}

	/** Der Hinweis am Anfang macht das Aktivierungswort fuer die Erkennung wahrscheinlicher. */
	String transcribe(byte[] wav, String wakeWord) throws IOException, InterruptedException {
		String boundary = "jarvis" + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n" + hint(wakeWord) + "\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(wav);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/inference"))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Whisper: HTTP " + response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject().get("text").getAsString().trim();
	}
}
