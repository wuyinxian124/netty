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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Simple {@link ChannelPool} implementation which will create new {@link Channel}s if someone tries to acquire
 * a {@link Channel} but none is in the pool atm. No limit on the maximal concurrent {@link Channel}s is enforced.
 *
 * @param <C>   the {@link Channel} type to pool.
 * @param <K>   the {@link ChannelPoolKey} that is used to store and lookup the {@link Channel}s.
 */
public final class SimpleChannelPool<C extends Channel, K extends ChannelPoolKey> implements ChannelPool<C, K> {

    private static final AttributeKey<ChannelPoolKey> KEY = AttributeKey.newInstance("channelPoolKey");
    private final ConcurrentMap<ChannelPoolKey, Deque<C>> pool = PlatformDependent.newConcurrentHashMap();
    private final ChannelPoolHandler<C, K> handler;
    private final ChannelPoolHealthChecker<C, K> healthCheck;
    private final Bootstrap bootstrap;

    public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler<C, K> handler) {
        this(bootstrap, handler, ActiveChannelPoolHealthChecker.<C, K>instance());
    }

    public SimpleChannelPool(Bootstrap bootstrap, final ChannelPoolHandler<C, K> handler,
                             final ChannelPoolHealthChecker<C, K> healthCheck) {
        this.handler = checkNotNull(handler, "handler");
        this.healthCheck = checkNotNull(healthCheck, "healthCheck");
        this.bootstrap = checkNotNull(bootstrap, "bootstrap").clone();
        this.bootstrap.handler(new ChannelInitializer<C>() {
            @SuppressWarnings("unchecked")
            @Override
            protected void initChannel(C ch) throws Exception {
                K key = (K) ch.attr(KEY).get();
                assert key != null;
                handler.channelCreated(ch, key);
            }
        });
    }

    @Override
    public Future<C> acquire(K key) {
        return acquire(key, ImmediateEventExecutor.INSTANCE.<C>newPromise());
    }

    @Override
    public Future<C> acquire(final K key, final Promise<C> promise) {
        checkNotNull(key, "key");
        checkNotNull(promise, "promise");
        try {
            Deque<C> channels = pool.get(key);
            if (channels == null) {
                newChannel(key, promise);
                return promise;
            }

            final C ch = channels.pollLast();
            if (ch == null) {
                newChannel(key, promise);
                return promise;
            }
            Future<Boolean> f = healthCheck.isHealthy(ch, key);
            if (f.isDone()) {
                notifyHealthCheck(f, ch, key, promise);
            } else {
                f.addListener(new GenericFutureListener<Future<? super Boolean>>() {
                    @Override
                    public void operationComplete(Future<? super Boolean> future) throws Exception {
                        notifyHealthCheck(future, ch, key, promise);
                    }
                });
            }
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    private void notifyHealthCheck(Future<? super Boolean> future, C ch, K key, Promise<C> promise) {
        if (future.isSuccess()) {
            handler.channelAcquired(ch, key);
            promise.setSuccess(ch);
        } else {
            ch.close();
            acquire(key, promise);
        }
    }

    private void newChannel(
            final K key, final Promise<C> promise) {
        Bootstrap bs;
        EventLoop loop = key.eventLoop();
        if (loop != null) {
            bs = bootstrap.clone(loop);
        } else {
            bs = bootstrap.clone();
        }
        ChannelFuture f = bs.attr(KEY, key).connect(key.remoteAddress());
        if (f.isDone()) {
            notifyConnect(f, key, promise);
        } else {
            f.addListener(new ChannelFutureListener() {
                @SuppressWarnings("unchecked")
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    notifyConnect(future, key, promise);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyConnect(ChannelFuture future, ChannelPoolKey key, Promise<C> promise) {
        if (future.isSuccess()) {
            C ch = (C) future.channel();
            ch.attr(KEY).set(key);
            promise.setSuccess(ch);
        } else {
            promise.setFailure(future.cause());
        }
    }

    @Override
    public Future<Boolean> release(C channel) {
        return release(channel, ImmediateEventExecutor.INSTANCE.<Boolean>newPromise());
    }

    @SuppressWarnings("unchecked")
    @Override
    public Future<Boolean> release(C channel, Promise<Boolean> promise) {
        checkNotNull(channel, "channel");
        checkNotNull(promise, "promise");
        try {
            K key = (K) channel.attr(KEY).getAndSet(null);
            if (key != null) {
                Deque<C> channels = pool.get(key);
                if (channels == null) {
                    channels = newDeque();
                    Deque<C> old = pool.putIfAbsent(key, channels);
                    if (old != null) {
                        channels = old;
                    }
                }

                if (channels.add(channel)) {
                    handler.channelReleased(channel, key);
                    promise.setSuccess(Boolean.TRUE);
                } else {
                    promise.setSuccess(Boolean.FALSE);
                }
            } else {
                promise.setSuccess(Boolean.FALSE);
            }

        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    private static <C extends Channel> Deque<C> newDeque() {
        if (PlatformDependent.javaVersion() < 7) {
            return new LinkedBlockingDeque<C>();
        } else {
            return new ConcurrentLinkedDeque<C>();
        }
    }
}
