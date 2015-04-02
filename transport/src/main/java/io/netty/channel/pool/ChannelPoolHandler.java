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
import io.netty.util.concurrent.Promise;

/**
 * Handler which is called for various actions done by the {@link ChannelPool}.
 *
 * @param <C>   the {@link Channel} type to pool.
 * @param <K>   the {@link ChannelPoolKey} that is used to store and lookup the {@link Channel}s.
 */
public interface ChannelPoolHandler<C extends Channel, K extends ChannelPoolKey> {
    /**
     * Called once a {@link Channel} was released by calling {@link ChannelPool#release(Channel)} or
     * {@link ChannelPool#release(Channel, Promise)}.
     *
     * @param ch        the {@link Channel}
     * @param key       the {@link ChannelPoolKey} for which the {@link Channel} was released
     */
    void channelReleased(C ch, K key);

    /**
     * Called once a {@link Channel} was acquired by calling {@link ChannelPool#acquire(ChannelPoolKey)} or
     * {@link ChannelPool#acquire(ChannelPoolKey, Promise)}.
     *  @param ch       the {@link Channel}
     * @param key       the {@link ChannelPoolKey} for which the {@link Channel} was acquired
     */
    void channelAcquired(C ch, K key);

    /**
     * Called once a new {@link Channel} is created in the {@link ChannelPool}.
     *
     * @param ch        the {@link Channel}
     * @param key       the {@link ChannelPoolKey} for which the {@link Channel} was requested
     */
    void channelCreated(C ch, K key);
}
