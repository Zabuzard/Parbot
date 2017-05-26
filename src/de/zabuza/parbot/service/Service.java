package de.zabuza.parbot.service;

import de.zabuza.parbot.Parbot;
import de.zabuza.parbot.logging.ILogger;
import de.zabuza.parbot.logging.LoggerFactory;
import de.zabuza.parbot.logging.LoggerUtil;
import de.zabuza.sparkle.IFreewarAPI;
import de.zabuza.sparkle.freewar.IFreewarInstance;

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
	 * Internal flag whether the service should run or not. If set to
	 * <tt>false</tt> the service will not enter the next iteration of its life
	 * cycle and shutdown.
	 */
	private boolean mDoRun;
	/**
	 * The instance of the Freewar API to use for accessing the games contents.
	 */
	private final IFreewarInstance mInstance;
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
	 * The port to use for the chat service
	 */
	private final int mPort;
	/**
	 * The address of the service to use for chat
	 */
	private final String mService;
	/**
	 * Whether the service should stop or not. If set to <tt>true</tt> the
	 * service will try to leave its life cycle in a normal way and shutdown.
	 */
	private boolean mShouldStopService;

	/**
	 * Creates a new Service instance. Call {@link #start()} to start the
	 * service and {@link #stopService()} to stop it.
	 * 
	 * @param port
	 *            The port to use for communication
	 * @param service
	 *            The address of the service to use for chat
	 * @param api
	 *            The Freewar API to use for accessing the games contents
	 * @param instance
	 *            The instance of the Freewar API to use for accessing the games
	 *            contents
	 * @param parent
	 *            The parent object that controls the service. If the service
	 *            shuts down in an abnormal way it will request its parent to
	 *            also shutdown.
	 */
	public Service(final int port, final String service, final IFreewarAPI api, final IFreewarInstance instance,
			final Parbot parent) {
		this.mPort = port;
		this.mService = service;
		this.mApi = api;
		this.mInstance = instance;
		this.mParent = parent;
		this.mLogger = LoggerFactory.getLogger();

		this.mDoRun = true;
		this.mShouldStopService = false;
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
		boolean terminateParent = false;

		// Enter the life cycle
		while (this.mDoRun) {
			try {
				if (this.mShouldStopService) {
					this.mDoRun = false;

				}

				// TODO Do something

				// Delay the next iteration
				waitToNextIteration();
			} catch (final Exception e) {
				this.mLogger.logError("Error while running service, shutting down: " + LoggerUtil.getStackTrace(e));
				// Try to shutdown
				this.mDoRun = false;
				terminateParent = true;
			}
		}

		// If the service is leaved shut it down
		shutdown();

		// Request parent to terminate
		if (terminateParent) {
			this.mParent.shutdown();
		}
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
