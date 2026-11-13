package de.lennard.jarvis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class JarvisMod implements ClientModInitializer {
	private static final Pattern TRIGGER = Pattern.compile(
			"^\\s*jarvis\\b[,:!.]?\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	static JarvisConfig config;
	static Assistant assistant;
	static FriendListener friends;

	@Override
	public void onInitializeClient() {
		config = JarvisConfig.load();
		Voice voice = new Voice(config);
		assistant = new Assistant(config, voice);
		friends = new FriendListener(config, assistant);
		voice.warmUp();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("jarvis", "main"));
		KeyMapping talk = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.jarvis.talk", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));
		KeyMapping menu = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.jarvis.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Game.onTick();
			voice.onKey(config.enabled && talk.isDown(), assistant::handleVoice);
			while (menu.consumeClick()) {
				client.gui.setScreen(new JarvisScreen(config, assistant));
			}
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				ChatLog.add(message.getString(), true);
			}
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> ChatLog.add(message.getString(), false));

		// Nachrichten an Jarvis gehen nie an den Server, andere Spieler sehen sie also nicht.
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (Game.isSendingOwnChat() || !config.enabled) {
				return true;
			}
			Matcher matcher = Pattern.compile("^\\s*" + Pattern.quote(config.wakeWord) + "\\b[,:!.]?\\s*(.*)$",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(message);
			if (!matcher.matches()) {
				return true;
			}
			assistant.handle(matcher.group(1).trim());
			return false;
		});
	}
}
