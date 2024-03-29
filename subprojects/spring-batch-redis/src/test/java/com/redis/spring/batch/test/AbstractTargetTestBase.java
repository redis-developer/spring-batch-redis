package com.redis.spring.batch.test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.testcontainers.lifecycle.Startable;

import com.redis.lettucemod.api.StatefulRedisModulesConnection;
import com.redis.lettucemod.api.sync.RedisModulesCommands;
import com.redis.lettucemod.util.RedisModulesUtils;
import com.redis.spring.batch.RedisItemReader;
import com.redis.spring.batch.common.KeyComparison;
import com.redis.spring.batch.common.KeyComparisonItemReader;
import com.redis.spring.batch.reader.StructItemReader;
import com.redis.testcontainers.RedisServer;

import io.lettuce.core.AbstractRedisClient;

public abstract class AbstractTargetTestBase extends AbstractTestBase {

	private final Logger log = LoggerFactory.getLogger(AbstractTargetTestBase.class);

	protected AbstractRedisClient targetClient;
	protected StatefulRedisModulesConnection<String, String> targetConnection;
	protected RedisModulesCommands<String, String> targetCommands;

	protected abstract RedisServer getTargetRedisServer();

	@BeforeAll
	void targetSetup() throws Exception {
		// Target Redis setup

		RedisServer targetRedis = getTargetRedisServer();
		if (targetRedis instanceof Startable) {
			((Startable) targetRedis).start();
		}
		targetClient = client(getTargetRedisServer());
		targetConnection = RedisModulesUtils.connection(targetClient);
		targetCommands = targetConnection.sync();
	}

	@AfterAll
	void targetTeardown() {
		if (targetConnection != null) {
			targetConnection.close();
		}
		if (targetClient != null) {
			targetClient.shutdown();
			targetClient.getResources().shutdown();
		}
		RedisServer targetRedis = getTargetRedisServer();
		if (targetRedis instanceof Startable) {
			((Startable) targetRedis).stop();
		}
	}

	@BeforeEach
	void targetFlushAll() {
		targetCommands.flushall();
	}

	/**
	 * 
	 * @param left
	 * @param right
	 * @return
	 * @return
	 * @return list of differences
	 * @throws Exception
	 */
	protected KeyspaceComparison compare(TestInfo info) throws Exception {
		assertDbNotEmpty(commands);
		KeyComparisonItemReader reader = comparisonReader(testInfo(info, "compare"));
		reader.open(new ExecutionContext());
		List<KeyComparison> comparisons = readAll(reader);
		reader.close();
		return new KeyspaceComparison(comparisons);
	}

	protected void logDiffs(Collection<KeyComparison> diffs) {
		for (KeyComparison diff : diffs) {
			log.error("{}: {} {}", diff.getStatus(), diff.getSource().getKey(), diff.getSource().getType());
		}
	}

	protected KeyComparisonItemReader comparisonReader(TestInfo info) throws Exception {
		StructItemReader<String, String> sourceReader = RedisItemReader.struct(client);
		StructItemReader<String, String> targetReader = RedisItemReader.struct(targetClient);
		KeyComparisonItemReader reader = new KeyComparisonItemReader(sourceReader, targetReader);
		configure(info, reader, "comparison");
		reader.setTtlTolerance(Duration.ofMillis(100));
		return reader;
	}

}
