package com.wengu.review.fsrs;

/**
 * 卡片的调度状态，与 py-fsrs 的 State 一致（1～3）。
 * <p>
 * FSRS-6 没有单独的“新卡”状态：新卡也是 LEARNING，用 {@link Card#isNew()} 判断。
 */
public enum State {

    /** 学习中：新卡，或正在走学习步。 */
    LEARNING(1),
    /** 复习：按天安排的正常复习。 */
    REVIEW(2),
    /** 重学：复习时忘了，正在走重学步。 */
    RELEARNING(3);

    private final int value;

    State(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static State of(int value) {
        for (State state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知状态: " + value);
    }
}
