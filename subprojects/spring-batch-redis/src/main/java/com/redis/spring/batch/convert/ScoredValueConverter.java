package com.redis.spring.batch.convert;

import org.springframework.core.convert.converter.Converter;

import io.lettuce.core.ScoredValue;

public class ScoredValueConverter<V, T> implements Converter<T, ScoredValue<V>> {

	private final Converter<T, V> memberConverter;
	private final Converter<T, Double> scoreConverter;

	public ScoredValueConverter(Converter<T, V> member, Converter<T, Double> score) {
		this.memberConverter = member;
		this.scoreConverter = score;
	}

	@Override
	public ScoredValue<V> convert(T source) {
		Double score = this.scoreConverter.convert(source);
		if (score == null) {
			return null;
		}
		return ScoredValue.just(score, memberConverter.convert(source));
	}

}