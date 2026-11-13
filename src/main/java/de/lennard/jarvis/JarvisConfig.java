package de.lennard.jarvis;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Liegt in .minecraft/config/jarvis.json. Ohne baseUrl laeuft Jarvis nur mit festen Befehlen. */
public class JarvisConfig {
	public static final int SPEAK_ON_VOICE = 0;
	public static final int SPEAK_ALWAYS = 1;
	public static final int SPEAK_NEVER = 2;
	public static final String[] PERSONALITIES = {"Butler", "Locker", "Frech", "Sachlich", "Jugendlich"};
	public static final String[] LENGTHS = {"Kurz", "Normal", "Ausführlich"};
	private static final Set<String> KEEP_ON_RESET = Set.of("baseUrl", "apiKey", "fallbackModels", "sttModel", "ttsModel");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String baseUrl = "";
	public String apiKey = "";
	public String model = "mistral-small-4";
	public List<String> fallbackModels = List.of();
	public String sttModel = "auto";
	public String ttsModel = "gemini-2.5-flash-preview-tts";
	public String ttsVoice = "de_DE-thorsten-high";
	public int speechSpeed = 100;

	public int volume = 100;
	public int speakMode = SPEAK_ON_VOICE;
	public boolean voicechatBroadcast = true;
	public boolean useAi = true;
	public boolean allowCommands = true;
	public boolean allowChat = true;
	public boolean allowShaders = true;
	public boolean allowChestBook = true;
	public boolean allowServerSwitch = true;
	public boolean allowWebSearch = true;

	public boolean enabled = true;
	public String wakeWord = "Jarvis";
	public String address = "Bro";
	public int personality = 4;
	public int answerLength = 0;
	public boolean echoInput = true;
	public boolean showListening = true;
	public int hedgeMillis = 1300;
	public int memorySize = 12;

	public int voicechatVolume = 100;
	public boolean listenToFriends = false;
	public boolean friendsOnlyGroup = false;
	public int friendCooldown = 5;
	public boolean showFriendRequests = true;

	public boolean hasLlm() {
		return useAi && baseUrl != null && !baseUrl.isBlank();
	}

	/** Stellt alle Einstellungen aus dem Menue zurueck, der KI-Zugang bleibt. */
	public void resetToDefaults() {
		JarvisConfig defaults = new JarvisConfig();
		for (Field field : JarvisConfig.class.getFields()) {
			if (!Modifier.isStatic(field.getModifiers()) && !KEEP_ON_RESET.contains(field.getName())) {
				try {
					field.set(this, field.get(defaults));
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				}
			}
		}
	}

	public void save() {
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("jarvis.json"), GSON.toJson(this));
		} catch (IOException e) {
			System.err.println("[Jarvis] config/jarvis.json nicht speicherbar: " + e.getMessage());
		}
	}

	public static JarvisConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("jarvis.json");
		try {
			if (Files.exists(file)) {
				return GSON.fromJson(Files.readString(file), JarvisConfig.class);
			}
			JarvisConfig config = new JarvisConfig();
			Files.writeString(file, GSON.toJson(config));
			return config;
		} catch (IOException | RuntimeException e) {
			System.err.println("[Jarvis] config/jarvis.json nicht lesbar: " + e.getMessage());
			return new JarvisConfig();
		}
	}
}
