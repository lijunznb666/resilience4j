/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
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


import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.metrics.FixedSizeSlidingWindowMetrics;
import io.github.resilience4j.core.metrics.Metrics;
import io.github.resilience4j.core.metrics.SlidingTimeWindowMetrics;
import io.github.resilience4j.core.metrics.Snapshot;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static io.github.resilience4j.core.metrics.Metrics.Outcome;

class CircuitBreakerMetrics implements CircuitBreaker.Metrics {

    private final Metrics metrics;
    // LJ MARK: 失败率阈值
    private final float failureRateThreshold;
    // LJ MARK: 慢调用阈值
    private final float slowCallRateThreshold;
    // LJ MARK: 慢调用时间阈值 (纳秒级)
    private final long slowCallDurationThresholdInNanos;
    // LJ MARK: 没执行数
    private final LongAdder numberOfNotPermittedCalls;
    private int minimumNumberOfCalls;

    /**
     * LJ MARK: 指标
     * @param slidingWindowSize LJ MARK: 滑动窗口大小，CircuitBreakerConfig 中配置
     * @param slidingWindowType LJ MARK: 滑动窗口类型 有基于计数 和基于时间两种 默认 基于计数
     * @param circuitBreakerConfig LJ MARK: config
     * @param clock LJ MARK: 滑动窗口 为基于时间是可用 取值为 CircuitBreakerState中的clock
     */
    private CircuitBreakerMetrics(int slidingWindowSize,
        CircuitBreakerConfig.SlidingWindowType slidingWindowType,
        CircuitBreakerConfig circuitBreakerConfig,
        Clock clock) {
        if (slidingWindowType == CircuitBreakerConfig.SlidingWindowType.COUNT_BASED) {
            this.metrics = new FixedSizeSlidingWindowMetrics(slidingWindowSize);
            // LJ MARK: 最小请求数 不能超过滑动窗口大小
            this.minimumNumberOfCalls = Math
                .min(circuitBreakerConfig.getMinimumNumberOfCalls(), slidingWindowSize);
        } else {
            this.metrics = new SlidingTimeWindowMetrics(slidingWindowSize, clock);
            this.minimumNumberOfCalls = circuitBreakerConfig.getMinimumNumberOfCalls();
        }
        this.failureRateThreshold = circuitBreakerConfig.getFailureRateThreshold();
        this.slowCallRateThreshold = circuitBreakerConfig.getSlowCallRateThreshold();
        this.slowCallDurationThresholdInNanos = circuitBreakerConfig.getSlowCallDurationThreshold()
            .toNanos();
        this.numberOfNotPermittedCalls = new LongAdder();
    }

