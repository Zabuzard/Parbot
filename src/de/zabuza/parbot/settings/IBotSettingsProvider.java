package de.zabuza.parbot.settings;

import de.zabuza.sparkle.freewar.chat.EChatType;

/**
 * Interface for objects that provide settings for the bot.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public interface IBotSettingsProvider {
	/**
	 * Gets the name of the chat bot.
	 * 
	 * @return the name of the chat bot or <tt>null</tt> if not set
	 */
	public String getChatbotUsername();

	/**
	 * Gets the chat type the bot is restricted to.
	 * 
	 * @return The chat type the bot is restricted to
	 */
	public EChatType getChatTypeRestriction();

	/**
	 * Gets the timeout after which the bot looses focus of its current player
	 * when receiving no message.
	 * 
	 * @return The timeout after which the bot looses focus or <tt>null</tt> if
	 *         not set
	 */
	public Long getFocusLostTimeout();

	/**
	 * Gets the port at which the service can be reached.
	 * 
	 * @return The port at which the service can be reached or <tt>null</tt> if
	 *         not set
	 */
	public Integer getPort();

	/**
	 * Gets the address at which the service can be reached.
	 * 
	 * @return The address at which the service can be reached
	 */
	public String getServerAddress();

	/**
	 * Gets the time window in minutes the service is allowed to use. It must
	 * shutdown after exceeding this time window.
	 * 
	 * @return The time window in minutes the service is allowed to use or
	 *         <tt>null</tt> if not set. Values equal or below <tt>zero</tt>
	 *         indicate that no time limit is to be used.
	 */
	public Integer getTimeWindow();
}
