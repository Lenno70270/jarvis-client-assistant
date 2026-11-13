package de.lennard.jarvis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Einstellungen im Aufbau der Sodium-Optionen: Seitenliste, flache Zeilen, Tooltip daneben. */
final class JarvisScreen extends Screen {
	private static final int THEME = 0xFF94E4D3;
	private static final int FOREGROUND = 0xFFFFFFFF;
	private static final int DISABLED = 0xFFAAAAAA;
	private static final int ROW_BACKGROUND = 0x90000000;
	private static final int ROW_HOVER = 0xE0000000;
	private static final int PANEL = 0x90000000;
	private static final int OVERLAY = 0xEA000000;
	private static final int SCROLL_TRACK = 0x60000000;
	private static final int SCROLL_THUMB = 0xFFAAAAAA;

	private static final int MARGIN = 6;
	private static final int ROW = 20;
	private static final int GROUP_GAP = 3;
	private static final int PAGE_WIDTH = 125;
	private static final int OPTION_WIDTH = 230;
	private static final int SLIDER_WIDTH = 90;
	private static final int TEXT_PAD = 6;
	private static final int BUTTON_WIDTH = 65;
	private static final int TOOLTIP_PAD = 5;
	private static final int MIN_TOOLTIP_WIDTH = 100;

	private static final List<String> WAKE_WORDS = List.of("Jarvis", "Computer", "Friday", "Alfred");
	private static final List<String> ADDRESSES = List.of("Bro", "Digga", "Sir", "Chef", "Boss", "Kollege", "");
	private static final List<String> MODELS = List.of("mistral-small-4", "gpt-oss-20b", "deepseek-v4-flash", "gpt-5.6-luna", "auto");

	private abstract static class Row {
		final String label;
		final String tooltip;
		BooleanSupplier active = () -> true;

		Row(String label, String tooltip) {
			this.label = label;
			this.tooltip = tooltip;
		}

		Row when(BooleanSupplier condition) {
			active = condition;
			return this;
		}

		abstract String value();

		void click(double x, int rowRight) {
		}
	}

	private final class Toggle extends Row {
		final Supplier<Boolean> getter;
		final Consumer<Boolean> setter;

		Toggle(String label, String tooltip, Supplier<Boolean> getter, Consumer<Boolean> setter) {
			super(label, tooltip);
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		String value() {
			return "";
		}

		@Override
		void click(double x, int rowRight) {
			setter.accept(!getter.get());
			config.save();
		}
	}

	private final class Slider extends Row {
		final int min;
		final int max;
		final int step;
		final String unit;
		final IntSupplier getter;
		final IntConsumer setter;

		Slider(String label, String tooltip, int min, int max, int step, String unit, IntSupplier getter, IntConsumer setter) {
			super(label, tooltip);
			this.min = min;
			this.max = max;
			this.step = step;
			this.unit = unit;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		String value() {
			return getter.getAsInt() + unit;
		}

		void set(double mouse, int rowRight) {
			int left = rowRight - TEXT_PAD - SLIDER_WIDTH;
			double share = Math.max(0, Math.min(1, (mouse - left) / SLIDER_WIDTH));
			int raw = min + (int) Math.round(share * (max - min) / step) * step;
			setter.accept(Math.min(max, raw));
		}

		@Override
		void click(double x, int rowRight) {
			dragging = this;
			dragRight = rowRight;
			set(x, rowRight);
		}
	}

	private record Page(String name, List<List<Row>> groups) {
	}

