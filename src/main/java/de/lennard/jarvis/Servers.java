package de.lennard.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Die gespeicherte Serverliste und das Verbinden. */
final class Servers {
	private static final int CONNECT_TIMEOUT_MILLIS = 40000;
	private static final int LEAVE_PAUSE_MILLIS = 1000;

	private Servers() {
	}

	private static List<ServerData> saved() {
		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		List<ServerData> servers = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			servers.add(list.get(i));
		}
		return servers;
	}

	static String list() {
		return Game.call(() -> {
			ServerData current = Minecraft.getInstance().getCurrentServer();
			StringBuilder text = new StringBuilder("Aktuell: " + (current == null ? "Einzelspieler" : current.ip) + ". Gespeichert: ");
			List<String> names = saved().stream().map(s -> s.name + " (" + s.ip + ")").toList();
			return text.append(names.isEmpty() ? "keine" : String.join("; ", names)).toString();
		});
	}

	/** Verlaesst die aktuelle Welt und verbindet sich mit einem gespeicherten Server oder einer Adresse. */
	static String connect(String target) throws InterruptedException {
		String query = target.toLowerCase(Locale.ROOT).trim();
		ServerData data = Game.call(() -> saved().stream()
				.filter(s -> s.name.toLowerCase(Locale.ROOT).contains(query) || s.ip.toLowerCase(Locale.ROOT).contains(query))
				.findFirst()
				.orElse(null));
		if (data == null && !query.contains(".") && !query.contains(":")) {
			throw new JarvisException("Kein gespeicherter Server passt zu \"" + target + "\". " + list());
		}
		ServerData server = data != null ? data : new ServerData(target, target, ServerData.Type.OTHER);
		// Erst ganz raus aus der alten Welt, sonst raeumt das Trennen die neue Verbindung mit ab.
		Game.run(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				mc.disconnectWithProgressScreen();
			}
		});
		long end = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
		while (Game.call(() -> Minecraft.getInstance().level != null) && System.currentTimeMillis() < end) {
			Thread.sleep(100);
		}
		Thread.sleep(LEAVE_PAUSE_MILLIS);
		Game.run(() -> ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), Minecraft.getInstance(),
				ServerAddress.parseString(server.ip), server, false, null));
		Thread.sleep(1000);
		while (System.currentTimeMillis() < end) {
			if (Game.call(() -> Minecraft.getInstance().player != null && Minecraft.getInstance().level != null)) {
				return "Verbunden mit " + server.name + ".";
			}
			Thread.sleep(250);
		}
		throw new JarvisException("Die Verbindung zu " + server.name + " kam nicht zustande.");
	}
}
