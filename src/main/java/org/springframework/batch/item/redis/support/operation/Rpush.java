package org.springframework.batch.item.redis.support.operation;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import io.lettuce.core.api.async.RedisListAsyncCommands;
import org.springframework.core.convert.converter.Converter;

import java.util.function.Predicate;

public class Rpush<T> extends AbstractCollectionOperation<T> {

    public Rpush(String key, Converter<T, String> member) {
        this(new ConstantConverter<>(key), member, new ConstantPredicate<>(false), new ConstantPredicate<>(false));
    }

    public Rpush(Converter<T, String> key, Converter<T, String> member, Predicate<T> delete, Predicate<T> remove) {
        super(key, member, delete, remove);
    }

    @SuppressWarnings("unchecked")
    @Override
    public RedisFuture<?> add(BaseRedisAsyncCommands<String, String> commands, T item, String key, String member) {
        return ((RedisListAsyncCommands<String, String>) commands).rpush(key, member);
    }

    @Override
    protected RedisFuture<?> remove(BaseRedisAsyncCommands<String, String> commands, String key, String member) {
        return ((RedisListAsyncCommands<String, String>) commands).lrem(key, -1, member);
    }
}
