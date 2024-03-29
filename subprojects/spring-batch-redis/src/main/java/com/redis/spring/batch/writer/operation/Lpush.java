package com.redis.spring.batch.writer.operation;

import java.util.function.Function;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisListAsyncCommands;

public class Lpush<K, V, T> extends AbstractPush<K, V, T> {

	public Lpush(Function<T, K> keyFunction) {
		super(keyFunction);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	protected RedisFuture doPush(RedisListAsyncCommands<K, V> commands, K key, V value) {
		return commands.lpush(key, value);
	}

}
