package de.lennard.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Alles, was Jarvis im Spiel darf: lesen, Commands senden, in den Chat schreiben. Er bewegt den Spieler
 * nicht und drueckt keine Tasten. Laeuft im Arbeits-Thread.
 */
final class Actions {
	private static final Set<String> OP_COMMANDS = Set.of("gamemode", "gamerule", "give", "clear", "tp", "teleport", "time",
			"weather", "kill", "effect", "enchant", "xp", "experience", "summon", "setblock", "fill", "clone", "difficulty",
			"defaultgamemode", "op", "deop", "ban", "ban-ip", "pardon", "kick", "whitelist", "stop", "save-all", "seed",
			"locate", "spawnpoint", "setworldspawn", "worldborder", "execute", "data", "attribute", "item", "loot", "scoreboard");
	private static final int MAIN_INVENTORY_SIZE = 36;
	private static final int ANSWER_WAIT_TICKS = 30;
	private static final int ANSWER_SETTLE_TICKS = 5;

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static LocalPlayer player() {
		LocalPlayer player = mc().player;
		if (player == null) {
			throw new JarvisException("Du bist gerade in keiner Welt.");
		}
		return player;
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	String situation() {
		return Game.call(() -> {
			LocalPlayer p = player();
			BlockPos pos = p.blockPosition();
			return "Position " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
					+ ", Dimension " + p.level().dimension().identifier().getPath()
					+ ", Leben " + Math.round(p.getHealth()) + "/20"
					+ ", Hunger " + p.getFoodData().getFoodLevel() + "/20"
					+ ", Spielmodus " + mc().gameMode.getPlayerMode().getName()
					+ ", in der Hand: " + itemId(p.getMainHandItem())
					+ ", Welt: " + worldType()
					+ ", Op-Commands: " + (hasOpRights() ? "erlaubt" : "gesperrt");
		});
	}

	String inventory() {
		return Game.call(() -> {
			List<String> items = new ArrayList<>();
			Inventory inventory = player().getInventory();
			for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
				ItemStack stack = inventory.getItem(i);
				if (!stack.isEmpty()) {
					items.add(stack.getCount() + "x " + itemId(stack));
				}
			}
			return items.isEmpty() ? "Dein Inventar ist leer." : "Inventar: " + String.join("; ", items);
		});
	}

	/** Der Server schickt nur die Commands, die der Spieler nutzen darf. Das Nachsehen ist also unsichtbar. */
	private static boolean mayUse(String command) {
		return mc().getConnection().getCommands().getRoot().getChild(command) != null;
	}

	private static boolean hasOpRights() {
		return mayUse("gamemode");
	}

	private static String worldType() {
		return mc().hasSingleplayerServer() ? "Einzelspieler" : "Server";
	}

	private static String blockedReason(String command) {
		return "/" + command + " ist gesperrt: "
				+ (mc().hasSingleplayerServer() ? "In dieser Welt sind Cheats aus." : "Auf diesem Server hast du dafür keine Rechte.");
	}

	String runCommand(String command) {
		String clean = command.trim().replaceFirst("^/+", "");
		if (clean.isEmpty()) {
			throw new JarvisException("Der Command ist leer.");
		}
		String root = clean.split("\\s+")[0].replaceFirst("^minecraft:", "").toLowerCase(Locale.ROOT);
		// Nur Op-Commands pruefen: Manche Server verstecken ihre Plugin-Commands wie /home aus der Liste.
		if (OP_COMMANDS.contains(root) && !Game.call(() -> mayUse(root))) {
			throw new JarvisException(Game.call(() -> blockedReason(root)));
		}
		Game.run(() -> mc().getConnection().sendCommand(clean));
		return "Gesendet: /" + clean;
	}

	/** Sendet den Command und wartet kurz auf die Antwort des Servers. */
	String runCommandWithAnswer(String command) throws InterruptedException {
		long mark = ChatLog.mark();
		String sent = runCommand(command);
		Game.waitUntil(() -> !ChatLog.serverLinesSince(mark).isEmpty(), ANSWER_WAIT_TICKS);
		Game.waitTicks(ANSWER_SETTLE_TICKS);
		List<String> answer = ChatLog.serverLinesSince(mark);
		return sent + (answer.isEmpty() ? " Der Server hat nichts geantwortet." : " Antwort des Servers: " + String.join(" | ", answer));
	}

	String sendChat(String message) {
		Game.sendChat(message);
		return "Im Chat gesendet: " + message;
	}
}
