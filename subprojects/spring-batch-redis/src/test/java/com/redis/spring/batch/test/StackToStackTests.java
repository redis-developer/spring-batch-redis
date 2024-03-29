package com.redis.spring.batch.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.item.support.ListItemWriter;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.util.unit.DataSize;

import com.redis.spring.batch.RedisItemReader;
import com.redis.spring.batch.RedisItemWriter;
import com.redis.spring.batch.common.DataType;
import com.redis.spring.batch.common.KeyValue;
import com.redis.spring.batch.common.Range;
import com.redis.spring.batch.common.ToScoredValueFunction;
import com.redis.spring.batch.common.ValueReader;
import com.redis.spring.batch.gen.GeneratorItemReader;
import com.redis.spring.batch.gen.MapOptions;
import com.redis.spring.batch.reader.DumpItemReader;
import com.redis.spring.batch.reader.StreamItemReader;
import com.redis.spring.batch.reader.StreamItemReader.AckPolicy;
import com.redis.spring.batch.reader.StructItemReader;
import com.redis.spring.batch.util.CodecUtils;
import com.redis.spring.batch.writer.DumpItemWriter;
import com.redis.spring.batch.writer.OperationItemWriter;
import com.redis.spring.batch.writer.StructItemWriter;
import com.redis.spring.batch.writer.operation.Del;
import com.redis.spring.batch.writer.operation.Expire;
import com.redis.spring.batch.writer.operation.ExpireAt;
import com.redis.spring.batch.writer.operation.Hset;
import com.redis.spring.batch.writer.operation.Lpush;
import com.redis.spring.batch.writer.operation.LpushAll;
import com.redis.spring.batch.writer.operation.Rpush;
import com.redis.spring.batch.writer.operation.Sadd;
import com.redis.spring.batch.writer.operation.Xadd;
import com.redis.spring.batch.writer.operation.Zadd;
import com.redis.testcontainers.RedisStackContainer;

import io.lettuce.core.Consumer;
import io.lettuce.core.KeyScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.Range.Boundary;
import io.lettuce.core.codec.ByteArrayCodec;

class StackToStackTests extends BatchTests {

	private static final RedisStackContainer source = RedisContainerFactory.stack();

	private static final RedisStackContainer target = RedisContainerFactory.stack();

	@Override
	protected RedisStackContainer getRedisServer() {
		return source;
	}

	@Override
	protected RedisStackContainer getTargetRedisServer() {
		return target;
	}

	@Test
	void readStructLive(TestInfo info) throws Exception {
		enableKeyspaceNotifications(client);
		StructItemReader<byte[], byte[]> reader = configure(info,
				RedisItemReader.struct(client, ByteArrayCodec.INSTANCE));
		live(reader);
		reader.setKeyspaceNotificationQueueCapacity(10000);
		reader.open(new ExecutionContext());
		int count = 1234;
		generate(info, generator(count, DataType.HASH, DataType.STRING));
		List<KeyValue<byte[]>> list = readAll(reader);
		Function<byte[], String> toString = CodecUtils.toStringKeyFunction(ByteArrayCodec.INSTANCE);
		Set<String> keys = list.stream().map(KeyValue::getKey).map(toString).collect(Collectors.toSet());
		Assertions.assertEquals(count, keys.size());
		reader.close();
	}

	@Test
	void replicateHLL(TestInfo info) throws Exception {
		String key1 = "hll:1";
		commands.pfadd(key1, "member:1", "member:2");
		String key2 = "hll:2";
		commands.pfadd(key2, "member:1", "member:2", "member:3");
		StructItemReader<byte[], byte[]> reader = configure(info,
				RedisItemReader.struct(client, ByteArrayCodec.INSTANCE));
		StructItemWriter<byte[], byte[]> writer = RedisItemWriter.struct(targetClient, ByteArrayCodec.INSTANCE);
		replicate(info, reader, writer);
		assertEquals(commands.pfcount(key1), targetCommands.pfcount(key1));
	}

	@Test
	void readLiveType(TestInfo info) throws Exception {
		enableKeyspaceNotifications(client);
		StructItemReader<String, String> reader = live(structReader(info));
		reader.setKeyType(DataType.HASH.getString());
		reader.open(new ExecutionContext());
		generate(info, generator(100));
		reader.open(new ExecutionContext());
		List<KeyValue<String>> keyValues = readAll(reader);
		reader.close();
		Assertions.assertTrue(keyValues.stream().allMatch(v -> v.getType() == DataType.HASH));
	}