	private record Hit(String label, int x, int y, int w, int h, Runnable action) {
		boolean contains(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private final JarvisConfig config;
	private final Assistant assistant;
	private final List<Page> pages = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private int page;
	private int scroll;
	private int contentHeight;
	private Slider dragging;
	private int dragRight;
	private double lastClickX;
	private GuiGraphicsExtractor g;

	JarvisScreen(JarvisConfig config, Assistant assistant) {
		super(Component.literal("Jarvis"));
		this.config = config;
		this.assistant = assistant;
		buildPages();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- Einstellungen ----

	/** Übersetzter Text. Die Texte stehen in assets/jarvis/lang. */
	private static String t(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	private void buildPages() {
		pages.clear();
		pages.add(new Page(t("jarvis.menu.page.general"), List.of(
				List.of(
						toggle(t("jarvis.menu.enabled"), t("jarvis.menu.enabled.tooltip"), () -> config.enabled, v -> config.enabled = v),
						cycle(t("jarvis.menu.wake_word"), t("jarvis.menu.wake_word.tooltip"), WAKE_WORDS, v -> v, () -> config.wakeWord, v -> config.wakeWord = v)),
				List.of(
						cycle(t("jarvis.menu.address"), t("jarvis.menu.address.tooltip"), ADDRESSES, v -> v.isEmpty() ? t("jarvis.menu.address.none") : v, () -> config.address, v -> config.address = v),
						cycle(t("jarvis.menu.personality"), t("jarvis.menu.personality.tooltip"), List.of(0, 1, 2, 3, 4), v -> t("jarvis.menu.personality." + v), () -> config.personality, v -> config.personality = v),
						cycle(t("jarvis.menu.length"), t("jarvis.menu.length.tooltip"), List.of(0, 1, 2), v -> t("jarvis.menu.length." + v), () -> config.answerLength, v -> config.answerLength = v)),
				List.of(
						toggle(t("jarvis.menu.echo"), t("jarvis.menu.echo.tooltip"), () -> config.echoInput, v -> config.echoInput = v),
						toggle(t("jarvis.menu.listening"), t("jarvis.menu.listening.tooltip"), () -> config.showListening, v -> config.showListening = v)))));

		List<Piper.VoiceInfo> voices = Piper.installedVoices();
		List<Row> voiceRows = new ArrayList<>();
		if (voices.isEmpty()) {
			voiceRows.add(info(t("jarvis.menu.speaker"), t("jarvis.menu.speaker.none.tooltip"), () -> "Cloud"));
		} else {
			voiceRows.add(cycle(t("jarvis.menu.speaker"), t("jarvis.menu.speaker.tooltip"),
					voices.stream().map(Piper.VoiceInfo::id).toList(),
					id -> voices.stream().filter(v -> v.id().equals(id)).map(Piper.VoiceInfo::name).findFirst().orElse("?"),
					() -> config.ttsVoice, v -> {
						config.ttsVoice = v;
						assistant.testVoice();
					}));
		}
		voiceRows.add(action(t("jarvis.menu.test_voice"), t("jarvis.menu.test_voice.tooltip"), assistant::testVoice));
		pages.add(new Page(t("jarvis.menu.page.voice"), List.of(voiceRows,
				List.of(
						slider(t("jarvis.menu.volume"), t("jarvis.menu.volume.tooltip"), 10, 100, 5, "%", () -> config.volume, v -> config.volume = v),
						slider(t("jarvis.menu.speed"), t("jarvis.menu.speed.tooltip"), 70, 150, 5, "%", () -> config.speechSpeed, v -> config.speechSpeed = v)),
				List.of(
						cycle(t("jarvis.menu.speak"), t("jarvis.menu.speak.tooltip"),
								List.of(JarvisConfig.SPEAK_ON_VOICE, JarvisConfig.SPEAK_ALWAYS, JarvisConfig.SPEAK_NEVER),
								v -> v == JarvisConfig.SPEAK_ALWAYS ? t("jarvis.menu.speak.always") : v == JarvisConfig.SPEAK_NEVER ? t("jarvis.menu.speak.never") : t("jarvis.menu.speak.on_voice"),
								() -> config.speakMode, v -> config.speakMode = v)))));

		pages.add(new Page(t("jarvis.menu.page.ai"), List.of(
				List.of(
						toggle(t("jarvis.menu.use_ai"), t("jarvis.menu.use_ai.tooltip"), () -> config.useAi, v -> config.useAi = v),
						cycle(t("jarvis.menu.model"), t("jarvis.menu.model.tooltip"), MODELS, v -> v, () -> config.model, v -> config.model = v),
						slider(t("jarvis.menu.hedge"), t("jarvis.menu.hedge.tooltip"), 500, 3000, 100, " ms", () -> config.hedgeMillis, v -> config.hedgeMillis = v)),
				List.of(
						toggle(t("jarvis.menu.web"), t("jarvis.menu.web.tooltip"), () -> config.allowWebSearch, v -> config.allowWebSearch = v),
						slider(t("jarvis.menu.memory"), t("jarvis.menu.memory.tooltip"), 0, 30, 2, "", () -> config.memorySize, v -> config.memorySize = v),
						action(t("jarvis.menu.clear_memory"), t("jarvis.menu.clear_memory.tooltip"), assistant::clearMemory)),
				List.of(
						info(t("jarvis.menu.last_reply"), t("jarvis.menu.last_reply.tooltip"), () -> assistant.lastReplyMillis() == 0 ? "-" : String.format("%.1f s", assistant.lastReplyMillis() / 1000.0)),
						info(t("jarvis.menu.last_model"), t("jarvis.menu.last_model.tooltip"), () -> assistant.lastModel().isEmpty() ? "-" : assistant.lastModel())))));

		pages.add(new Page(t("jarvis.menu.page.permissions"), List.of(
				List.of(
						toggle(t("jarvis.menu.commands"), t("jarvis.menu.commands.tooltip"), () -> config.allowCommands, v -> config.allowCommands = v),
						toggle(t("jarvis.menu.chat"), t("jarvis.menu.chat.tooltip"), () -> config.allowChat, v -> config.allowChat = v)),
				List.of(
						toggle(t("jarvis.menu.chestbook"), t("jarvis.menu.chestbook.tooltip"), () -> config.allowChestBook, v -> config.allowChestBook = v),
						toggle(t("jarvis.menu.shaders"), t("jarvis.menu.shaders.tooltip"), () -> config.allowShaders, v -> config.allowShaders = v),
						toggle(t("jarvis.menu.servers"), t("jarvis.menu.servers.tooltip"), () -> config.allowServerSwitch, v -> config.allowServerSwitch = v)))));

		BooleanSupplier listening = () -> config.listenToFriends;
		pages.add(new Page(t("jarvis.menu.page.voicechat"), List.of(
				List.of(
						toggle(t("jarvis.menu.broadcast"), t("jarvis.menu.broadcast.tooltip"), () -> config.voicechatBroadcast, v -> config.voicechatBroadcast = v),
						slider(t("jarvis.menu.vc_volume"), t("jarvis.menu.vc_volume.tooltip"), 10, 100, 5, "%", () -> config.voicechatVolume, v -> config.voicechatVolume = v).when(() -> config.voicechatBroadcast),
						info(t("jarvis.menu.status"), t("jarvis.menu.status.tooltip"), () -> VoiceBridge.canSend() ? t("jarvis.menu.status.ready") : t("jarvis.menu.status.muted"))),
				List.of(
						toggle(t("jarvis.menu.friends"), t("jarvis.menu.friends.tooltip"), () -> config.listenToFriends, v -> config.listenToFriends = v),
						cycle(t("jarvis.menu.who"), t("jarvis.menu.who.tooltip"), List.of(false, true), v -> v ? t("jarvis.menu.who.group") : t("jarvis.menu.who.all"), () -> config.friendsOnlyGroup, v -> config.friendsOnlyGroup = v).when(listening),
						slider(t("jarvis.menu.cooldown"), t("jarvis.menu.cooldown.tooltip"), 0, 60, 5, " s", () -> config.friendCooldown, v -> config.friendCooldown = v).when(listening),
						new Toggle(t("jarvis.menu.show_requests"), t("jarvis.menu.show_requests.tooltip"), () -> config.showFriendRequests, v -> config.showFriendRequests = v).when(listening),
						info(t("jarvis.menu.recognition"), t("jarvis.menu.recognition.tooltip"), () -> JarvisMod.friends == null ? "-" : JarvisMod.friends.status()).when(listening),
						info(t("jarvis.menu.recognition_time"), t("jarvis.menu.recognition_time.tooltip"), () -> JarvisMod.friends == null || JarvisMod.friends.lastRecognitionMillis() == 0 ? "-" : String.format("%.1f s", JarvisMod.friends.lastRecognitionMillis() / 1000.0)).when(listening)))));
	}

	private Row toggle(String label, String tooltip, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		return new Toggle(label, tooltip, getter, setter);
	}

	private <T> Row cycle(String label, String tooltip, List<T> values, Function<T, String> text, Supplier<T> getter, Consumer<T> setter) {
		return new Row(label, tooltip) {
			@Override
			String value() {
				return text.apply(getter.get());
			}

			@Override
			void click(double x, int rowRight) {
				setter.accept(values.get((values.indexOf(getter.get()) + 1) % values.size()));
				config.save();
			}
		};
	}

	private Row slider(String label, String tooltip, int min, int max, int step, String unit, IntSupplier getter, IntConsumer setter) {
		return new Slider(label, tooltip, min, max, step, unit, getter, setter);
	}

	private Row action(String label, String tooltip, Runnable run) {
		return new Row(label, tooltip) {
			@Override
			String value() {
				return t("jarvis.menu.run");
			}

			@Override
			void click(double x, int rowRight) {
				run.run();
			}
		};
	}

	private Row info(String label, String tooltip, Supplier<String> value) {
		return new Row(label, tooltip) {
			@Override
			String value() {
				return value.get();
			}
		};
	}

	// ---- Zeichnen ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		g = graphics;
		hits.clear();
		int top = MARGIN;
		int bottom = height - MARGIN - ROW - MARGIN;
		pageList(mouseX, mouseY, top, bottom);
		Row hovered = optionList(mouseX, mouseY, top, bottom);
		if (hovered != null) {
			tooltip(hovered, top, bottom);
		}
		footer(mouseX, mouseY);
	}

	private void pageList(int mx, int my, int top, int bottom) {
		g.fill(MARGIN, top, MARGIN + PAGE_WIDTH, bottom, PANEL);
		g.text(font, "Jarvis", MARGIN + TEXT_PAD, top + 6, THEME, true);
		int y = top + ROW;
		for (int i = 0; i < pages.size(); i++) {
			int index = i;
			boolean selected = page == i;
			if (selected || over(mx, my, MARGIN, y, PAGE_WIDTH, ROW)) {
				g.fill(MARGIN, y, MARGIN + PAGE_WIDTH, y + ROW, ROW_HOVER);
			}
			g.text(font, pages.get(i).name(), MARGIN + TEXT_PAD + 4, y + 6, selected ? THEME : FOREGROUND, true);
			hits.add(new Hit(pages.get(i).name(), MARGIN, y, PAGE_WIDTH, ROW, () -> {
				page = index;
				scroll = 0;
			}));
			y += ROW;
		}
	}

	private Row optionList(int mx, int my, int top, int bottom) {
		int x = MARGIN + PAGE_WIDTH + MARGIN;
		int w = Math.min(OPTION_WIDTH, width - x - MARGIN - 8);
		int viewHeight = bottom - top;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewHeight)));
		Row hovered = null;
		int y = top - scroll;
		g.enableScissor(x, top, x + w, bottom);
		for (List<Row> group : pages.get(page).groups()) {
			for (Row row : group) {
				boolean visible = y + ROW > top && y < bottom;
				boolean hover = visible && over(mx, my, x, y, w, ROW) && my >= top && my < bottom;
				if (visible) {
					drawRow(row, x, y, w, hover);
					if (row.active.getAsBoolean()) {
						int rowRight = x + w;
						hits.add(new Hit(row.label, x, Math.max(y, top), w, Math.min(y + ROW, bottom) - Math.max(y, top), () -> row.click(lastClickX, rowRight)));
					}
				}
				if (hover) {
					hovered = row;
				}
				y += ROW;
			}
			y += GROUP_GAP;
		}
		g.disableScissor();
		contentHeight = y + scroll - top - GROUP_GAP;
		if (contentHeight > viewHeight) {
			int bar = x + w + 2;
			int thumb = Math.max(16, viewHeight * viewHeight / contentHeight);
			int offset = (viewHeight - thumb) * scroll / Math.max(1, contentHeight - viewHeight);
			g.fill(bar, top, bar + 5, bottom, SCROLL_TRACK);
			g.fill(bar + 1, top + offset, bar + 4, top + offset + thumb, SCROLL_THUMB);
		}
		return hovered;
	}

