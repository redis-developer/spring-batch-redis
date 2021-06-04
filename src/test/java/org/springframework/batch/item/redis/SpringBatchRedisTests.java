package org.springframework.batch.item.redis;

import com.redislabs.testcontainers.RedisClusterContainer;
import com.redislabs.testcontainers.RedisContainer;
import com.redislabs.testcontainers.RedisStandaloneContainer;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import io.lettuce.core.api.sync.*;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.support.ConnectionPoolSupport;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.runner.RunWith;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.DefaultBufferedReaderFactory;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.separator.DefaultRecordSeparatorPolicy;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.redis.support.KeyValue;
import org.springframework.batch.item.redis.support.*;
import org.springframework.batch.item.redis.support.operation.Hset;
import org.springframework.batch.item.redis.support.operation.NullValuePredicate;
import org.springframework.batch.item.redis.support.operation.Xadd;
import org.springframework.batch.item.redis.support.operation.Zadd;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.item.support.ListItemWriter;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Testcontainers
@SpringBootTest(classes = BatchTestApplication.class)
@RunWith(SpringRunner.class)
@SuppressWarnings({"rawtypes", "unchecked", "unused", "BusyWait", "SingleStatementInBlock", "NullableProblems", "SameParameterValue"})
@Slf4j
public class SpringBatchRedisTests {

    @Container
    private static final RedisStandaloneContainer REDIS = new RedisStandaloneContainer().withKeyspaceNotifications();
    @Container
    private static final RedisClusterContainer REDIS_CLUSTER = new RedisClusterContainer().withKeyspaceNotifications();
    @Container
    private static final RedisStandaloneContainer REDIS_REPLICA = new RedisStandaloneContainer();

    protected static final Map<RedisContainer, AbstractRedisClient> CLIENTS = new HashMap<>();
    protected static final Map<RedisContainer, GenericObjectPool<? extends StatefulConnection<String, String>>> POOLS = new HashMap<>();
    protected static final Map<RedisContainer, StatefulConnection<String, String>> CONNECTIONS = new HashMap<>();
    protected static final Map<RedisContainer, StatefulRedisPubSubConnection<String, String>> PUBSUB_CONNECTIONS = new HashMap<>();
    protected static final Map<RedisContainer, BaseRedisAsyncCommands<String, String>> ASYNCS = new HashMap<>();
    protected static final Map<RedisContainer, BaseRedisCommands<String, String>> SYNCS = new HashMap<>();

    @BeforeAll
    public static void setup() {
        add(REDIS);
        add(REDIS_CLUSTER);
        add(REDIS_REPLICA);
    }

    private static void add(RedisContainer container) {
        if (container.isCluster()) {
            RedisClusterClient client = RedisClusterClient.create(container.getRedisURI());
            CLIENTS.put(container, client);
            StatefulRedisClusterConnection<String, String> connection = client.connect();
            CONNECTIONS.put(container, connection);
            SYNCS.put(container, connection.sync());
            ASYNCS.put(container, connection.async());
            PUBSUB_CONNECTIONS.put(container, client.connectPubSub());
            POOLS.put(container, ConnectionPoolSupport.createGenericObjectPool(client::connect, new GenericObjectPoolConfig<>()));
        } else {
            RedisClient client = RedisClient.create(container.getRedisURI());
            CLIENTS.put(container, client);
            StatefulRedisConnection<String, String> connection = client.connect();
            CONNECTIONS.put(container, connection);
            SYNCS.put(container, connection.sync());
            ASYNCS.put(container, connection.async());
            PUBSUB_CONNECTIONS.put(container, client.connectPubSub());
            POOLS.put(container, ConnectionPoolSupport.createGenericObjectPool(client::connect, new GenericObjectPoolConfig<>()));
        }
    }

    @AfterEach
    public void flushall() {
        for (BaseRedisCommands<String, String> sync : SYNCS.values()) {
            ((RedisServerCommands<String, String>) sync).flushall();
        }
    }

