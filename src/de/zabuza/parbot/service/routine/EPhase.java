package de.zabuza.parbot.service.routine;

/**
 * All different phases of the tool.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public enum EPhase {
	/**
	 * Phase in which the tool fetches the answer of the chat bot.
	 */
	FETCH_ANSWER,
	/**
	 * Phase in which the tool fetches the latest message of the player.
	 */
	FETCH_PLAYER_MESSAGE,
	/**
	 * Phase in which the tool posts the answer of the chat bot to the player.
	 */
	POST_ANSWER,
	/**
	 * Phase in which the tool selects a user to chat with.
	 */
	SELECT_USER
}