	@Test
	void readStructMemoryUsage(TestInfo info) throws Exception {
		generate(info, generator(73));
		long memLimit = 200;
		StructItemReader<String, String> reader = structReader(info);
		reader.setMemoryUsageLimit(DataSize.ofBytes(memLimit));
		reader.open(new ExecutionContext());
		List<KeyValue<String>> keyValues = readAll(reader);
		reader.close();
		Assertions.assertFalse(keyValues.isEmpty());
		for (KeyValue<String> keyValue : keyValues) {
			Assertions.assertTrue(keyValue.getMemoryUsage() > 0);
			if (keyValue.getMemoryUsage() > memLimit) {
				Assertions.assertNull(keyValue.getValue());
			}
		}
	}

	@Test
	void readStructMemoryUsageTTL(TestInfo info) throws Exception {
		String key = "myhash";
		Map<String, String> hash = new HashMap<>();
		hash.put("field1", "value1");
		hash.put("field2", "value2");
		commands.hset(key, hash);
		long ttl = System.currentTimeMillis() + 123456;
		commands.pexpireat(key, ttl);
		StructItemReader<String, String> reader = structReader(info);
		reader.setMemoryUsageLimit(DataSize.ofBytes(-1));
		ValueReader<String, String, String, KeyValue<String>> executor = reader.valueReader();
		executor.open();
		KeyValue<String> ds = executor.execute(key);
		Assertions.assertEquals(key, ds.getKey());
		Assertions.assertEquals(ttl, ds.getTtl());
		Assertions.assertEquals(DataType.HASH, ds.getType());
		Assertions.assertTrue(ds.getMemoryUsage() > 0);
		executor.close();
	}

	@Test
	void readStructMemLimit(TestInfo info) throws Exception {
		DataSize limit = DataSize.ofBytes(500);
		String key1 = "key:1";
		commands.set(key1, "bar");
		String key2 = "key:2";
		commands.set(key2, GeneratorItemReader.string(Math.toIntExact(limit.toBytes() * 2)));
		StructItemReader<String, String> reader = structReader(info);
		reader.setMemoryUsageLimit(limit);
		reader.open(new ExecutionContext());
		List<KeyValue<String>> keyValues = readAll(reader);
		reader.close();
		Map<String, KeyValue<String>> map = keyValues.stream()
				.collect(Collectors.toMap(s -> s.getKey(), Function.identity()));
		Assertions.assertNull(map.get(key2).getValue());
	}

	@Test
	void replicateStructByteArray(TestInfo info) throws Exception {
		GeneratorItemReader gen = generator(1000);
		generate(info, gen);
		StructItemReader<byte[], byte[]> reader = configure(info,
				RedisItemReader.struct(client, ByteArrayCodec.INSTANCE));
		StructItemWriter<byte[], byte[]> writer = RedisItemWriter.struct(targetClient, ByteArrayCodec.INSTANCE);
		replicate(info, reader, writer);
	}

	@Test
	void replicateStructMemLimit(TestInfo info) throws Exception {
		generate(info, generator(73));
		StructItemReader<String, String> reader = structReader(info);
		reader.setMemoryUsageLimit(DataSize.ofMegabytes(100));
		StructItemWriter<String, String> writer = RedisItemWriter.struct(targetClient);
		replicate(info, reader, writer);
	}

	@Test
	void replicateDumpMemLimitHigh(TestInfo info) throws Exception {
		generate(info, generator(73));
		DumpItemReader reader = dumpReader(info);
		reader.setMemoryUsageLimit(DataSize.ofMegabytes(100));
		DumpItemWriter writer = RedisItemWriter.dump(targetClient);
		replicate(info, reader, writer);
	}

	@Test
	void replicateDumpMemLimitLow(TestInfo info) throws Exception {
		generate(info, generator(73));
		Assertions.assertTrue(commands.dbsize() > 10);
		long memLimit = 1500;
		DumpItemReader reader = dumpReader(info);
		reader.setMemoryUsageLimit(DataSize.ofBytes(memLimit));
		DumpItemWriter writer = RedisItemWriter.dump(targetClient);
		run(info, reader, writer);
		StructItemReader<String, String> fullReader = configure(info, RedisItemReader.struct(client), "full");
		fullReader.setMemoryUsageLimit(DataSize.ofBytes(-1));
		fullReader.open(new ExecutionContext());
		List<KeyValue<String>> items = readAll(fullReader);
		fullReader.close();
		Predicate<KeyValue<String>> isMemKey = v -> v.getMemoryUsage() > memLimit;
		List<KeyValue<String>> bigkeys = items.stream().filter(isMemKey).collect(Collectors.toList());
		Assertions.assertEquals(commands.dbsize(), bigkeys.size() + targetCommands.dbsize());
	}

