package de.lennard.jarvis;

import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MergeClientSoundEvent;

/** Wird nur von Simple Voice Chat geladen. Mischt Jarvis' Stimme ein und reicht empfangenen Ton weiter. */
public class JarvisVoicechatPlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "jarvis";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MergeClientSoundEvent.class, event -> {
			VoicechatClientApi api = event.getVoicechat();
			boolean ready = !api.isMuted() && !api.isDisabled() && !api.isDisconnected();
			VoiceBridge.report(ready);
			short[] frame = ready ? VoiceBridge.nextFrame() : null;
			if (frame != null) {
				event.mergeAudio(frame);
			}
		});
		registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, event -> received(event, false));
		registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, event -> received(event, false));
		registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, event -> received(event, true));
	}

	private static void received(ClientReceiveSoundEvent event, boolean inGroup) {
		FriendListener listener = JarvisMod.friends;
		if (listener != null && event.getRawAudio() != null) {
			listener.onAudio(event.getId(), event.getRawAudio(), inGroup);
		}
	}
}
