package de.lennard.jarvis;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Alles, was Jarvis ausserhalb von Minecraft nachschlagen kann. */
final class Web {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final Pattern RESULT = Pattern.compile(
			"class=\"result__a\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
	private static final int MAX_RESULTS = 5;

	private Web() {
	}

	private static String get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
				.GET()
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static String plain(String html) {
		return html.replaceAll("<[^>]+>", "")
				.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
				.replaceAll("\\s+", " ").trim();
	}

	static String search(String query) throws InterruptedException {
		try {
			Matcher matcher = RESULT.matcher(get("https://html.duckduckgo.com/html/?kl=de-de&q=" + encode(query)));
			List<String> results = new ArrayList<>();
			while (matcher.find() && results.size() < MAX_RESULTS) {
				results.add(plain(matcher.group(1)) + ": " + plain(matcher.group(2)));
			}
			if (!results.isEmpty()) {
				return String.join("\n", results);
			}
		} catch (IOException e) {
			System.err.println("[Jarvis] Suche: " + e);
		}
		return wikipedia(query);
	}

	private static String wikipedia(String query) throws InterruptedException {
		try {
			JsonArray hits = JsonParser.parseString(get("https://de.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=4&srsearch=" + encode(query)))
					.getAsJsonObject().getAsJsonObject("query").getAsJsonArray("search");
			List<String> results = new ArrayList<>();
			for (JsonElement hit : hits) {
				JsonObject page = hit.getAsJsonObject();
				results.add(page.get("title").getAsString() + ": " + plain(page.get("snippet").getAsString()));
			}
			return results.isEmpty() ? "Keine Treffer." : String.join("\n", results);
		} catch (IOException | RuntimeException e) {
			return "Die Suche ist gerade nicht erreichbar.";
		}
	}

	static String weather(String place) throws InterruptedException {
		try {
			JsonElement results = JsonParser.parseString(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=de&name=" + encode(place)))
					.getAsJsonObject().get("results");
			if (results == null || results.getAsJsonArray().isEmpty()) {
				return "Den Ort \"" + place + "\" kenne ich nicht.";
			}
			JsonObject location = results.getAsJsonArray().get(0).getAsJsonObject();
			String url = "https://api.open-meteo.com/v1/forecast?timezone=auto&forecast_days=2"
					+ "&current=temperature_2m,apparent_temperature,precipitation,wind_speed_10m,weather_code"
					+ "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code"
					+ "&latitude=" + location.get("latitude").getAsString() + "&longitude=" + location.get("longitude").getAsString();
			JsonObject forecast = JsonParser.parseString(get(url)).getAsJsonObject();
			JsonObject now = forecast.getAsJsonObject("current");
			JsonObject daily = forecast.getAsJsonObject("daily");
			return location.get("name").getAsString() + " jetzt: " + sky(now.get("weather_code").getAsInt())
					+ ", " + now.get("temperature_2m").getAsString() + " °C, gefühlt " + now.get("apparent_temperature").getAsString()
					+ " °C, Wind " + now.get("wind_speed_10m").getAsString() + " km/h. Morgen: "
					+ sky(daily.getAsJsonArray("weather_code").get(1).getAsInt()) + ", "
					+ daily.getAsJsonArray("temperature_2m_min").get(1).getAsString() + " bis "
					+ daily.getAsJsonArray("temperature_2m_max").get(1).getAsString() + " °C, Regenrisiko "
					+ daily.getAsJsonArray("precipitation_probability_max").get(1).getAsString() + " %.";
		} catch (IOException | RuntimeException e) {
			return "Das Wetter ist gerade nicht abrufbar.";
		}
	}

	private static String sky(int code) {
		if (code == 0) {
			return "klar";
		}
		if (code <= 3) {
			return "bewölkt";
		}
		if (code <= 48) {
			return "Nebel";
		}
		if (code <= 67 || (code >= 80 && code <= 82)) {
			return "Regen";
		}
		if (code <= 77 || code == 85 || code == 86) {
			return "Schnee";
		}
		return "Gewitter";
	}

	/** Kleiner Rechner fuer + - * / ^ und Klammern, weil Sprachmodelle sich verrechnen. */
	static String calculate(String expression) {
		try {
			Calculator calculator = new Calculator(expression.replace(",", ".").replace("x", "*").replace(":", "/").replaceAll("\\s+", ""));
			double value = calculator.parse();
			return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.valueOf(value);
		} catch (RuntimeException e) {
			return "Den Ausdruck kann ich nicht rechnen.";
		}
	}

	private static final class Calculator {
		private final String text;
		private int position;

		Calculator(String text) {
			this.text = text;
		}

		double parse() {
			double value = sum();
			if (position != text.length()) {
				throw new IllegalArgumentException(text);
			}
			return value;
		}

		private boolean eat(char expected) {
			if (position < text.length() && text.charAt(position) == expected) {
				position++;
				return true;
			}
			return false;
		}

		private double sum() {
			double value = product();
			while (true) {
				if (eat('+')) {
					value += product();
				} else if (eat('-')) {
					value -= product();
				} else {
					return value;
				}
			}
		}

		private double product() {
			double value = power();
			while (true) {
				if (eat('*')) {
					value *= power();
				} else if (eat('/')) {
					value /= power();
				} else {
					return value;
				}
			}
		}

		private double power() {
			double value = atom();
			return eat('^') ? Math.pow(value, power()) : value;
		}

		private double atom() {
			if (eat('-')) {
				return -atom();
			}
			if (eat('(')) {
				double value = sum();
				if (!eat(')')) {
					throw new IllegalArgumentException(text);
				}
				return value;
			}
			int start = position;
			while (position < text.length() && (Character.isDigit(text.charAt(position)) || text.charAt(position) == '.')) {
				position++;
			}
			return Double.parseDouble(text.substring(start, position));
		}
	}
}
