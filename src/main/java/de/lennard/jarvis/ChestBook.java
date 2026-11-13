package de.lennard.jarvis;

import java.lang.reflect.InvocationTargetException;

import net.fabricmc.loader.api.FabricLoader;

/** Verbindung zur Mod Kistenbuch. Sie ist optional, deshalb laeuft alles per Reflection. */
final class ChestBook {
	private ChestBook() {
	}

	static boolean available() {
		return FabricLoader.getInstance().isModLoaded("kistenbuch");
	}

	private static Object call(String method, Class<?>[] types, Object... args) {
		if (!available()) {
			throw new JarvisException("Das Kistenbuch ist nicht installiert.");
		}
		try {
			return Class.forName("de.lennard.kistenbuch.KistenbuchApi").getMethod(method, types).invoke(null, args);
		} catch (InvocationTargetException e) {
			throw new JarvisException("Das Kistenbuch meldet einen Fehler: " + e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new JarvisException("Diese Version vom Kistenbuch passt nicht: " + e);
		}
	}

	static String find(String query) {
		return (String) call("find", new Class<?>[] {String.class}, query);
	}

	static String mark(String query) {
		int count = (int) call("mark", new Class<?>[] {String.class}, query);
		return count == 0 ? "Keine passende Kiste im Kistenbuch." : count + " Kisten markiert, sie leuchten jetzt in der Welt.";
	}

	static String clearMarks() {
		return call("clearMarks", new Class<?>[0]) + " Markierungen entfernt.";
	}
}
