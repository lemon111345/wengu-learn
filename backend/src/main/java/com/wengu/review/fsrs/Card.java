package com.wengu.review.fsrs;

import java.time.Instant;

/**
 * 一张卡片的调度状态（不可变）。每次复习由 {@link Scheduler#review} 返回一张新卡片。
 * <p>
 * 字段与 py-fsrs 的 Card 对应，但不带 card_id：卡片与知识点的关联由 review 模块按 kp_id 维护。
 *
 * @param state      调度状态
 * @param step       当前处于第几个学习步或重学步；REVIEW 状态下为 null
 * @param stability  稳定性（天），新卡为 null
 * @param difficulty 难度（1～10），新卡为 null
 * @param due        下次到期时间（UTC）
 * @param lastReview 上次复习时间（UTC），新卡为 null
 */
public record Card(State state, Integer step, Double stability, Double difficulty, Instant due, Instant lastReview) {

    /** 创建一张新卡，立即到期。 */
    public static Card newCard(Instant now) {
        return new Card(State.LEARNING, 0, null, null, now, null);
    }

    /** 是否为新卡：从未复习过。 */
    public boolean isNew() {
        return lastReview == null;
    }
}