    private CircuitBreakerMetrics(int slidingWindowSize,
        CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        this(slidingWindowSize, circuitBreakerConfig.getSlidingWindowType(), circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forClosed(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(circuitBreakerConfig.getSlidingWindowSize(),
            circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forHalfOpen(int permittedNumberOfCallsInHalfOpenState,
        CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(permittedNumberOfCallsInHalfOpenState,
            CircuitBreakerConfig.SlidingWindowType.COUNT_BASED, circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forForcedOpen(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(0, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
            circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forDisabled(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return new CircuitBreakerMetrics(0, CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
            circuitBreakerConfig, clock);
    }

    static CircuitBreakerMetrics forMetricsOnly(CircuitBreakerConfig circuitBreakerConfig, Clock clock) {
        return forClosed(circuitBreakerConfig, clock);
    }

    /**
     * Records a call which was not permitted, because the CircuitBreaker state is OPEN.
     */
    void onCallNotPermitted() {
        numberOfNotPermittedCalls.increment();
    }

    /**
     * Records a successful call and checks if the thresholds are exceeded.
     * LJ MARK: 调用成功
     * @return the result of the check
     */
    public Result onSuccess(long duration, TimeUnit durationUnit) {
        Snapshot snapshot;
        // LJ MARK: 超过慢调用时间阈值 及算做 慢成功 否则成功
        if (durationUnit.toNanos(duration) > slowCallDurationThresholdInNanos) {
            snapshot = metrics.record(duration, durationUnit, Outcome.SLOW_SUCCESS);
        } else {
            snapshot = metrics.record(duration, durationUnit, Outcome.SUCCESS);
        }
        // LJ MARK: 校验失败率或者慢调用率是否超过了阈值
        return checkIfThresholdsExceeded(snapshot);
    }

    /**
     * Records a failed call and checks if the thresholds are exceeded.
     * LJ MARK: 调用失败
     * @return the result of the check
     */
    public Result onError(long duration, TimeUnit durationUnit) {
        Snapshot snapshot;
        // LJ MARK: 超过慢调用时间阈值 及算做 慢失败 否则失败
        if (durationUnit.toNanos(duration) > slowCallDurationThresholdInNanos) {
            snapshot = metrics.record(duration, durationUnit, Outcome.SLOW_ERROR);
        } else {
            snapshot = metrics.record(duration, durationUnit, Outcome.ERROR);
        }
        // LJ MARK: 校验失败率或者慢调用率是否超过了阈值
        return checkIfThresholdsExceeded(snapshot);
    }

    /**
     * Checks if the failure rate is above the threshold or if the slow calls percentage is above
     * the threshold.
     * LJ MARK: 校验失败率或者慢调用率是否超过了阈值
     * @param snapshot a metrics snapshot
     * @return false, if the thresholds haven't been exceeded.
     */
    // LJ MARK 核心内容
    private Result checkIfThresholdsExceeded(Snapshot snapshot) {
        float failureRateInPercentage = getFailureRate(snapshot);
        float slowCallsInPercentage = getSlowCallRate(snapshot);
        // LJ MARK: -1 表示 总请求数低于最小请求阈值 默认始终不熔断
        if (failureRateInPercentage == -1 || slowCallsInPercentage == -1) {
            return Result.BELOW_MINIMUM_CALLS_THRESHOLD;
        }
        // LJ MARK: 失败和慢调用 阈值都满足
        if (failureRateInPercentage >= failureRateThreshold
            && slowCallsInPercentage >= slowCallRateThreshold) {
            return Result.ABOVE_THRESHOLDS;
        }
        if (failureRateInPercentage >= failureRateThreshold) {
            return Result.FAILURE_RATE_ABOVE_THRESHOLDS;
        }

        if (slowCallsInPercentage >= slowCallRateThreshold) {
            return Result.SLOW_CALL_RATE_ABOVE_THRESHOLDS;
        }
        return Result.BELOW_THRESHOLDS;
    }

    private float getSlowCallRate(Snapshot snapshot) {
        int bufferedCalls = snapshot.getTotalNumberOfCalls();
        // LJ MARK: 未达到最小请求数 返回-1
        if (bufferedCalls == 0 || bufferedCalls < minimumNumberOfCalls) {
            return -1.0f;
        }
        return snapshot.getSlowCallRate();
    }

    private float getFailureRate(Snapshot snapshot) {
        int bufferedCalls = snapshot.getTotalNumberOfCalls();
        // LJ MARK: 未达到最小请求数 返回-1
        if (bufferedCalls == 0 || bufferedCalls < minimumNumberOfCalls) {
            return -1.0f;
        }
        return snapshot.getFailureRate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getFailureRate() {
        return getFailureRate(metrics.getSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getSlowCallRate() {
        return getSlowCallRate(metrics.getSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfSuccessfulCalls() {
        return this.metrics.getSnapshot().getNumberOfSuccessfulCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfBufferedCalls() {
        return this.metrics.getSnapshot().getTotalNumberOfCalls();
    }

    @Override
    public int getNumberOfFailedCalls() {
        return this.metrics.getSnapshot().getNumberOfFailedCalls();
    }

    @Override
    public int getNumberOfSlowCalls() {
        return this.metrics.getSnapshot().getTotalNumberOfSlowCalls();
    }

    @Override
    public int getNumberOfSlowSuccessfulCalls() {
        return this.metrics.getSnapshot().getNumberOfSlowSuccessfulCalls();
    }

    @Override
    public int getNumberOfSlowFailedCalls() {
        return this.metrics.getSnapshot().getNumberOfSlowFailedCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumberOfNotPermittedCalls() {
        return this.numberOfNotPermittedCalls.sum();
    }

    enum Result {
        BELOW_THRESHOLDS,
        FAILURE_RATE_ABOVE_THRESHOLDS,
        SLOW_CALL_RATE_ABOVE_THRESHOLDS,
        ABOVE_THRESHOLDS,
        BELOW_MINIMUM_CALLS_THRESHOLD;

        public static boolean hasExceededThresholds(Result result) {
            return hasFailureRateExceededThreshold(result) ||
                hasSlowCallRateExceededThreshold(result);
        }

        public static boolean hasFailureRateExceededThreshold(Result result) {
            return result == ABOVE_THRESHOLDS || result == FAILURE_RATE_ABOVE_THRESHOLDS;
        }

        public static boolean hasSlowCallRateExceededThreshold(Result result) {
            return result == ABOVE_THRESHOLDS || result == SLOW_CALL_RATE_ABOVE_THRESHOLDS;
        }
    }
}
