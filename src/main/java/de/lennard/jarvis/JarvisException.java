package de.lennard.jarvis;

/** Ein erwarteter Fehlschlag, dessen Text direkt dem Spieler oder dem Modell gezeigt wird. */
class JarvisException extends RuntimeException {
	JarvisException(String message) {
		super(message);
	}
}
