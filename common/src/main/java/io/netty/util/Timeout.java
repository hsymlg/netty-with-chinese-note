/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util;

/**
 * A handle associated with a {@link TimerTask} that is returned by a
 * {@link Timer}.
 */
public interface Timeout {

    /**
     * Returns the {@link Timer} that created this handle.
     * 返回创建此句柄的{@link Timer}。
     */
    Timer timer();

    /**
     * Returns the {@link TimerTask} which is associated with this handle.
     * 返回与此句柄关联的{@link TimerTask}。
     */
    TimerTask task();

    /**
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been expired.
     * 当且仅当与此句柄关联的{@link TimerTask}已过期时返回 true
     */
    boolean isExpired();

    /**
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been cancelled.
     * 当且仅当与此句柄关联的{@link TimerTask}已被取消时返回 true
     */
    boolean isCancelled();

    /**
     * Attempts to cancel the {@link TimerTask} associated with this handle.
     * If the task has been executed or cancelled already, it will return with
     * no side effect.
     * 当且仅当与此句柄关联的{@link TimerTask}已过期时返回 true
     * @return True if the cancellation completed successfully, otherwise false
     */
    boolean cancel();
}
