package de.zabuza.parbot.service;

import de.zabuza.brainbridge.client.BrainBridgeClient;
import de.zabuza.parbot.Parbot;
import de.zabuza.parbot.logging.ILogger;
import de.zabuza.parbot.logging.LoggerFactory;
import de.zabuza.parbot.logging.LoggerUtil;
import de.zabuza.parbot.service.routine.Routine;
import de.zabuza.sparkle.IFreewarAPI;
import de.zabuza.sparkle.freewar.chat.IChat;

/**
 * Actual service thread of the tool. Once a connection to Freewar was
 * established this service enters a loop that will select a user and start an
 * automatic chat with him for a given time. Call {@link #start()} to start the
 * service and {@link #stopService()} to stop it. If the service leaves its life
 * cycle abnormally it will request the parent tool to also shutdown.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public final class Service extends Thread {
	/**
	 * The time in milliseconds to wait for the next iteration of the life
	 * cycle.
	 */
	private final static long SERVICE_INTERVAL = 200;
	/**
	 * The Freewar API to use for accessing the games contents.
	 */
	private final IFreewarAPI mApi;
	/**
	 * The client to use for accessing the brain bridge API.
	 */
	private final BrainBridgeClient mBrainBridge;
	/**
	 * The chat instance of the Freewar API to use for accessing the games chat.
	 */
	private final IChat mChat;
	/**
	 * Internal flag whether the service should run or not. If set to
	 * <tt>false</tt> the service will not enter the next iteration of its life
	 * cycle and shutdown.
	 */
	private boolean mDoRun;
	/**
	 * The logger to use for logging.
	 */
	private final ILogger mLogger;
	/**
	 * The parent object that controls the service. If the service shuts down it
	 * will request its parent to also shutdown.
	 */
	private final Parbot mParent;
	/**
	 * Set if the service encountered a problem that needs to be resolved,
	 * <tt>null</tt> else. Use {@link #setProblem(Exception)} to set it. Use
	 * {@link #hasProblem()} to ask if there is currently a problem and
	 * {@link #getProblemTimestamp()} to get a timestamp of the last encountered
	 * problem.
	 */
	private Exception mProblem;
	/**
	 * The timestamp of the last encountered problem. Use
	 * {@link #setProblem(Exception)} to set a problem. Use
	 * {@link #hasProblem()} to ask if there is currently a problem and
	 * {@link #getProblemTimestamp()} to get a timestamp of the last encountered
	 * problem.
	 */
	private long mProblemTimestamp;
	/**
	 * The actual routine of the service which will select a user and start an
	 * automatic chat with him.
	 */
	private Routine mRoutine;
	/**
	 * Whether the service should stop or not. If set to <tt>true</tt> the
	 * service will try to leave its life cycle in a normal way and shutdown.
	 */
	private boolean mShouldStopService;
	/**
	 * A timestamp in milliseconds when the service must shutdown itself as
	 * assigned time window was exceeded.
	 */
	private final long mTerminationTimeStamp;

	/**
	 * Creates a new Service instance. Call {@link #start()} to start the
	 * service and {@link #stopService()} to stop it.
	 * 
	 * @param api
	 *            The Freewar API to use for accessing the games contents
	 * @param chat
	 *            The chat instance of the Freewar API to use for accessing the
	 *            games chat
	 * @param brainBridge
	 *            The client to use for accessing the brain bridge API
	 * @param terminationTimeStamp
	 *            A timestamp in milliseconds when the service must shutdown
	 *            itself as assigned time window was exceeded
	 * 
	 * @param parent
	 *            The parent object that controls the service. If the service
	 *            shuts down in an abnormal way it will request its parent to
	 *            also shutdown.
	 */
	public Service(final IFreewarAPI api, final IChat chat, final BrainBridgeClient brainBridge,
			final long terminationTimeStamp, final Parbot parent) {
		this.mApi = api;
		this.mChat = chat;
		this.mBrainBridge = brainBridge;
		this.mTerminationTimeStamp = terminationTimeStamp;
		this.mParent = parent;
		this.mRoutine = null;
		this.mLogger = LoggerFactory.getLogger();

		this.mDoRun = true;
		this.mShouldStopService = false;
	}

	/**
	 * Gets the current encountered problem if set. It is set if the service
	 * encountered a problem that needs to be resolved, <tt>null</tt> else. Use
	 * {@link #setProblem(Exception)} to set it. Use {@link #hasProblem()} to
	 * ask if there is currently a problem and {@link #getProblemTimestamp()} to
	 * get a timestamp of the last encountered problem.
	 * 
	 * @return The current encountered problem or <tt>null</tt> if there is no
	 */
	public Exception getProblem() {
		return this.mProblem;
	}

	/**
	 * The timestamp of the last encountered problem. Use
	 * {@link #setProblem(Exception)} to set a problem. Use
	 * {@link #hasProblem()} to ask if there is currently a problem and
	 * {@link #getProblem()} to actually get it.
	 * 
	 * @return The timestamp of the last encountered problem
	 */
	public long getProblemTimestamp() {
		return this.mProblemTimestamp;
	}

	/**
	 * Whether the service currently encountered a problem that needs to be
	 * resolved. A problem can be set by using {@link #setProblem(Exception)}
	 * and accessed by {@link #getProblem()}.
	 * 
	 * @return <tt>True</tt> if there is a problem, <tt>false</tt> if not.
	 */
	public boolean hasProblem() {
		return this.mProblem != null;
	}

	/**
	 * Whether the service is alive and running.
	 * 
	 * @return <tt>True</tt> if the service is alive and running, <tt>false</tt>
	 *         otherwise
	 */
	public boolean isActive() {
		return this.mDoRun;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run() {
		try {
			// Create the routine
			this.mRoutine = new Routine(this, this.mChat, this.mBrainBridge);
		} catch (final Exception e) {
			// Do not enter the service loop
			this.mLogger.logError("Error while starting service, not entering: " + LoggerUtil.getStackTrace(e));
			this.mDoRun = false;
		}

		// Enter the life cycle
		while (this.mDoRun) {
			try {
				final long currentTime = System.currentTimeMillis();
				if (hasProblem() || this.mShouldStopService || currentTime >= this.mTerminationTimeStamp) {
					this.mDoRun = false;
				}

				if (this.mDoRun) {
					this.mRoutine.update();
				}

				// Delay the next iteration
				waitToNextIteration();
			} catch (final Exception e) {
				this.mLogger.logError("Error while running service, shutting down: " + LoggerUtil.getStackTrace(e));
				// Try to shutdown
				this.mDoRun = false;
			}
		}

		// If the service is leaved shut it down
		shutdown();

		// Request parent to terminate
		this.mParent.shutdown();
	}

	/**
	 * Set if the service encountered a problem that needs to be resolved. Use
	 * {@link #getProblem()} to get it. Use {@link #hasProblem()} to ask if
	 * there is currently a problem and {@link #getProblemTimestamp()} to get a
	 * timestamp of the last encountered problem.
	 * 
	 * @param problem
	 *            The problem to set
	 */
	public void setProblem(final Exception problem) {
		this.mProblem = problem;
		this.mProblemTimestamp = System.currentTimeMillis();
		this.mLogger.logError("Problem registered: " + LoggerUtil.getStackTrace(problem));
		this.mLogger.flush();
	}

	/**
	 * Requests the service to stop. It will try to end its life cycle in a
	 * normal way and shutdown.
	 */
	public void stopService() {
		this.mShouldStopService = true;
	}

	/**
	 * Waits a given time before executing the next iteration of the services
	 * life cycle.
	 */
	public void waitToNextIteration() {
		try {
			sleep(SERVICE_INTERVAL);
		} catch (final InterruptedException e) {
			// Log the error but continue
			this.mLogger.logError("Service wait got interrupted: " + LoggerUtil.getStackTrace(e));
		}
	}

	/**
	 * Shuts the service down. Afterwards this instance can not be used anymore,
	 * instead create a new one.
	 */
	private void shutdown() {
		this.mLogger.logInfo("Shutting down service");
		if (this.mRoutine != null) {
			try {
				this.mRoutine.reset();
			} catch (final Exception e) {
				// Log the error but continue
				this.mLogger.logError("Error while shutting down routine: " + LoggerUtil.getStackTrace(e));
			}
		}
		if (this.mApi != null) {
			try {
				this.mApi.shutdown(true);
			} catch (final Exception e) {
				// Log the error but continue
				this.mLogger.logError("Error while shutting down API: " + LoggerUtil.getStackTrace(e));
			}
		}
	}
}
