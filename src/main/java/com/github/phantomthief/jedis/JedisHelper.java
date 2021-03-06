/**
 *
 */
package com.github.phantomthief.jedis;

import static com.github.phantomthief.tuple.Tuple.tuple;
import static com.github.phantomthief.util.MoreSuppliers.lazy;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.partition;
import static com.google.common.collect.Maps.newHashMapWithExpectedSize;
import static com.google.common.reflect.Reflection.newProxy;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.nanoTime;
import static java.lang.reflect.Proxy.newProxyInstance;
import static java.util.Collections.singleton;
import static java.util.function.Function.identity;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.phantomthief.jedis.OpInterceptor.JedisOpCall;
import com.github.phantomthief.jedis.exception.NoAvailablePoolException;
import com.github.phantomthief.jedis.exception.RethrowException;
import com.github.phantomthief.jedis.exception.UnexpectedJedisConnectionException;
import com.github.phantomthief.tuple.TwoTuple;
import com.github.phantomthief.util.CursorIteratorEx;
import com.github.phantomthief.util.TriFunction;

import redis.clients.jedis.BasicCommands;
import redis.clients.jedis.BinaryJedis;
import redis.clients.jedis.BinaryJedisCommands;
import redis.clients.jedis.BinaryRedisPipeline;
import redis.clients.jedis.BinaryShardedJedis;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCommands;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.PipelineBase;
import redis.clients.jedis.RedisPipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPipeline;
import redis.clients.jedis.ShardedJedisPool;
import redis.clients.jedis.Tuple;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.util.Pool;

/**
 * @author w.vela
 */
public class JedisHelper<J extends Closeable> {

    public static final long SETNX_KEY_NOT_SET = 0L;
    public static final long SETNX_KEY_SET = 1L;
    public static final int LIMIT_INFINITY = -1;
    public static final String POSITIVE_INF = "+inf";
    public static final String NEGATIVE_INF = "-inf";
    public static final String NOT_EXIST = "NX";
    public static final String ALREADY_EXIST = "XX";
    public static final String SECONDS = "EX";
    public static final String MILLISECONDS = "PX";
    private static final Logger logger = LoggerFactory.getLogger(JedisHelper.class);
    private static final int PARTITION_SIZE = 100;

    private static final Object EMPTY_KEY = new Object();

    private final Supplier<Object> poolFactory;
    private final int pipelinePartitionSize;

    private final Class<?> jedisType;
    private final Class<?> binaryJedisType;

    private final Supplier<BasicCommands> basicCommandsSupplier = lazy(this::getBasic0);
    private final Supplier<JedisCommands> jedisCommandsSupplier = lazy(this::get0);
    private final Supplier<BinaryJedisCommands> binaryJedisCommandsSupplier = lazy(
            this::getBinary0);

    private final List<PoolListener<Object>> poolListeners;
    private final List<OpListener<Object>> opListeners;
    private final List<PipelineOpListener<Object, Object>> pipelineOpListeners;

    private final List<OpInterceptor<J, Object>> opInterceptors;

    @SuppressWarnings("unchecked")
    private JedisHelper(Builder<J, Object> builder) {
        this.poolFactory = builder.poolFactory;
        this.pipelinePartitionSize = builder.pipelinePartitionSize;
        this.jedisType = builder.jedisType;
        this.binaryJedisType = builder.binaryJedisType;
        this.poolListeners = builder.poolListeners;
        this.opListeners = builder.opListeners;
        this.pipelineOpListeners = (List) builder.pipelineOpListeners;
        this.opInterceptors = builder.opInterceptors;
    }

    public static String getShardBitKey(long bit, String keyPrefix, int keyHashRange) {
        return keyPrefix + "_" + (bit / keyHashRange);
    }

