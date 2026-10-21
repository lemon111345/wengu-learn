package com.wengu.review.fsrs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 回放随机数：按顺序返回事先装入的值，用来让 Java 版 fuzz 使用和 py-fsrs 完全相同的随机数。
 * <p>
 * 多取一个会立即失败，少取一个由 {@link #assertExhausted} 发现，因此也能验证“什么时候调用随机数”与 Python 一致。
 */
final class ReplayRandom implements DoubleSupplier {

    private final Deque<Double> values = new ArrayDeque<>();

    void load(Iterable<Double> next) {
        next.forEach(values::addLast);
    }

    @Override
    public double getAsDouble() {
        if (values.isEmpty()) {
            fail("调用随机数的次数比 py-fsrs 多");
        }
        return values.removeFirst();
    }

    void assertExhausted(String where) {
        assertTrue(values.isEmpty(), where + "：调用随机数的次数比 py-fsrs 少，剩余 " + values.size() + " 个");
    }
}
