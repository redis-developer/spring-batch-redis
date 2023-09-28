package com.redis.spring.batch;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.AbstractJobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemStreamItemReader;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.lang.NonNull;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

import com.redis.spring.batch.common.DataType;
import com.redis.spring.batch.common.Operation;
import com.redis.spring.batch.common.SimpleOperationExecutor;
import com.redis.spring.batch.reader.DumpItemReader;
import com.redis.spring.batch.reader.KeyTypeItemReader;
import com.redis.spring.batch.reader.KeyspaceNotificationItemReader;
import com.redis.spring.batch.reader.KeyspaceNotificationItemReader.OrderingStrategy;
import com.redis.spring.batch.reader.PollableItemReader;
import com.redis.spring.batch.reader.ProcessingItemWriter;
import com.redis.spring.batch.reader.ScanKeyItemReader;
import com.redis.spring.batch.reader.StructItemReader;
import com.redis.spring.batch.step.FlushingChunkProvider;
import com.redis.spring.batch.step.FlushingStepBuilder;
import com.redis.spring.batch.util.BatchUtils;
import com.redis.spring.batch.writer.BlockingQueueItemWriter;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.Metrics;

public abstract class RedisItemReader<K, V, T> extends AbstractItemStreamItemReader<T> implements PollableItemReader<T> {

    public enum ReaderMode {
        SCAN, LIVE
    }

    public static final String QUEUE_METER = "redis.batch.reader.queue.size";

    public static final int DEFAULT_QUEUE_CAPACITY = 10000;

    public static final int DEFAULT_THREADS = 1;

    public static final int DEFAULT_CHUNK_SIZE = 50;

    public static final int DEFAULT_POOL_SIZE = GenericObjectPoolConfig.DEFAULT_MAX_TOTAL;

    public static final Duration DEFAULT_FLUSH_INTERVAL = FlushingChunkProvider.DEFAULT_FLUSH_INTERVAL;

    public static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofMillis(10);

    public static final OrderingStrategy DEFAULT_ORDERING = OrderingStrategy.PRIORITY;

    public static final int DEFAULT_NOTIFICATION_QUEUE_CAPACITY = 10000;

    public static final ReaderMode DEFAULT_MODE = ReaderMode.SCAN;

    private final Log log = LogFactory.getLog(getClass());

    protected final AbstractRedisClient client;

    protected final RedisCodec<K, V> codec;

    private ReaderMode mode = DEFAULT_MODE;

    private int database;

    private OrderingStrategy orderingStrategy = DEFAULT_ORDERING;

    private int notificationQueueCapacity = DEFAULT_NOTIFICATION_QUEUE_CAPACITY;

    private Duration flushInterval = DEFAULT_FLUSH_INTERVAL;

    private Duration idleTimeout;

    private long scanCount;

    private ItemProcessor<K, K> keyProcessor;

    private ReadFrom readFrom;

    private int threads = DEFAULT_THREADS;

    private int chunkSize = DEFAULT_CHUNK_SIZE;

    private int poolSize = DEFAULT_POOL_SIZE;

    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

    private String keyPattern;

    private DataType keyType;

    private Duration pollTimeout = DEFAULT_POLL_TIMEOUT;

    private JobRepository jobRepository;

    private PlatformTransactionManager transactionManager;

    private String name;

    private JobExecution jobExecution;

    private ItemReader<K> keyReader;

    private BlockingQueue<T> queue;

    protected RedisItemReader(AbstractRedisClient client, RedisCodec<K, V> codec) {
        setName(ClassUtils.getShortName(getClass()));
        this.client = client;
        this.codec = codec;
    }

    public AbstractRedisClient getClient() {
        return client;
    }

    public RedisCodec<K, V> getCodec() {
        return codec;
    }

    public ReaderMode getMode() {
        return mode;
    }

    public void setScanCount(long count) {
        this.scanCount = count;
    }

