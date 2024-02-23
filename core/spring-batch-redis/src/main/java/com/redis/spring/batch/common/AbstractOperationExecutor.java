package com.redis.spring.batch.common;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;

import com.redis.spring.batch.util.ConnectionUtils;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.internal.Exceptions;
import io.lettuce.core.support.ConnectionPoolSupport;

public abstract class AbstractOperationExecutor<K, V, I, O> implements ItemStream {

	public static final int DEFAULT_POOL_SIZE = GenericObjectPoolConfig.DEFAULT_MAX_TOTAL;

	protected final AbstractRedisClient client;

	private final RedisCodec<K, V> codec;

	private ReadFrom readFrom;

	private int poolSize = DEFAULT_POOL_SIZE;

	private GenericObjectPool<StatefulConnection<K, V>> pool;

	private BatchOperation<K, V, I, O> batchOperation;

	protected AbstractOperationExecutor(AbstractRedisClient client, RedisCodec<K, V> codec) {
		this.client = client;
		this.codec = codec;
	}

	public void setPoolSize(int poolSize) {
		this.poolSize = poolSize;
	}

	public void setReadFrom(ReadFrom readFrom) {
		this.readFrom = readFrom;
	}

	@Override
	public synchronized void open(ExecutionContext executionContext) {
		if (pool == null) {
			Supplier<StatefulConnection<K, V>> connectionSupplier = ConnectionUtils.supplier(client, codec, readFrom);
			GenericObjectPoolConfig<StatefulConnection<K, V>> config = new GenericObjectPoolConfig<>();
			config.setMaxTotal(poolSize);
			batchOperation = batchOperation();
			pool = ConnectionPoolSupport.createGenericObjectPool(connectionSupplier, config);
		}
	}

	protected abstract BatchOperation<K, V, I, O> batchOperation();

	@Override
	public synchronized void close() {
		if (pool != null) {
			pool.close();
			pool = null;
		}
	}

	public List<O> process(Iterable<I> items) throws RedisException {
		StatefulConnection<K, V> connection;
		try {
			connection = pool.borrowObject();
		} catch (Exception e) {
			throw new RedisConnectionException("Could not get connection from pool", e);
		}
		try {
			connection.setAutoFlushCommands(false);
			BaseRedisAsyncCommands<K, V> commands = ConnectionUtils.async(connection);
			List<RedisFuture<O>> futures = batchOperation.execute(commands, items);
			connection.flushCommands();
			return getAll(connection.getTimeout(), futures);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RedisCommandInterruptedException(e);
		} catch (Exception e) {
			throw Exceptions.fromSynchronization(e);
		} finally {
			connection.setAutoFlushCommands(true);
			connection.close();
		}
	}

	public static <T> List<T> getAll(Duration timeout, List<RedisFuture<T>> futures)
			throws TimeoutException, InterruptedException, ExecutionException {
		List<T> results = new ArrayList<>(futures.size());
		long nanos = timeout.toNanos();
		long time = System.nanoTime();
		for (RedisFuture<T> f : futures) {
			if (timeout.isNegative()) {
				results.add(f.get());
			} else {
				if (nanos < 0) {
					throw new TimeoutException(String.format("Timed out after %s", timeout));
				}
				results.add(f.get(nanos, TimeUnit.NANOSECONDS));
				long now = System.nanoTime();
				nanos -= now - time;
				time = now;
			}
		}
		return results;
	}

}