	@Test
	void writeStruct(TestInfo info) throws Exception {
		int count = 1000;
		GeneratorItemReader reader = generator(count);
		generate(info, reader);
		StructItemWriter<String, String> writer = RedisItemWriter.struct(client);
		run(info, reader, writer);
		awaitUntil(() -> keyCount("gen:*") == count);
		assertEquals(count, keyCount("gen:*"));
	}

	@Test
	void writeStructMultiExec(TestInfo info) throws Exception {
		int count = 10;
		GeneratorItemReader reader = generator(count);
		StructItemWriter<String, String> writer = RedisItemWriter.struct(client);
		writer.setMultiExec(true);
		run(info, step(info, 1, reader, null, writer));
		assertEquals(count, commands.dbsize());
	}

	@Test
	void writeStreamMultiExec(TestInfo testInfo) throws Exception {
		String stream = "stream:1";
		List<Map<String, String>> messages = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			Map<String, String> body = new HashMap<>();
			body.put("field1", "value1");
			body.put("field2", "value2");
			messages.add(body);
		}
		ListItemReader<Map<String, String>> reader = new ListItemReader<>(messages);
		Xadd<String, String, Map<String, String>> xadd = new Xadd<>(keyFunction(stream), Function.identity());
		OperationItemWriter<String, String, Map<String, String>> writer = writer(xadd);
		writer.setMultiExec(true);
		run(testInfo, reader, writer);
		Assertions.assertEquals(messages.size(), commands.xlen(stream));
		List<StreamMessage<String, String>> xrange = commands.xrange(stream, io.lettuce.core.Range.create("-", "+"));
		for (int index = 0; index < xrange.size(); index++) {
			StreamMessage<String, String> message = xrange.get(index);
			Assertions.assertEquals(messages.get(index), message.getBody());
		}
	}

	@Test
	void readMultipleStreams(TestInfo info) throws Exception {
		String consumerGroup = "consumerGroup";
		generateStreams(info, 277);
		KeyScanArgs args = KeyScanArgs.Builder.type(DataType.STREAM.getString());
		final List<String> keys = ScanIterator.scan(commands, args).stream().collect(Collectors.toList());
		for (String key : keys) {
			long count = commands.xlen(key);
			StreamItemReader<String, String> reader1 = streamReader(info, key,
					Consumer.from(consumerGroup, "consumer1"));
			reader1.setAckPolicy(AckPolicy.MANUAL);
			StreamItemReader<String, String> reader2 = streamReader(info, key,
					Consumer.from(consumerGroup, "consumer2"));
			reader2.setAckPolicy(AckPolicy.MANUAL);
			ListItemWriter<StreamMessage<String, String>> writer1 = new ListItemWriter<>();
			TestInfo testInfo1 = new SimpleTestInfo(info, key, "1");
			TaskletStep step1 = faultTolerant(flushingStep(testInfo1, reader1, writer1)).build();
			TestInfo testInfo2 = new SimpleTestInfo(info, key, "2");
			ListItemWriter<StreamMessage<String, String>> writer2 = new ListItemWriter<>();
			TaskletStep step2 = faultTolerant(flushingStep(testInfo2, reader2, writer2)).build();
			SimpleFlow flow1 = flow("flow1").start(step1).build();
			SimpleFlow flow2 = flow("flow2").start(step2).build();
			SimpleFlow flow = flow("replicate").split(new SimpleAsyncTaskExecutor()).add(flow1, flow2).build();
			run(job(testInfo1).start(flow).build().build());
			Assertions.assertEquals(count, writer1.getWrittenItems().size() + writer2.getWrittenItems().size());
			assertMessageBody(writer1.getWrittenItems());
			assertMessageBody(writer2.getWrittenItems());
			Assertions.assertEquals(count, commands.xpending(key, consumerGroup).getCount());
			reader1 = streamReader(info, key, Consumer.from(consumerGroup, "consumer1"));
			reader1.setAckPolicy(AckPolicy.MANUAL);
			reader1.open(new ExecutionContext());
			reader1.ack(writer1.getWrittenItems());
			reader1.close();
			reader2 = streamReader(info, key, Consumer.from(consumerGroup, "consumer2"));
			reader2.setAckPolicy(AckPolicy.MANUAL);
			reader2.open(new ExecutionContext());
			reader2.ack(writer2.getWrittenItems());
			reader2.close();
			Assertions.assertEquals(0, commands.xpending(key, consumerGroup).getCount());
		}
	}

	@Test
	void replicateStruct(TestInfo info) throws Exception {
		GeneratorItemReader gen = generator(100);
		generate(info, gen);
		StructItemReader<String, String> reader = structReader(info);
		StructItemWriter<String, String> writer = RedisItemWriter.struct(targetClient);
		replicate(info, reader, writer);
	}

	private static FlowBuilder<SimpleFlow> flow(String name) {
		return new FlowBuilder<>(name);
	}

	@Test
	void writeHash(TestInfo info) throws Exception {
		int count = 100;
		List<Map<String, String>> maps = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			Map<String, String> body = new HashMap<>();
			body.put("id", String.valueOf(index));
			body.put("field1", "value1");
			body.put("field2", "value2");
			maps.add(body);
		}
		ListItemReader<Map<String, String>> reader = new ListItemReader<>(maps);
		Hset<String, String, Map<String, String>> hset = new Hset<>(m -> "hash:" + m.remove("id"), Function.identity());
		OperationItemWriter<String, String, Map<String, String>> writer = writer(hset);
		run(info, reader, writer);
		assertEquals(count, keyCount("hash:*"));
		for (int index = 0; index < maps.size(); index++) {
			Map<String, String> hash = commands.hgetall("hash:" + index);
			assertEquals(maps.get(index), hash);
		}
	}

	@Test
	void writeHashDel(TestInfo info) throws Exception {
		List<Entry<String, Map<String, String>>> hashes = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			String key = String.valueOf(index);
			Map<String, String> value = new HashMap<>();
			value.put("field1", "value1");
			commands.hset("hash:" + key, value);
			Map<String, String> body = new HashMap<>();
			body.put("field2", "value2");
			hashes.add(new AbstractMap.SimpleEntry<>(key, index < 50 ? null : body));
		}
		ListItemReader<Map.Entry<String, Map<String, String>>> reader = new ListItemReader<>(hashes);
		Hset<String, String, Entry<String, Map<String, String>>> hset = new Hset<>(e -> "hash:" + e.getKey(),
				Entry::getValue);
		OperationItemWriter<String, String, Entry<String, Map<String, String>>> writer = writer(hset);
		run(info, reader, writer);
		assertEquals(100, keyCount("hash:*"));
		assertEquals(2, commands.hgetall("hash:50").size());
	}

	@Test
	void writeDel(TestInfo info) throws Exception {
		generate(info, generator(73));
		GeneratorItemReader gen = generator(73);
		Del<String, String, KeyValue<String>> del = new Del<>(KeyValue::getKey);
		OperationItemWriter<String, String, KeyValue<String>> writer = writer(del);
		run(info, gen, writer);
		assertEquals(0, keyCount(GeneratorItemReader.DEFAULT_KEYSPACE + "*"));
	}

	@Test
	void writeLpush(TestInfo info) throws Exception {
		int count = 73;
		GeneratorItemReader gen = generator(count, DataType.STRING);
		Lpush<String, String, KeyValue<String>> lpush = new Lpush<>(KeyValue::getKey);
		lpush.setValueFunction(v -> (String) v.getValue());
		OperationItemWriter<String, String, KeyValue<String>> writer = writer(lpush);
		run(info, gen, writer);
		assertEquals(count, commands.dbsize());
		for (String key : commands.keys("*")) {
			assertEquals(DataType.LIST.getString(), commands.type(key));
		}
	}

	@Test
	void writeRpush(TestInfo info) throws Exception {
		int count = 73;
		GeneratorItemReader gen = generator(count, DataType.STRING);
		Rpush<String, String, KeyValue<String>> rpush = new Rpush<>(KeyValue::getKey);
		rpush.setValueFunction(v -> (String) v.getValue());
		OperationItemWriter<String, String, KeyValue<String>> writer = writer(rpush);
		run(info, gen, writer);
		assertEquals(count, commands.dbsize());
		for (String key : commands.keys("*")) {
			assertEquals(DataType.LIST.getString(), commands.type(key));
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	void writeLpushAll(TestInfo info) throws Exception {
		int count = 73;
		GeneratorItemReader gen = generator(count, DataType.LIST);
		LpushAll<String, String, KeyValue<String>> lpushAll = new LpushAll<>(KeyValue::getKey,
				v -> (Collection<String>) v.getValue());
		OperationItemWriter<String, String, KeyValue<String>> writer = writer(lpushAll);
		run(info, gen, writer);
		assertEquals(count, commands.dbsize());
		for (String key : commands.keys("*")) {
			assertEquals(DataType.LIST.getString(), commands.type(key));
		}
	}

	@Test
	void writeExpire(TestInfo info) throws Exception {
		int count = 73;
		GeneratorItemReader gen = generator(count, DataType.STRING);
		Duration ttl = Duration.ofMillis(1);
		Expire<String, String, KeyValue<String>> expire = new Expire<>(KeyValue::getKey);
		expire.setTtl(ttl);
		OperationItemWriter<String, String, KeyValue<String>> writer = writer(expire);
		run(info, gen, writer);
		awaitUntil(() -> commands.dbsize() == 0);
		assertEquals(0, commands.dbsize());
	}

	@Test
	void writeExpireAt(TestInfo info) throws Exception {
		int count = 73;
		GeneratorItemReader gen = generator(count, DataType.STRING);
		ExpireAt<String, String, KeyValue<String>> expireAt = new ExpireAt<>(KeyValue::getKey);
		expireAt.setEpochFunction(v -> System.currentTimeMillis());
		OperationItemWriter<String, String, KeyValue<String>> writer = writer(expireAt);
		run(info, gen, writer);
		awaitUntil(() -> commands.dbsize() == 0);
		assertEquals(0, commands.dbsize());
	}

	@Test
	void writeZset(TestInfo info) throws Exception {
		String key = "zadd";
		List<ZValue> values = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			values.add(new ZValue(String.valueOf(index), index % 10));
		}
		ListItemReader<ZValue> reader = new ListItemReader<>(values);
		Zadd<String, String, ZValue> zadd = new Zadd<>(keyFunction(key),
				new ToScoredValueFunction<>(ZValue::getMember, ZValue::getScore));
		OperationItemWriter<String, String, ZValue> writer = writer(zadd);
		run(info, reader, writer);
		assertEquals(1, commands.dbsize());
		assertEquals(values.size(), commands.zcard(key));
		assertEquals(60, commands
				.zrangebyscore(key, io.lettuce.core.Range.from(Boundary.including(0), Boundary.including(5))).size());
	}

	@Test
	void writeSet(TestInfo info) throws Exception {
		String key = "sadd";
		List<String> values = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			values.add(String.valueOf(index));
		}
		ListItemReader<String> reader = new ListItemReader<>(values);
		Sadd<String, String, String> sadd = new Sadd<>(keyFunction(key), Function.identity());
		OperationItemWriter<String, String, String> writer = writer(sadd);
		run(info, reader, writer);
		assertEquals(1, commands.dbsize());
		assertEquals(values.size(), commands.scard(key));
	}

	private MapOptions hashOptions(Range fieldCount) {
		MapOptions options = new MapOptions();
		options.setFieldCount(fieldCount);
		return options;
	}

	@Test
	void writeStructOverwrite(TestInfo info) throws Exception {
		GeneratorItemReader gen1 = generator(100, DataType.HASH);
		gen1.setHashOptions(hashOptions(Range.of(5)));
		generate(info, gen1);
		GeneratorItemReader gen2 = generator(100, DataType.HASH);
		gen2.setHashOptions(hashOptions(Range.of(10)));
		generate(testInfo(info, "target"), targetClient, gen2);
		replicate(info, structReader(info), RedisItemWriter.struct(targetClient));
		assertEquals(commands.hgetall("gen:1"), targetCommands.hgetall("gen:1"));
	}

	@Test
	void writeStructMerge(TestInfo info) throws Exception {
		GeneratorItemReader gen1 = generator(100, DataType.HASH);
		gen1.setHashOptions(hashOptions(Range.of(5)));
		generate(info, gen1);
		GeneratorItemReader gen2 = generator(100, DataType.HASH);
		gen2.setHashOptions(hashOptions(Range.of(10)));
		generate(testInfo(info, "target"), targetClient, gen2);
		StructItemReader<String, String> reader = structReader(info);
		StructItemWriter<String, String> writer = RedisItemWriter.struct(targetClient);
		writer.setMerge(true);
		run(testInfo(info, "replicate"), reader, writer);
		Map<String, String> actual = targetCommands.hgetall("gen:1");
		assertEquals(10, actual.size());
	}

}
