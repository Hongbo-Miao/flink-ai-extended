/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.flink.ml.cluster.rpc;

import com.alibaba.flink.ml.cluster.node.MLContext;
import com.alibaba.flink.ml.cluster.node.runner.MLRunner;
import com.alibaba.flink.ml.cluster.node.runner.ExecutionStatus;
import com.alibaba.flink.ml.cluster.node.runner.MLRunnerFactory;
import com.alibaba.flink.ml.util.*;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * machine learning cluster node server.
 *      register to application master, make up machine learning cluster.
 */
public class NodeServer implements Runnable {
	private static final Logger LOG = LoggerFactory.getLogger(NodeServer.class);
	private Server server;
	private String jobName;
	private volatile MLContext mlContext;
	private MLRunner runner;
	protected final ExecutorService runnerService;
	//the flag set by GRPC Server to notify this thread the command from AM
	private AMCommand amCommand = AMCommand.NOPE;
	private long idleStart = Long.MAX_VALUE;
	private final long idleTimeout;

	public enum AMCommand {
		NOPE,
		STOP,
		RESTART
	}


	public NodeServer(MLContext mlContext, String jobName) {
		this.mlContext = mlContext;
		this.jobName = jobName;
		idleTimeout = Long.valueOf(mlContext.getProperties().getOrDefault(
				MLConstants.NODE_IDLE_TIMEOUT, MLConstants.NODE_IDLE_TIMEOUT_DEFAULT));
		runnerService = Executors.newFixedThreadPool(1, r -> {
			Thread runnerThread = new Thread(r);
			runnerThread.setDaemon(true);
			runnerThread.setName("runner_" + mlContext.getIdentity());
			// r.setUncaughtExceptionHandler(new TFRunnerExceptionHandler());
			return runnerThread;
		});
	}

	/**
	 * @return machine learning node server port getter.
	 */
	public int getPort() {
		return server.getPort();
	}

	/**
	 * create startup script to start machine learning process.
	 * @param mlContext current node runtime context.
	 */
	public static synchronized void prepareStartupScript(MLContext mlContext) {
		String workDir = mlContext.getWorkDir().getAbsolutePath();
		LOG.info("work dir:" + workDir);
		// create startup.py
		File startupScript = new File(workDir + "/" + MLConstants.STARTUP_SCRIPT);
		if (startupScript.exists()) {
			startupScript.delete();
		}
		if (!startupScript.exists()) {
			LOG.info("create startup.py");
			try {
				URL url = NodeServer.class.getClassLoader().getResource(MLConstants.STARTUP_SCRIPT);
				Preconditions.checkNotNull(url, "Cannot find startup.py in classpath");
				File tmpStartupScript = new File(workDir + "/" + "tmp_" + MLConstants.STARTUP_SCRIPT);
				FileUtils.copyURLToFile(url, tmpStartupScript);
				tmpStartupScript.renameTo(startupScript);
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
		mlContext.getProperties().put(MLConstants.STARTUP_SCRIPT_FILE, startupScript.getAbsolutePath());
	}

	/**
	 * create python virtual environment and user code.
	 * @param mlContext current node runtime context.
	 */
	public static synchronized void prepareRuntimeEnv(MLContext mlContext) {
		prepareStartupScript(mlContext);
		String workDir = mlContext.getWorkDir().getAbsolutePath();
		// create virtual env
		try {
			PythonUtil.setupVirtualEnv(mlContext);
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		if (mlContext.useDistributeCache()) {
			LOG.info("use distribute cache");
		} else {
			// create user code
			LOG.info("use user code.zip");
			String codeFile = mlContext.getProperties().getOrDefault(MLConstants.REMOTE_CODE_ZIP_FILE, "");
			LOG.info("code file:" + codeFile);
			File targetDir = new File(workDir + "/code");
			LOG.info("target dir:" + targetDir.getAbsolutePath());
			if (!codeFile.isEmpty()) {
				String codeFileName = FileUtil.parseFileName(codeFile);
				LOG.info("codeFileName:" + codeFileName);
				String codeDirName = FileUtil.getFileNameWithoutExtension(codeFileName);
				if (mlContext.getProperties().containsKey(MLConstants.CODE_DIR_NAME)) {
					codeDirName = mlContext.getProperties().get(MLConstants.CODE_DIR_NAME);
				}
				LOG.info("codeDirName:" + codeDirName);
				targetDir = new File(workDir + "/" + codeDirName);
				mlContext.getProperties().put(MLConstants.CODE_DIR, targetDir.getAbsolutePath());
				if (!targetDir.exists()) {
					LOG.info("real targetDir:" + targetDir.getAbsolutePath());
					synchronized (PythonUtil.class) {
						if (!targetDir.exists()) {
							LOG.info("download file to local:" + codeFile);
							try {
								FileUtil.downLoadZipToLocal(workDir, codeFile, codeDirName);
							} catch (IOException e) {
								LOG.error("Fail to download zip {} to local {}", codeFile, workDir);
								throw new RuntimeException(e);
							}
						} else {
							LOG.info("target dir exists!");
						}
					}
				} else {
					LOG.info("target dir already exists!");
				}
			}
			if (mlContext.getProperties().containsKey(MLConstants.PYTHON_SCRIPT_DIR)) {
				File pythonDir = new File(workDir + "/"
						+ mlContext.getProperties().get(MLConstants.PYTHON_SCRIPT_DIR));
				mlContext.setPythonDir(pythonDir.toPath());
			} else {
				mlContext.setPythonDir(targetDir.toPath());
			}
			String[] pyFile = { mlContext.getProperties().get(MLConstants.USER_ENTRY_PYTHON_FILE) };
			mlContext.setPythonFiles(pyFile);
			if (mlContext.startWithStartup()) {
				LOG.info("Running {} via {}", mlContext.getScript().getName(),
						mlContext.getProperties().get(MLConstants.STARTUP_SCRIPT_FILE));
			} else {
				//start without startup.pyN
				LOG.info("Running {} ", mlContext.getScript().getAbsolutePath());
			}
		}
	}

	/**
	 * start node server then start machine learning runner.
	 */
	@Override
	public void run() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// Use stderr here since the LOG may has been reset by its JVM shutdown hook.
			if (NodeServer.this.runner != null) {
				LOG.warn("*** shutting down gRPC server since JVM is shutting down");
				NodeServer.this.cleanup(null);
			}
		}));