    @AfterAll
    public static void teardown() {
        for (StatefulConnection<String, String> connection : CONNECTIONS.values()) {
            connection.close();
        }
        for (StatefulRedisPubSubConnection<String, String> pubSubConnection : PUBSUB_CONNECTIONS.values()) {
            pubSubConnection.close();
        }
        for (GenericObjectPool<? extends StatefulConnection<String, String>> pool : POOLS.values()) {
            pool.close();
        }
        for (AbstractRedisClient client : CLIENTS.values()) {
            client.shutdown();
            client.getResources().shutdown();
        }
        SYNCS.clear();
        ASYNCS.clear();
        CONNECTIONS.clear();
        PUBSUB_CONNECTIONS.clear();
        POOLS.clear();
        CLIENTS.clear();
    }

    static Stream<RedisContainer> containers() {
        return Stream.of(REDIS, REDIS_CLUSTER);
    }

    protected static RedisClient redisClient(RedisContainer container) {
        return (RedisClient) CLIENTS.get(container);
    }

    protected static RedisClusterClient redisClusterClient(RedisContainer container) {
        return (RedisClusterClient) CLIENTS.get(container);
    }

    protected static <T> T sync(RedisContainer container) {
        return (T) SYNCS.get(container);
    }

    protected static <T> T async(RedisContainer container) {
        return (T) ASYNCS.get(container);
    }

    protected static <C extends StatefulConnection<String, String>> C connection(RedisContainer container) {
        return (C) CONNECTIONS.get(container);
    }

    protected static <C extends StatefulRedisPubSubConnection<String, String>> C pubSubConnection(RedisContainer container) {
        return (C) PUBSUB_CONNECTIONS.get(container);
    }

    protected static <C extends StatefulConnection<String, String>> GenericObjectPool<C> pool(RedisContainer container) {
        if (POOLS.containsKey(container)) {
            return (GenericObjectPool<C>) POOLS.get(container);
        }
        throw new IllegalStateException("No pool found for " + container);
    }


    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private JobLauncher asyncJobLauncher;
    @Autowired
    private JobBuilderFactory jobs;
    @Autowired
    private StepBuilderFactory steps;

    private Job job(RedisContainer redisContainer, String name, TaskletStep step) {
        return jobs.get(name(redisContainer, name) + "-job").start(step).build();
    }

    private <I, O> JobExecution execute(RedisContainer redisContainer, String name, ItemReader<? extends I> reader, ItemWriter<O> writer) throws Throwable {
        return execute(redisContainer, name, step(name, reader, writer).build());
    }

    private JobExecution execute(RedisContainer redisContainer, String name, TaskletStep step) throws Exception {
        return checkForFailure(jobLauncher.run(job(redisContainer, name, step), new JobParameters()));
    }

    private JobExecution checkForFailure(JobExecution execution) {
        if (!execution.getExitStatus().getExitCode().equals(ExitStatus.COMPLETED.getExitCode())) {
            Assertions.fail("Job not completed: " + execution.getExitStatus());
        }
        return execution;
    }

    private <I, O> JobExecution executeFlushing(RedisContainer redisContainer, String name, PollableItemReader<? extends I> reader, ItemWriter<O> writer) throws Throwable {
        TaskletStep step = flushing(step(name, reader, writer)).build();
        JobExecution execution = asyncJobLauncher.run(job(redisContainer, name, step), new JobParameters());
        awaitRunning(execution);
        Thread.sleep(200);
        return execution;
    }

    private <I, O> SimpleStepBuilder<I, O> step(String name, ItemReader<? extends I> reader, ItemWriter<O> writer) {
        return steps.get(name + "-step").<I, O>chunk(50).reader(reader).writer(writer);
    }

    private <I, O> FlushingStepBuilder<I, O> flushing(SimpleStepBuilder<I, O> step) {
        return new FlushingStepBuilder<>(step).idleTimeout(Duration.ofMillis(500));
    }

    private FlatFileItemReader<Map<String, String>> fileReader(Resource resource) throws IOException {
        FlatFileItemReaderBuilder<Map<String, String>> builder = new FlatFileItemReaderBuilder<>();
        builder.name("flat-file-reader");
        builder.resource(resource);
        builder.saveState(false);
        builder.linesToSkip(1);
        builder.fieldSetMapper(new MapFieldSetMapper());
        builder.recordSeparatorPolicy(new DefaultRecordSeparatorPolicy());
        FlatFileItemReaderBuilder.DelimitedBuilder<Map<String, String>> delimitedBuilder = builder.delimited();
        BufferedReader reader = new DefaultBufferedReaderFactory().create(resource, FlatFileItemReader.DEFAULT_CHARSET);
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(DelimitedLineTokenizer.DELIMITER_COMMA);
        String[] fieldNames = tokenizer.tokenize(reader.readLine()).getValues();
        delimitedBuilder.names(fieldNames);
        return builder.build();
    }

