package de.lennard.jarvis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Sprechen mit Jarvis: Mikrofon aufnehmen, in Text wandeln und Antworten vorlesen. */
final class Voice {
	private static final AudioFormat MIC_FORMAT = new AudioFormat(16000f, 16, 1, true, false);
	private static final int MIN_RECORDING_BYTES = 16000 * 2 / 3;

	private final JarvisConfig config;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Piper piper = new Piper();
	private boolean cloudNoticeShown;
	private volatile TargetDataLine microphone;
	private ByteArrayOutputStream recording;
	private Thread reader;

	Voice(JarvisConfig config) {
		this.config = config;
	}

	boolean isRecording() {
		return microphone != null;
	}

	void startRecording() throws LineUnavailableException {
		TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, MIC_FORMAT));
		line.open(MIC_FORMAT);
		line.start();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		recording = buffer;
		microphone = line;
		reader = new Thread(() -> {
			byte[] chunk = new byte[3200];
			int read;
			while ((read = line.read(chunk, 0, chunk.length)) > 0) {
				buffer.write(chunk, 0, read);
			}
		}, "Jarvis-Mikrofon");
		reader.setDaemon(true);
		reader.start();
	}

	/** Beendet die Aufnahme und liefert sie als WAV. Zu kurze Aufnahmen ergeben null. */
	byte[] stopRecording() throws IOException {
		TargetDataLine line = microphone;
		microphone = null;
		line.stop();
		line.close();
		try {
			reader.join(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		byte[] pcm = recording.toByteArray();
		if (pcm.length < MIN_RECORDING_BYTES) {
			return null;
		}
		ByteArrayOutputStream wav = new ByteArrayOutputStream();
		AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), MIC_FORMAT, pcm.length / MIC_FORMAT.getFrameSize()),
				javax.sound.sampled.AudioFileFormat.Type.WAVE, wav);
		return wav.toByteArray();
	}

	String transcribe(byte[] wav) throws IOException, InterruptedException {
		String boundary = "jarvis" + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		field(body, boundary, "model", config.sttModel);
		field(body, boundary, "language", "de");
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"jarvis.wav\"\r\n"
				+ "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(wav);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> response = http.send(request("/audio/transcriptions", "multipart/form-data; boundary=" + boundary, body.toByteArray()),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Spracherkennung: HTTP " + response.statusCode());
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		return json.get("text").getAsString().trim();
	}

	/** Spricht Satz fuer Satz: Der naechste Satz entsteht schon, waehrend der erste laeuft. */
	void speak(String text) throws IOException, InterruptedException {
		List<String> sentences = sentences(text);
		CompletableFuture<byte[]> next = render(sentences.getFirst());
		for (int i = 0; i < sentences.size(); i++) {
			byte[] wav;
			try {
				wav = next.join();
			} catch (CompletionException e) {
				throw new IOException(e.getCause().getMessage(), e.getCause());
			}
			if (i + 1 < sentences.size()) {
				next = render(sentences.get(i + 1));
			}
			if (config.voicechatBroadcast && VoiceBridge.canSend()) {
				VoiceBridge.enqueue(wav, config.voicechatVolume);
			}
			play(wav, config.volume);
		}
	}

	private CompletableFuture<byte[]> render(String sentence) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return synthesize(sentence);
			} catch (IOException e) {
				throw new CompletionException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CompletionException(e);
			}
		});
	}

	private static List<String> sentences(String text) {
		List<String> result = new ArrayList<>();
		for (String part : text.split("(?<=[.!?])\\s+")) {
			if (!part.isBlank()) {
				result.add(part.trim());
			}
		}
		return result.isEmpty() ? List.of(text) : result;
	}

	/** Lokale Stimme, wenn vorhanden. Sonst die Cloud, deren Gratis-Limit aber schnell voll ist. */
	byte[] synthesize(String text) throws IOException, InterruptedException {
		String voice = activeVoice();
		if (voice != null) {
			return piper.synthesize(voice, config.speechSpeed, text);
		}
		if (!cloudNoticeShown) {
			cloudNoticeShown = true;
			Game.say("Keine lokalen Stimmen gefunden, ich nutze die Cloud-Stimme.");
		}
		try {
			return requestSpeech("gemini-2.5-flash-preview-tts", "Charon", text);
		} catch (IOException e) {
			return requestSpeech("auto", "alloy", text);
		}
	}

	private String activeVoice() {
		if (Piper.installed(config.ttsVoice)) {
			return config.ttsVoice;
		}
		List<Piper.VoiceInfo> voices = Piper.installedVoices();
		return voices.isEmpty() ? null : voices.getFirst().id();
	}

	/** Laedt die Stimme im Hintergrund, damit die erste Antwort nicht darauf warten muss. */
	void warmUp() {
		System.out.println("[Jarvis] Lokale Stimmen: " + Piper.installedVoices().size());
		CompletableFuture.runAsync(() -> {
			try {
				String voice = activeVoice();
				if (voice != null) {
					piper.warmUp(voice, config.speechSpeed);
				}
			} catch (IOException e) {
				System.err.println("[Jarvis] Piper: " + e);
			}
		});
	}

	private byte[] requestSpeech(String model, String voice, String text) throws IOException, InterruptedException {
		JsonObject json = new JsonObject();
		json.addProperty("model", model);
		json.addProperty("voice", voice);
		json.addProperty("response_format", "wav");
		json.addProperty("input", text);
		HttpResponse<byte[]> response = http.send(request("/audio/speech", "application/json", json.toString().getBytes(StandardCharsets.UTF_8)),
				HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("Sprachausgabe: HTTP " + response.statusCode());
		}
		return response.body();
	}

	static void play(byte[] wav) throws IOException {
		play(wav, 100);
	}

	static void play(byte[] wav, int volumePercent) throws IOException {
		try (AudioInputStream audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
			SourceDataLine line = AudioSystem.getSourceDataLine(audio.getFormat());
			line.open(audio.getFormat());
			if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				float decibel = (float) (20 * Math.log10(Math.max(volumePercent, 1) / 100.0));
				((FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN)).setValue(decibel);
			}
			line.start();
			byte[] chunk = new byte[8192];
			int read;
			while ((read = audio.read(chunk, 0, chunk.length)) > 0) {
				line.write(chunk, 0, read);
			}
			line.drain();
			line.close();
		} catch (UnsupportedAudioFileException | LineUnavailableException e) {
			throw new IOException("Wiedergabe: " + e.getMessage(), e);
		}
	}

	private HttpRequest request(String path, String contentType, byte[] body) {
		return HttpRequest.newBuilder(URI.create(config.baseUrl.replaceAll("/+$", "") + path))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", contentType)
				.header("Authorization", "Bearer " + config.apiKey)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
	}

	private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
				.getBytes(StandardCharsets.UTF_8));
	}

	/** Wird jeden Tick mit dem Zustand der Sprechtaste aufgerufen. */
	void onKey(boolean down, Consumer<byte[]> finished) {
		try {
			if (down && !isRecording()) {
				startRecording();
				if (config.showListening) {
					Game.say("Ich höre zu.");
				}
			} else if (!down && isRecording()) {
				byte[] wav = stopRecording();
				if (wav != null) {
					finished.accept(wav);
				}
			}
		} catch (LineUnavailableException | IOException e) {
			microphone = null;
			Game.say("Mikrofon nicht verfügbar: " + e.getMessage());
		}
	}
}