    public static Map<Long, String> getShardBitKeys(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        Map<Long, String> result = new HashMap<>();
        for (Long bit : bits) {
            result.put(bit, getShardBitKey(bit, keyPrefix, keyHashRange));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Builder<ShardedJedis, ShardedJedisPool>
            newShardedBuilder(Supplier<ShardedJedisPool> poolFactory) {
        Builder<ShardedJedis, ShardedJedisPool> builder = new Builder<>();
        builder.poolFactory = (Supplier) poolFactory;
        builder.jedisType = ShardedJedis.class;
        builder.binaryJedisType = BinaryShardedJedis.class;
        return builder;
    }

    @SuppressWarnings("unchecked")
    public static <T extends JedisPool> Builder<Jedis, T> newBuilder(Supplier<T> poolFactory) {
        Builder<Jedis, T> builder = new Builder<>();
        builder.poolFactory = (Supplier) poolFactory;
        builder.jedisType = Jedis.class;
        builder.binaryJedisType = BinaryJedis.class;
        return builder;
    }

    public void pipeline(Consumer<RedisPipeline> function) {
        pipeline(p -> {
            function.accept(p);
            return null;
        });
    }

    public void binaryPipeline(Consumer<BinaryRedisPipeline> function) {
        binaryPipeline(p -> {
            function.accept(p);
            return null;
        });
    }

    public <V> V pipeline(Function<RedisPipeline, Response<V>> function) {
        return pipeline(singleton(EMPTY_KEY), (p, k) -> function.apply(p)).get(EMPTY_KEY);
    }

    public <V> V binaryPipeline(Function<BinaryRedisPipeline, Response<V>> function) {
        return binaryPipeline(singleton(EMPTY_KEY), (p, k) -> function.apply(p)).get(EMPTY_KEY);
    }

    public <K, V> Map<K, V> pipeline(Iterable<K> keys,
            BiFunction<RedisPipeline, K, Response<V>> function) {
        return pipeline(keys, function, identity());
    }

    public <K, V> Map<K, V> binaryPipeline(Iterable<K> keys,
            BiFunction<BinaryRedisPipeline, K, Response<V>> function) {
        return binaryPipeline(keys, function, identity());
    }

    public <K, V, T> Map<K, T> pipeline(Iterable<K> keys,
            BiFunction<RedisPipeline, K, Response<V>> function, Function<V, T> decoder) {
        return pipeline(keys, function, decoder, true);
    }

    public <K, V, T> Map<K, T> binaryPipeline(Iterable<K> keys,
            BiFunction<BinaryRedisPipeline, K, Response<V>> function, Function<V, T> decoder) {
        return binaryPipeline(keys, function, decoder, true);
    }

    public <K, V, T> Map<K, T> pipeline(Iterable<K> keys,
            BiFunction<RedisPipeline, K, Response<V>> function, Function<V, T> decoder,
            boolean includeNullValue) {
        return pipeline0(keys, function, decoder, includeNullValue, this::generatePipeline);
    }

    public <K, V, T> Map<K, T> binaryPipeline(Iterable<K> keys,
            BiFunction<BinaryRedisPipeline, K, Response<V>> function, Function<V, T> decoder,
            boolean includeNullValue) {
        return pipeline0(keys, function, decoder, includeNullValue, this::generateBinaryPipeline);
    }

    @SuppressWarnings({ "unchecked" })
    private <P1> Map<PipelineOpListener<P1, Object>, Object> fireOnPipelineStarted(Object pool) {
        RuntimeException toThrow = null;
        Map<PipelineOpListener<P1, Object>, Object> map = new LinkedHashMap<>();
        // never use collect, coz value may be null.
        for (PipelineOpListener<Object, Object> pipelineOpListener : pipelineOpListeners) {
            Object value = null;
            try {
                value = pipelineOpListener.onPipelineStarted(pool);
                map.put((PipelineOpListener) pipelineOpListener, value);
            } catch (RethrowException e) {
                if (toThrow == null) {
                    toThrow = e.getThrowable();
                }
            } catch (Throwable e) {
                logger.error("", e);
                map.put((PipelineOpListener) pipelineOpListener, value);
            }
        }
        if (toThrow != null) {
            throw toThrow;
        }
        return map;
    }

    private <K, V, T, P1> Map<K, T> pipeline0(Iterable<K> keys,
            BiFunction<P1, K, Response<V>> function, Function<V, T> decoder,
            boolean includeNullValue,
            TriFunction<Object, J, Map<PipelineOpListener<Object, Object>, Object>, TwoTuple<PipelineBase, P1>> pipelineGenerator) {
        int size;
        if (keys instanceof Collection) {
            size = ((Collection<K>) keys).size();
        } else {
            size = 16;
        }
        Map<K, T> result = newHashMapWithExpectedSize(size);
        if (keys != null) {
            Iterable<List<K>> partition = partition(keys, pipelinePartitionSize);

            for (List<K> list : partition) {
                Object pool = poolFactory.get();
                Map<PipelineOpListener<Object, Object>, Object> started = fireOnPipelineStarted(
                        pool);
                Throwable t = null;
                try (J jedis = getJedis(pool)) {
                    TwoTuple<PipelineBase, P1> tuple = pipelineGenerator.apply(pool, jedis,
                            started);
                    PipelineBase pipeline = tuple.getFirst();
                    P1 p1 = tuple.getSecond();
                    Map<K, Response<V>> thisMap = new HashMap<>(list.size());
                    for (K key : list) {
                        Response<V> apply = function.apply(p1, key);
                        if (apply != null) {
                            thisMap.put(key, apply);
                        }
                    }
                    fireBeforeSync(pool, pipeline, started, t);
                    syncPipeline(pipeline);
                    try {
                        thisMap.forEach((key, value) -> {
                            V rawValue = value.get();
                            if (rawValue != null || includeNullValue) {
                                T apply = decoder.apply(rawValue);
                                result.put(key, apply);
                            }
                        });
                    } catch (JedisConnectionException e) {
                        throw new UnexpectedJedisConnectionException(e);
                    }
                } catch (Throwable e) {
                    t = e;
                } finally {
                    fireAfterSync(pool, started, t);
                }
            }
        }
        return result;
    }

    private void fireBeforeSync(Object pool, PipelineBase pipeline,
            Map<PipelineOpListener<Object, Object>, Object> s, Throwable t) {
        s.forEach((p, v) -> {
            try {
                p.beforeSync(pool, pipeline, v);
            } catch (Throwable e) {
                logger.error("", e);
            }
        });
    }

    private void fireAfterSync(Object pool, Map<PipelineOpListener<Object, Object>, Object> s, Throwable t) {
        RuntimeException toThrow = null;
        for (Entry<PipelineOpListener<Object, Object>, Object> entry : s.entrySet()) {
            PipelineOpListener<Object, Object> p = entry.getKey();
            Object v = entry.getValue();
            try {
                p.afterSync(pool, v, t);
            } catch (RethrowException e) {
                if (toThrow == null) {
                    toThrow = e.getThrowable();
                }
            } catch (Throwable e) {
                logger.error("", e);
            }
        }
        if (toThrow != null) {
            throw toThrow;
        }
    }

    public BasicCommands getBasic() {
        return basicCommandsSupplier.get();
    }

    private BasicCommands getBasic0() {
        return (BasicCommands) newProxyInstance(jedisType.getClassLoader(),
                jedisType.getInterfaces(), new PoolableJedisCommands());
    }

    public JedisCommands get() {
        return jedisCommandsSupplier.get();
    }

    private JedisCommands get0() {
        return (JedisCommands) newProxyInstance(jedisType.getClassLoader(),
                jedisType.getInterfaces(), new PoolableJedisCommands());
    }

    public BinaryJedisCommands getBinary() {
        return binaryJedisCommandsSupplier.get();
    }

    private BinaryJedisCommands getBinary0() {
        return (BinaryJedisCommands) newProxyInstance(binaryJedisType.getClassLoader(),
                binaryJedisType.getInterfaces(), new PoolableJedisCommands());
    }

    private void syncPipeline(PipelineBase pipeline) {
        if (pipeline instanceof Pipeline) {
            ((Pipeline) pipeline).sync();
        } else if (pipeline instanceof ShardedJedisPipeline) {
            ((ShardedJedisPipeline) pipeline).sync();
        }
    }

    @SuppressWarnings("unchecked")
    private J getJedis(Object pool) {
        if (pool instanceof Pool) {
            long borrowedTime = currentTimeMillis();
            long borrowedNanoTime = nanoTime();
            try {
                J resource = ((Pool<J>) pool).getResource();
                firePoolListener(pool, borrowedTime, borrowedNanoTime, null);
                return resource;
            } catch (Throwable e) {
                firePoolListener(pool, borrowedTime, borrowedNanoTime, e);
                throw e;
            }
        } else {
            throw new IllegalArgumentException("invalid pool:" + pool);
        }
    }

    private void firePoolListener(Object pool, long borrowedTime, long borrowedNanoTime, Throwable e) {
        for (PoolListener<Object> poolListener : poolListeners) {
            try {
                poolListener.onPoolBorrowed(pool, borrowedTime, borrowedNanoTime, e);
            } catch (Throwable ex) {
                logger.error("", ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private TwoTuple<PipelineBase, RedisPipeline> generatePipeline(Object pool, J jedis,
            Map<PipelineOpListener<Object, Object>, Object> startPipeline) {
        if (jedis instanceof Jedis) {
            Pipeline pipelined = ((Jedis) jedis).pipelined();
            PipelineBase p = pipelined;
            RedisPipeline p1 = newProxy(RedisPipeline.class,
                    new PipelineListenerHandler<>(pool, p, pipelineOpListeners, startPipeline));
            return tuple(p, p1);
        } else if (jedis instanceof ShardedJedis) {
            ShardedJedisPipeline pipelined = ((ShardedJedis) jedis).pipelined();
            PipelineBase p = pipelined;
            RedisPipeline p1 = newProxy(RedisPipeline.class,
                    new PipelineListenerHandler<>(pool, p, pipelineOpListeners, startPipeline));
            return tuple(p, p1);
        } else {
            throw new IllegalArgumentException("invalid jedis:" + jedis);
        }
    }

    @SuppressWarnings("unchecked")
    private TwoTuple<PipelineBase, BinaryRedisPipeline> generateBinaryPipeline(Object pool, J jedis,
            Map<PipelineOpListener<Object, Object>, Object> startPipeline) {
        if (jedis instanceof Jedis) {
            Pipeline pipelined = ((Jedis) jedis).pipelined();
            PipelineBase p = pipelined;
            BinaryRedisPipeline p1 = newProxy(BinaryRedisPipeline.class,
                    new PipelineListenerHandler<>(pool, p, pipelineOpListeners, startPipeline));
            return tuple(p, p1);
        } else if (jedis instanceof ShardedJedis) {
            ShardedJedisPipeline pipelined = ((ShardedJedis) jedis).pipelined();
            PipelineBase p = pipelined;
            BinaryRedisPipeline p1 = newProxy(BinaryRedisPipeline.class,
                    new PipelineListenerHandler<>(pool, p, pipelineOpListeners, startPipeline));
            return tuple(p, p1);
        } else {
            throw new IllegalArgumentException("invalid jedis:" + jedis);
        }
    }

    public boolean getShardBit(long bit, String keyPrefix, int keyHashRange) {
        return getShardBit(singleton(bit), keyPrefix, keyHashRange).getOrDefault(bit, false);
    }

    public Map<Long, Boolean> getShardBit(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        return pipeline(bits, (p, bit) -> p.getbit(getShardBitKey(bit, keyPrefix, keyHashRange),
                bit % keyHashRange));
    }

    public long getShardBitCount(String keyPrefix, int keyHashRange, long start, long end) {
        return generateKeys(keyPrefix, keyHashRange, start, end).values().stream()
                .mapToLong(get()::bitcount).sum();
    }

    public boolean setShardBit(long bit, String keyPrefix, int keyHashRange) {
        return setShardBit(singleton(bit), keyPrefix, keyHashRange).get(bit);
    }

    public boolean setShardBit(long bit, String keyPrefix, int keyHashRange, boolean value) {
        return setShardBitSet(singleton(bit), keyPrefix, keyHashRange, value).get(bit);
    }

    public Map<Long, Boolean> setShardBitSet(Collection<Long> bits, String keyPrefix,
            int keyHashRange, boolean value) {
        return pipeline(bits, (p, bit) -> p.setbit(getShardBitKey(bit, keyPrefix, keyHashRange),
                bit % keyHashRange, value));
    }

    public Map<Long, Boolean> setShardBit(Collection<Long> bits, String keyPrefix,
            int keyHashRange) {
        return setShardBitSet(bits, keyPrefix, keyHashRange, true);
    }

    public void delShardBit(String keyPrefix, int keyHashRange, long start, long end) {
        Map<Long, String> allKeys = generateKeys(keyPrefix, keyHashRange, start, end);
        allKeys.values().forEach(get()::del);
    }

    public Stream<Long> iterateShardBit(String keyPrefix, int keyHashRange, long start, long end) {
        Map<Long, String> allKeys = generateKeys(keyPrefix, keyHashRange, start, end);
        return allKeys.entrySet().stream().flatMap(this::mapToLong);
    }

    private Map<Long, String> generateKeys(String keyPrefix, int keyHashRange, long start,
            long end) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (long i = start; i <= end; i += keyHashRange) {
            result.put((i / keyHashRange) * keyHashRange, keyPrefix + "_" + (i / keyHashRange));
        }
        return result;
    }

    private Stream<Long> mapToLong(Entry<Long, String> entry) {
        byte[] bytes = getBinary().get(entry.getValue().getBytes());
        List<Long> result = new ArrayList<>();
        if (bytes != null && bytes.length > 0) {
            for (int i = 0; i < (bytes.length * 8); i++) {
                if ((bytes[i / 8] & (1 << (7 - (i % 8)))) != 0) {
                    result.add(entry.getKey() + i);
                }
            }
        }
        return result.stream();
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<String> scan(ScanParams params) {
        // javac cannot infer types...
        return this.<String, String> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).scan(c, params);
            } else if (j instanceof ShardedJedis) {
                throw new UnsupportedOperationException();
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    public Stream<Entry<String, String>> hscan(String key) {
        return hscan(key, new ScanParams());
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<Entry<String, String>> hscan(String key, ScanParams params) {
        // javac cannot infer types...
        return this.<String, Entry<String, String>> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).hscan(key, c, params);
            } else if (j instanceof ShardedJedis) {
                return ((ShardedJedis) j).hscan(key, c, params);
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    public Stream<Tuple> zscan(String key) {
        return zscan(key, new ScanParams());
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<Tuple> zscan(String key, ScanParams params) {
        // javac cannot infer types...
        return this.<String, Tuple> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).zscan(key, c, params);
            } else if (j instanceof ShardedJedis) {
                return ((ShardedJedis) j).zscan(key, c, params);
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    public Stream<String> sscan(String key) {
        return sscan(key, new ScanParams());
    }

    @SuppressWarnings("RedundantTypeArguments")
    public Stream<String> sscan(String key, ScanParams params) {
        // javac cannot infer types...
        return this.<String, String> scan((j, c) -> {
            if (j instanceof Jedis) {
                return ((Jedis) j).sscan(key, c, params);
            } else if (j instanceof ShardedJedis) {
                return ((ShardedJedis) j).sscan(key, c, params);
            } else {
                throw new UnsupportedOperationException();
            }
        }, ScanResult::getStringCursor, "0").stream();
    }

    private <K, R> CursorIteratorEx<R, K, ScanResult<R>> scan(
            BiFunction<J, K, ScanResult<R>> scanFunction,
            Function<ScanResult<R>, K> cursorExtractor, K initCursor) {
        return CursorIteratorEx.newBuilder()
                .withDataRetriever((K cursor) -> {
                    Object pool = poolFactory.get();
                    try (J jedis = getJedis(pool)) {
                        return scanFunction.apply(jedis, cursor);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .withCursorExtractor(cursorExtractor)
                .withDataExtractor((ScanResult<R> s) -> s.getResult().iterator())
                .withEndChecker(s -> "0".equals(s) || s == null)
                .withInitCursor(initCursor)
                .build();
    }

    public static final class Builder<J extends Closeable, O> {

        private Supplier<Object> poolFactory;
        private int pipelinePartitionSize;

        private Class<?> jedisType;
        private Class<?> binaryJedisType;

        private List<PoolListener<O>> poolListeners = new ArrayList<>();
        private List<OpListener<O>> opListeners = new ArrayList<>();
        private List<PipelineOpListener<O, ?>> pipelineOpListeners = new ArrayList<>();

        private List<OpInterceptor<J, O>> opInterceptors = new ArrayList<>();

        @CheckReturnValue
        @Nonnull
        public Builder<J, O> withPipelinePartitionSize(int size) {
            this.pipelinePartitionSize = size;
            return this;
        }

        @CheckReturnValue
        @Nonnull
        public Builder<J, O> addPoolListener(@Nonnull PoolListener<O> poolListener) {
            this.poolListeners.add(checkNotNull(poolListener));
            return this;
        }

        /**
         * @param op interceptors will be called as adding sequence.
         */
        @CheckReturnValue
        @Nonnull
        public Builder<J, O> addOpInterceptor(@Nonnull OpInterceptor<J, O> op) {
            this.opInterceptors.add(checkNotNull(op));
            return this;
        }

        @CheckReturnValue
        @Nonnull
        public Builder<J, O> addOpListener(@Nonnull OpListener<O> op) {
            this.opListeners.add(checkNotNull(op));
            return this;
        }

        @CheckReturnValue
        @Nonnull
        public Builder<J, O> addPipelineOpListener(@Nonnull PipelineOpListener<O, ?> op) {
            this.pipelineOpListeners.add(checkNotNull(op));
            return this;
        }

        @SuppressWarnings("unchecked")
        @Nonnull
        public JedisHelper<J> build() {
            ensure();
            return new JedisHelper(this);
        }

        private void ensure() {
            if (pipelinePartitionSize <= 0) {
                pipelinePartitionSize = PARTITION_SIZE;
            }
        }
    }

    private final class PoolableJedisCommands implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            long start = currentTimeMillis();
            long startNano = nanoTime();
            Object pool = poolFactory.get();
            if (pool == null) {
                NoAvailablePoolException exception = new NoAvailablePoolException();
                long costInNano = nanoTime() - startNano;
                for (OpListener<Object> opListener : opListeners) {
                    opListener.onComplete(null, start, startNano, method, args, costInNano, exception);
                }
                throw exception;
            }
            Throwable t = null;
            try (J jedis = getJedis(pool)) {
                J thisJedis = jedis;
                for (OpInterceptor<J, Object> opInterceptor : opInterceptors) {
                    JedisOpCall<J> call = opInterceptor.interceptCall(pool, method, jedis, args);
                    if (call == null) {
                        continue;
                    }
                    method = call.getMethod();
                    thisJedis = call.getJedis();
                    args = call.getArgs();

                    if (call.hasFinalObject()) {
                        return call.getFinalObject();
                    }
                }
                return method.invoke(thisJedis, args);
            } catch (Throwable e) {
                if (e instanceof InvocationTargetException) {
                    t = ((InvocationTargetException) e).getTargetException();
                } else {
                    t = e;
                }
                throw e;
            } finally {
                long costInNano = nanoTime() - startNano;
                for (OpListener<Object> opListener : opListeners) {
                    try {
                        opListener.onComplete(pool, start, startNano, method, args, costInNano, t);
                    } catch (Throwable e) {
                        logger.error("", e);
                    }
                }
            }
        }
    }
}
