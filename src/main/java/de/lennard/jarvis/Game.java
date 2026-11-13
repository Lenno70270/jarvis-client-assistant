package de.lennard.jarvis;

import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Bruecke zwischen Jarvis' Arbeits-Thread und dem Spiel-Thread. Aufgaben laufen als normaler
 * Ablauf im Arbeits-Thread, jeder Spielzugriff geht ueber {@link #call}.
 */
final class Game {
	private static final Object TICK = new Object();
	private static long ticks;
	private static boolean sendingOwnChat;

	private Game() {
	}

	static void onTick() {
		synchronized (TICK) {
			ticks++;
			TICK.notifyAll();
		}
	}

	static <T> T call(Supplier<T> task) {
		try {
			return Minecraft.getInstance().submit(task).join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof JarvisException failure) {
				throw failure;
			}
			throw e;
		}
	}

	static void run(Runnable task) {
		call(() -> {
			task.run();
			return null;
		});
	}

	static void waitTicks(int count) throws InterruptedException {
		synchronized (TICK) {
			long end = ticks + count;
			while (ticks < end) {
				TICK.wait(1000);
				if (Minecraft.getInstance().player == null) {
					throw new JarvisException("Die Verbindung zur Welt ist weg.");
				}
			}
		}
	}

	/** Wartet, bis die Bedingung gilt. Liefert false, wenn die Zeit abgelaufen ist. */
	static boolean waitUntil(BooleanSupplier condition, int timeoutTicks) throws InterruptedException {
		for (int i = 0; i < timeoutTicks; i++) {
			if (call(condition::getAsBoolean)) {
				return true;
			}
			waitTicks(1);
		}
		return false;
	}

	static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			Component line = Component.literal("[Jarvis] ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
			mc.gui.chatListener().handleSystemMessage(line, false);
		});
	}

	static boolean isSendingOwnChat() {
		return sendingOwnChat;
	}

	/** Sendet oeffentlich in den Chat, ohne dass Jarvis die eigene Nachricht wieder abfaengt. */
	static void sendChat(String message) {
		run(() -> {
			sendingOwnChat = true;
			try {
				Minecraft.getInstance().getConnection().sendChat(message);
			} finally {
				sendingOwnChat = false;
			}
		});
	}
}
