package com.redis.spring.batch.item.redis.reader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.util.ClassUtils;

import com.redis.spring.batch.item.AbstractQueuePollableItemReader;
import com.redis.spring.batch.item.redis.common.BatchUtils;
import com.redis.spring.batch.item.redis.common.DataType;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.pubsub.RedisClusterPubSubListener;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.pubsub.RedisPubSubListener;

public class KeyNotificationItemReader<K, V> extends AbstractQueuePollableItemReader<K> {

	private static final String KEYSPACE_PATTERN = "__keyspace@%s__:%s";
	private static final String KEYEVENT_PATTERN = "__keyevent@%s__:*";

	private static final String SEPARATOR = ":";

	private final KeyNotificationDataTypeFunction dataTypeFunction = new KeyNotificationDataTypeFunction();
	private final AbstractRedisClient client;
	private final RedisCodec<K, V> codec;
	private final Function<String, K> keyEncoder;
	private final Function<K, String> keyDecoder;
	private final Function<V, String> valueDecoder;

	public static final int DEFAULT_QUEUE_CAPACITY = 10000;

	private int database;
	private String keyPattern;
	private String keyType;

	private AutoCloseable publisher;
	private HashSet<Wrapper<K>> keySet;
	private List<KeyEventListener<K>> notificationListeners = new ArrayList<>();

	public KeyNotificationItemReader(AbstractRedisClient client, RedisCodec<K, V> codec) {
		setName(ClassUtils.getShortName(getClass()));
		this.client = client;
		this.codec = codec;
		this.keyEncoder = BatchUtils.stringKeyFunction(codec);
		this.keyDecoder = BatchUtils.toStringKeyFunction(codec);
		this.valueDecoder = BatchUtils.toStringValueFunction(codec);
	}

	public void addEventListener(KeyEventListener<K> listener) {
		notificationListeners.add(listener);
	}

	public String pubSubPattern() {
		if (isKeyEvents()) {
			return String.format(KEYEVENT_PATTERN, database);
		}
		return String.format(KEYSPACE_PATTERN, database, keyPattern);
	}

	private boolean isKeyEvents() {
		return keyPattern == null;
	}

	@Override
	public boolean isComplete() {
		return publisher == null;
	}

	@Override
	protected synchronized void doOpen() throws Exception {
		super.doOpen();
		if (keySet == null) {
			keySet = new HashSet<>(getQueueCapacity());
		}
		if (publisher == null) {
			publisher = publisher();
		}
	}

	@Override
	protected synchronized void doClose() throws Exception {
		if (publisher != null) {
			publisher.close();
			publisher = null;
		}
		keySet = null;
		super.doClose();
	}

	private void keySpaceNotification(K channel, V message) {
		addEvent(keyEncoder.apply(suffix(channel)), valueDecoder.apply(message));
	}

	@SuppressWarnings("unchecked")
	private void keyEventNotification(K channel, V message) {
		addEvent((K) message, suffix(channel));
	}

	private void addEvent(K key, String event) {
		if (acceptType(event)) {
			Wrapper<K> wrapper = new Wrapper<>(key);
			if (keySet.contains(wrapper)) {
				notifyListeners(key, event, KeyNotificationStatus.DUPLICATE);
			} else {
				boolean added = queue.offer(key);
				if (added) {
					keySet.add(wrapper);
				} else {
					notifyListeners(key, event, KeyNotificationStatus.QUEUE_FULL);
				}
			}
		} else {
			notifyListeners(key, event, KeyNotificationStatus.KEY_TYPE);
		}
	}

	private void notifyListeners(K key, String event, KeyNotificationStatus status) {
		notificationListeners.forEach(l -> l.event(key, event, status));
	}

	private boolean acceptType(String event) {
		return keyType == null || keyType.equalsIgnoreCase(eventDataType(event).getString());
	}

	private DataType eventDataType(String event) {
		return dataTypeFunction.apply(event);
	}

	private KeyNotificationConsumer<K, V> notificationConsumer() {
		if (isKeyEvents()) {
			return this::keyEventNotification;
		}
		return this::keySpaceNotification;
	}

	private String suffix(K key) {
		String string = keyDecoder.apply(key);
		int index = string.indexOf(SEPARATOR);
		if (index > 0) {
			return string.substring(index + 1);
		}
		return null;
	}

	private AutoCloseable publisher() {
		String pubSubPattern = pubSubPattern();
		K pattern = keyEncoder.apply(pubSubPattern);
		KeyNotificationConsumer<K, V> consumer = notificationConsumer();
		if (client instanceof RedisClusterClient) {
			RedisClusterPubSubListener<K, V> listener = new RedisClusterKeyNotificationListener<>(consumer);
			return new RedisClusterKeyNotificationPublisher<>((RedisClusterClient) client, codec, listener, pattern);
		}
		RedisPubSubListener<K, V> listener = new RedisKeyNotificationListener<>(consumer);
		return new RedisKeyNotificationPublisher<>((RedisClient) client, codec, listener, pattern);
	}

	@Override
	protected K doPoll(long timeout, TimeUnit unit) throws InterruptedException {
		K key = super.doPoll(timeout, unit);
		if (key == null) {
			return null;
		}
		keySet.remove(new Wrapper<>(key));
		return key;
	}

	public int getDatabase() {
		return database;
	}

	public void setDatabase(int database) {
		this.database = database;
	}

	public String getKeyPattern() {
		return keyPattern;
	}

	public void setKeyPattern(String keyPattern) {
		this.keyPattern = keyPattern;
	}

	public String getKeyType() {
		return keyType;
	}

	public void setKeyType(String keyType) {
		this.keyType = keyType;
	}

}