	private void drawRow(Row row, int x, int y, int w, boolean hover) {
		boolean active = row.active.getAsBoolean();
		g.fill(x, y, x + w, y + ROW, hover && active ? ROW_HOVER : ROW_BACKGROUND);
		g.text(font, row.label, x + TEXT_PAD, y + 6, active ? FOREGROUND : DISABLED, true);
		int right = x + w - TEXT_PAD;
		if (row instanceof Toggle toggle) {
			drawTick(right, y, toggle.getter.get(), active);
		} else if (row instanceof Slider slider && active && (hover || dragging == slider)) {
			drawSlider(slider, right, y);
		} else {
			String value = row.value();
			g.text(font, value, right - font.width(value), y + 6, active ? FOREGROUND : DISABLED, true);
		}
	}

	private void drawTick(int right, int y, boolean on, boolean active) {
		int left = right - 10;
		int boxTop = y + 5;
		g.outline(left, boxTop, 10, 10, active ? FOREGROUND : DISABLED);
		if (on) {
			g.fill(left + 2, boxTop + 2, left + 8, boxTop + 8, active ? THEME : DISABLED);
		}
	}

	private void drawSlider(Slider slider, int right, int y) {
		int left = right - SLIDER_WIDTH;
		float share = (slider.getter.getAsInt() - slider.min) / (float) (slider.max - slider.min);
		g.fill(left, y + 10, right, y + 11, DISABLED);
		int thumb = left + Math.round(share * (SLIDER_WIDTH - 4));
		g.fill(thumb, y + 5, thumb + 4, y + 15, FOREGROUND);
		String value = slider.value();
		g.text(font, value, left - font.width(value) - 6, y + 6, FOREGROUND, true);
	}

