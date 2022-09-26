/*
 *
 *  Copyright 2019 Robert Winkler and Bohdan Storozhuk
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
package io.github.resilience4j.core.metrics;


import java.util.concurrent.TimeUnit;

/**
 * A {@link Metrics} implementation is backed by a sliding window that aggregates only the last
 * {@code N} calls.
 * <p>
 * The sliding window is implemented with a circular array of {@code N} measurements. If the time
 * window size is 10, the circular array has always 10 measurements.
 * <p>
 * The sliding window incrementally updates a total aggregation. The total aggregation is updated
 * incrementally when a new call outcome is recorded. When the oldest measurement is evicted, the
 * measurement is subtracted from the total aggregation. (Subtract-on-Evict)
 * <p>
 * The time to retrieve a Snapshot is constant 0(1), since the Snapshot is pre-aggregated and is
 * independent of the window size. The space requirement (memory consumption) of this implementation
 * should be O(n).
 */
// LJ MARK: 基于计数的滑动窗口算法 获取指标时间复杂度 O(1)(快照预先聚合 totalAggregation) 空间复杂度 O(n)
public class FixedSizeSlidingWindowMetrics implements Metrics {
    // LJ MARK: 滑动窗口大小
    private final int windowSize;
    // LJ MARK: 指标聚合信息   包含 总执行时间，慢调用数，慢调用失败数，失败数，调用数
    private final TotalAggregation totalAggregation;
    // LJ MARK: 度量数组
    private final Measurement[] measurements;
    // LJ MARK: 当前滑动窗口位置
    int headIndex;

    /**
     * Creates a new {@link FixedSizeSlidingWindowMetrics} with the given window size.
     *
     * @param windowSize the window size
     */
    public FixedSizeSlidingWindowMetrics(int windowSize) {
        this.windowSize = windowSize;
        this.measurements = new Measurement[this.windowSize];
        this.headIndex = 0;
        for (int i = 0; i < this.windowSize; i++) {
            measurements[i] = new Measurement();
        }
        this.totalAggregation = new TotalAggregation();
    }

    // LJ MARK: 加锁 保证线程安全
    @Override
    public synchronized Snapshot record(long duration, TimeUnit durationUnit, Outcome outcome) {
        totalAggregation.record(duration, durationUnit, outcome);
        moveWindowByOne().record(duration, durationUnit, outcome);
        return new SnapshotImpl(totalAggregation);
    }

    // LJ MARK: 加锁 保证线程安全
    public synchronized Snapshot getSnapshot() {
        return new SnapshotImpl(totalAggregation);
    }

    private Measurement moveWindowByOne() {
        // LJ MARK: 前进一位
        moveHeadIndexByOne();
        // LJ MARK: 获取旧指标信息
        Measurement latestMeasurement = getLatestMeasurement();
        // LJ MARK: 从总聚合中减去旧指标
        totalAggregation.removeBucket(latestMeasurement);
        // LJ MARK: 重置当前指标
        latestMeasurement.reset();
        return latestMeasurement;
    }

    /**
     * Returns the head partial aggregation of the circular array.
     *
     * @return the head partial aggregation of the circular array
     */
    private Measurement getLatestMeasurement() {
        return measurements[headIndex];
    }

    /**
     * Moves the headIndex to the next bucket.
     */
    void moveHeadIndexByOne() {
        this.headIndex = (headIndex + 1) % windowSize;
    }
}