    @ParameterizedTest
    @MethodSource("containers")
    void testFlushingStep(RedisContainer container) throws Throwable {
        PollableItemReader<String> reader = keyEventReader(container);
        ListItemWriter<String> writer = new ListItemWriter<>();
        JobExecution execution = executeFlushing(container, "flushing", reader, writer);
        dataGenerator(container).end(3).maxExpire(Duration.ofMillis(0)).dataTypes(DataStructure.STRING, DataStructure.HASH).build().call();
        awaitJobTermination(execution);
        RedisServerCommands<String, String> commands = sync(container);
        Assertions.assertEquals(commands.dbsize(), writer.getWrittenItems().size());
    }

    private PollableItemReader<String> keyEventReader(RedisContainer container) {
        int queueCapacity = KeyValueItemReader.LiveKeyValueItemReaderBuilder.DEFAULT_QUEUE_CAPACITY;
        if (container.isCluster()) {
            RedisClusterClient client = redisClusterClient(container);
            return new RedisClusterKeyspaceNotificationItemReader(client::connectPubSub, KeyValueItemReader.LiveKeyValueItemReaderBuilder.DEFAULT_PUBSUB_PATTERN, queueCapacity);
        }
        RedisClient client = redisClient(container);
        return new RedisKeyspaceNotificationItemReader(client::connectPubSub, KeyValueItemReader.LiveKeyValueItemReaderBuilder.DEFAULT_PUBSUB_PATTERN, queueCapacity);
    }

    private void awaitJobTermination(JobExecution execution) throws Throwable {
        while (execution.isRunning()) {
            Thread.sleep(10);
        }
        checkForFailure(execution);
    }

    @ParameterizedTest
    @MethodSource("containers")
    void testKeyspaceNotificationReader(RedisContainer container) throws Throwable {
        BlockingQueue<String> queue = new LinkedBlockingDeque<>(10000);
        AbstractKeyspaceNotificationItemReader<?> reader = keyspaceNotificationReader(container, queue);
        reader.open(new ExecutionContext());
        BaseRedisAsyncCommands<String, String> async = async(container);
        async.setAutoFlushCommands(false);
        List<String> keys = new ArrayList<>();
        List<RedisFuture<?>> futures = new ArrayList<>();
        for (int index = 0; index < 4321; index++) {
            String key = "key" + index;
            futures.add(((RedisStringAsyncCommands<String, String>) async).set(key, "value"));
            if (futures.size() == 50) {
                async.flushCommands();
                LettuceFutures.awaitAll(1000, TimeUnit.MILLISECONDS, futures.toArray(new RedisFuture[0]));
                futures.clear();
            }
            keys.add(key);
        }
        async.flushCommands();
        LettuceFutures.awaitAll(1000, TimeUnit.MILLISECONDS, futures.toArray(new RedisFuture[0]));
        Thread.sleep(10);
        Assertions.assertEquals(keys.size(), queue.size());
        reader.close();
        async.setAutoFlushCommands(true);
    }

    private AbstractKeyspaceNotificationItemReader<?> keyspaceNotificationReader(RedisContainer container, BlockingQueue<String> queue) {
        String pattern = KeyValueItemReader.LiveKeyValueItemReaderBuilder.pubSubPattern(0, KeyValueItemReader.LiveKeyValueItemReaderBuilder.DEFAULT_KEY_PATTERN);
        if (container.isCluster()) {
            RedisClusterClient client = redisClusterClient(container);
            return new RedisClusterKeyspaceNotificationItemReader(client::connectPubSub, pattern, queue);
        }
        RedisClient client = redisClient(container);
        return new RedisKeyspaceNotificationItemReader(client::connectPubSub, pattern, queue);
    }


