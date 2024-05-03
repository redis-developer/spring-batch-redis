package com.redis.spring.batch.writer;

import com.redis.spring.batch.common.KeyValue;

public class KeyValueRestore<K, V> extends Restore<K, V, KeyValue<K, byte[]>> {

	public KeyValueRestore() {
		super(KeyValue::getKey, KeyValue::getValue);
		setTtlFunction(KeyValue::getTtl);
	}

}
