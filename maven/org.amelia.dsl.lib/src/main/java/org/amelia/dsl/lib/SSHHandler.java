/*
 * Copyright © 2015 Universidad Icesi
 * 
 * This file is part of the Amelia project.
 * 
 * The Amelia project is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * The Amelia project is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with the Amelia project. If not, see <http://www.gnu.org/licenses/>.
 */
package org.amelia.dsl.lib;

import static net.sf.expectit.filter.Filters.removeColors;
import static net.sf.expectit.filter.Filters.removeNonPrintable;
import static net.sf.expectit.matcher.Matchers.regexp;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.amelia.dsl.lib.descriptors.CommandDescriptor;
import org.amelia.dsl.lib.descriptors.Host;
import org.amelia.dsl.lib.util.Arrays;
import org.amelia.dsl.lib.util.AuthenticationUserInfo;
import org.amelia.dsl.lib.util.Log;
import org.amelia.dsl.lib.util.ShellUtils;
import org.amelia.dsl.lib.util.Threads;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import net.sf.expectit.Expect;
import net.sf.expectit.ExpectBuilder;
import net.sf.expectit.Result;

/**
 * @author Miguel Jiménez - Initial contribution and API
 */
public class SSHHandler extends Thread {

	/**
	 * @author Miguel Jiménez - Initial contribution and API
	 */
	public static final class OutputLog implements Appendable {

		/**
		 * All lines read by the SSH channel.
		 */
		private final ArrayList<CharSequence> logs;

		/**
		 * Sinks fed by this appendable.
		 */
		private final List<Appendable> replicas;

		/**
		 * Default constructor.
		 */
		public OutputLog() {
			this.logs = new ArrayList<CharSequence>();
			this.replicas = new ArrayList<Appendable>();
		}