    @ParameterizedTest
    @MethodSource("containers")
    void testDataStructureReader(RedisContainer container) throws Throwable {
        populateSource("scan-reader-populate", container);
        KeyValueItemReader<DataStructure> reader = dataStructureReader(container);
        ListItemWriter<DataStructure> writer = new ListItemWriter<>();
        JobExecution execution = execute(container, "scan-reader", reader, writer);
        Assertions.assertTrue(execution.getAllFailureExceptions().isEmpty());
        RedisServerCommands<String, String> sync = sync(container);
        Assertions.assertEquals(sync.dbsize(), writer.getWrittenItems().size());
    }

    private void populateSource(String name, RedisContainer container) throws Throwable {
        FlatFileItemReader<Map<String, String>> fileReader = fileReader(new ClassPathResource("beers.csv"));
        StatefulConnection<String, String> connection = connection(container);
        ItemWriter<Map<String, String>> hsetWriter = items -> {
            BaseRedisAsyncCommands<String, String> async = async(container);
            async.setAutoFlushCommands(false);
            List<RedisFuture<?>> futures = new ArrayList<>();
            for (Map<String, String> item : items) {
                futures.add(((RedisHashAsyncCommands<String, String>) async).hset(item.get(Beers.FIELD_ID), item));
            }
            async.flushCommands();
            LettuceFutures.awaitAll(RedisURI.DEFAULT_TIMEOUT_DURATION, futures.toArray(new RedisFuture[0]));
            async.setAutoFlushCommands(true);
        };
        execute(container, name, fileReader, hsetWriter);
    }

    @ParameterizedTest
    @MethodSource("containers")
    void testStreamReader(RedisContainer container) throws Throwable {
        dataGenerator(container).dataTypes(DataStructure.STREAM).end(100).build().call();
        StreamItemReader reader = streamReader(container, XReadArgs.StreamOffset.from("stream:0", "0-0"));
        ListItemWriter<StreamMessage<String, String>> writer = new ListItemWriter<>();
        JobExecution execution = executeFlushing(container, "stream-reader", reader, writer);
        awaitJobTermination(execution);
        Assertions.assertEquals(10, writer.getWrittenItems().size());
        List<? extends StreamMessage<String, String>> items = writer.getWrittenItems();
        for (StreamMessage<String, String> message : items) {
            Assertions.assertTrue(message.getBody().containsKey("field1"));
            Assertions.assertTrue(message.getBody().containsKey("field2"));
        }
    }

    private StreamItemReader streamReader(RedisContainer container, XReadArgs.StreamOffset<String> offset) {
        if (container.isCluster()) {
            return StreamItemReader.client(redisClusterClient(container)).offset(offset).build();
        }
        return StreamItemReader.client(redisClient(container)).offset(offset).build();
    }

    @ParameterizedTest
    @MethodSource("containers")
    void testMultiThreadedReader(RedisContainer container) throws Throwable {
        populateSource("multithreaded-scan-reader-populate", container);
        SynchronizedItemStreamReader<DataStructure> synchronizedReader = new SynchronizedItemStreamReader<>();
        synchronizedReader.setDelegate(dataStructureReader(container));
        synchronizedReader.afterPropertiesSet();
        SynchronizedListItemWriter<DataStructure> writer = new SynchronizedListItemWriter<>();
        String name = "multithreaded-scan-reader";
        int threads = 4;
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setMaxPoolSize(threads);
        taskExecutor.setCorePoolSize(threads);
        taskExecutor.afterPropertiesSet();
        JobExecution execution = execute(container, name, step(name, synchronizedReader, writer).taskExecutor(taskExecutor).throttleLimit(threads).build());
        Assertions.assertTrue(execution.getAllFailureExceptions().isEmpty());
        RedisServerCommands<String, String> sync = sync(container);
        Assertions.assertEquals(sync.dbsize(), writer.getWrittenItems().size());
    }

    private static class SynchronizedListItemWriter<T> implements ItemWriter<T> {

        private final List<T> writtenItems = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void write(List<? extends T> items) {
            writtenItems.addAll(items);
        }

        public List<? extends T> getWrittenItems() {
            return this.writtenItems;
        }
    }


