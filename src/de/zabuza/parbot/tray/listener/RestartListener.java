package de.zabuza.parbot.tray.listener;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import de.zabuza.parbot.Parbot;
import de.zabuza.parbot.logging.ILogger;
import de.zabuza.parbot.logging.LoggerFactory;

/**
 * Listener to use for restarting the tool. When the event arrives it performs
 * {@link Parbot#stop()} and {@link Parbot#start()}.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public final class RestartListener implements ActionListener {
	/**
	 * The logger to use for logging.
	 */
	private final ILogger mLogger;
	/**
	 * The parent tool to restart when the event arrives.
	 */
	private final Parbot mParent;

	/**
	 * Creates a new restart listener that restarts the given tool when an
	 * action event arrives. Therefore it performs {@link Parbot#stop()} and
	 * {@link Parbot#start()}.
	 * 
	 * @param parent
	 *            The tool to shutdown when the event arrives
	 */
	public RestartListener(final Parbot parent) {
		this.mParent = parent;
		this.mLogger = LoggerFactory.getLogger();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	@Override
	public void actionPerformed(final ActionEvent e) {
		this.mLogger.logInfo("Executing restart action");
		this.mParent.stop();
		this.mParent.start();
	}

}