		/**
		 * Echo any char sequence that gets appended to this appendable.
		 * @param appendable The appendable
		 * @return if the appendable is added to the list of replicas
		 */
		public boolean echoTo(Appendable appendable) {
			return this.replicas.add(appendable);
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Appendable#append(java.lang.CharSequence)
		 */
		@Override
		public Appendable append(CharSequence csq) throws IOException {
			if (!csq.toString().isEmpty() && !csq.toString().equals("\n")) {
				this.logs.add(csq);
				for (Appendable tmp: this.replicas)
					tmp.append(csq);
			}
			return this;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Appendable#append(char)
		 */
		@Override
		public Appendable append(char c) throws IOException {
			this.append(String.valueOf(c));
			return this;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Appendable#append(java.lang.CharSequence, int, int)
		 */
		@Override
		public Appendable append(CharSequence csq, int start, int end)
			throws IOException {
			this.append(csq.subSequence(start, end));
			return this;
		}

		/**
		 * @return a cloned version of the current list of logs
		 */
		@SuppressWarnings("unchecked")
		public synchronized List<CharSequence> logs() {
			return (List<CharSequence>) this.logs.clone();
		}

		public List<Appendable> replicas() {
			return this.replicas;
		}
		
	}

	private final Host host;
	
	private final String subsystem;

	private Session session;

	private Channel channel;

	private Expect expect;

	private final int connectionTimeout;

	private final int executionTimeout;

	private File output;

	private final List<CommandDescriptor> executions;

	private final SingleThreadTaskQueue taskQueue;
	
	private final SimpleDateFormat dateFormat;

	/**
	 * A runtime representation of the shell channel.
	 */
	private final OutputLog outputLog;

	/**
	 * The logger
	 */
	private final static Logger logger = LogManager.getLogger(SSHHandler.class);

	public SSHHandler(final Host host, final String subsystem) throws Exception {
		this.host = host;
		this.subsystem = subsystem;
		String _connectionTimeout = System
				.getProperty("amelia.connection_timeout");
		String _executionTimeout = System
				.getProperty("amelia.execution_timeout");
		this.connectionTimeout = Integer.parseInt(_connectionTimeout);
		this.executionTimeout = Integer.parseInt(_executionTimeout);
		this.executions = new ArrayList<CommandDescriptor>();
		this.taskQueue = new SingleThreadTaskQueue();
		this.dateFormat = new SimpleDateFormat("yyyy-MM-dd@HH:mm:ss.SSS");
		// prepare the output log and file
		this.output = createOutputFile();
		this.outputLog = new OutputLog();
		this.outputLog.echoTo(new PrintStream(this.output, "UTF-8"));
		// Handle uncaught exceptions
		this.taskQueue.setUncaughtExceptionHandler(Threads.exceptionHandler());
	}
	
	public void setup() throws Exception {
		connect();
		initialize();
		configure();
	}

	@Override
	public void run() {
		// Once it's configured, it's ready to execute commands
		this.taskQueue.start();
	}

	private void connect() throws JSchException, IOException {
		JSch jsch = new JSch();
		String identity = System.getProperty("amelia.identity");
		String knownHosts = System.getProperty("amelia.known_hosts");

		if (new File(identity).exists())
			jsch.addIdentity(identity);
		else
			logger.warn("Identity file '" + identity
					+ "' not found. Execution will continue without it");

		if (new File(knownHosts).exists())
			jsch.setKnownHosts(knownHosts);
		else
			logger.warn("Known hosts file '" + knownHosts
					+ "' not found. Execution will continue without it");

		this.session = jsch.getSession(this.host.username(),
				this.host.hostname(), this.host.sshPort());

		if (this.host.password() != null)
			this.session.setPassword(this.host.password());

		UserInfo ui = new AuthenticationUserInfo();
		this.session.setUserInfo(ui);
		this.session.connect(this.connectionTimeout);
		this.channel = session.openChannel("shell");
		this.channel.connect(this.connectionTimeout);
	}

	private void initialize() throws Exception {
		this.expect = new ExpectBuilder()
			.withOutput(this.channel.getOutputStream())
			.withInputs(this.channel.getInputStream(),
				this.channel.getExtInputStream())
			.withEchoInput(this.outputLog)
			.withEchoOutput(this.outputLog)
			//.withInputFilters(removeColors(), removeNonPrintable())
			.withExceptionOnFailure()
			.withTimeout(this.executionTimeout, TimeUnit.MILLISECONDS)
			.build();
	}

	private void configure() throws IOException {
		String prompt = ShellUtils.ameliaPromptRegexp();
		String initialPrompt = "\\$|#";

		this.expect.expect(regexp(initialPrompt));

		// Switch off echo
		this.expect.sendLine("stty -echo");
		this.expect.expect(regexp(initialPrompt));

		// Query the current shell
		this.expect.sendLine(ShellUtils.currentShellCommand());
		Result result = this.expect.expect(regexp(initialPrompt));

		String shell = result.getBefore().split("\n")[0].trim();

		if (!shell.matches("bash|zsh")) {
			RuntimeException e = new RuntimeException(
					"Shell not supported: " + shell);
			logger.error("Shell not supported: " + shell, e);
			throw e;
		}

		// Change shell prompt to the Amelia prompt
		this.expect.sendLine(ShellUtils.ameliaPromptFormat(shell));
		this.expect.expect(regexp(prompt));
	}

	public void executeCommand(final CommandDescriptor descriptor,
		final ScheduledTask<?> command) throws InterruptedException {
		this.taskQueue.execute(new Callable<Object>() {
			@Override public Object call() throws Exception {
				try {
					return command.call(
						host,
						ShellUtils.ameliaPromptRegexp(),
						false
					);
				} catch (Exception e) {
					logger.error(e);
					throw e;
				}
			}
		});

		if (descriptor.isExecution()) {
			this.executions.add(descriptor);
		}
	}

	public int stopExecutions(List<CommandDescriptor> executions) throws IOException {
		// FIXME: Improve the search string to identify deployed composites when
		// the classpath is different (libraries are in different order)
		String prompt = ShellUtils.ameliaPromptRegexp();
		List<String> components = new ArrayList<String>();

		// Stop executions in reverse order (to avoid abruptly stopping
		// components)
		for (int i = executions.size() - 1; i >= 0; i--) {
			CommandDescriptor descriptor = executions.remove(i);
			String command = prepareRunCommand(descriptor.toCommandString());
			String[] data = command.split(" "); // data[0]: compositeName
			this.expect.sendLine(ShellUtils.runningCompositeName(command));
			Result r = this.expect.expect(regexp(prompt));
			if (r.getBefore().contains(data[0])) {
				this.expect.sendLine(ShellUtils.killCommand(command));
				this.expect.expect(regexp(prompt));
				components.add(data[0]);
				logger.info("Execution of composite " + data[0]
						+ " was successfully stopped in " + this.host);
			}
		}
		notifyAboutStoppedExecutions(components);
		return components.size();
	}
	
	private String prepareRunCommand(String command) {
		// remove the "frascati run" part
		Pattern pattern = Pattern.compile("(frascati run) (\\-r [0-9]+ )?(.*)");
		Matcher matcher = pattern.matcher(command);
		if (matcher.find()) {
			command = matcher.group(3);
		}
		return command;
	}
	
	private void notifyAboutStoppedExecutions(List<String> components) {
		if (!components.isEmpty()) {
			int nExecutions = components.size();
			String have = nExecutions == 1 ? " has " : " have ";
			String s = nExecutions == 1 ? "" : "s";
			String message = "Component" + s + " '" 
					+ Arrays.join(components.toArray(new String[0]), "', '", "' and '")
					+ "'" + have + "been stopped";
			Log.success(this.host, message);
		}
	}

	/**
	 * Stops current executions on the task queue
	 */
	public void shutdownTaskQueue() {
		this.taskQueue.shutdown();
	}

	public void stopExecutions() throws IOException {
		stopExecutions(this.executions);
	}

	public List<CommandDescriptor> executions() {
		return this.executions;
	}

	public Expect expect() {
		return this.expect;
	}

	public boolean close() throws IOException {
		if (this.expect != null)
			this.expect.close();
		if (this.channel != null && this.channel.isConnected())
			this.channel.disconnect();
		if (this.session != null && this.session.isConnected())
			this.session.disconnect();
		return !this.isConnected();
	}

	public boolean isConnected() {
		if (this.session == null || this.channel == null)
			return false;
		return this.session.isConnected() && this.channel.isConnected();
	}

	private File createOutputFile() throws Exception {
		String date = this.dateFormat.format(new Date());
		String fileName = this.host + "-" + date + ".txt";
		File parent = new File("sessions" + File.separator + this.subsystem);
		File file = new File(parent, fileName);
		try {
			if (!parent.exists())
				parent.mkdirs();
			file.createNewFile();
		} catch (IOException e) {
			throw new Exception(
					"The application does not have permission to create files", e);
		}
		return file;
	}

	public Host host() {
		return this.host;
	}

	/**
	 * @return a runtime representation of the shell channel
	 */
	public OutputLog outputLog() {
		return this.outputLog;
	}

}
