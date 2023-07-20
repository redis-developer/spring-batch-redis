package com.redis.spring.batch.convert;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import io.lettuce.core.ScoredValue;

public class ScoredValueConverter<V, T> implements Function<T, ScoredValue<V>> {

	private final Function<T, V> member;
	private final ToDoubleFunction<T> score;

	public ScoredValueConverter(Function<T, V> member, ToDoubleFunction<T> score) {
		this.member = member;
		this.score = score;
	}

	@Override
	public ScoredValue<V> apply(T source) {
		return ScoredValue.just(score.applyAsDouble(source), member.apply(source));
	}

}