package com.redis.spring.batch.common;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.awaitility.Awaitility;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.Assert;

public class JobRunner {

	public static final Duration DEFAULT_RUNNING_TIMEOUT = Duration.ofSeconds(5);
	public static final Duration DEFAULT_TERMINATION_TIMEOUT = Duration.ofSeconds(5);

	private Duration runningTimeout = DEFAULT_RUNNING_TIMEOUT;
	private Duration terminationTimeout = DEFAULT_TERMINATION_TIMEOUT;
	private final JobRepository jobRepository;
	private final PlatformTransactionManager transactionManager;
	private final SimpleJobLauncher jobLauncher;
	private final SimpleJobLauncher asyncJobLauncher;

	public JobRunner(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		Assert.notNull(jobRepository, "A job repository is required");
		Assert.notNull(transactionManager, "A transaction manager is required");
		this.jobRepository = jobRepository;
		this.transactionManager = transactionManager;
		this.jobLauncher = launcher(new SyncTaskExecutor());
		this.asyncJobLauncher = launcher(new SimpleAsyncTaskExecutor());
	}

	public void setRunningTimeout(Duration runningTimeout) {
		Utils.assertPositive(runningTimeout, "Running timeout");
		this.runningTimeout = runningTimeout;
	}

	public void setTerminationTimeout(Duration terminationTimeout) {
		Utils.assertPositive(terminationTimeout, "Termination timeout");
		this.terminationTimeout = terminationTimeout;
	}

	public JobRepository getJobRepository() {
		return jobRepository;
	}

	public PlatformTransactionManager getTransactionManager() {
		return transactionManager;
	}

	private SimpleJobLauncher launcher(TaskExecutor taskExecutor) {
		SimpleJobLauncher launcher = new SimpleJobLauncher();
		launcher.setJobRepository(jobRepository);
		launcher.setTaskExecutor(taskExecutor);
		return launcher;
	}

	public JobBuilder job(String name) {
		return new JobBuilder(name).repository(jobRepository);
	}

	public StepBuilder step(String name) {
		return new StepBuilder(name).repository(jobRepository).transactionManager(transactionManager);
	}

	public JobExecution run(Job job) throws JobExecutionException {
		JobExecution execution = jobLauncher.run(job, new JobParameters());
		awaitTermination(execution);
		return execution;
	}

	public static void awaitTermination(JobExecution execution, Duration timeout) {
		if (execution == null) {
			return;
		}
		Awaitility.await().timeout(timeout).until(() -> isTerminated(execution));
	}

	public static boolean isTerminated(JobExecution execution) {
		return execution.getStatus() == BatchStatus.COMPLETED
				|| execution.getStatus().isGreaterThan(BatchStatus.STOPPED);
	}

	public void awaitTermination(JobExecution execution) {
		awaitTermination(execution, terminationTimeout);
	}

	public void awaitRunning(Callable<Boolean> callable) {
		Awaitility.await().timeout(runningTimeout).until(callable);
	}

	public void awaitRunning(JobExecution execution) {
		awaitRunning(execution, runningTimeout);
	}

	public static void awaitRunning(JobExecution execution, Duration timeout) {
		if (execution == null) {
			return;
		}
		Awaitility.await().timeout(timeout).until(() -> execution.getStatus() != BatchStatus.STARTING);
	}

	public JobExecution runAsync(Job job) throws JobExecutionException {
		JobExecution execution = asyncJobLauncher.run(job, new JobParameters());
		awaitRunning(execution);
		return execution;
	}

	public static JobRunner inMemory() throws Exception {
		@SuppressWarnings("deprecation")
		org.springframework.batch.core.repository.support.MapJobRepositoryFactoryBean bean = new org.springframework.batch.core.repository.support.MapJobRepositoryFactoryBean();
		bean.afterPropertiesSet();
		return new JobRunner(bean.getObject(), bean.getTransactionManager());
	}

}