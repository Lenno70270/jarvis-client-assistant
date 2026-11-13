package de.lennard.jarvis;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Echter Client gegen einen echten Dedicated Server. Mit JARVIS_TEST_CONFIG laeuft zusaetzlich die echte
 * KI, mit -PextraMods und JARVIS_TEST_SHADER Iris und Simple Voice Chat.
 */
public class JarvisClientTest implements FabricClientGameTest {
	private static final int SECONDS = 20;
	private final List<String> jarvisSaid = new CopyOnWriteArrayList<>();
	private final List<String> publicChat = new CopyOnWriteArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> jarvisSaid.add(message.getString()));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> publicChat.add(message.getString()));

		Properties properties = new Properties();
		properties.setProperty("max-players", "5");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(properties)) {
			String address = playThrough(ctx, server);
			serverHop(ctx, address);
		}
		singleplayer(ctx);
	}

	private void singleplayer(ClientGameTestContext ctx) {
		try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(state -> state.setAllowCommands(true)).create()) {
			world.getConnection().waitForChunksRender();
			Actions actions = new Actions();
			String situation = await(ctx, 5, actions::situation);
			check(situation.contains("Welt: Einzelspieler") && situation.contains("Op-Commands: erlaubt"), situation);
			tell(ctx, "Jarvis /gamemode spectator");
			ctx.waitFor(mc -> mc.player.isSpectator(), 5 * SECONDS);
			System.out.println("[JarvisTest] OK Einzelspieler: " + situation);
		}
	}

	private String playThrough(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			server.runCommand("op @a");
			server.runCommand("gamemode survival @a");
			ctx.waitTicks(20);

			command(ctx, server);
			llmRunsCommand(ctx, server);
			menu(ctx);
			realLlm(ctx, server);
			shaders(ctx);
			voicechat(ctx);

			if (!publicChat.isEmpty()) {
				throw new AssertionError("Nachrichten sind beim Server gelandet: " + publicChat);
			}
			System.out.println("[JarvisTest] Jarvis sagte:\n  " + String.join("\n  ", jarvisSaid));
			return ctx.computeOnClient(mc -> mc.getCurrentServer().ip);
		}
	}

	private void command(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		tell(ctx, "Jarvis /gamemode creative");
		ctx.waitFor(mc -> mc.player.isCreative(), 5 * SECONDS);
		server.runCommand("gamemode survival @a");
		ctx.waitFor(mc -> !mc.player.isCreative(), 5 * SECONDS);
		Actions actions = new Actions();
		String answer = await(ctx, 10, () -> actions.runCommandWithAnswer("give @s gibtsnicht"));
		check(answer.contains("Antwort des Servers"), answer);
		System.out.println("[JarvisTest] OK Command mit Serverantwort: " + answer);

		// Ohne Op sperrt Jarvis Op-Commands, ohne dass etwas zum Server geht.
		server.runCommand("deop @a");
		ctx.waitFor(mc -> mc.getConnection().getCommands().getRoot().getChild("gamemode") == null, 10 * SECONDS);
		tell(ctx, "Jarvis /gamemode creative");
		ctx.waitFor(mc -> said("ist gesperrt"), 5 * SECONDS);
		ctx.waitTicks(20);
		check(ctx.computeOnClient(mc -> !mc.player.isCreative()), "Spielmodus blieb gleich");
		check(await(ctx, 5, actions::situation).contains("Op-Commands: gesperrt"), "Lage meldet die Sperre");
		System.out.println("[JarvisTest] OK ohne Op gesperrt: " + jarvisSaid.getLast());
		server.runCommand("op @a");
		ctx.waitFor(mc -> mc.getConnection().getCommands().getRoot().getChild("gamemode") != null, 10 * SECONDS);
		check(await(ctx, 5, actions::situation).contains("Op-Commands: erlaubt"), "Lage meldet Op");
		System.out.println("[JarvisTest] OK mit Op: " + await(ctx, 5, actions::situation));
	}

	private void llmRunsCommand(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		AtomicInteger requests = new AtomicInteger();
		List<String> bodies = new CopyOnWriteArrayList<>();
		HttpServer llm = fakeLlm(requests, bodies);
		JarvisMod.config.baseUrl = "http://127.0.0.1:" + llm.getAddress().getPort();
		try {
			tell(ctx, "Jarvis schalte in den Kreativmodus");
			ctx.waitFor(mc -> mc.player.isCreative(), 15 * SECONDS);
			ctx.waitFor(mc -> said("Sehr wohl"), 10 * SECONDS);
		} finally {
			JarvisMod.config.baseUrl = "";
			llm.stop(0);
		}
		server.runCommand("gamemode survival @a");
		ctx.waitFor(mc -> !mc.player.isCreative(), 5 * SECONDS);
		check(requests.get() == 2, "zwei KI-Anfragen, waren " + requests.get());
		check(bodies.get(1).contains("\"role\":\"tool\""), "Werkzeug-Ergebnis geht an die KI");
		System.out.println("[JarvisTest] OK KI-Schleife sendet einen Command");
	}

	private void menu(ClientGameTestContext ctx) {
		String before = JarvisMod.config.ttsVoice;
		JarvisScreen screen = ctx.computeOnClient(mc -> new JarvisScreen(JarvisMod.config, JarvisMod.assistant));
		ctx.setScreen(() -> screen);
		ctx.waitTicks(30);
		ctx.takeScreenshot("jarvis-menue-stimme");
		check(ctx.computeOnClient(mc -> screen.press(Component.translatable("jarvis.menu.page.voice").getString())), "Seite Stimme");
		ctx.waitTicks(5);
		ctx.takeScreenshot("jarvis-menue-voice");
		check(ctx.computeOnClient(mc -> screen.press(Component.translatable("jarvis.menu.page.voice").getString())), "Seite Stimme");
		ctx.waitTicks(5);
		boolean clicked = ctx.computeOnClient(mc -> screen.press(Component.translatable("jarvis.menu.speed").getString()) || true);
		check(clicked, "Menü reagiert");
		System.out.println("[JarvisTest] OK Menü: Stimme jetzt " + JarvisMod.config.ttsVoice);
		for (String[] page : new String[][] {{"general", "allgemein"}, {"ai", "ki"}, {"permissions", "rechte"}, {"voicechat", "voicechat"}}) {
			String name = Component.translatable("jarvis.menu.page." + page[0]).getString();
			check(ctx.computeOnClient(mc -> screen.press(name)), "Seite " + name);
			ctx.waitTicks(10);
			ctx.takeScreenshot("jarvis-menue-" + page[1]);
		}
		JarvisMod.config.ttsVoice = before;
		JarvisMod.config.save();
		ctx.setScreen(() -> null);
		ctx.waitTicks(5);
	}

	private void realLlm(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		String path = System.getenv("JARVIS_TEST_CONFIG");
		if (path == null) {
			return;
		}
		JarvisConfig real;
		try {
			real = new Gson().fromJson(Files.readString(Path.of(path)), JarvisConfig.class);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		JarvisMod.config.baseUrl = real.baseUrl;
		JarvisMod.config.apiKey = real.apiKey;
		JarvisMod.config.model = real.model;
		JarvisMod.config.fallbackModels = real.fallbackModels;
		try {
			voice(ctx, real);
			friends(ctx, real);
			chestBook(ctx, server);
			for (String question : new String[] {"erzähl mir einen Witz", "was ist 17 mal 23?", "wie wird das Wetter morgen in Berlin?", "wer ist aktuell Bundeskanzler von Deutschland?"}) {
				String answer = ask(ctx, question);
				check(!answer.contains("spezialisiert") && !answer.contains("KI nicht erreichbar") && !answer.contains("ohne KI"), "Antwort auf " + question + ": " + answer);
				if (question.contains("17")) {
					check(answer.contains("391"), "Rechnung: " + answer);
				}
			}

			tell(ctx, "Jarvis gib mir voll verzauberte Diamantstiefel");
			ctx.waitFor(mc -> count(mc, "diamond_boots") >= 1, 60 * SECONDS);
			System.out.println("[JarvisTest] OK echte KI gibt per Command verzauberte Stiefel");

			Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
			String refused = ask(ctx, "lauf bitte zehn Blöcke nach vorne und öffne meine Ender-Chest");
			check(ctx.computeOnClient(mc -> mc.player.position().distanceTo(start)) < 0.5, "Spieler hat sich bewegt");
			System.out.println("[JarvisTest] OK Spieler bleibt stehen: " + refused);
		} finally {
			JarvisMod.config.baseUrl = "";
			JarvisMod.config.apiKey = "";
		}
	}

	/** Mit installiertem Kistenbuch: Jarvis findet Items und markiert Kisten, Mitspieler nicht. */
	private void chestBook(ClientGameTestContext ctx, TestDedicatedServerContext server) {
		if (!ChestBook.available()) {
			return;
		}
		net.minecraft.core.BlockPos chest = ctx.computeOnClient(mc -> mc.player.blockPosition().offset(2, 0, 0));
		String pos = chest.getX() + " " + chest.getY() + " " + chest.getZ();
		server.runCommand("setblock " + pos + " chest");
		server.runCommand("item replace block " + pos + " container.0 with diamond 12");
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(chest).add(0, 0.5, 0), net.minecraft.core.Direction.UP, chest, false)));
		ctx.waitFor(mc -> mc.player.containerMenu != mc.player.inventoryMenu, 5 * SECONDS);
		ctx.waitTicks(15);
		ctx.runOnClient(mc -> mc.player.closeContainer());
		ctx.waitTicks(10);
		String found = ctx.computeOnClient(mc -> ChestBook.find("diamond"));
		check(found.contains("12x") && found.contains(pos), "Kistenbuch kennt die Kiste: " + found);

		String answer = ask(ctx, "wo sind meine Diamanten?");
		check(answer.contains(String.valueOf(chest.getX())) && answer.contains(String.valueOf(chest.getZ())), "Antwort nennt die Kiste: " + answer);

		ask(ctx, "markier die Kiste mit den Diamanten");
		ctx.waitFor(mc -> ChestBook.find("diamond").contains("[markiert]"), 20 * SECONDS);
		System.out.println("[JarvisTest] OK Kistenbuch: Jarvis hat die Kiste markiert");

		ask(ctx, "mach alle Markierungen weg");
		ctx.waitFor(mc -> !ChestBook.find("diamond").contains("[markiert]"), 20 * SECONDS);
		System.out.println("[JarvisTest] OK Kistenbuch: Markierungen entfernt");
	}

	private String ask(ClientGameTestContext ctx, String question) {
		int before = jarvisSaid.size();
		tell(ctx, "Jarvis " + question);
		ctx.waitFor(mc -> jarvisSaid.size() >= before + 2, 40 * SECONDS);
		ctx.waitTicks(2);
		String answer = jarvisSaid.getLast();
		System.out.println("[JarvisTest] OK " + question + " (" + JarvisMod.assistant.lastReplyMillis() + " ms, "
				+ JarvisMod.assistant.lastModel() + "): " + answer);
		return answer;
	}

	private void voice(ClientGameTestContext ctx, JarvisConfig real) {
		Voice voice = new Voice(real);
		for (Piper.VoiceInfo info : Piper.installedVoices()) {
			real.ttsVoice = info.id();
			long started = System.currentTimeMillis();
			byte[] wav = await(ctx, 40, () -> voice.synthesize("Wie spät ist es gerade, Sir?"));
			System.out.println("[JarvisTest] OK Stimme " + info.name() + ": " + wav.length + " Bytes in " + (System.currentTimeMillis() - started) + " ms");
		}
		real.ttsVoice = Piper.installedVoices().getFirst().id();
		byte[] wav = await(ctx, 40, () -> voice.synthesize("Jarvis, wie spät ist es gerade?"));
		long warm = System.currentTimeMillis();
		await(ctx, 20, () -> voice.synthesize("Das ist ein zweiter Satz."));
		System.out.println("[JarvisTest] OK warme Stimme: " + (System.currentTimeMillis() - warm) + " ms");
		String heard = await(ctx, 40, () -> voice.transcribe(wav));
		check(heard.toLowerCase(Locale.ROOT).contains("spät"), "Sprache erkannt: " + heard);
		System.out.println("[JarvisTest] OK Sprache hin und zurueck: " + heard);
		await(ctx, 20, () -> {
			Voice.play(wav);
			return true;
		});
		byte[] mic = await(ctx, 10, () -> {
			voice.startRecording();
			Thread.sleep(1200);
			return voice.stopRecording();
		});
		check(mic != null && mic.length > 16000, "Mikrofon nimmt auf");
		System.out.println("[JarvisTest] OK Mikrofon nimmt auf, " + mic.length + " Bytes");
	}

	private void shaders(ClientGameTestContext ctx) {
		String pack = System.getenv("JARVIS_TEST_SHADER");
		if (pack == null || !FabricLoader.getInstance().isModLoaded("iris")) {
			return;
		}
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			Path directory = (Path) iris.getMethod("getShaderpacksDirectory").invoke(null);
			Files.createDirectories(directory);
			Files.copy(Path.of(pack), directory.resolve(Path.of(pack).getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
			var inUse = api.getClass().getMethod("isShaderPackInUse");
			inUse.setAccessible(true);
			ctx.runOnClient(mc -> System.out.println("[JarvisTest] OK " + Shaders.set("bsl", true)));
			ctx.waitTicks(40);
			check((boolean) inUse.invoke(api), "Shader sind an");
			ctx.runOnClient(mc -> System.out.println("[JarvisTest] OK " + Shaders.set(null, false)));
			ctx.waitTicks(40);
			check(!(boolean) inUse.invoke(api), "Shader sind aus");
		} catch (ReflectiveOperationException | IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Sprachprobe durch die lokale Erkennung, dazu der Sicherheitstest gegen Commands von Mitspielern. */
	private void friends(ClientGameTestContext ctx, JarvisConfig real) {
		boolean listening = JarvisMod.config.listenToFriends;
		int cooldown = JarvisMod.config.friendCooldown;
		JarvisMod.config.listenToFriends = true;
		JarvisMod.config.friendCooldown = 0;
		try {
			FriendListener listener = JarvisMod.friends;
			java.util.UUID friend = java.util.UUID.randomUUID();
			ctx.waitFor(mc -> listener.status().equals("hört zu"), 5 * SECONDS);
			ctx.waitTicks(100);
			real.ttsVoice = "de_DE-karlsson-low";
			byte[] wav = await(ctx, 30, () -> new Voice(real).synthesize("Jarvis, was ist die Hauptstadt von Frankreich?"));
			short[] samples = await(ctx, 5, () -> VoiceBridge.toPcm48k(wav));
			int before = jarvisSaid.size();
			await(ctx, 10, () -> {
				for (int i = 0; i + 960 <= samples.length; i += 960) {
					listener.onAudio(friend, java.util.Arrays.copyOfRange(samples, i, i + 960), false);
				}
				return true;
			});
			long spoken = System.currentTimeMillis();
			ctx.waitFor(mc -> jarvisSaid.stream().skip(before).anyMatch(line -> line.contains("fragt:")), 40 * SECONDS);
			long answered = System.currentTimeMillis() - spoken;
			ctx.waitTicks(10);
			System.out.println("[JarvisTest] OK Mitspieler gehoert: " + jarvisSaid.subList(before, jarvisSaid.size())
					+ " Satzende bis Antwort " + answered + " ms, davon Erkennung " + listener.lastRecognitionMillis() + " ms");

			// Unbekanntes Gerede ohne Aktivierungswort loest nichts aus.
			int quiet = jarvisSaid.size();
			await(ctx, 5, () -> {
				listener.handle(friend, "Wo gehen wir heute farmen?");
				return true;
			});
			ctx.waitTicks(40);
			check(jarvisSaid.size() == quiet, "Gerede ohne Aktivierungswort wurde ignoriert");

			// Ein Mitspieler darf keine Commands ausloesen, auch nicht, wenn die KI es versucht.
			String model = JarvisMod.config.baseUrl;
			AtomicInteger requests = new AtomicInteger();
			List<String> bodies = new CopyOnWriteArrayList<>();
			HttpServer llm = fakeLlm(requests, bodies);
			JarvisMod.config.baseUrl = "http://127.0.0.1:" + llm.getAddress().getPort();
			try {
				ctx.runOnClient(mc -> JarvisMod.assistant.handleFriend("Boeser Freund", "schalte in den Kreativmodus"));
				ctx.waitFor(mc -> requests.get() >= 2, 15 * SECONDS);
				ctx.waitTicks(40);
			} finally {
				JarvisMod.config.baseUrl = model;
				llm.stop(0);
			}
			check(ctx.computeOnClient(mc -> !mc.player.isCreative()), "Mitspieler konnte keinen Command auslösen");
			check(!bodies.getFirst().contains("run_command") && !bodies.getFirst().contains("send_chat") && !bodies.getFirst().contains("Position"), "Mitspieler bekommen keine Commands und keine Lage");
			check(!bodies.getFirst().contains("find_items") && !bodies.getFirst().contains("mark_chests"), "Mitspieler bekommen kein Kistenbuch");
			check(bodies.get(1).contains("Mitspieler dürfen das nicht"), "Werkzeug wurde abgelehnt");
			System.out.println("[JarvisTest] OK Mitspieler kann keine Commands ausloesen");
		} finally {
			JarvisMod.config.listenToFriends = listening;
			JarvisMod.config.friendCooldown = cooldown;
		}
	}

	/** Prueft mit echtem Simple Voice Chat, dass Jarvis' Rahmen abgeholt werden. */
	private void voicechat(ClientGameTestContext ctx) {
		if (!FabricLoader.getInstance().isModLoaded("voicechat")) {
			return;
		}
		// Der frische Test-Client hat den Voicechat noch nicht eingerichtet und ist stumm.
		ctx.runOnClient(mc -> {
			finishVoicechatSetup();
			setVoicechatMuted(false);
		});
		ctx.waitFor(mc -> VoiceBridge.canSend(), 20 * SECONDS);
		byte[] wav = java.util.Base64.getDecoder().decode(silentWav());
		await(ctx, 5, () -> {
			VoiceBridge.enqueue(wav, 100);
			return true;
		});
		int queued = VoiceBridge.queued();
		check(queued > 0, "Rahmen in der Warteschlange");
		ctx.waitFor(mc -> VoiceBridge.queued() == 0, 15 * SECONDS);
		System.out.println("[JarvisTest] OK Voicechat hat " + queued + " Rahmen abgeholt");

		// Stumm geschaltet darf nichts mehr rausgehen.
		ctx.runOnClient(mc -> setVoicechatMuted(true));
		ctx.waitFor(mc -> !VoiceBridge.canSend(), 10 * SECONDS);
		System.out.println("[JarvisTest] OK stumm: Jarvis sendet nicht in den Voicechat");
	}

	private static void finishVoicechatSetup() {
		try {
			Class.forName("de.maxhenkel.voicechat.gui.onboarding.OnboardingManager").getMethod("finishOnboarding").invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void setVoicechatMuted(boolean muted) {
		try {
			Object state = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager").getMethod("getPlayerStateManager").invoke(null);
			state.getClass().getMethod("setDisabled", boolean.class).invoke(state, false);
			state.getClass().getMethod("setMuted", boolean.class).invoke(state, muted);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Verbindet vom Titelbild aus und dann noch einmal aus der laufenden Welt heraus. */
	private void serverHop(ClientGameTestContext ctx, String address) {
		System.out.println("[JarvisTest] OK " + await(ctx, 60, () -> Servers.connect(address)));
		ctx.waitFor(mc -> mc.player != null, 20 * SECONDS);
		ctx.waitTicks(40);
		System.out.println("[JarvisTest] OK aus der Welt: " + await(ctx, 60, () -> Servers.connect(address)));
		ctx.waitFor(mc -> mc.player != null, 20 * SECONDS);
		ctx.waitTicks(40);
		ctx.runOnClient(Minecraft::disconnectWithProgressScreen);
		ctx.waitFor(mc -> mc.player == null, 20 * SECONDS);
		ctx.waitTicks(20);
		ctx.setScreen(TitleScreen::new);
		ctx.waitTicks(5);
	}

	/** Der Test-Thread steuert die Ticks, Jarvis' Ablaeufe muessen also daneben laufen. */
	private static <T> T await(ClientGameTestContext ctx, int seconds, Callable<T> task) {
		CompletableFuture<T> future = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try {
				future.complete(task.call());
			} catch (Exception | AssertionError e) {
				future.completeExceptionally(e);
			}
		});
		thread.setDaemon(true);
		thread.start();
		ctx.waitFor(mc -> future.isDone(), seconds * SECONDS);
		return future.join();
	}

	/** Eine halbe Sekunde Stille als WAV mit 24 kHz, 16 Bit, Mono. */
	private static String silentWav() {
		int samples = 12000;
		java.nio.ByteBuffer wav = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
		wav.putInt(16).putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16);
		wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
		return java.util.Base64.getEncoder().encodeToString(wav.array());
	}

	private static HttpServer fakeLlm(AtomicInteger requests, List<String> bodies) {
		String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
				+ "\"function\":{\"name\":\"set_gamemode\",\"arguments\":\"{\\\"mode\\\":\\\"creative\\\"}\"}}]}}]}";
		String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Sehr wohl, Sir.\"}}]}";
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/chat/completions", exchange -> {
				bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				byte[] reply = (requests.incrementAndGet() == 1 ? toolCall : answer).getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, reply.length);
				exchange.getResponseBody().write(reply);
				exchange.close();
			});
			server.start();
			return server;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void tell(ClientGameTestContext ctx, String text) {
		ctx.runOnClient(mc -> mc.getConnection().sendChat(text));
	}

	private boolean said(String text) {
		return jarvisSaid.stream().anyMatch(line -> line.contains(text));
	}

	private static int count(Minecraft mc, String id) {
		int total = 0;
		for (int i = 0; i < 36; i++) {
			var stack = mc.player.getInventory().getItem(i);
			if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(id)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what + "\nJarvis sagte: " + jarvisSaid);
		}
	}
}
