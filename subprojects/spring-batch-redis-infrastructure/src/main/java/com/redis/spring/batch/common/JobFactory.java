package com.redis.spring.batch.common;

import org.hsqldb.jdbc.JDBCDataSource;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.batch.BatchDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.batch.BatchProperties.Jdbc;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

public class JobFactory implements JobLauncher, InitializingBean {

	private JobRepository jobRepository;
	private PlatformTransactionManager transactionManager;
	private JobLauncher jobLauncher;
	private JobLauncher asyncJobLauncher;
	private String name = ClassUtils.getShortName(getClass());

	@Override
	public void afterPropertiesSet() throws Exception {
		if (transactionManager == null) {
			transactionManager = resourcelessTransactionManager();
		}
		if (jobRepository == null) {
			jobRepository = jobRepository();
			if (jobRepository == null) {
				throw new IllegalStateException("Could not initialize job repository");
			}
		}
		if (jobLauncher == null) {
			jobLauncher = jobLauncher(new SyncTaskExecutor());
			asyncJobLauncher = jobLauncher(new SimpleAsyncTaskExecutor());
		}
	}

	public static ResourcelessTransactionManager resourcelessTransactionManager() {
		return new ResourcelessTransactionManager();
	}

	private JobRepository jobRepository() throws Exception {
		JobRepositoryFactoryBean bean = new JobRepositoryFactoryBean();
		bean.setDataSource(dataSource(name));
		bean.setDatabaseType("HSQL");
		bean.setTransactionManager(transactionManager);
		bean.afterPropertiesSet();
		return bean.getObject();
	}

	private static JDBCDataSource dataSource(String name) throws Exception {
		JDBCDataSource source = new JDBCDataSource();
		source.setURL("jdbc:hsqldb:mem:" + name);
		Jdbc jdbc = new Jdbc();
		jdbc.setInitializeSchema(DatabaseInitializationMode.ALWAYS);
		DataSourceScriptDatabaseInitializer initializer = new BatchDataSourceScriptDatabaseInitializer(source, jdbc);
		initializer.afterPropertiesSet();
		initializer.initializeDatabase();
		return source;
	}

	private JobLauncher jobLauncher(TaskExecutor taskExecutor) {
		TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
		launcher.setJobRepository(jobRepository);
		launcher.setTaskExecutor(taskExecutor);
		return launcher;
	}

	public <I, O> SimpleStepBuilder<I, O> step(String name, int chunkSize) {
		return stepBuilder(name).chunk(chunkSize, transactionManager);
	}

	public StepBuilder stepBuilder(String name) {
		return new StepBuilder(name, jobRepository);
	}

	public JobExecution run(Job job) throws JobExecutionAlreadyRunningException, JobRestartException,
			JobInstanceAlreadyCompleteException, JobParametersInvalidException {
		return run(job, new JobParameters());
	}

	public JobExecution runAsync(Job job) throws JobExecutionAlreadyRunningException, JobRestartException,
			JobInstanceAlreadyCompleteException, JobParametersInvalidException {
		return runAsync(job, new JobParameters());
	}

	@Override
	public JobExecution run(Job job, JobParameters jobParameters) throws JobExecutionAlreadyRunningException,
			JobRestartException, JobInstanceAlreadyCompleteException, JobParametersInvalidException {
		return jobLauncher.run(job, jobParameters);
	}

	public JobExecution runAsync(Job job, JobParameters jobParameters) throws JobExecutionAlreadyRunningException,
			JobRestartException, JobInstanceAlreadyCompleteException, JobParametersInvalidException {
		return asyncJobLauncher.run(job, jobParameters);
	}

	public static JobExecution checkJobExecution(JobExecution jobExecution) throws JobExecutionException {
		if (jobExecution.getExitStatus().getExitCode().equals(ExitStatus.FAILED.getExitCode())) {
			for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
				ExitStatus exitStatus = stepExecution.getExitStatus();
				if (exitStatus.getExitCode().equals(ExitStatus.FAILED.getExitCode())) {
					String message = String.format("Error executing step %s in job %s: %s", stepExecution.getStepName(),
							jobExecution.getJobInstance().getJobName(), exitStatus.getExitDescription());
					if (stepExecution.getFailureExceptions().isEmpty()) {
						throw new JobExecutionException(message);
					}
					throw new JobExecutionException(message, stepExecution.getFailureExceptions().get(0));
				}
			}
			if (jobExecution.getAllFailureExceptions().isEmpty()) {
				throw new JobExecutionException(String.format("Error executing job %s: %s",
						jobExecution.getJobInstance().getJobName(), jobExecution.getExitStatus().getExitDescription()));
			}
		}
		return jobExecution;
	}

	public static ThreadPoolTaskExecutor threadPoolTaskExecutor(int threads) {
		ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
		taskExecutor.setMaxPoolSize(threads);
		taskExecutor.setCorePoolSize(threads);
		taskExecutor.setQueueCapacity(threads);
		taskExecutor.initialize();
		return taskExecutor;
	}

	public void setJobRepository(JobRepository jobRepository) {
		this.jobRepository = jobRepository;
	}

	public void setTransactionManager(PlatformTransactionManager platformTransactionManager) {
		this.transactionManager = platformTransactionManager;
	}

	public void setJobLauncher(JobLauncher jobLauncher) {
		this.jobLauncher = jobLauncher;
	}

	public void setName(String name) {
		this.name = name;
	}

	public JobBuilder job(String name) {
		return new JobBuilder(name, jobRepository);
	}

}
