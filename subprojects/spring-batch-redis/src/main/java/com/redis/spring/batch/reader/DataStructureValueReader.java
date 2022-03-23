package com.redis.spring.batch.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import com.redis.lettucemod.api.async.RedisJSONAsyncCommands;
import com.redis.lettucemod.api.async.RedisTimeSeriesAsyncCommands;
import com.redis.lettucemod.timeseries.RangeOptions;
import com.redis.spring.batch.DataStructure;

import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import io.lettuce.core.api.async.RedisHashAsyncCommands;
import io.lettuce.core.api.async.RedisKeyAsyncCommands;
import io.lettuce.core.api.async.RedisListAsyncCommands;
import io.lettuce.core.api.async.RedisSetAsyncCommands;
import io.lettuce.core.api.async.RedisSortedSetAsyncCommands;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import io.lettuce.core.api.async.RedisStringAsyncCommands;

public class DataStructureValueReader<K, V> extends AbstractValueReader<K, V, DataStructure<K>> {

	private static final RangeOptions RANGE_OPTIONS = RangeOptions.builder().build();

	public DataStructureValueReader(Supplier<StatefulConnection<K, V>> connectionSupplier,
			GenericObjectPoolConfig<StatefulConnection<K, V>> poolConfig,
			Function<StatefulConnection<K, V>, BaseRedisAsyncCommands<K, V>> async) {
		super(connectionSupplier, poolConfig, async);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected List<DataStructure<K>> read(BaseRedisAsyncCommands<K, V> commands, long timeout, List<? extends K> keys)
			throws InterruptedException, ExecutionException, TimeoutException {
		List<RedisFuture<String>> typeFutures = new ArrayList<>(keys.size());
		for (K key : keys) {
			typeFutures.add(((RedisKeyAsyncCommands<K, V>) commands).type(key));
		}
		commands.flushCommands();
		List<DataStructure<K>> dataStructures = new ArrayList<>(keys.size());
		List<RedisFuture<Long>> ttlFutures = new ArrayList<>(keys.size());
		List<RedisFuture<?>> valueFutures = new ArrayList<>(keys.size());
		for (int index = 0; index < keys.size(); index++) {
			K key = keys.get(index);
			String type = typeFutures.get(index).get(timeout, TimeUnit.MILLISECONDS);
			valueFutures.add(value(commands, key, type));
			ttlFutures.add(absoluteTTL(commands, key));
			dataStructures.add(new DataStructure<>(key, type));
		}
		commands.flushCommands();
		for (int index = 0; index < dataStructures.size(); index++) {
			DataStructure<K> dataStructure = dataStructures.get(index);
			RedisFuture<?> valueFuture = valueFutures.get(index);
			if (valueFuture != null) {
				Object value = valueFuture.get(timeout, TimeUnit.MILLISECONDS);
				if (value != null) {
					setValue(dataStructure, value);
				}
			}
			long absoluteTTL = ttlFutures.get(index).get(timeout, TimeUnit.MILLISECONDS);
			dataStructure.setTtl(absoluteTTL);
		}
		return dataStructures;
	}

	/**
	 * Sets the value of a data structure
	 * 
	 * @param dataStructure the data structure to set the value of
	 * @param value         data structure value, not null
	 */
	protected void setValue(DataStructure<K> dataStructure, Object value) {
		dataStructure.setValue(value);
	}

	@SuppressWarnings("unchecked")
	private RedisFuture<?> value(BaseRedisAsyncCommands<K, V> commands, K key, String type) {
		if (type == null) {
			return null;
		}
		switch (type.toLowerCase()) {
		case DataStructure.TYPE_HASH:
			return ((RedisHashAsyncCommands<K, V>) commands).hgetall(key);
		case DataStructure.TYPE_LIST:
			return ((RedisListAsyncCommands<K, V>) commands).lrange(key, 0, -1);
		case DataStructure.TYPE_SET:
			return ((RedisSetAsyncCommands<K, V>) commands).smembers(key);
		case DataStructure.TYPE_STREAM:
			return ((RedisStreamAsyncCommands<K, V>) commands).xrange(key, Range.create("-", "+"));
		case DataStructure.TYPE_STRING:
			return ((RedisStringAsyncCommands<K, V>) commands).get(key);
		case DataStructure.TYPE_ZSET:
			return ((RedisSortedSetAsyncCommands<K, V>) commands).zrangeWithScores(key, 0, -1);
		case DataStructure.TYPE_JSON:
			return ((RedisJSONAsyncCommands<K, V>) commands).jsonGet(key);
		case DataStructure.TYPE_TIMESERIES:
			return ((RedisTimeSeriesAsyncCommands<K, V>) commands).range(key, RANGE_OPTIONS);
		default:
			return null;
		}
	}

}