    public void setJobRepository(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setKeyProcessor(ItemProcessor<K, K> processor) {
        this.keyProcessor = processor;
    }

    public void setThreads(int threads) {
        this.threads = threads;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public void setMode(ReaderMode mode) {
        this.mode = mode;
    }

    public void setReadFrom(ReadFrom readFrom) {
        this.readFrom = readFrom;
    }

    public void setKeyPattern(String globPattern) {
        this.keyPattern = globPattern;
    }

    public void setKeyType(DataType type) {
        this.keyType = type;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public void setFlushInterval(Duration interval) {
        this.flushInterval = interval;
    }

    public void setNotificationQueueCapacity(int notificationQueueCapacity) {
        this.notificationQueueCapacity = notificationQueueCapacity;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public void setOrderingStrategy(OrderingStrategy orderingStrategy) {
        this.orderingStrategy = orderingStrategy;
    }

    @Override
    public void setName(String name) {
        super.setName(name);
        this.name = name;
    }

    public ItemReader<K> getKeyReader() {
        return keyReader;
    }

    public boolean isOpen() {
        return jobExecution != null && jobExecution.isRunning() && BatchUtils.isOpen(keyReader);
    }

    @Override
    public synchronized void open(ExecutionContext executionContext) {
        super.open(executionContext);
        if (jobExecution == null) {
            log.debug("Opening");
            initializeJobRepository();
            JobBuilderFactory jobBuilderFactory = new JobBuilderFactory(jobRepository);
            Job job = jobBuilderFactory.get(name).start(step().build()).build();
            SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
            jobLauncher.setJobRepository(jobRepository);
            jobLauncher.setTaskExecutor(new SimpleAsyncTaskExecutor());
            try {
                jobExecution = jobLauncher.run(job, new JobParameters());
            } catch (JobExecutionException e) {
                throw new ItemStreamException("Job execution failed", e);
            }
            log.debug("Open");
        }
    }

    @Override
    public synchronized T read() throws Exception {
        T item;
        do {
            item = poll(pollTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } while (item == null && jobExecution != null && jobExecution.isRunning());
        return item;
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    private @NonNull JobRepository initializeJobRepository() {
        if (jobRepository == null || transactionManager == null) {
            @SuppressWarnings("deprecation")
            AbstractJobRepositoryFactoryBean bean = new org.springframework.batch.core.repository.support.MapJobRepositoryFactoryBean();
            try {
                jobRepository = bean.getObject();
                transactionManager = bean.getTransactionManager();
            } catch (Exception e) {
                throw new ItemStreamException("Could not initialize job repository");
            }
        }
        return jobRepository;
    }

    public SimpleOperationExecutor<K, V, K, T> operationExecutor() {
        Operation<K, V, K, T> operation = operation();
        SimpleOperationExecutor<K, V, K, T> executor = new SimpleOperationExecutor<>(client, codec, operation);
        executor.setPoolSize(poolSize);
        executor.setReadFrom(readFrom);
        return executor;
    }

    protected abstract Operation<K, V, K, T> operation();

    private SimpleStepBuilder<K, K> step() {
        SimpleStepBuilder<K, K> step = new StepBuilder(name).chunk(chunkSize);
        step.repository(jobRepository);
        step.transactionManager(transactionManager);
        keyReader = keyReader();
        step.reader(keyReader);
        step.processor(keyProcessor);
        queue = new LinkedBlockingQueue<>(queueCapacity);
        Metrics.globalRegistry.gaugeCollectionSize(QUEUE_METER, Collections.emptyList(), queue);
        BlockingQueueItemWriter<T> queueWriter = new BlockingQueueItemWriter<>(queue);
        ProcessingItemWriter<K, T> writer = new ProcessingItemWriter<>(operationExecutor(), queueWriter);
        step.writer(writer);
        if (threads > 1) {
            step.taskExecutor(BatchUtils.threadPoolTaskExecutor(threads));
            step.throttleLimit(threads);
        }
        if (isLive()) {
            FlushingStepBuilder<K, K> flushingStep = new FlushingStepBuilder<>(step);
            flushingStep.interval(flushInterval);
            flushingStep.idleTimeout(idleTimeout);
            return flushingStep;
        }
        return step;
    }

    private ItemReader<K> keyReader() {
        if (isLive()) {
            return keyspaceNotificationReader();
        }
        return scanKeyReader();
    }

    private KeyspaceNotificationItemReader<K> keyspaceNotificationReader() {
        KeyspaceNotificationItemReader<K> reader = new KeyspaceNotificationItemReader<>(client, codec);
        reader.setDatabase(database);
        reader.setKeyPattern(keyPattern);
        reader.setKeyType(keyType);
        reader.setOrderingStrategy(orderingStrategy);
        reader.setQueueCapacity(notificationQueueCapacity);
        reader.setPollTimeout(pollTimeout);
        return reader;
    }

    public ScanKeyItemReader<K> scanKeyReader() {
        ScanKeyItemReader<K> reader = new ScanKeyItemReader<>(client, codec);
        reader.setReadFrom(readFrom);
        reader.setLimit(scanCount);
        reader.setMatch(keyPattern);
        reader.setType(keyType == null ? null : keyType.getString());
        return reader;
    }

    private boolean isLive() {
        return mode == ReaderMode.LIVE;
    }

    @Override
    public synchronized void close() {
        super.close();
        if (jobExecution != null) {
            log.debug("Closing");
            if (jobExecution.isRunning()) {
                log.debug("Stopping step executions");
                for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
                    stepExecution.setTerminateOnly();
                }
            }
            jobExecution = null;
        }
    }

    public static DumpItemReader<String, String> dump(AbstractRedisClient client) {
        return dump(client, StringCodec.UTF8);
    }

    public static <K, V> DumpItemReader<K, V> dump(AbstractRedisClient client, RedisCodec<K, V> codec) {
        return new DumpItemReader<>(client, codec);
    }

    public static StructItemReader<String, String> struct(AbstractRedisClient client) {
        return struct(client, StringCodec.UTF8);
    }

    public static <K, V> StructItemReader<K, V> struct(AbstractRedisClient client, RedisCodec<K, V> codec) {
        return new StructItemReader<>(client, codec);
    }

    public static KeyTypeItemReader<String, String> keyType(AbstractRedisClient client) {
        return keyType(client, StringCodec.UTF8);
    }

    public static <K, V> KeyTypeItemReader<K, V> keyType(AbstractRedisClient client, RedisCodec<K, V> codec) {
        return new KeyTypeItemReader<>(client, codec);
    }

}
