package com.wengu.chat.experiment;

import java.util.List;

/**
 * 模型给出的评分建议。字段名即 JSON 中的字段名，含义见 prompts/rating-suggestion-*.md。
 * <p>
 * Spring AI 生成的 JSON Schema 按字段名字母顺序排列，模型也按这个顺序输出。判断依据命名为 analysis，
 * 排在 rating 前面，模型先写依据、再给结论（v1 叫 reason，排在 rating 后面，依据成了事后的解释）。
 * 实验结束后（#20）再移到 chat 的正式代码中。
 *
 * @param analysis   判断依据
 * @param rating     建议的评分
 * @param weakPoints 学生还没掌握的要点，复习时针对它们提问（#21）；没有时为空列表
 */
record RatingSuggestion(String analysis, Rating rating, List<String> weakPoints) {

    /** FSRS 的四档评分，number 与 FSRS 的 Rating 取值一致（1～4）。 */
    enum Rating {
        AGAIN(1, "忘了"),
        HARD(2, "困难"),
        GOOD(3, "良好"),
        EASY(4, "简单");

        final int number;
        final String label;

        Rating(int number, String label) {
            this.number = number;
            this.label = label;
        }

        static Rating ofNumber(int number) {
            for (Rating r : values()) {
                if (r.number == number) {
                    return r;
                }
            }
            throw new IllegalArgumentException("评分只能是 1～4：" + number);
        }

        /** 显示用，如“GOOD（良好）”。不覆盖 toString，避免影响 JSON 的序列化。 */
        String display() {
            return name() + "（" + label + "）";
        }
    }
}
