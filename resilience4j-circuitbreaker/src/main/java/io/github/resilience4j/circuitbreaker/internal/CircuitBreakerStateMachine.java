/*
 *
 *  Copyright 2019 Robert Winkler
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.circuitbreaker.internal;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.ResultRecordedAsFailureException;
import io.github.resilience4j.circuitbreaker.event.*;
import io.github.resilience4j.core.EventConsumer;
import io.github.resilience4j.core.EventProcessor;
import io.github.resilience4j.core.lang.Nullable;
import io.vavr.collection.HashMap;
import io.vavr.collection.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static io.github.resilience4j.circuitbreaker.CircuitBreaker.State.*;
import static io.github.resilience4j.circuitbreaker.internal.CircuitBreakerMetrics.Result;
import static io.github.resilience4j.circuitbreaker.internal.CircuitBreakerMetrics.Result.BELOW_THRESHOLDS;
import static java.time.temporal.ChronoUnit.MILLIS;

/**
 * A CircuitBreaker finite state machine.
 */
public final class CircuitBreakerStateMachine implements CircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakerStateMachine.class);

    private final String name;
    // LJ MARK: 保证状态的原子性，初始状态为关闭状态
    private final AtomicReference<CircuitBreakerState> stateReference;
    private final CircuitBreakerConfig circuitBreakerConfig;
    private final Map<String, String> tags;
    private final CircuitBreakerEventProcessor eventProcessor;
    private final Clock clock;
    private final SchedulerFactory schedulerFactory;
    private final Function<Clock, Long> currentTimestampFunction;
    private final TimeUnit timestampUnit;

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param clock                A Clock which can be mocked in tests.
     * @param schedulerFactory     A SchedulerFactory which can be mocked in tests.
     */
    private CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock, SchedulerFactory schedulerFactory,
        io.vavr.collection.Map<String, String> tags) {
        this.name = name;
        this.circuitBreakerConfig = Objects
            .requireNonNull(circuitBreakerConfig, "Config must not be null");
        // LJ MARK: 熔断器事件处理器
        this.eventProcessor = new CircuitBreakerEventProcessor();
        this.clock = clock;
        // LJ MARK: 初始化state为关闭状态
        this.stateReference = new AtomicReference<>(new ClosedState());
        this.schedulerFactory = schedulerFactory;
        this.tags = Objects.requireNonNull(tags, "Tags must not be null");
        this.currentTimestampFunction = circuitBreakerConfig.getCurrentTimestampFunction();
        this.timestampUnit = circuitBreakerConfig.getTimestampUnit();
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param schedulerFactory     A SchedulerFactory which can be mocked in tests.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        SchedulerFactory schedulerFactory) {
        this(name, circuitBreakerConfig, Clock.systemUTC(), schedulerFactory, HashMap.empty());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock) {
        this(name, circuitBreakerConfig, clock, SchedulerFactory.getInstance(), HashMap.empty());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        Clock clock, io.vavr.collection.Map<String, String> tags) {
        this(name, circuitBreakerConfig, clock, SchedulerFactory.getInstance(), tags);
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig) {
        this(name, circuitBreakerConfig, Clock.systemUTC());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration.
     * @param tags                 Tags to add to the CircuitBreaker.
     */
    public CircuitBreakerStateMachine(String name, CircuitBreakerConfig circuitBreakerConfig,
        io.vavr.collection.Map<String, String> tags) {
        this(name, circuitBreakerConfig, Clock.systemUTC(), tags);
    }

    /**
     * Creates a circuitBreaker with default config.
     *
     * @param name the name of the CircuitBreaker
     */
    public CircuitBreakerStateMachine(String name) {
        this(name, CircuitBreakerConfig.ofDefaults());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration supplier.
     */
    public CircuitBreakerStateMachine(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfig) {
        this(name, circuitBreakerConfig.get());
    }

    /**
     * Creates a circuitBreaker.
     *
     * @param name                 the name of the CircuitBreaker
     * @param circuitBreakerConfig The CircuitBreaker configuration supplier.
     */
    public CircuitBreakerStateMachine(String name,
        Supplier<CircuitBreakerConfig> circuitBreakerConfig,
        io.vavr.collection.Map<String, String> tags) {
        this(name, circuitBreakerConfig.get(), tags);
    }

    @Override
    public long getCurrentTimestamp() {
        return this.currentTimestampFunction.apply(clock);
    }

    @Override
    public TimeUnit getTimestampUnit() {
        return timestampUnit;
    }

    @Override
    public boolean tryAcquirePermission() {
        boolean callPermitted = stateReference.get().tryAcquirePermission();
        if (!callPermitted) {
            publishCallNotPermittedEvent();
        }
        return callPermitted;
    }

    @Override
    public void releasePermission() {
        stateReference.get().releasePermission();
    }

    @Override
    public void acquirePermission() {
        try {
            stateReference.get().acquirePermission();
        } catch (Exception e) {
            publishCallNotPermittedEvent();
            throw e;
        }
    }

    @Override
    public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
        // Handle the case if the completable future throws a CompletionException wrapping the original exception
        // where original exception is the the one to retry not the CompletionException.
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            Throwable cause = throwable.getCause();
            handleThrowable(duration, durationUnit, cause);
        } else {
            handleThrowable(duration, durationUnit, throwable);
        }
    }

    private void handleThrowable(long duration, TimeUnit durationUnit, Throwable throwable) {
        if (circuitBreakerConfig.getIgnoreExceptionPredicate().test(throwable)) {
            LOG.debug("CircuitBreaker '{}' ignored an exception:", name, throwable);
            releasePermission();
            publishCircuitIgnoredErrorEvent(name, duration, durationUnit, throwable);
        } else if (circuitBreakerConfig.getRecordExceptionPredicate().test(throwable)) {
            LOG.debug("CircuitBreaker '{}' recorded an exception as failure:", name, throwable);
            publishCircuitErrorEvent(name, duration, durationUnit, throwable);
            stateReference.get().onError(duration, durationUnit, throwable);
        } else {
            LOG.debug("CircuitBreaker '{}' recorded an exception as success:", name, throwable);
            publishSuccessEvent(duration, durationUnit);
            stateReference.get().onSuccess(duration, durationUnit);
        }
    }

    @Override
    public void onSuccess(long duration, TimeUnit durationUnit) {
        LOG.debug("CircuitBreaker '{}' succeeded:", name);
        publishSuccessEvent(duration, durationUnit);
        stateReference.get().onSuccess(duration, durationUnit);
    }

    @Override
    public void onResult(long duration, TimeUnit durationUnit, @Nullable Object result) {
        if (result != null && circuitBreakerConfig.getRecordResultPredicate().test(result)) {
            LOG.debug("CircuitBreaker '{}' recorded a result type '{}' as failure:", name, result.getClass());
            ResultRecordedAsFailureException failure = new ResultRecordedAsFailureException(name, result);
            publishCircuitErrorEvent(name, duration, durationUnit, failure);
            stateReference.get().onError(duration, durationUnit, failure);
        } else {
            onSuccess(duration, durationUnit);
        }
    }

    /**
     * Get the state of this CircuitBreaker.
     *
     * @return the the state of this CircuitBreaker
     */
    @Override
    public State getState() {
        return this.stateReference.get().getState();
    }

    /**
     * Get the name of this CircuitBreaker.
     *
     * @return the the name of this CircuitBreaker
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * Get the config of this CircuitBreaker.
     *
     * @return the config of this CircuitBreaker
     */
    @Override
    public CircuitBreakerConfig getCircuitBreakerConfig() {
        return circuitBreakerConfig;
    }

    @Override
    public Metrics getMetrics() {
        return this.stateReference.get().getMetrics();
    }

    @Override
    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return String.format("CircuitBreaker '%s'", this.name);
    }

    @Override
    public void reset() {
        CircuitBreakerState previousState = stateReference
            .getAndUpdate(currentState -> new ClosedState());
        if (previousState.getState() != CLOSED) {
            publishStateTransitionEvent(
                StateTransition.transitionBetween(getName(), previousState.getState(), CLOSED));
        }
        publishResetEvent();
    }

    // LJ MARK: 状态转换核心方法
    private void stateTransition(State newState,
        UnaryOperator<CircuitBreakerState> newStateGenerator) {
        // LJ MARK: 先获取当前State然后执行状态更新方法，AtomicReference保证原子性
        CircuitBreakerState previousState = stateReference.getAndUpdate(currentState -> {
            // LJ MARK: StateTransition 定义了可以从 A状态到B状态的枚举, 如果不存在该case，transitionBetween方法将会抛出异常
            StateTransition.transitionBetween(getName(), currentState.getState(), newState);
            currentState.preTransitionHook();
            return newStateGenerator.apply(currentState);
        });
        // LJ MARK: 发布状态转换事件
        publishStateTransitionEvent(
            StateTransition.transitionBetween(getName(), previousState.getState(), newState));
    }

    @Override
    public void transitionToDisabledState() {
        stateTransition(DISABLED, currentState -> new DisabledState());
    }

    @Override
    public void transitionToMetricsOnlyState() {
        stateTransition(METRICS_ONLY, currentState -> new MetricsOnlyState());
    }

    @Override
    public void transitionToForcedOpenState() {
        stateTransition(FORCED_OPEN,
            currentState -> new ForcedOpenState(currentState.attempts() + 1));
    }

    @Override
    public void transitionToClosedState() {
        stateTransition(CLOSED, currentState -> new ClosedState());
    }

    @Override
    public void transitionToOpenState() {
        stateTransition(OPEN,
            currentState -> new OpenState(currentState.attempts() + 1, currentState.getMetrics()));
    }

    @Override
    public void transitionToHalfOpenState() {
        stateTransition(HALF_OPEN, currentState -> new HalfOpenState(currentState.attempts()));
    }

    private boolean shouldPublishEvents(CircuitBreakerEvent event) {
        return stateReference.get().shouldPublishEvents(event);
    }

    private void publishEventIfHasConsumer(CircuitBreakerEvent event) {
        if (!eventProcessor.hasConsumers()) {
            LOG.debug("No Consumers: Event {} not published", event.getEventType());
            return;
        }

        publishEvent(event);
    }

    private void publishEvent(CircuitBreakerEvent event) {
        if (shouldPublishEvents(event)) {
            try {
                eventProcessor.consumeEvent(event);
                LOG.debug("Event {} published: {}", event.getEventType(), event);
            } catch (Throwable t) {
                LOG.warn("Failed to handle event {}", event.getEventType(), t);
            }
        } else {
            LOG.debug("Publishing not allowed: Event {} not published", event.getEventType());
        }
    }

    private void publishStateTransitionEvent(final StateTransition stateTransition) {
        if (StateTransition.isInternalTransition(stateTransition)) {
            return;
        }
        publishEventIfHasConsumer(new CircuitBreakerOnStateTransitionEvent(name, stateTransition));
    }

    private void publishResetEvent() {
        publishEventIfHasConsumer(new CircuitBreakerOnResetEvent(name));
    }

    private void publishCallNotPermittedEvent() {
        publishEventIfHasConsumer(new CircuitBreakerOnCallNotPermittedEvent(name));
    }

    private void publishSuccessEvent(final long duration, TimeUnit durationUnit) {
        if (eventProcessor.hasConsumers()) {
            publishEvent(new CircuitBreakerOnSuccessEvent(name, elapsedDuration(duration, durationUnit)));
        }
    }

    private Duration elapsedDuration(final long duration, TimeUnit durationUnit) {
        return Duration.ofNanos(durationUnit.toNanos(duration));
    }

    private void publishCircuitErrorEvent(final String name, final long duration, final TimeUnit durationUnit,
                                          final Throwable throwable) {
        if (eventProcessor.hasConsumers()) {
            final Duration elapsedDuration = elapsedDuration(duration, durationUnit);
            publishEvent(new CircuitBreakerOnErrorEvent(name, elapsedDuration, throwable));
        }
    }

    private void publishCircuitIgnoredErrorEvent(final String name, long duration, final TimeUnit durationUnit,
                                                 final Throwable throwable) {
        final Duration elapsedDuration = elapsedDuration(duration, durationUnit);
        publishEventIfHasConsumer(new CircuitBreakerOnIgnoredErrorEvent(name, elapsedDuration, throwable));
    }

    private void publishCircuitFailureRateExceededEvent(final String name, float failureRate) {
        publishEventIfHasConsumer(new CircuitBreakerOnFailureRateExceededEvent(name, failureRate));
    }

    private void publishCircuitSlowCallRateExceededEvent(final String name, float slowCallRate) {
        publishEventIfHasConsumer(new CircuitBreakerOnSlowCallRateExceededEvent(name, slowCallRate));
    }

    private void publishCircuitThresholdsExceededEvent(final Result result, final CircuitBreakerMetrics metrics) {
        if (Result.hasFailureRateExceededThreshold(result)) {
            publishCircuitFailureRateExceededEvent(getName(), metrics.getFailureRate());
        }

        if (Result.hasSlowCallRateExceededThreshold(result)) {
            publishCircuitSlowCallRateExceededEvent(getName(), metrics.getSlowCallRate());
        }
    }

    @Override
    public EventPublisher getEventPublisher() {
        return eventProcessor;
    }

    // LJ MARK: 熔断器各种状态
    private interface CircuitBreakerState {
        // LJ MARK: 尝试获取执行权限
        boolean tryAcquirePermission();
        // LJ MARK: 获取执行权限
        void acquirePermission();
        // LJ MARK: 释放权限
        void releasePermission();
        // LJ MARK: 执行失败, 记录失败指标, 当达到设定值时,调用状态机触发状态转移(切换) eg: close->open
        void onError(long duration, TimeUnit durationUnit, Throwable throwable);
        // LJ MARK: 执行成功,记录指标,当达到设定值时,调用状态机触发状态转移(切换) eg: open->half open
        void onSuccess(long duration, TimeUnit durationUnit);

        int attempts();
        // LJ MARK: 返回当前熔断器状态
        CircuitBreaker.State getState();
        // LJ MARK: 返回当前熔断器的指标
        CircuitBreakerMetrics getMetrics();

        /**
         * Should the CircuitBreaker in this state publish events
         *
         * @return a boolean signaling if the events should be published
         */
        default boolean shouldPublishEvents(CircuitBreakerEvent event) {
            return event.getEventType().forcePublish || getState().allowPublish;
        }

        /**
         * This method is invoked before transit to other CircuitBreakerState.
         */
        default void preTransitionHook() {
            // noOp
        }
    }

    private class CircuitBreakerEventProcessor extends
        EventProcessor<CircuitBreakerEvent> implements EventConsumer<CircuitBreakerEvent>,
        EventPublisher {

        @Override
        public EventPublisher onSuccess(
            EventConsumer<CircuitBreakerOnSuccessEvent> onSuccessEventConsumer) {
            // LJ MARK: 注册请求成功事件的Consumer
            registerConsumer(CircuitBreakerOnSuccessEvent.class.getName(),
                onSuccessEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onError(
            EventConsumer<CircuitBreakerOnErrorEvent> onErrorEventConsumer) {
            registerConsumer(CircuitBreakerOnErrorEvent.class.getName(),
                onErrorEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onStateTransition(
            EventConsumer<CircuitBreakerOnStateTransitionEvent> onStateTransitionEventConsumer) {
            registerConsumer(CircuitBreakerOnStateTransitionEvent.class.getName(),
                onStateTransitionEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onReset(
            EventConsumer<CircuitBreakerOnResetEvent> onResetEventConsumer) {
            registerConsumer(CircuitBreakerOnResetEvent.class.getName(),
                onResetEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onIgnoredError(
            EventConsumer<CircuitBreakerOnIgnoredErrorEvent> onIgnoredErrorEventConsumer) {
            registerConsumer(CircuitBreakerOnIgnoredErrorEvent.class.getName(),
                onIgnoredErrorEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onCallNotPermitted(
            EventConsumer<CircuitBreakerOnCallNotPermittedEvent> onCallNotPermittedEventConsumer) {
            registerConsumer(CircuitBreakerOnCallNotPermittedEvent.class.getName(),
                onCallNotPermittedEventConsumer);
            return this;
        }

        @Override
        public EventPublisher onFailureRateExceeded(
            EventConsumer<CircuitBreakerOnFailureRateExceededEvent> onFailureRateExceededConsumer) {
            registerConsumer(CircuitBreakerOnFailureRateExceededEvent.class.getName(),
                onFailureRateExceededConsumer);
            return this;
        }

        @Override
        public EventPublisher onSlowCallRateExceeded(
            EventConsumer<CircuitBreakerOnSlowCallRateExceededEvent> onSlowCallRateExceededConsumer) {
            registerConsumer(CircuitBreakerOnSlowCallRateExceededEvent.class.getName(),
                onSlowCallRateExceededConsumer);
            return this;
        }

        @Override
        public void consumeEvent(CircuitBreakerEvent event) {
            // LJ MARK: 调用父类EveentProcess.processEvent 方法
            super.processEvent(event);
        }
    }

    // LJ MARK: 熔断器 关闭状态
    private class ClosedState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isClosed;

        ClosedState() {
            this.circuitBreakerMetrics = CircuitBreakerMetrics.forClosed(getCircuitBreakerConfig(), clock);
            this.isClosed = new AtomicBoolean(true);
        }

        /**
         * Returns always true, because the CircuitBreaker is closed.
         *
         * @return always true, because the CircuitBreaker is closed.
         */
        @Override
        public boolean tryAcquirePermission() {
            return isClosed.get();
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is closed.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Transitions to open state when thresholds have been exceeded.
         * LJ MARK: 是否达到阈值
         * @param result the Result
         */
        private void checkIfThresholdsExceeded(Result result) {
            if (Result.hasExceededThresholds(result)) {
                if (isClosed.compareAndSet(true, false)) {
                    publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
                    // LJ MARK: 转换为开启状态 调用 CircuitBreakerStateMachine 的状态转换方法
                    transitionToOpenState();
                }
            }
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.CLOSED;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    // LJ MARK: 熔断器开启状态
    private class OpenState implements CircuitBreakerState {

        private final int attempts;
        // LJ MARK: 打开状态的持续时间，在配置类CircuitBreakerConfig的实例中已设置
        private final Instant retryAfterWaitDuration;
        // LJ MARK: 打开状态的指标
        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isOpen;

        @Nullable
        private final ScheduledFuture<?> transitionToHalfOpenFuture;

        OpenState(final int attempts, CircuitBreakerMetrics circuitBreakerMetrics) {
            this.attempts = attempts;
            final long waitDurationInMillis = circuitBreakerConfig
                .getWaitIntervalFunctionInOpenState().apply(attempts);
            this.retryAfterWaitDuration = clock.instant().plus(waitDurationInMillis, MILLIS);
            this.circuitBreakerMetrics = circuitBreakerMetrics;
            // LJ MARK: 如果设置了自动由开转换为半开状态 启动一个定时任务 来处理，在状态转换的过程中 该ScheduledFuture 将会被cancel
            if (circuitBreakerConfig.isAutomaticTransitionFromOpenToHalfOpenEnabled()) {
                ScheduledExecutorService scheduledExecutorService = schedulerFactory.getScheduler();
                transitionToHalfOpenFuture = scheduledExecutorService
                    .schedule(this::toHalfOpenState, waitDurationInMillis, TimeUnit.MILLISECONDS);
            } else {
                transitionToHalfOpenFuture = null;
            }
            isOpen = new AtomicBoolean(true);
        }

        /**
         * Returns false, if the wait duration has not elapsed. Returns true, if the wait duration
         * has elapsed and transitions the state machine to HALF_OPEN state.
         *
         * @return false, if the wait duration has not elapsed. true, if the wait duration has
         * elapsed.
         */
        @Override
        public boolean tryAcquirePermission() {
            // Thread-safe
            // LJ MARK: 如果到达了打开状态的持续时间，则触发状态机，由打开状态转换为半开状态，允许执行
            if (clock.instant().isAfter(retryAfterWaitDuration)) {
                // LJ MARK: 转换为半开状态
                toHalfOpenState();
                // Check if the call is allowed to run in HALF_OPEN state after state transition
                // super.tryAcquirePermission() doesn't work right that's why the code is copied
                // LJ MARK: stateReference 此时为HalfOpenState 尝试获取执行权限
                boolean callPermitted = stateReference.get().tryAcquirePermission();
                if (!callPermitted) {
                    // LJ MARK: event 事件
                    publishCallNotPermittedEvent();
                    // LJ MARK: 处理指标
                    circuitBreakerMetrics.onCallNotPermitted();
                }
                return callPermitted;
            }
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            if (!tryAcquirePermission()) {
                throw CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreakerStateMachine.this);
            }
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // Could be called when Thread 1 invokes acquirePermission when the state is CLOSED, but in the meantime another
            // Thread 2 calls onError and the state changes from CLOSED to OPEN before Thread 1 calls onError.
            // But the onError event should still be recorded, even if it happened after the state transition.
            circuitBreakerMetrics.onError(duration, durationUnit);
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // Could be called when Thread 1 invokes acquirePermission when the state is CLOSED, but in the meantime another
            // Thread 2 calls onError and the state changes from CLOSED to OPEN before Thread 1 calls onSuccess.
            // But the onSuccess event should still be recorded, even if it happened after the state transition.
            circuitBreakerMetrics.onSuccess(duration, durationUnit);
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }

        @Override
        public void preTransitionHook() {
            cancelAutomaticTransitionToHalfOpen();
        }

        private synchronized void toHalfOpenState() {
            if (isOpen.compareAndSet(true, false)) {
                transitionToHalfOpenState();
            }
        }

        private void cancelAutomaticTransitionToHalfOpen() {
            if (transitionToHalfOpenFuture != null && !transitionToHalfOpenFuture.isDone()) {
                transitionToHalfOpenFuture.cancel(true);
            }
        }

    }

    // LJ MARK: 熔断器-disable状态
    private class DisabledState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;

        DisabledState() {
            this.circuitBreakerMetrics = CircuitBreakerMetrics
                .forDisabled(getCircuitBreakerConfig(), clock);
        }

        /**
         * Returns always true, because the CircuitBreaker is disabled.
         * LJ MARK: 熔断器在disable状态下，总是允许执行
         * @return always true, because the CircuitBreaker is disabled.
         */
        @Override
        public boolean tryAcquirePermission() {
            return true;
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is disabled.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // noOp
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // noOp
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.DISABLED;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    // LJ MARK: 仅开启指标状态
    private class MetricsOnlyState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final AtomicBoolean isFailureRateExceeded;
        private final AtomicBoolean isSlowCallRateExceeded;

        MetricsOnlyState() {
            circuitBreakerMetrics = CircuitBreakerMetrics
                .forMetricsOnly(getCircuitBreakerConfig(), clock);
            isFailureRateExceeded = new AtomicBoolean(false);
            isSlowCallRateExceeded = new AtomicBoolean(false);
        }

        /**
         * Returns always true, because the CircuitBreaker is always closed in this state.
         *
         * @return always true, because the CircuitBreaker is always closed in this state.
         */
        @Override
        public boolean tryAcquirePermission() {
            return true;
        }

        /**
         * Does not throw an exception, because the CircuitBreaker is always closed in this state.
         */
        @Override
        public void acquirePermission() {
            // noOp
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        private void checkIfThresholdsExceeded(Result result) {
            if (!Result.hasExceededThresholds(result)) {
                return;
            }

            if (shouldPublishFailureRateExceededEvent(result)) {
                publishCircuitFailureRateExceededEvent(getName(), circuitBreakerMetrics.getFailureRate());
            }

            if (shouldPublishSlowCallRateExceededEvent(result)) {
                publishCircuitSlowCallRateExceededEvent(getName(), circuitBreakerMetrics.getSlowCallRate());
            }
        }

        private boolean shouldPublishFailureRateExceededEvent(Result result) {
            return Result.hasFailureRateExceededThreshold(result) &&
                isFailureRateExceeded.compareAndSet(false, true);
        }

        private boolean shouldPublishSlowCallRateExceededEvent(Result result) {
            return Result.hasSlowCallRateExceededThreshold(result) &&
                isSlowCallRateExceeded.compareAndSet(false, true);
        }

        @Override
        public int attempts() {
            return 0;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.METRICS_ONLY;
        }

        /**
         * Get metrics of the CircuitBreaker
         */
        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    // LJ MARK: 强制开启状态
    private class ForcedOpenState implements CircuitBreakerState {

        private final CircuitBreakerMetrics circuitBreakerMetrics;
        private final int attempts;

        ForcedOpenState(int attempts) {
            this.attempts = attempts;
            this.circuitBreakerMetrics = CircuitBreakerMetrics.forForcedOpen(circuitBreakerConfig, clock);
        }

        /**
         * Returns always false, and records the rejected call.
         *
         * @return always false, since the FORCED_OPEN state always denies calls.
         */
        @Override
        public boolean tryAcquirePermission() {
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            circuitBreakerMetrics.onCallNotPermitted();
            throw CallNotPermittedException
                .createCallNotPermittedException(CircuitBreakerStateMachine.this);
        }

        @Override
        public void releasePermission() {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // noOp
        }

        /**
         * Should never be called when tryAcquirePermission returns false.
         */
        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // noOp
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.FORCED_OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }

    // LJ MARK: 半开状态
    private class HalfOpenState implements CircuitBreakerState {
        // LJ MARK: 半开状态下 允许执行数 在CircuitBreakerConfig中配置
        private final AtomicInteger permittedNumberOfCalls;
        // LJ MARK: 是否处于半开状态
        private final AtomicBoolean isHalfOpen;
        private final int attempts;
        private final CircuitBreakerMetrics circuitBreakerMetrics;
        @Nullable
        private final ScheduledFuture<?> transitionToOpenFuture;

        HalfOpenState(int attempts) {
            int permittedNumberOfCallsInHalfOpenState = circuitBreakerConfig
                .getPermittedNumberOfCallsInHalfOpenState();
            this.circuitBreakerMetrics = CircuitBreakerMetrics
                .forHalfOpen(permittedNumberOfCallsInHalfOpenState, getCircuitBreakerConfig(), clock);
            this.permittedNumberOfCalls = new AtomicInteger(permittedNumberOfCallsInHalfOpenState);
            this.isHalfOpen = new AtomicBoolean(true);
            this.attempts = attempts;
            // LJ MARK: 半开状态持续时间 如果大于1 则启用定时任务 将其转换为开启状态
            final long maxWaitDurationInHalfOpenState = circuitBreakerConfig.getMaxWaitDurationInHalfOpenState().toMillis();
            if (maxWaitDurationInHalfOpenState >= 1) {
                ScheduledExecutorService scheduledExecutorService = schedulerFactory.getScheduler();
                transitionToOpenFuture = scheduledExecutorService
                    .schedule(this::toOpenState, maxWaitDurationInHalfOpenState, TimeUnit.MILLISECONDS);
            } else {
                transitionToOpenFuture = null;
            }
        }

        /**
         * Checks if test request is allowed.
         * <p>
         * Returns true, if test request counter is not zero. Returns false, if test request counter
         * is zero.
         *
         * @return true, if test request counter is not zero.
         */
        @Override
        public boolean tryAcquirePermission() {
            // LJ MARK: 半开状态下允许执行数 是否还够额
            if (permittedNumberOfCalls.getAndUpdate(current -> current == 0 ? current : --current)
                > 0) {
                return true;
            }
            circuitBreakerMetrics.onCallNotPermitted();
            return false;
        }

        @Override
        public void acquirePermission() {
            if (!tryAcquirePermission()) {
                throw CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreakerStateMachine.this);
            }
        }

        @Override
        public void preTransitionHook() {
            cancelAutomaticTransitionToOpen();
        }

        private void cancelAutomaticTransitionToOpen() {
            if (transitionToOpenFuture != null && !transitionToOpenFuture.isDone()) {
                transitionToOpenFuture.cancel(true);
            }
        }

        private void toOpenState() {
            if (isHalfOpen.compareAndSet(true, false)) {
                transitionToOpenState();
            }
        }

        @Override
        public void releasePermission() {
            permittedNumberOfCalls.incrementAndGet();
        }

        @Override
        public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
        }

        @Override
        public void onSuccess(long duration, TimeUnit durationUnit) {
            // CircuitBreakerMetrics is thread-safe
            checkIfThresholdsExceeded(circuitBreakerMetrics.onSuccess(duration, durationUnit));
        }

        @Override
        public int attempts() {
            return attempts;
        }

        /**
         * Transitions to open state when thresholds have been exceeded. Transitions to closed state
         * when thresholds have not been exceeded.
         *
         * @param result the result
         */
        private void checkIfThresholdsExceeded(Result result) {
            // LJ MARK: 如果失败率大于等于阈值 则转换为开启状态
            if (Result.hasExceededThresholds(result)) {
                if (isHalfOpen.compareAndSet(true, false)) {
                    transitionToOpenState();
                }
            }
            // LJ MARK: 失败率小于阈值 则转换为关闭状态
            if (result == BELOW_THRESHOLDS) {
                if (isHalfOpen.compareAndSet(true, false)) {
                    transitionToClosedState();
                }
            }
        }

        /**
         * Get the state of the CircuitBreaker
         */
        @Override
        public CircuitBreaker.State getState() {
            return CircuitBreaker.State.HALF_OPEN;
        }

        @Override
        public CircuitBreakerMetrics getMetrics() {
            return circuitBreakerMetrics;
        }
    }
}
