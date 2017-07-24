package de.zabuza.parbot.exceptions;

/**
 * Exception that is thrown whenever the language detection service is not
 * available, for example if the given API key was wrong.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public final class LanguageDetectionServiceUnavailableException extends IllegalStateException {

	/**
	 * Serial version UID.
	 */
	private static final long serialVersionUID = 1L;

}
