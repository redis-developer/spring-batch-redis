package com.redis.spring.batch.reader;

import com.redis.spring.batch.common.Operation;
import com.redis.spring.batch.common.KeyValue;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.codec.RedisCodec;

public class KeyTypeItemReader<K, V> extends AbstractKeyValueItemReader<K, V> {

	public KeyTypeItemReader(AbstractRedisClient client, RedisCodec<K, V> codec) {
		super(client, codec);
	}

	@Override
	protected Operation<K, V, K, KeyValue<K>> operation() {
		return new KeyTypeReadOperation<>();
	}

}
