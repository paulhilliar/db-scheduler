/**
 * Copyright (C) Gustav Karlsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.kagkarlsson.scheduler;

import com.github.kagkarlsson.scheduler.SchedulerState.SettableSchedulerState;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.github.kagkarlsson.scheduler.ExecutorUtils.defaultThreadFactoryWithPrefix;

public class Scheduler implements SchedulerClient {

	public static final double TRIGGER_NEXT_BATCH_WHEN_AVAILABLE_THREADS_RATIO = 0.5;
	public static final String THREAD_PREFIX = "db-scheduler";
	public static final Duration SHUTDOWN_WAIT = Duration.ofMinutes(30);
	private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);
	private final SchedulerClient delegate;
	private final Clock clock;
	private final TaskRepository taskRepository;
	private final TaskResolver taskResolver;
    private int threadpoolSize;
    private final ExecutorService executorService;
	private final Waiter executeDueWaiter;
	protected final List<OnStartup> onStartup;
	private final Waiter detectDeadWaiter;
	private final Duration heartbeatInterval;
	private final StatsRegistry statsRegistry;
	private final int pollingLimit;
	private final ExecutorService dueExecutor;
	private final ExecutorService detectDeadExecutor;
	private final ExecutorService updateHeartbeatExecutor;
	private final Map<Execution, CurrentlyExecuting> currentlyProcessing = Collections.synchronizedMap(new HashMap<>());
	private final Waiter heartbeatWaiter;
	private final SettableSchedulerState schedulerState = new SettableSchedulerState();
	private int currentGenerationNumber = 1;

	protected Scheduler(Clock clock, TaskRepository taskRepository, TaskResolver taskResolver, int threadpoolSize, ExecutorService executorService, SchedulerName schedulerName,
			  Waiter executeDueWaiter, Duration heartbeatInterval, boolean enableImmediateExecution, StatsRegistry statsRegistry, int pollingLimit, List<OnStartup> onStartup) {
		this.clock = clock;
		this.taskRepository = taskRepository;
		this.taskResolver = taskResolver;
        this.threadpoolSize = threadpoolSize;
        this.executorService = executorService;
		this.executeDueWaiter = executeDueWaiter;
		this.onStartup = onStartup;
		this.detectDeadWaiter = new Waiter(heartbeatInterval.multipliedBy(2), clock);
		this.heartbeatInterval = heartbeatInterval;
		this.heartbeatWaiter = new Waiter(heartbeatInterval, clock);
		this.statsRegistry = statsRegistry;
		this.pollingLimit = pollingLimit;
		this.dueExecutor = Executors.newSingleThreadExecutor(defaultThreadFactoryWithPrefix(THREAD_PREFIX + "-execute-due-"));
		this.detectDeadExecutor = Executors.newSingleThreadExecutor(defaultThreadFactoryWithPrefix(THREAD_PREFIX + "-detect-dead-"));
		this.updateHeartbeatExecutor = Executors.newSingleThreadExecutor(defaultThreadFactoryWithPrefix(THREAD_PREFIX + "-update-heartbeat-"));
		SchedulerClientEventListener earlyExecutionListener = (enableImmediateExecution ? new TriggerCheckForDueExecutions(schedulerState, clock, executeDueWaiter) : SchedulerClientEventListener.NOOP);
		delegate = new StandardSchedulerClient(taskRepository, earlyExecutionListener);
	}

	public void start() {
		LOG.info("Starting scheduler.");

		executeOnStartup();

		dueExecutor.submit(new RunUntilShutdown(this::executeDue, executeDueWaiter, schedulerState, statsRegistry));
		detectDeadExecutor.submit(new RunUntilShutdown(this::detectDeadExecutions, detectDeadWaiter, schedulerState, statsRegistry));
		updateHeartbeatExecutor.submit(new RunUntilShutdown(this::updateHeartbeats, heartbeatWaiter, schedulerState, statsRegistry));

		schedulerState.setStarted();
	}

	protected void executeOnStartup() {
		onStartup.forEach(os -> {
			try {
				os.onStartup(this, this.clock);
			} catch (Exception e) {
				LOG.error("Unexpected error while executing OnStartup tasks. Continuing.", e);
				statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
			}
		});
	}

	public void stop() {
		if (schedulerState.isShuttingDown()) {
			LOG.warn("Multiple calls to 'stop()'. Scheduler is already stopping.");
			return;
		}

		schedulerState.setIsShuttingDown();

		LOG.info("Shutting down Scheduler.");
		if (!ExecutorUtils.shutdownNowAndAwaitTermination(dueExecutor, Duration.ofSeconds(5))) {
			LOG.warn("Failed to shutdown due-executor properly.");
		}
		if (!ExecutorUtils.shutdownNowAndAwaitTermination(detectDeadExecutor, Duration.ofSeconds(5))) {
			LOG.warn("Failed to shutdown detect-dead-executor properly.");
		}
		if (!ExecutorUtils.shutdownNowAndAwaitTermination(updateHeartbeatExecutor, Duration.ofSeconds(5))) {
			LOG.warn("Failed to shutdown update-heartbeat-executor properly.");
		}

		LOG.info("Letting running executions finish. Will wait up to {}.", SHUTDOWN_WAIT);
		if (ExecutorUtils.shutdownAndAwaitTermination(executorService, SHUTDOWN_WAIT)) {
			LOG.info("Scheduler stopped.");
		} else {
			LOG.warn("Scheduler stopped, but some tasks did not complete. Was currently running the following executions:\n{}",
					new ArrayList<>(currentlyProcessing.keySet()).stream().map(Execution::toString).collect(Collectors.joining("\n")));
		}
	}

	public SchedulerState getSchedulerState() {
	    return schedulerState;
    }

	@Override
	public <T> void schedule(TaskInstance<T> taskInstance, Instant executionTime) {
		this.delegate.schedule(taskInstance, executionTime);
	}

	@Override
	public void reschedule(TaskInstanceId taskInstanceId, Instant newExecutionTime) {
		this.delegate.reschedule(taskInstanceId, newExecutionTime);
	}

	@Override
	public void cancel(TaskInstanceId taskInstanceId) {
		this.delegate.cancel(taskInstanceId);
	}

	@Override
	public void getScheduledExecutions(Consumer<ScheduledExecution<Object>> consumer) {
		this.delegate.getScheduledExecutions(consumer);
	}

	@Override
	public <T> void getScheduledExecutionsForTask(String taskName, Class<T> dataClass, Consumer<ScheduledExecution<T>> consumer) {
		this.delegate.getScheduledExecutionsForTask(taskName, dataClass, consumer);
	}

	@Override
	public Optional<ScheduledExecution<Object>> getScheduledExecution(TaskInstanceId taskInstanceId) {
		return this.delegate.getScheduledExecution(taskInstanceId);
	}

	public List<Execution> getFailingExecutions(Duration failingAtLeastFor) {
		return taskRepository.getExecutionsFailingLongerThan(failingAtLeastFor);
	}

	public boolean triggerCheckForDueExecutions() {
		return executeDueWaiter.wake();
	}

	public List<CurrentlyExecuting> getCurrentlyExecuting() {
		return new ArrayList<>(currentlyProcessing.values());
	}

	protected void executeDue() {
		Instant now = clock.now();
		List<Execution> dueExecutions = taskRepository.getDue(now, pollingLimit);
		LOG.trace("Found {} taskinstances due for execution", dueExecutions.size());

		int thisGenerationNumber = this.currentGenerationNumber + 1;
		DueExecutionsBatch newDueBatch = new DueExecutionsBatch(Scheduler.this.threadpoolSize, thisGenerationNumber, dueExecutions.size(), pollingLimit == dueExecutions.size());

		for (Execution e : dueExecutions) {
			executorService.execute(new PickAndExecute(e, newDueBatch));
		}
		this.currentGenerationNumber = thisGenerationNumber;
		statsRegistry.register(StatsRegistry.SchedulerStatsEvent.RAN_EXECUTE_DUE);
	}

	@SuppressWarnings({"rawtypes","unchecked"})
	protected void detectDeadExecutions() {
		LOG.debug("Checking for dead executions.");
		Instant now = clock.now();
		final Instant oldAgeLimit = now.minus(getMaxAgeBeforeConsideredDead());
		List<Execution> oldExecutions = taskRepository.getOldExecutions(oldAgeLimit);

		if (!oldExecutions.isEmpty()) {
			oldExecutions.forEach(execution -> {

				LOG.info("Found dead execution. Delegating handling to task. Execution: " + execution);
				try {

					Optional<Task> task = taskResolver.resolve(execution.taskInstance.getTaskName());
					if (task.isPresent()) {
						statsRegistry.register(StatsRegistry.SchedulerStatsEvent.DEAD_EXECUTION);
						task.get().getDeadExecutionHandler().deadExecution(execution, new ExecutionOperations(taskRepository, execution));
					} else {
						LOG.error("Failed to find implementation for task with name '{}' for detected dead execution. Either delete the execution from the databaser, or add an implementation for it.", execution.taskInstance.getTaskName());
					}

				} catch (Throwable e) {
					LOG.error("Failed while handling dead execution {}. Will be tried again later.", execution, e);
					statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
				}
			});
		} else {
			LOG.trace("No dead executions found.");
		}
		statsRegistry.register(StatsRegistry.SchedulerStatsEvent.RAN_DETECT_DEAD);
	}

	void updateHeartbeats() {
		if (currentlyProcessing.isEmpty()) {
			LOG.trace("No executions to update heartbeats for. Skipping.");
			return;
		}

		LOG.debug("Updating heartbeats for {} executions being processed.", currentlyProcessing.size());
		Instant now = clock.now();
		new ArrayList<>(currentlyProcessing.keySet()).forEach(execution -> {
			LOG.trace("Updating heartbeat for execution: " + execution);
			try {
				taskRepository.updateHeartbeat(execution, now);
			} catch (Throwable e) {
				LOG.error("Failed while updating heartbeat for execution {}. Will try again later.", execution, e);
				statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
			}
		});
		statsRegistry.register(StatsRegistry.SchedulerStatsEvent.RAN_UPDATE_HEARTBEATS);
	}

	private Duration getMaxAgeBeforeConsideredDead() {
		return heartbeatInterval.multipliedBy(4);
	}

	private class PickAndExecute implements Runnable {
		private Execution candidate;
		private DueExecutionsBatch addedDueExecutionsBatch;

		public PickAndExecute(Execution candidate, DueExecutionsBatch dueExecutionsBatch) {
			this.candidate = candidate;
			this.addedDueExecutionsBatch = dueExecutionsBatch;
		}

		@Override
		public void run() {
			if (schedulerState.isShuttingDown()) {
				LOG.info("Scheduler has been shutdown. Skipping fetched due execution: " + candidate.taskInstance.getTaskAndInstance());
				return;
			}

			if (addedDueExecutionsBatch.isOlderGenerationThan(currentGenerationNumber)) {
				// skipping execution due to it being stale
				addedDueExecutionsBatch.markBatchAsStale();
				statsRegistry.register(StatsRegistry.CandidateStatsEvent.STALE);
				LOG.trace("Skipping queued execution (current generationNumber: {}, execution generationNumber: {})", currentGenerationNumber, addedDueExecutionsBatch.getGenerationNumber());
				return;
			}

			final Optional<Execution> pickedExecution = taskRepository.pick(candidate, clock.now());

			if (!pickedExecution.isPresent()) {
				// someone else picked id
				LOG.debug("Execution picked by another scheduler. Continuing to next due execution.");
				statsRegistry.register(StatsRegistry.CandidateStatsEvent.ALREADY_PICKED);
				return;
			}

			currentlyProcessing.put(pickedExecution.get(), new CurrentlyExecuting(pickedExecution.get(), clock));
			try {
				statsRegistry.register(StatsRegistry.CandidateStatsEvent.EXECUTED);
				executePickedExecution(pickedExecution.get());
			} finally {
				if (currentlyProcessing.remove(pickedExecution.get()) == null) {
					LOG.error("Released execution was not found in collection of executions currently being processed. Should never happen.");
					statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
				}
				addedDueExecutionsBatch.oneExecutionDone(() -> triggerCheckForDueExecutions());
			}
		}

		private void executePickedExecution(Execution execution) {
			final Optional<Task> task = taskResolver.resolve(execution.taskInstance.getTaskName());
			if (!task.isPresent()) {
				LOG.error("Failed to find implementation for task with name '{}'. If there are a high number of executions, this may block other executions and must be fixed.", execution.taskInstance.getTaskName());
				return;
			}

			Instant executionStarted = clock.now();
			try {
				LOG.debug("Executing " + execution);
				CompletionHandler completion = task.get().execute(execution.taskInstance, new ExecutionContext(schedulerState, execution, Scheduler.this));
				LOG.debug("Execution done");

				complete(completion, execution, executionStarted);
				statsRegistry.register(StatsRegistry.ExecutionStatsEvent.COMPLETED);

			} catch (RuntimeException unhandledException) {
				LOG.error("Unhandled exception during execution. Treating as failure.", unhandledException);
				failure(task.get().getFailureHandler(), execution, unhandledException, executionStarted);
				statsRegistry.register(StatsRegistry.ExecutionStatsEvent.FAILED);

			} catch (Throwable unhandledError) {
				LOG.error("Error during execution. Treating as failure.", unhandledError);
				failure(task.get().getFailureHandler(), execution, unhandledError, executionStarted);
				statsRegistry.register(StatsRegistry.ExecutionStatsEvent.FAILED);
			}
		}

		private void complete(CompletionHandler completion, Execution execution, Instant executionStarted) {
			ExecutionComplete completeEvent = ExecutionComplete.success(execution, executionStarted, clock.now());
			try {
				completion.complete(completeEvent, new ExecutionOperations(taskRepository, execution));
				statsRegistry.registerSingleCompletedExecution(completeEvent);
			} catch (Throwable e) {
				statsRegistry.register(StatsRegistry.SchedulerStatsEvent.COMPLETIONHANDLER_ERROR);
				statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
				LOG.error("Failed while completing execution {}. Execution will likely remain scheduled and locked/picked. " +
						"The execution should be detected as dead in {}, and handled according to the tasks DeadExecutionHandler.", execution, getMaxAgeBeforeConsideredDead(), e);
			}
		}

		private void failure(FailureHandler failureHandler, Execution execution, Throwable cause, Instant executionStarted) {
			ExecutionComplete completeEvent = ExecutionComplete.failure(execution, executionStarted, clock.now(), cause);
			try {
				failureHandler.onFailure(completeEvent, new ExecutionOperations(taskRepository, execution));
				statsRegistry.registerSingleCompletedExecution(completeEvent);
			} catch (Throwable e) {
				statsRegistry.register(StatsRegistry.SchedulerStatsEvent.FAILUREHANDLER_ERROR);
				statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
				LOG.error("Failed while completing execution {}. Execution will likely remain scheduled and locked/picked. " +
						"The execution should be detected as dead in {}, and handled according to the tasks DeadExecutionHandler.", execution, getMaxAgeBeforeConsideredDead(), e);
			}
		}

	}

	public static SchedulerBuilder create(DataSource dataSource, Task<?> ... knownTasks) {
		return create(dataSource, Arrays.asList(knownTasks));
	}

	public static SchedulerBuilder create(DataSource dataSource, List<Task<?>> knownTasks) {
		return new SchedulerBuilder(dataSource, knownTasks);
	}

}
