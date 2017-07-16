package de.zabuza.parbot.service.routine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriverException;

import de.zabuza.brainbridge.client.BrainBridgeClient;
import de.zabuza.brainbridge.client.BrainInstance;
import de.zabuza.grawlox.Grawlox;
import de.zabuza.parbot.exceptions.FetchAnswerNotPossibleException;
import de.zabuza.parbot.exceptions.ProfanityFilterNoDatabaseException;
import de.zabuza.parbot.exceptions.UserSelectionNotPossibleException;
import de.zabuza.parbot.logging.ILogger;
import de.zabuza.parbot.logging.LoggerFactory;
import de.zabuza.parbot.logging.LoggerUtil;
import de.zabuza.parbot.service.Service;
import de.zabuza.parbot.settings.IBotSettingsProvider;
import de.zabuza.sparkle.freewar.chat.EChatType;
import de.zabuza.sparkle.freewar.chat.IChat;
import de.zabuza.sparkle.freewar.chat.Message;

/**
 * The actual routine of the service which will select a user and start an
 * automatic chat with him. The routine works in rounds, once created use
 * {@link #update()} to spend the routine a round of processing. In a round it
 * will only execute small steps and quickly return so that it does not slow
 * down parent processes. You may just bind it into a life cycle of a
 * controlling thread. With {@link #getPhase()} the current phase of the routine
 * can be accessed. The method {@link #reset()} can be used to reset the routine
 * to its initial situation and begin with the first phase again.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public final class Routine {
	/**
	 * Constant for an empty text.
	 */
	private static final String EMPTY_TEXT = "";
	/**
	 * Needle that matches the name of the user in the chat with the API.
	 */
	private static final String GUEST_NEEDLE = "gast";
	/**
	 * Amount of how many update phases the routine is allowed to use for
	 * resolving a problem by itself. If it does not resolve the problem within
	 * this limit it must give up and throw the problem to parent objects.
	 */
	private final static int PROBLEM_SELF_RESOLVING_TRIES_MAX = 5;
	/**
	 * Amount of how many update phases the routine needs to pass without
	 * encountering a problem until it is allowed to reset the resolving tries
	 * counter.
	 */
	private static final int PROBLEM_SELF_RESOLVING_TRIES_RESET = 3;
	/**
	 * Prepend this operator to indicate that a REGEX should match case
	 * insensitive.
	 */
	private static final String REGEX_CASE_INSENSITIVE_OPERATOR = "(?i)";
	/**
	 * Pattern that matches all characters that get ignored on comparing
	 * messages to determine if they are identical.
	 */
	private static final String REGEX_IGNORE_ON_MESSAGES_COMPARISON = "[^A-Za-z]";

	/**
	 * Whether the two given messages are identical. Comparison is made in lower
	 * case without special symbols.
	 * 
	 * @param firstMessage
	 *            The first message to compare with
	 * @param secondMessage
	 *            The second message to compare against
	 * @return <tt>True</tt> if the two given messages are identical,
	 *         <tt>false</tt> if not
	 */
	private static boolean areMessagesIdentical(final String firstMessage, final String secondMessage) {
		if (firstMessage == null || secondMessage == null) {
			return false;
		}

		// Compare in lower case without special symbols
		final String firstMessagePrepared = firstMessage.toLowerCase().replaceAll(REGEX_IGNORE_ON_MESSAGES_COMPARISON,
				EMPTY_TEXT);
		final String secondMessagePrepared = secondMessage.toLowerCase().replaceAll(REGEX_IGNORE_ON_MESSAGES_COMPARISON,
				EMPTY_TEXT);

		return firstMessagePrepared.equals(secondMessagePrepared);
	}

	/**
	 * The client to use for accessing the brain bridge API.
	 */
	private final BrainBridgeClient mBrainBridge;
	/**
	 * The chat instance of the Freewar API to use for accessing the games chat.
	 */
	private final IChat mChat;
	/**
	 * The username of the chat-bot for distinguishing own posted message from
	 * other users.
	 */
	private final String mChatbotUsername;
	/**
	 * Only select users from the given chat type.
	 */
	private final EChatType mChatTypeRestriction;
	/**
	 * The current chat instance to use or <tt>null</tt> if there is no.
	 */
	private BrainInstance mCurrentInstance;
	/**
	 * The name of the current selected user or <tt>null</tt> if there is no.
	 */
	private String mCurrentSelectedUser;
	/**
	 * The timeout limit when receiving no messages from a player triggers the
	 * selection of a new player.
	 */
	private final long mFocusLostTimeout;
	/**
	 * The last known message of all players or <tt>null</tt> if there is no.
	 */
	private Message mLastKnownMessage;
	/**
	 * The logger to use for logging.
	 */
	private final ILogger mLogger;
	/**
	 * Counter which counts against a maximal timeout limit. If reached a new
	 * user will be selected. The counter increases each time no new message was
	 * received from the current selected player.
	 */
	private long mNoMessageTimeoutCounter;
	/**
	 * Timestamp of the last time when no new message was received from the
	 * current selected player or <tt>0</tt> if that was not the case.
	 */
	private long mNoMessageTimeoutLastTimestamp;
	/**
	 * The current phase the routine is in.
	 */
	private EPhase mPhase;
	/**
	 * The latest answer of the chat bot to the current selected player or
	 * <tt>null</tt> if there is no.
	 */
	private String mPlayerAnswer;
	/**
	 * The latest message of the current selected player or <tt>null</tt> if
	 * there is no unknown message.
	 */
	private String mPlayerMessage;
	/**
	 * Amount of how often after encountering a problem the routine has
	 * successfully passes an update phase without encountering a problem again.
	 */
	private int mProblemSelfResolvingPhasesWithoutProblem;

	/**
	 * Amount of how often the routine has tried to resolve a problem by itself
	 * in a row. The counter is reseted once it finishes an update phase without
	 * problems and increased whenever an error occurs that the routine likes to
	 * resolve by itself.
	 */
	private int mProblemSelfResolvingTries;

	/**
	 * Profanity filter to use for rejecting profane messages.
	 */
	private final Grawlox mProfanityFilter;

	/**
	 * The service to use for callback when encountering a problem that needs to
	 * be resolved.
	 */
	private final Service mService;

	/**
	 * Whether there was a problem in the last update phase of the routine or
	 * not.
	 */
	private boolean mWasProblemLastUpdate;

	/**
	 * Creates a new instance of a routine with the given data. The routine
	 * works in rounds, once created use {@link #update()} to spend the routine
	 * a round of processing. In a round it will only execute small steps and
	 * quickly return so that it does not slow down parent processes. You may
	 * just bind it into a life cycle of a controlling thread. With
	 * {@link #getPhase()} the current phase of the routine can be accessed. The
	 * method {@link #reset()} can be used to reset the routine to its initial
	 * situation and begin with the first phase again.
	 * 
	 * @param service
	 *            The service to use for callback when encountering problems
	 *            that need to be resolved
	 * @param chat
	 *            The chat instance of the Freewar API to use for accessing the
	 *            games chat
	 * @param brainBridge
	 *            The client to use for accessing the brain bridge API
	 * @param botSettingsProvider
	 *            Object that provides settings about bot settings to use
	 */
	public Routine(final Service service, final IChat chat, final BrainBridgeClient brainBridge,
			final IBotSettingsProvider botSettingsProvider) {
		this.mLogger = LoggerFactory.getLogger();
		this.mService = service;
		this.mChat = chat;
		this.mBrainBridge = brainBridge;
		this.mPhase = EPhase.SELECT_USER;
		try {
			this.mProfanityFilter = Grawlox.createFromDefault();
		} catch (final IOException e) {
			throw new ProfanityFilterNoDatabaseException();
		}

		this.mChatbotUsername = botSettingsProvider.getChatbotUsername();
		this.mFocusLostTimeout = botSettingsProvider.getFocusLostTimeout().longValue();
		this.mChatTypeRestriction = botSettingsProvider.getChatTypeRestriction();

		this.mProblemSelfResolvingTries = 0;
		this.mProblemSelfResolvingPhasesWithoutProblem = 0;
		this.mWasProblemLastUpdate = false;

		this.mCurrentInstance = null;
		this.mCurrentSelectedUser = null;
		this.mPlayerMessage = null;
		this.mLastKnownMessage = null;
		this.mPlayerAnswer = null;
		this.mNoMessageTimeoutCounter = 0L;
		this.mNoMessageTimeoutLastTimestamp = 0L;
	}

	/**
	 * Gets the current phase of the routine. Can be used by a parent object to
	 * fetch the progress of the routine.
	 * 
	 * @return The current phase of the routine
	 */
	public EPhase getPhase() {
		return this.mPhase;
	}

	/**
	 * Resets the routine to its initial situation such that the next call of
	 * {@link #update()} will begin with the first phase again.
	 */
	public void reset() {
		setPhase(EPhase.SELECT_USER);

		this.mWasProblemLastUpdate = false;
		this.mProblemSelfResolvingTries = 0;
		this.mProblemSelfResolvingPhasesWithoutProblem = 0;

		if (this.mCurrentInstance != null) {
			this.mCurrentInstance.shutdown();
		}
		this.mCurrentSelectedUser = null;
		this.mPlayerMessage = null;
		this.mLastKnownMessage = null;
		this.mPlayerAnswer = null;
		this.mNoMessageTimeoutCounter = 0L;
		this.mNoMessageTimeoutLastTimestamp = 0L;
	}

	/**
	 * The routine works in rounds, once created this method is used to spend
	 * the routine a round of processing. In a round it will only execute small
	 * steps and quickly return so that it does not slow down parent processes.
	 * You may just bind it into a life cycle of a controlling thread. If a
	 * problem occurs that can not be resolved the method will catch it and
	 * callback with {@link Service#setProblem(Exception)} to the parent
	 * service.
	 */
	public void update() {
		// Check the problem state
		if (!this.mWasProblemLastUpdate) {
			// There was no problem in the last round
			if (this.mProblemSelfResolvingTries > 0) {
				this.mProblemSelfResolvingPhasesWithoutProblem++;
				if (this.mProblemSelfResolvingPhasesWithoutProblem >= PROBLEM_SELF_RESOLVING_TRIES_RESET) {
					// Managed enough rounds without problem to reset the
					// counter
					this.mProblemSelfResolvingPhasesWithoutProblem = 0;
					this.mProblemSelfResolvingTries = 0;
				}
			}
		} else {
			// Reset the problem state for this round
			this.mWasProblemLastUpdate = false;
		}

		try {
			// Select user phase
			if (getPhase() == EPhase.SELECT_USER) {
				if (this.mLogger.isDebugEnabled()) {
					this.mLogger.logDebug("Starting select user phase");
				}

				selectUser();

				// If user selection was not possible and the fault lies by the
				// brain bridge API
				if (this.mCurrentSelectedUser != null && this.mCurrentInstance == null) {
					throw new UserSelectionNotPossibleException();
				}

				// Proceed to the next phase if a user could be selected
				if (this.mCurrentInstance != null && this.mCurrentSelectedUser != null) {
					setPhase(EPhase.FETCH_PLAYER_MESSAGE);
				}
				return;
			}

			// Fetch player message phase
			if (getPhase() == EPhase.FETCH_PLAYER_MESSAGE) {
				if (this.mLogger.isDebugEnabled()) {
					this.mLogger.logDebug("Starting fetch player message phase");
				}

				fetchPlayerMessage();

				// If there was no unknown message
				if (this.mPlayerMessage == null) {
					if (this.mNoMessageTimeoutLastTimestamp == 0L) {
						if (this.mLogger.isDebugEnabled()) {
							this.mLogger.logDebug("No message received, start counting");
						}
						// Start counting from now on
						this.mNoMessageTimeoutLastTimestamp = System.currentTimeMillis();
					} else {
						final long timeNow = System.currentTimeMillis();
						final long difference = timeNow - this.mNoMessageTimeoutLastTimestamp;
						this.mNoMessageTimeoutLastTimestamp = timeNow;
						if (this.mLogger.isDebugEnabled()) {
							this.mLogger.logDebug("No message received, increasing by " + difference);
						}
						// Add the difference from the last time to now
						this.mNoMessageTimeoutCounter += difference;
					}
				} else {
					if (this.mLogger.isDebugEnabled()) {
						this.mLogger.logDebug("Message received");
					}
					// Reset the counter
					this.mNoMessageTimeoutCounter = 0L;
					this.mNoMessageTimeoutLastTimestamp = 0L;
				}

				// Proceed to the next phase
				if (this.mNoMessageTimeoutCounter >= this.mFocusLostTimeout) {
					if (this.mLogger.isDebugEnabled()) {
						this.mLogger.logDebug("No message receive limit exceeded");
					}
					// Select a new user
					setPhase(EPhase.SELECT_USER);
				} else if (this.mPlayerMessage == null && this.mLastKnownMessage != null) {
					// Check if a user requests focus switch
					final Optional<String> senderOfLastMessage = this.mLastKnownMessage.getSender();
					if (senderOfLastMessage.isPresent()
							&& !senderOfLastMessage.get().equals(this.mCurrentSelectedUser)) {
						final String lastMessageContent = this.mLastKnownMessage.getContent().toLowerCase();
						final String focusSwitchNeedle = this.mChatbotUsername.toLowerCase();
						if (lastMessageContent.contains(focusSwitchNeedle)) {
							// Select a new user
							if (this.mLogger.isDebugEnabled()) {
								this.mLogger.logDebug("User requested focus switch");
							}
							setPhase(EPhase.SELECT_USER);
						}
					}
				} else if (this.mPlayerMessage != null) {
					// Continue with the regular phase sequence if a message
					// could be found
					setPhase(EPhase.FETCH_ANSWER);
				}
				return;
			}

			// Fetch answer phase
			if (getPhase() == EPhase.FETCH_ANSWER) {
				if (this.mLogger.isDebugEnabled()) {
					this.mLogger.logDebug("Starting fetch answer phase");
				}

				fetchAnswer();

				// If an answer could not be fetched
				if (this.mPlayerAnswer == null) {
					throw new FetchAnswerNotPossibleException();
				}

				// Proceed to the next phase
				setPhase(EPhase.POST_ANSWER);
				return;
			}

			// Post answer phase
			if (getPhase() == EPhase.POST_ANSWER) {
				if (this.mLogger.isDebugEnabled()) {
					this.mLogger.logDebug("Starting post answer phase");
				}

				postAnswer();

				// Proceed to the next phase
				setPhase(EPhase.FETCH_PLAYER_MESSAGE);
				return;
			}
		} catch (final WebDriverException | UserSelectionNotPossibleException | FetchAnswerNotPossibleException e) {
			if (this.mProblemSelfResolvingTries >= PROBLEM_SELF_RESOLVING_TRIES_MAX) {
				// The problem could not get resolved in the limit
				this.mWasProblemLastUpdate = true;
				this.mService.setProblem(e);
			} else {
				// Log the problem but continue
				this.mWasProblemLastUpdate = true;
				this.mProblemSelfResolvingTries++;
				// Do not log unfixable errors
				if (!(e instanceof StaleElementReferenceException || e instanceof TimeoutException
						|| e instanceof NoSuchElementException)) {
					this.mLogger.logError("Error while routine: " + LoggerUtil.getStackTrace(e));
				}
			}
		} catch (final Exception e) {
			this.mWasProblemLastUpdate = true;
			this.mService.setProblem(e);
		}
	}

	/**
	 * Posts the player message to the chat service and fetches the answer of
	 * the bot.
	 */
	private void fetchAnswer() {
		// Free previous resources
		this.mPlayerAnswer = null;

		this.mPlayerAnswer = this.mCurrentInstance.post(this.mPlayerMessage);
	}

	/**
	 * Fetches the latest message of the current selected player if it is
	 * unknown.
	 */
	private void fetchPlayerMessage() {
		// Free previous resources
		this.mPlayerMessage = null;

		final ArrayList<Message> chatMessages = this.mChat.getMessages(this.mChatTypeRestriction);

		// Reversely iterate the chat messages to find the latest unknown
		// message
		for (int i = chatMessages.size() - 1; i >= 0; i--) {
			final Message currentMessage = chatMessages.get(i);
			// All unknown message where fetched, reached only known messages
			if (currentMessage.equals(this.mLastKnownMessage)) {
				break;
			}

			// Search unknown messages of the current selected player
			final Optional<String> sender = currentMessage.getSender();
			if (sender.isPresent() && this.mCurrentSelectedUser.equals(sender.get())
					&& !isProfane(currentMessage.getContent())) {
				// The message is unknown, of the current selected player and
				// not profane
				// Remove all occurrences of the bot name so that the chat bot
				// feels addressed
				this.mPlayerMessage = currentMessage.getContent()
						.replaceAll(REGEX_CASE_INSENSITIVE_OPERATOR + this.mChatbotUsername, EMPTY_TEXT);
				break;
			}
		}

		// Update the last known message
		if (!chatMessages.isEmpty()) {
			this.mLastKnownMessage = chatMessages.get(chatMessages.size() - 1);
		}
	}

	/**
	 * Whether the given input is profane, i.e. if it contains swearwords.<br>
	 * Also allows logging of profane messages.
	 * 
	 * @param message
	 *            The input in question
	 * @return <tt>True</tt> if the input contains swearwords, <tt>false</tt>
	 *         otherwise
	 */
	private boolean isProfane(final String message) {
		final boolean isProfane = this.mProfanityFilter.isProfane(message);
		if (isProfane) {
			this.mLogger.logInfo("Message is profane: " + message);
		}
		return isProfane;
	}

	/**
	 * Posts the current answer of the chat bot to the current selected player
	 * in the game.
	 */
	private void postAnswer() {
		final String adjustedAnswer = this.mPlayerAnswer.replaceAll(REGEX_CASE_INSENSITIVE_OPERATOR + GUEST_NEEDLE,
				this.mCurrentSelectedUser);
		// Do not post the message if it is profane or identical to the initial
		// message of the player (could get interpreted as spam)
		if (!isProfane(adjustedAnswer) && !areMessagesIdentical(this.mPlayerMessage, this.mPlayerAnswer)) {
			this.mChat.submitMessage(adjustedAnswer, this.mChatTypeRestriction);
		}
	}

	/**
	 * Selects a user from the chat and creates a chat instance for chatting
	 * with him.
	 */
	private void selectUser() {
		// Free previous resources
		if (this.mCurrentInstance != null) {
			this.mCurrentInstance.shutdown();
			this.mCurrentInstance = null;
		}
		this.mCurrentSelectedUser = null;
		this.mLastKnownMessage = null;
		this.mNoMessageTimeoutCounter = 0L;
		this.mNoMessageTimeoutLastTimestamp = 0L;

		// Select a new user
		final ArrayList<Message> chatMessages = this.mChat.getMessages(this.mChatTypeRestriction);

		// Reversely iterate the chat messages to find a user
		for (int i = chatMessages.size() - 1; i >= 0; i--) {
			final Message lastMessage = chatMessages.get(chatMessages.size() - 1);
			final Optional<String> sender = lastMessage.getSender();
			if (sender.isPresent()) {
				final String senderCandidate = sender.get();
				// Reject the candidate if it is the chat-bot itself or the
				// message is profane
				if (!senderCandidate.equals(this.mChatbotUsername) && !isProfane(lastMessage.getContent())) {
					this.mCurrentSelectedUser = senderCandidate;
					break;
				}
			}
		}

		// There was no user to select, yield the iteration
		if (this.mCurrentSelectedUser == null) {
			return;
		}

		// Create an instance for the user
		this.mCurrentInstance = this.mBrainBridge.createInstance();
	}

	/**
	 * Sets the current phase the routine is in.
	 * 
	 * @param phase
	 *            The phase to set
	 */
	private void setPhase(final EPhase phase) {
		this.mPhase = phase;
	}
}
