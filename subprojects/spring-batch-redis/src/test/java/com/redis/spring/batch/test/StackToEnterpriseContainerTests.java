package com.redis.spring.batch.test;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.redis.testcontainers.RedisEnterpriseContainer;
import com.redis.testcontainers.RedisStackContainer;

@EnabledOnOs(OS.LINUX)
class StackToEnterpriseContainerTests extends BatchTests {

	private static final RedisStackContainer source = RedisContainerFactory.stack();

	private static final RedisEnterpriseContainer target = RedisContainerFactory.enterprise();

	@Override
	protected RedisStackContainer getRedisServer() {
		return source;
	}

	@Override
	protected RedisEnterpriseContainer getTargetRedisServer() {
		return target;
	}

}
