package de.lennard.jarvis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * Hoert im Voicechat auf das Aktivierungswort. Standardmaessig aus. Audio liegt nur kurz im Speicher,
 * wird lokal erkannt und nie gespeichert. Nur der Satz nach dem Aktivierungswort geht an die KI.
 */
final class FriendListener {
	private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);
	private static final long SILENCE_MILLIS = 450;
	private static final long MAX_SEGMENT_MILLIS = 7000;
	private static final int MIN_SEGMENT_BYTES = 16000 * 2 * 2 / 5;
	private static final long ARMED_MILLIS = 7000;
	private static final int MAX_WAITING = 2;

	private static final class Segment {
		final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		final long started = System.currentTimeMillis();
		volatile long lastFrame = started;
	}

	private final JarvisConfig config;
	private final Assistant assistant;
	private final Whisper whisper = new Whisper();
	private final Map<UUID, Segment> segments = new ConcurrentHashMap<>();
	private final Map<UUID, Long> armedUntil = new ConcurrentHashMap<>();
	/** Reden viele durcheinander, faellt das aelteste Stueck weg, damit Jarvis nie hinterherhinkt. */
	private final ThreadPoolExecutor recognizer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_WAITING), task -> {
		Thread thread = new Thread(task, "Jarvis-Erkennung");
		thread.setDaemon(true);
		return thread;
	}, new ThreadPoolExecutor.DiscardOldestPolicy());

	private volatile long lastRecognitionMillis;

	FriendListener(JarvisConfig config, Assistant assistant) {
		this.config = config;
		this.assistant = assistant;
		ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "Jarvis-Zuhoeren");
			thread.setDaemon(true);
			return thread;
		});
		clock.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
	}

	private boolean active() {
		return config.enabled && config.listenToFriends && config.hasLlm() && Whisper.installed();
	}

	long lastRecognitionMillis() {
		return lastRecognitionMillis;
	}

	String status() {
		if (!Whisper.installed()) {
			return net.minecraft.network.chat.Component.translatable("jarvis.menu.recognition.not_installed").getString();
		}
		return net.minecraft.network.chat.Component.translatable(whisper.running() ? "jarvis.menu.recognition.listening" : "jarvis.menu.recognition.off").getString();
	}

	/** Kommt alle 20 ms aus Simple Voice Chat: 48 kHz, Mono. */
	void onAudio(UUID sender, short[] samples, boolean inGroup) {
		if (!active() || (config.friendsOnlyGroup && !inGroup)) {
			return;
		}
		Segment segment = segments.computeIfAbsent(sender, id -> new Segment());
		synchronized (segment) {
			for (int i = 0; i + 2 < samples.length; i += 3) {
				int value = (samples[i] + samples[i + 1] + samples[i + 2]) / 3;
				segment.pcm.write(value & 0xff);
				segment.pcm.write(value >> 8 & 0xff);
			}
			segment.lastFrame = System.currentTimeMillis();
		}
	}

	private void tick() {
		try {
			if (active()) {
				whisper.start(config.wakeWord);
			} else {
				if (whisper.running()) {
					whisper.stop();
				}
				segments.clear();
				armedUntil.clear();
				return;
			}
			long now = System.currentTimeMillis();
			for (Map.Entry<UUID, Segment> entry : segments.entrySet()) {
				Segment segment = entry.getValue();
				if (now - segment.lastFrame > SILENCE_MILLIS || now - segment.started > MAX_SEGMENT_MILLIS) {
					segments.remove(entry.getKey());
					byte[] pcm;
					synchronized (segment) {
						pcm = segment.pcm.toByteArray();
					}
					if (pcm.length >= MIN_SEGMENT_BYTES) {
						recognize(entry.getKey(), pcm);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("[Jarvis] Zuhören: " + e);
		}
	}

	private void recognize(UUID sender, byte[] pcm) {
		recognizer.execute(() -> {
			try {
				long started = System.currentTimeMillis();
				String heard = whisper.transcribe(wav(pcm), config.wakeWord);
				lastRecognitionMillis = System.currentTimeMillis() - started;
				handle(sender, heard);
			} catch (IOException e) {
				System.err.println("[Jarvis] Erkennung: " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private static byte[] wav(byte[] pcm) throws IOException {
		ByteArrayOutputStream wav = new ByteArrayOutputStream();
		AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT, pcm.length / FORMAT.getFrameSize()), AudioFileFormat.Type.WAVE, wav);
		return wav.toByteArray();
	}

	private Pattern wakePattern() {
		String word = config.wakeWord.toLowerCase(Locale.ROOT);
		String spellings = word.equals("jarvis") ? "j+a+h?r*[vw]+i+e*s+|dscha+h?r*[vw]+i+s+|tscha+h?r*[vw]+i+s+|cha+r*[vw]+i+s+|darvis|travis" : Pattern.quote(word);
		return Pattern.compile("(?:^|[^\\p{L}])(?:" + spellings + ")(?:$|[^\\p{L}])[\\s,:!.?-]*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
	}

	/** Gehoertes ohne Aktivierungswort wird sofort verworfen. */
	void handle(UUID sender, String heard) {
		String text = heard.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ").trim();
		if (text.isEmpty()) {
			return;
		}
		Matcher matcher = wakePattern().matcher(text);
		String question;
		if (matcher.find()) {
			question = matcher.group(1).trim();
			if (question.length() < 3) {
				armedUntil.put(sender, System.currentTimeMillis() + ARMED_MILLIS);
				return;
			}
		} else if (armedUntil.getOrDefault(sender, 0L) > System.currentTimeMillis()) {
			question = text;
		} else {
			return;
		}
		armedUntil.remove(sender);
		assistant.handleFriend(nameOf(sender), question);
	}

	private static String nameOf(UUID id) {
		return Game.call(() -> {
			var connection = Minecraft.getInstance().getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
			return info == null ? "Jemand" : info.getProfile().name();
		});
	}
}
