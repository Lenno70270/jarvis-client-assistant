package de.lennard.jarvis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Merkt sich die letzten Chatzeilen, damit Jarvis Serverantworten lesen kann. */
final class ChatLog {
	private static final int MAX_LINES = 60;
	private static final Deque<Line> LINES = new ArrayDeque<>();
	private static long counter;

	record Line(long id, String text, boolean fromServer) {
	}

	private ChatLog() {
	}

	static synchronized void add(String text, boolean fromServer) {
		if (text.startsWith("[Jarvis]")) {
			return;
		}
		LINES.addLast(new Line(++counter, text, fromServer));
		while (LINES.size() > MAX_LINES) {
			LINES.removeFirst();
		}
	}

	static synchronized long mark() {
		return counter;
	}

	/** Nur Systemnachrichten: Was andere Spieler schreiben, gehoert nicht zur Command-Antwort. */
	static synchronized List<String> serverLinesSince(long mark) {
		List<String> result = new ArrayList<>();
		for (Line line : LINES) {
			if (line.id() > mark && line.fromServer()) {
				result.add(line.text());
			}
		}
		return result;
	}

	static synchronized List<String> recent(int count) {
		List<String> all = LINES.stream().map(Line::text).toList();
		return all.subList(Math.max(0, all.size() - count), all.size());
	}
}
