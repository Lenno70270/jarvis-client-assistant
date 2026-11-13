package de.lennard.jarvis;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

/** Schaltet Iris-Shader. Iris ist optional, deshalb laeuft alles per Reflection. */
final class Shaders {
	private Shaders() {
	}

	private static Class<?> iris() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			throw new JarvisException("Iris ist nicht installiert.");
		}
		try {
			return Class.forName("net.irisshaders.iris.Iris");
		} catch (ClassNotFoundException e) {
			throw new JarvisException("Iris lässt sich nicht ansprechen.");
		}
	}

	private static List<String> packs() {
		try {
			Path directory = (Path) iris().getMethod("getShaderpacksDirectory").invoke(null);
			try (Stream<Path> files = Files.list(directory)) {
				return files.filter(p -> Files.isDirectory(p) || p.toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
						.map(p -> p.getFileName().toString())
						.sorted()
						.toList();
			}
		} catch (ReflectiveOperationException | IOException e) {
			throw new JarvisException("Die Shader-Liste ist nicht lesbar: " + e);
		}
	}

	static String list() {
		List<String> packs = packs();
		return packs.isEmpty() ? "Es sind keine Shader installiert." : "Installierte Shader: " + String.join("; ", packs);
	}

	/** Schaltet Shader ein oder aus. Ohne Namen bleibt das zuletzt gewaehlte Paket aktiv. */
	static String set(String name, boolean enabled) {
		String pack = null;
		if (enabled && name != null && !name.isBlank()) {
			String query = flat(name);
			pack = packs().stream().filter(p -> flat(p).contains(query)).findFirst()
					.orElseThrow(() -> new JarvisException("Kein Shader passt zu \"" + name + "\". " + list()));
		}
		try {
			Class<?> iris = iris();
			Object config = iris.getMethod("getIrisConfig").invoke(null);
			if (pack != null) {
				config.getClass().getMethod("setShaderPackName", String.class).invoke(config, pack);
			}
			config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
			config.getClass().getMethod("save").invoke(config);
			iris.getMethod("reload").invoke(null);
		} catch (InvocationTargetException e) {
			throw new JarvisException("Iris meldet einen Fehler: " + e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new JarvisException("Diese Iris-Version passt nicht: " + e);
		}
		return enabled ? "Shader an" + (pack == null ? "." : ": " + pack + ".") : "Shader aus.";
	}

	private static String flat(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
