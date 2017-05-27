package de.zabuza.parbot.service.routine;

import java.util.ArrayList;

import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;

import com.google.common.base.Optional;

import de.zabuza.brainbridge.client.BrainBridgeClient;
import de.zabuza.brainbridge.client.BrainInstance;
import de.zabuza.parbot.exceptions.FetchAnswerNotPossibleException;
import de.zabuza.parbot.exceptions.UserSelectionNotPossibleException;
import de.zabuza.parbot.logging.ILogger;
import de.zabuza.parbot.logging.LoggerFactory;
import de.zabuza.parbot.logging.LoggerUtil;
import de.zabuza.parbot.service.Service;
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
	 * Only select users from the given chat type.
	 */
	private static final EChatType CHAT_TYPE_RESTRICTION = EChatType.GLOBAL;
	/**
	 * The timeout limit when receiving no messages from a player triggers the
	 * selection of a new player.
	 */
	private static final long NO_MESSAGE_TIMEOUT_LIMIT = 60_000L;
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
	 * The client to use for accessing the brain bridge API.
	 */
	private final BrainBridgeClient mBrainBridge;
	/**
	 * The chat instance of the Freewar API to use for accessing the games chat.
	 */
	private final IChat mChat;
	/**
	 * The current chat instance to use or <tt>null</tt> if there is no.
	 */
	private BrainInstance mCurrentInstance;
	/**
	 * The name of the current selected user or <tt>null</tt> if there is no.
	 */
	private String mCurrentSelectedUser;
	/**
	 * The last known message of the current selected player or <tt>null</tt> if
	 * there is no.
	 */
	private String mLastKnownPlayerMessage;
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
	 */
	public Routine(final Service service, final IChat chat, final BrainBridgeClient brainBridge) {
		this.mLogger = LoggerFactory.getLogger();
		this.mService = service;
		this.mChat = chat;
		this.mBrainBridge = brainBridge;
		this.mPhase = EPhase.SELECT_USER;

		this.mProblemSelfResolvingTries = 0;
		this.mProblemSelfResolvingPhasesWithoutProblem = 0;
		this.mWasProblemLastUpdate = false;

		this.mCurrentInstance = null;
		this.mCurrentSelectedUser = null;
		this.mPlayerMessage = null;
		this.mLastKnownPlayerMessage = null;
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
		this.mLastKnownPlayerMessage = null;
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
				// In other cases simply try it again

				// Proceed to the next phase
				setPhase(EPhase.FETCH_PLAYER_MESSAGE);
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
						// Start counting from now on
						this.mNoMessageTimeoutLastTimestamp = System.currentTimeMillis();
					} else {
						final long timeNow = System.currentTimeMillis();
						// Add the difference from the last time to now
						this.mNoMessageTimeoutCounter += timeNow - this.mNoMessageTimeoutLastTimestamp;
					}
				} else {
					// Reset the counter
					this.mNoMessageTimeoutCounter = 0L;
					this.mNoMessageTimeoutLastTimestamp = 0L;
				}

				// Proceed to the next phase
				if (this.mNoMessageTimeoutCounter >= NO_MESSAGE_TIMEOUT_LIMIT) {
					// Select a new user
					setPhase(EPhase.SELECT_USER);
				} else {
					// Continue with the regular phase sequence
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
		} catch (final StaleElementReferenceException | NoSuchElementException | TimeoutException
				| UserSelectionNotPossibleException | FetchAnswerNotPossibleException e) {
			if (this.mProblemSelfResolvingTries >= PROBLEM_SELF_RESOLVING_TRIES_MAX) {
				// The problem could not get resolved in the limit
				this.mWasProblemLastUpdate = true;
				this.mService.setProblem(e);
			} else {
				// Log the problem but continue
				this.mWasProblemLastUpdate = true;
				this.mProblemSelfResolvingTries++;
				this.mLogger.logError("Error while routine: " + LoggerUtil.getStackTrace(e));
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

		final ArrayList<Message> chatMessages = this.mChat.getMessages(CHAT_TYPE_RESTRICTION);

		// Reversely iterate the chat messages to find the latest unknown
		// message
		for (int i = chatMessages.size() - 1; i >= 0; i--) {
			final Message lastMessage = chatMessages.get(chatMessages.size() - 1);
			final Optional<String> sender = lastMessage.getSender();
			if (sender.isPresent() && this.mCurrentSelectedUser.equals(sender.get())) {
				final String messageContent = lastMessage.getContent();
				if (!messageContent.equals(this.mLastKnownPlayerMessage)) {
					// The message is unknown
					this.mPlayerMessage = messageContent;
					this.mLastKnownPlayerMessage = messageContent;
				}
				break;
			}
		}
	}

	/**
	 * Posts the current answer of the chat bot to the current selected player
	 * in the game.
	 */
	private void postAnswer() {
		final String adjustedAnswer = this.mCurrentSelectedUser + ", " + this.mPlayerAnswer;
		this.mChat.submitMessage(adjustedAnswer, CHAT_TYPE_RESTRICTION);
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
		this.mLastKnownPlayerMessage = null;
		this.mNoMessageTimeoutCounter = 0L;
		this.mNoMessageTimeoutLastTimestamp = 0L;

		// Select a new user
		final ArrayList<Message> chatMessages = this.mChat.getMessages(CHAT_TYPE_RESTRICTION);

		// Reversely iterate the chat messages to find a user
		for (int i = chatMessages.size() - 1; i >= 0; i--) {
			final Message lastMessage = chatMessages.get(chatMessages.size() - 1);
			final Optional<String> sender = lastMessage.getSender();
			if (sender.isPresent()) {
				this.mCurrentSelectedUser = sender.get();
				break;
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
