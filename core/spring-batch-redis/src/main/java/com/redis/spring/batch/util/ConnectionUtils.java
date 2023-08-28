package com.redis.spring.batch.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import org.springframework.batch.item.ItemStreamException;
import org.springframework.util.FileCopyUtils;

import com.redis.lettucemod.RedisModulesClient;
import com.redis.lettucemod.cluster.RedisModulesClusterClient;
import com.redis.spring.batch.reader.KeyValueItemProcessor;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

public interface ConnectionUtils {

    static Supplier<StatefulConnection<String, String>> supplier(AbstractRedisClient client) {
        return supplier(client, StringCodec.UTF8);
    }

    static Supplier<StatefulConnection<String, String>> supplier(AbstractRedisClient client, ReadFrom readFrom) {
        return supplier(client, StringCodec.UTF8, readFrom);
    }

    static <K, V> Supplier<StatefulConnection<K, V>> supplier(AbstractRedisClient client, RedisCodec<K, V> codec) {
        return supplier(client, codec, null);
    }

    static <K, V> Supplier<StatefulConnection<K, V>> supplier(AbstractRedisClient client, RedisCodec<K, V> codec,
            ReadFrom readFrom) {
        if (client instanceof RedisModulesClusterClient) {
            return () -> {
                StatefulRedisClusterConnection<K, V> connection = ((RedisModulesClusterClient) client).connect(codec);
                if (readFrom != null) {
                    connection.setReadFrom(readFrom);
                }
                return connection;
            };
        }
        return () -> ((RedisModulesClient) client).connect(codec);
    }

    @SuppressWarnings("unchecked")
    static <K, V, T> T sync(StatefulConnection<K, V> connection) {
        if (connection instanceof StatefulRedisClusterConnection) {
            return (T) ((StatefulRedisClusterConnection<K, V>) connection).sync();
        }
        return (T) ((StatefulRedisConnection<K, V>) connection).sync();
    }

    @SuppressWarnings("unchecked")
    static <K, V, T> T async(StatefulConnection<K, V> connection) {
        if (connection instanceof StatefulRedisClusterConnection) {
            return (T) ((StatefulRedisClusterConnection<K, V>) connection).async();
        }
        return (T) ((StatefulRedisConnection<K, V>) connection).async();
    }

    @SuppressWarnings("unchecked")
    static String loadScript(AbstractRedisClient client, String filename) {
        byte[] bytes;
        try (InputStream inputStream = KeyValueItemProcessor.class.getClassLoader().getResourceAsStream(filename)) {
            bytes = FileCopyUtils.copyToByteArray(inputStream);
        } catch (IOException e) {
            throw new ItemStreamException("Could not read LUA script file " + filename);
        }
        try (StatefulConnection<String, String> connection = supplier(client, StringCodec.UTF8).get()) {
            return ((RedisScriptingCommands<String, String>) sync(connection)).scriptLoad(bytes);
        }
    }

}