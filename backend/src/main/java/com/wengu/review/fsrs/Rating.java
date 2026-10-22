package com.wengu.review.fsrs;

/**
 * 复习评分，对应 PRD 中的“忘了 / 困难 / 良好 / 简单”。
 * <p>
 * value 与 py-fsrs 的 Rating 一致（1～4），算法公式里直接用到这个数值。
 */
public enum Rating {

    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4);

    private final int value;

    Rating(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static Rating of(int value) {
        for (Rating rating : values()) {
            if (rating.value == value) {
                return rating;
            }
        }
        throw new IllegalArgumentException("未知评分: " + value);
    }
}