    @ParameterizedTest
    @MethodSource("containers")
    void testStreamWriter(RedisContainer redisContainer) throws Throwable {
        String stream = "stream:0";
        List<Map<String, String>> messages = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            Map<String, String> body = new HashMap<>();
            body.put("field1", "value1");
            body.put("field2", "value2");
            messages.add(body);
        }
        ListItemReader<Map<String, String>> reader = new ListItemReader<>(messages);
        OperationItemWriter<Map<String, String>> writer = operationWriter(redisContainer, new Xadd<>(stream, i -> i));
        execute(redisContainer, "stream-writer", reader, writer);
        RedisStreamCommands<String, String> sync = sync(redisContainer);
        Assertions.assertEquals(messages.size(), sync.xlen(stream));
        List<StreamMessage<String, String>> xrange = sync.xrange(stream, Range.create("-", "+"));
        for (int index = 0; index < xrange.size(); index++) {
            StreamMessage<String, String> message = xrange.get(index);
            Assertions.assertEquals(messages.get(index), message.getBody());
        }
    }

    private String name(RedisContainer container, String name) {
        if (container.isCluster()) {
            return "cluster-" + name;
        }
        return name;
    }

    private void awaitRunning(JobExecution execution) throws InterruptedException {
        while (!execution.isRunning()) {
            Thread.sleep(10);
        }
    }

    @Test
    public void testStreamTransactionWriter() throws Throwable {
        String stream = "stream:1";
        List<Map<String, String>> messages = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            Map<String, String> body = new HashMap<>();
            body.put("field1", "value1");
            body.put("field2", "value2");
            messages.add(body);
        }
        ListItemReader<Map<String, String>> reader = new ListItemReader<>(messages);
        OperationItemWriter<Map<String, String>> writer = OperationItemWriter.operation(new Xadd(stream, i -> i)).client(redisClient(REDIS)).transactional(true).build();
        execute(REDIS, "stream-tx-writer", reader, writer);
        RedisStreamCommands<String, String> sync = sync(REDIS);
        Assertions.assertEquals(messages.size(), sync.xlen(stream));
        List<StreamMessage<String, String>> xrange = sync.xrange(stream, Range.create("-", "+"));
        for (int index = 0; index < xrange.size(); index++) {
            StreamMessage<String, String> message = xrange.get(index);
            Assertions.assertEquals(messages.get(index), message.getBody());
        }
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @MethodSource("containers")
    public void testHashWriter(RedisContainer container) throws Throwable {
        List<Map<String, String>> maps = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            Map<String, String> body = new HashMap<>();
            body.put("id", String.valueOf(index));
            body.put("field1", "value1");
            body.put("field2", "value2");
            maps.add(body);
        }
        ListItemReader<Map<String, String>> reader = new ListItemReader<>(maps);
        KeyMaker<Map<String, String>> keyConverter = KeyMaker.<Map<String, String>>builder().prefix("hash").converters(h -> h.remove("id")).build();
        OperationItemWriter<Map<String, String>> writer = operationWriter(container, new Hset<>(keyConverter, m -> m));
        execute(container, "hash-writer", reader, writer);
        RedisKeyCommands<String, String> sync = sync(container);
        Assertions.assertEquals(maps.size(), sync.keys("hash:*").size());
        RedisHashCommands<String, String> hashCommands = sync(container);
        for (int index = 0; index < maps.size(); index++) {
            Map<String, String> hash = hashCommands.hgetall("hash:" + index);
            Assertions.assertEquals(maps.get(index), hash);
        }
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testHashDelWriter(RedisContainer container) throws Throwable {
        List<Map.Entry<String, Map<String, String>>> hashes = new ArrayList<>();
        RedisHashCommands<String, String> commands = sync(container);
        for (int index = 0; index < 100; index++) {
            String key = String.valueOf(index);
            Map<String, String> value = new HashMap<>();
            value.put("field1", "value1");
            commands.hset("hash:" + key, value);
            Map<String, String> body = new HashMap<>();
            body.put("field2", "value2");
            hashes.add(new AbstractMap.SimpleEntry<>(key, index < 50 ? null : body));
        }
        RedisKeyCommands<String, String> sync = sync(container);
        ListItemReader<Map.Entry<String, Map<String, String>>> reader = new ListItemReader<>(hashes);
        KeyMaker<Map.Entry<String, Map<String, String>>> keyConverter = KeyMaker.<Map.Entry<String, Map<String, String>>>builder().prefix("hash").converters(Map.Entry::getKey).build();
        OperationItemWriter<Map.Entry<String, Map<String, String>>> writer = operationWriter(container, new Hset<>(keyConverter, Map.Entry::getValue, new NullValuePredicate<>(Map.Entry::getValue)));
        execute(container, "hash-del-writer", reader, writer);
        Assertions.assertEquals(50, sync.keys("hash:*").size());
        Assertions.assertEquals(2, commands.hgetall("hash:50").size());
    }

    private <T> OperationItemWriter<T> operationWriter(RedisContainer container, OperationItemWriter.RedisOperation<T> operation) {
        if (container.isCluster()) {
            return OperationItemWriter.operation(operation).client(redisClusterClient(container)).build();
        }
        return OperationItemWriter.operation(operation).client(redisClient(container)).build();
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testSortedSetWriter(RedisContainer container) throws Throwable {
        List<ScoredValue<String>> values = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            values.add((ScoredValue<String>) ScoredValue.fromNullable(index % 10, String.valueOf(index)));
        }
        ListItemReader<ScoredValue<String>> reader = new ListItemReader<>(values);
        KeyMaker<ScoredValue<String>> keyConverter = KeyMaker.<ScoredValue<String>>builder().prefix("zset").build();
        OperationItemWriter<ScoredValue<String>> writer = operationWriter(container, new Zadd<>("zset", Value::getValue, ScoredValue::getScore));
        execute(container, "sorted-set-writer", reader, writer);
        RedisServerCommands<String, String> sync = sync(container);
        Assertions.assertEquals(1, sync.dbsize());
        Assertions.assertEquals(values.size(), ((RedisSortedSetCommands<String, String>) sync).zcard("zset"));
        List<String> range = ((RedisSortedSetCommands<String, String>) sync).zrangebyscore("zset", Range.from(Range.Boundary.including(0), Range.Boundary.including(5)));
        Assertions.assertEquals(60, range.size());
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testDataStructureWriter(RedisContainer container) throws Throwable {
        List<DataStructure> list = new ArrayList<>();
        long count = 100;
        for (int index = 0; index < count; index++) {
            DataStructure keyValue = new DataStructure();
            keyValue.setKey("hash:" + index);
            keyValue.setType(DataStructure.HASH);
            Map<String, String> map = new HashMap<>();
            map.put("field1", "value1");
            map.put("field2", "value2");
            keyValue.setValue(map);
            list.add(keyValue);
        }
        ListItemReader<DataStructure> reader = new ListItemReader<>(list);
        DataStructureItemWriter<DataStructure> writer = dataStructureWriter(container);
        execute(container, "value-writer", reader, writer);
        RedisKeyCommands<String, String> sync = sync(container);
        List<String> keys = sync.keys("hash:*");
        Assertions.assertEquals(count, keys.size());
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testLiveReader(RedisContainer container) throws Throwable {
        LiveKeyValueItemReader<KeyValue<byte[]>> reader = liveKeyDumpReader(container);
        ListItemWriter<KeyValue<byte[]>> writer = new ListItemWriter<>();
        JobExecution execution = executeFlushing(container, "live-reader", reader, writer);
        log.debug("Generating keyspace notifications");
        dataGenerator(container).end(123).maxExpire(Duration.ofMillis(0)).dataTypes(DataStructure.STRING, DataStructure.HASH).build().call();
        awaitJobTermination(execution);
        RedisServerCommands<String, String> sync = sync(container);
        Assertions.assertEquals(sync.dbsize(), writer.getWrittenItems().size());
    }

    private LiveKeyValueItemReader<KeyValue<byte[]>> liveKeyDumpReader(RedisContainer container) {
        Duration idleTimeout = Duration.ofMillis(500);
        if (container.isCluster()) {
            return KeyDumpItemReader.client(redisClusterClient(container)).live().idleTimeout(idleTimeout).build();
        }
        return KeyDumpItemReader.client(redisClient(container)).live().idleTimeout(idleTimeout).build();
    }

    private KeyValueItemReader<KeyValue<byte[]>> keyDumpReader(RedisContainer container) {
        if (container.isCluster()) {
            return KeyDumpItemReader.client(redisClusterClient(container)).build();
        }
        return KeyDumpItemReader.client(redisClient(container)).build();
    }

    private KeyValueItemReader<DataStructure> dataStructureReader(RedisContainer container) {
        if (container.isCluster()) {
            return DataStructureItemReader.client(redisClusterClient(container)).build();
        }
        return DataStructureItemReader.client(redisClient(container)).build();
    }

    private DataStructureValueReader dataStructureValueReader(RedisContainer container) {
        if (container.isCluster()) {
            return DataStructureValueReader.client(redisClusterClient(container)).build();
        }
        return DataStructureValueReader.client(redisClient(container)).build();
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testDataStructureReplication(RedisContainer container) throws Throwable {
        dataGenerator(container).end(10000).build().call();
        KeyValueItemReader<DataStructure> reader = dataStructureReader(container);
        DataStructureItemWriter<DataStructure> writer = dataStructureWriter(REDIS_REPLICA);
        execute(container, "ds-replication", reader, writer);
        compare(container, "ds-replication");
    }

    private DataGenerator.DataGeneratorBuilder dataGenerator(RedisContainer container) {
        if (container.isCluster()) {
            return DataGenerator.client(redisClusterClient(container));
        }
        return DataGenerator.client(redisClient(container));
    }

    private DataStructureItemWriter<DataStructure> dataStructureWriter(RedisContainer container) {
        if (container.isCluster()) {
            return DataStructureItemWriter.client(redisClusterClient(container)).build();
        }
        return DataStructureItemWriter.client(redisClient(container)).build();
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testReplication(RedisContainer redisContainer) throws Throwable {
        dataGenerator(redisContainer).end(10000).build().call();
        KeyValueItemReader reader = keyDumpReader(redisContainer);
        OperationItemWriter<KeyValue<byte[]>> writer = keyDumpWriter(REDIS_REPLICA);
        execute(redisContainer, "replication", reader, writer);
        compare(redisContainer, "replication");
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testLiveReplication(RedisContainer redisContainer) throws Throwable {
        dataGenerator(redisContainer).end(10000).build().call();
        KeyValueItemReader reader = keyDumpReader(redisContainer);
        reader.setName("reader");
        OperationItemWriter< KeyValue<byte[]>> writer = keyDumpWriter(REDIS_REPLICA);
        writer.setName("writer");
        TaskletStep replicationStep = step("replication", reader, writer).build();
        LiveKeyValueItemReader liveReader = liveKeyDumpReader(redisContainer);
        liveReader.setName("live-reader");
        OperationItemWriter< KeyValue< byte[]>> liveWriter = keyDumpWriter(REDIS_REPLICA);
        liveWriter.setName("live-writer");
        TaskletStep liveReplicationStep = flushing(step("live-replication", liveReader, liveWriter)).build();
        SimpleFlow replicationFlow = new FlowBuilder<SimpleFlow>("replication-flow").start(replicationStep).build();
        SimpleFlow liveReplicationFlow = new FlowBuilder<SimpleFlow>("live-replication-flow").start(liveReplicationStep).build();
        Job job = jobs.get(name(redisContainer, "live-replication-job")).start(new FlowBuilder<SimpleFlow>("live-replication-flow").split(new SimpleAsyncTaskExecutor()).add(replicationFlow, liveReplicationFlow).build()).build().build();
        JobExecution execution = asyncJobLauncher.run(job, new JobParameters());
        awaitRunning(execution);
        dataGenerator(redisContainer).end(123).build().call();
        awaitJobTermination(execution);
        compare(redisContainer, "live-replication");
    }

    private KeyDumpItemWriter keyDumpWriter(RedisContainer container) {
        if (container.isCluster()) {
            return KeyDumpItemWriter.client(redisClusterClient(container)).build();
        }
        return KeyDumpItemWriter.client(redisClient(container)).build();
    }

    private void compare(RedisContainer container, String name) throws Throwable {
        RedisServerCommands<String, String> sourceSync = sync(container);
        RedisServerCommands<String, String> targetSync = sync(REDIS_REPLICA);
        Assertions.assertEquals(sourceSync.dbsize(), targetSync.dbsize());
        KeyValueItemReader left = dataStructureReader(container);
        DataStructureValueReader right = dataStructureValueReader(REDIS_REPLICA);
        KeyComparisonResultCounter counter = new KeyComparisonResultCounter();
        KeyComparisonItemWriter writer = KeyComparisonItemWriter.valueReader(right).resultHandler(counter).ttlTolerance(Duration.ofMillis(500)).build();
        execute(container, name + "-compare", left, writer);
        Assertions.assertEquals(sourceSync.dbsize(), counter.get(KeyComparisonItemWriter.Result.OK));
        Assertions.assertTrue(counter.isOK());
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testComparisonWriter(RedisContainer container) throws Throwable {
        BaseRedisAsyncCommands<String, String> source = async(container);
        source.setAutoFlushCommands(false);
        BaseRedisAsyncCommands<String, String> target = async(REDIS_REPLICA);
        target.setAutoFlushCommands(false);
        List<RedisFuture<?>> sourceFutures = new ArrayList<>();
        List<RedisFuture<?>> targetFutures = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            sourceFutures.add(((RedisStringAsyncCommands<String, String>) source).set("key" + index, "value" + index));
            targetFutures.add(((RedisStringAsyncCommands<String, String>) target).set("key" + index, "value" + index));
        }
        source.flushCommands();
        LettuceFutures.awaitAll(10, TimeUnit.SECONDS, sourceFutures.toArray(new RedisFuture[0]));
        target.flushCommands();
        LettuceFutures.awaitAll(10, TimeUnit.SECONDS, targetFutures.toArray(new RedisFuture[0]));
        source.setAutoFlushCommands(true);
        target.setAutoFlushCommands(true);
        RedisHashCommands<String, String> sourceSync = sync(container);
        sourceSync.hset("zehash", "zefield", "zevalue");
        KeyValueItemReader left = dataStructureReader(container);
        DataStructureValueReader right = dataStructureValueReader(REDIS_REPLICA);
        KeyComparisonResultCounter counter = new KeyComparisonResultCounter();
        KeyComparisonItemWriter writer = KeyComparisonItemWriter.valueReader(right).resultHandler(counter).ttlTolerance(Duration.ofMillis(500)).build();
        execute(container, "test-comparison-writer-compare", left, writer);
        Assertions.assertFalse(counter.isOK());
    }

    @Test
    public void testMetrics() throws Throwable {
        Metrics.globalRegistry.getMeters().forEach(Metrics.globalRegistry::remove);
        SimpleMeterRegistry registry = new SimpleMeterRegistry(new SimpleConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public Duration step() {
                return Duration.ofMillis(1);
            }
        }, Clock.SYSTEM);
        Metrics.addRegistry(registry);
        dataGenerator(REDIS).end(100).build().call();
        KeyValueItemReader reader = DataStructureItemReader.client(redisClient(REDIS)).queueCapacity(10).chunkSize(1).build();
        ItemWriter<DataStructure> writer = items -> Thread.sleep(1);
        TaskletStep step = steps.get("metrics-step").<DataStructure, DataStructure>chunk(1).reader(reader).writer(writer).build();
        Job job = job(REDIS, "metrics-job", step);
        JobExecution execution = asyncJobLauncher.run(job, new JobParameters());
        awaitRunning(execution);
        Thread.sleep(100);
        registry.forEachMeter(m -> log.debug("Meter: {}", m.getId().getName()));
        Search search = registry.find("spring.batch.item.read");
        Assertions.assertNotNull(search.timer());
        search = registry.find("spring.batch.redis.reader.queue.size");
        Assertions.assertNotNull(search.gauge());
        awaitJobTermination(execution);
    }

    @ParameterizedTest
    @MethodSource("containers")
    public void testScanSizeEstimator(RedisContainer container) throws Throwable {
        dataGenerator(container).end(12345).dataTypes(DataStructure.HASH).build().call();
        ScanSizeEstimator estimator = sizeEstimator(container);
        long matchSize = estimator.estimate(ScanSizeEstimator.EstimateOptions.builder().sampleSize(100).match("hash:*").build());
        RedisKeyCommands<String, String> sync = sync(container);
        long hashCount = sync.keys("hash:*").size();
        Assertions.assertEquals(hashCount, matchSize, (double) hashCount / 10);
        long typeSize = estimator.estimate(ScanSizeEstimator.EstimateOptions.builder().sampleSize(1000).type(DataStructure.HASH).build());
        Assertions.assertEquals(hashCount, typeSize, (double) hashCount / 10);
    }

    private ScanSizeEstimator sizeEstimator(RedisContainer container) {
        if (container.isCluster()) {
            return ScanSizeEstimator.client(redisClusterClient(container)).build();
        }
        return ScanSizeEstimator.client(redisClient(container)).build();
    }

}