		// prepare python environment
		prepareRuntimeEnv(mlContext);
		Future runnerFuture = null;
		try {
			//1. start GRPC server
			this.server = ServerBuilder.forPort(0)
					.addService(new NodeServiceImpl(this, this.mlContext)).build();
			this.server.start();
			LOG.info("node (" + getDisplayName() + ") server started, listening on " + server.getPort());

			//2. start Node
			runnerFuture = startMLRunner();

			boolean exit = false;
			//exit the loop for following conditions:
			// successfully executed the python script
			// AM asks to stop
			// idle for certain amount of time
			while (!exit && runner.getResultStatus() != ExecutionStatus.SUCCEED) {
				if (runnerFuture.isDone()) {
					if (idleStart == Long.MAX_VALUE) {
						idleStart = System.currentTimeMillis();
					}
					long duration = System.currentTimeMillis() - idleStart;
					if (duration > idleTimeout) {
						throw new MLException(String.format(
								"%s has been idle for %d seconds", mlContext.getIdentity(), duration / 1000));
					}
					Thread.sleep(1000);
				} else {
					idleStart = Long.MAX_VALUE;
					runnerService.awaitTermination(10, TimeUnit.SECONDS);
				}
				switch (getAmCommand()) {
					case STOP:
						stopMLRunner(runnerFuture);
						setAmCommand(AMCommand.NOPE);
						exit = true;
						break;
					case RESTART:
						stopMLRunner(runnerFuture);
						runnerFuture = startMLRunner();
						setAmCommand(AMCommand.NOPE);
						break;
				}
			}
		} catch (InterruptedException e) {
			LOG.error(mlContext.getIdentity() + " node server interrupted");
		} catch (Exception e) {
			LOG.error("Error to run node service {}.", e.getMessage());
			throw new RuntimeException(e);
		} finally {
			cleanup(runnerFuture);
		}
	}

	public String getDisplayName() {
		return jobName + ":" + mlContext.getIndex();
	}


	private Future startMLRunner() throws Exception {
		LOG.info("begin start node:" + mlContext.getIdentity());
		runner = MLRunnerFactory.createMLRunner(mlContext, this);
		Future future = runnerService.submit(runner);
		LOG.info("end start node:" + mlContext.getIdentity());
		return future;
	}

	private void stopMLRunner(Future runnerFuture) {
		if (null != runnerService && (!runnerService.isShutdown())) {
			LOG.info("begin stop node:" + mlContext.getIdentity());
			try {
				runner.notifyStop();
				if(null != runnerFuture) {
					runnerFuture.cancel(true);
				}
				runnerService.awaitTermination(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOG.warn("Interrupted waiting for scriptRunner thread to finish, dumping its stack trace:" + e.getMessage());
			}
			LOG.info("end stop node:" + mlContext.getIdentity());
		}
	}

	/** Stop serving requests and shutdown resources. */
	private synchronized void cleanup(Future runnerFuture) {
		LOG.info("{} run cleanup!", mlContext.getIdentity());
		stopMLRunner(runnerFuture);
		runnerService.shutdownNow();
		try {
			runnerService.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
			LOG.warn("runner service thread poll shutdown interrupted:" + e.getMessage());
		}

		if (server != null) {
			LOG.info(getDisplayName() + " shut down");
			server.shutdownNow();
			try {
				if (!server.awaitTermination(2, TimeUnit.MINUTES)) {
					LOG.warn("{} timed out waiting for GRPC server to terminate");
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
				LOG.info("{} interrupted shutting down GRPC server", mlContext.getIdentity());
			}
			server = null;
		}

		if (mlContext != null && mlContext.getInputQueue() != null) {
			LOG.info("{} mark input queue finished.", mlContext.getIdentity());
			mlContext.getInputQueue().markFinished();
		}

		runner = null;
	}

	public synchronized void setAmCommand(AMCommand cmd) {
		this.amCommand = cmd;
	}

	public synchronized AMCommand getAmCommand() {
		return amCommand;
	}

	@VisibleForTesting
	MLRunner getRunner() {
		return runner;
	}

}
