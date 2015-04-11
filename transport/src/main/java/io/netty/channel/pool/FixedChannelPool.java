/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.pool;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * {@link ChannelPool} implementation that takes another {@link ChannelPool} implementation and enfore a maximum
 * number of concurrent connections.
 *
 * @param <C>   the {@link Channel} type to pool.
 * @param <K>   the {@link ChannelPoolKey} that is used to store and lookup the {@link Channel}s.
 */
public final class FixedChannelPool<C extends Channel, K extends ChannelPoolKey> implements ChannelPool<C, K> {

    private final AtomicInteger acquiredChannelCount = new AtomicInteger();
    private final Queue<AcquireTask<C, K>> pendingAcquireQueue = new ConcurrentLinkedQueue<AcquireTask<C, K>>();
    private final FutureListener<C> decrementListener = new FutureListener<C>() {
        @Override
        public void operationComplete(Future<C> future) throws Exception {
            if (future.isSuccess()) {
                future.getNow().closeFuture().addListener(closeListener);
            } else {
                acquiredChannelCount.decrementAndGet();
                runTaskQueue();
            }
        }
    };
    private final FutureListener<Boolean> runListener = new FutureListener<Boolean>() {
        @Override
        public void operationComplete(Future<Boolean> future) throws Exception {
            runTaskQueue();
        }
    };
    private final ChannelFutureListener closeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // The channel was closed so try to process the taskqueue again as this means we lost one connection out
            // of the pool.
            runTaskQueue();
        }
    };

    private final ChannelPool<C, K> pool;
    private final int maxConnections;

    public FixedChannelPool(ChannelPool<C, K> pool, int maxConnections) {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be >= 1 but was " + maxConnections);
        }
        this.pool = checkNotNull(pool, "pool");
        this.maxConnections = maxConnections;
    }

    @Override
    public Future<C> acquire(K key) {
        return acquire(key, ImmediateEventExecutor.INSTANCE.<C>newPromise());
    }

    @Override
    public Future<Boolean> release(C channel) {
        return release(channel, ImmediateEventExecutor.INSTANCE.<Boolean>newPromise());
    }

    @Override
    public Future<C> acquire(K key, Promise<C> promise) {
        if (acquiredChannelCount.incrementAndGet() <= maxConnections) {
            promise.addListener(decrementListener);
            return pool.acquire(key, promise);
        }
        pendingAcquireQueue.add(new AcquireTask<C, K>(key, promise));
        return promise;
    }

    @Override
    public Future<Boolean> release(C channel, Promise<Boolean> promise) {
        if (channel.isActive()) {
            Future<Boolean> f = pool.release(channel, promise);
            if (f.isDone()) {
                runTaskQueue();
            } else {
                f.addListener(runListener);
            }
            return f;
        } else {
            return promise.setSuccess(Boolean.FALSE);
        }
    }

    private void runTaskQueue() {
        for (;;) {
            if (acquiredChannelCount.decrementAndGet() <= maxConnections) {
                AcquireTask<C, K> task = pendingAcquireQueue.poll();
                if (task == null) {
                    // increment again as we was not able to poll a task.
                    acquiredChannelCount.incrementAndGet();
                    break;
                }
                Promise<C> promise = task.promise;
                promise.addListener(decrementListener);
                pool.acquire(task.key, promise);
            } else {
                break;
            }
        }
    }

    private static final class AcquireTask<C, K> {
        private final K key;
        private final Promise<C> promise;

        AcquireTask(final K key, Promise<C> promise) {
            this.key = key;
            this.promise = promise;
        }
    }
}