	private void tooltip(Row row, int top, int bottom) {
		int x = MARGIN + PAGE_WIDTH + MARGIN + OPTION_WIDTH + 10;
		int w = width - x - MARGIN;
		if (w < MIN_TOOLTIP_WIDTH) {
			return;
		}
		List<FormattedCharSequence> lines = font.split(Component.literal(row.tooltip), w - TOOLTIP_PAD * 2);
		int h = lines.size() * (font.lineHeight + 2) + TOOLTIP_PAD * 2;
		g.fill(x, top, x + w, Math.min(top + h, bottom), OVERLAY);
		int y = top + TOOLTIP_PAD;
		for (FormattedCharSequence line : lines) {
			g.text(font, line, x + TOOLTIP_PAD, y, FOREGROUND, true);
			y += font.lineHeight + 2;
		}
	}

	private void footer(int mx, int my) {
		int y = height - MARGIN - ROW;
		button(t("jarvis.menu.done"), width - MARGIN - BUTTON_WIDTH, y, mx, my, this::onClose);
		button(t("jarvis.menu.defaults"), width - MARGIN - BUTTON_WIDTH * 2 - 4, y, mx, my, () -> {
			config.resetToDefaults();
			config.save();
			buildPages();
		});
	}

	private void button(String label, int x, int y, int mx, int my, Runnable action) {
		g.fill(x, y, x + BUTTON_WIDTH, y + ROW, over(mx, my, x, y, BUTTON_WIDTH, ROW) ? ROW_HOVER : ROW_BACKGROUND);
		g.centeredText(font, label, x + BUTTON_WIDTH / 2, y + 6, FOREGROUND);
		hits.add(new Hit(label, x, y, BUTTON_WIDTH, ROW, action));
	}

	private static boolean over(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	// ---- Eingabe ----

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		lastClickX = event.x();
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(event.x(), event.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging != null) {
			dragging.set(event.x(), dragRight);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			config.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll -= (int) (vertical * ROW);
		return true;
	}

	@Override
	public void onClose() {
		config.save();
		super.onClose();
	}

	/** Fuer den Test: loest ein Element ueber seinen Namen aus. */
	boolean press(String label) {
		for (Hit hit : List.copyOf(hits)) {
			if (label.equals(hit.label())) {
				hit.action().run();
				return true;
			}
		}
		return false;
	}
}
