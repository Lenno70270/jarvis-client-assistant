package de.lennard.jarvis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Warteschlange fuer Jarvis' Stimme im Voicechat. Sie kennt Simple Voice Chat nicht, damit die Mod
 * auch ohne diese Mod laedt. Das Plugin holt alle 20 ms einen Rahmen ab.
 */
final class VoiceBridge {
	private static final int SAMPLE_RATE = 48000;
	private static final int FRAME_SAMPLES = 960;
	private static final long READY_WINDOW_MILLIS = 1000;
	private static final Queue<short[]> FRAMES = new ConcurrentLinkedQueue<>();
	private static volatile long lastReady;

	private VoiceBridge() {
	}

	/** Das Plugin meldet bei jedem Rahmen, ob du verbunden und nicht stumm bist. */
	static void report(boolean ready) {
		if (ready) {
			lastReady = System.currentTimeMillis();
		} else {
			lastReady = 0;
			FRAMES.clear();
		}
	}

	static boolean canSend() {
		return System.currentTimeMillis() - lastReady < READY_WINDOW_MILLIS;
	}

	static short[] nextFrame() {
		return FRAMES.poll();
	}

	static int queued() {
		return FRAMES.size();
	}

	/** Wandelt eine WAV-Datei in 48-kHz-Rahmen und stellt sie in die Warteschlange. */
	static void enqueue(byte[] wav, int volumePercent) throws IOException {
		short[] samples = resample(decode(wav), volumePercent / 100f);
		for (int start = 0; start < samples.length; start += FRAME_SAMPLES) {
			short[] frame = new short[FRAME_SAMPLES];
			System.arraycopy(samples, start, frame, 0, Math.min(FRAME_SAMPLES, samples.length - start));
			FRAMES.add(frame);
		}
	}

	/** Macht aus einer WAV-Datei rohen Ton, wie ihn Simple Voice Chat liefert. */
	static short[] toPcm48k(byte[] wav) throws IOException {
		return resample(decode(wav), 1f);
	}

	private record Pcm(short[] samples, float rate) {
	}

	private static Pcm decode(byte[] wav) throws IOException {
		try (AudioInputStream source = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
			float rate = source.getFormat().getSampleRate();
			AudioFormat target = new AudioFormat(rate, 16, 1, true, false);
			try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, source)) {
				byte[] bytes = pcm.readAllBytes();
				short[] samples = new short[bytes.length / 2];
				for (int i = 0; i < samples.length; i++) {
					samples[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
				}
				return new Pcm(samples, rate);
			}
		} catch (UnsupportedAudioFileException e) {
			throw new IOException("Audioformat: " + e.getMessage(), e);
		}
	}

	private static short[] resample(Pcm pcm, float gain) {
		double step = pcm.rate() / SAMPLE_RATE;
		int length = (int) (pcm.samples().length / step);
		short[] result = new short[length];
		for (int i = 0; i < length; i++) {
			double position = i * step;
			int index = (int) position;
			double fraction = position - index;
			short a = pcm.samples()[Math.min(index, pcm.samples().length - 1)];
			short b = pcm.samples()[Math.min(index + 1, pcm.samples().length - 1)];
			double value = (a + (b - a) * fraction) * gain;
			result[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
		}
		return result;
	}
}
